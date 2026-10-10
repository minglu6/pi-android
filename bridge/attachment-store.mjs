import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {createHash, randomBytes} from 'node:crypto';

export const MAX_CHUNK_BYTES = 256 * 1024;
export const MAX_ATTACHMENTS = 8;
export const IMAGE_MIMES = new Set(['image/png', 'image/jpeg', 'image/gif', 'image/webp']);
const ID = /^[a-f0-9]{48}$/;
const SHA = /^[a-f0-9]{64}$/;
const NOFOLLOW = fs.constants.O_NOFOLLOW;

function positiveInteger(value, fallback, label) {
  const n = value === undefined ? fallback : Number(value);
  if (!Number.isSafeInteger(n) || n <= 0) throw new Error(`${label} 必须是正整数`);
  return n;
}
export function sanitizeName(name) {
  if (typeof name !== 'string' || !name.trim() || name.length > 4096) throw new Error('附件名称无效');
  const base = path.posix.basename(name.replace(/\\/g, '/'))
    .replace(/[\p{Cc}\p{Cf}<>:"|?*]/gu, '_').trim().slice(0, 200);
  return !base || /^\.+$/.test(base) ? 'attachment' : base;
}
function descriptor(params, maxSize) {
  const {mimeType, size, kind} = params;
  const name = sanitizeName(params.name);
  if (typeof mimeType !== 'string' || mimeType.length > 127 ||
      !/^[a-z0-9!#$&^_.+-]+\/[a-z0-9!#$&^_.+-]+$/.test(mimeType)) throw new Error('附件 MIME 无效');
  if (kind !== 'image' && kind !== 'file') throw new Error('附件 kind 必须是 image 或 file');
  if (!Number.isSafeInteger(size) || size < 0 || size > maxSize) throw new Error(`附件 size 超出限制（${maxSize} 字节）`);
  if (kind === 'image' && (!IMAGE_MIMES.has(mimeType) || size === 0)) throw new Error('不支持的图片类型或空图片；支持 PNG/JPEG/GIF/WebP');
  return {name, mimeType, size, kind};
}
export function decodeChunk(data, maxBytes = MAX_CHUNK_BYTES) {
  // Buffer.from(base64) 自身会容忍空白、URL alphabet、错误 padding；必须另行严格验证。
  if (typeof data !== 'string' || data.length === 0 || data.length > 4 * Math.ceil(maxBytes / 3) ||
      !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(data)) {
    throw new Error('chunk 必须是规范 base64 且不超过 maxChunkBytes');
  }
  const bytes = Buffer.from(data, 'base64');
  if (bytes.length > maxBytes || bytes.toString('base64') !== data) throw new Error('chunk base64 非规范或超出限制');
  return bytes;
}
function matchesImageHeader(bytes, mimeType) {
  switch (mimeType) {
    case 'image/png': return bytes.subarray(0, 8).equals(Buffer.from('89504e470d0a1a0a', 'hex'));
    case 'image/jpeg': return bytes.length >= 3 && bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255;
    case 'image/gif': return ['GIF87a', 'GIF89a'].includes(bytes.subarray(0, 6).toString('ascii'));
    case 'image/webp': return bytes.length >= 12 && bytes.subarray(0, 4).toString('ascii') === 'RIFF' && bytes.subarray(8, 12).toString('ascii') === 'WEBP';
    default: return false;
  }
}
function syncDir(dir) {
  const fd = fs.openSync(dir, fs.constants.O_RDONLY | fs.constants.O_DIRECTORY | NOFOLLOW);
  try { fs.fsyncSync(fd); } finally { fs.closeSync(fd); }
}
function privateStat(target, directory = false) {
  const stat = fs.lstatSync(target);
  if (!(directory ? stat.isDirectory() : stat.isFile()) || stat.uid !== process.getuid() ||
      (stat.mode & 0o777) !== (directory ? 0o700 : 0o600) || (!directory && stat.nlink !== 1)) {
    throw new Error('附件存储不是私有普通文件/目录');
  }
  return stat;
}

/** 单 token 连接器的持久附件库。上传只持有一个 <=256KiB chunk，不缓存完整文件。 */
export class AttachmentStore {
  constructor({dir = process.env.PI_ANDROID_UPLOAD_DIR || path.join(os.homedir(), '.pi/agent/uploads/pi-android'),
    maxSize = process.env.PI_ANDROID_UPLOAD_MAX_BYTES,
    timeoutMs = process.env.PI_ANDROID_UPLOAD_TIMEOUT_MS} = {}) {
    this.configuredDir = path.resolve(dir);
    this.maxSize = positiveInteger(maxSize, 50 * 1024 * 1024, 'PI_ANDROID_UPLOAD_MAX_BYTES');
    this.timeoutMs = positiveInteger(timeoutMs, 5 * 60 * 1000, 'PI_ANDROID_UPLOAD_TIMEOUT_MS');
    this.root = null;
    this.partials = new Map();
  }
  init() {
    if (this.root) { privateStat(this.root, true); return; }
    fs.mkdirSync(this.configuredDir, {recursive: true, mode: 0o700});
    privateStat(this.configuredDir, true); // 拒绝 symlink / 非私有目录；不修改已有目录权限。
    this.root = fs.realpathSync(this.configuredDir);
    // 崩溃遗留只回收明确已死进程的 .partial；绝不遍历/删除完成目录。
    for (const name of fs.readdirSync(this.root)) {
      const match = /^\.partial-([1-9][0-9]*)-[a-f0-9]{48}$/.exec(name);
      if (!match) continue;
      try { process.kill(Number(match[1]), 0); } catch (e) {
        if (e.code === 'ESRCH') this.removePartialDir(path.join(this.root, name));
      }
    }
  }
  removePartialDir(dir) {
    // 仅删除自有格式的普通私有文件；异常/未知内容保留供运维检查，不跟随链接。
    try {
      privateStat(dir, true);
      for (const name of fs.readdirSync(dir)) {
        if (!['content', 'meta.json'].includes(name)) return;
        privateStat(path.join(dir, name));
      }
      for (const name of fs.readdirSync(dir)) fs.unlinkSync(path.join(dir, name));
      fs.rmdirSync(dir);
    } catch { /* 异常目录不接管 */ }
  }
  begin(owner, params) {
    const attachment = descriptor(params, this.maxSize);
    if ([...this.partials.values()].filter(p => p.owner === owner).length >= MAX_ATTACHMENTS || this.partials.size >= 64) {
      throw new Error('并行上传数量超出限制');
    }
    this.init();
    const uploadId = randomBytes(24).toString('hex');
    const dir = path.join(this.root, `.partial-${process.pid}-${uploadId}`);
    fs.mkdirSync(dir, {mode: 0o700});
    let fd;
    try {
      fd = fs.openSync(path.join(dir, 'content'), fs.constants.O_WRONLY | fs.constants.O_CREAT | fs.constants.O_EXCL | NOFOLLOW, 0o600);
    } catch (e) { this.removePartialDir(dir); throw e; }
    const partial = {owner, dir, fd, attachment, received: 0, hash: createHash('sha256'), prefix: Buffer.alloc(0), timer: null};
    this.partials.set(uploadId, partial);
    this.touch(uploadId, partial);
    return {uploadId, maxChunkBytes: MAX_CHUNK_BYTES};
  }
  touch(id, p) {
    clearTimeout(p.timer);
    p.timer = setTimeout(() => this.discard(id), this.timeoutMs);
    p.timer.unref();
  }
  owned(owner, uploadId) {
    if (typeof uploadId !== 'string' || !ID.test(uploadId)) throw new Error('uploadId 无效');
    const p = this.partials.get(uploadId);
    if (!p || p.owner !== owner) throw new Error('上传不存在、已完成、已过期或不属于此连接');
    return p;
  }
  chunk(owner, {uploadId, offset, data}) {
    const p = this.owned(owner, uploadId);
    if (!Number.isSafeInteger(offset) || offset !== p.received) throw new Error('chunk offset 必须等于 received');
    const bytes = decodeChunk(data);
    if (bytes.length > p.attachment.size - p.received) throw new Error('chunk 超出声明 size');
    try {
      let written = 0;
      while (written < bytes.length) {
        const count = fs.writeSync(p.fd, bytes, written, bytes.length - written);
        if (!count) throw new Error('附件写入无进展');
        written += count;
      }
      p.hash.update(bytes);
      if (p.prefix.length < 12) p.prefix = Buffer.concat([p.prefix, bytes.subarray(0, 12 - p.prefix.length)]);
      p.received += bytes.length;
      this.touch(uploadId, p);
      return {received: p.received};
    } catch { this.discard(uploadId); throw new Error('附件写入失败，上传已取消'); }
  }
  finish(owner, {uploadId, sha256}) {
    const p = this.owned(owner, uploadId);
    if (typeof sha256 !== 'string' || !SHA.test(sha256)) throw new Error('sha256 必须是小写 64 位十六进制');
    if (p.received !== p.attachment.size) throw new Error('附件字节数与声明 size 不符');
    if (p.hash.copy().digest('hex') !== sha256) {
      this.discard(uploadId);
      throw new Error('附件 SHA256 不符，上传已取消');
    }
    if (p.attachment.kind === 'image' && !matchesImageHeader(p.prefix, p.attachment.mimeType)) {
      this.discard(uploadId);
      throw new Error('图片内容与 MIME 不符或格式不支持，上传已取消');
    }
    const attachment = {id: uploadId, ...p.attachment};
    try {
      fs.fsyncSync(p.fd);
      fs.closeSync(p.fd); p.fd = null;
      const meta = fs.openSync(path.join(p.dir, 'meta.json'), fs.constants.O_WRONLY | fs.constants.O_CREAT | fs.constants.O_EXCL | NOFOLLOW, 0o600);
      try { fs.writeFileSync(meta, JSON.stringify({v: 1, attachment, sha256})); fs.fsyncSync(meta); }
      finally { fs.closeSync(meta); }
      syncDir(p.dir);
      fs.renameSync(p.dir, path.join(this.root, uploadId)); // 原子提交；此后永远不自动删除。
      clearTimeout(p.timer); this.partials.delete(uploadId);
      syncDir(this.root);
      return {attachment};
    } catch { this.discard(uploadId); throw new Error('附件持久提交失败'); }
  }
  discard(id) {
    const p = this.partials.get(id);
    if (!p) return;
    this.partials.delete(id); clearTimeout(p.timer);
    if (p.fd !== null) { try { fs.closeSync(p.fd); } catch { /* 尽力关闭，仍清理 partial */ } }
    this.removePartialDir(p.dir);
  }
  abort(owner, {uploadId}) {
    if (typeof uploadId !== 'string' || !ID.test(uploadId)) throw new Error('uploadId 无效');
    const p = this.partials.get(uploadId);
    if (p) { this.owned(owner, uploadId); this.discard(uploadId); }
    // 幂等：已完成/已清理的 ID 也返回 cancelled，但绝不删完成附件。
    return {cancelled: true};
  }
  disconnect(owner) {
    for (const [id, p] of this.partials) if (p.owner === owner) this.discard(id);
  }
  close() { for (const id of this.partials.keys()) this.discard(id); }
  resolve(id) {
    if (typeof id !== 'string' || !ID.test(id)) throw new Error('attachmentId 无效');
    this.init();
    const dir = path.join(this.root, id);
    privateStat(dir, true);
    const metaPath = path.join(dir, 'meta.json');
    if (privateStat(metaPath).size > 4096) throw new Error('附件元数据无效');
    const meta = JSON.parse(fs.readFileSync(metaPath, {encoding: 'utf8', flag: fs.constants.O_RDONLY | NOFOLLOW}));
    if (meta.v !== 1 || meta.attachment?.id !== id || typeof meta.sha256 !== 'string' || !SHA.test(meta.sha256)) throw new Error('附件元数据无效');
    // 已存文件不因后来降低上传 cap 而不可用。
    const attachment = {id, ...descriptor(meta.attachment, Number.MAX_SAFE_INTEGER)};
    if (attachment.name !== meta.attachment.name) throw new Error('附件元数据名称无效');
    const savedPath = path.join(dir, 'content');
    privateStat(savedPath);
    return {attachment, path: savedPath, sha256: meta.sha256};
  }
  readVerified(item, base64 = false) {
    // 不信任磁盘元数据中的路径；每次读取检查普通文件、大小和 SHA。文件不整块读取。
    privateStat(path.dirname(item.path), true);
    privateStat(item.path);
    const fd = fs.openSync(item.path, fs.constants.O_RDONLY | NOFOLLOW);
    try {
      const stat = fs.fstatSync(fd);
      if (stat.size !== item.attachment.size) throw new Error('附件保存字节数不符');
      const hash = createHash('sha256');
      const bytes = Buffer.alloc(MAX_CHUNK_BYTES);
      const encoded = [];
      let count, total = 0, carry = Buffer.alloc(0), prefix = Buffer.alloc(0);
      while ((count = fs.readSync(fd, bytes, 0, bytes.length, null)) > 0) {
        const part = bytes.subarray(0, count);
        total += count;
        if (total > item.attachment.size) throw new Error('附件保存字节数不符');
        hash.update(part);
        if (prefix.length < 12) prefix = Buffer.concat([prefix, part.subarray(0, 12 - prefix.length)]);
        if (base64) {
          // 即使 readSync 短读也仅在最后一块加 padding，不在图片中间插入 '='。
          const joined = carry.length ? Buffer.concat([carry, part]) : part;
          const complete = joined.length - joined.length % 3;
          encoded.push(joined.subarray(0, complete).toString('base64'));
          carry = Buffer.from(joined.subarray(complete));
        }
      }
      if (total !== item.attachment.size || hash.digest('hex') !== item.sha256) throw new Error('附件保存 SHA256/字节数不符');
      if (item.attachment.kind === 'image' && !matchesImageHeader(prefix, item.attachment.mimeType)) throw new Error('图片内容与 MIME 不符');
      if (base64 && carry.length) encoded.push(carry.toString('base64'));
      return base64 ? encoded.join('') : undefined;
    } finally { fs.closeSync(fd); }
  }
}

export function prepareInlineImages(store, params) {
  if (!Array.isArray(params.images) || params.images.length > MAX_ATTACHMENTS) throw new Error('images 必须是至多 8 张图片的数组');
  const images = params.images.map(image => {
    if (!image || image.type !== 'image' || !IMAGE_MIMES.has(image.mimeType)) throw new Error('不支持的图片类型；支持 PNG/JPEG/GIF/WebP');
    const bytes = decodeChunk(image.data, store.maxSize);
    if (!matchesImageHeader(bytes.subarray(0, 12), image.mimeType)) throw new Error('图片内容与 MIME 不符');
    return {type: 'image', data: image.data, mimeType: image.mimeType};
  });
  if (params.message !== undefined && typeof params.message !== 'string') throw new Error('message 必须是文本');
  return {params: {...params, images, ...(images.length && !params.message?.trim() ? {message: '请查看这些附件。'} : {})},
    imageItems: [], inlineImages: images};
}

export function prepareAttachments(store, params) {
  const {attachmentIds, ...forwarded} = params;
  if (!Array.isArray(attachmentIds) || attachmentIds.length > MAX_ATTACHMENTS ||
      new Set(attachmentIds).size !== attachmentIds.length) throw new Error('attachmentIds 必须是至多 8 个不重复 ID 的数组');
  if (params.images !== undefined && (!Array.isArray(params.images) || params.images.length)) {
    throw new Error('attachmentIds 不可与内联 images 混用');
  }
  if (params.message !== undefined && typeof params.message !== 'string') throw new Error('message 必须是文本');
  const items = attachmentIds.map(id => store.resolve(id));
  if (!items.length) return {params: forwarded, imageItems: []};
  let message = params.message?.trim() ? params.message : '请查看这些附件。';
  const files = items.filter(i => i.attachment.kind === 'file');
  for (const item of files) store.readVerified(item);
  if (files.length) message += '\n\n<pi_attachments_v1>\n' + JSON.stringify(files.map(item => ({
    name: item.attachment.name, path: item.path, mimeType: item.attachment.mimeType,
    size: item.attachment.size, kind: item.attachment.kind,
  }))) + '\n</pi_attachments_v1>';
  return {params: {...forwarded, message}, imageItems: items.filter(i => i.attachment.kind === 'image')};
}

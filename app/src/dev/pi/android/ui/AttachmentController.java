package dev.pi.android.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.AtomicFile;

import android.media.ExifInterface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import dev.pi.android.bridge.BridgeService;

/**
 * 附件草稿与分块上传协调器。
 *
 * <p>草稿落在应用私有目录 {@code files/attachment-drafts}，进程级单例，Activity 重建/换主题不丢。
 * 图片在后台线程做有界解码（ImageDecoder / 采样 BitmapFactory + EXIF 旋正），统一压到 JPEG
 * （有透明像素才保留 PNG），最长边 2048；普通文件原样复制。上传按桥协议
 * upload_begin / upload_chunk / upload_finish 串行分块，严格校验 offset/received/hash；
 * 任何失败只清理远端半成品，本地草稿保留。
 *
 * <p>本类不持有 Activity，不直接操作 UI；状态经 {@link Listener} 在主线程回调。
 */
public final class AttachmentController {

    public static final int MAX_ITEMS = 8;
    public static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
    public static final int CHUNK_BYTES = 256 * 1024;
    private static final int MAX_IMAGE_EDGE = 2048;
    private static final int JPEG_QUALITY = 85;
    private static final int THUMB_EDGE = 192;
    private static final int THUMB_QUALITY = 70;
    private static final long UPLOAD_TIMEOUT_MS = 60_000L;

    public static final class Item {
        public final String id;
        public final String name;
        public final String mimeType;
        public final String kind; // image | file
        public final long size;
        public final String sha256;
        public final String thumbnailBase64;
        public final File file;

        Item(String id, String name, String mimeType, String kind, long size,
             String sha256, String thumbnailBase64, File file) {
            this.id = id;
            this.name = name;
            this.mimeType = mimeType;
            this.kind = kind;
            this.size = size;
            this.sha256 = sha256;
            this.thumbnailBase64 = thumbnailBase64;
            this.file = file;
        }
    }

    public interface Listener {
        void onChanged();
        void onError(String message);
    }

    public interface ReadyCallback {
        void onReady(List<Item> submitted, JSONArray attachmentIds);
        void onError(String message);
    }

    private static AttachmentController instance;

    public static synchronized AttachmentController get(Context context) {
        if (instance == null) instance = new AttachmentController(context.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final File dir;
    private final List<Item> items = new ArrayList<>();
    private final Object lock = new Object();

    private Listener listener;
    private boolean preparing;
    private boolean uploading;
    private String status = "";
    private volatile boolean cancelRequested;
    private BridgeService uploadService;
    private final List<String> pendingRpcIds = new ArrayList<>();
    private final List<String> remoteHandles = new ArrayList<>();
    private ReadyCallback readyCallback;
    private List<Item> uploadBatch;
    private JSONArray uploadedIds;

    private AttachmentController(Context app) {
        this.app = app;
        this.dir = new File(app.getFilesDir(), "attachment-drafts");
        // 草稿恢复放后台，不阻塞首个 UI 帧。
        worker.execute(this::restoreDrafts);
    }

    // ---------- 状态查询（主线程） ----------

    public void setListener(Listener l) {
        synchronized (lock) { listener = l; }
    }

    public void clearListener(Listener l) {
        synchronized (lock) { if (listener == l) listener = null; }
    }

    public List<Item> items() {
        synchronized (lock) { return new ArrayList<>(items); }
    }

    public boolean hasAttachments() {
        synchronized (lock) { return !items.isEmpty(); }
    }

    public boolean isPreparing() {
        synchronized (lock) { return preparing; }
    }

    public boolean isUploading() {
        synchronized (lock) { return uploading; }
    }

    public String status() {
        synchronized (lock) { return status; }
    }

    // ---------- 草稿增删 ----------

    public void addUris(List<Uri> uris, boolean asImages) {
        if (uris == null || uris.isEmpty()) return;
        worker.execute(() -> {
            for (Uri uri : uris) addOne(uri, asImages);
            finishPrepare();
        });
    }

    /** Long paste / @-mention: store plain text as a draft file attachment. */
    public void addTextContent(@NonNull String name, @NonNull String body, @NonNull String mimeType) {
        final String safeName = name == null || name.trim().isEmpty() ? "paste.txt" : name.trim();
        final String text = body == null ? "" : body;
        final String mime = mimeType == null || mimeType.isEmpty() ? "text/plain" : mimeType;
        worker.execute(() -> {
            synchronized (lock) {
                if (items.size() >= MAX_ITEMS) {
                    postError("最多添加 " + MAX_ITEMS + " 个附件");
                    return;
                }
                preparing = true;
                status = "正在准备附件…";
            }
            notifyChanged();
            String tmpId = UUID.randomUUID().toString().replace("-", "");
            File out = null;
            try {
                ensureDir();
                byte[] bytes = text.getBytes("UTF-8");
                if (bytes.length > MAX_FILE_BYTES) {
                    postError("文件超过 50MB：" + safeName);
                    return;
                }
                out = new File(dir, tmpId + ".txt");
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(bytes);
                }
                String sha = sha256(out);
                Item item = new Item(tmpId, safeName, mime, "file", bytes.length, sha, "", out);
                synchronized (lock) {
                    items.add(item);
                    status = "";
                }
                persistMeta();
                notifyChanged();
            } catch (IOException e) {
                postError("附件写入失败");
                cleanup(out);
            } finally {
                finishPrepare();
            }
        });
    }

    private void addOne(Uri uri, boolean asImages) {
        synchronized (lock) {
            if (items.size() >= MAX_ITEMS) {
                postError("最多添加 " + MAX_ITEMS + " 个附件");
                return;
            }
            preparing = true;
            status = "正在准备附件…";
        }
        notifyChanged();
        String tmpId = UUID.randomUUID().toString().replace("-", "");
        File tmp = new File(dir, tmpId + ".part");
        File out = null;
        try {
            ensureDir();
            String mime = app.getContentResolver().getType(uri);
            String name = displayName(uri);
            if (mime == null || mime.isEmpty()) mime = guessMime(name);
            boolean image = asImages || (mime != null && mime.startsWith("image/"));
            long size;
            String finalMime = mime == null ? "application/octet-stream" : mime;
            if (image) {
                ImageResult normalized = normalizeImage(uri);
                if (normalized == null) {
                    postError("图片无法解码：" + name);
                    cleanup(tmp);
                    return;
                }
                out = new File(dir, tmpId + (normalized.png ? ".png" : ".jpg"));
                writeFile(out, normalized.bytes);
                size = normalized.bytes.length;
                finalMime = normalized.png ? "image/png" : "image/jpeg";
            } else {
                out = new File(dir, tmpId + ".bin");
                size = copyStream(uri, tmp);
                if (size > MAX_FILE_BYTES) {
                    postError("文件超过 50MB：" + name);
                    cleanup(tmp);
                    return;
                }
                if (!tmp.renameTo(out)) throw new IOException("rename failed");
            }
            String sha = sha256(out);
            String thumb = image ? thumbnailBase64(out) : "";
            Item item = new Item(tmpId, name, finalMime, image ? "image" : "file",
                    size, sha, thumb, out);
            synchronized (lock) {
                items.add(item);
                status = "";
            }
            persistMeta();
            notifyChanged();
        } catch (IOException | SecurityException e) {
            postError("附件读取失败，草稿未保存");
            cleanup(tmp);
            if (out != null) cleanup(out);
        } catch (OutOfMemoryError e) {
            postError("图片太大，无法处理");
            cleanup(tmp);
            if (out != null) cleanup(out);
        }
    }

    private void finishPrepare() {
        synchronized (lock) {
            preparing = false;
            if (!isBusyStatusLocked()) status = "";
        }
        notifyChanged();
    }

    private boolean isBusyStatusLocked() {
        return status.startsWith("正在");
    }

    public void remove(String id) {
        worker.execute(() -> {
            Item removed = null;
            synchronized (lock) {
                for (int i = 0; i < items.size(); i++) {
                    if (items.get(i).id.equals(id)) { removed = items.remove(i); break; }
                }
            }
            if (removed != null) {
                cleanup(removed.file);
                persistMeta();
                notifyChanged();
            }
        });
    }

    // ---------- 上传 ----------

    public void upload(BridgeService service, ReadyCallback callback) {
        List<Item> batch;
        synchronized (lock) {
            if (uploading || preparing) {
                main.post(() -> callback.onError("附件还在处理中，请稍候"));
                return;
            }
            if (items.isEmpty()) {
                main.post(() -> callback.onError("没有可上传的附件"));
                return;
            }
            batch = new ArrayList<>(items);
            uploading = true;
            cancelRequested = false;
            uploadService = service;
            readyCallback = callback;
            uploadBatch = batch;
            uploadedIds = new JSONArray();
            remoteHandles.clear();
            status = "正在上传附件 1/" + batch.size() + "…";
        }
        notifyChanged();
        worker.execute(() -> uploadBatch(batch));
    }

    public void cancelUpload() {
        cancelRequested = true;
        List<String> ids;
        BridgeService svc;
        synchronized (lock) {
            ids = new ArrayList<>(pendingRpcIds);
            pendingRpcIds.clear();
            svc = uploadService;
        }
        if (svc != null) for (String id : ids) svc.forgetUploadCallback(id);
    }

    /** prompt 已被 Pi 接受后调用：清掉这批草稿和本地副本。 */
    public void clearAccepted(List<Item> submitted) {
        if (submitted == null || submitted.isEmpty()) return;
        worker.execute(() -> {
            synchronized (lock) {
                items.removeAll(submitted);
            }
            for (Item item : submitted) cleanup(item.file);
            persistMeta();
            notifyChanged();
        });
    }

    private void uploadBatch(List<Item> batch) {
        try {
            for (int i = 0; i < batch.size(); i++) {
                throwIfCancelled();
                Item item = batch.get(i);
                setStatus("正在上传附件 " + (i + 1) + "/" + batch.size() + "…");
                String uploadId = begin(item);
                remoteHandles.add(uploadId);
                streamChunks(item, uploadId);
                JSONObject attachment = finish(item, uploadId);
                String attachmentId = attachment.optString("id");
                if (attachmentId.isEmpty()) throw new IOException("桥未返回附件 ID");
                uploadedIds.put(attachmentId);
            }
            List<Item> submitted = new ArrayList<>(batch);
            JSONArray ids = uploadedIds;
            main.post(() -> {
                synchronized (lock) {
                    uploading = false;
                    status = "";
                    uploadService = null;
                }
                ReadyCallback cb = readyCallback;
                readyCallback = null;
                notifyChanged();
                if (cb != null) cb.onReady(submitted, ids);
            });
        } catch (UploadCancelled cancelled) {
            abortRemote();
            main.post(() -> {
                synchronized (lock) {
                    uploading = false;
                    status = "";
                    uploadService = null;
                }
                ReadyCallback cb = readyCallback;
                readyCallback = null;
                notifyChanged();
                if (cb != null) cb.onError("已取消上传");
            });
        } catch (Exception e) {
            abortRemote();
            String message = e instanceof UploadException ? e.getMessage() : "附件上传失败，草稿已保留";
            main.post(() -> {
                synchronized (lock) {
                    uploading = false;
                    status = "";
                    uploadService = null;
                }
                ReadyCallback cb = readyCallback;
                readyCallback = null;
                notifyChanged();
                if (cb != null) cb.onError(message);
            });
        }
    }

    private String begin(Item item) throws Exception {
        JSONObject params = new JSONObject();
        params.put("name", item.name);
        params.put("mimeType", item.mimeType);
        params.put("size", item.size);
        params.put("kind", item.kind);
        JSONObject data = call("upload_begin", params);
        String uploadId = data.optString("uploadId");
        long maxChunk = data.optLong("maxChunkBytes", CHUNK_BYTES);
        if (uploadId.isEmpty() || maxChunk < 1024) throw new UploadException("桥拒绝了上传请求");
        return uploadId;
    }

    private void streamChunks(Item item, String uploadId) throws Exception {
        byte[] buffer = new byte[CHUNK_BYTES];
        long offset = 0;
        try (FileInputStream in = new FileInputStream(item.file)) {
            int read;
            while ((read = readFully(in, buffer)) > 0) {
                throwIfCancelled();
                String encoded = Base64.encodeToString(buffer, 0, read, Base64.NO_WRAP);
                JSONObject params = new JSONObject();
                params.put("uploadId", uploadId);
                params.put("offset", offset);
                params.put("data", encoded);
                JSONObject data = call("upload_chunk", params);
                long received = data.optLong("received", -1);
                offset += read;
                if (received != offset) throw new UploadException("上传进度校验失败");
            }
        }
        if (offset != item.size) throw new UploadException("附件大小与声明不一致");
    }

    private JSONObject finish(Item item, String uploadId) throws Exception {
        JSONObject params = new JSONObject();
        params.put("uploadId", uploadId);
        params.put("sha256", item.sha256);
        JSONObject data = call("upload_finish", params);
        JSONObject attachment = data.optJSONObject("attachment");
        if (attachment == null) throw new UploadException("桥未确认附件");
        return attachment;
    }

    private void abortRemote() {
        BridgeService svc;
        List<String> handles;
        synchronized (lock) {
            svc = uploadService;
            handles = new ArrayList<>(remoteHandles);
            remoteHandles.clear();
        }
        if (svc == null || !svc.isConnected()) return;
        for (String handle : handles) {
            try {
                JSONObject params = new JSONObject();
                params.put("uploadId", handle);
                svc.uploadRpc("upload_abort", params, (ok, data, error) -> { });
            } catch (Exception ignored) {
            }
        }
    }

    /** 在 worker 线程等待一次桥响应；超时/断开/取消都转失败，绝不无限挂起。 */
    private JSONObject call(String command, JSONObject params) throws Exception {
        final Object gate = new Object();
        final JSONObject[] box = new JSONObject[1];
        final String[] error = new String[1];
        final boolean[] done = new boolean[1];
        BridgeService svc;
        synchronized (lock) { svc = uploadService; }
        if (svc == null) throw new UploadException("连接已断开，附件草稿已保留");
        String rpcId = svc.uploadRpc(command, params, (ok, data, err) -> {
            synchronized (gate) {
                if (done[0]) return;
                done[0] = true;
                if (ok) box[0] = data != null ? data : new JSONObject();
                else error[0] = err == null || err.isEmpty() ? "上传请求被拒绝" : err;
                gate.notifyAll();
            }
        });
        if (rpcId.isEmpty()) throw new UploadException("连接已断开，附件草稿已保留");
        synchronized (lock) { pendingRpcIds.add(rpcId); }
        long deadline = System.currentTimeMillis() + UPLOAD_TIMEOUT_MS;
        synchronized (gate) {
            while (!done[0]) {
                if (cancelRequested) break;
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) break;
                try {
                    gate.wait(Math.min(remain, 500));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        synchronized (lock) { pendingRpcIds.remove(rpcId); }
        if (!done[0]) {
            svc.forgetUploadCallback(rpcId);
            if (cancelRequested) throw new UploadCancelled();
            throw new UploadException("上传响应超时，附件草稿已保留");
        }
        if (cancelRequested) throw new UploadCancelled();
        if (error[0] != null) throw new UploadException(error[0]);
        return box[0];
    }

    private void throwIfCancelled() throws UploadCancelled {
        if (cancelRequested) throw new UploadCancelled();
    }

    private void setStatus(String value) {
        synchronized (lock) { status = value; }
        notifyChanged();
    }

    // ---------- 图片处理 ----------

    private static final class ImageResult {
        final byte[] bytes;
        final boolean png;
        ImageResult(byte[] bytes, boolean png) { this.bytes = bytes; this.png = png; }
    }

    @Nullable
    private ImageResult normalizeImage(Uri uri) throws IOException {
        Bitmap bitmap = decodeBounded(uri, MAX_IMAGE_EDGE);
        if (bitmap == null) return null;
        try {
            bitmap = applyExif(uri, bitmap);
            boolean alpha = bitmap.hasAlpha();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (alpha) bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            else bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
            return new ImageResult(out.toByteArray(), alpha);
        } finally {
            bitmap.recycle();
        }
    }

    @Nullable
    private Bitmap decodeBounded(Uri uri, int maxEdge) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = app.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        // 防御恶意尺寸：单边超 20000 或总像素超 1 亿直接拒。
        long w = bounds.outWidth, h = bounds.outHeight;
        if (Math.max(w, h) > 20000 || w * h > 100_000_000L) return null;
        int sample = 1;
        while (Math.max(w / (sample * 2), h / (sample * 2)) >= maxEdge) sample *= 2;
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                ImageDecoder.Source source = ImageDecoder.createSource(app.getContentResolver(), uri);
                final int sampleFinal = sample;
                return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    if (sampleFinal > 1) decoder.setTargetSampleSize(sampleFinal);
                });
            } catch (IOException e) {
                // fall through to BitmapFactory
            }
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream in = app.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            Bitmap decoded = BitmapFactory.decodeStream(in, null, opts);
            if (decoded == null) return null;
            return scaleDown(decoded, maxEdge);
        }
    }

    private static Bitmap scaleDown(Bitmap src, int maxEdge) {
        int w = src.getWidth(), h = src.getHeight();
        int max = Math.max(w, h);
        if (max <= maxEdge) return src;
        float scale = (float) maxEdge / max;
        Bitmap scaled = Bitmap.createScaledBitmap(src,
                Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), true);
        if (scaled != src) src.recycle();
        return scaled;
    }

    private Bitmap applyExif(Uri uri, Bitmap bitmap) {
        try (InputStream in = app.getContentResolver().openInputStream(uri)) {
            if (in == null) return bitmap;
            ExifInterface exif = new ExifInterface(in);
            int orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            Matrix matrix = new Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90: matrix.postRotate(90); break;
                case ExifInterface.ORIENTATION_ROTATE_180: matrix.postRotate(180); break;
                case ExifInterface.ORIENTATION_ROTATE_270: matrix.postRotate(270); break;
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.postScale(-1, 1); break;
                default: return bitmap;
            }
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0,
                    bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (rotated != bitmap) bitmap.recycle();
            return rotated;
        } catch (Exception ignored) {
            return bitmap;
        }
    }

    private String thumbnailBase64(File image) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(image.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return "";
        int sample = 1;
        while (Math.max(bounds.outWidth / (sample * 2), bounds.outHeight / (sample * 2)) >= THUMB_EDGE) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bitmap = BitmapFactory.decodeFile(image.getAbsolutePath(), opts);
        if (bitmap == null) return "";
        try {
            bitmap = scaleDown(bitmap, THUMB_EDGE);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, out);
            return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } finally {
            bitmap.recycle();
        }
    }

    // ---------- 小工具 ----------

    private long copyStream(Uri uri, File tmp) throws IOException {
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = app.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new IOException("无法打开附件");
            int read;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > MAX_FILE_BYTES) return total;
                out.write(buffer, 0, read);
            }
        }
        return total;
    }

    private static int readFully(InputStream in, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int read = in.read(buffer, total, buffer.length - total);
            if (read < 0) break;
            total += read;
        }
        return total;
    }

    private static void writeFile(File out, byte[] bytes) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(bytes);
        }
    }

    private static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            try (FileInputStream in = new FileInputStream(file)) {
                int read;
                while ((read = in.read(buffer)) > 0) digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private String displayName(Uri uri) {
        String name = null;
        try (android.database.Cursor cursor = app.getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (Exception ignored) {
        }
        if (name == null || name.isEmpty()) {
            name = uri.getLastPathSegment();
            if (name == null || name.isEmpty()) name = "附件";
            int slash = name.lastIndexOf('/');
            if (slash >= 0) name = name.substring(slash + 1);
        }
        return name.length() > 120 ? name.substring(0, 120) : name;
    }

    private static String guessMime(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return null;
        String ext = name.substring(dot + 1).toLowerCase(java.util.Locale.US);
        switch (ext) {
            case "png": return "image/png";
            case "jpg":
            case "jpeg": return "image/jpeg";
            case "gif": return "image/gif";
            case "webp": return "image/webp";
            case "pdf": return "application/pdf";
            case "txt":
            case "md":
            case "log": return "text/plain";
            case "json": return "application/json";
            case "zip": return "application/zip";
            default: return null;
        }
    }

    private void ensureDir() throws IOException {
        if (dir.isDirectory() || dir.mkdirs()) return;
        throw new IOException("无法创建附件目录");
    }

    private static void cleanup(File file) {
        if (file != null && file.exists()) file.delete();
    }

    // ---------- 草稿持久化 ----------

    private void persistMeta() {
        List<Item> snapshot;
        synchronized (lock) { snapshot = new ArrayList<>(items); }
        try {
            JSONArray array = new JSONArray();
            for (Item item : snapshot) {
                JSONObject obj = new JSONObject();
                obj.put("id", item.id);
                obj.put("name", item.name);
                obj.put("mimeType", item.mimeType);
                obj.put("kind", item.kind);
                obj.put("size", item.size);
                obj.put("sha256", item.sha256);
                obj.put("thumbnailBase64", item.thumbnailBase64);
                obj.put("fileName", item.file.getName());
                array.put(obj);
            }
            AtomicFile atomic = new AtomicFile(new File(dir, "drafts.json"));
            FileOutputStream out = atomic.startWrite();
            try {
                out.write(array.toString().getBytes("UTF-8"));
                atomic.finishWrite(out);
            } catch (IOException e) {
                atomic.failWrite(out);
            }
        } catch (Exception ignored) {
        }
    }

    private void restoreDrafts() {
        File meta = new File(dir, "drafts.json");
        if (!meta.isFile()) return;
        try {
            byte[] raw = new byte[(int) Math.min(meta.length(), 1024 * 1024)];
            try (FileInputStream in = new FileInputStream(meta)) {
                int read = in.read(raw);
                if (read <= 0) return;
                JSONArray array = new JSONArray(new String(raw, 0, read, "UTF-8"));
                List<Item> restored = new ArrayList<>();
                for (int i = 0; i < array.length() && restored.size() < MAX_ITEMS; i++) {
                    JSONObject obj = array.optJSONObject(i);
                    if (obj == null) continue;
                    String fileName = obj.optString("fileName");
                    // 只接受自己目录里的平文件名，拒绝路径穿越。
                    if (fileName.isEmpty() || fileName.contains("/") || fileName.contains("..")) continue;
                    File file = new File(dir, fileName);
                    if (!file.isFile()) continue;
                    Item item = new Item(obj.optString("id"), obj.optString("name", "附件"),
                            obj.optString("mimeType", "application/octet-stream"),
                            obj.optString("kind", "file"), obj.optLong("size"),
                            obj.optString("sha256"), obj.optString("thumbnailBase64"), file);
                    if (item.id.isEmpty() || item.file.length() != item.size) continue;
                    restored.add(item);
                }
                if (!restored.isEmpty()) {
                    synchronized (lock) { items.addAll(restored); }
                    notifyChanged();
                }
            }
        } catch (Exception ignored) {
            // 元数据损坏：不影响使用，下次增删会重写。
        }
    }

    // ---------- 回调分发 ----------

    private void notifyChanged() {
        Listener l;
        synchronized (lock) { l = listener; }
        if (l != null) main.post(l::onChanged);
    }

    private void postError(String message) {
        Listener l;
        synchronized (lock) { l = listener; }
        if (l != null) main.post(() -> l.onError(message));
    }

    private static final class UploadCancelled extends Exception { }

    private static final class UploadException extends Exception {
        UploadException(String message) { super(message); }
    }
}

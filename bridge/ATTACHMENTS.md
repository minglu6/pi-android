# 后端附件上传协议 v1

仅连接器后端；不修改 APK、不部署或重启服务。依据本机官方 Pi **1.1.0** 的完整
`docs/rpc.md`、`rpc-commands.md`、`message-types.md`、`json.md`、
`rpc-extension-ui.md`、`session-format.md`、`sdk.md`、`cli.md`、`models.md`、
`cli-integration.md` 和 `examples/rpc-client.ts`；不导入或修改 Pi 内部实现。

## 线协议

先按现有方式发送 `{auth: token}`。以下四项都是 **bridge-local RPC**，不写入 Pi stdin，
也不会为了上传启动 Pi。沿用普通响应 envelope 与客户端 ID：

```json
{"id":"u1","rpc":"upload_begin","params":{"name":"报告.pdf","mimeType":"application/pdf","size":123,"kind":"file"}}
```

成功响应 `data` 分别为：

| RPC | params | response.data |
| --- | --- | --- |
| `upload_begin` | `{name,mimeType,size,kind:'image'\|'file'}` | `{uploadId,maxChunkBytes:262144}` |
| `upload_chunk` | `{uploadId,offset,data:base64}` | `{received}` |
| `upload_finish` | `{uploadId,sha256}` | `{attachment:{id,name,mimeType,size,kind}}` |
| `upload_abort` | `{uploadId}` | `{cancelled:true}` |

- `size` / `offset` 是安全整数，单位字节；空文件允许，空图片不允许。
- 每次等待 chunk ACK，再发送下一个。`offset` 必须严格等于上次 `received`；不接受覆盖、
  跳跃、重复 chunk。每块解码后不超过 256 KiB。
- base64 必须使用标准 alphabet、正确 padding 和零 padding bits；不接受空 chunk、空白、
  URL-safe alphabet、缺失/多余 padding。`sha256` 是原始文件字节的小写 64 位十六进制摘要。
- `upload_finish` 要求精确字节数和 SHA256；摘要不符会取消并删除 partial。
  元数据/offset/base64/字节数错误可在同一连接上修正后重试；无效写入会取消 partial。
- `uploadId` 与完成的 `attachment.id` 是同一个随机 192-bit opaque ID，不是路径。
  不允许客户端指定磁盘路径。名称只保留最后一段 basename（同时处理 `/` 与 `\\`），
  清理控制/格式字符和危险分隔字符，限制为 200 字符；实际磁盘文件名固定为 `content`。
- 同连接至多 8 个进行中的上传，全桥至多 64 个；每 prompt 至多 8 个不重复 attachment ID。
- partial 仅属创建它的 WebSocket；其他已鉴权连接不能 chunk/finish/abort 它。
  已完成附件可被同一 token 边界内的其他连接复用：本桥只有一个共享 token，没有独立账号体系。
- abort 幂等：未知但格式有效的 ID 也返回 cancelled；**完成的附件绝不删除**，即使尚未发送
  prompt、发送失败、已进入 prompt/历史、用户取消 Pi、断线或会话被删除。
  `cancelled:true` 表示该上传不再进行，不表示已提交文件被删除。

## 持久存储与配置

通过启动连接器时的环境变量设置（无须、也不会直接改 runit 服务配置）：

| 变量 | 默认值 |
| --- | --- |
| `PI_ANDROID_UPLOAD_DIR` | `~/.pi/agent/uploads/pi-android` |
| `PI_ANDROID_UPLOAD_MAX_BYTES` | `52428800`（每附件 50 MiB） |
| `PI_ANDROID_UPLOAD_TIMEOUT_MS` | `300000`（5 分钟无有效上传进展） |

两个数值必须是正安全整数，配置无效则拒绝启动。目录延迟到首次合法的已鉴权上传/附件解析
才创建，未鉴权连接不能创建文件。目录 0700、文件 0600；已存在但非私有、非当前 UID 所有、
或是 symlink 的根目录会拒绝使用，不替用户改权限。

```
<upload-dir>/
  .partial-<bridge-pid>-<opaque-id>/content
  <opaque-id>/content
  <opaque-id>/meta.json
```

上传只持有单个有界 chunk、增量 SHA 和 12 字节格式头，不在 RAM 缓存完整上传；
finish 对文件/元数据/目录 fsync 后原子 rename，再 fsync 根目录。完成元数据保存描述符和 SHA，
不保存 token、客户端路径或 base64。正常 shutdown / disconnect / abort / timeout 清理 partial。
SIGKILL/崩溃无法立即清理：下次库初始化仅回收明确 PID 已死的私有 `.partial-*`，
不会回收仍活的另一个 bridge 的 partial。PID 重用或异常文件/权限导致无法确证时保留供人工检查。

重启后按严格 ID 格式解析自有目录；拒绝链接、硬链接、非普通文件、权限异常和不匹配元数据。
prompt 时有界读取并再次验证真实大小与 SHA；不接受从 meta.json 指定其他路径。
降低上传 size cap 不会使已完成附件失效。磁盘路径写入历史后不要移动 upload-dir，
升级/备份应一起保留该目录及权限。**没有自动 GC**；磁盘配额/人工删除由操作者管理，
删除后旧历史的文件路径会失效。

## prompt 转换

```json
{"id":"p1","rpc":"prompt","params":{"message":"分析附件","attachmentIds":["opaque-id"],"streamingBehavior":"followUp"}}
```

只允许在 `prompt` 使用 `attachmentIds`，转发前去掉该字段。保留官方 `streamingBehavior` 等
原有参数；文本无附件的转发不变。文本缺失、空或仅空白且有附件时使用 **`请查看这些附件。`**。
不允许 attachment IDs 与非空内联 `images` 混用。

### 图片

`kind:image` 支持规范 MIME `image/png` / `image/jpeg` / `image/gif` / `image/webp`。
上传完成时检查文件签名与 MIME，不进行解包、执行或外部转换。拒绝 SVG/HEIC/BMP/PDF 等；
可以作为 `kind:file` 上传这些格式。签名检查不是完整图片解码器，损坏的内部结构仍可能被 Pi 拒绝。

转为官方 RPC 内容，不加文件路径块：

```json
{"type":"prompt","message":"请查看这些附件。","images":[{"type":"image","data":"原始字节的标准base64","mimeType":"image/png"}]}
```

发送前使用唯一内部 ID 发官方 `get_state`，不向客户端暴露额外普通响应；查询 10 秒超时、失败、
Pi 退出或客户端断线时不发送后续 prompt。`model.input` 明确不含 `image` 时清晰失败。
缺少能力声明时不猜测模型，仍用原生图片通道让 Pi/provider 判断；并未承诺未知模型必有视觉能力。
查询与 prompt 不是官方原子操作；并发模型切换（或排队后换模型）仍存在竞态，请客户端避免这种操作。

原有直接 `prompt.images` 通道也保留，限制为至多 8 张、相同支持 MIME/签名/规范 base64，并执行
相同模型检查；其 WebSocket payload 仍受原有 8 MiB 限制。大附件应走 chunk 上传。

**内存边界：**上传与文件读取是有界的；官方原生图片字段在发送 prompt 时需要构造完整 base64
字符串和 JSONL。大批量图片会产生额外内存和 Node 字符串/提供者大小限制，建议缩小图片后上传。
本桥保证发出的原始图片字节，不绕过官方 Pi 的 resize/转换/模型请求限制；实际历史中的图片可能
被官方 resize。无法仅凭 fake Pi 测试声称所有提供者/损坏图片/最大批量都可接受。

### 文件

仅 file 描述符追加到 message，精确格式为：

```
\n\n<pi_attachments_v1>\n
JSON.stringify([{name,path,mimeType,size,kind}])
\n</pi_attachments_v1>
```

实际示例：

```text
分析附件

<pi_attachments_v1>
[{"name":"报告.pdf","path":"/absolute/upload-dir/opaque-id/content","mimeType":"application/pdf","size":123,"kind":"file"}]
</pi_attachments_v1>
```

`path` 是 canonical upload root 下的真实绝对保存路径，字节不改写；名称经清理和 JSON 转义。
这是一段可读元数据，不是 shell 命令、自动读取/解压指令或安全沙箱。Pi 工具能按路径读取，
但其实际可读性取决于运行 UID/文件系统权限；连接器与 Pi 通常同 UID。内容本身仍是非可信输入，
提示词驱动的工具权限沿用原有安全边界。图片与文件混合时只有文件进入此块。

## RPC 生命周期与隔离测试

保留桥的唯一内部 ID → WebSocket/client ID 关联；不同客户端同 ID 不冲突。
上传响应只发给发起者，模型探测响应内部消费；正常 prompt response 仍表示 accepted/queued/handled，
不是模型完成。事件不伪造、不省略、不把 `agent_end` 当作最终完成，继续等官方 `agent_settled`。

仅运行本地 fake Pi / 临时目录 / 动态端口：

```sh
cd ~/projects/pi-android
node --test bridge/tests/*.test.mjs
```

fixture 从不导入 Pi、不读真实会话，环境强制把 upload-dir/HOME/token/session-dir 指向私有测试目录。
覆盖未授权零文件/零子进程、bounds/base64/offset/hash、basename/path/symlink、权限、清理、
崩溃恢复/重连持久 ID、最大附件数、文本回归、原生图片字节、文件元数据精确字符串、
模型能力拒绝/未知/失败/断线、ID 关联、正常生命周期和 local RPC 不误转发。

本次不连接真实 Pi、不操作真实服务、没有 app/ 变更或 commit；真实提供者/最大图片批量、
设备上传 UI 与升级部署验收留给协调者在用户任务空闲时集成。

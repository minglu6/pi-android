# 模型切换与会话/连接回归修复（2026-10-10）

基线：`6efe21f`。不修改 Pi 核心、模型认证或扩展配置。

## 已确认的问题与修复

- WebSocket 在收到鉴权确认前补发 RPC；旧 socket 的回调和已排队的主线程消息能覆盖新连接。连接世代现在同时保护网络回调和主线程交付；Pi 子进程 offline 不再撤销已认证 socket。
- 多个客户端都从 `a1` 编号，桥原先按客户端 ID 保存 pending，且会按 command 猜响应归属。现在桥生成唯一内部 ID，响应只回发起 socket，恢复原客户端 ID；未知/失配响应不广播。
- 模型身份与显示名、磁盘缓存混用；Activity 重建可将旧缓存覆盖新状态。身份只用 provider + id，服务持有确认状态；旧状态请求和旧模型的 thinking 能力回复被拒绝。失败不污染最近模型列表。
- 思考档位在请求成功前乐观更新。现在读取权威 get_state，支持实际值被 Pi 钳制，失败保留已确认档位。
- 新建/切换/Fork 取消仍会清空聊天、标题。现在等成功且未 cancelled 后同步状态与历史；改名失败不先改标题。
- 回到前台或重连不恢复历史；旧历史请求会覆盖正在生成的内容。现在仅接受本页最新历史请求，并在空闲时同步。
- 同一轮多个 assistant message 覆盖同一气泡。现在每条 assistant 消息有独立行，结束时按完整 message 校正；工具结果保留。
- 静默 90 秒自动 abort 会误杀长工具调用、重试和扩展提问。现在只检查状态；Stop 清掉后续队列并请求 abort，等待真实状态确认。
- 扩展提问的旧 timeout/旧按钮会重复回答，并清除下一条提问。回答 sink 现在仅能完成当前有效请求一次；渲染异常释放 awaiting。
- 真实 Pi 扩展的 setStatus 覆盖模型芯片、Hindsight 的自定义消息被显示为大段原始上下文。现在状态保留模型/档位前缀，Hindsight recall 显示结构化条数摘要；display=false 的 custom 消息不显示，不修改后端原文。
- javac 错误被过滤管道吞掉、AAR 资源错误被忽略，可能继续签出缺类 APK。现在两个阶段失败即终止，保留错误输出。
- 桥默认 --no-session 与“保留历史”承诺矛盾。现在使用 Pi 默认持久会话；只有显式 --no-session（或 --pi-arg）才临时运行，旧 --session 兼容为 no-op。
- token 缺失/为空现在拒绝启动；命令 params 不能覆盖 type/id；删除会话还验证真实路径，拒绝 symlink 越界。

## 可重复的隔离测试

```sh
npm ci --prefix bridge
# 初次显式下载 JVM JSON 依赖；SHA256 校验，jar 不进 git。
bash tests/bridge-connection/setup-json.sh
bash tests/bridge-connection/run.sh            # 15 项，不开 socket
bash tests/bridge-connection/verify-regressions.sh  # 5 项证明旧基线失败、新代码通过
bash tests/model-state/run.sh                  # 47 个真实 Java 检查
bash tests/extension-ui/run.sh                  # 超时/重复/重连/渲染/历史/状态
python3 tests/test_build_failures.py            # 真实 build.sh + 故障工具 fixture
node --test bridge/tests/pi-rpc-bridge.test.mjs # 17 项，独立临时目录/token/端口/fake Pi
```

CI 运行隔离测试后再构建 APK。测试不读真实 token、不启动真实 Pi、不改真实历史。
基线脚本默认读取固定 `6efe21f`，需要本地 git 中存在该 commit。

可选设备 UI fixture：`node tests/ui-fixture.mjs --port 18945`，App 配置为 127.0.0.1:18945、
token `pi-ui-fixture-only`；仅测试模拟状态，不读真实认证、不调用模型。真实配置不可改成该测试 token 后长期留用。

## 本机设备实证

全部在动态 scrcpy 虚拟屏进行，未操作主屏：

1. 同 id `shared-id`、不同 provider fixture-a/b，点击 B 后请求确为 fixture-b + shared-id，芯片显示 B。
2. rejected-id 返回失败后原模型不变；thinking 拒绝后仍 high。
3. 第一次 new_session 返回 cancelled：原标题与两条历史保留；第二次成功后才清空。
4. STREAM_FIRST → TOOL_RESULT → STREAM_SECOND 都独立可见；杀掉 App 后重连恢复相同历史。
5. 恢复真实连接配置、重启桥后，APK 选择 opencode-go / hy4-preview；另一个真实 RPC 客户端 get_state 确认 provider/id。
6. 实际请求“只回复 PI_ANDROID_MODEL_OK，不调用工具”，收到了该回复；持久会话 JSONL 的 assistant 元数据 provider=opencode-go、model=hy4-preview、stopReason=stop，与 UI 切换一致，不依赖模型自述身份。

取证工作目录：本机 `~/tmp/pi-android-debug/`；`private/` 为受限备份，包含原配置/运行态，**禁止输出、提交或上传**。
生产 APK 原来自 CI，签名不同；用户明确同意卸载。已先备份配置，再卸载/安装本机同一 keystore 签名的新包并恢复连接偏好；Termux 认证与原持久会话未删除。

## 边界

未声称覆盖所有 Android/厂商省电、全部扩展 UI、认证过期、网络故障或全部 Pi 版本。多客户端共享同一个 Pi session，模型/会话操作仍是共享操作。
CI 每次临时生成签名与本机固定 keystore 不同；不配置稳定签名时，CI 包与本机构建包不能覆盖升级。不要为绕过签名清除数据。

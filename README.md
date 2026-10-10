# pi-android

Pi（[@earendil-works/pi-coding-agent](https://github.com/earendil-works/pi)）的非官方 Android 原生客户端。

**不是** Pi 官方客户端，与 Earendil Inc. 没有隶属关系，也没有其背书。It reuses nothing but Pi's
public, documented RPC interface; no Pi source code is vendored or patched here.

```
pi-android.apk                      Java 原生 UI · 独立包名 dev.pi.android · 官方 Pi logo
   │  ws://127.0.0.1:8945  (loopback + token)
   ▼
Termux runit: pi-rpc-bridge  →  node bridge/pi-rpc-bridge.mjs  →  pi --mode rpc
                                                                    (stdin/stdout JSONL)
   ▼
现有 ~/.pi/agent：模型认证 / Skills / Extensions / 历史会话 全部保留
```

APK 关闭不会销毁 Termux 里的任何东西；反过来，Termux 里的 Pi 也不用为 APK 活着。

## 为什么不是 fork Pocket Pi

ChatGPT 在原始对话里建议 fork [Pocket Pi](https://github.com/earendil-works/pi)（把 Termux 打包进 APK）。
本机已经有一套可用的 Termux + Pi，再包一份 bootstrap 只是多一个要维护的运行环境；
而 Pi 1.1.0 本身就有语言无关的官方集成入口（`pi --mode rpc` + `RpcClient`），
所以「自己写一个客户端」是被上游支持的做法，不必逆向第三方 App。
另外 fork 要接 Pocket Pi 的 MIT 署名义务并处理 Logo/商标问题，薄客户端这两件事都消失。

代价要说清楚：**这是自己维护的一个客户端**，Pi 增加 RPC 命令时本仓库要跟着加，
不像 fork 那样天然跟着上游走。好处是 upgrade 只需 `pkg upgrade` 更新 Pi，APK 通常不用动。

## 仓库结构

| 路径 | 是什么 |
| --- | --- |
| `bridge/pi-rpc-bridge.mjs` | WebSocket ↔ Pi RPC 的桥（Termux 侧，Node + ws） |
| `app/src/dev/pi/android/` | APK 源码：`ui/` 界面、`bridge/` 连接层、`model/`、`util/` |
| `app/tools/` | 替代 gradle 的四个脚本（依赖获取、依赖 R 生成、打包、AXML 包名读取） |
| `app/build.sh` | 一键构建：`aapt2 → javac → d8 → assemble → apksigner` |
| `app/libs/` | 依赖 AAR/JAR（**不进 git**，用 `tools/fetch-aars.py` 重建） |
| `artwork/` | logo 原件与 adaptive / monochrome 说明 |
| `docs/build-pipeline.md` | 无 gradle 构建链的坑目录（含 1200 条 `No package ID 7f` 的成因） |
| `NOTICE.md` | 商标 / logo / 许可证说明 |

## 用起来

```sh
# 1) 一次生成 token（600），APK 侧要填同一份
umask 077; head -c 24 /dev/urandom | base64 > ~/.pi/agent/pi-android.token

# 2) 桥常驻：runit 服务
sv up pi-rpc-bridge          # 日志 $PREFIX/var/log/sv/pi-rpc-bridge/current
# （一次性跑：~/bin/pi-rpc-bridge）

# 3) 构建并安装 APK
cd ~/projects/pi-android/app && bash build.sh
adb -s <serial> install -r out/pi-android.apk

# 4) 在虚拟屏上验证（真机主屏不要动）
PHONE_VD_SERIAL=<serial> phone-vd-start --app dev.pi.android
phone-screen shot.png && tesseract -l chi_sim+eng shot.png -     # OCR 取证最稳

# 5) 首次连：打开 app → 设置里填 token（或用下面的一键投放）
TOKEN=$(cat ~/.pi/agent/pi-android.token)
adb shell am start -n dev.pi.android/.ui.MainActivity --es token "$TOKEN" --display <N>
```

也可把文本直接分享给 Pi（`am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "…"`），
这是本机自动化驱动它的正规入口。深链面板：`--es ui sessions|settings|fork|models|thinking|newsession`。

## 已实现 / 待办

### 已实现

- WebSocket 连接 + token 鉴权 + 自动重连（配置签名变化才重连，避免分享 Intent 掐断流式回复）；
- 提问与流式回复（`message_update` delta 累积，`text_end`/`message_end` 用权威文本覆写）；
- 工具调用气泡（`tool_execution_start/update/end`）；
- 中断（abort：顶栏菜单 / 发送钮在流式时变为停止）；
- 新建会话、模型切换、思考档位切换；
- `get_state` 驱动的状态芯片（模型名 · 思考档位 · 连接点）；
- **前台 `BridgeService`**：持有 WebSocket + 常驻通知（桥状态 + 一键停止），App 进后台不丢连接；
- **会话列表**（桥本地命令 `list_sessions` → `switch_session` → `get_messages` 重建历史）；顶栏快捷入口；
- **Fork**（`get_fork_messages` → `fork`）；
- **扩展 UI 子协议**：select / confirm / input / editor —— 前台用聊天流下方按钮行作答；后台走高优先级通知按钮；`notify` 落成气泡，`setStatus` / `setTitle` 反映到顶栏。不用 AlertDialog：虚拟屏上存在「对象创建成功、isShowing() 为 true、屏幕上却没有」的情况，而这几种请求会**阻塞 Pi**；
- 首次启动空状态：无 Token / 未连接时引导进设置；
- Adaptive launcher icon + Material You **monochrome** 层（`artwork/` 说明）。

### 还没做 / 已知限制

- 会话树可视化（`get_tree` 已在命令白名单，UI 未做）；
- `setWidget` / 富编辑器大文本体验仍偏简陋（不阻塞，可忽略）；
- 多窗口 / 折叠屏专门布局；
- ColorOS 等厂商仍可能杀前台服务以外的进程 —— 依赖常驻通知保活，无法 100% 对抗激进清理。

## 安全边界

- 桥只绑 `127.0.0.1`，token 必填（token 文件丢失时会明确告警）。本机 loopback 不对 App 做隔离，
  所以 token 是唯一的门槛，权限应是 `~/.pi/agent/pi-android.token` 的 600。
- 命令白名单默认**不包含 `bash`**。开 `--allow-bash` 等于把设备的 shell 交给能连到 loopback 的 App。
- 依赖里有 sha256 清单（`app/libs/manifest.json`），重建时核对即可。

## 许可证

本仓库代码按 MIT 许可（见 `LICENSE`）。
Pi 的 logo 与商标不属于 MIT 授权范围，使用前请看 `NOTICE.md`。

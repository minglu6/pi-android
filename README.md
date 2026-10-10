# pi-android

Unofficial Android client for Pi
([@earendil-works/pi-coding-agent](https://github.com/earendil-works/pi)).

**Not affiliated with or endorsed by Earendil Inc.** It reuses nothing but Pi's
public, documented RPC interface; no Pi source code is vendored or patched here.

Pi（[@earendil-works/pi-coding-agent](https://github.com/earendil-works/pi)）的非官方 Android 原生客户端。
与 Earendil Inc. 没有隶属关系，也没有其背书。

## Quick start

```sh
# 1) Token（600），APK 与连接器共用
umask 077; head -c 24 /dev/urandom | base64 > ~/.pi/agent/pi-android.token

# 2) Termux 侧常驻连接器
sv up pi-rpc-bridge          # 或一次性：~/bin/pi-rpc-bridge

# 3) 构建 APK（需 ANDROID_JAR + aapt2/d8/apksigner，见 CONTRIBUTING.md）
make apk
# 或：cd app && bash build.sh --only-apk
adb install -r app/out/pi-android.apk

# 4) 首次连：设置里填 token，或 adb 投放
TOKEN=$(cat ~/.pi/agent/pi-android.token)
adb shell am start -n dev.pi.android/.ui.MainActivity --es token "$TOKEN"
```

也可把文本分享给 Pi（`ACTION_SEND` text/plain），或深链
`--es ui sessions|settings|fork|models|thinking|newsession`。

贡献 / 环境变量 / CI：见 [`CONTRIBUTING.md`](CONTRIBUTING.md)。

## Architecture

```
pi-android.apk                      Java 原生 UI · 包名 dev.pi.android · 官方 Pi logo
   │  ws://127.0.0.1:8945  (loopback + token)
   ▼
Termux: pi-rpc-bridge  →  bridge/pi-rpc-bridge.mjs  →  pi --mode rpc
                                                          (stdin/stdout JSONL)
   ▼
现有 ~/.pi/agent：模型认证 / Skills / Extensions / 历史会话 全部保留
```

APK 关闭不会销毁 Termux 里的任何东西；反过来，Termux 里的 Pi 也不用为 APK 活着。
桥默认使用 Pi 的持久会话；显式 `--no-session` 才是临时会话，进程结束后不会保留。

本仓库**不是** Pocket Pi fork：本机已有 Termux + Pi，薄客户端只走官方 `pi --mode rpc`，
不必打包第二套 bootstrap。代价是 RPC 演进时本仓库要跟着加；好处是 `pkg upgrade` 更新 Pi
通常不用动 APK。

| 路径 | 是什么 |
| --- | --- |
| `bridge/pi-rpc-bridge.mjs` | WebSocket ↔ Pi RPC 连接器（Termux / Node） |
| `app/src/dev/pi/android/` | APK：`ui/` · `bridge/` · `model/` · `util/` |
| `app/tools/` | 无 Gradle 脚本：`fetch-aars` / `gen-dep-r` / `assemble` / `axml-package` |
| `app/build.sh` | `aapt2 → javac → d8 → assemble → apksigner` |
| `app/libs/` | 依赖 AAR/JAR（**不进 git**，`make deps` 重建） |
| `artwork/` | logo 与 adaptive / monochrome 说明 |
| `docs/build-pipeline.md` | 构建坑目录与 CI 细节 |
| `Makefile` | 规范命令：`deps` / `apk` / `clean` |
| `NOTICE.md` | 商标 / logo / 许可证说明 |

## Build

不使用 Gradle。本机或 CI 都走同一条链：

```sh
# 非 Termux 示例
export ANDROID_HOME=/path/to/android-sdk
export PATH="$ANDROID_HOME/build-tools/35.0.0:$PATH"
export ANDROID_JAR="$ANDROID_HOME/platforms/android-36/android.jar"

make deps    # fetch-aars（含 okhttp）+ axml-package
make apk     # → app/out/pi-android.apk
```

依赖 roots 与 CI 完全一致（含 `com.squareup.okhttp3:okhttp:4.12.0`），见
[`CONTRIBUTING.md`](CONTRIBUTING.md) 与 [`docs/build-pipeline.md`](docs/build-pipeline.md)。

## Download APK

不必本机编译时：

1. 仓库 **Actions** → **Build APK** → 选一次成功运行
2. 页面底部 **Artifacts** → 下载 **`pi-android`** → 解压得 `pi-android.apk`
3. 或对 `v*` tag：在 **Releases** 直接下载

```sh
gh workflow run build-apk.yml --ref master
gh run watch
```

签名为 CI / `build.sh` 自签 keystore，**无需**仓库 Secrets。
注意：CI 临时 keystore 每次不同，与本机固定 keystore 也不同；这些包不能互相覆盖升级。
请保留本机 `app/pi-android.jks`（不要提交），不要为绕过签名随意卸载或清除数据。

## Regression tests

隔离 RPC、真实 Java 状态/连接测试、设备 UI fixture 与本次修复证据，见
[`docs/debugging-20261010.md`](docs/debugging-20261010.md)。CI 会在打包前运行隔离回归。

## Status（已实现 / 待办）

### 已实现

- WebSocket + token 鉴权 + 按配置签名重连
- 流式回复、工具气泡、abort（菜单 / 发送钮）
- 新建会话、模型 / 思考档位切换；`get_state` 状态芯片
- 前台 `BridgeService`（常驻通知 / 连接器状态，后台不丢连接）
- 会话列表（`list_sessions` → `switch_session` → `get_messages`）、Fork；可重命名 / 删除
- 扩展 UI：select / confirm / input / editor（前台按钮行；后台通知；`notify` / `setStatus` / `setTitle`）
- 首启空状态（无 Token / 未连接引导）、adaptive + monochrome 图标
- GitHub Actions 自动打 APK
- 日用聊天打磨：Markdown/代码块、时间戳、断线横幅、粘底滚动、会话重命名/删除
- Tokyo Night 风格聊天 UI（简洁顶栏 / 无气泡消息 / 工具时间线 / 卡片输入框）；MesloLGLDZ Nerd Font Mono

### 已知限制 / 未做

- 会话树可视化（`get_tree` 已在白名单，无 UI）
- `setWidget` / 大文本编辑体验仍简陋
- 多窗口 / 折叠专门布局
- 厂商激进杀进程无法 100% 对抗（依赖前台通知保活）

产品方向与大功能请先讨论，不要在本仓库默默扩 scope。

## 安全边界

- 连接器只绑 `127.0.0.1`，token 必填（文件权限建议 600）
- 命令白名单默认**不含直接 `bash` RPC**；`--allow-bash` 显式开启。该白名单不限制 prompt 驱动的 agent 工具，不是工具权限沙箱
- 依赖 sha256 清单：`app/libs/manifest.json`（重建后核对）

## License

代码：MIT（`LICENSE`）。Pi 的 logo / 商标**不在** MIT 范围内 —— 见 `NOTICE.md`。

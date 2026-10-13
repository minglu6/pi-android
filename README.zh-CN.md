# pi-android

**支持本机 Termux 与 Tailscale 远程连接的 Pi 原生 Android 客户端。**

[English](README.md) | 简体中文

pi-android 通过 Node.js WebSocket 连接器和 Pi 公开文档中的 `pi --mode rpc` 接口，将 Java 编写的 Android 应用与 [Pi](https://github.com/earendil-works/pi) 连接。Pi 在现有 Termux 或电脑环境中运行，应用不捆绑第二套 Pi 运行时。

**本项目由社区独立维护，并非官方项目，与 Earendil Inc. 没有隶属关系，也未获得其背书。** 仓库未内嵌或修改 Pi 源码。第三方品牌、界面参考及许可说明见[许可与来源声明](NOTICE.zh-CN.md)。

## 界面示例

<p align="center">
  <img src="docs/screenshots/home-light.jpg" width="360" alt="pi-android 浅色主题首页，展示本机 Termux 连接配置、历史建议和消息输入区">
</p>

浅色主题下的中文首页，当前选择本机 Termux 连接配置。

## 项目定位

提供两种连接方式：

- **本机连接：**在同一台 Android 设备上运行应用、连接器和 Pi，通过 Termux 与 `127.0.0.1:8945` 通信。
- **远程连接：**在电脑上运行连接器和 Pi，将手机与电脑加入同一 Tailscale 网络，在应用中选择对应电脑的连接配置。

连接器使用其宿主上的 Pi 配置，通常位于 `~/.pi/agent`，包括模型凭据、Skills、扩展及已保存的会话。远程模式下，任务仍在电脑上执行。Tailscale 需要单独安装；应用不内嵌 VPN，不提供托管中继，也不意味着可以安全地将连接器暴露到公网。

## 功能

- 流式会话，支持 Markdown、代码块、工具时间线、模型思考、回复中止，以及生成过程中的引导和排队输入。
- 创建会话、浏览历史、切换会话、创建分支、重命名、删除和本地置顶；输入历史及会话搜索、导出。
- 选择模型和思考强度；可用选项取决于已安装的 Pi 版本及模型提供商能力。
- 图片和文件附件、项目文件引用、文件/Git 视图及直接 shell 交互。
- 表格、内嵌图片及 SVG/HTML 回复预览。HTML/SVG 预览使用受限 WebView，关闭 JavaScript 和网络访问。
- 扩展交互：选择、确认、输入、编辑、通知、状态更新、标题及文本组件。
- 会话专属指令、定时消息，以及通过 `/until` 执行基于命令的完成条件检查。
- 多连接配置、状态与延迟检测、按配置保留模型和会话状态；在设置中启动、更新本机 Termux 连接器。
- 前台连接服务、后台交互通知，以及通过 Android `ACTION_SEND` 接收 `text/plain` 文本分享。
- 基于历史的空态建议，并为旧连接器提供兼容处理。
- 紧凑的原生界面、Meslo Nerd Font 字体及浅色/深色主题。

界面覆盖 Pi RPC 的部分能力。具体功能取决于已安装的 Pi 和连接器版本；本仓库尚未提供逐版本兼容性矩阵。

## 架构

```text
Android 应用（dev.pi.android）
    │ WebSocket + 共享令牌
    ├── 本机：127.0.0.1:8945 → Termux 连接器
    └── 远程：Tailscale 地址:8945 → 电脑连接器
                                             │
                                  stdin/stdout JSONL
                                             ▼
                                   Pi：pi --mode rpc
                                             │
                                     宿主配置与工具
```

每个连接器按需启动一个 Pi 子进程，供已连接的客户端共享。会话默认持久化；显式传入 `--no-session` 才使用临时会话。关闭应用界面不一定停止前台服务或连接器。最后一个 WebSocket 客户端断开后，连接器默认在 600 秒后停止 Pi 子进程；`--idle-exit 0` 可关闭该超时。Android 的后台执行或电池管理策略仍可能终止相关应用。

## Termux 本机配置

### 环境要求

- Android 8.0 / API 26 或更高版本。这是构建配置声明的最低要求，并非所有设备均已验证的兼容性承诺。
- Termux，以及可用的 Node.js、npm 和支持 `pi --mode rpc` 的 Pi 安装。
- 已在该 Termux Pi 环境中配置模型提供商。
- Termux 中已获取本仓库代码，且设备已安装 pi-android APK。

### 1. 准备连接器与令牌

在 Termux 的仓库根目录执行：

```sh
npm ci --prefix bridge
pi --version
```

创建共享令牌。以下命令会创建父目录，并拒绝覆盖已有令牌：

```sh
node --input-type=module -e '
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { randomBytes } from "node:crypto";
const dir = path.join(os.homedir(), ".pi/agent");
fs.mkdirSync(dir, { recursive: true });
fs.writeFileSync(path.join(dir, "pi-android.token"), randomBytes(24).toString("base64") + "\n", { mode: 0o600, flag: "wx" });
'
```

若提示 `EEXIST`，请复用已有令牌；如需更换，必须同步更新应用的连接配置。请妥善保管令牌内容。令牌文件不可读或为空时，连接器会拒绝启动。

### 2. 手动启动连接器

```sh
node bridge/pi-rpc-bridge.mjs
```

使用应用期间需保持该 Termux 进程运行。会话持久化默认开启。

常用参数：

| 参数 | 用途 |
| --- | --- |
| `--pi-bin /path/to/pi` | 指定 Pi 可执行文件，也支持 `PI_BIN` 环境变量。 |
| `--cwd /path/to/project` | 指定初始目录；未指定时使用连接器已保存的目录或 `HOME`。 |
| `--token-file /path/to/token` | 指定令牌文件，默认为 `~/.pi/agent/pi-android.token`。 |
| `--port 8945` | 指定监听端口，应用中的配置需保持一致。 |
| `--no-session` | 使用临时 Pi 会话，代替正常的持久化行为。 |
| `--idle-exit 0` | 所有客户端断开后仍保持 Pi 子进程运行。 |
| `--tailscale` | 自动发现并绑定宿主的 Tailscale IPv4 地址；不可用时拒绝启动。 |

### 3. 连接应用

通过顶栏配置入口或设置打开**连接配置**，选择本机 Termux 配置：

| 设置项 | 值 |
| --- | --- |
| 主机地址 | `127.0.0.1` |
| 端口 | `8945` |
| 令牌 | Termux 中 `~/.pi/agent/pi-android.token` 的内容 |

返回会话页面并发送消息。如需查看连接状态和后台扩展交互提示，请在系统询问时允许通知。模型请求使用 Pi 中配置的提供商；Agent 在本地运行并不代表模型推理离线进行。

### 可选：Termux 自动启动与更新

设置中提供连接器配置引导和**自动启动 Termux 连接器**选项。使用前需要：

1. 从官方 F-Droid 或 GitHub 渠道安装 Termux。
2. 通过配置引导或 Android 应用设置，授予 pi-android **在 Termux 中运行命令**权限（`com.termux.permission.RUN_COMMAND`）。
3. 在 `~/.termux/termux.properties` 中设置 `allow-external-apps=true`，然后在 Termux 执行 `termux-reload-settings`。

使用回环地址配置时，应用可通过 Termux `RUN_COMMAND` 请求后台启动连接器，无需打开 Termux 界面。它会尝试已有 runit 服务及受支持的包装脚本、连接器脚本位置，并采用退避重连。手动重新连接也可以请求启动连接器。仅克隆本仓库不会自动注册 runit 服务。

**更新并重启连接器**会从本仓库 `master` 分支下载连接器模块，按需安装依赖并重启。此操作会在 Termux 中执行更新后的代码，使用前请审查上游变更。远程连接配置不会触发本机 Termux 启动。设置中还提供连接器诊断，用于排查启动失败。

## Tailscale 远程配置

在电脑和 Android 设备上安装并登录 Tailscale，使两端加入同一 tailnet。在电脑上配置 Pi 及其模型提供商。在电脑的本仓库检出目录中，按上面的命令安装连接器依赖并创建令牌。各宿主可以使用独立令牌；手机对应配置中的令牌必须与该宿主一致。

### 前台运行

在电脑的仓库根目录执行：

```sh
tailscale status
node bridge/pi-rpc-bridge.mjs --tailscale --port 8945 --token-file "$HOME/.pi/agent/pi-android.token"
```

`--tailscale` 通过 `tailscale ip -4` 或 `tailscale0` 网卡发现地址，宿主需具备可用的 Tailscale 地址发现方式。无法使用自动发现时，可显式指定 `--host <tailscale-ip>`；不要使用公网地址或通配监听地址。

### 可选：安装电脑端用户服务

创建令牌并配置好 Node.js、npm、Pi 和 Tailscale 后执行：

```sh
bash scripts/install-remote.sh
```

[安装脚本](scripts/install-remote.sh)会安装依赖并创建、启动常驻用户服务：macOS 使用 `launchd`，Linux 使用 `systemd --user`。默认参数为 `--tailscale` 和 `--idle-exit 0`，可通过 `PI_ANDROID_HOST`、`PI_ANDROID_PORT` 或 `PI_ANDROID_TOKEN_FILE` 覆盖对应值。macOS 日志位于 `~/Library/Logs/pi-rpc-bridge*.log`；Linux 可通过 `systemctl --user status pi-rpc-bridge` 查看状态。其他宿主平台如具备相应运行环境，可使用前台命令；服务安装脚本仅支持 macOS 和 Linux。

### 添加手机端配置

1. 打开 Android Tailscale，连接到同一 tailnet。
2. 在 pi-android 中打开**连接配置**，新建配置。
3. 填写名称、电脑的 Tailscale IPv4 地址或 MagicDNS 名称、端口 `8945` 及电脑端连接器令牌。可选工作目录指的是远程宿主上的目录，而非手机目录。
4. 测试连接并选择该配置。切换配置后，应用会重新连接到对应宿主。本机 Termux 配置仍可单独使用。

请保持宿主在线且连接器运行。应用不会启动远程电脑，也不会自行建立 VPN 连接。

## 构建与下载

项目直接使用 Android SDK 工具构建，不依赖 Gradle。环境准备和回归命令见[贡献指南](CONTRIBUTING.zh-CN.md)，实现细节见[构建参考](docs/build-pipeline.zh-CN.md)。

在已配置构建工具的主机上，从仓库根目录执行：

```sh
export ANDROID_HOME=/path/to/android-sdk
export PATH="$ANDROID_HOME/build-tools/35.0.0:$ANDROID_HOME/platform-tools:$PATH"
export ANDROID_JAR="$ANDROID_HOME/platforms/android-36/android.jar"
make apk
adb install -r app/out/pi-android.apk
```

`make apk` 会同时获取依赖，并打包仓库中已有的字体资源。如需下载 CI 构建产物，请打开仓库的 **Actions → Build APK**，选择成功的运行记录并下载 **pi-android** artifact。由 `v*` 标签触发的构建还会将 APK 附加到 GitHub Release。CI 会在打包前运行隔离回归，但不能替代设备验证。

**签名说明：**本地构建复用 `app/pi-android.jks`；全新的 CI 运行会生成新的密钥。不同密钥签名的 APK 不能直接覆盖升级。持续本地构建时请保留签名密钥；卸载已有应用前，应考虑本地设置丢失的影响。当前 CI 不提供固定的发布签名身份。

## 安全边界

- 连接器默认绑定 `127.0.0.1`，远程访问请使用 `--tailscale`。它拒绝显式通配地址 `0.0.0.0` 和 `::`，但并未将显式 `--host` 限制为 Tailscale 地址。请勿将其暴露到公网。
- 启动时必须提供可读、非空的令牌。仅使用回环地址无法隔离同一设备上的其他 Android 应用；请保护令牌文件，建议权限为 `0600`。
- 应用协议为明文 `ws://`，并非 TLS。推荐的远程配置由 Tailscale 提供加密网络路径。令牌本身不会加密通信内容。
- 已认证客户端具有较高控制权限：可以使用直接 `bash` / `abort_bash` RPC、Agent 工具、项目文件、Git 操作及会话删除。无需 `--allow-bash` 即可使用 shell。应将连接配置令牌视为宿主控制凭据。
- 定时消息和 `/until` 检查可以在连接器宿主上执行任务。
- 模型服务、扩展和工具具有各自的数据访问与安全边界。使用前请审查第三方代码和权限。

## 已知限制

- 尚无可视化会话树；连接器允许 `get_tree` 并不代表已有相应界面。
- 支持文本组件，但不复现任意自定义终端界面。当前忽略 `set_editor_text` 请求。
- 尚无专门的折叠屏或多窗口布局。
- 前台服务和通知无法保证后台持续运行。定时执行依赖连接器进程持续可用。
- Pi RPC 的变更可能需要同步更新连接器或应用；旧连接器可能不支持新增功能。

## 文档

| 主题 | English | 简体中文 |
| --- | --- | --- |
| 项目说明 | [README](README.md) | [项目说明](README.zh-CN.md) |
| 参与贡献 | [Guide](CONTRIBUTING.md) | [贡献指南](CONTRIBUTING.zh-CN.md) |
| 构建流水线 | [Reference](docs/build-pipeline.md) | [构建参考](docs/build-pipeline.zh-CN.md) |
| 许可与来源声明 | [Notice](NOTICE.md) | [许可与来源声明](NOTICE.zh-CN.md) |
| 图形素材 | [Guide](artwork/README.md) | [图形素材说明](artwork/README.zh-CN.md) |

以下补充技术资料保留其现有语言：

- [移动端交互](docs/mobile-interaction.md)
- [界面规范](docs/ui-guidelines.md)
- [回归与调试记录](docs/debugging-20261010.md)
- [附件协议](bridge/ATTACHMENTS.md)

## 许可证

项目代码采用 [MIT License](LICENSE)。第三方依赖及随包字体保留各自的许可证。Pi 名称、logo 及其他品牌素材不在本项目 MIT 许可的授权范围内，详见 [NOTICE.zh-CN.md](NOTICE.zh-CN.md)。

# 参与 pi-android 开发

[English](CONTRIBUTING.md) | **简体中文**

pi-android 是 Pi 的非官方 Android 客户端。请先阅读[项目介绍](README.zh-CN.md)，了解项目范围、配置方式和安全边界；第三方署名与品牌使用注意事项见 [NOTICE.zh-CN.md](NOTICE.zh-CN.md)。

请保持改动聚焦，并在实现重大变更前先讨论。本项目有意采用精简的**无 Gradle** APK 构建流水线：`aapt2 → javac → d8 → assemble → apksigner`。

## 构建要求

应用要求 **Android 8.0（API 26）及以上版本**，目标 API 为 **36**。这两个值由 [app/build.sh](app/build.sh) 设置；目标 API 为 36 并不意味着设备必须运行 API 36。

| 要求 | 用途 |
| --- | --- |
| Bash、Make 和标准 Unix 命令行工具 | 执行构建脚本和仓库构建目标 |
| JDK 17 | CI 使用 Temurin 17；Java 源码以 `-source 11 -target 11` 编译 |
| Python 3 | 执行仓库中的依赖与打包工具，无需额外安装 Python 包 |
| Android SDK Platform 36 | 提供 `android.jar` |
| Android SDK Build-Tools 35.0.0 | CI 使用的版本，提供 `aapt2`、`d8` 和 `apksigner` |
| 能访问 Google Maven 和 Maven Central 的网络 | 解析并下载依赖 |
| Android SDK Platform-Tools（`adb`，可选） | 在设备或模拟器上安装和检查应用 |
| Node.js 22 和 npm，用于连接器开发及隔离测试 | 与 CI 测试运行时保持一致 |
| `curl` 和 `sha256sum`，用于 JVM 测试准备 | 下载并校验独立的 JSON 测试依赖 |

Android 工具必须适用于构建主机。在 Termux 中，需要使用可在 Termux 环境运行的二进制工具；桌面 SDK 工具不能直接替代 Android 主机上的工具。

对于桌面 SDK 安装，在仓库根目录配置：

```sh
export ANDROID_HOME=/path/to/android-sdk
export PATH="$ANDROID_HOME/build-tools/35.0.0:$ANDROID_HOME/platform-tools:$PATH"
export ANDROID_JAR="$ANDROID_HOME/platforms/android-36/android.jar"
```

无论使用哪种主机，都建议显式设置 `ANDROID_JAR`。脚本保留了一个特定 Termux 布局的旧默认路径，不会根据 `ANDROID_HOME` 自动查找 SDK。请使用 `bash` 调用脚本，因为其 shebang 指向 Termux 的 Bash 安装路径。

## 依赖与构建

以下命令均在仓库根目录执行：

```sh
make apk
```

`make apk` 会先执行 `make deps`，再执行 `bash app/build.sh --only-apk`。签名产物为 **`app/out/pi-android.apk`**。`make build` 是 `make apk` 的别名。

常用目标：

| 命令 | 行为 |
| --- | --- |
| `make deps` | 将 AAR/JAR 依赖下载到 `app/libs/`，并生成 `packages.json` |
| `make packages` | 根据已下载的 AAR 重新生成 `packages.json` |
| `bash app/build.sh --only-apk` | 使用现有依赖构建，不下载依赖，也不安装应用 |
| `make install` | 构建后执行 `adb install -r app/out/pi-android.apk` |
| `make clean` | 删除 `app/build/`、`app/out/` 和 `app/libs/`，保留签名密钥库 |

依赖根节点和排除项定义在 [Makefile](Makefile) 中。修改时，请同步更新 [CI 工作流](.github/workflows/build-apk.yml) 中的对应列表。依赖解析器是专用工具，并非完整的 Maven 或 Gradle 实现。变更依赖版本后，应先执行 `make clean`，再执行 `make apk`，避免残留旧版本 JAR。

`app/res/font/` 中的 MesloLGLDZ Nerd Font Regular 和 Bold 文件是必需的源码资源，不由 Maven 下载。修改字体时，请保留两个字体文件、字体族 XML 及 `app/res/raw/` 中的对应许可声明；详见 [NOTICE.zh-CN.md](NOTICE.zh-CN.md)。

生成的依赖、构建输出、APK 和密钥库均由 Git 忽略，请勿提交。资源生成、打包与排障细节见[构建参考](docs/build-pipeline.zh-CN.md)。

## 回归测试

隔离测试要求 `PATH` 中提供 JDK 17、Python 3、Node.js 22/npm、`curl` 和 `sha256sum`（macOS 可能需要通过 GNU coreutils 提供 `sha256sum`）。请显式准备依赖；JVM 测试脚本不会自动下载缺失依赖：

```sh
make deps
npm ci --prefix bridge
bash tests/bridge-connection/setup-json.sh
```

`make deps` 提供连接回归使用的 OkHttp、Okio、Kotlin 和 annotation JAR。`setup-json.sh` 单独下载 `org.json:json:20240303` 测试 JAR，并校验 SHA-256；请勿提交该文件。

当前 CI 回归步骤执行：

```sh
bash tests/bridge-connection/run.sh
bash tests/model-state/run.sh
bash tests/extension-ui/run.sh
bash tests/ansi/run.sh
bash tests/message-queue/run.sh
bash tests/card-scroll/run.sh
bash tests/slash-sheet/run.sh
python3 tests/test_build_failures.py
node --test bridge/tests/pi-rpc-bridge.test.mjs bridge/tests/suggestions.test.mjs
```

仓库还提供以下回归，当前未纳入上述 CI 步骤：

```sh
bash tests/transcript/run.sh
bash tests/attachments-model/run.sh
node --test bridge/tests/attachments.test.mjs tests/paced-fixture.test.mjs
bash tests/bridge-connection/verify-regressions.sh
```

基线比较要求本地 Git 历史中存在提交 `6efe21f`。脚本在临时目录中验证旧实现出现指定断言失败，再运行当前测试，不会替换工作树源码。

这些测试不需要真实 Pi 凭据或运行真实 Pi 后端。Python 构建失败测试通过故意失败的工具 fixture 执行 `build.sh`，并非完整 APK 构建。可选设备 UI fixture 和手动复现步骤见[回归排查记录](docs/debugging-20261010.md)及[移动端交互指南](docs/mobile-interaction.md)。完成 fixture 测试后必须恢复真实连接配置，不能将测试 token 留作生产配置。

## 签名与安装

首次构建会生成 `app/pi-android.jks`，别名为 `piandroid`。后续本地构建会复用该文件。默认密码是 `android`；`KS_PASS` 可覆盖创建或打开密钥库时使用的密码。修改 `KS_PASS` 不会修改已有密钥库的密码。

这是开发用途的签名配置，并非托管的发布签名服务。如果需要保持本地更新兼容，请妥善保管并备份密钥库。工作流不会恢复或提供持久化签名密钥，因此全新 CI 检出的构建会生成新密钥。**不同 CI 运行之间，以及 CI 与本地构建之间的 APK，不保证能够相互覆盖升级。** Release 附件使用该次构建的密钥，不使用独立的固定发布密钥。

Android 通常要求更新包与已安装应用使用相同的签名身份。`adb install -r` 无法绕过签名不匹配。应使用原密钥重新构建，或在明确接受影响后卸载旧应用，再安装不同签名的 APK。卸载会删除应用本地数据，请提前保留所需信息。

安装已经构建好的 APK：

```sh
adb install -r app/out/pi-android.apk
```

同时连接多个设备时，显式选择设备：

```sh
adb devices
adb -s SERIAL install -r app/out/pi-android.apk
```

将 `SERIAL` 替换为设备标识。`make install` 不会添加 `-s`；需要选择设备时请使用上述显式命令。

## CI 产物与 Release

**Build APK** 工作流会在向 `master` 推送、提交以 `master` 为目标的拉取请求、推送 `v*` 标签或手动触发时运行。

- **Actions 产物：** 打开一次成功的工作流运行，下载 `pi-android` 产物并解压得到 `pi-android.apk`。产物保留期配置为 30 天。
- **GitHub Releases：** 标签构建还会将 `pi-android.apk` 附加到对应 Release。分支和拉取请求构建不会创建 Release。
- **签名：** 两种分发方式均存在上述签名限制。工作流不需要自定义签名密钥 Secret；发布 Release 使用工作流的 `GITHUB_TOKEN`。

安装并登录 GitHub CLI，且具有触发工作流的权限后，可执行：

```sh
gh workflow run build-apk.yml --ref master
gh run watch
```

构建成功不等于设备兼容性验证通过，也不代表完成了运行时测试。请仅报告实际执行过的检查。

## 贡献要求

- 遵循 [.editorconfig](.editorconfig)：Java/XML 使用四空格缩进，Makefile 命令使用制表符。
- 保留无 Gradle 构建流程，除非已明确讨论构建系统变更。
- 修改文档所描述的行为时，同步维护英文与简体中文版本。
- 遵循 [UI 指南](docs/ui-guidelines.md)：复用已有主题化 sheet/dialog，应用 `AppFonts`，保留 Meslo/CJK 字体回退。不要为约定俗成的交互增加多余图例或 hint 字幕，不要重复字段占位说明，也不要在顶栏模型 chip 下重复模型或路径信息。保留必要的空态、一次性前置配置以及错误/风险提示。
- 用户可见字符串放入 `strings.xml`；中文界面使用“连接器”，不使用“桥”。
- 在问题报告或拉取请求中提供环境、复现步骤、相关日志和实际执行的检查；删除或遮盖令牌、凭证、提示词及其他隐私数据。
- 修改连接器时，遵循 [README 安全说明](README.zh-CN.md)。保留推荐的 `127.0.0.1` 绑定与 token 认证。Pocket 风格的 `!shell` / `!!shell` 已启用官方 `bash` 与 `abort_bash` RPC；`--allow-bash` 仅保留为兼容同义词，并非安全开关。RPC 白名单不会将提示词触发的 agent 工具限制在沙箱中。
- 不得暗示官方背书。修改品牌素材或分发 APK 前，请阅读 [NOTICE.zh-CN.md](NOTICE.zh-CN.md)。

# APK 构建参考

[English](build-pipeline.md) | **简体中文**

本文说明仓库的**无 Gradle** APK 构建流水线及实用排障方法。首次配置请参阅 [CONTRIBUTING.zh-CN.md](../CONTRIBUTING.zh-CN.md)；应用和连接器配置见 [README.zh-CN.md](../README.zh-CN.md)。分发 APK 或修改品牌素材前，请阅读 [NOTICE.zh-CN.md](../NOTICE.zh-CN.md)。

## 构建约定

[app/build.sh](../app/build.sh) 构建包名为 `dev.pi.android` 的应用，最低 API 为 **26（Android 8.0）**，目标 API 为 **36**。请使用 Android SDK Platform 36 的 `android.jar`。CI 使用 JDK 17 和 Android Build-Tools 35.0.0；`javac` 将源码编译为 Java 11 字节码。

构建主机需要 Bash、Make、Python 3、`javac`、`keytool`、`aapt2`、`d8`、`apksigner` 和标准 Unix 工具。依赖解析需要访问 Google Maven 和 Maven Central。构建本身不需要 `adb`，但本文中的安装命令需要它。无需安装 Gradle 或额外的 Python 包。

隔离回归测试还使用 Node.js 22/npm、`curl` 和 `sha256sum`；依赖准备方式以及 CI 测试与额外本地回归的区别见 [CONTRIBUTING.zh-CN.md](../CONTRIBUTING.zh-CN.md)。

所有示例均在仓库根目录执行：

```sh
export ANDROID_HOME=/path/to/android-sdk
export PATH="$ANDROID_HOME/build-tools/35.0.0:$ANDROID_HOME/platform-tools:$PATH"
export ANDROID_JAR="$ANDROID_HOME/platforms/android-36/android.jar"
make apk
```

工具必须适用于构建主机；在 Android 上构建时，需要使用兼容 Termux 的二进制工具。请显式设置 `ANDROID_JAR`，不要依赖脚本保留的特定 Termux 布局旧默认路径。仅设置 `ANDROID_HOME` 不够：脚本不会根据该变量查找 `android.jar`。请使用 `bash` 调用 `app/build.sh`，避免在桌面主机上使用其 Termux 专用 shebang。

## 命令与输出

[Makefile](../Makefile) 是本地构建的标准入口：

| 命令 | 效果 |
| --- | --- |
| `make deps` | 解析、下载依赖并生成 AAR 包名元数据 |
| `make packages` | 根据现有 AAR 重新生成包名元数据 |
| `make apk` / `make build` | 准备依赖，然后构建并签名 APK |
| `bash app/build.sh --only-apk` | 仅使用现有依赖构建 |
| `make install` | 执行 `make apk`，然后运行 `adb install -r` |
| `make clean` | 删除 `app/libs/`、`app/build/` 和 `app/out/`，不删除 `app/pi-android.jks` |

脚本每次构建都会重新创建 `app/build/`。不带参数运行脚本会构建 APK，但**不会**安装；只有传入 `--install` 时才尝试安装。建议使用 `make install` 或显式 `adb` 命令，以便直接观察安装失败。

| 路径 | 内容 |
| --- | --- |
| `app/libs/` | 下载并解包的依赖 |
| `app/libs/manifest.json` | 依赖坐标、下载构件大小和 SHA-256 记录 |
| `app/libs/graph.json` | 解析后的依赖图 |
| `app/libs/packages.json` | AAR 路径与 manifest 包名的映射 |
| `app/build/` | 编译后的资源、生成的 Java、类文件、DEX 和未签名 APK |
| `app/out/pi-android.apk` | 已签名 APK |
| `app/pi-android.jks` | 本地复用的签名密钥库 |

这些生成路径均不纳入版本控制。`manifest.json` 用于记录下载信息，既不是提交到仓库的依赖锁文件，也不保证每次运行都会重新验证缓存文件。

## 流水线阶段

### 1. 解析依赖

`make deps` 先执行 [fetch-aars.py](../app/tools/fetch-aars.py)，再执行 [axml-package.py](../app/tools/axml-package.py)。依赖根节点与排除项维护在 Makefile 中，[CI 工作流](../.github/workflows/build-apk.yml) 也包含同一份列表；修改时必须同步两处。

解析器从 Google Maven 和 Maven Central 读取 POM 依赖，使用自身的版本比较逻辑为每个 `group:artifact` 选择版本，下载 AAR/JAR，并提取 AAR 中的类、资源和 `R.txt`。它不是 Maven 或 Gradle 依赖语义的通用替代实现。

修改依赖时，请保留以下约定：

- 编译 classpath 和 DEX 输入既需要 AAR 的 `classes.jar`，也需要独立 JAR 构件。
- 连接器客户端使用 OkHttp，因此将其作为显式根依赖。
- 图片 EXIF 旋转校正使用平台 `android.media.ExifInterface`，其可用 API 低于应用的最低 API，因此无需引入 `androidx.exifinterface`。
- 显式包含 Kotlin 标准库。当前排除 `kotlin-stdlib-jdk7`、`kotlin-stdlib-jdk8` 和 `kotlin-stdlib-common`，用于避免这组依赖中 Kotlin 构件内容重叠；未审查解析后的输入前，不应移除这些排除项。
- 必须从每个 AAR 的 manifest 读取包名，不能根据 Maven 坐标猜测。包名工具支持文本 XML 和二进制 XML manifest。
- 变更版本后，`app/libs/` 可能仍保留未被排除的旧构件。构建会包含找到的所有 JAR，因此修改依赖后应执行 `make clean` 再重新构建。

### 2. 编译与链接资源

`aapt2 compile` 处理应用资源及每个依赖的资源目录。`aapt2 link` 将它们合并到 `app/build/base.apk`，生成资源表和 `app/build/gen/dev/pi/android/R.java`。依赖资源通过 `-R` 输入链接到应用包中。

应用的 Meslo 字体族需要 `app/res/font/meslo_lgldz_nf_regular.ttf` 和 `meslo_lgldz_nf_bold.ttf`，由 `meslo_lgldz_nf.xml` 引用。这些文件已包含在仓库中，不由 `make deps` 下载。请保留 `app/res/raw/` 中的对应声明。若资源链接报告缺少 `font/meslo_lgldz_nf_*`，应恢复所需字体资源，而不是删除主题引用。UI 改动还必须遵循 [UI 指南](ui-guidelines.md)，包括 `AppFonts` 字体回退以及禁止重复交互说明的要求。

随后，[gen-dep-r.py](../app/tools/gen-dep-r.py) 为依赖包生成 `R` 类。它结合 `packages.json` 中的包名、各个 `R.txt` 中的符号名，以及应用生成的 `R.java` 中的最终 ID。标量 `int` 字段和 `styleable` 的 `int[]` 字段都不可遗漏。AAR 字节码可能引用这些生成类，而 AAR 本身并不提供这些类。

当前构建不会调用 [lib-resources.py](../app/tools/lib-resources.py)。不要假设流水线已经启用逐依赖的静态资源链接阶段。

### 3. 编译 Java 并生成 DEX

构建会生成 `BuildInfo.java`，写入 UTC 构建时间和 Git 短提交 ID；无法获取提交 ID 时写入 `none`。随后以 `android.jar` 和全部依赖 JAR 为 classpath，编译应用源码及生成的 Java 源码，再将编译后的类和这些 JAR 传给 `d8 --release --min-api 26`。

`build.sh` 使用 `set -euo pipefail`：应用或依赖的 `aapt2 compile` 失败都会终止构建。Java 编译器输出保存在 `app/build/javac.log`；`javac` 失败时会输出该日志，并在生成 DEX 或签名前退出。只有编译成功后才过滤警告。

打包器会包含 D8 输出的所有 `classes*.dex`，而不仅是 `classes.dex`。Android API 26 原生支持 multidex；方法数增加本身并不意味着需要添加旧版 `MultiDex` Application 初始化逻辑。修改 DEX 配置前，应先检查实际的 D8 报错和输出。

### 4. 组装与签名

[assemble.py](../app/tools/assemble.py) 以 `base.apk` 为基础，加入：

- D8 输出目录中的所有 `classes*.dex` 文件。
- 依赖 AAR 与 `app/assets/` 中的资源文件。
- AAR 原生库，将 `jni/<abi>/*.so` 或 `libs/<abi>/*.so` 映射为 APK 中的 `lib/<abi>/` 条目。

若 `app/pi-android.jks` 不存在，构建会先创建它，再使用启用 v1、v2 和 v3 签名的 `apksigner` 输出 `app/out/pi-android.apk`。密钥库别名为 `piandroid`，默认密码为 `android`，可通过 `KS_PASS` 覆盖。打开已有密钥库时必须使用其现有密码。

脚本会嵌入时间戳，并在密钥库不存在时生成新密钥，因此该流程不承诺生成逐字节一致的可复现 APK。

## CI 分发与签名身份

**Build APK** 工作流会在向 `master` 推送、提交以 `master` 为目标的拉取请求、推送 `v*` 标签或手动触发时运行。它安装工具链、准备依赖、执行隔离的 RPC/JVM/构建失败回归、构建 APK，然后上传名为 **`pi-android`** 的 Actions 产物，保留期配置为 **30 天**。下载并解压产物后即可获得 `pi-android.apk`。回归命令及尚未纳入 CI 的额外测试见 [CONTRIBUTING.zh-CN.md](../CONTRIBUTING.zh-CN.md)；CI 成功不代表设备 UI 兼容性已经验证。

标签构建还会将同一 APK 附加到 GitHub Release。分支与拉取请求构建只发布 Actions 产物。Release 发布使用 `GITHUB_TOKEN`，未配置自定义签名密钥 Secret。

**当前工作流没有持久化的 CI 签名身份。** 每次全新 CI 检出都会生成新密钥库，因此 Release 附件不代表使用了固定的发布签名密钥。本地构建会复用 `app/pi-android.jks`，且 `make clean` 会保留该文件。如果需要兼容更新，请妥善保管并备份它。

更新通常需要与已安装应用相同的签名身份。不同 CI 运行之间，以及本地与 CI 构建之间的 APK，可能出现 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。应使用原密钥，或在明确接受影响后卸载旧应用再安装新 APK。卸载会删除应用本地数据，不应将其作为自动排障步骤。

## 故障排查

### 工具、平台 JAR 或依赖缺失

确认 `ANDROID_JAR` 指向已安装的 API 36 平台 JAR，且所有必需工具均位于 `PATH`。工具无法执行时，请检查主机架构是否匹配。依赖目录缺失时使用 `make deps`；AAR 已存在但需要重新生成包名元数据时，使用 `make packages`。

`kotlin.jvm.internal.Intrinsics` 或 `LifecycleOwner` 等类缺失，可能意味着依赖字节码没有完整包含。应检查解析后的构件，以及编译和 DEX 两个阶段的输入，而不是添加与根因无关的 UI 绕过逻辑。

### 重复类或旧版本依赖残留

对于 `Type ... is defined multiple times` 等 D8 错误，检查 JAR 输入、版本和 Kotlin 排除项。修改版本后，从干净的依赖目录重新构建：

```sh
make clean
make apk
```

这会删除已下载的依赖和输出文件，但保留本地密钥库。

### 资源或依赖 `R` 类缺失

如果 `ClassNotFoundException` 涉及依赖的 `R` 类，请检查 `packages.json`、该 AAR 的 `R.txt` 和生成的依赖 `R.java`。主题或 styleable 错误还需要检查生成的数组，不能只检查标量资源 ID。

资源编译与 Java 编译失败现在会终止构建，不应继续依据“这些失败会被忽略”的旧说明。请阅读完整 `aapt2` 诊断和 `app/build/javac.log`。依赖 `R` 生成器仍可能跳过合并表中不存在的符号，因此还需检查缺失字段报告和生成类，再复现受影响的界面或操作。打包成功本身不等于运行时验证通过。`python3 tests/test_build_failures.py` 使用故意失败的工具 fixture 检查失败传播，不构建真实 APK。

### 设备选择与已安装版本

连接多个设备时，使用明确的设备序列号：

```sh
adb devices
adb -s SERIAL install -r app/out/pi-android.apk
adb -s SERIAL shell am force-stop dev.pi.android
adb -s SERIAL shell am start -n dev.pi.android/.ui.MainActivity
```

将 `SERIAL` 替换为目标设备标识。主动停止并重新启动应用，是为了在新进程中复现问题；这并不意味着每次安装 APK 都会保留旧进程。不要直接假定设备正在运行最新 APK，应先检查安装错误、签名身份和设备状态。生成的 `app/build/gen/dev/pi/android/BuildInfo.java` 记录构建时间和提交 ID；设置页面会显示这两项，可据此在设备上核对已安装构建。

<a id="p12"></a>

### 运行时诊断（P12）

本节保留现有源码注释以“P12”引用的实用诊断方法。

优先使用 Android 标准诊断工具：

```sh
adb -s SERIAL logcat -b crash
adb -s SERIAL shell dumpsys activity activities
```

条件允许时，收集相关应用日志和截图。截图可能过期，也可能无法反映窗口被遮挡的情况；诊断 UI 行为时，应同时提供复现步骤以及 Activity/窗口状态。特定设备的显示或日志行为不应被当作 Android 的普遍限制。

`BridgeService` 还提供按需启用的 trace 通道：它在**服务创建时**检查应用私有文件目录中是否存在 `debug.trace`，若存在则向同目录的 `debug.log` 追加记录。访问这些文件需要相应设备权限；普通非 root 安装不会允许任意 shell 命令读取这些私有文件。在获得授权的 root 测试设备上，可在该应用数据目录中管理标记文件，并重启服务使变更生效。排查结束后，移除标记文件并重启。请将 trace 文件视为敏感数据，在分享前删除或遮盖隐私内容。

例如，在已获授权、应用数据使用标准路径的 root 测试设备上：

```sh
adb -s SERIAL shell "su -c 'touch /data/data/dev.pi.android/files/debug.trace'"
adb -s SERIAL shell am force-stop dev.pi.android
adb -s SERIAL shell am start -n dev.pi.android/.ui.MainActivity
# 复现问题后，私下收集 trace。
adb -s SERIAL shell "su -c 'cat /data/data/dev.pi.android/files/debug.log'"
adb -s SERIAL shell "su -c 'rm /data/data/dev.pi.android/files/debug.trace'"
```

移除标记后，应再次重启应用/服务以关闭 tracing。路径和 `su` 权限取决于测试设备及 Android 用户，这些命令不适用于无特权安装。若使用虚拟屏，必须先确认其处于活动/ON 状态，再将截图作为证据。[回归排查记录](debugging-20261010.md)和[移动端交互指南](mobile-interaction.md)记录了隔离 UI fixture 与取证限制；它们属于历史报告，不能证明新构建已经通过测试。

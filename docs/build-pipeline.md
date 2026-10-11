# pi-android 构建流水线（无 Gradle）

本仓库**故意不用 Gradle**。Termux 场景里往往只有 `aapt2 / javac / d8 / apksigner / python3`；
桌面 / CI 则用 Android cmdline-tools 提供同样的二进制。Gradle 干的活拆成：

```
app/tools/fetch-aars.py    ← 替代 gradle 的依赖解析（Google Maven + Maven Central，传递依赖 + 版本择优）
app/tools/gen-dep-r.py     ← 替代 AGP 的依赖 R 类生成
app/tools/assemble.py      ← 替代 AGP 的打包（dex + AAR 的 assets/.so 合并）
app/build.sh               ← 串起来：aapt2 → javac → d8 → assemble → apksigner
Makefile                   ← 仓库根目录规范入口：make deps / make apk
```

不可省的工具适配器。`app/tools/lib-resources.py`（把依赖做成 `aapt2 link --static-lib` 静态库）
**当前不在主构建路径里**，保留它只是作为记录与备用：见下 §3 结论。

贡献入口与最短路径：[`CONTRIBUTING.md`](../CONTRIBUTING.md)。

```sh
make apk                                                 # 或：bash app/build.sh --only-apk
adb install -r app/out/pi-android.apk
PHONE_VD_SERIAL=<serial> phone-vd-start --app dev.pi.android   # 虚拟屏验证（可选）
```

依赖落在 `app/libs/`（约 59 个构件，含 `manifest.json` 的 sha256 审计清单；以实际拉取为准），
**不进 git**；任何机器重建都靠下面的 `--root` 命令（与 CI 相同）。

## 非 Termux 环境变量

`app/build.sh` 默认：

```sh
ANDROID_JAR="${ANDROID_JAR:-$HOME/antdaily/android/android.jar}"   # Termux 布局
```

并要求 `aapt2` / `d8` / `apksigner` / `python3` 在 `PATH` 上。桌面或 CI 请显式设置：

```sh
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/build-tools/35.0.0:$PATH"   # 与 CI BUILD_TOOLS_VERSION 对齐即可
export ANDROID_JAR="$ANDROID_HOME/platforms/android-36/android.jar"
```

- **MIN_SDK=26** / **TARGET_SDK=36**（见 `app/build.sh`）
- 平台 jar 必须来自 `platforms;android-36`
- 请用 `bash app/build.sh …` 调用（脚本 shebang 指向 Termux 的 bash 路径）

## 依赖清单（一次即可）

```sh
cd app
python3 tools/fetch-aars.py \
  --root androidx.appcompat:appcompat:1.7.0 \
  --root androidx.recyclerview:recyclerview:1.3.2 \
  --root androidx.constraintlayout:constraintlayout:2.2.1 \
  --root com.google.android.material:material:1.12.0 \
  --root org.jetbrains.kotlin:kotlin-stdlib:1.9.24 \
  --root com.squareup.okhttp3:okhttp:4.12.0 \
  --root androidx.swiperefreshlayout:swiperefreshlayout:1.1.0 \
  --exclude org.jetbrains.kotlin:kotlin-stdlib-jdk7 \
  --exclude org.jetbrains.kotlin:kotlin-stdlib-jdk8 \
  --exclude org.jetbrains.kotlin:kotlin-stdlib-common
python3 tools/axml-package.py --all --libs libs      # 读每个 AAR 的 package（生成 libs/packages.json）
```

`okhttp` 必须显式 `--root`：源码 `BridgeConnection` 直接 `import okhttp3.*`，传递依赖图里
AndroidX 不会带上它。图片 EXIF 旋正用平台 `android.media.ExifInterface`（minSdk 26 ≥ 24），
不拉 `androidx.exifinterface`。

## CI（GitHub Actions）

仓库工作流 [`.github/workflows/build-apk.yml`](../.github/workflows/build-apk.yml) 在每次
**push 到 `master`**、**指向 `master` 的 PR**、**手动 `workflow_dispatch`**、以及 **`v*` tag** 时：

1. 装 JDK 17 + Android cmdline-tools，拉 `platforms;android-36` 与 `build-tools;35.0.0`
   （提供 `aapt2` / `d8` / `apksigner` 与 `android.jar`，与 `app/build.sh` 的 TARGET_SDK=36 对齐）；
2. 按上面的命令拉依赖并跑 `axml-package.py`；
3. `ANDROID_JAR=…/platforms/android-36/android.jar bash app/build.sh --only-apk`；
4. 上传产物 **`pi-android` artifact**（路径 `app/out/pi-android.apk`）；tag 推送时额外挂到
   GitHub Release。

签名用 `build.sh` 现场生成的自签 `pi-android.jks`（默认密码 `android`），**不需要**仓库 Secrets。

### 下载 APK

1. 打开仓库 **Actions** → 选中一次成功的 **Build APK** 运行；
2. 页面底部 **Artifacts** → 下载 `pi-android`（解压得到 `pi-android.apk`）；
3. 或对 tag：在 **Releases** 页直接下 APK。

手动触发：

```sh
gh workflow run build-apk.yml --ref master
gh run watch   # 或打开 Actions 页看进度
```

## 坑目录（全部实证，按被踩顺序）

### P1 · AndroidX 是 Kotlin 写的，`kotlin-stdlib` 必须自己加
POM 依赖图里不声明它（但 bytecode 用了 `kotlin.jvm.internal.Intrinsics`）。
症状：`ClassNotFoundException: kotlin.jvm.internal.Intrinsics`（发生在 `AppCompatActivity` 构造里）。
而且 gradle 会把 `kotlin-stdlib-jdk7/jdk8` 提升到同一版本（那两个包 1.8 之后是空壳），
我们按最高版本择优会留下 1.6.21 的旧版 → d8 报 `Type ... is defined multiple times`，
所以必须 `--exclude` 掉 `kotlin-stdlib-jdk7/jdk8/common`。

### P2 · 版本号要从两段剥 `[]`，POM 里的版本写成 `[2.6.2]`
只 `lstrip` 会留下 `2.6.2]`，于是下载目录叫 `libs/.../2.6.2]/`，下次按内存路径找不到
→ 症状是某个 jar 静默缺失，最后在运行时炸 `NoClassDefFound: LifecycleOwner`。现已 `strip`。

### P3 · 依赖既有 AAR 也有 JAR，classpath 不能只搜 `classes.jar`
`androidx.annotation`、`kotlin-stdlib`、`error_prone_annotations` 是纯 jar；
`find "$LIBS" -name '*.jar'` 才覆盖得到（`classes.jar` 也是 `.jar`，一并被捡到）。

### P4 · **最关键**：依赖的 `R` 类不会随 AAR 来，必须补生成
AAR 的 `classes.jar` 引用 `androidx.appcompat.R`，但 AAR 不带 R 类（Gradle 会按最终合并的
资源表给每个依赖包生成一份）。没有这一步的运行时症状：
`ClassNotFoundException: androidx.appcompat.R$drawable` —— 从错误信息完全看不出是资源问题。
`gen-dep-r.py` 的做法：

1. `libs/packages.json`（由 `axml-package.py` 读 AAR 的 AndroidManifest.xml 得到包名
   —— 不能靠 groupId 猜：`core` 是 `androidx.core`，`core-ktx` 却是 `androidx.core.ktx`）；
2. AAR 里的 `R.txt` 给出该库的资源名清单（type + name，忽略其中的旧 id）；
3. aapt2 link 生成的 `dev/pi/android/R.java` 里有合并后的最终 id；
4. 按名字取值，写出 `<pkg>.R`。`styleable` 是 `int[]`，必须单独认出来，
   否则 Material3 会缺 1600+ 个样式数组（表现为主题相关的神秘崩溃）。

### P5 · 包名要真读 manifest，别猜
见 P4 括号里的两个例子。另外 AndroidX 的 AAR manifest 是**未编译的文本 XML**
（`<?xml` 开头，不是 `03 00 08 00`），所以 `axml-package.py` 两条路都支持。

### P6 · 别指望保留各自包名：依赖资源直接 `-R` 给应用 link 的后果
这条路（每个依赖 `aapt2 compile` 后直接喂给应用的 link）的结果是资源被合并进应用包，
同时刷出 1200 条 `No package ID 7f found for resource ID 0x7f…`。
实测：多数资源其实合并成功（应用表里 7233 个 id，material 的 color/dimen/layout/id
一个不缺），所以这些报错更像"把跨库引用烘成数字 id 时无处查找"的告警，不是资源丢失。
目前的成品 APK 就是这条路径的产物，已在虚拟屏上验证渲染正确（见 §验证）。
若后续某个依赖开始出现资源解析失败，再改用 `lib-resources.py` 的静态库路径。

### P7 · 若真走静态库路径，依赖要用 `-I` 而不是 `-R`
`aapt2 link --static-lib -I android.jar -I dep.apk -R own.zip`。
用 `-R dep.apk` 会把依赖的资源并入本库包名，反而让 core 引用自己的资源时找不到：
`resource id/normal (aka androidx.drawerlayout:id/normal) not found`。
即便如此，`appcompat` 仍会失败：`res/color/*.xml` 里的 `app:alpha` 是一个
**core 声明的 attr**（`libs/androidx/core/core/1.13.0/res/values/values.xml` 第 117 行），
res-auto 在本库 link 时解析到 `androidx.appcompat:alpha` 就找不到；
material 又依赖 appcompat，一并失败。这也是 P6 决定先不走这条路的原因。

### P8 · `adb` 本机连了两个 serial，`install` 必须带 `-s`
`127.0.0.1:5555` 与 `emulator-5554` 都是本机虚拟屏/ADB 自连；phone-* 脚本要用
`PHONE_VD_SERIAL` 指定，否则报「检测到多个 ADB device」。

### P9 · 单个 dex 目前够用，超过 64K 方法要手动开 multidex
现在产一棵 `classes.dex`（含全部 AndroidX），没触发上限。真到那一步，
d8 默认不会自己拆 dex，需要显式切 multi-dex 并在 Application 里挂 `MultiDex`。

### P10 · 虚拟屏上 AlertDialog 可能“存在但不可见”，阻塞式扩展 UI 别用它
同一个 Activity 里，`AlertDialog.Builder(this).show()` 不抛异常、`isShowing()` 返回 true，
但屏幕上根本看不到（同一次会话里 RecyclerView 内容正常显示）。为了定位这个坑花了不少轮：
先把构建时间烘进 UI（`build.sh` 生成 `BuildInfo.java`）确认跑的是新代码，再靠**界面里的可见
取证**（见 #66：本机 App 的 logcat 收不到，连 `Log.i` 都没有）一步步排除。
结论：select/confirm/input/editor 这类**会阻塞 Pi** 的请求，UI 改用 Activity 布局里的按钮行，
不要用 AlertDialog。`list_sessions` 那种“信息型”列表对话框仍可以用 AlertDialog（实测可见）。

### P11 · 验证 Debugging 的时序：改完 APK 要 `am force-stop`
`adb install -r` 之后，正在运行的进程仍跑旧 dex —— 这不是缓存错觉，是 Android 的加载行为。
每次验证新构建都要：`install -r` → `am force-stop dev.pi.android` → 重启虚拟屏 → 立刻操作。
虚拟屏大约 1 分钟内会把窗口息掉、tap 失去响应，验证脚本要把“启动 → 意图 → 抓屏”压在一轮里做完。

### P12 · App 的 logcat 收不到，取证要自己铺一条路
本机 App 的 logcat **收不到任何本 App 的输出**（连 `Log.i` 都没有，同进程的其它 App 日志正常），
所以 UI 之外的事实要取证只能靠：

1. 把构建时间/commit 烘进界面（`build.sh` 生成 `BuildInfo.java`）—— 确认设备上跑的是哪一份 APK；
2. 虚拟屏截图 + `tesseract -l chi_sim+eng` OCR（注意记忆 #56：虚拟屏息屏时可能返回最后一帧，
   文件大小不再是活性判断；务必同时看 `DisplayInfo.state`）；
3. **服务端 trace 文件**：App 侧 trace 追加到 `/data/data/dev.pi.android/files/debug.log`，
   默认关闭，打开方式：`su -c 'touch /data/data/dev.pi.android/files/debug.trace'`，
   读取：`su -c 'cat /data/data/dev.pi.android/files/debug.log'`。
   崩溃仍然能从 `adb logcat -b crash` 里拿到。
4. 通知类的显式证据：`adb shell dumpsys notification --noredact | grep dev.pi.android`
   —— 这条比截图可靠得多。

另外 `Activity.isResumed()` 是 **final** 的：自定义接口不要叫这个名字，否则运行期
`LinkageError`（方法冲突，App 直接起不来，只在 `logcat -b crash` 里能看到）。

## 验证（2026-10-10）

安装后在虚拟屏启动 `dev.pi.android`，`dumpsys activity` 显示
`topResumedActivity=... dev.pi.android/.ui.MainActivity`；
抓屏 + `tesseract -l chi_sim+eng` 读出屏幕上确有工具栏状态 "Connecting…"、
INFO 文案与输入框 hint "Message Pi…"。**判断 UI 是否真的渲染出来要用 OCR/窗口 dumpsys 取证，
不要只凭截图文件大小**（见可见czy记忆 #56：虚拟屏熄屏时最后一帧仍可能很大）。

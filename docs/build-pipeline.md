# APK build reference

**English** | [简体中文](build-pipeline.zh-CN.md)

This document describes the repository's **no-Gradle** APK pipeline and practical troubleshooting. For initial setup, see [CONTRIBUTING.md](../CONTRIBUTING.md); for app and connector configuration, see [README.md](../README.md). Review [NOTICE.md](../NOTICE.md) before distributing APKs or changing branding.

## Build contract

[app/build.sh](../app/build.sh) builds package `dev.pi.android` with minimum API **26 (Android 8.0)** and target API **36**. Use Android SDK Platform 36's `android.jar`. CI uses JDK 17 and Android Build-Tools 35.0.0; `javac` compiles the sources to Java 11 bytecode.

The host needs Bash, Make, Python 3, `javac`, `keytool`, `aapt2`, `d8`, `apksigner`, and standard Unix utilities. Dependency resolution needs access to Google Maven and Maven Central. `adb` is optional for building, required for the documented installation commands. No Gradle installation or additional Python packages are required.

The isolated regression suite additionally uses Node.js 22/npm, `curl`, and `sha256sum`; see [CONTRIBUTING.md](../CONTRIBUTING.md) for dependency setup and the distinction between CI tests and additional local regressions.

All examples run from the repository root:

```sh
export ANDROID_HOME=/path/to/android-sdk
export PATH="$ANDROID_HOME/build-tools/35.0.0:$ANDROID_HOME/platform-tools:$PATH"
export ANDROID_JAR="$ANDROID_HOME/platforms/android-36/android.jar"
make apk
```

Use host-compatible tools, including Termux-compatible binaries when building on Android. Set `ANDROID_JAR` explicitly rather than relying on the script's legacy Termux-specific fallback. `ANDROID_HOME` alone is not enough: the script does not use it to locate `android.jar`. Invoke `app/build.sh` with `bash` to avoid its Termux-specific shebang on desktop hosts.

## Commands and outputs

The [Makefile](../Makefile) is the canonical local entry point:

| Command | Effect |
| --- | --- |
| `make deps` | Resolve/download dependencies and generate AAR package metadata |
| `make packages` | Regenerate package metadata from existing AARs |
| `make apk` / `make build` | Run dependency preparation, then build and sign the APK |
| `bash app/build.sh --only-apk` | Build from existing dependencies only |
| `make install` | Run `make apk`, then `adb install -r` |
| `make clean` | Delete `app/libs/`, `app/build/`, and `app/out/`, but not `app/pi-android.jks` |

The script recreates `app/build/` on each build. Running it without arguments builds an APK but does **not** install it; installation is attempted only with `--install`. Prefer `make install` or an explicit `adb` command so installation failures are visible directly.

| Path | Contents |
| --- | --- |
| `app/libs/` | Downloaded and extracted dependencies |
| `app/libs/manifest.json` | Dependency coordinates, downloaded artifact sizes, and SHA-256 records |
| `app/libs/graph.json` | Resolved dependency graph |
| `app/libs/packages.json` | AAR paths mapped to manifest package names |
| `app/build/` | Compiled resources, generated Java, classes, DEX, and unsigned APK |
| `app/out/pi-android.apk` | Signed APK |
| `app/pi-android.jks` | Reused local signing keystore |

These generated paths are excluded from version control. `manifest.json` records downloads; it is not a committed dependency lockfile or a guarantee that cached files are revalidated on every run.

## Pipeline stages

### 1. Resolve dependencies

`make deps` runs [fetch-aars.py](../app/tools/fetch-aars.py), then [axml-package.py](../app/tools/axml-package.py). Root dependencies and exclusions are maintained in the Makefile and repeated in the [CI workflow](../.github/workflows/build-apk.yml); update both lists together.

The resolver reads POM dependencies from Google Maven and Maven Central, chooses a version for each `group:artifact` using its own version comparison, downloads AARs/JARs, and extracts AAR classes, resources, and `R.txt`. It is not a general replacement for Maven or Gradle dependency semantics.

Preserve these invariants when changing dependencies:

- The classpath and DEX inputs need both AAR `classes.jar` files and standalone JAR artifacts.
- OkHttp is an explicit root dependency for the connector client.
- Image EXIF rotation uses the platform `android.media.ExifInterface`, available below the app's minimum API level; no `androidx.exifinterface` dependency is required.
- Kotlin's standard library is included explicitly. The current `kotlin-stdlib-jdk7`, `kotlin-stdlib-jdk8`, and `kotlin-stdlib-common` exclusions prevent overlapping Kotlin artifacts in this dependency set; do not remove them without reviewing the resolved inputs.
- Read package names from each AAR's manifest, not from Maven coordinates. The package helper handles text XML and binary XML manifests.
- Changing versions can leave old, non-excluded artifacts in `app/libs/`. Since the build includes every JAR it finds, use `make clean` and rebuild after dependency changes.

### 2. Compile and link resources

`aapt2 compile` processes app resources and each dependency resource directory. `aapt2 link` merges them into `app/build/base.apk`, producing the resource table and `app/build/gen/dev/pi/android/R.java`. Dependency resources are linked into the app package through `-R` inputs.

The app's Meslo font family requires both `app/res/font/meslo_lgldz_nf_regular.ttf` and `meslo_lgldz_nf_bold.ttf`, referenced by `meslo_lgldz_nf.xml`. These files are included in the repository and are not downloaded by `make deps`. Preserve their notices in `app/res/raw/`. If resource linking reports missing `font/meslo_lgldz_nf_*`, restore the required font resources rather than removing theme references. UI changes must also follow the [UI guidelines](ui-guidelines.md) (Chinese), including `AppFonts` fallback and the prohibition on redundant interaction hints.

[gen-dep-r.py](../app/tools/gen-dep-r.py) then generates the dependency packages' `R` classes. It combines package names from `packages.json`, symbol names from each `R.txt`, and final IDs from the app's generated `R.java`. Both scalar `int` fields and `styleable` `int[]` fields matter. AAR bytecode may reference these generated classes even though the AAR does not supply them.

[lib-resources.py](../app/tools/lib-resources.py) is not invoked by the current build. Do not assume a per-library static-resource-linking stage is active.

### 3. Compile Java and generate DEX

The build generates `BuildInfo.java` with a UTC build timestamp and the short Git commit ID (or `none` if unavailable). It compiles app and generated Java sources against `android.jar` and all dependency JARs, then passes the compiled classes and those JARs to `d8 --release --min-api 26`.

`build.sh` uses `set -euo pipefail`: a failed app or dependency `aapt2 compile` stops the build. Java compiler output is saved to `app/build/javac.log`; a failed `javac` prints that log and exits before DEX generation or signing. Warning filtering is applied only after successful compilation.

The packager includes all `classes*.dex` files emitted by D8, not just `classes.dex`. Android API 26 supports native multidex; a method-count increase does not by itself require a legacy `MultiDex` application hook. Inspect the actual D8 error and outputs before changing the DEX configuration.

### 4. Assemble and sign

[assemble.py](../app/tools/assemble.py) starts from `base.apk` and adds:

- Every `classes*.dex` file from the D8 output directory.
- Assets from dependency AARs and `app/assets/`.
- AAR native libraries, mapping `jni/<abi>/*.so` or `libs/<abi>/*.so` to APK `lib/<abi>/` entries.

The build creates `app/pi-android.jks` if absent, then uses `apksigner` with v1, v2, and v3 signing enabled to write `app/out/pi-android.apk`. The keystore alias is `piandroid`; its default password is `android`, overridable through `KS_PASS`. An existing keystore must be opened with its existing password.

The script embeds timestamps and uses a generated key when no keystore exists, so this process does not promise byte-for-byte reproducible APKs.

## CI distribution and signing identity

The **Build APK** workflow runs for pushes to `master`, pull requests targeting `master`, `v*` tag pushes, and manual dispatch. It installs the toolchain, prepares dependencies, runs isolated RPC/JVM/build-failure regressions, builds the APK, and uploads an Actions artifact named **`pi-android`**, with a configured retention of **30 days**. Download and extract the artifact to obtain `pi-android.apk`. The regression commands and additional tests not yet in CI are listed in [CONTRIBUTING.md](../CONTRIBUTING.md); CI success does not establish device UI compatibility.

Tag builds also attach the same APK to a GitHub Release. Branch and pull-request builds only publish Actions artifacts. Release publication uses `GITHUB_TOKEN`; no custom signing secret is configured.

**There is no persistent CI signing identity in the current workflow.** Each fresh CI checkout generates a new keystore. A release attachment therefore does not imply a stable release signing key. Local builds reuse `app/pi-android.jks`, and `make clean` preserves it. Keep that file private and back it up if you need compatible updates.

An update normally needs the installed app's signing identity. APKs from separate CI runs, or from local and CI builds, can fail with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Use the original key or intentionally uninstall the old app before installing the new APK. Uninstallation removes local app data; do not use it as an automatic troubleshooting step.

## Troubleshooting

### Missing tools, platform JAR, or dependencies

Confirm `ANDROID_JAR` points to the installed API 36 platform JAR and that all required tools are on `PATH`. Check the host architecture when a tool cannot execute. Use `make deps` for a missing dependency tree; use `make packages` when AARs exist but package metadata needs regeneration.

A missing class such as `kotlin.jvm.internal.Intrinsics` or `LifecycleOwner` can indicate missing dependency bytecode. Check the resolved artifacts and both compilation and DEX inputs rather than adding an unrelated UI workaround.

### Duplicate classes or stale dependency versions

For D8 errors such as `Type ... is defined multiple times`, inspect the JAR inputs, versions, and Kotlin exclusions. Rebuild from a clean dependency tree after changing versions:

```sh
make clean
make apk
```

This removes downloaded dependencies and output files but retains the local keystore.

### Missing resources or dependency `R` classes

For `ClassNotFoundException` involving a dependency's `R` class, inspect `packages.json`, that AAR's `R.txt`, and the generated dependency `R.java`. Theme or styleable errors also require checking generated arrays, not just scalar resource IDs.

Resource-compilation and Java-compilation failures now terminate the build; do not rely on older advice that those failures are ignored. Read the complete `aapt2` diagnostics and `app/build/javac.log`. The dependency `R` generator can still skip symbols absent from the merged table, so inspect its missing-field reports and generated classes, then reproduce the affected screen or operation. Packaging success alone is not runtime validation. `python3 tests/test_build_failures.py` checks failure propagation with intentionally failing tool fixtures without building a real APK.

### Device selection and installed version

Use an explicit device serial when multiple devices are connected:

```sh
adb devices
adb -s SERIAL install -r app/out/pi-android.apk
adb -s SERIAL shell am force-stop dev.pi.android
adb -s SERIAL shell am start -n dev.pi.android/.ui.MainActivity
```

Replace `SERIAL` with the intended device identifier. A deliberate stop/start establishes a fresh process for reproduction; it is not a claim that every APK installation leaves the old process running. Check installation errors, signing identity, and device state before assuming the latest APK is running. Generated `app/build/gen/dev/pi/android/BuildInfo.java` records the build timestamp and commit; the Settings screen displays both, allowing the installed build to be checked on-device.

<a id="p12"></a>

### Runtime diagnostics (P12)

This section retains the practical diagnostics referenced as “P12” by existing source comments.

Start with Android's normal diagnostic tools:

```sh
adb -s SERIAL logcat -b crash
adb -s SERIAL shell dumpsys activity activities
```

Capture relevant app logs and screenshots where available. A screenshot alone can be stale or fail to show an obscured window; include reproduction steps and activity/window state when diagnosing UI behavior. Device-specific display or logging behavior is not a general Android limitation.

`BridgeService` also has an opt-in trace channel: it checks for `debug.trace` in the app's private files directory **when the service is created**, then appends to `debug.log` in the same directory. Access requires suitable device privileges; normal non-root installations do not expose those private files through arbitrary shell commands. On an authorized rooted test device, manage the marker in that app data directory and restart the service for changes to take effect. Remove the marker and restart after investigation. Treat trace files as sensitive and redact private content before sharing.

For example, on an authorized rooted test device whose app data is at the standard path:

```sh
adb -s SERIAL shell "su -c 'touch /data/data/dev.pi.android/files/debug.trace'"
adb -s SERIAL shell am force-stop dev.pi.android
adb -s SERIAL shell am start -n dev.pi.android/.ui.MainActivity
# Reproduce the problem, then collect the trace privately.
adb -s SERIAL shell "su -c 'cat /data/data/dev.pi.android/files/debug.log'"
adb -s SERIAL shell "su -c 'rm /data/data/dev.pi.android/files/debug.trace'"
```

Restart the app/service again after removing the marker to disable tracing. The path and `su` access depend on the test device and Android user; these are not instructions for unprivileged installations. If a virtual display is involved, verify its active/ON state before treating screenshots as evidence. The [regression investigation](debugging-20261010.md) and [mobile interaction guide](mobile-interaction.md) (Chinese) document isolated UI fixtures and evidence limitations; they are historical reports, not proof that a new build has been tested.

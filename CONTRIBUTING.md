# Contributing to pi-android

Unofficial Android client for Pi. This repo intentionally **does not use Gradle**;
APKs are built with `aapt2 → javac → d8 → assemble → apksigner` via `app/build.sh`.

Please keep PRs focused. Product roadmap / large features belong in discussion first —
see README「已实现 / 待办」for current scope.

## Prerequisites

| Tool | Notes |
| --- | --- |
| JDK 17+ (`javac`, `keytool`) | CI uses Temurin 17; sources compile with `-source 11 -target 11` |
| Python 3 | `fetch-aars.py`, `axml-package.py`, `assemble.py`, … |
| `aapt2`, `d8`, `apksigner` | From Android **build-tools** (CI: `35.0.0`) |
| `android.jar` | From Android **platforms;android-36** — export as `ANDROID_JAR` |

### Non-Termux (desktop / CI)

```sh
export ANDROID_HOME=/path/to/android-sdk
export PATH="$ANDROID_HOME/build-tools/35.0.0:$PATH"
export ANDROID_JAR="$ANDROID_HOME/platforms/android-36/android.jar"
```

`app/build.sh` defaults `ANDROID_JAR` to `$HOME/antdaily/android/android.jar` (Termux layout).
On any other host you **must** set `ANDROID_JAR` explicitly.

Always invoke the script with bash (shebang is Termux-specific):

```sh
bash app/build.sh --only-apk
```

### Termux

If `aapt2` / `d8` / `apksigner` are already on `PATH` and `android.jar` lives at the
default path above, you can skip the exports.

## Fetch dependencies

From `app/` (same roots as CI / `.github/workflows/build-apk.yml`):

```sh
cd app
python3 tools/fetch-aars.py \
  --root androidx.appcompat:appcompat:1.7.0 \
  --root androidx.recyclerview:recyclerview:1.3.2 \
  --root androidx.constraintlayout:constraintlayout:2.2.1 \
  --root com.google.android.material:material:1.12.0 \
  --root org.jetbrains.kotlin:kotlin-stdlib:1.9.24 \
  --root com.squareup.okhttp3:okhttp:4.12.0 \
  --exclude org.jetbrains.kotlin:kotlin-stdlib-jdk7 \
  --exclude org.jetbrains.kotlin:kotlin-stdlib-jdk8 \
  --exclude org.jetbrains.kotlin:kotlin-stdlib-common
python3 tools/axml-package.py --all --libs libs
```

`okhttp` is required (`BridgeConnection` imports `okhttp3`). `app/libs/` is gitignored;
rebuild from this command on every clean checkout.

Or: `make deps` from the repo root (see `Makefile`).

## Build the APK

```sh
# after deps + ANDROID_JAR / PATH as above
bash app/build.sh --only-apk          # → app/out/pi-android.apk
# or
make apk
```

Signing uses a locally generated self-signed `app/pi-android.jks` (default password
`android`). No GitHub Secrets required. Do not commit the keystore.

Install (optional):

```sh
adb install -r app/out/pi-android.apk
# or: make install
```

Deep dive / failure modes: [`docs/build-pipeline.md`](docs/build-pipeline.md).

## Download a CI-built APK

1. GitHub → **Actions** → workflow **Build APK** → pick a green run
2. Bottom **Artifacts** → download **`pi-android`** → unzip → `pi-android.apk`
3. Tags `v*`: also attached on the **Releases** page

Manual dispatch (after the workflow exists on `master`):

```sh
gh workflow run build-apk.yml --ref master
gh run watch
```

## Connector / 连接器 (Termux)

APK talks to `bridge/pi-rpc-bridge.mjs` on loopback + token（用户可见文案称「连接器」；
代码里仍用 `BridgeService` 等标识符）。Keep it bound to `127.0.0.1`. Official
`bash` / `abort_bash` are whitelisted for Pocket `!shell` / `!!shell`（`--allow-bash`
仍为同义词）。See README「安全边界」and `NOTICE.md` for trademark / logo rules.

## Style

- Java / XML: 4-space indent (see `.editorconfig`)
- Prefer incremental, reviewable diffs; keep `app/build.sh` green
- Do not migrate to Gradle unless there is a hard blocker

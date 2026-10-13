# Contributing to pi-android

**English** | [简体中文](CONTRIBUTING.zh-CN.md)

pi-android is an unofficial Android client for Pi. Read the [project overview](README.md) for scope, setup, and security boundaries, and [NOTICE.md](NOTICE.md) for third-party attribution and branding considerations.

Keep contributions focused and discuss substantial changes before implementation. The APK uses a deliberately small, **no-Gradle** build pipeline: `aapt2 → javac → d8 → assemble → apksigner`.

## Build requirements

The app requires **Android 8.0 (API 26) or later** and targets **API 36**. These values are set by [app/build.sh](app/build.sh); targeting API 36 does not mean the device must run API 36.

| Requirement | Purpose |
| --- | --- |
| Bash, Make, and standard Unix command-line utilities | Run the build script and repository targets |
| JDK 17 | CI uses Temurin 17; Java sources compile with `-source 11 -target 11` |
| Python 3 | Run the repository's dependency and packaging helpers; no additional Python packages are required |
| Android SDK Platform 36 | Supplies `android.jar` |
| Android SDK Build-Tools 35.0.0 | CI version; supplies `aapt2`, `d8`, and `apksigner` |
| Network access to Google Maven and Maven Central | Resolve and download dependencies |
| Android SDK Platform-Tools (`adb`), optional | Install and inspect the app on a device or emulator |
| Node.js 22 and npm, for connector development and isolated tests | Match the CI test runtime |
| `curl` and `sha256sum`, for JVM test setup | Download and verify the separate JSON test dependency |

Use host-compatible Android tools. On Termux, the binaries must work in the Termux environment; desktop SDK binaries are not interchangeable with Android-hosted tools.

From the repository root, configure a desktop SDK installation:

```sh
export ANDROID_HOME=/path/to/android-sdk
export PATH="$ANDROID_HOME/build-tools/35.0.0:$ANDROID_HOME/platform-tools:$PATH"
export ANDROID_JAR="$ANDROID_HOME/platforms/android-36/android.jar"
```

Set `ANDROID_JAR` explicitly on any host. The script has a legacy Termux-specific fallback path; it does not discover the SDK from `ANDROID_HOME`. Invoke it with `bash`, because its shebang points to Termux's Bash installation.

## Dependencies and build

Run all commands below from the repository root:

```sh
make apk
```

`make apk` runs `make deps` first, then `bash app/build.sh --only-apk`. The signed output is **`app/out/pi-android.apk`**. `make build` is an alias for `make apk`.

Useful targets:

| Command | Behavior |
| --- | --- |
| `make deps` | Fetch AAR/JAR dependencies into `app/libs/` and generate `packages.json` |
| `make packages` | Regenerate `packages.json` from already downloaded AARs |
| `bash app/build.sh --only-apk` | Build using the existing dependencies, without fetching or installing |
| `make install` | Build, then run `adb install -r app/out/pi-android.apk` |
| `make clean` | Remove `app/build/`, `app/out/`, and `app/libs/`; preserve the signing keystore |

Dependency roots and exclusions are defined in the [Makefile](Makefile). If you change them, update the matching list in the [CI workflow](.github/workflows/build-apk.yml). The dependency resolver is purpose-built, not a full Maven or Gradle implementation. After changing dependency versions, use `make clean` followed by `make apk` to avoid retaining obsolete JARs.

The MesloLGLDZ Nerd Font Regular and Bold files in `app/res/font/` are required source resources, not Maven downloads. Keep both files, their font-family XML, and the corresponding license notices in `app/res/raw/` when changing fonts; see [NOTICE.md](NOTICE.md).

Generated dependencies, build outputs, APKs, and keystores are ignored by Git. Do not commit them. See the [build reference](docs/build-pipeline.md) for resource generation, packaging, and troubleshooting.

## Regression tests

The isolated tests require JDK 17, Python 3, Node.js 22/npm, `curl`, and `sha256sum` on `PATH` (macOS may need GNU coreutils for `sha256sum`). Prepare dependencies explicitly; the JVM runners do not download missing dependencies:

```sh
make deps
npm ci --prefix bridge
bash tests/bridge-connection/setup-json.sh
```

`make deps` supplies OkHttp, Okio, Kotlin, and annotation JARs used by the bridge-connection tests. `setup-json.sh` downloads the separate `org.json:json:20240303` test JAR and verifies its SHA-256; do not commit it.

The current CI regression step runs:

```sh
bash tests/bridge-connection/run.sh
bash tests/model-state/run.sh
bash tests/extension-ui/run.sh
bash tests/ansi/run.sh
bash tests/message-queue/run.sh
python3 tests/test_build_failures.py
node --test bridge/tests/pi-rpc-bridge.test.mjs bridge/tests/suggestions.test.mjs
```

Additional checked-in regressions, not currently included in that CI step:

```sh
bash tests/transcript/run.sh
bash tests/attachments-model/run.sh
node --test bridge/tests/attachments.test.mjs tests/paced-fixture.test.mjs
bash tests/bridge-connection/verify-regressions.sh
```

The baseline comparison requires commit `6efe21f` in local Git history. It verifies specific failures against the old implementation in a temporary directory, then runs the current tests; it does not replace working-tree source files.

These tests do not need real Pi credentials or a running real Pi backend. The Python failure tests exercise `build.sh` with deliberately failing tool fixtures, not a complete APK build. For optional device UI fixtures and manual reproduction, see the [regression investigation](docs/debugging-20261010.md) and [mobile interaction guide](docs/mobile-interaction.md) (Chinese). Restore the real connection configuration after fixture testing; never leave the fixture token as production configuration.

## Signing and installation

The first build generates `app/pi-android.jks` with alias `piandroid`. Subsequent local builds reuse that file. The default password is `android`; `KS_PASS` overrides the password used when creating or opening the keystore. Changing `KS_PASS` does not change an existing keystore's password.

This is a development signing setup, not a managed release-signing service. Keep the keystore private and back it up if you need compatible local updates. Builds in fresh CI checkouts generate new keys because the workflow does not restore or provision a persistent signing key. **APKs from different CI runs, or from CI and a local build, are not guaranteed to update one another.** A release attachment uses the key from that build, not a separate stable release key.

Android normally requires the same signing identity to update an installed package. `adb install -r` cannot bypass a signature mismatch. Rebuild with the original key, or deliberately uninstall the existing app before installing the differently signed APK. Uninstalling removes the app's local data; preserve any information you need first.

To install an already built APK:

```sh
adb install -r app/out/pi-android.apk
```

With multiple connected devices, select one explicitly:

```sh
adb devices
adb -s SERIAL install -r app/out/pi-android.apk
```

Replace `SERIAL` with the device identifier. `make install` does not add `-s`; use the explicit command when device selection is needed.

## CI artifacts and releases

The **Build APK** workflow runs on pushes to `master`, pull requests targeting `master`, pushes of `v*` tags, and manual dispatch.

- **Actions artifacts:** open a successful workflow run, download the `pi-android` artifact, and extract `pi-android.apk`. Artifact retention is configured to 30 days.
- **GitHub Releases:** tag builds also attach `pi-android.apk` to the corresponding release. Branch and pull-request builds do not create releases.
- **Signing:** both distribution paths have the signing limitation described above. The workflow needs no custom signing secret; release publication uses the workflow's `GITHUB_TOKEN`.

With the GitHub CLI installed, authenticated, and authorized to dispatch workflows:

```sh
gh workflow run build-apk.yml --ref master
gh run watch
```

A successful build is not evidence of device compatibility or a complete runtime test. Report the checks you actually performed.

## Contribution expectations

- Follow [.editorconfig](.editorconfig): four spaces for Java/XML and tabs for Makefile recipes.
- Preserve the no-Gradle workflow unless a build-system change has been discussed explicitly.
- Keep English and Simplified Chinese documentation equivalent when changing documented behavior.
- Follow the [UI guidelines](docs/ui-guidelines.md) (Chinese): reuse themed sheets/dialogs, apply `AppFonts`, and retain the Meslo/CJK fallback behavior. Do not add redundant legends or hint subtitles for conventional interactions, repeat field placeholders, or duplicate model/path information beneath the top-bar model chip. Keep meaningful empty states, one-time setup guidance, and error/risk notices.
- Put user-visible strings in `strings.xml`; use “连接器” rather than “桥” in Chinese UI text.
- Include the environment, reproduction steps, relevant logs, and checks performed in bug reports or pull requests. Redact tokens, credentials, prompts, and other private data.
- For connector changes, follow the [README security guidance](README.md). Keep the recommended `127.0.0.1` binding and token authentication. Official `bash` and `abort_bash` RPCs are enabled for Pocket-style `!shell` / `!!shell`; `--allow-bash` is retained as a compatibility synonym, not a security switch. The RPC allowlist does not sandbox agent tools invoked through prompts.
- Do not imply official endorsement. Review [NOTICE.md](NOTICE.md) before changing branding or distributing APKs.

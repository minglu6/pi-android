#!/data/data/com.termux/files/usr/bin/bash
# pi-android 构建：Termux 离线链 aapt2 → javac → d8 → assemble → apksigner（本机无 gradle/S）
#   app/build.sh              构建并安装（安装见 --install）
#   app/build.sh --only-apk   只打包，不安装
# 依赖: aapt2, javac, d8, apksigner, keytool, python3（Termux 均已装）
# 依赖 jars/AAR 由 app/tools/fetch-aars.py 拉到 app/libs
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ANDROID_JAR="${ANDROID_JAR:-$HOME/antdaily/android/android.jar}"
LIBS="$HERE/libs"
BUILD="$HERE/build"
OUT="$HERE/out"
KS="$HERE/pi-android.jks"
KS_PASS="${KS_PASS:-android}"
PKG=dev.pi.android
# minSdk 26：目标是本机 Android 16；再低会被 ColorOS/厂商 launcher 与 androidx 一并拖累
MIN_SDK=26
TARGET_SDK=36

[ -f "$ANDROID_JAR" ] || { echo "缺 android.jar: $ANDROID_JAR" >&2; exit 1; }
command -v aapt2 d8 apksigner python3 >/dev/null || { echo "缺 aapt2/d8/apksigner/python3" >&2; exit 1; }

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/dex" "$BUILD/classes" "$BUILD/compiled-libs" "$OUT"

echo "[1/6] aapt2 compile：应用自身资源"
aapt2 compile --dir "$HERE/res" -o "$BUILD/res.zip"

echo "[1b/6] aapt2 compile：AndroidX / Material 的 AAR 资源"
i=0
while IFS= read -r d; do
  i=$((i+1))
  # 每个依赖一个 zip，避免 --dir 合并时资源 id 冲突报错
  aapt2 compile --dir "$d" -o "$BUILD/compiled-libs/lib${i}.zip" 2>/dev/null \
    || aapt2 compile --dir "$d" -o "$BUILD/compiled-libs/lib${i}-partial.zip" || true
done < <(find "$LIBS" -type d -name res | sort)
LIB_RES_ZIPS=($(ls "$BUILD/compiled-libs"/*.zip 2>/dev/null | sed "s|^|-R |"))
echo "     依赖资源包: ${#LIB_RES_ZIPS[@]} 个"

echo "[2/6] aapt2 link（生成含 resources.arsc 的基础 APK + R.java）"
aapt2 link -o "$BUILD/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$HERE/AndroidManifest.xml" \
    --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
    --auto-add-overlay \
    --java "$BUILD/gen" \
    "$BUILD/res.zip" ${LIB_RES_ZIPS[@]+"${LIB_RES_ZIPS[@]}"}

echo "[3/6] javac 编译源码 + R.java"
CP="$ANDROID_JAR"
# AAR 里的 classes.jar 和纯 JAR 构件（如 androidx.annotation）都要进 classpath
while IFS= read -r j; do CP="$CP:$j"; done < <(find "$LIBS" -name '*.jar' | sort)
# 依赖库自己也引用自己的 R（androidx.appcompat.R …），合并后需按最终表补这些类
python3 "$HERE/tools/gen-dep-r.py" --app-r "$BUILD/gen/dev/pi/android/R.java" \
    --libs "$LIBS" --out "$BUILD/gen"

# 生成 BuildInfo：把构建时间与 commit 烘进 APK。站外验证“设备上跑的是哪一份”时，
# 屏幕上看得见比看 logcat 靠谱 —— 本机 UMLog/app log 并不一定回收得到（见 docs/build-pipeline.md）
mkdir -p "$BUILD/gen/dev/pi/android"
GIT_HEAD="$(git -C "$HERE/.." rev-parse --short HEAD 2>/dev/null || echo none)"
cat > "$BUILD/gen/dev/pi/android/BuildInfo.java" <<JAVA
package dev.pi.android;

/** 由 build.sh 生成：build=$(date -u '+%Y-%m-%dT%H:%M:%SZ') head=$GIT_HEAD */
public final class BuildInfo {
  public static final String BUILD_TIME = "$(date -u '+%Y-%m-%dT%H:%M:%SZ')";
  public static final String HEAD = "$GIT_HEAD";
}
JAVA

find "$HERE/src" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
javac -encoding UTF-8 -nowarn \
    -source 11 -target 11 \
    -cp "$CP" \
    -d "$BUILD/classes" @"$BUILD/sources.txt" 2>&1 \
    | grep -v 'bootstrap class path\|source value\|target value\| deprecat\|警告\|warning' || true

echo "[4/6] d8 生成 dex"
# 依赖的 classes.jar 里才有 androidx 的类实现，必须一起打进 dex
d8 --release --lib "$ANDROID_JAR" --min-api "$MIN_SDK" --output "$BUILD/dex" \
    $(find "$BUILD/classes" -name '*.class') \
    $(while IFS= read -r j; do echo "$j"; done < <(find "$LIBS" -name '*.jar' | sort))

echo "[5/6] 组装 APK（dex + AAR 的 assets/.so）"
APK="$BUILD/unsigned.apk"
python3 "$HERE/tools/assemble.py" --base "$BUILD/base.apk" --dex "$BUILD/dex" \
    --libs "$LIBS" --extra-assets "$HERE/assets" --out "$APK"

echo "[6/6] 签名"
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -alias piandroid -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -dname "CN=pi-android, OU=dev, O=dev, L=CN, ST=CN, C=CN"
fi
rm -f "$OUT/pi-android.apk"
apksigner sign --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --min-sdk-version "$MIN_SDK" --out "$OUT/pi-android.apk" "$APK"
ls -l "$OUT/pi-android.apk"

if [ "${1:-}" = "--install" ]; then
    shift
    if adb install -r "$OUT/pi-android.apk"; then
        echo "已安装 $PKG"
    else
        am start -a android.intent.action.VIEW 2>/dev/null || true
    fi
fi

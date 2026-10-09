#!/usr/bin/env bash
#
# HyperOS Workbench AllApps Live-Blur Fix — 构建脚本 / build script
#
# 依赖 / Requirements:
#   - JDK 17+           (通过 JAVA_HOME，或 PATH 中的 javac/java/keytool/jar)
#   - Android SDK        (通过 ANDROID_SDK_ROOT 或 ANDROID_HOME；需含 build-tools 与 platforms)
#   - LibXposed API      (先运行 scripts/fetch-libxposed.sh，或用 LIBXPOSED_JAR 指定)
#
# 可选环境变量 / Optional env vars:
#   JAVA_HOME         JDK 根目录
#   ANDROID_SDK_ROOT  Android SDK 根目录（也接受 ANDROID_HOME）
#   LIBXPOSED_JAR     io.github.libxposed:api 的 classes.jar 路径
#   BUILD_TOOLS_VER   指定 build-tools 版本，默认自动取最高
#   PLATFORM_VER      指定 platforms/android-XX，默认自动取最高
#
set -euo pipefail

# ---------- 平台差异 ----------
# Windows(git-bash) 下传给 .exe 的路径必须是 Windows 风格（C:/...），
# 否则 aapt2.exe / java.exe 会把 "/c/Users/..." 解析失败，所以这里统一转一次。
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*)
    EXE=".exe"
    SEP=";"
    SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -W)"
    winpath() { cygpath -w "$1" 2>/dev/null || printf '%s' "$1"; }
    ;;
  *)
    EXE=""
    SEP=":"
    SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
    winpath() { printf '%s' "$1"; }
    ;;
esac
ROOT="$SELF_DIR"
B="$ROOT/build"

# ---------- 定位 JDK ----------
if [ -n "${JAVA_HOME:-}" ]; then
  JH="$(winpath "$JAVA_HOME")"
  JAVAC="$JH/bin/javac$EXE"; JAVA="$JH/bin/java$EXE"
  JAR="$JH/bin/jar$EXE";     KEYTOOL="$JH/bin/keytool$EXE"
else
  JAVAC="$(command -v javac || true)"; JAVA="$(command -v java || true)"
  JAR="$(command -v jar || true)";     KEYTOOL="$(command -v keytool || true)"
fi
for t in "$JAVAC" "$JAVA" "$JAR" "$KEYTOOL"; do
  if [ -z "$t" ] || [ ! -x "$t" ]; then
    echo "[ERROR] 找不到 JDK 工具，请设置 JAVA_HOME" >&2
    exit 1
  fi
done

# ---------- 定位 Android SDK ----------
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then
  echo "[ERROR] 找不到 Android SDK，请设置 ANDROID_SDK_ROOT 或 ANDROID_HOME" >&2
  exit 1
fi
SDK="$(winpath "$SDK")"

if [ -n "${BUILD_TOOLS_VER:-}" ]; then
  BT="$SDK/build-tools/$BUILD_TOOLS_VER"
else
  BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
  BT="${BT%/}"
fi
[ -x "$BT/aapt2$EXE" ]    || { echo "[ERROR] 缺少 aapt2: $BT/aapt2$EXE" >&2; exit 1; }
[ -x "$BT/zipalign$EXE" ] || { echo "[ERROR] 缺少 zipalign: $BT/zipalign$EXE" >&2; exit 1; }

D8_JAR="$BT/lib/d8.jar"
APKSIGNER_JAR="$BT/lib/apksigner.jar"
[ -f "$D8_JAR" ]        || { echo "[ERROR] 缺少 d8.jar" >&2; exit 1; }
[ -f "$APKSIGNER_JAR" ] || { echo "[ERROR] 缺少 apksigner.jar" >&2; exit 1; }

if [ -n "${PLATFORM_VER:-}" ]; then
  ANDROID_JAR="$SDK/platforms/$PLATFORM_VER/android.jar"
else
  ANDROID_JAR="$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"
fi
[ -f "$ANDROID_JAR" ] || { echo "[ERROR] 缺少 android.jar，请安装 platforms;android-XX" >&2; exit 1; }

# ---------- 定位 LibXposed API ----------
LX="${LIBXPOSED_JAR:-$ROOT/libs/libxposed-api-classes.jar}"
LX="$(winpath "$LX")"
if [ ! -f "$LX" ]; then
  echo "[ERROR] 缺少 LibXposed API: $LX" >&2
  echo "        请先运行: scripts/fetch-libxposed.sh" >&2
  exit 1
fi

echo "JAVA      : $JAVA"
"$JAVA" -version 2>&1 | head -1
echo "aapt2     : $BT/aapt2$EXE"
echo "android   : $ANDROID_JAR"
echo "libxposed : $LX"
echo

rm -rf "$B"
mkdir -p "$B/gen" "$B/classes" "$B/dex"

echo "[1/6] aapt2 compile (resources)"
"$BT/aapt2$EXE" compile --dir "$ROOT/res" -o "$B/res.zip"

echo "[2/6] aapt2 link (manifest + R.java)"
"$BT/aapt2$EXE" link -o "$B/base.apk" -I "$ANDROID_JAR" \
  --manifest "$ROOT/AndroidManifest.xml" --java "$B/gen" "$B/res.zip"

echo "[3/6] javac"
"$JAVAC" -source 8 -target 8 -nowarn -encoding UTF-8 \
  -cp "$ANDROID_JAR$SEP$LX" \
  -d "$B/classes" \
  $(find "$ROOT/src" -name '*.java') $(find "$B/gen" -name '*.java')

echo "[4/6] d8 (dex)"
"$JAVA" -cp "$D8_JAR" com.android.tools.r8.D8 \
  --lib "$ANDROID_JAR" --min-api 29 --output "$B/dex" \
  $(find "$B/classes" -name '*.class')

echo "[5/6] package + zipalign"
cp "$B/base.apk" "$B/unsigned.apk"
( cd "$B/dex" && "$JAR" uf "$B/unsigned.apk" classes.dex )
( cd "$ROOT" && "$JAR" uf "$B/unsigned.apk" \
    META-INF/xposed/java_init.list \
    META-INF/xposed/module.prop \
    META-INF/xposed/scope.list )
# jar uf 会破坏 aapt2 的 4 字节对齐，必须重新 zipalign，
# 否则 targetSdk >= 30 的包会因 resources.arsc 未对齐而装不上
"$BT/zipalign$EXE" -f -p 4 "$B/unsigned.apk" "$B/aligned.apk"

echo "[6/6] sign"
KS="$ROOT/keystore/debug.keystore"
if [ ! -f "$KS" ]; then
  mkdir -p "$ROOT/keystore"
  "$KEYTOOL" -genkeypair -keystore "$KS" -alias debug \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
OUT="$ROOT/HyperOS-Workbench-Blur-Fix.apk"
"$JAVA" -jar "$APKSIGNER_JAR" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$OUT" "$B/aligned.apk"

echo
echo "==================== 构建完成 / Build OK ===================="
"$JAVA" -jar "$APKSIGNER_JAR" verify --print-certs "$OUT" 2>&1 | head -3
ls -l "$OUT"

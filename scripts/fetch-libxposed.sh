#!/usr/bin/env bash
#
# 下载 LibXposed 的编译依赖（Maven Central 上都是 .aar，需要解出 classes.jar）：
#   - io.github.libxposed:api       hook 侧 API（在目标进程里用）
#   - io.github.libxposed:service   UI 侧 API（在模块自己的进程里访问 LSPosed 配置）
#
# 可用 LIBXPOSED_VER 指定版本，默认 102.0.0（对应 LSPosed API 102）。
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VER="${LIBXPOSED_VER:-102.0.0}"
BASE="https://repo1.maven.org/maven2/io/github/libxposed"

mkdir -p "$ROOT/libs"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fetch() {
  local artifact="$1" out="$2"
  local url="$BASE/$artifact/$VER/$artifact-$VER.aar"
  echo "下载 io.github.libxposed:$artifact:$VER ..."
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL -o "$TMP/$artifact.aar" "$url"
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$TMP/$artifact.aar" "$url"
  else
    echo "[ERROR] 需要 curl 或 wget" >&2
    exit 1
  fi

  if command -v unzip >/dev/null 2>&1; then
    unzip -o "$TMP/$artifact.aar" classes.jar -d "$TMP/$artifact" >/dev/null
  elif command -v jar >/dev/null 2>&1; then
    ( cd "$TMP" && mkdir -p "$artifact" && cd "$artifact" && jar xf "$TMP/$artifact.aar" classes.jar )
  else
    echo "[ERROR] 需要 unzip 或 jar" >&2
    exit 1
  fi

  mv "$TMP/$artifact/classes.jar" "$ROOT/libs/$out"
  echo "  -> libs/$out"
}

fetch api       libxposed-api-classes.jar
fetch interface libxposed-interface-classes.jar
fetch service   libxposed-service-classes.jar

# ---- R8/d8 ----
# build-tools 自带的 R8 版本偏旧（r34 里是 8.2.2），处理 libxposed-service 的
# 某些 class 时会内部 NPE，所以单独拉一份新的 R8 放到 libs/ 下，构建脚本会优先用它。
R8_VER="${R8_VER:-9.5.23}"
R8_URL="https://dl.google.com/dl/android/maven2/com/android/tools/r8/$R8_VER/r8-$R8_VER.jar"
echo "下载 R8 (d8) $R8_VER ..."
if command -v curl >/dev/null 2>&1; then
  curl -fsSL -o "$ROOT/libs/r8.jar" "$R8_URL"
elif command -v wget >/dev/null 2>&1; then
  wget -q -O "$ROOT/libs/r8.jar" "$R8_URL"
else
  echo "[ERROR] 需要 curl 或 wget" >&2
  exit 1
fi
echo "  -> libs/r8.jar"

echo
echo "完成 / Done:"
ls -l "$ROOT/libs"

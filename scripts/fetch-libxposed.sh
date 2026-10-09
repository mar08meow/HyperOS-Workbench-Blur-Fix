#!/usr/bin/env bash
#
# 下载 LibXposed API（io.github.libxposed:api）并取出其中的 classes.jar。
# Maven Central 上该 artifact 是 .aar（不是 .jar），需要解出 classes.jar 才能当编译依赖用。
#
# 可用 LIBXPOSED_VER 指定版本，默认 102.0.0（对应 LSPosed API 102）。
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VER="${LIBXPOSED_VER:-102.0.0}"
BASE="https://repo1.maven.org/maven2/io/github/libxposed/api/$VER"

DEST="$ROOT/libs/libxposed-api-classes.jar"
mkdir -p "$ROOT/libs"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "下载 io.github.libxposed:api:$VER ..."
if command -v curl >/dev/null 2>&1; then
  curl -fsSL -o "$TMP/api.aar" "$BASE/api-$VER.aar"
elif command -v wget >/dev/null 2>&1; then
  wget -q -O "$TMP/api.aar" "$BASE/api-$VER.aar"
else
  echo "[ERROR] 需要 curl 或 wget" >&2
  exit 1
fi

echo "解出 classes.jar ..."
if command -v unzip >/dev/null 2>&1; then
  unzip -o "$TMP/api.aar" classes.jar -d "$TMP" >/dev/null
elif command -v jar >/dev/null 2>&1; then
  ( cd "$TMP" && jar xf api.aar classes.jar )
else
  echo "[ERROR] 需要 unzip 或 jar" >&2
  exit 1
fi

mv "$TMP/classes.jar" "$DEST"
echo "完成 / Done: $DEST"
ls -l "$DEST"

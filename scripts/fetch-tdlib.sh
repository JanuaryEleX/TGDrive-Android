#!/usr/bin/env bash
# TG云盘：下载 TDLib 预编译库（up9cloud/android-libtdjson v1.8.65）
# 并只保留 arm64-v8a/libtdjson.so 放入 app/src/main/jniLibs/。
#
# 用法:
#   ./scripts/fetch-tdlib.sh            # 幂等：so 已存在则跳过
#   ./scripts/fetch-tdlib.sh --force    # 强制重新下载
#   ./scripts/fetch-tdlib.sh --expected-sha256 <hex>   # 覆盖期望哈希；传空串则只报告实际值
#
# 兼容 macOS/Linux/WSL/Git-Bash（依赖 curl + tar，或 wget 兜底）。
set -euo pipefail

URL="${TD_URL:-https://github.com/up9cloud/android-libtdjson/releases/download/v1.8.65/libs.tar.gz}"
EXPECTED_SHA256="${TD_EXPECTED_SHA256:-cb92b2dd62489f016c92e26f604c1658d87b0c3bbb56ca56ced6870def05f401}"
FORCE=0

for arg in "$@"; do
  case "$arg" in
    --force) FORCE=1 ;;
    --expected-sha256) EXPECTED_SHA256="__NEXT__" ;;
    --expected-sha256=*) EXPECTED_SHA256="${arg#*=}" ;;
    *)
      if [ "$EXPECTED_SHA256" = "__NEXT__" ]; then EXPECTED_SHA256="$arg"; else
        echo "用法: fetch-tdlib.sh [--force] [--expected-sha256 <hex>]" >&2; exit 2
      fi ;;
  esac
done
[ "$EXPECTED_SHA256" = "__NEXT__" ] && EXPECTED_SHA256=""

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DEST_DIR="$REPO_ROOT/app/src/main/jniLibs/arm64-v8a"
DEST_SO="$DEST_DIR/libtdjson.so"
TMP_TAR="${TMPDIR:-/tmp}/tgdrive-tdlib-libs.tar.gz"
STAGE="$REPO_ROOT/.tdlib-stage"

info() { echo "[fetch-tdlib] $*"; }
ok()   { echo "[fetch-tdlib] $*"; }
warn() { echo "[fetch-tdlib] $*" >&2; }
die()  { echo "[fetch-tdlib] $*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 1. 幂等检查
# ---------------------------------------------------------------------------
if [ -f "$DEST_SO" ] && [ "$FORCE" -ne 1 ]; then
  SIZE=$(wc -c < "$DEST_SO" | tr -d ' ')
  ok "已存在 $DEST_SO（$((SIZE / 1024 / 1024)) MB），跳过。重新拉取请加 --force"
  exit 0
fi

# ---------------------------------------------------------------------------
# 2. 下载
# ---------------------------------------------------------------------------
info "下载 $URL"
START=$(date +%s)
if command -v curl >/dev/null 2>&1; then
  curl -fSL --retry 3 -o "$TMP_TAR" "$URL"
elif command -v wget >/dev/null 2>&1; then
  wget -q --show-progress -O "$TMP_TAR" "$URL"
else
  die "需要 curl 或 wget"
fi
END=$(date +%s)
[ -f "$TMP_TAR" ] || die "下载失败：$TMP_TAR 不存在"
TAR_SIZE=$(wc -c < "$TMP_TAR" | tr -d ' ')
info "下载完成：$((TAR_SIZE / 1024 / 1024)) MB，用时 $((END - START))s"

# ---------------------------------------------------------------------------
# 3. SHA256 校验
# ---------------------------------------------------------------------------
if command -v sha256sum >/dev/null 2>&1; then
  ACTUAL="$(sha256sum "$TMP_TAR" | awk '{print $1}')"
elif command -v shasum >/dev/null 2>&1; then
  ACTUAL="$(shasum -a 256 "$TMP_TAR" | awk '{print $1}')"
else
  die "需要 sha256sum 或 shasum"
fi

if [ -n "$EXPECTED_SHA256" ]; then
  if [ "$ACTUAL" != "$(echo "$EXPECTED_SHA256" | tr 'A-Z' 'a-z')" ]; then
    die "SHA256 不匹配！
  期望: $EXPECTED_SHA256
  实际: $ACTUAL
  请到 release 页核对后用 --expected-sha256 传入新值"
  fi
  ok "SHA256 校验通过：$ACTUAL"
else
  warn "未提供期望值，实际 SHA256 = $ACTUAL（请自行与 release 页核对）"
fi

# ---------------------------------------------------------------------------
# 4. 解压
# ---------------------------------------------------------------------------
rm -rf "$STAGE"
mkdir -p "$STAGE"
tar -xzf "$TMP_TAR" -C "$STAGE"

# ---------------------------------------------------------------------------
# 5. 只保留 arm64-v8a/libtdjson.so
# ---------------------------------------------------------------------------
SO_FILE="$(find "$STAGE" -type f -name 'libtdjson.so' -path '*arm64-v8a*' | head -n 1)"
if [ -z "$SO_FILE" ]; then
  ABIS="$(find "$STAGE" -type d | sed "s|$STAGE/||" | sort -u | head -12 | tr '\n' ' ')"
  die "未在压缩包中找到 arm64-v8a/libtdjson.so。包内目录：$ABIS"
fi
mkdir -p "$DEST_DIR"
cp -f "$SO_FILE" "$DEST_SO"

# ---------------------------------------------------------------------------
# 6. 产物报告 + 清理
# ---------------------------------------------------------------------------
SO_SIZE=$(wc -c < "$DEST_SO" | tr -d ' ')
ok "产物路径：$DEST_SO"
ok "产物大小：$((SO_SIZE / 1024 / 1024)) MB（源包 $((TAR_SIZE / 1024 / 1024)) MB）"
rm -rf "$STAGE"
info "临时解压目录已清理；下载缓存保留在 $TMP_TAR（可手动删除）"
ok "完成。现在可用 Android Studio 打开 $REPO_ROOT 构建。"

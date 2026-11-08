#!/usr/bin/env bash
# Builds the libcuda.so.1 stub into the SDK's native directory.
#
# libNSurveillance.so has a hard DT_NEEDED entry on libcuda.so.1, so it will not load
# without it - see scripts/cuda-stub/libcuda_stub.c for the full reasoning. Nothing
# NVIDIA is installed: this compiles one small userspace .so from source.
#
# Re-run after moving or re-extracting the SDK (the file lives inside the SDK tree).
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_ROOT="${SENTIVEILLANCE_SDK_ROOT:-$PROJECT_DIR/my_files/sdk/SentiVeillance_10_1_SDK}"
LIB_DIR="$SDK_ROOT/Lib/Linux_x86_64"
TARGET="$LIB_DIR/libcuda.so.1"

if [ ! -d "$LIB_DIR" ]; then
  echo "SDK native directory not found: $LIB_DIR" >&2
  echo "Set SENTIVEILLANCE_SDK_ROOT to the SentiVeillance_10_1_SDK directory." >&2
  exit 1
fi

# Refuse to clobber a real driver library, in case one is ever installed there.
if [ -e "$TARGET" ] && ! grep -q "libcuda.so.1 stub" "$TARGET" 2>/dev/null; then
  echo "$TARGET already exists and is not our stub - leaving it alone." >&2
  exit 1
fi

gcc -shared -fPIC -O2 -Wall -Wextra \
    -Wl,-soname,libcuda.so.1 \
    -o "$TARGET" \
    "$PROJECT_DIR/scripts/cuda-stub/libcuda_stub.c"

echo "built $TARGET"
readelf -d "$TARGET" | grep SONAME

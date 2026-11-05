#!/usr/bin/env bash
# Drops the two OpenSSL 1.1 libraries the bundled ffmpeg needs into the SDK's own
# Lib/Linux_x86_64 directory. Nothing is installed system-wide: the files land next to
# the SDK libraries and are picked up through LD_LIBRARY_PATH (see scripts/run.sh).
#
# Ubuntu 26.04 ships OpenSSL 3 only, but the SDK's ffmpeg 7.x build was linked against
# OpenSSL 1.1. The two ABIs are not compatible, so symlinking libcrypto.so.3 does NOT
# work - the real 1.1 libraries are required.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_ROOT="${SENTIVEILLANCE_SDK_ROOT:-$PROJECT_DIR/my_files/sdk/SentiVeillance_10_1_SDK}"
LIB_DIR="$SDK_ROOT/Lib/Linux_x86_64"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

DEB_URL="http://security.ubuntu.com/ubuntu/pool/main/o/openssl/libssl1.1_1.1.1f-1ubuntu2.24_amd64.deb"

echo "Downloading libssl1.1 ..."
curl -fL --retry 3 -o "$WORK/libssl1.1.deb" "$DEB_URL"

echo "Extracting ..."
dpkg-deb -x "$WORK/libssl1.1.deb" "$WORK/extracted"

for lib in libssl.so.1.1 libcrypto.so.1.1; do
  src="$(find "$WORK/extracted" -name "$lib" -type f | head -1)"
  if [ -z "$src" ]; then
    echo "Could not find $lib inside the package" >&2
    exit 1
  fi
  cp -v "$src" "$LIB_DIR/$lib"
done

echo
echo "Verifying ffmpeg libraries now resolve:"
for so in libavformat.so.61 libavcodec.so.61 libavutil.so.59; do
  missing=$(LD_LIBRARY_PATH="$LIB_DIR" ldd "$LIB_DIR/$so" 2>/dev/null | awk '/not found/{print $1}' | tr '\n' ' ')
  [ -z "$missing" ] && echo "  OK   $so" || echo "  FAIL $so still needs: $missing"
done

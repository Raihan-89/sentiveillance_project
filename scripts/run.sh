#!/usr/bin/env bash
# Launches the pipeline with the environment the SDK needs.
#
# Why LD_LIBRARY_PATH and not just jna.library.path:
#   JNA finds libNCore.so / libNSurveillance.so through jna.library.path (SdkBootstrap
#   sets that). But libNMedia.so dlopen()s the bundled ffmpeg libraries at runtime, and
#   those have no RUNPATH of their own - only the OS loader resolves them, and it reads
#   LD_LIBRARY_PATH, which has to be set before the JVM starts.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_ROOT="${SENTIVEILLANCE_SDK_ROOT:-$PROJECT_DIR/my_files/sdk/SentiVeillance_10_1_SDK}"
LIB_DIR="$SDK_ROOT/Lib/Linux_x86_64"

if [ ! -d "$LIB_DIR" ]; then
  echo "SDK native libraries not found at $LIB_DIR" >&2
  echo "Set SENTIVEILLANCE_SDK_ROOT to the SentiVeillance_10_1_SDK directory." >&2
  exit 1
fi

export LD_LIBRARY_PATH="$LIB_DIR${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export SENTIVEILLANCE_SDK_ROOT="$SDK_ROOT"

cd "$PROJECT_DIR"
exec ./mvnw -o spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Dsentiveillance.sdk.root=$SDK_ROOT" \
  "$@"

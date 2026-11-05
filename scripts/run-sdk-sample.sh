#!/usr/bin/env bash
# Runs Neurotechnology's OWN prebuilt surveillance sample.
#
# Purpose: isolate "is our code wrong?" from "is there no licence?".
# The sample is the vendor's binary, unmodified. If it cannot obtain a trial licence
# either, the problem is the licence and not anything we wrote.
#
# The sample's LibraryManager derives the native library path from the working
# directory and reads ../Licenses/TrialFlag.txt, so it MUST run from Bin/Java.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_ROOT="${SENTIVEILLANCE_SDK_ROOT:-$PROJECT_DIR/my_files/sdk/SentiVeillance_10_1_SDK}"
JAVA_DIR="$SDK_ROOT/Bin/Java"
LIB_DIR="$SDK_ROOT/Lib/Linux_x86_64"

SAMPLE="${1:-surveillance-sample}"

if [ ! -f "$JAVA_DIR/$SAMPLE.jar" ]; then
  echo "No such sample: $JAVA_DIR/$SAMPLE.jar" >&2
  echo "Available:" >&2
  ls "$JAVA_DIR"/*-sample.jar 2>/dev/null | xargs -n1 basename >&2
  exit 1
fi

export LD_LIBRARY_PATH="$LIB_DIR${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

echo "Running vendor sample: $SAMPLE"
echo "Working directory    : $JAVA_DIR  (required by the sample's LibraryManager)"
echo "TrialFlag.txt        : $(head -1 "$SDK_ROOT/Bin/Licenses/TrialFlag.txt" 2>/dev/null || echo '<missing>')"
echo "---------------------------------------------------------------"

cd "$JAVA_DIR"
exec java -jar "$SAMPLE.jar"

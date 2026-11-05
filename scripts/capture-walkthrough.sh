#!/usr/bin/env bash
# Grabs a burst of frames from the camera so you can walk into view and then check
# how big faces actually are. No SDK licence needed.
#
# Usage:
#   scripts/capture-walkthrough.sh 'rtsp://user:pass@host:554/cam/...' [seconds]
#
# Default 20 seconds, 1 frame per second, written to var/walkthrough/.
set -uo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROPS="$PROJECT_DIR/src/main/resources/application.properties"
OUT_DIR="$PROJECT_DIR/var/walkthrough"

URL="${1:-}"
SECS="${2:-20}"
if [ -z "$URL" ]; then
  URL=$(sed -n 's/^sentiveillance\.source\.url=//p' "$PROPS" | head -1)
fi
if [ -z "$URL" ]; then
  echo "Usage: $0 'rtsp://user:pass@host:554/cam/realmonitor?channel=1&subtype=0' [seconds]" >&2
  exit 1
fi

rm -rf "$OUT_DIR"; mkdir -p "$OUT_DIR"

echo "Capturing ${SECS}s at 1 frame/second -> $OUT_DIR"
echo
echo "   >>> WALK THROUGH THE SCENE NOW, facing the camera as someone would <<<"
echo "       Stop where people will actually pass (a doorway, the gate, a desk)."
echo
sleep 2

timeout $((SECS + 15)) ffmpeg -nostdin -v error -rtsp_transport tcp -i "$URL" \
  -t "$SECS" -vf fps=1 -q:v 2 "$OUT_DIR/frame_%03d.jpg" 2>"$OUT_DIR/capture.err"

COUNT=$(ls -1 "$OUT_DIR"/frame_*.jpg 2>/dev/null | wc -l)
if [ "$COUNT" -eq 0 ]; then
  echo "No frames captured:"
  sed 's/^/   /' "$OUT_DIR/capture.err" | head -5
  exit 1
fi

echo "Captured $COUNT frame(s):"
ls -1 "$OUT_DIR"/frame_*.jpg | sed 's|.*/|   |'
echo
echo "Open them and pick one where your face is clearly visible, then tell Claude"
echo "which file - it can measure the head size and say whether recognition will work."

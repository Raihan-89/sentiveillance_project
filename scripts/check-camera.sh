#!/usr/bin/env bash
# Validates an RTSP camera BEFORE the SDK ever sees it - no licence needed.
#
# If ffprobe cannot open the stream, neither can the SDK: its Media plugin uses the same
# bundled ffmpeg. So this isolates camera/network problems from SDK problems.
#
# Usage:
#   scripts/check-camera.sh                      # reads the URL from application.properties
#   scripts/check-camera.sh 'rtsp://user:pass@192.168.1.108:554/cam/realmonitor?channel=1&subtype=0'
#
# Always quote the URL - it contains '&' and '?'.
set -uo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROPS="$PROJECT_DIR/src/main/resources/application.properties"
OUT_DIR="$PROJECT_DIR/var/camera-check"

URL="${1:-}"
if [ -z "$URL" ]; then
  URL=$(sed -n 's/^sentiveillance\.source\.url=//p' "$PROPS" | head -1)
fi
if [ -z "$URL" ]; then
  echo "No URL given and sentiveillance.source.url is not set in application.properties." >&2
  echo "Usage: $0 'rtsp://user:pass@host:554/cam/realmonitor?channel=1&subtype=0'" >&2
  exit 1
fi

mask() { printf '%s' "$1" | sed -E 's|://[^/@]*@|://***:***@|'; }
HOST=$(printf '%s' "$URL" | sed -nE 's|.*://([^@/]*@)?([^:/?]+).*|\2|p')

echo "URL  : $(mask "$URL")"
echo "Host : $HOST"
echo

echo "1. Network"
if ping -c2 -W2 "$HOST" >/dev/null 2>&1; then
  rtt=$(ping -c3 -W2 "$HOST" 2>/dev/null | tail -1 | awk -F'/' '{print $5}')
  echo "   reachable, avg rtt ${rtt:-?} ms"
else
  echo "   NOT reachable - check IP, cabling and that you are on the same subnet"
  exit 1
fi
if timeout 4 bash -c "echo > /dev/tcp/$HOST/554" 2>/dev/null; then
  echo "   rtsp port 554 open"
else
  echo "   port 554 CLOSED - enable RTSP in the camera web UI (Setup > Network > RTSP)"
fi

echo
echo "2. Stream (rtsp over tcp, 15s timeout)"
mkdir -p "$OUT_DIR"
PROBE="$OUT_DIR/probe.json"
if timeout 20 ffprobe -v error -rtsp_transport tcp \
      -select_streams v:0 -show_entries stream=codec_name,width,height,avg_frame_rate,bit_rate \
      -of json "$URL" > "$PROBE" 2>"$OUT_DIR/probe.err"; then
  W=$(sed -n 's/.*"width": *\([0-9]*\).*/\1/p' "$PROBE" | head -1)
  H=$(sed -n 's/.*"height": *\([0-9]*\).*/\1/p' "$PROBE" | head -1)
  CODEC=$(sed -n 's/.*"codec_name": *"\([^"]*\)".*/\1/p' "$PROBE" | head -1)
  FPS=$(sed -n 's/.*"avg_frame_rate": *"\([^"]*\)".*/\1/p' "$PROBE" | head -1)
  echo "   codec      : ${CODEC:-?}"
  echo "   resolution : ${W:-?} x ${H:-?}"
  echo "   frame rate : ${FPS:-?}"
else
  echo "   FAILED to open the stream:"
  sed 's/^/     /' "$OUT_DIR/probe.err" | head -5
  echo "   Common causes: wrong credentials, wrong path, or RTSP disabled on the camera."
  exit 1
fi

echo
echo "3. Grabbing a frame"
SHOT="$OUT_DIR/frame.jpg"
if timeout 25 ffmpeg -nostdin -v error -rtsp_transport tcp -i "$URL" \
      -frames:v 1 -q:v 2 -y "$SHOT" 2>"$OUT_DIR/grab.err"; then
  echo "   saved: $SHOT"
else
  echo "   could not grab a frame:"
  sed 's/^/     /' "$OUT_DIR/grab.err" | head -5
fi

echo
echo "4. Face-size guidance"
echo "   The engine only RECOGNISES a face when the eyes are at least"
echo "   sentiveillance.faces.minimal-inter-ocular-distance pixels apart (default 24)."
if [ -n "${W:-}" ]; then
  echo "   At ${W}px wide, eyes are ~24px apart when a person's head spans roughly"
  echo "   $(( W / 22 ))-$(( W / 14 ))px, i.e. the head fills about 5-7% of the frame width."
  echo "   Open $SHOT and check a head at the spot people walk past:"
  echo "     - head clearly bigger than that  -> good"
  echo "     - head much smaller              -> zoom in, move the camera closer,"
  echo "                                         or lower minimal-inter-ocular-distance"
fi
echo
echo "   Also check in the frame: faces visible at 15-30 degrees from horizontal (not"
echo "   tops of heads), no burnt-in timestamp over the walking area, no strong backlight."

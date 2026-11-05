#!/usr/bin/env bash
# Static prerequisite check for the SentiVeillance pipeline.
# Loads nothing and starts nothing - it only inspects files, ports and processes.
set -uo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK_ROOT="${SENTIVEILLANCE_SDK_ROOT:-$PROJECT_DIR/my_files/sdk/SentiVeillance_10_1_SDK}"
LIB_DIR="$SDK_ROOT/Lib/Linux_x86_64"

fail=0
ok()   { printf '  \033[32mOK\033[0m    %s\n' "$*"; }
bad()  { printf '  \033[31mFAIL\033[0m  %s\n' "$*"; fail=1; }
warn() { printf '  \033[33mWARN\033[0m  %s\n' "$*"; }

echo "SDK root: $SDK_ROOT"
echo
echo "1. SDK layout"
for p in "$LIB_DIR/libNSurveillance.so" "$SDK_ROOT/Bin/Data/SurveillanceFaces.ndf" \
         "$SDK_ROOT/Bin/Java/neurotec-surveillance.jar" \
         "$SDK_ROOT/Bin/Linux_x86_64/Activation/pgd"; do
  [ -e "$p" ] && ok "${p#"$SDK_ROOT"/}" || bad "missing: $p"
done

echo
echo "2. Java"
if command -v java >/dev/null; then
  v=$(java -version 2>&1 | head -1)
  major=$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p')
  if [ "${major:-0}" -ge 21 ]; then ok "$v"; else bad "need Java 21+, found: $v"; fi
else
  bad "java not on PATH"
fi

echo
echo "3. Native library dependencies"
for so in libNCore.so libNSurveillance.so libNBiometrics.so libNMedia.so NdmMedia.so \
          libavformat.so.61 libavcodec.so.61 libavutil.so.59; do
  [ -e "$LIB_DIR/$so" ] || { warn "$so not present"; continue; }
  missing=$(LD_LIBRARY_PATH="$LIB_DIR" ldd "$LIB_DIR/$so" 2>/dev/null | awk '/not found/{print $1}' | sort -u | tr '\n' ' ')
  if [ -z "$missing" ]; then ok "$so"; else bad "$so needs: $missing"; fi
done

echo
echo "4. Licensing service (pgd)"
if pgrep -x pgd >/dev/null; then
  ok "pgd is running (pid $(pgrep -x pgd | tr '\n' ' '))"
else
  bad "pgd is not running -> $SDK_ROOT/Bin/Linux_x86_64/Activation/run_pgd.sh start"
fi
# pgd must run as root. Started as an unprivileged user it comes up, writes its
# log, and then serves nothing - NLicense.obtain just returns false with no error.
# (/tmp/Neurotech is only pgd's stdout redirect, not a licensing endpoint.)
pgd_pid=$(pgrep -x pgd | head -1)
if [ -n "$pgd_pid" ]; then
  pgd_user=$(ps -o user= -p "$pgd_pid" | tr -d ' ')
  if [ "$pgd_user" = "root" ]; then
    ok "pgd running as root"
  else
    bad "pgd is running as '$pgd_user', not root -> sudo run_pgd.sh restart"
  fi
fi
if grep -qiE '^[[:space:]]*trial[[:space:]]*=[[:space:]]*true' \
     "$SDK_ROOT/Bin/Linux_x86_64/Activation/pgd.conf" 2>/dev/null; then
  # A trial licence is validated online every run.
  if timeout 6 getent hosts neurotechnology.com >/dev/null 2>&1; then
    ok "trial mode + name resolution works (trial needs internet)"
  else
    bad "trial mode is on but neurotechnology.com does not resolve - trial needs internet"
  fi
fi
[ -f "$SDK_ROOT/Bin/Licenses/TrialFlag.txt" ] \
  && ok "TrialFlag.txt = $(head -1 "$SDK_ROOT/Bin/Licenses/TrialFlag.txt")" \
  || warn "Bin/Licenses/TrialFlag.txt not found"

echo
echo "5. PostgreSQL"
if command -v pg_isready >/dev/null && pg_isready -q 2>/dev/null; then
  ok "server accepting connections"
else
  warn "pg_isready says the server is not ready"
fi
# Credentials come from application.properties. -w means never prompt, so a wrong
# password fails fast instead of hanging the script on a password prompt.
props="$PROJECT_DIR/src/main/resources/application.properties"
db_user=$(sed -n 's/^spring\.datasource\.username=//p' "$props" | head -1)
db_pass=$(sed -n 's/^spring\.datasource\.password=//p' "$props" | head -1)
db_url=$(sed -n 's/^spring\.datasource\.url=jdbc:postgresql:\/\///p' "$props" | head -1)
db_host=${db_url%%:*}
db_rest=${db_url#*:}
db_port=${db_rest%%/*}
db_name=${db_url##*/}
if [ -z "$db_user" ] || [ "$db_user" = "CHANGE_ME" ]; then
  bad "spring.datasource.username is not set in application.properties"
elif PGPASSWORD="$db_pass" psql -w -h "${db_host:-localhost}" -p "${db_port:-5432}" \
       -U "$db_user" -d "${db_name:-sentiveillance}" -Atqc 'select 1' >/dev/null 2>&1; then
  ok "connected to '${db_name}' as '${db_user}'"
else
  bad "cannot connect to '${db_name}' as '${db_user}' - check username/password"
fi

echo
echo "6. Configured source"
props="$PROJECT_DIR/src/main/resources/application.properties"
type=$(grep -E '^sentiveillance\.source\.type=' "$props" 2>/dev/null | cut -d= -f2-)
echo "  type = ${type:-<unset>}"
if [ "$type" = "VIDEO_FILE" ]; then
  f=$(grep -E '^sentiveillance\.source\.file=' "$props" | cut -d= -f2-)
  [ -r "$PROJECT_DIR/$f" ] && ok "video file readable: $f" || bad "video file not readable: $f"
elif [ "$type" = "IP_CAMERA" ]; then
  u=$(grep -E '^sentiveillance\.source\.url=' "$props" | cut -d= -f2-)
  host=$(printf '%s' "$u" | sed -n 's|.*://\([^@/]*@\)\?\([^:/?]*\).*|\2|p')
  if [ -n "$host" ]; then
    ping -c1 -W2 "$host" >/dev/null 2>&1 && ok "camera host $host reachable" || bad "camera host $host unreachable"
  else
    bad "sentiveillance.source.url is not set"
  fi
fi

echo
[ "$fail" -eq 0 ] && echo "All hard checks passed." || echo "Some checks FAILED - see above."
exit "$fail"

#!/usr/bin/env bash
# restart-local.sh — rebuild plm-field-tracker.jar, copy to ~/Documents/plm-toolkit 2/,
# kill whatever's on port 8090, start fresh, wait until it answers HTTP, print the PID.
#
# Usage:
#   ./restart-local.sh              # full: mvn package + copy + restart
#   ./restart-local.sh --no-build   # skip mvn, restart from existing JAR in target/
#   ./restart-local.sh --restart    # skip mvn AND skip copy, just kick the running JAR
#   ./restart-local.sh --tail       # after start-up, tail the log
#
# Exits non-zero if the build fails or the server doesn't come up within 90 s.

set -euo pipefail

PROJECT_DIR="/Users/vikasjindal/git/plm-field-tracker"
LOCAL_DIR="/Users/vikasjindal/Documents/plm-toolkit 2"
REMOTE_SHARE="/Volumes/uls-ep-aglipccb/plm-toolkit"
# pom.xml targets Java 11 — Corretto 11 is the JDK for THIS project.
# (Other ~/git/* projects in CLAUDE.md target Java 1.8 — don't confuse them with this one.)
JDK_HOME="/Library/Java/JavaVirtualMachines/amazon-corretto-11.jdk/Contents/Home"
JAR_NAME="plm-field-tracker-1.0.1.jar"
PORT=8090
LOG_FILE="/tmp/plm-toolkit-local.log"
URL="http://localhost:${PORT}/"

DO_BUILD=1
DO_COPY=1
DO_TAIL=0
for arg in "$@"; do
  case "$arg" in
    --no-build) DO_BUILD=0 ;;
    --restart)  DO_BUILD=0; DO_COPY=0 ;;
    --tail)     DO_TAIL=1 ;;
    -h|--help)  sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "unknown flag: $arg (try --help)"; exit 2 ;;
  esac
done

cd "$PROJECT_DIR"

# 1. Build (default)
if [[ $DO_BUILD -eq 1 ]]; then
  echo "[build] mvn -q -DskipTests package (JDK 11)"
  JAVA_HOME="$JDK_HOME" mvn -q -DskipTests package
fi

JAR_PATH="$PROJECT_DIR/target/$JAR_NAME"
if [[ ! -f "$JAR_PATH" ]]; then
  echo "[error] JAR not found at $JAR_PATH — run without --no-build first." >&2
  exit 1
fi

# 2. Copy to local + (optional) remote share
if [[ $DO_COPY -eq 1 ]]; then
  echo "[copy ] $JAR_PATH → $LOCAL_DIR/"
  cp "$JAR_PATH" "$LOCAL_DIR/$JAR_NAME"

  if [[ -d "$REMOTE_SHARE" ]]; then
    echo "[copy ] $JAR_PATH → $REMOTE_SHARE/"
    cp "$JAR_PATH" "$REMOTE_SHARE/$JAR_NAME"
  else
    echo "[skip ] remote share $REMOTE_SHARE not mounted (local-only deploy)"
  fi
fi

# 3. Hard-kill anything on the port AND any stray plm-field-tracker JVM.
#    Skips graceful shutdown — repeated TERM cycles in one day were leaving
#    Tomcat's static-resource handler in a wedged state on subsequent restarts
#    (symptoms: /login.html hangs while /api/* still works). Hard kill on every
#    restart sidesteps the whole problem.
PIDS=$(lsof -i :"$PORT" -t 2>/dev/null || true)
STRAYS=$(pgrep -f "plm-field-tracker-1\.0\.1\.jar" 2>/dev/null || true)
ALL_PIDS=$(printf "%s\n%s\n" "$PIDS" "$STRAYS" | sort -u | grep -v '^$' || true)
if [[ -n "$ALL_PIDS" ]]; then
  echo "[kill ] SIGKILL: $(echo $ALL_PIDS)"
  # shellcheck disable=SC2086
  kill -9 $ALL_PIDS 2>/dev/null || true
  sleep 2
  STILL=$(lsof -i :"$PORT" -t 2>/dev/null || true)
  if [[ -n "$STILL" ]]; then
    echo "[kill ] still listening — second SIGKILL pass: $STILL"
    # shellcheck disable=SC2086
    kill -9 $STILL 2>/dev/null || true
    sleep 1
  fi
fi

# 4. Start fresh
cd "$LOCAL_DIR"
echo "[start] java -Xmx4g -jar $JAR_NAME (logs → $LOG_FILE)"
nohup java -Xmx4g -jar "$JAR_NAME" \
  --spring.config.additional-location=file:./config/application.properties \
  > "$LOG_FILE" 2>&1 &
NEW_PID=$!
echo "[start] PID $NEW_PID"

# 5. Wait until it answers HTTP (200/302/401 all count as "alive"); 90 s cap
echo "[wait ] for $URL ..."
DEADLINE=$(( $(date +%s) + 90 ))
while true; do
  CODE=$(curl -s -o /dev/null -w "%{http_code}" "$URL" || echo "000")
  if [[ "$CODE" =~ ^(200|302|401)$ ]]; then
    echo "[ok   ] up in $(( $(date +%s) - DEADLINE + 90 ))s — HTTP $CODE → http://localhost:${PORT}"
    break
  fi
  if [[ $(date +%s) -gt $DEADLINE ]]; then
    echo "[error] server didn't come up within 90 s (last HTTP: $CODE). Check $LOG_FILE" >&2
    exit 1
  fi
  sleep 2
done

if [[ $DO_TAIL -eq 1 ]]; then
  echo "[tail ] following $LOG_FILE (Ctrl-C to stop)"
  exec tail -f "$LOG_FILE"
fi

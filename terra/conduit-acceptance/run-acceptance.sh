#!/usr/bin/env bash
# Conduit (RealWorld) acceptance run (#2898): starts a disposable PostgreSQL 17 and the assembled Conduit-on-Terra distribution, runs the
# upstream RealWorld API suite (spec/hurl, 13 files) against it with a pinned Hurl, prints a pass/fail summary and exits non-zero on ANY
# failure (a failed request or assertion, an app or database that does not come up, a suite that examined nothing).
#
#   mvn -pl terra/conduit-acceptance -am install     # builds target/conduit-terra-terra (the Terra distribution)
#   terra/conduit-acceptance/run-acceptance.sh
#
# Environment (all optional): DIST (distribution dir), APP_PORT (default: a free port), WORK (scratch dir, default mktemp; kept when
# KEEP_WORK=1), HURL_IMAGE (default: the pinned image below), PG_IMAGE, STARTUP_TIMEOUT (seconds, default 120), JAVA_OPTS.
# Requires: docker, java 25 on PATH (or JAVA_HOME), curl, awk, python3. Never touches a database other than its own container.
#
# What is NOT the upstream suite verbatim: assertions on the BODY of an error response (`jsonpath "$.errors..."` under an `HTTP 4xx`/`5xx`
# response line) are removed from a scratch copy before the run, because Terra answers RFC 9457 problem details (owner ruling 2026-11-08),
# not the spec's {"errors":{...}} shape. Every status line, every success-body assertion and every capture is kept; the number removed
# is printed. See README.md.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
DIST="${DIST:-$HERE/target/conduit-terra-terra}"
HURL_IMAGE="${HURL_IMAGE:-ghcr.io/orange-opensource/hurl:8.0.1}"
PG_IMAGE="${PG_IMAGE:-postgres:17}"
STARTUP_TIMEOUT="${STARTUP_TIMEOUT:-120}"
# The suite's pre-signed tokens are not used by spec/hurl, but the secret must be at least 32 bytes (B-04). A test value, not a secret.
export CONDUIT_JWT_SECRET="${CONDUIT_JWT_SECRET:-conduit-bench-jwt-secret-do-not-use-in-production}"

[ -d "$DIST/lib" ] || { echo "ACCEPTANCE ERROR: no distribution at $DIST (build the module first)" >&2; exit 2; }
command -v docker >/dev/null || { echo "ACCEPTANCE ERROR: docker is required" >&2; exit 2; }

WORK="${WORK:-$(mktemp -d "${TMPDIR:-/tmp}/conduit-acceptance.XXXXXX")}"
mkdir -p "$WORK"
SUFFIX="$$-$(date +%s)"
PG_NAME="conduit-acc-pg-$SUFFIX"
APP_PID=""

cleanup() {
  # Only what this run started: its own app process and its own container.
  if [ -n "$APP_PID" ] && kill -0 "$APP_PID" 2>/dev/null; then
    kill "$APP_PID" 2>/dev/null || true
    for _ in 1 2 3 4 5 6 7 8 9 10; do kill -0 "$APP_PID" 2>/dev/null || break; sleep 1; done
    kill -9 "$APP_PID" 2>/dev/null || true
  fi
  docker rm -f "$PG_NAME" >/dev/null 2>&1 || true
  if [ "${KEEP_WORK:-0}" != 1 ]; then rm -rf "$WORK" || true; else echo "work dir kept: $WORK"; fi
}
trap cleanup EXIT

free_port() { python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()'; }

# --- database ---------------------------------------------------------------------------------------------------------------------------
docker run -d --name "$PG_NAME" -e POSTGRES_USER=conduit -e POSTGRES_PASSWORD=conduit -e POSTGRES_DB=conduit \
  -p 127.0.0.1::5432 "$PG_IMAGE" >/dev/null
PG_PORT="$(docker port "$PG_NAME" 5432/tcp | head -1 | sed 's/.*://')"
ready=0
for _ in $(seq 1 60); do
  if docker exec "$PG_NAME" pg_isready -U conduit -d conduit -h 127.0.0.1 >/dev/null 2>&1; then ready=1; break; fi
  sleep 1
done
[ "$ready" = 1 ] || { echo "ACCEPTANCE ERROR: PostgreSQL did not become ready" >&2; exit 2; }
# pg_isready answers during the image's init restart: wait until a real query succeeds on the final server
for _ in $(seq 1 30); do
  docker exec "$PG_NAME" psql -U conduit -d conduit -h 127.0.0.1 -tAc 'select 1' >/dev/null 2>&1 && break
  sleep 1
done

# The app does not migrate: the schema the @PgSql queries were validated against is applied to the fresh database, as the upstream run did.
docker exec -i "$PG_NAME" psql -v ON_ERROR_STOP=1 -U conduit -d conduit -h 127.0.0.1 -q < "$HERE/schema/V1__conduit.sql" >/dev/null \
  || { echo "ACCEPTANCE ERROR: applying schema/V1__conduit.sql failed" >&2; exit 2; }

# --- application -------------------------------------------------------------------------------------------------------------------------
APP_PORT="${APP_PORT:-$(free_port)}"
export TERRA_JWT__SECRET="$CONDUIT_JWT_SECRET"
export TERRA_HTTP__PORT="$APP_PORT"
export TERRA_DATABASE__HOST=127.0.0.1
export TERRA_DATABASE__PORT="$PG_PORT"
export TERRA_DATABASE__DATABASE=conduit
export TERRA_DATABASE__USERNAME=conduit
export TERRA_DATABASE__PASSWORD=conduit
export TERRA_DATABASE__ASYNC_URL="postgresql://127.0.0.1:$PG_PORT/conduit"

JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
# shellcheck disable=SC2086
"$JAVA_BIN" --enable-preview ${JAVA_OPTS:--Xms512m -Xmx512m -XX:+UseG1GC} \
  -cp "$DIST/lib/*" org.pragmatica.terra.launcher.TerraMain "$DIST/application" >"$WORK/app.log" 2>&1 &
APP_PID=$!

deadline=$(( $(date +%s) + STARTUP_TIMEOUT ))
up=0
while [ "$(date +%s)" -lt "$deadline" ]; do
  kill -0 "$APP_PID" 2>/dev/null || { echo "ACCEPTANCE ERROR: the application exited during startup; tail of its log:" >&2; tail -40 "$WORK/app.log" >&2; exit 2; }
  if [ "$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$APP_PORT/api/tags" || true)" = 200 ]; then up=1; break; fi
  sleep 1
done
[ "$up" = 1 ] || { echo "ACCEPTANCE ERROR: GET /api/tags did not answer 200 within ${STARTUP_TIMEOUT}s; tail of the app log:" >&2; tail -40 "$WORK/app.log" >&2; exit 2; }
echo "application up on port $APP_PORT, PostgreSQL on port $PG_PORT, suite image $HURL_IMAGE"

# --- suite copy without error-body assertions ----------------------------------------------------------------------------------------------
SUITE="$WORK/suite"
mkdir -p "$SUITE"
exempt=0
for f in "$HERE"/spec/hurl/*.hurl; do
  awk -v out="$WORK/exempt.count" '
    /^(GET|POST|PUT|DELETE|PATCH) / { err = 0 }
    /^HTTP [45][0-9][0-9]/ { err = 1 }
    /^HTTP [123][0-9][0-9]/ { err = 0 }
    err && /^jsonpath "\$\.errors/ { n++; next }
    { print }
    END { print n + 0 >> out }' "$f" > "$SUITE/$(basename "$f")"
done
exempt=$(awk '{s+=$1} END{print s+0}' "$WORK/exempt.count")
files=$(ls "$SUITE"/*.hurl | wc -l | tr -d ' ')
[ "$files" -gt 0 ] || { echo "ACCEPTANCE ERROR: no suite files found under $HERE/spec/hurl" >&2; exit 2; }

# --- run: one hurl invocation per file, all against the same application ----------------------------------------------------------------------
UID_VAL="$(date +%s)$$"
mkdir -p "$WORK/report"
passed=0; failed=0; requests=0; assertions=0
failed_files=""
for f in "$SUITE"/*.hurl; do
  name="$(basename "$f" .hurl)"
  set +e
  docker run --rm --network host --user "$(id -u):$(id -g)" -v "$SUITE:/suite:ro" -v "$WORK/report:/report" "$HURL_IMAGE" \
    --test --jobs 1 --report-json "/report/$name" \
    --variable "host=http://127.0.0.1:$APP_PORT" --variable "uid=$UID_VAL" "/suite/$name.hurl" >"$WORK/report/$name.out" 2>&1
  rc=$?
  set -e
  read -r n_req n_asserts < <(python3 -I - "$WORK/report/$name" <<'PY'
import json, sys
d = json.load(open(sys.argv[1] + "/report.json"))
entries = [e for f in d for e in f.get("entries", [])] if isinstance(d, list) else []
print(len(entries), sum(len(e.get("asserts", [])) for e in entries))
PY
  ) || { n_req=0; n_asserts=0; }
  requests=$(( requests + n_req ))
  assertions=$(( assertions + n_asserts ))
  if [ "$rc" = 0 ] && [ "$n_req" -gt 0 ]; then
    passed=$(( passed + 1 )); echo "PASS  $name ($n_req requests, $n_asserts asserts)"
  else
    failed=$(( failed + 1 )); failed_files="$failed_files $name"
    echo "FAIL  $name (exit $rc, $n_req requests)"
    sed -n '1,200p' "$WORK/report/$name.out"
  fi
done

echo "-------------------------------------------------------------------------------"
echo "Conduit acceptance: files $((passed + failed)), passed $passed, failed $failed, requests executed $requests, asserts evaluated $assertions, error-body assertions exempt $exempt"
if [ "$failed" -gt 0 ] || [ "$passed" -eq 0 ]; then
  echo "RESULT: FAIL ($failed_files )"
  exit 1
fi
echo "RESULT: PASS"

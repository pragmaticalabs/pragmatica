#!/bin/bash
# Start PostgreSQL for local development.
#
# Usage:
#   ./start-postgres.sh          # start and init schema
#   ./start-postgres.sh --reset  # drop existing, recreate from scratch
#
# #952: this script used to print "PostgreSQL ready" and exit 0 for a container that had already
# died. Two defects combined to produce that, and both are fixed here.
#
#   * The data directory was hardcoded to the pre-18 path while the image pin had moved to 18. It is
#     now DERIVED from the image itself, so it cannot drift from the pin again. Measured on
#     postgres:18-alpine: PGDATA is /var/lib/postgresql/18/docker and the declared VOLUME is
#     /var/lib/postgresql, so a volume mounted at /var/lib/postgresql/data held NOTHING - the
#     cluster lived in the container's writable layer and every row was discarded by the next
#     `rm -f` this script itself performs.
#   * The readiness loop had no failure branch. It counted to 30 and fell through, so its success
#     carried no information: "ready" was printed whether or not anything was listening, and the
#     real failure surfaced ten seconds later misattributed to `mvn install`. The loop can now fail
#     - it checks the container is RUNNING rather than that a counter elapsed, bounds itself in
#     time, names the database, and exits non-zero.
set -e

# Auto-detect container runtime
if command -v docker >/dev/null 2>&1; then
    RUNTIME="docker"
elif command -v podman >/dev/null 2>&1; then
    RUNTIME="podman"
else
    echo "ERROR: Neither docker nor podman found."
    exit 1
fi

CONTAINER_NAME="forge-postgres"
PG_USER="forge"
PG_PASSWORD="forge"
PG_DB="forge"
PG_PORT=5432
PG_IMAGE="postgres:18-alpine"
PG_VOLUME="forge-pgdata"
PG_READY_TIMEOUT="${PG_READY_TIMEOUT:-60}"
SCHEMA_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/schema"

# The data path is a property of the IMAGE, not of this script. Postgres 18 moved PGDATA to
# /var/lib/postgresql/<major>/docker and declares /var/lib/postgresql as its volume; earlier images
# use /var/lib/postgresql/data for both. Preferring the declared VOLUME that CONTAINS PGDATA is what
# the image intends and what makes the data outlive the container.
resolve_data_mount() {
    local pgdata volumes candidate

    pgdata=$($RUNTIME image inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$PG_IMAGE" 2>/dev/null \
             | sed -n 's/^PGDATA=//p' | head -1)
    volumes=$($RUNTIME image inspect --format '{{range $path, $_ := .Config.Volumes}}{{println $path}}{{end}}' "$PG_IMAGE" 2>/dev/null)

    if [ -n "$pgdata" ]; then
        while IFS= read -r candidate; do
            [ -n "$candidate" ] || continue
            case "$pgdata" in
                "$candidate"|"$candidate"/*) printf '%s\n' "$candidate"; return 0 ;;
            esac
        done <<< "$volumes"

        printf '%s\n' "$pgdata"
        return 0
    fi

    printf '%s\n' "$volumes" | head -1
}

# The readiness wait, with the failure branch #952 was missing. Three ways it can end and only one
# of them is success; the elapsed time and probe COUNT are printed either way, because "ready" with
# no number attached is exactly what the old loop said about a container that had been dead for
# ten seconds.
wait_until_ready() {
    local started=$SECONDS attempts=0 elapsed state exit_code

    while true; do
        state=$($RUNTIME inspect --format '{{.State.Running}}' "$CONTAINER_NAME" 2>/dev/null || echo "absent")

        if [ "$state" != "true" ]; then
            exit_code=$($RUNTIME inspect --format '{{.State.ExitCode}}' "$CONTAINER_NAME" 2>/dev/null || echo "unknown")
            elapsed=$((SECONDS - started))
            echo ""
            echo "ERROR: PostgreSQL database '$PG_DB' is NOT available."
            echo "       Container '$CONTAINER_NAME' ($PG_IMAGE) is not running: state=$state, exit code $exit_code,"
            echo "       after ${elapsed}s and $attempts readiness probe(s)."
            echo "       Volume '$PG_VOLUME' was mounted at ${PG_DATA_MOUNT:-<unresolved>}."
            echo "       Nothing downstream will be able to reach ${PG_DB} on port ${PG_PORT}."
            echo "       Last lines from the container:"
            $RUNTIME logs --tail 20 "$CONTAINER_NAME" 2>&1 | sed 's/^/         /' || true
            exit 1
        fi

        attempts=$((attempts + 1))

        if $RUNTIME exec "$CONTAINER_NAME" pg_isready -U "$PG_USER" -d "$PG_DB" >/dev/null 2>&1; then
            elapsed=$((SECONDS - started))
            echo " ready after ${elapsed}s and $attempts probe(s)."
            return 0
        fi

        elapsed=$((SECONDS - started))

        if [ "$elapsed" -ge "$PG_READY_TIMEOUT" ]; then
            echo ""
            echo "ERROR: PostgreSQL database '$PG_DB' did not become ready within ${PG_READY_TIMEOUT}s."
            echo "       Container '$CONTAINER_NAME' ($PG_IMAGE) is running, but pg_isready failed on"
            echo "       all $attempts probe(s)."
            echo "       Volume '$PG_VOLUME' was mounted at ${PG_DATA_MOUNT:-<unresolved>}."
            echo "       Nothing downstream will be able to reach ${PG_DB} on port ${PG_PORT}."
            echo "       Last lines from the container:"
            $RUNTIME logs --tail 20 "$CONTAINER_NAME" 2>&1 | sed 's/^/         /' || true
            exit 1
        fi

        echo -n "."
        sleep 1
    done
}

if [ "$1" = "--reset" ]; then
    echo "Resetting PostgreSQL container..."
    $RUNTIME rm -f "$CONTAINER_NAME" 2>/dev/null || true
    $RUNTIME volume rm "$PG_VOLUME" 2>/dev/null || true
fi

# `image inspect` cannot read an image that is not present, and guessing the data path is the defect
# this derivation exists to remove, so pull first and let a failed pull fail here.
if ! $RUNTIME image inspect "$PG_IMAGE" >/dev/null 2>&1; then
    echo "Pulling $PG_IMAGE..."
    $RUNTIME pull "$PG_IMAGE"
fi

PG_DATA_MOUNT="$(resolve_data_mount)"

if [ -z "$PG_DATA_MOUNT" ]; then
    echo "ERROR: could not read the data directory from $PG_IMAGE."
    echo "       The image declares neither a VOLUME nor a PGDATA environment entry."
    echo "       Refusing to guess: a wrong path starts a container that looks healthy and silently"
    echo "       discards every row (#952)."
    exit 1
fi

# Start container if not running
if $RUNTIME ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    echo "PostgreSQL container $CONTAINER_NAME already running on port $PG_PORT"
else
    # Remove stopped container if exists
    $RUNTIME rm -f "$CONTAINER_NAME" 2>/dev/null || true

    echo "Starting PostgreSQL $PG_IMAGE on port $PG_PORT (volume $PG_VOLUME at $PG_DATA_MOUNT)..."
    $RUNTIME run -d \
        --name "$CONTAINER_NAME" \
        -e POSTGRES_USER="$PG_USER" \
        -e POSTGRES_PASSWORD="$PG_PASSWORD" \
        -e POSTGRES_DB="$PG_DB" \
        -p "${PG_PORT}:5432" \
        -v "${PG_VOLUME}:${PG_DATA_MOUNT}" \
        "$PG_IMAGE" \
        -c max_connections=500
fi

# Wait unconditionally, whether this run started the container or found it. The old script skipped
# the check entirely on the already-running branch, which was a second blind path to the same false
# "ready": a container that is up is not a database that answers.
echo -n "Waiting for PostgreSQL (timeout ${PG_READY_TIMEOUT}s)..."
wait_until_ready

# Schema is managed by Aether's migration engine.
# Migration scripts in schema/ are applied automatically on blueprint deploy.

echo ""
echo "PostgreSQL ready — verified by pg_isready, not by a loop counter:"
echo "  JDBC URL: jdbc:postgresql://localhost:${PG_PORT}/${PG_DB}"
echo "  User: $PG_USER / $PG_PASSWORD"
echo "  Container: $CONTAINER_NAME (image $PG_IMAGE)"
echo "  Data volume: $PG_VOLUME mounted at $PG_DATA_MOUNT"

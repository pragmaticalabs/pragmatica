#!/bin/bash
# Start PostgreSQL for Forge benchmarking via Podman.
#
# Usage:
#   ./start-postgres.sh          # start and init schema
#   ./start-postgres.sh --reset  # drop existing, recreate from scratch
set -e

CONTAINER_NAME="forge-postgres"
PG_USER="forge"
PG_PASSWORD="forge"
PG_DB="forge"
PG_PORT=5432
PG_IMAGE="postgres:18-alpine"
SCHEMA_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/schema"

if [ "$1" = "--reset" ]; then
    echo "Resetting PostgreSQL container..."
    podman rm -f "$CONTAINER_NAME" 2>/dev/null || true
    podman volume rm forge-pgdata 2>/dev/null || true
fi

# Start container if not running
if podman ps --format '{{.Names}}' | grep -q "^${CONTAINER_NAME}$"; then
    echo "PostgreSQL already running on port $PG_PORT"
else
    # Remove stopped container if exists
    podman rm -f "$CONTAINER_NAME" 2>/dev/null || true

    echo "Starting PostgreSQL $PG_IMAGE on port $PG_PORT..."
    podman run -d \
        --name "$CONTAINER_NAME" \
        -e POSTGRES_USER="$PG_USER" \
        -e POSTGRES_PASSWORD="$PG_PASSWORD" \
        -e POSTGRES_DB="$PG_DB" \
        -p "${PG_PORT}:5432" \
        -v forge-pgdata:/var/lib/postgresql/data \
        "$PG_IMAGE" \
        -c max_connections=500

    # Wait for PostgreSQL to be ready
    echo -n "Waiting for PostgreSQL..."
    for i in $(seq 1 30); do
        if podman exec "$CONTAINER_NAME" pg_isready -U "$PG_USER" -d "$PG_DB" > /dev/null 2>&1; then
            echo " ready."
            break
        fi
        echo -n "."
        sleep 1
    done
fi

# Run schema init
if [ -f "$SCHEMA_DIR/url-shortener-pg.sql" ]; then
    echo "Initializing schema..."
    podman exec -i "$CONTAINER_NAME" psql -U "$PG_USER" -d "$PG_DB" < "$SCHEMA_DIR/url-shortener-pg.sql"
    echo "Schema initialized."
fi

echo ""
echo "PostgreSQL ready:"
echo "  JDBC URL: jdbc:postgresql://localhost:${PG_PORT}/${PG_DB}"
echo "  User: $PG_USER / $PG_PASSWORD"

#!/bin/bash
# Stop PostgreSQL container. Data persists in forge-pgdata volume.
#
# Usage:
#   ./stop-postgres.sh          # stop (data persists)
#   ./stop-postgres.sh --purge  # stop and delete data volume
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

$RUNTIME stop "$CONTAINER_NAME" 2>/dev/null && echo "PostgreSQL stopped." || echo "PostgreSQL not running."

if [ "$1" = "--purge" ]; then
    $RUNTIME rm -f "$CONTAINER_NAME" 2>/dev/null || true
    $RUNTIME volume rm forge-pgdata 2>/dev/null || true
    echo "Container and data volume removed."
fi

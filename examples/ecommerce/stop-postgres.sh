#!/bin/bash
# Stop PostgreSQL container. Data persists in forge-pgdata volume.
#
# Usage:
#   ./stop-postgres.sh          # stop (data persists)
#   ./stop-postgres.sh --purge  # stop and delete data volume
set -e

CONTAINER_NAME="forge-postgres"

podman stop "$CONTAINER_NAME" 2>/dev/null && echo "PostgreSQL stopped." || echo "PostgreSQL not running."

if [ "$1" = "--purge" ]; then
    podman rm -f "$CONTAINER_NAME" 2>/dev/null || true
    podman volume rm forge-pgdata 2>/dev/null || true
    echo "Container and data volume removed."
fi

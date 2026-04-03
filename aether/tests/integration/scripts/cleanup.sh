#!/bin/bash
# cleanup.sh — Tear down integration test cluster
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="${SCRIPT_DIR}/.."
source "${ROOT_DIR}/lib/common.sh"

HOST="${TARGET_HOST:-localhost}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/aether_test}"
SSH_USER="${AETHER_SSH_USER:-aether}"

remote_exec() {
    if [ "$HOST" = "localhost" ]; then
        bash -c "$1"
    else
        ssh -i "$SSH_KEY" "${SSH_USER}@${HOST}" "$1"
    fi
}

log_step "Stopping integration test cluster on ${HOST}"
remote_exec "cd ~/aether-build && docker compose -f docker-compose.yml down -v 2>/dev/null" || true

log_step "Removing load test temp files"
rm -f /tmp/load_result_*.txt /tmp/sustained_load*.log

log_pass "Cleanup complete"

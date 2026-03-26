#!/bin/bash
# cleanup.sh — Tear down cluster and clean up on target machine
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../lib/common.sh"

log_info "Cleaning up Aether cluster on ${TARGET_HOST}"

log_step "Stopping all Aether containers"
remote_exec "docker ps -a --filter 'name=aether-' --format '{{.Names}}' | xargs -r docker stop" 2>/dev/null || true

log_step "Removing all Aether containers"
remote_exec "docker ps -a --filter 'name=aether-' --format '{{.Names}}' | xargs -r docker rm -f" 2>/dev/null || true

log_step "Removing Aether data"
remote_exec "rm -rf /opt/aether/data/*" 2>/dev/null || true

log_step "Removing load test temp files"
rm -f /tmp/load_result_*.txt
rm -f /tmp/sustained_load*.log

log_pass "Cleanup complete"

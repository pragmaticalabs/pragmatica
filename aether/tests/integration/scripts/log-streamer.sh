#!/usr/bin/env bash
# Runs ON the remote test host (deployed by start_log_streamers, lib/cluster.sh).
#
# Attaches an APPENDING `docker logs -f` stream to every aether-* container, re-scanning
# every 5s so containers created later (compose resets, CTM auto-heal replacements) are
# picked up automatically. The point: streamed files survive `docker rm` — auto-heal
# destroys a dying node's container WITH its json-file logs, which is exactly how the
# run2/run4 pre-kill node deaths (node-5, node-3) became undiagnosable. A stream's file
# persists after its container is removed; appending means a same-name restart extends
# the file instead of clobbering the evidence.
#
# State lives under /tmp/aether-node-logs: <container>.log (the evidence),
# <container>.pid (the stream's pid, for liveness re-check), daemon.pid (this loop).
set -u

LOG_DIR="/tmp/aether-node-logs"
mkdir -p "$LOG_DIR"

while true; do
    for c in $(docker ps --format '{{.Names}}' --filter name=aether- 2>/dev/null); do
        pidfile="${LOG_DIR}/${c}.pid"
        if [ ! -e "$pidfile" ] || ! kill -0 "$(cat "$pidfile" 2>/dev/null)" 2>/dev/null; then
            nohup docker logs -f "$c" >> "${LOG_DIR}/${c}.log" 2>&1 &
            echo $! > "$pidfile"
        fi
    done
    sleep 5
done

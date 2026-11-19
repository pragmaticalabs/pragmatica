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
#
# `log-streamer.sh stop` is the teardown (#3427): it ends the daemon and every stream by the pid it RECORDED, never by a
# process-name pattern (a pattern on a shared host can hit another run's streams). A recorded pid is only signalled when its
# /proc cmdline still names what we started, so a stale file whose pid was reused kills nothing.
set -u

LOG_DIR="${AETHER_STREAM_DIR:-/tmp/aether-node-logs}"

if [ "${1:-}" = stop ]; then
    # Daemon first, so it cannot respawn a stream we are about to end.
    for f in "$LOG_DIR/daemon.pid" "$LOG_DIR"/aether-*.pid; do
        [ -f "$f" ] || continue
        p=$(cat "$f" 2>/dev/null)
        case "$p" in '' | *[!0-9]*) continue ;; esac
        cmd=$(tr '\0' ' ' < "/proc/$p/cmdline" 2>/dev/null)
        case "$cmd" in
            *aether-log-streamer.sh* | 'docker logs -f aether-'*) kill "$p" 2>/dev/null ;;
        esac
    done
    exit 0
fi

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

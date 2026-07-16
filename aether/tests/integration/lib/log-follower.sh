#!/bin/bash
# =============================================================================
# log-follower.sh — robust application-log capture for cloud cluster nodes
# =============================================================================
# WHY THIS EXISTS
#   The cloud leader-app-log capture failed TWICE because the ad-hoc probe
#   scripts (/tmp/conclusive/log-follower.sh, /tmp/final-probe/capture-leader-logs.sh)
#   tailed the node with a bare `docker logs -f aether-node` over SSH. On cloud
#   `--runtime container` VMs that inner invocation fails two ways while SSH
#   itself succeeds:
#     1. `permission denied while trying to connect to the docker API` — the SSH
#        login user is not root / not in the docker group, so it cannot reach the
#        daemon socket.
#     2. `bash: docker: command not found` — on a CTM auto-heal replacement VM the
#        node's own `docker run` start has not finished installing docker yet, or
#        the docker binary is on a PATH a non-login SSH shell does not inherit
#        (get.docker.com / snap install locations).
#   Result: zero application log captured during chaos windows — blocking
#   root-cause of reconciler stalls. This library fixes the capture itself.
#
# WHAT THE REAL CLOUD LOG SOURCE IS (evidence)
#   Cloud `--runtime container` nodes are started by NodeUserDataRenderer's
#   container branch as a PLAIN detached docker container:
#     aether/aether-config/.../NodeUserDataRenderer.java:255-256
#         docker run -d --name aether-node --restart no --network host ...
#   There is NO systemd unit (SystemdUnitTemplate.java is referenced ONLY by its
#   own test — dead code, never wired into the render path) and NO in-container
#   file log: the image entrypoint runs `java ... -jar` writing to stdout/stderr
#   (aether/docker/aether-node/Dockerfile:65-76), captured by Docker's default
#   json-file log driver. Therefore the application log lives in exactly one place
#   on the VM: the Docker daemon, retrievable via `docker logs aether-node`.
#   The cloud SSH operator defaults to root (cloud_ssh -> CLOUD_SSH_USER:-root,
#   lib/common.sh:1063; Bug 16-A, BootstrapPhaseDeployCloudSshRestartTest.java:704),
#   so `sudo docker logs` is the reliable form and is harmless when already root.
#
# CAPTURE STRATEGY (priority order, with fallback — first that works wins)
#   1. sudo docker logs -f  — the real source; sudo + an explicit docker-binary
#      probe fix BOTH the permission-denied and command-not-found failures.
#   2. sudo journalctl -u   — defensive: if a future renderer change moves the
#      node under systemd, this picks it up without another silent-capture run.
#   3. tail -F <file>       — defensive: if the node is ever made to write a file
#      log (/var/log/aether/*.log, /opt/aether/logs/*.log).
#   4. FAIL LOUDLY          — emit a diagnostic banner naming every probe that was
#      tried and why each failed, so a future run never silently captures nothing.
#
# SCOPE
#   This is the CLOUD path. Local docker clusters (where the harness runs as a
#   user with socket access and `docker logs <name>` works directly) are
#   unaffected: follow_node_log dispatches on CLOUD_MODE and uses the plain
#   `docker logs -f` form locally.
#
# USAGE
#   source this file (it expects lib/common.sh already sourced for SSH_OPTS,
#   AETHER_SSH_KEY, CLOUD_MODE, CLOUD_SSH_USER, cloud_public_ip, remote_exec).
#     follow_node_log <node_id> <out_file>        # background tail, prints PID
#     follow_cluster_logs <out_dir> [node_id...]  # tail several nodes at once
# =============================================================================

# ---------------------------------------------------------------------------
# Remote-side resolver + tail builder.
#
# Emitted as a single bash -c payload executed on the VM. It:
#   - resolves a usable `docker` binary across the PATHs an SSH non-login shell
#     may miss (fixes `command not found`),
#   - runs it under sudo when not already root (fixes `permission denied`),
#   - falls through journalctl -> file -> loud failure,
# and streams the chosen source to the SSH stdout (which the caller redirects
# into the per-node log file). The first line it emits is a `# [log-follower]
# source=...` banner so the captured file self-documents which path won.
# ---------------------------------------------------------------------------
_remote_follow_payload() {
    local container="${1:-aether-node}"
    local unit="${2:-aether-node}"
    cat <<REMOTE_FOLLOW
set -u

# sudo prefix only when not already root (cloud_ssh logs in as root by default,
# but an operator override may use a non-root user that needs sudo for the socket).
SUDO=""
if [ "\$(id -u)" != "0" ]; then
    if command -v sudo >/dev/null 2>&1; then SUDO="sudo -n"; fi
fi

# Resolve a docker binary across PATHs a non-login SSH shell may not inherit
# (get.docker.com installs to /usr/bin; snap to /snap/bin). Fixes 'command not found'.
DOCKER=""
for cand in docker /usr/bin/docker /usr/local/bin/docker /snap/bin/docker; do
    if command -v "\$cand" >/dev/null 2>&1 || [ -x "\$cand" ]; then DOCKER="\$cand"; break; fi
done

# --- 1. sudo docker logs -f (the real cloud source) ---
if [ -n "\$DOCKER" ]; then
    # Resolve the container: prefer the well-known name, else the first aether-* container.
    CID="${container}"
    if ! \$SUDO "\$DOCKER" inspect "${container}" >/dev/null 2>&1; then
        CID="\$(\$SUDO "\$DOCKER" ps -q --filter name=aether 2>/dev/null | head -n1)"
    fi
    if [ -n "\$CID" ] && \$SUDO "\$DOCKER" inspect "\$CID" >/dev/null 2>&1; then
        echo "# [log-follower] source=docker container=\$CID via='\$SUDO \$DOCKER logs'"
        exec \$SUDO "\$DOCKER" logs -f --tail 0 "\$CID" 2>&1
    fi
    DOCKER_WHY="docker present but no reachable aether container (socket-perm or not-yet-started)"
else
    DOCKER_WHY="docker binary not found on PATH or known install locations"
fi

# --- 2. sudo journalctl -u <unit> -f (defensive: systemd-managed node) ---
if command -v journalctl >/dev/null 2>&1; then
    if \$SUDO journalctl -u "${unit}" -n0 >/dev/null 2>&1; then
        echo "# [log-follower] source=journald unit=${unit} via='\$SUDO journalctl -u'"
        exec \$SUDO journalctl -u "${unit}" -f --no-pager 2>&1
    fi
    JOURNAL_WHY="journalctl present but unit '${unit}' unknown (node not under systemd)"
else
    JOURNAL_WHY="journalctl not present"
fi

# --- 3. tail -F <app file log> (defensive: file-logging node) ---
for f in /var/log/aether/*.log /opt/aether/logs/*.log /opt/aether/aether-node.log; do
    if [ -f "\$f" ]; then
        echo "# [log-follower] source=file path=\$f via='tail -F'"
        exec tail -F "\$f" 2>&1
    fi
done
FILE_WHY="no app file log at /var/log/aether/*.log, /opt/aether/logs/*.log, /opt/aether/aether-node.log"

# --- 4. FAIL LOUDLY — never silently capture nothing ---
echo "# [log-follower] FAILED to locate any application log source on \$(hostname 2>/dev/null || echo VM)"
echo "# [log-follower]   docker:    \$DOCKER_WHY"
echo "# [log-follower]   journald:  \$JOURNAL_WHY"
echo "# [log-follower]   file:      \$FILE_WHY"
echo "# [log-follower]   (login user=\$(id -un 2>/dev/null) uid=\$(id -u 2>/dev/null); sudo=\${SUDO:-none})"
exit 7
REMOTE_FOLLOW
}

# ---------------------------------------------------------------------------
# follow_node_log <node_id> <out_file> [container] [unit]
#   Starts a background follower for one node, appending to <out_file>.
#   Cloud: SSHes to the node's public IP and runs the resolver payload above.
#   Local: plain `docker logs -f` (socket access works for the harness user).
#   Echoes the background PID to stdout; the caller may record it for teardown.
# ---------------------------------------------------------------------------
follow_node_log() {
    local node_id="$1"
    local out_file="$2"
    local container="${3:-aether-node}"
    local unit="${4:-aether-node}"

    : "${out_file:?follow_node_log: out_file required}"
    : "${node_id:?follow_node_log: node_id required}"

    if [ "${CLOUD_MODE:-false}" = "true" ]; then
        : "${AETHER_SSH_KEY:?AETHER_SSH_KEY must be set for cloud log capture}"
        local target_ip
        if ! target_ip=$(cloud_public_ip "$node_id"); then
            echo "# [log-follower] FAILED: cannot resolve public IP for node '$node_id'" >> "$out_file"
            return 1
        fi
        local payload
        payload="$(_remote_follow_payload "$container" "$unit")"
        # Feed the resolver payload to the remote `bash -s` over STDIN (here-string)
        # rather than as a `bash -c '<quoted>'` argument. This removes every layer of
        # shell-quoting hazard: the script travels as data on stdin, never re-parsed by
        # the local or remote shell as a quoted token. The here-string is ssh's entire
        # stdin (a finite pipe that EOFs after the payload), so ssh cannot drain the
        # surrounding loop's stdin — and `-n` is deliberately NOT used: it would redirect
        # stdin from /dev/null and discard the payload.
        ( while true; do
              ssh "${SSH_OPTS[@]}" -i "${AETHER_SSH_KEY}" \
                  "${CLOUD_SSH_USER:-root}@${target_ip}" 'bash -s' <<<"$payload" \
                  >> "$out_file" 2>&1
              echo "# [log-follower] tail dropped for $node_id ($target_ip); retry in 8s" >> "$out_file"
              sleep 8
          done ) &
        echo "$!"
        return 0
    fi

    # Local docker path — unchanged behaviour; harness user has socket access.
    ( while true; do
          docker logs -f --tail 0 "$container" >> "$out_file" 2>&1
          echo "# [log-follower] tail dropped for $container; retry in 8s" >> "$out_file"
          sleep 8
      done ) &
    echo "$!"
    return 0
}

# ---------------------------------------------------------------------------
# follow_cluster_logs <out_dir> [node_id...]
#   Convenience: start a follower per node, one log file each. PIDs are written
#   to <out_dir>/.follower-pids for teardown (kill $(cat .follower-pids)).
# ---------------------------------------------------------------------------
follow_cluster_logs() {
    local out_dir="$1"; shift
    : "${out_dir:?follow_cluster_logs: out_dir required}"
    mkdir -p "$out_dir"
    : > "$out_dir/.follower-pids"
    local node_id pid
    for node_id in "$@"; do
        pid="$(follow_node_log "$node_id" "$out_dir/${node_id}.log")"
        [ -n "$pid" ] && echo "$pid" >> "$out_dir/.follower-pids"
    done
}

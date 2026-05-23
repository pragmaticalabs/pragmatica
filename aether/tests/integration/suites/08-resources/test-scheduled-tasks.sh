#!/bin/bash
# test-scheduled-tasks.sh — Verify scheduled task execution, last-run advancement, pause/resume
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
source "${SCRIPT_DIR}/../../lib/common.sh"
source "${SCRIPT_DIR}/../../lib/cluster.sh"

test_cluster_ready() {
    wait_for_cluster_ready 60
    log_pass "Cluster ready"
}

test_scheduled_tasks_endpoint() {
    local tasks
    tasks=$(api_get "/api/scheduled-tasks")
    assert_ne "$tasks" "" "Scheduled tasks endpoint returns data"
}

test_task_last_execution_advances() {
    # The heartbeat task is registered by test-persistence's slice activation
    # (publishScheduledTasks fires during the activation chain). Suite bootstrap
    # deploys the blueprint async, then `await_generation_quiesced` waits only
    # for cluster generation quiesce — NOT for slice instance activation, which
    # may lag by tens of seconds (44s observed for cold @PgSql provisioning).
    # Poll for at least one task to surface before reading its fields.
    wait_for "scheduled task registered (post-bootstrap activation)" \
             '[ -n "$(aether_field "scheduled-tasks list" tasks.0.configSection 2>/dev/null)" ]' \
             120 || {
        log_fail "No scheduled tasks present in /api/scheduled-tasks within 120s of cluster ready"
        return 1
    }

    local tasks
    tasks=$(aether_json "scheduled-tasks list") || {
        log_fail "aether scheduled-tasks list failed"
        return 1
    }
    if [ -z "$tasks" ]; then
        log_fail "No tasks returned by scheduled-tasks list"
        return 1
    fi
    # Extract first task's (configSection, artifact, method) triple.
    local section artifact method
    section=$(aether_field "scheduled-tasks list" "tasks.0.configSection") || {
        log_fail "Could not extract task configSection from list"
        return 1
    }
    if [ -z "$section" ]; then
        log_fail "No tasks present (tasks.0.configSection empty)"
        return 1
    fi
    artifact=$(aether_field "scheduled-tasks list" "tasks.0.artifact") || {
        log_fail "Could not extract task artifact from list"
        return 1
    }
    method=$(aether_field "scheduled-tasks list" "tasks.0.method") || {
        log_fail "Could not extract task method from list"
        return 1
    }
    # Capture pre-inject lastExecutionAt.
    local pre_ts
    pre_ts=$(aether_field "scheduled-tasks list" "tasks.0.lastExecutionAt")
    pre_ts="${pre_ts:-0}"
    # Inject (synchronously trigger the task and advance its lastExecutionAt).
    local inject_response inject_rc post_ts
    set +e
    inject_response=$(aether_json scheduled-tasks inject --section "$section" --artifact "$artifact" --method "$method" 2>&1)
    inject_rc=$?
    set -e
    if [ "$inject_rc" -ne 0 ]; then
        log_fail "scheduled-tasks inject failed for ${section}/${artifact}/${method} (rc=${inject_rc}): $(printf '%s' "$inject_response" | head -c 300)"
        return 1
    fi
    if printf '%s' "$inject_response" | grep -q '"error"'; then
        log_fail "scheduled-tasks inject returned error JSON for ${section}/${artifact}/${method}: $(printf '%s' "$inject_response" | head -c 300)"
        return 1
    fi
    # Post-inject: capture currentExecutionMs from response (tolerate missing match
    # under set -euo pipefail — grep -oE exit 1 on no match would otherwise abort
    # the whole script silently).
    post_ts=$(printf '%s' "$inject_response" | grep -oE '"currentExecutionMs"[[:space:]]*:[[:space:]]*[0-9]+' | grep -oE '[0-9]+$' || true)
    post_ts="${post_ts:-0}"
    if [ "$post_ts" -le "$pre_ts" ]; then
        log_fail "Task last-execution did not advance: pre=$pre_ts post=$post_ts"
        return 1
    fi
    log_pass "Task last-execution advanced via inject: $pre_ts -> $post_ts"
}

test_pause_task() {
    local section artifact method
    section=$(aether_field "scheduled-tasks list" "tasks.0.configSection") || {
        log_fail "Could not extract task configSection from list"
        return 1
    }
    if [ -z "$section" ]; then
        log_fail "No tasks present (tasks.0.configSection empty)"
        return 1
    fi
    artifact=$(aether_field "scheduled-tasks list" "tasks.0.artifact") || {
        log_fail "Could not extract task artifact from list"
        return 1
    }
    method=$(aether_field "scheduled-tasks list" "tasks.0.method") || {
        log_fail "Could not extract task method from list"
        return 1
    }

    # Invoke pause via CLI; CLI returns non-zero on transport / non-2xx response.
    local pause_response
    pause_response=$(aether_failover scheduled-tasks pause "$section" "$artifact" "$method" --format json) || {
        log_fail "scheduled-tasks pause CLI failed for ${section}/${artifact}/${method}"
        return 1
    }
    if [ -z "$pause_response" ]; then
        log_fail "scheduled-tasks pause returned empty response for ${section}/${artifact}/${method}"
        return 1
    fi
    # The pause route returns TaskActionResult{success, configSection, artifact, method, action};
    # assert success=true and action=paused so we don't pass on a 2xx error envelope.
    if ! echo "$pause_response" | grep -qE '"success"[[:space:]]*:[[:space:]]*true'; then
        log_fail "Pause response did not assert success=true: ${pause_response}"
        return 1
    fi

    # Readback: confirm the task's `paused` field is now true. The pause command
    # writes to consensus on the leader; the readback may hit any node, so we poll
    # for KV propagation rather than relying on an immediate read. Mirrors the
    # `publish_blueprint` pattern in lib/cluster.sh:1131.
    if ! wait_for "task ${section}/${method} reflects paused=true" \
                  '[ "$(aether_field "scheduled-tasks list" "tasks.0.paused" 2>/dev/null)" = "true" ]' \
                  10; then
        local paused
        paused=$(aether_field "scheduled-tasks list" "tasks.0.paused" 2>/dev/null || echo '')
        log_fail "Post-pause readback: expected tasks.0.paused=true, got '${paused}'"
        return 1
    fi
    log_pass "Task ${section}/${method} paused and readback confirms paused=true"
}

test_resume_task() {
    local section artifact method
    section=$(aether_field "scheduled-tasks list" "tasks.0.configSection") || {
        log_fail "Could not extract task configSection from list"
        return 1
    }
    if [ -z "$section" ]; then
        log_fail "No tasks present (tasks.0.configSection empty)"
        return 1
    fi
    artifact=$(aether_field "scheduled-tasks list" "tasks.0.artifact") || {
        log_fail "Could not extract task artifact from list"
        return 1
    }
    method=$(aether_field "scheduled-tasks list" "tasks.0.method") || {
        log_fail "Could not extract task method from list"
        return 1
    }

    local resume_response
    resume_response=$(aether_failover scheduled-tasks resume "$section" "$artifact" "$method" --format json) || {
        log_fail "scheduled-tasks resume CLI failed for ${section}/${artifact}/${method}"
        return 1
    }
    if [ -z "$resume_response" ]; then
        log_fail "scheduled-tasks resume returned empty response for ${section}/${artifact}/${method}"
        return 1
    fi
    if ! echo "$resume_response" | grep -qE '"success"[[:space:]]*:[[:space:]]*true'; then
        log_fail "Resume response did not assert success=true: ${resume_response}"
        return 1
    fi

    # Readback: confirm the task's `paused` field is now false. Same KV-propagation
    # consideration as the pause readback — poll instead of single-shot read.
    if ! wait_for "task ${section}/${method} reflects paused=false" \
                  '[ "$(aether_field "scheduled-tasks list" "tasks.0.paused" 2>/dev/null)" = "false" ]' \
                  10; then
        local paused
        paused=$(aether_field "scheduled-tasks list" "tasks.0.paused" 2>/dev/null || echo '')
        log_fail "Post-resume readback: expected tasks.0.paused=false, got '${paused}'"
        return 1
    fi
    log_pass "Task ${section}/${method} resumed and readback confirms paused=false"
}

test_cluster_healthy_after_task_ops() {
    assert_cluster_healthy "Cluster healthy after scheduled task operations"
}

run_test "Cluster ready" test_cluster_ready
run_test "Scheduled tasks endpoint" test_scheduled_tasks_endpoint
run_test "Last execution advances" test_task_last_execution_advances
run_test "Pause task" test_pause_task
run_test "Resume task" test_resume_task
run_test "Healthy after task ops" test_cluster_healthy_after_task_ops
print_summary

# Suite audit — 12-network / 13-edge-cases / 14-storage / 15-delegation

Scope: every `test_*` function in the four assigned suites. Helpers in `lib/*.sh` are treated as opaque per brief.

Prior-art note: the 88638126 audit findings on `test-gossip-encryption.sh`, `test-quic-connectivity.sh`, `test-disruption-budget.sh`, `test-concurrent-deploys.sh`, `test-stale-route-cleanup.sh` and `test-02-reassignment.sh` have largely been **remediated since that audit**. Status against each prior is called out inline.

---

## Suite 12-network

### test-gossip-encryption.sh

#### test_cluster_ready (L9)
- **Claims:** cluster reaches ready state.
- **Actually checks:** `wait_for_cluster_ready 60`; unconditional `log_pass`.
- **Assertions:** none — pass is wired to "helper did not throw".
- **Correctness:** ACCEPTABLE — helper is the sound part; the literal `log_pass` is a structural sticker on the file, not on a check.
- **Tooling:** lib helper.
- **Severity:** LOW.

#### test_cluster_formed_with_encryption (L14)
- **Claims:** 5 ON_DUTY healthy cores formed under encrypted gossip.
- **Actually checks:** `cluster_active_core_count == 5`.
- **Assertions:** L24 `assert_eq count 5`.
- **Correctness:** SOUND for operational health. Does NOT itself check encryption — but encryption is checked in the sibling test below, so the title is misleading rather than tautological.
- **Tooling:** api_get + helper.
- **Severity:** LOW (rename suggested).

#### test_gossip_encryption_active_via_config (L27, renamed semantics)
- **Claims:** TLS handshakes occurred → cluster transport is TLS.
- **Actually checks:** `quic_handshake_total > 0` from `/api/metrics/transport`.
- **Assertions:** L36-39 empty-body fail; L42 `> 0` pass; L46 fail.
- **Correctness:** SOUND. Resolves prior-audit GREEN-STICKER (config-path fishing → warn-then-pass). The metric is a runtime fact: any non-zero count proves QuicSslContext executed at least one handshake. Empty body now fails (was warn). Empty/non-numeric defaults to `-1` so the `-gt 0` branch is safe.
- **Tooling:** curl-direct via `api_get` + `json_value`.
- **Severity:** RESOLVED.

#### test_gossip_encryption_via_transport (L50)
- **Claims:** TLS handshakes succeed (failures bounded).
- **Actually checks:** `quic_handshake_total >= 1` AND `quic_handshake_failures_total <= total/2`.
- **Assertions:** L65 fail if total<1; L72 fail if failures > total/2; L76 pass.
- **Correctness:** SOUND, but the **50% failure threshold is loose**. A 49% TLS-handshake failure rate would PASS — that is a real cert/protocol regression in production. Tighten to a much smaller ratio (e.g. ≤ 5%) once expected post-chaos churn is characterised.
- **Tooling:** curl-direct + json_value.
- **Severity:** MEDIUM — production regression at 10-40% failure rate would not fire.

#### test_nodes_communicating_encrypted (L79)
- **Claims:** gossip works → encryption works.
- **Actually checks:** `cluster_leader` non-empty, `cluster_events` non-empty.
- **Assertions:** L83, L87 `assert_ne ""`.
- **Correctness:** WEAK — proves gossip live, not encryption. Implicit reasoning ("if QUIC were broken, leader would be empty") is rational but not directly tested. Redundant with `test_gossip_encryption_via_transport`.
- **Tooling:** lib helpers.
- **Severity:** LOW.

#### test_health_probes_over_encrypted_transport (L90)
- **Claims:** health probes work over encrypted transport.
- **Actually checks:** `assert_cluster_healthy`; `assert_http_status .../health/live 200`.
- **Correctness:** SOUND for liveness. Decoupled from encryption — depends on sibling test for that claim.
- **Tooling:** lib helper + curl.
- **Severity:** LOW.

**Summary:** prior `warn-then-pass` regression is **fixed**. Residual gap: handshake-failure threshold is 50% (too lax). pass=4 / fail=6 / warn=3 (the 3 warns are pre-condition soft-gates on `wait_for_phase NORMAL`, intentional).

---

### test-quic-connectivity.sh

#### test_cluster_ready (L12)
- **Claims:** cluster ready, NORMAL phase preferred.
- **Actually checks:** `wait_for_cluster_ready 60`; `wait_for_phase NORMAL 180` (soft, warn-then-pass on miss).
- **Correctness:** Soft `wait_for_phase` is **a documented degraded-cluster pass-through**. The author's stated rationale (cumulative degradation in cluster B) is real, but a NORMAL-phase requirement that downgrades to `log_warn` means subsequent kill-detection tests run against a possibly cold-boot cluster where kills produce `UnknownObserved` and no NODE_FAILED event fires — the test then fails on the subsequent event-wait, which is the safety net. Acceptable but fragile.
- **Severity:** LOW.

#### test_all_nodes_connected (L25)
- **Claims:** every node sees ≥4 QUIC peers.
- **Actually checks:** queries ONE node's `connectedPeerCount`, asserts `>= 4`.
- **Correctness:** SOUND but **insufficient coverage**. Only the entry node is checked — a partition that leaves one node with 4 peers and another with 0 would PASS. Prior audit flagged the `-1` fallback collapsing to `assert_eq 5`; current code uses `-ge 4` with a `-1` default, which correctly fails on empty/malformed. RESOLVED for the original GREEN-STICKER; new gap is per-node coverage.
- **Tooling:** api_get + json_value (single endpoint).
- **Severity:** MEDIUM — extend to iterate `/api/nodes/status` per-node QUIC connectivity.

#### test_kill_node_and_detect_drop (L49)
- **Claims:** kill produces NODE_FAILED + replacement + no quorum break.
- **Actually checks:** `wait_for_node_departure` (event-driven); `wait_for_replacement_of` (NODE_JOINED in event log); `observe_quorum_window`.
- **Correctness:** SOUND. Event-driven barriers replace prior snapshot polling and the kill→detect contract is clear.
- **Tooling:** topology.sh helpers + cluster.sh.
- **Severity:** LOW.

#### test_connections_recovered (L87)
- **Claims:** post-recovery 5 ON_DUTY healthy cores.
- **Actually checks:** `wait_for "5 ON_DUTY healthy cores" 180`; `assert_cluster_healthy`.
- **Correctness:** SOUND. Documents the deliberate non-revival of the killed node (elastic-cluster model).
- **Severity:** LOW.

**Summary:** prior GREEN-STICKER (metric -1 → wrong proxy) RESOLVED. Residual: per-node QUIC coverage missing. pass=4 / fail=6 / warn=3.

---

### test-swim-detection.sh

#### test_cluster_ready (L17)
- **Claims:** 5 ON_DUTY healthy cores; soft NORMAL phase preference.
- **Actually checks:** `wait_for_cluster_ready 60`; soft `wait_for_phase`; `assert_eq count 5`.
- **Correctness:** SOUND for the core-count assertion; same soft-phase fragility as the QUIC test.
- **Severity:** LOW.

#### test_swim_detection_time (L32)
- **Claims:** SWIM detects faulty node within DETECTION_TIMEOUT (default 15s).
- **Actually checks:** kills node, measures elapsed until departure event; **passes regardless of whether elapsed ≤ 15s**.
- **Assertions:** L47 wait_for_node_departure with 60s budget; **L51-56: if elapsed > DETECTION_TIMEOUT, `log_warn` then `log_pass` anyway.**
- **Correctness:** GREEN-STICKER — the entire purpose of "SWIM detection time" is to fail when detection breaches the budget. Demoting to `log_warn` then `log_pass` means any detection in [16s, 60s] silently passes despite breaching the spec'd 15s detection window. A real regression that pushed detection to 45s would still PASS the test.
- **Tooling:** event-poll via topology.sh + epoch math.
- **Severity:** HIGH — RC1 readiness gap. Either delete the threshold check or make it authoritative.

#### test_recovery_after_detection (L63)
- **Claims:** post-kill convergence to 5 ON_DUTY healthy cores.
- **Actually checks:** `wait_for "5 ON_DUTY..." 180` + `assert_cluster_healthy`.
- **Correctness:** SOUND.
- **Severity:** LOW.

**Summary:** SWIM-detection-time threshold is non-binding. pass=2 / fail=2 / warn=3.

---

### test-partition-quorum-gate.sh

#### test_initial_state (L169)
- **Claims:** 5 ON_DUTY healthy cores under NORMAL phase.
- **Actually checks:** wait_for_cluster_ready, soft `wait_for_phase NORMAL`, `wait_for_leader 60`, `assert_eq count 5`.
- **Correctness:** SOUND, with the documented soft-phase trade-off (NORMAL is the precondition for the gate's cold-start fallback NOT to leak through; if NORMAL fails, S05 may become unfalsifiable — that's flagged in the comment).
- **Severity:** LOW.

#### test_pick_minority (L186)
- **Claims:** identifies leader + 2 non-leader minority targets.
- **Actually checks:** `cluster_leader`; `pick_non_leader 2`; persists to file.
- **Correctness:** SOUND — both fail paths (empty leader, <2 minority) `log_fail`.
- **Severity:** LOW.

#### test_partition_does_not_decommission_within_window (L214)
- **Claims:** S05 — within a 5s partition (< 8s self-drain), the gate blocks DECOMMISSIONED writes for minority NodeIds.
- **Actually checks:** pre-condition KV ON_DUTY for both targets; disconnects via `docker network disconnect`; polls KV lifecycle for both at ~1Hz for 5s, failing **immediately** on first DECOMMISSIONED read; heals on exit.
- **Assertions:** L232/233 pre-cond ON_DUTY; L249/253 fail on DECOMMISSIONED inside window; L260 pass after window.
- **Correctness:** SOUND. Reads authoritative `/api/nodes/lifecycle/{id}` (KV-direct, not MembershipView), which is correct per the inline comment. Sample window is 5 polls × 1Hz; failure would manifest in 1-2 FSM cycles, so coverage is adequate.
- **Tooling:** docker network manipulation via remote_exec; api_get; grep-extract.
- **Severity:** LOW. Best-in-suite test.

#### test_cluster_heals_to_5_onduty (L268)
- **Claims:** S06 — within HEAL_BUDGET_S (30s), cluster returns to 5 ON_DUTY.
- **Actually checks:** `wait_for "5 ON_DUTY..." 30`; `assert_cluster_healthy`.
- **Correctness:** SOUND, tight budget.
- **Severity:** LOW.

**Summary:** This file is exemplary — explicit FSM cells named in failure messages, authoritative endpoint, EXIT trap for partition heal, idempotent cleanup. pass=3 / fail=7 / warn=6 (warns are soft preconditions and idempotent cleanup; not silent-pass).

---

## Suite 13-edge-cases

### test-concurrent-deploys.sh

#### test_cluster_ready (L17)
- **Claims:** cluster ready with both baseline blueprints deployed.
- **Actually checks:** wait_for_cluster_ready, generation barriers, push+deploy of two distinct blueprints, `wait_for_slices_active 1`.
- **Correctness:** SOUND for setup. Multiple `log_warn` fallbacks on quiesce timing — soft-gates on inherited-churn from prior suite. Acceptable.
- **Severity:** LOW.

#### test_initial_slice_count (L33)
- **Claims:** slices endpoint returns data.
- **Actually checks:** `assert_ne slices ""`.
- **Correctness:** WEAK — proves response non-empty, not slice count > 0. Title says "count" but no count is asserted.
- **Severity:** LOW (semantic only).

#### test_concurrent_deploy (L39)
- **Claims:** two concurrent stream publishes both succeed (2xx).
- **Actually checks:** parallel curl POSTs to two streams; both statuses must be `200-299`.
- **Assertions:** L94-95 explicit 2xx check; fail otherwise.
- **Correctness:** SOUND. Prior `< 500` GREEN-STICKER **RESOLVED** — code now requires strict 2xx for both.
- **Tooling:** curl-direct (http_status), bash background processes.
- **Severity:** RESOLVED.

#### test_both_blueprints_visible (L105)
- **Claims:** slices endpoint returns data after concurrent ops.
- **Actually checks:** quiesce barrier, `cluster_slices`; passes if non-empty, **also passes (`log_pass "Slices endpoint responds"`) when empty after a warn**.
- **Assertions:** L111 pass-on-data; **L114-116 pass-on-empty-after-warn**.
- **Correctness:** GREEN-STICKER. Prior-audit flag persists. An empty slices payload after deploying two blueprints is a real product failure; the fallback `log_pass "Slices endpoint responds"` swaps subjects ("blueprints visible" → "endpoint responds"). Either escalate empty to `log_fail` or delete this test (the next test `test_slices_active_after_concurrent_deploy` covers the real claim).
- **Severity:** HIGH — RC1-BLOCK candidate. Title and behaviour diverge under failure.

#### test_slices_active_after_concurrent_deploy (L119)
- **Claims:** slices active after concurrent deploy.
- **Actually checks:** `wait_for_slices_active 1 120`; `slices_total_instances > 0`.
- **Correctness:** SOUND.
- **Severity:** LOW.

#### test_artifact_isolation (L126)
- **Claims:** both test-echo and test-persistence appear as distinct artifacts.
- **Actually checks:** grep for both artifact GAVs in `/api/slices` JSON; fail unless both present.
- **Correctness:** SOUND. Real isolation check (matches prior-audit recommendation).
- **Tooling:** grep on JSON.
- **Severity:** LOW.

#### test_cluster_healthy_after_concurrent_deploys (L150)
- **Claims:** cluster healthy.
- **Actually checks:** `assert_cluster_healthy`.
- **Correctness:** SOUND.
- **Severity:** LOW.

**Summary:** Most prior-audit GREEN-STICKERS resolved; **L114-116 remains the live one**. pass=5 / fail=2 / warn=7.

---

### test-disruption-budget.sh

#### test_cluster_ready (L9)
- **Claims:** cluster ready, ≥3 nodes, CTM auto-heal disabled for the suite.
- **Actually checks:** wait_for_cluster_ready, quiesce barrier, `cluster_member_count >= 3`, `disable_auto_heal`. EXIT trap re-enables.
- **Correctness:** SOUND. The auto-heal disable is essential to make L102-126 deterministic. Prior audit's "every-outcome → cannot fail" pattern was caused by auto-heal racing; **resolved here by explicit disable**.
- **Severity:** RESOLVED.

#### test_drain_first_node_allowed (L42)
- **Claims:** first drain accepted (2xx).
- **Actually checks:** curl POST to `/api/nodes/drain/{id}`; pass on 2xx, fail on anything else (with body capture for diagnostics).
- **Correctness:** SOUND — a TODO comment notes a known 503 mode but the assertion does NOT silently demote (explicit `log_fail` per L77).
- **Severity:** LOW.

#### test_drain_second_node_allowed (L81)
- **Claims:** second drain accepted (still within budget) — race-tolerant with 409 if auto-heal interleaves.
- **Actually checks:** 2xx PASS, 409 PASS-with-rationale, otherwise FAIL.
- **Correctness:** WEAK — auto-heal is disabled for the suite by the prior test, so the 409 race-window comment doesn't apply here, yet the code still accepts 409. The justification is "race during disable/in-flight provisioning"; that race should not exist if disable_auto_heal is synchronous. If it really is asynchronous, the prior test's `log_pass` is premature. Either way the dual-status acceptance is a semantic looseness.
- **Severity:** MEDIUM — the 409 fallback may mask a real budget-misallocation bug. Tighten to "2xx only" once disable_auto_heal is verified synchronous.

#### test_drain_beyond_budget_rejected (L102)
- **Claims:** third drain rejected with 409.
- **Actually checks:** drain attempt; require status == 409 strictly.
- **Correctness:** SOUND. Prior audit's "every outcome accepted" GREEN-STICKER **RESOLVED** — now strict 409.
- **Severity:** RESOLVED.

#### test_quorum_preserved (L128)
- **Claims:** quorum preserved after drains.
- **Actually checks:** `assert_cluster_healthy`.
- **Correctness:** SOUND but indirect — `assert_cluster_healthy` doesn't directly check quorum math, it checks the cluster's health endpoint.
- **Severity:** LOW.

#### test_reactivate_nodes (L132)
- **Claims:** drained nodes reactivated, cluster healthy.
- **Actually checks:** grep lifecycle for "drain" substring, iterate node IDs and call `activate_node`; surfaces failures as `log_warn` via stderr capture.
- **Correctness:** Prior `activate_node ... 2>/dev/null || true` GREEN-STICKER **RESOLVED** — now captures stderr and warns. But: ultimate cleanup-step failure does not fail the test. For cleanup that's defensible; an ops-impacting drain that won't reactivate should at minimum surface to the suite summary as a failure.
- **Severity:** LOW (resolution acceptable for cleanup-class step).

**Summary:** Major prior-audit issues RESOLVED. Residual: L94-99 dual-status looseness. pass=5 / fail=5 / warn=4.

---

### test-stale-route-cleanup.sh

#### test_cluster_ready (L12)
- **Claims:** cluster ready, baseline blueprint deployed.
- **Actually checks:** wait_for_cluster_ready, quiesce barriers, push+deploy, wait_for_slices_active.
- **Correctness:** SOUND.
- **Severity:** LOW.

#### test_slices_deployed (L24)
- **Claims:** slices deployed (instances > 0).
- **Actually checks:** `wait_for_slices_active 1 120` + `slices_total_instances > 0`.
- **Correctness:** SOUND.
- **Severity:** LOW.

#### test_app_routes_reachable (L31)
- **Claims:** EchoSlice's `/api/echo/health` route is wired (positive readiness).
- **Actually checks:** `app_route_wired` against the prefixed APP path; explicit `log_fail` if not wired; **no management-API fallback**.
- **Correctness:** SOUND. Prior audit's wrong-endpoint GREEN-STICKER **RESOLVED** — code now probes APP route on APP_ENDPOINT (not /api/status on management port).
- **Severity:** RESOLVED.

#### test_kill_node_hosting_routes (L48)
- **Claims:** killing a non-leader hosting routes triggers generation advance.
- **Actually checks:** picks non-leader from `cluster_node_list`, `kill_node`, fixed `sleep 5`, then `await_generation_quiesced "current+1"` with timeout — warns on miss.
- **Correctness:** WEAK — the hardcoded `sleep 5` is a small smell but the real barrier is `await_generation_quiesced`. The `log_pass "Route cleanup fenced by generation advance"` at L80 fires unconditionally after the quiesce call (which only warns on failure, never fails). So a generation that never advanced PASSES the test.
- **Severity:** MEDIUM — quiesce-timeout should fail this test, not warn. The next test (no 502/504) is a partial safety net but tests a different surface.

#### test_no_502_504_after_cleanup (L83)
- **Claims:** no 502/504 on APP route over 10 probes.
- **Actually checks:** 10 × `http_status` on `/api/echo/health`; count 502/504; `assert_eq count 0`.
- **Correctness:** SOUND. Prior audit's wrong-endpoint GREEN-STICKER (probing mgmt /api/status) **RESOLVED**. Note: 10 polls at 1Hz may miss a brief window — fine for catch-stale, weak for catch-flaky.
- **Severity:** LOW.

#### test_kv_store_routes_clean (L102)
- **Claims:** slices endpoint responds after cleanup.
- **Actually checks:** `assert_ne slices ""`.
- **Correctness:** WEAK — only proves the response is non-empty; doesn't verify the killed-node's slice records are pruned. Title implies cleanup verification but assertion is "endpoint up".
- **Severity:** LOW (semantic only).

#### test_recovery_complete (L109)
- **Claims:** cluster recovered to 5 nodes.
- **Actually checks:** `wait_for_node_count 5 90` + `assert_cluster_healthy`.
- **Correctness:** SOUND.
- **Severity:** LOW.

**Summary:** Endpoint-surface GREEN-STICKERS resolved. Residual: L80 unconditional pass after a non-fatal quiesce. pass=4 / fail=1 / warn=4.

---

## Suite 14-storage

### test-storage-cli.sh

#### test_cluster_ready (L9)
- **Claims:** cluster ready.
- **Actually checks:** wait_for_cluster_ready; unconditional `log_pass`.
- **Correctness:** ACCEPTABLE.
- **Severity:** LOW.

#### test_cli_storage_list (L15)
- **Claims:** CLI `storage list` returns output.
- **Actually checks:** runs `aether_failover storage list 2>/dev/null || true`; `skip_test` on empty; `assert_ne ""` otherwise.
- **Correctness:** GREEN-STICKER candidate — `2>/dev/null || true` masks CLI errors as "no instances configured" → `skip_test`. A real CLI regression (binary crash, missing subcommand) would skip-not-fail. Project memory `feedback_silent_stderr_is_a_trap` explicitly forbids this pattern.
- **Tooling:** CLI via aether_failover.
- **Severity:** HIGH — production regression silently skipped.

#### test_cli_storage_status (L26)
- **Claims:** CLI `storage status <name>` returns detail.
- **Actually checks:** discovers an instance name via REST, then same `|| true` skip-on-empty pattern.
- **Correctness:** Same GREEN-STICKER as above (silent stderr → skip).
- **Severity:** HIGH.

#### test_cli_storage_list_json (L54)
- **Claims:** CLI `storage list --format json` returns valid JSON.
- **Actually checks:** runs CLI, regex-checks first non-whitespace char is `{` or `[`. Skips on empty.
- **Correctness:** WEAK — `^\s*[\{\[]` only verifies the leading character, NOT that the output parses as JSON. A truncated/malformed `{"instances":...` (no closing brace) PASSES. Use a real parser (`json_value` / jq is forbidden per memory but `python3 -m json.tool` or a Java-side validator could be used). Plus the same silent-skip issue on empty.
- **Severity:** MEDIUM (looseness) + HIGH (silent skip).

**Summary:** Suite is **mostly stubs** — three tests gated on `skip_test` when CLI output is empty (silent stderr trap). The "list returns JSON" test only verifies the first char. pass=2 / fail=1 / skip=5 / warn=0.

---

### test-storage-management.sh

#### test_cluster_ready (L9)
- Same as elsewhere; ACCEPTABLE.

#### test_storage_list (L15)
- **Claims:** `/api/storage` returns instance list.
- **Actually checks:** grep for `"instances"` OR an empty `{}` object; skip on empty body.
- **Correctness:** WEAK — accepts `{}` (no instances) as PASS. Title says "returns instance list" but `{}` proves the endpoint exists, not that it lists anything. Plus skip-on-empty masks regressions.
- **Severity:** MEDIUM.

#### test_storage_list_contains_artifacts (L32)
- **Claims:** default "artifacts" instance present in list.
- **Actually checks:** grep "artifacts"; on miss: `log_warn` then **`return 0` (silent pass)**.
- **Correctness:** GREEN-STICKER — primary claim of the test demoted to warn. The "artifacts" instance is mandatory for the artifact-repo service used by every deploy; its absence is a real product regression.
- **Severity:** HIGH — RC1-BLOCK candidate. Either delete the test or escalate the missing-artifacts case to fail.

#### test_storage_instance_detail (L53)
- **Claims:** `/api/storage/{name}` returns detail with tiers + readiness.
- **Actually checks:** discovers a name; `assert_contains` for "tiers" and "readiness"; skip on empty.
- **Correctness:** SOUND once we have a name. `assert_contains` is a substring grep — vulnerable if the JSON contained the substring in a different position (e.g. inside an error payload), but the API surface makes that unlikely.
- **Severity:** LOW.

#### test_storage_snapshot (L78)
- **Claims:** POST `/api/storage/snapshot/{name}` triggers snapshot.
- **Actually checks:** discovers a name, POSTs; on empty response: `log_warn` then **`return 0`**.
- **Correctness:** GREEN-STICKER. The whole test is the trigger; empty body means "not wired yet" — that's a real gap. Snapshot pass-on-empty is a stub. The subsequent `assert_contains "$snapshot" "epoch"` is reachable only on non-empty.
- **Severity:** HIGH.

#### test_cluster_storage_view (L104)
- **Claims:** `/api/cluster/storage` returns instances.
- **Actually checks:** `assert_contains result "instances"`; skip on empty.
- **Correctness:** WEAK — substring grep on JSON, but adequate for an endpoint smoke.
- **Severity:** LOW.

#### test_cluster_storage_detail (L115)
- **Claims:** cluster-wide instance detail includes nodeCount + nodes.
- **Actually checks:** discovers name; `assert_contains` for "nodeCount" and "nodes"; skip on empty.
- **Correctness:** SOUND.
- **Severity:** LOW.

**Summary:** Storage management suite is **smoke-test grade** — heavy on `skip_test` and `log_warn → return 0`. Suite passes regardless of whether storage subsystem is actually functional. pass=3 / fail=1 / skip=3 / warn=12.

---

## Suite 15-delegation

### test-01-task-assignments.sh

#### test_cluster_ready (L13)
- **Claims:** cluster ready, leader elected, task groups ACTIVE.
- **Actually checks:** wait_for_cluster_ready 120, `assert_ne leader ""`, soft `wait_for_all_tasks_active` (warn on miss).
- **Correctness:** SOUND core checks; soft-warn on task-active aligns with pattern in other suites.
- **Severity:** LOW.

#### test_tasks_api_returns_data (L25)
- **Claims:** `/api/cluster/tasks` returns non-empty with `assignments`.
- **Actually checks:** `assert_ne ""`, `assert_contains "assignments"`.
- **Correctness:** SOUND.
- **Severity:** LOW.

#### test_all_groups_assigned (L35)
- **Claims:** ≥6 task groups assigned.
- **Actually checks:** `task_assignment_count >= 6`.
- **Correctness:** SOUND.
- **Severity:** LOW.

#### test_all_groups_active (L44)
- **Claims:** all 6 named task groups reach ACTIVE.
- **Actually checks:** `wait_for` ≥6 ACTIVE entries, then per-group `assert_eq status ACTIVE` for METRICS, SCALING, STRATEGIES, DEPLOYMENT, STORAGE, STREAMING.
- **Correctness:** SOUND. Strong per-group enumeration prevents the "≥6 but wrong groups" failure mode.
- **Severity:** LOW.

#### test_tasks_distributed (L60)
- **Claims:** tasks distributed across ≥2 nodes ("not all on leader").
- **Actually checks:** unique `assignedTo` values; `assert_ge unique_nodes 1`.
- **Correctness:** GREEN-STICKER. Title says "not all on leader" and comment says "should have ≥2", but assertion is `>= 1` — i.e. all-on-leader PASSES. The original distribution claim is structurally untestable as written.
- **Tooling:** grep on JSON.
- **Severity:** HIGH — RC1-BLOCK candidate. Either tighten to `>= 2` or rename to "tasks have at least one assigned node".

#### test_assignments_point_to_valid_nodes (L73)
- **Claims:** no empty `assignedTo`.
- **Actually checks:** grep `"assignedTo":""`; fail if found.
- **Correctness:** SOUND for empty-string case. Does NOT verify the node id is in the live cluster (a stale-but-non-empty assignment passes). Title overpromises.
- **Severity:** LOW.

#### test_deployment_group_functional (L89)
- **Claims:** DEPLOYMENT ACTIVE → CDM functional → slices endpoint works.
- **Actually checks:** `status == ACTIVE`; `cluster_slices` rc + non-empty. Prior `|| echo ""` GREEN-STICKER **RESOLVED** per L100-106 comment.
- **Correctness:** SOUND.
- **Severity:** RESOLVED.

#### test_metrics_group_functional (L112)
- **Claims:** METRICS ACTIVE → collection running.
- **Actually checks:** `status == ACTIVE`; `task_group_node METRICS` non-empty.
- **Correctness:** WEAK — proves an assignment exists, not that collection is actually running (no metric value, no sample timestamp). Title overpromises.
- **Severity:** MEDIUM — adding a positive probe (e.g. recent sample exists on `/api/metrics/...`) would close the gap.

**Summary:** Prior `|| echo ""` resolved. Live GREEN-STICKER: `test_tasks_distributed` `>= 1`. pass=5 / fail=1 / warn=1.

---

### test-02-reassignment.sh

#### test_prerequisite (L31)
- **Claims:** cluster healthy with all 6 task groups ACTIVE.
- **Actually checks:** wait_for_cluster_ready, `wait_for` on ACTIVE count ≥ 6, `log_pass`.
- **Correctness:** SOUND.
- **Severity:** LOW.

#### test_operator_reassign (L42)
- **Claims:** operator can force-reassign METRICS via API.
- **Actually checks:** discovers current node, picks a different one, `reassign_task_group`, `wait_for_task_assigned METRICS target 30`, `assert_eq new_node target`.
- **Correctness:** SOUND. Comment at L60-62 explicitly addresses the stale-ACTIVE race.
- **Severity:** LOW.

#### test_reassignment_status_active (L73)
- **Claims:** METRICS ACTIVE after reassignment.
- **Actually checks:** `assert_eq status ACTIVE`.
- **Correctness:** SOUND.
- **Severity:** LOW.

#### test_other_groups_unaffected (L82)
- **Claims:** other 5 groups still ACTIVE.
- **Actually checks:** per-group `assert_eq status ACTIVE`.
- **Correctness:** SOUND.
- **Severity:** LOW.

#### test_node_failure_reassignment (L94)
- **Claims:** killing the SCALING-host triggers reassignment.
- **Actually checks:** skips if no SSH key; identifies SCALING host; pre-reassigns to non-leader if needed; captures topology baseline; `kill_node`; **event-driven `wait_for_node_departure` (90s)** replaces the prior hardcoded 5s sleep; `wait_for_task_active SCALING 60`; asserts non-empty + ACTIVE; **deliberately does NOT call `start_node`** per the Wave 7 single-writer fix; waits 240s for cluster_ready.
- **Correctness:** SOUND. Prior-audit `sleep 5` GREEN-STICKER **RESOLVED** (now event-driven at L130). Also reflects the 9b37f4b5c removal of start_node revival (comment at L154-159 makes this explicit). Remaining looseness: the assertion `assert_ne new_node ""` (L147) plus `assert_eq status ACTIVE` is weaker than "SCALING is on a node that is currently ON_DUTY and reachable", but the documented rationale (CTM may reuse the logical id at a fresh port) is correct.
- **Severity:** RESOLVED.

**Summary:** Prior `sleep 5` and start_node revival both RESOLVED. pass=4 / fail=1 / warn=1 / skip=3.

---

## Severity rollup

| Rank | Test | Severity | Reason |
|---|---|---|---|
| 1 | 12-network/test-swim-detection.sh L51-56 | HIGH | DETECTION_TIMEOUT threshold is non-binding (log_warn → log_pass anyway). Detection > 15s silently passes. |
| 2 | 13-edge-cases/test-concurrent-deploys.sh L114-116 | HIGH | Empty slices payload `log_pass`'d as "endpoint responds" — subject swap. |
| 3 | 14-storage/test-storage-management.sh L43-49 | HIGH | Missing "artifacts" instance demoted to log_warn → return 0. |
| 4 | 14-storage/test-storage-management.sh L97-99 | HIGH | Snapshot endpoint empty body → log_warn → return 0. |
| 5 | 14-storage/test-storage-cli.sh L18-22, 45-49, 56-60 | HIGH | `2>/dev/null \|\| true` masks CLI failure as skip_test. |
| 6 | 15-delegation/test-01-task-assignments.sh L67 | HIGH | Distribution claim "across at least 2 nodes" actually asserts `>= 1`. |
| 7 | 12-network/test-gossip-encryption.sh L72 | MEDIUM | TLS handshake failure ceiling 50% — too lax. |
| 8 | 12-network/test-quic-connectivity.sh L41 | MEDIUM | Per-node QUIC coverage missing (one node sampled). |
| 9 | 13-edge-cases/test-disruption-budget.sh L94-99 | MEDIUM | 2xx OR 409 dual acceptance on second drain. |
| 10 | 13-edge-cases/test-stale-route-cleanup.sh L78-80 | MEDIUM | Quiesce-timeout downgraded to warn, then unconditional log_pass. |
| 11 | 14-storage/test-storage-cli.sh L62-67 | MEDIUM | Leading-char regex passes truncated/malformed JSON. |
| 12 | 14-storage/test-storage-management.sh L23 | MEDIUM | Empty `{}` accepted as "list returns instances". |
| 13 | 15-delegation/test-01-task-assignments.sh L114-120 | MEDIUM | METRICS "functional" check only verifies assignment exists, not sampling. |

## RC1-BLOCK shortlist
- 14-storage suite as a whole (4 of 9 storage tests silently pass on absent functionality).
- test-swim-detection.sh detection budget non-binding.
- test_tasks_distributed `>= 1`.

## Self-check pass:fail:skip:warn

| File | pass | fail | skip | warn |
|---|---|---|---|---|
| 12/test-gossip-encryption.sh | 4 | 6 | 0 | 3 |
| 12/test-quic-connectivity.sh | 4 | 6 | 0 | 3 |
| 12/test-swim-detection.sh | 2 | 2 | 0 | 3 |
| 12/test-partition-quorum-gate.sh | 3 | 7 | 0 | 6 |
| 13/test-concurrent-deploys.sh | 5 | 2 | 0 | 7 |
| 13/test-disruption-budget.sh | 5 | 5 | 0 | 4 |
| 13/test-stale-route-cleanup.sh | 4 | 1 | 0 | 4 |
| 14/test-storage-cli.sh | 2 | 1 | 4 | 1 |
| 14/test-storage-management.sh | 3 | 1 | 3 | 12 |
| 15/test-01-task-assignments.sh | 5 | 1 | 0 | 1 |
| 15/test-02-reassignment.sh | 4 | 1 | 2 | 1 |

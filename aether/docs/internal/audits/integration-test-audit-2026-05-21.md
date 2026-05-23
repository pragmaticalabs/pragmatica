# Integration Test Audit — 2026-05-21

| Field | Value |
|---|---|
| Branch | `release-1.0.0-rc1` |
| HEAD | `a52dd99d4` |
| Scope | `aether/tests/integration/suites/**` + `aether/tests/integration/lib/**` |
| Method | 7 parallel agents (1 per suite batch + lib + CLI inventory + prior-audit triage) |
| Reference docs | `aether/docs/specs/test-readiness-contract.md`, `aether/docs/reference/cli.md` |
| Detailed evidence | `integration-test-audit-2026-05-21-partials/` (7 source files) |

## Executive summary

200+ test functions across 16 suites (`00-smoke` through `15-delegation`) plus opt-in `01-stability`, layered over 124 helper functions in `lib/`.

| Severity | Count | Disposition |
|---|---|---|
| **RC1-BLOCK** | **18** | Tests whose claim is materially uncoupled from their assertion — would not catch the regression they advertise. Listed in §2.2. |
| **HIGH (RC2)** | 12 | Material green stickers; remediation needed before product matures but not RC1-critical given mitigations. |
| **MEDIUM (RC2)** | 19 | Narrow coverage / name-vs-check mismatch / dual-status acceptance. |
| **LOW** | ~20 | Redundancy, semantic looseness, intentional skips. |
| **SOUND** | ~140 | Strict assertion; would fail on a real regression. |

**Prior audit status:** Of the 45 findings in the 2026-05-10 "green sticker audit" (session `88638126`), **23 are DONE**, 4 PARTIAL, 4 OPEN, 0 STALE. The audit campaign closed most of the institutionalised demotion patterns (warn-then-pass, `< 500` predicate, fall-back-to-stale defaults). The 18 RC1-blockers identified below are mostly **different patterns** that the prior audit didn't reach, not regressions.

**Suites with concentrated RC1-blockers:**
- **05-security** (4 RC1-blockers) — entire `test-principal-injection.sh` file does not verify principal/identity; `test-cert-rotation.sh` tests `/health/live` (auth-bypassed) under a "TLS rotation" name.
- **06-deployment** (3+1 RC1-blockers) — all three strategy `*_promote` tests log status but never assert; `test_blue_green_rollback` defined but **never invoked** by `run_test`.
- **07-cluster-mgmt** (2 RC1-blockers) — `test_config_visible_on_all_nodes` queries the same endpoint twice (never probes other nodes); `test_config_identical_after_reapply` computes byte counts but never asserts identity.
- **08-resources** (2 RC1-blockers) — `test_subscriber_receives_events` and `test_task_last_execution_advances` are pure warn-then-pass; `test-scheduled-tasks.sh` has 11 `log_pass` and 0 hard `log_fail` in demoted paths.

**Strongest suites:** **09-artifacts** (cryptographic SHA-256 equality makes assertions non-fakeable), **10-database** (all prior green stickers remediated with the datasource-discovery pattern), **11-observability** (massive remediation via `/api/alerts/inject` + `/api/traces/inject` test endpoints).

**Weakest suite:** **14-storage** (4 of 9 tests silently `skip_test` or `log_warn → return 0` on absent functionality; not just under-tested but actively misleading).

**Tooling distribution:** CLI dominant for cluster mgmt (~70% of helpers). `curl -direct` confined to liveness probes (`/health/live`) and app-route status code checks (per `feedback_prefer_aether_cli`). `docker exec` is appropriate for chaos kills and teardown. `log-grep` is rare and confined to 02-chaos negative assertions — both explicitly documented as "soft" or "alternative acceptance" by their authors.

---

# §1 Catalog

Per-suite catalogue of every test function. Each row classifies:
- **Correctness** — `SOUND` (strict; would fail on regression), `NARROW` (covers a subset of the claim), `WEAK` (tautological / name-vs-check mismatch / non-empty-as-success), `GREEN-STICKER` (warn-then-pass / unconditional log_pass on the failure path), `TAUTOLOGY` (cannot fail by construction).
- **Tooling** — `CLI` (uses `aether ...` or a `lib` helper that wraps it), `curl-direct` (raw HTTP), `docker` (docker exec/inspect), `log-grep` (container log inspection), `shell` (pure shell ops), `mixed` (multiple).
- **Severity** — `RC1-BLOCK` (must fix before RC1), `HIGH` (RC2 blocker), `MEDIUM` (RC2 follow-up), `LOW` (cosmetic / redundant), `—` (SOUND, no action).

Detailed evidence for any function lives in the corresponding partial under `integration-test-audit-2026-05-21-partials/`.

## §1.1 lib/ shared helpers

Audit scope: 124 functions across `cluster.sh` (89), `common.sh` (46), `topology.sh` (8), `load.sh` (6), `json.sh` (6), `suite.sh` (5), `generation.sh` (6). Full detail: `partials/lib-audit.md`.

**Headline:** No RC1-BLOCK findings in lib helpers. The 88638126 audit's high-leverage green stickers are all FIXED (`pick_non_leader` no longer falls back to `node-1..5`; `app_route_wired` is now body-discriminated; `_api_call` surfaces error bodies on non-2xx; `observe_quorum_window` is fail-closed by default; `is_cluster_healthy` is pinned to exact `"healthy"`).

**Single structural defect:** `drain_node` / `activate_node` defined **twice** in `cluster.sh` (L1523 and L1545; second definition wins). Two different API contracts collide; the dead first definition should be deleted.

**Residual NARROW findings (10, all RC2):**

| Function | File:line | Issue |
|---|---|---|
| `api_put` / `api_delete` | `common.sh:203,232` | Don't call `_resolve_live_endpoint` — asymmetric with `api_get`/`api_post`; PUT/DELETE fails if pinned endpoint is dead. |
| `deploy_blueprint` | `cluster.sh:1022` | `2>/dev/null` strips CLI failure reason before falling back to `api_post`. |
| `deploy_blueprint_file` | `cluster.sh:1062` | Raw `curl -sfk` drops HTTP error body — should route through `_api_call`. |
| `cluster_active_core_count` | `cluster.sh:119` | `api_get \|\| true` then `[ -z "$topology" ] && echo 0` — silently returns 0 when API is unreachable. |
| `slices_total_instances` / `slices_active_instances` | `cluster.sh:737,928` | `echo "${count:-0}"` conflates parse error with "0 instances". |
| `assert_gt` / `assert_ge` | `common.sh:368,378` | `2>/dev/null` on bash arithmetic masks "empty `$actual`" as "below threshold". |
| `task_group_status` | `cluster.sh:2017` | Falls back to `UNASSIGNED` on CLI error — conflated with real state (mitigated by `wait_for_task_assigned`). |
| `cluster_member_count` | `cluster.sh:13` | `max(members, desiredSize)` biases UP — misleading during scale-down. Documented; `cluster_node_count_quiesced` exists for callers who care. |
| `wait_for_node_count_on` | `cluster.sh:2233` | Still reads `coreCount` from `/api/cluster/topology` (the filtered ON_DUTY+HEALTHY signal the generation-snapshot fix retired in `cluster_member_count`). |
| `json_value` / `json_array_length` | `json.sh:18,58` | Substring-first-match / awk `},{` split. Documented with explicit WARNING block directing ambiguous callers to `aether_field`. |

## §1.2 Suite 00-smoke

| Function | File:line | Correctness | Tooling | Severity |
|---|---|---|---|---|
| `test_nodes_formed` | `test-cluster-formation.sh:9` | SOUND | CLI | — |
| `test_leader_elected` | `:24` | SOUND | CLI | — |
| `test_quorum_established` | `:36` | SOUND (redundant) | CLI | LOW |
| `test_liveness_probe` | `:48` | WEAK | curl-direct | LOW |
| `test_all_nodes_visible` | `:52` | SOUND (redundant) | CLI | LOW |
| `test_status_endpoint` | `:64` | WEAK (non-empty-as-success) | CLI | MEDIUM |
| `test_events_available` | `:73` | WEAK (non-empty-as-success) | CLI | MEDIUM |
| `test_push_artifacts` | `test-slice-deployment.sh:12` | SOUND | CLI | — |
| `test_deploy_blueprint` | `:17` | WEAK (non-empty-as-success) | CLI | MEDIUM |
| `test_slices_provisioned` | `:23` | SOUND | CLI | — |
| `test_blueprint_listed` | `:30` | WEAK (substring) | CLI | LOW |
| `test_app_endpoint_reachable` | `:36` | PARTIAL | curl-direct | LOW |
| `test_app_request_succeeds` | `:51` | SOUND | curl-direct | — |

**Notable:** Three duplicate uses of `cluster_member_count == NODE_COUNT` predicate. No warn-then-pass demotions in this suite.

## §1.3 Suite 01-stability

| Function | File:line | Correctness | Tooling | Severity |
|---|---|---|---|---|
| `test_cluster_baseline` | `test-soak-4h.sh:45` | SOUND | CLI | — |
| `test_deploy_app` | `:52` | SOUND | CLI | — |
| `test_app_reachable` | `:59` | SOUND | curl-direct | — |
| `test_collect_pre_stats` | `:76` | WEAK (diagnostic) | curl-direct | LOW |
| `test_soak_load` | `:82` | PARTIAL (3xx-as-success) | curl-direct | LOW |
| `test_collect_post_stats` | `:103` | WEAK (no leak diff) | curl-direct | MEDIUM |
| `test_no_node_drift` | `:110` | PARTIAL (floor-only) | CLI | LOW |
| `test_cluster_still_healthy` | `:116` | SOUND | CLI | — |
| `test_no_leader_change` | `:120` | WEAK (name/check mismatch) | CLI | MEDIUM |
| `test_stream_exists` | `test-streaming-soak.sh:17` | WEAK (empty list passes) | CLI | MEDIUM |
| `test_sustained_publish` | `:27` | SOUND | curl-direct | — |
| `test_cluster_stable_after_stream` | `:64` | SOUND (NODE_COUNT bug) | CLI | LOW |
| `test_health_after_stream` | `:70` | SOUND | CLI | — |

**Notable:** `test_collect_post_stats` files header promises "leak detection" with no diff/threshold assertion — only operator inspection of `/tmp/soak_stats.txt` would catch a leak. `test_no_leader_change` (and several siblings in 02-chaos) check existence, not stability.

## §1.4 Suite 02-chaos

| Function | File:line | Correctness | Tooling | Severity |
|---|---|---|---|---|
| `test_initial_state` (×5 files) | `test-kill-*.sh:11` | PARTIAL (phase-warn demotion) | CLI | LOW |
| `test_kill_non_leader` | `test-kill-node.sh:21` | SOUND | mixed | — |
| `test_leader_unchanged` | `:48` | WEAK (name/check mismatch) | CLI | MEDIUM |
| `test_health_with_4_nodes` (×3 files) | `:54` | SOUND | CLI | — |
| `test_auto_heal` (×4 files) | various | SOUND | CLI | — |
| `test_kill_leader_and_reelect` | `test-kill-leader.sh:21` | SOUND (fail-closed) | mixed | — |
| `test_cluster_has_quorum` | `:63` | SOUND | CLI | — |
| `test_kill_two_nodes` | `test-kill-multiple.sh:21` | PARTIAL (quiesce-warn demotion) | mixed | LOW |
| `test_quorum_maintained` | `:66` | SOUND | CLI | — |
| `test_leader_still_active` | `:72` | WEAK (name/check mismatch) | CLI | LOW |
| `test_kill_during_load` | `test-kill-under-load.sh:29` | SOUND | mixed | LOW |
| `test_cluster_survives` | `:77` | SOUND | CLI | — |
| `test_prime_replacement_via_kill` | `test-joining-window-kill.sh:301` | SOUND | mixed | — |
| `test_catch_replacement_in_joining_window` | `:316` | SOUND (widened) | mixed | LOW |
| `test_decommission_within_budget` | `:368` | SOUND | curl-direct | — |
| `test_transport_unreachable_event_logged` | `:394` | PARTIAL (acceptance widening) | log-grep | MEDIUM |
| `test_pick_non_leader_excludes_decommissioned` | `:411` | PARTIAL (skip-via-warn) | CLI | LOW |
| `test_pick_victims_and_kill_three_simultaneously` | `test-self-drain-quorum-loss.sh:258` | SOUND | mixed | — |
| `test_survivors_self_drain_and_exit` | `:316` | SOUND | docker | — |
| `test_survivor_exit_codes_are_two` | `:368` | SOUND | docker | — |
| `test_drain_trigger_log_signature_present` | `:386` | WARN-THEN-PASS | CLI | LOW |
| `test_no_kv_writes_after_drain_trigger` | `:422` | WARN-ONLY (cannot fail) | log-grep | MEDIUM |
| `test_cluster_recovers_to_five_on_duty` | `:449` | SOUND | mixed | — |

**Notable:** The `test_initial_state::wait_for_phase NORMAL || log_warn` demotion is replicated across all 5 chaos files. Two acceptance-widening cases in `test-joining-window-kill.sh` (`swim-faulty` accepted alongside `transport-failure` — defeats S01 path-isolation premise). The "negative assertion as warning" pattern in `test_no_kv_writes_after_drain_trigger` means a real KV-write leak post-drain would be downgraded to warning, not failure — the compile-time `noConsensusOrKvImports` test is the real guard.

## §1.5 Suite 03-scaling

| Function | File:line | Correctness | Tooling | Severity |
|---|---|---|---|---|
| `test_seed_config` (×3) | various | SOUND | CLI | — |
| `test_initial_state` | `test-01-quorum-safety.sh:16` | WEAK (floor 3 not 5) | CLI | LOW |
| `test_reject_scale_to_1` | `:63` | PARTIAL (5xx accepted) | curl-direct | MEDIUM |
| `test_reject_scale_to_2` | `:74` | PARTIAL | curl-direct | MEDIUM |
| `test_reject_scale_above_max` | `:85` | PARTIAL | curl-direct | MEDIUM |
| `test_cluster_unchanged` | `:96` | WEAK (name/check mismatch) | CLI | LOW |
| `test_baseline_5_nodes` | `test-02-scale-up.sh:16` | SOUND | CLI | — |
| `test_scale_up_to_7` | `:23` | SOUND | CLI | — |
| `test_7_nodes_healthy` | `:34` | SOUND | CLI | — |
| `test_restore_to_5` | `:38` | SOUND | CLI | — |
| `test_scale_up_to_7` (×2) | `test-03-scale-down.sh:27` | SOUND | CLI | — |
| `test_scale_down_under_load` | `:39` | SOUND | mixed | LOW |
| `test_5_nodes_healthy` | `:64` | SOUND | CLI | — |
| **`test_no_data_loss`** | `:71` | **WEAK (egregious)** | CLI | **HIGH** |

**Notable:** **`test_no_data_loss`** — name promises a data-loss guarantee; actual assertion is `cluster_events != ""`. The name is grossly misleading and the assertion is tautological. The rejection trio (`test_reject_scale_to_*`) accepts ANY `>= 400` (including 5xx server crashes) as "rejection".

## §1.6 Suite 04-streaming

| Function | File:line | Correctness | Tooling | Severity |
|---|---|---|---|---|
| `test_cluster_ready` (×4 files) | various | GREEN-STICKER (warn-demoted task-active) | CLI | RC2 |
| `test_create_stream` | `test-stream-replication.sh:18` | NARROW (substring) | curl-direct | RC2 |
| `test_publish_events_for_replication` | `:25` | SOUND | CLI | — |
| `test_stream_visible_on_governor` | `:36` | TAUTOLOGY | CLI | RC2 |
| **`test_read_events_from_partition`** | `:42` | **NARROW** | curl-direct | **RC1-BLOCK** |
| `test_read_from_non_governor_node` | `:55` | SOUND | mixed | RC2 |
| `test_stream_in_list_after_replication` | `:108` | SOUND/NARROW | CLI | — |
| **`test_publish_and_verify_count`** | `test-stream-consumer.sh:17` | **GREEN-STICKER+LOG-PARSE** | mixed | **RC1-BLOCK** |
| `test_stream_metadata` | `:46` | TAUTOLOGY | mixed | RC2 |
| `test_multiple_streams_isolation` | `:59` | NARROW (mis-named) | CLI | RC2 |
| `test_publish_single_event` | `test-stream-publish.sh:17` | TAUTOLOGY | CLI | RC2 |
| `test_publish_batch` | `:24` | SOUND | CLI | — |
| `test_stream_info` | `:38` | TAUTOLOGY | CLI | RC2 |
| `test_stream_appears_in_list` | `:44` | SOUND | CLI | — |
| `test_sustained_stream_publish` | `test-stream-under-load.sh:28` | SOUND | curl-direct | — |
| `test_stream_info_after_load` | `:65` | TAUTOLOGY | CLI | RC2 |
| `test_cluster_stable` | `:71` | SOUND | CLI | — |
| `test_concurrent_publish_and_query` | `:78` | NARROW (sequential not concurrent) | CLI | RC2 |

**Notable RC1-blockers:**
- **`test_read_events_from_partition`**: `assert_contains "$result" "events"` matches `{"events":[]}` — the publish→read invariant cannot fail when partition returns empty.
- **`test_publish_and_verify_count`**: `stream_publish ... > /dev/null 2>&1` silences publish failures; only the count read needs to succeed. Broken publish never trips the count.

## §1.7 Suite 05-security

| Function | File:line | Correctness | Tooling | Severity |
|---|---|---|---|---|
| **`test_tls_active`** | `test-cert-rotation.sh:21` | **TAUTOLOGY** | CLI | **RC1-BLOCK** |
| **`test_rotation_under_load`** | `:28` | **GREEN-STICKER** | mixed | **RC1-BLOCK** |
| `test_cluster_healthy_after_rotation` | `:76` | SOUND | CLI | — |
| `test_all_nodes_present` | `:81` | SOUND | CLI | — |
| **`test_admin_identity_in_response`** | `test-principal-injection.sh:14` | **TAUTOLOGY** | curl-direct | **RC1-BLOCK** |
| **`test_different_keys_different_identity`** | `:21` | **TAUTOLOGY** | curl-direct | **RC1-BLOCK** |
| **`test_app_endpoint_principal`** | `:36` | **GREEN-STICKER** | curl-direct | **RC1-BLOCK** |
| **`test_unauthenticated_response_format`** | `:54` | **GREEN-STICKER** | curl-direct | **RC1-BLOCK** |
| `test_health_public_no_auth` | `test-route-security.sh:9` | SOUND | mixed | — |
| `test_status_requires_auth` | `:15` | SOUND | curl-direct | — |
| `test_status_with_auth` | `:22` | SOUND | mixed | — |
| `test_status_invalid_key` | `:27` | SOUND | curl-direct | — |
| `test_viewer_can_read` | `:34` | SOUND | mixed | — |
| `test_viewer_cannot_mutate` | `:45` | SOUND | curl-direct | — |
| `test_admin_can_deploy` | `:57` | NARROW | curl-direct | RC2 |
| `test_operator_can_scale` | `:73` | NARROW | curl-direct | RC2 |

**6 RC1-blockers in this suite.** `test-route-security.sh` (RBAC enforcement) is the only file in this suite that actually tests its claim. **The entire `test-principal-injection.sh` file is theatrical** — none of its 4 functions actually verify principal/identity. `test-cert-rotation.sh::test_rotation_under_load` admits its own vacuousness inline ("if TLS is not configured, skip with explanatory log_pass") and drives load against `/health/live` (auth-bypassed and cert-rotation-irrelevant).

## §1.8 Suite 06-deployment

| Function | File:line | Correctness | Tooling | Severity |
|---|---|---|---|---|
| `test_cluster_ready` (×4) | various | GREEN-STICKER (warn-demoted task-active) | CLI | RC2 |
| `test_immediate_deploy` | `test-deploy-immediate.sh:21` | TAUTOLOGY | CLI | RC2 |
| `test_cluster_healthy_after_deploy` | `:28` | SOUND | mixed | — |
| `test_slices_active` | `:34` | SOUND | CLI | — |
| `test_rolling_start` | `test-deploy-rolling.sh:18` | NARROW (quiesce warns) | CLI | RC2 |
| **`test_rolling_promote`** | `:31` | **GREEN-STICKER** | CLI | **RC1-BLOCK** |
| `test_rolling_complete` | `:43` | SOUND | CLI | — |
| `test_canary_start` | `test-deploy-canary.sh:23` | NARROW | CLI | RC2 |
| `test_canary_list` | `:40` | NARROW (substring) | CLI | RC2 |
| **`test_canary_promote`** | `:46` | **GREEN-STICKER** | CLI | **RC1-BLOCK** |
| `test_canary_complete` | `:55` | NARROW | CLI | RC2 |
| `test_blue_green_start` | `test-deploy-blue-green.sh:18` | NARROW | CLI | RC2 |
| **`test_blue_green_promote`** | `:32` | **GREEN-STICKER** | CLI | **RC1-BLOCK** |
| **`test_blue_green_rollback`** | `:44` | **DEAD CODE (defined, never invoked)** | CLI | **RC1-BLOCK** |
| `test_blue_green_complete` | `:56` | SOUND | CLI | — |
| `test_schema_status` | `test-schema-migration.sh:34` | TAUTOLOGY | CLI | RC2 |
| `test_schema_status_all` | `:49` | NARROW (shape-only) | CLI | RC2 |
| `test_trigger_migration` | `:65` | SOUND | mixed | — |
| `test_schema_retry` | `:96` | SOUND | mixed | — |

**4 RC1-blockers in this suite.** The three deployment-strategy `*_promote` functions all share the same defect: `deploy_status` output is logged via `log_info` and never asserted; the only gate is `deployment list contains "deploymentId"`. **Critically**, `test_blue_green_rollback` is defined (L44-54) but **never invoked by `run_test`** — the rollback path is dead code, despite being part of the blue-green contract.

## §1.9 Suite 07-cluster-mgmt

| Function | File:line | Correctness | Tooling | Severity |
|---|---|---|---|---|
| `test_skip_if_running` | `test-bootstrap.sh:11` | SOUND | mixed | — |
| `test_config_exists` | `:22` | SOUND | shell | — |
| `test_bootstrap_cluster` | `:31` | GREEN-STICKER (no-CLI path) | CLI/shell | RC2 |
| `test_cluster_forms` | `:42` | SOUND | CLI | — |
| `test_expected_node_count` | `:47` | SOUND | CLI | — |
| `test_leader_elected` | `:54` | NARROW | CLI | RC2 |
| `test_health_probes` | `:61` | SOUND | mixed | — |
| `test_management_api_accessible` | `:66` | TAUTOLOGY | curl-direct | RC2 |
| `test_destroy_guard` | `test-destroy.sh:9` | SOUND | shell | — |
| `test_cluster_exists` | `:18` | SOUND | CLI | — |
| `test_destroy_cluster` | `:25` | SOUND | mixed | — |
| `test_cluster_gone` | `:53` | SOUND | curl-direct | — |
| `test_no_containers_running` | `:65` | SOUND | docker | — |
| `test_data_cleaned` | `:76` | GREEN-STICKER (data-leftover branch) | mixed | RC2 |
| `test_get_current_config` | `test-apply.sh:14` | TAUTOLOGY | CLI | RC2 |
| `test_apply_config_override` | `:20` | NARROW | CLI | RC2 |
| `test_config_converges` | `:34` | NARROW | mixed | RC2 |
| **`test_config_visible_on_all_nodes`** | `:40` | **TAUTOLOGY** | CLI | **RC1-BLOCK** |
| `test_overrides_endpoint` | `:50` | GREEN-STICKER | curl-direct | RC2 |
| `test_cluster_unchanged` | `:61` | SOUND | CLI | — |
| `test_export_config` | `test-export.sh:16` | TAUTOLOGY | CLI | RC2 |
| `test_export_valid_json` | `:25` | NARROW (regex-shape) | shell | RC2 |
| `test_reapply_exported_config` | `:35` | NARROW (mis-named) | CLI | RC2 |
| **`test_config_identical_after_reapply`** | `:54` | **GREEN-STICKER** | CLI | **RC1-BLOCK** |
| `test_cluster_healthy_after_roundtrip` | `:69` | SOUND | CLI | — |

**2 RC1-blockers in this suite.** `test_config_visible_on_all_nodes` calls `config_export` twice on the same `$CLUSTER_ENDPOINT` — "all nodes" is never probed. `test_config_identical_after_reapply` computes byte counts of original and reapplied configs, **logs the diff but never asserts equality**, then unconditionally `log_pass`s.

## §1.10 Suite 08-resources

| Function | File:line | Correctness | Tooling | Severity |
|---|---|---|---|---|
| `test_mgmt_health_endpoint` | `test-http-client.sh:15` | SOUND | lib | — |
| `test_mgmt_status_json` | `:19` | SOUND | CLI | — |
| `test_mgmt_nodes_json` | `:29` | AMBER (stderr swallow) | CLI | LOW |
| `test_mgmt_content_type` | `:38` | SOUND | curl-direct | — |
| `test_mgmt_invalid_path` | `:44` | SOUND (404 exact — prior fix) | lib | — |
| `test_mgmt_concurrent_requests` | `:55` | SOUND | lib | — |
| `test_stream_exists_or_created` | `test-pub-sub.sh:17` | GREEN-STICKER (else branch) | CLI | LOW |
| `test_publish_events` | `:32` | SOUND | CLI | LOW |
| `test_stream_info_after_publish` | `:46` | AMBER (non-empty-as-success) | CLI | MEDIUM |
| **`test_subscriber_receives_events`** | `:52` | **GREEN-STICKER (double warn-then-pass)** | CLI | **RC1-BLOCK** |
| `test_competing_consumers_multi_instance` | `:64` | GREEN-STICKER (single-instance branch) | CLI | MEDIUM |
| `test_scheduled_tasks_endpoint` | `test-scheduled-tasks.sh:14` | AMBER | CLI | LOW |
| **`test_task_last_execution_advances`** | `:20` | **GREEN-STICKER (3 of 4 branches pass)** | CLI | **RC1-BLOCK** |
| `test_pause_task` | `:59` | GREEN-STICKER | CLI | HIGH |
| `test_resume_task` | `:84` | GREEN-STICKER | CLI | HIGH |
| `test_deploy_sql_app` | `test-sql-connector.sh:18` | AMBER | mixed | LOW |
| `test_put_kv_pair` | `:38` | AMBER (3xx-as-success) | mixed | LOW |
| `test_get_kv_pair` | `:58` | SOUND | mixed | — |
| `test_connection_pooling_rapid_requests` | `:82` | AMBER (50% threshold) | mixed | MEDIUM |
| `test_deploy_notification_hub` | `test-streaming-resources.sh:18` | GREEN-STICKER (empty test) | mixed | MEDIUM |
| `test_stream_publisher_provisioned` | `:23` | GREEN-STICKER (else branch) | CLI | MEDIUM |
| `test_publish_notifications` | `:37` | SOUND (prior `\|\| true` REMEDIATED) | CLI | — |
| `test_subscriber_receives_notifications` | `:51` | AMBER | CLI | LOW |
| `test_analytics_counts_increment` | `:58` | AMBER (no count comparison) | CLI | MEDIUM |

**2 RC1-blockers in this suite, both in `test-scheduled-tasks.sh`:**
- `test_subscriber_receives_events` has DOUBLE warn-then-pass — the test name claims subscriber functionality, but no consumer is ever attached and no event count is verified.
- `test_task_last_execution_advances`: 3 of 4 outcomes are warn-then-pass demotions; the only failure path is unreachable.

`test_pause_task` and `test_resume_task` are HIGH severity — pause/resume is a real product capability that this suite cannot detect as broken.

## §1.11 Suite 09-artifacts

| Function | File:line | Correctness | Tooling | Severity |
|---|---|---|---|---|
| `test_generate_artifact` | `test-artifact-push-resolve.sh:30` | SOUND | shell | — |
| `test_push_artifact` | `:37` | SOUND | mixed | — |
| `test_resolve_artifact` | `:53` | SOUND | mixed | — |
| `test_checksum_matches` | `:68` | SOUND (SHA-256 strict) | mixed | — |
| `test_cluster_healthy_after` | `:75` | SOUND | CLI | — |
| `test_identify_second_node` | `test-artifact-replication.sh:34` | AMBER (fallback to same endpoint) | mixed | LOW |
| `test_push_to_primary` | `:72` | SOUND | mixed | — |
| `test_wait_for_replication` | `:89` | GREEN-STICKER (decorative) | shell | LOW |
| `test_resolve_from_second_node` | `:96` | SOUND | mixed | — |
| `test_integrity_across_nodes` | `:109` | SOUND (SHA-256 strict) | mixed | — |
| `test_64kb_boundary`, `test_128kb`, `test_1mb`, `test_5mb` | `test-large-artifact.sh:67-79` | SOUND | mixed | — |
| `test_cluster_healthy_after_large_artifacts` | `:88` | SOUND | CLI | — |

**Strongest suite in the audit.** Cryptographic SHA-256 equality makes results non-fakeable. Prior `test-large-artifact.sh:43` inverted-check finding REMEDIATED. The one GREEN-STICKER (`test_wait_for_replication`) is decorative — the next test is strict.

## §1.12 Suite 10-database

| Function | File:line | Correctness | Tooling | Severity |
|---|---|---|---|---|
| (all 18 functions across baseline/retry/versioned files) | | SOUND | mixed | — |

**All prior green stickers REMEDIATED.** Datasource-discovery pattern (`wait_for "tracked datasource" + per-datasource addressing`) is a model for other rewrites. `test_migrations_applied` now asserts `currentVersion >= 900` strictly (the V900 migration is fixture-required by `test-persistence`).

## §1.13 Suite 11-observability

| Function | File:line | Correctness | Tooling | Severity |
|---|---|---|---|---|
| `test_transport_metrics_endpoint` | `test-transport-metrics.sh:14` | AMBER | curl-direct | LOW |
| `test_active_connections_metric`, `test_messages_sent_metric`, `test_messages_received_metric`, `test_transport_metrics_non_zero` | `:22,33,44,58` | SOUND (all prior REMEDIATED) | curl-direct | — |
| `test_prometheus_endpoint_responds`, `test_valid_prometheus_format`, `test_http_request_metrics`, `test_jvm_metrics`, `test_cluster_metrics`, `test_no_empty_metric_values` | `test-prometheus-metrics.sh:14-79` | SOUND (all prior REMEDIATED) | curl-direct | — |
| `test_thresholds_endpoint`, `test_set_alert_threshold`, `test_trigger_alert_condition`, `test_check_alerts_fired`, `test_alerts_have_fields` | `test-alerts.sh:25-104` | SOUND (all prior REMEDIATED via `/api/alerts/inject`) | curl-direct | — |
| `test_certificate_endpoint`, `test_expires_at_field`, `test_seconds_until_expiry`, `test_renewal_status_field`, `test_certificate_not_expired` | `test-certificate-status.sh:29-121` | SOUND (NOT_CONFIGURED branch) | curl-direct | — |
| `test_inject_events_round_robin` | `test-events-cluster-ordering.sh:28` | SOUND | curl-direct | — |
| `test_wait_for_replication` | `:53` | GREEN-STICKER (decorative) | curl-direct | LOW |
| `test_all_nodes_agree_on_order` | `:76` | SOUND | curl-direct | — |
| `test_generate_traceable_requests`, `test_traces_endpoint`, `test_traces_contain_request_id`, `test_traces_contain_duration`, `test_traces_contain_depth` | `test-invocation-traces.sh:51-115` | SOUND (all prior REMEDIATED via `/api/traces/inject`) | curl-direct | — |

**Heavy remediation since 88638126.** The institutionalised warn-then-pass subsystem flagged by the prior audit is GONE. Architectural enablers: `/api/alerts/inject` and `/api/traces/inject` test-only endpoints; NOT_CONFIGURED branching for cert tests; sophisticated grep-rc handling (distinguishes "no matches" from "grep error"). Tooling note: this suite is heavily `curl-direct` — see §3 for whether CLI gaps justify it.

## §1.14 Suite 12-network

| Function | File:line | Correctness | Tooling | Severity |
|---|---|---|---|---|
| `test_cluster_formed_with_encryption` | `test-gossip-encryption.sh:14` | SOUND (mis-named) | mixed | LOW |
| `test_gossip_encryption_active_via_config` | `:27` | SOUND (prior REMEDIATED) | curl-direct | — |
| `test_gossip_encryption_via_transport` | `:50` | SOUND but lax (50% failure ceiling) | curl-direct | MEDIUM |
| `test_nodes_communicating_encrypted` | `:79` | WEAK (indirect) | lib | LOW |
| `test_health_probes_over_encrypted_transport` | `:90` | SOUND | mixed | LOW |
| `test_all_nodes_connected` | `test-quic-connectivity.sh:25` | SOUND (one node sampled) | curl-direct | MEDIUM |
| `test_kill_node_and_detect_drop` | `:49` | SOUND | mixed | — |
| `test_connections_recovered` | `:87` | SOUND | mixed | — |
| **`test_swim_detection_time`** | `test-swim-detection.sh:32` | **GREEN-STICKER (15s budget non-binding)** | mixed | **HIGH** |
| `test_recovery_after_detection` | `:63` | SOUND | mixed | — |
| `test_initial_state` | `test-partition-quorum-gate.sh:169` | SOUND | mixed | — |
| `test_pick_minority` | `:186` | SOUND | CLI | — |
| `test_partition_does_not_decommission_within_window` | `:214` | SOUND | mixed | — |
| `test_cluster_heals_to_5_onduty` | `:268` | SOUND | mixed | — |

**Notable:** `test_swim_detection_time` — the entire purpose of the test is to fail when SWIM detection breaches a 15s budget; current code demotes to `log_warn → log_pass` for elapsed in [16s, 60s]. A real regression to 45s detection silently passes. `test-partition-quorum-gate.sh` is exemplary — explicit FSM cells named in failure messages, authoritative endpoint, EXIT trap, idempotent cleanup.

## §1.15 Suite 13-edge-cases

| Function | File:line | Correctness | Tooling | Severity |
|---|---|---|---|---|
| `test_initial_slice_count` | `test-concurrent-deploys.sh:33` | WEAK (no count assertion) | CLI | LOW |
| `test_concurrent_deploy` | `:39` | SOUND (prior REMEDIATED) | curl-direct | — |
| **`test_both_blueprints_visible`** | `:105` | **GREEN-STICKER (empty payload pass)** | mixed | **HIGH** |
| `test_slices_active_after_concurrent_deploy` | `:119` | SOUND | CLI | — |
| `test_artifact_isolation` | `:126` | SOUND | shell | — |
| `test_drain_first_node_allowed` | `test-disruption-budget.sh:42` | SOUND | curl-direct | — |
| `test_drain_second_node_allowed` | `:81` | WEAK (2xx OR 409 dual acceptance) | curl-direct | MEDIUM |
| `test_drain_beyond_budget_rejected` | `:102` | SOUND (prior REMEDIATED) | curl-direct | — |
| `test_quorum_preserved` | `:128` | SOUND | CLI | — |
| `test_reactivate_nodes` | `:132` | SOUND (prior REMEDIATED) | mixed | — |
| `test_slices_deployed` | `test-stale-route-cleanup.sh:24` | SOUND | CLI | — |
| `test_app_routes_reachable` | `:31` | SOUND (prior REMEDIATED) | mixed | — |
| `test_kill_node_hosting_routes` | `:48` | WEAK (quiesce-warn unconditional pass) | mixed | MEDIUM |
| `test_no_502_504_after_cleanup` | `:83` | SOUND (prior REMEDIATED) | curl-direct | — |
| `test_kv_store_routes_clean` | `:102` | WEAK (non-empty-as-success) | CLI | LOW |
| `test_recovery_complete` | `:109` | SOUND | CLI | — |

## §1.16 Suite 14-storage

| Function | File:line | Correctness | Tooling | Severity |
|---|---|---|---|---|
| `test_cli_storage_list` | `test-storage-cli.sh:15` | GREEN-STICKER (silent skip) | CLI | HIGH |
| `test_cli_storage_status` | `:26` | GREEN-STICKER (silent skip) | CLI | HIGH |
| `test_cli_storage_list_json` | `:54` | WEAK (leading-char regex) + GREEN-STICKER | CLI | MEDIUM |
| `test_storage_list` | `test-storage-management.sh:15` | WEAK (`{}` accepted) | curl-direct | MEDIUM |
| **`test_storage_list_contains_artifacts`** | `:32` | **GREEN-STICKER (warn → return 0)** | curl-direct | **HIGH** |
| `test_storage_instance_detail` | `:53` | SOUND | curl-direct | — |
| **`test_storage_snapshot`** | `:78` | **GREEN-STICKER (empty body → return 0)** | curl-direct | **HIGH** |
| `test_cluster_storage_view` | `:104` | WEAK (substring) | curl-direct | LOW |
| `test_cluster_storage_detail` | `:115` | SOUND | curl-direct | — |

**Weakest suite in the audit.** 4 of 9 tests silently pass on absent functionality (skip_test on CLI failure; warn-then-return-0 on missing "artifacts" instance and on snapshot empty response). The "artifacts" storage instance is mandatory for the artifact-repo service used by every deploy — its absence is a real product regression that this suite does not catch.

## §1.17 Suite 15-delegation

| Function | File:line | Correctness | Tooling | Severity |
|---|---|---|---|---|
| `test_tasks_api_returns_data` | `test-01-task-assignments.sh:25` | SOUND | CLI | — |
| `test_all_groups_assigned` | `:35` | SOUND | CLI | — |
| `test_all_groups_active` | `:44` | SOUND | CLI | — |
| **`test_tasks_distributed`** | `:60` | **GREEN-STICKER (`>=1` not `>=2`)** | shell | **HIGH** |
| `test_assignments_point_to_valid_nodes` | `:73` | SOUND (narrow) | shell | LOW |
| `test_deployment_group_functional` | `:89` | SOUND (prior REMEDIATED) | CLI | — |
| `test_metrics_group_functional` | `:112` | WEAK (assignment-exists, not collecting) | CLI | MEDIUM |
| `test_prerequisite` | `test-02-reassignment.sh:31` | SOUND | CLI | — |
| `test_operator_reassign` | `:42` | SOUND | CLI | — |
| `test_reassignment_status_active` | `:73` | SOUND | CLI | — |
| `test_other_groups_unaffected` | `:82` | SOUND | CLI | — |
| `test_node_failure_reassignment` | `:94` | SOUND (prior REMEDIATED) | mixed | — |

**Notable:** `test_tasks_distributed` — name + comment say "across ≥2 nodes / not all on leader"; assertion is `assert_ge unique_nodes 1`. All-tasks-on-leader passes the test. The original distribution claim is structurally untestable as written.

---

# §2 Correctness review

## §2.1 Cross-cuts

### Tautology census (non-empty-as-success / "endpoint responds")

26 instances across suites — the dominant green-sticker pattern after the 88638126 campaign closed `< 500`. Concentrated in 04-streaming (`test_publish_single_event`, `test_stream_visible_on_governor`, `test_stream_info`, `test_stream_info_after_load`), 06-deployment (`test_immediate_deploy`, `test_schema_status`), 07-cluster-mgmt (`test_get_current_config`, `test_export_config`, `test_management_api_accessible`). The pattern survives because `assert_ne "$result" ""` reads like a real check but admits any error JSON, empty array, or status 200 with malformed body.

**Suggested generic fix:** introduce `assert_json_field <response> <jq-path> <expected>` and migrate. Where no expected value is known (smoke tests), `assert_json_shape <response> <key>` to require a specific field be present and non-empty.

### Name/check mismatch census

8 instances. Most consequential:
- `03-scaling/test-03-scale-down.sh::test_no_data_loss` — name promises data-loss check; assertion is `cluster_events != ""`.
- `01-stability/test-soak-4h.sh::test_no_leader_change` — checks "leader exists", not "leader unchanged".
- `02-chaos/test-kill-node.sh::test_leader_unchanged`, `kill-multiple.sh::test_leader_still_active` — same.
- `03-scaling/test-01-quorum-safety.sh::test_cluster_unchanged` — same.
- `15-delegation/test-01-task-assignments.sh::test_tasks_distributed` — name says ≥2 nodes; check is ≥1.
- `04-streaming/test-stream-consumer.sh::test_multiple_streams_isolation` — checks "first stream still exists", not isolation.
- `04-streaming/test-stream-under-load.sh::test_concurrent_publish_and_query` — sequential, not concurrent.

### Warn-then-pass demotion census

Of the 88638126-flagged demotions, **all have been remediated** in 11-observability (`test-transport-metrics`, `test-prometheus-metrics`, `test-alerts`, `test-certificate-status`, `test-invocation-traces`). Residual instances exist:

- `04-streaming` — all four `test_cluster_ready` functions demote task-active readiness to warn (replication then runs against half-ready cluster).
- `02-chaos` — all five `test_initial_state` demote `wait_for_phase NORMAL` to warn.
- `08-resources/test-scheduled-tasks.sh` — 3 of 4 outcomes in `test_task_last_execution_advances` are warn-then-pass; pause/resume tests demote on empty response.
- `12-network/test-swim-detection.sh::test_swim_detection_time` — 15s threshold demoted to warn, then unconditional pass on detection in [16s, 60s].
- `13-edge-cases/test-stale-route-cleanup.sh::test_kill_node_hosting_routes` — quiesce-timeout demoted to warn, then unconditional log_pass.
- `14-storage` — 4 of 9 tests use `log_warn → return 0` pattern.

### Fall-back-to-stale / silent-skip census

Mostly resolved per prior audit. Residual:
- `14-storage/test-storage-cli.sh` — 3 tests use `2>/dev/null || true` to mask CLI failure as `skip_test` (the only suite where this antipattern survives).
- `lib/cluster.sh::cluster_member_count` — `max(members, desiredSize)` still biases UP on scale-down.
- `lib/cluster.sh::wait_for_node_count_on` — still reads `coreCount` (filtered ON_DUTY+HEALTHY signal) instead of the canonical generation count.

## §2.2 RC1-blocker action list

The 18 tests below would not detect the regression they claim to test. Each is named, cited, and given a one-line replacement. Fixing these closes the major gap between "what we say we verify" and "what we actually verify" before RC1.

| # | File | Function | What's wrong | Replacement |
|---|------|----------|--------------|-------------|
| 1 | `04-streaming/test-stream-replication.sh:42` | `test_read_events_from_partition` | `assert_contains "$result" "events"` passes against `{"events":[]}` | Assert `events` array length ≥ N (use `aether_field` / json parser); reject empty array |
| 2 | `04-streaming/test-stream-consumer.sh:17` | `test_publish_and_verify_count` | Publish stderr silenced; broken publish never trips the count | Remove `> /dev/null 2>&1`; track per-publish rc; fail if any publish failed |
| 3 | `05-security/test-cert-rotation.sh:21` | `test_tls_active` | `config_export` non-empty mis-labelled as "TLS active" | Assert `tlsEnabled=true` field in `/api/certificates` response |
| 4 | `05-security/test-cert-rotation.sh:28` | `test_rotation_under_load` | Vacuous pass when TLS not configured (self-admitted); load drives `/health/live` (no cert path) | Skip cleanly if NOT_CONFIGURED; drive load at an authenticated route through the TLS handshake |
| 5 | `05-security/test-principal-injection.sh:14` | `test_admin_identity_in_response` | non-empty body, no principal field check | Server-side echo of caller identity in `/api/whoami` (or similar); assert response contains the API key's identity |
| 6 | `05-security/test-principal-injection.sh:21` | `test_different_keys_different_identity` | Never compares the two responses | Compare admin and viewer response bodies; assert they differ on the identity field |
| 7 | `05-security/test-principal-injection.sh:36` | `test_app_endpoint_principal` | "Any positive HTTP code" passes | Send unauth request to a known auth-required path; assert 401 (not just `status > 0`) |
| 8 | `05-security/test-principal-injection.sh:54` | `test_unauthenticated_response_format` | Warn-then-pass on missing WWW-Authenticate; status code never asserted to be 401 | Assert status == 401 strictly; assert header present strictly |
| 9 | `06-deployment/test-deploy-rolling.sh:31` | `test_rolling_promote` | Promote outcome logged, never asserted | Capture `deploy_status` output; assert status reaches `PROMOTED` (or strategy-appropriate terminal) |
| 10 | `06-deployment/test-deploy-canary.sh:46` | `test_canary_promote` | Same | Same |
| 11 | `06-deployment/test-deploy-blue-green.sh:32` | `test_blue_green_promote` | Same | Same |
| 12 | `06-deployment/test-deploy-blue-green.sh:44` | `test_blue_green_rollback` | Defined but never invoked by `run_test` | Wire `run_test test_blue_green_rollback` into the run list; verify rollback returns to the prior version |
| 13 | `07-cluster-mgmt/test-apply.sh:40` | `test_config_visible_on_all_nodes` | Calls `config_export` twice on same `$CLUSTER_ENDPOINT`; "all nodes" never probed | Iterate per-node management ports; assert each returns the same config |
| 14 | `07-cluster-mgmt/test-export.sh:54` | `test_config_identical_after_reapply` | Logs byte diff, never asserts equality | `assert_eq "$orig" "$exported"` (or canonical-form comparison) |
| 15 | `08-resources/test-pub-sub.sh:52` | `test_subscriber_receives_events` | Double warn-then-pass; no consumer ever attached | Open a consumer offset, publish N events, assert offset advanced by N |
| 16 | `08-resources/test-scheduled-tasks.sh:20` | `test_task_last_execution_advances` | 3 of 4 branches are warn-then-pass demotions | Add `/api/scheduled-tasks/inject` test endpoint (per the alerts/traces pattern); assert lastExecutionTime advances strictly |
| 17 | `12-network/test-swim-detection.sh:32` | `test_swim_detection_time` | DETECTION_TIMEOUT (15s) demoted to warn; elapsed in [16s, 60s] silently passes | Either fail strictly on elapsed > DETECTION_TIMEOUT, or remove the budget claim from the test name |
| 18 | `14-storage/test-storage-management.sh:32` | `test_storage_list_contains_artifacts` | Missing "artifacts" instance → log_warn → return 0 | Strict `log_fail` if "artifacts" instance is absent (this is mandatory for the artifact-repo service) |

## §2.3 HIGH severity (RC2)

| # | File | Function | Issue |
|---|------|----------|-------|
| 1 | `03-scaling/test-03-scale-down.sh:71` | `test_no_data_loss` | Tautological — name promises data-loss check, asserts only events endpoint non-empty |
| 2 | `08-resources/test-scheduled-tasks.sh:59,84` | `test_pause_task` / `test_resume_task` | Endpoint-responds demotion on empty result |
| 3 | `13-edge-cases/test-concurrent-deploys.sh:105` | `test_both_blueprints_visible` | Empty slices payload `log_pass`d as "endpoint responds" — subject swap |
| 4 | `14-storage/test-storage-cli.sh:15,26,54` | `test_cli_storage_list` / `_status` / `_list_json` | `2>/dev/null \|\| true` masks CLI failure as silent skip |
| 5 | `14-storage/test-storage-management.sh:78` | `test_storage_snapshot` | Snapshot endpoint empty body → log_warn → return 0 |
| 6 | `15-delegation/test-01-task-assignments.sh:60` | `test_tasks_distributed` | Distribution claim "across ≥2 nodes" actually asserts `>=1` |

## §2.4 MEDIUM severity (RC2) — summary

19 instances; full detail in §1 catalog. Three recurring patterns:
- **Substring grep where exact JSON parse is needed** (deploy strategy `_list` asserts substring "CANARY"/"COMPLETED" on whole response body).
- **3xx-as-success** in `lib/load.sh::start_load` / `_api_call` — counts `200..399` as success. Affects soak + scale-down + kill-under-load error-rate gates.
- **5xx-accepted-as-rejection** in `03-scaling/test-01-quorum-safety.sh` rejection trio (`>= 400` accepts 5xx server crashes as "rejected").

## §2.5 Prior audit (session 88638126, 2026-05-10) — triage summary

Of 45 findings, distribution after 11 days:

| Status | Count | Notes |
|---|---|---|
| DONE | 23 | Fix landed and pattern eliminated |
| PARTIAL | 4 | Fix narrowed but didn't fully eliminate the smell (e.g., post-publish soft probe in `test-concurrent-deploys.sh:112-115`) |
| OPEN | 4 | Pattern still present at cited or equivalent site |
| OPEN/UNCERTAIN | 3 | Line numbers shifted; couldn't pin current site reliably |
| STALE | 0 | None of the cited paths belonged to the deleted LB module |

**The 4 confirmed OPEN findings worth following up:**
1. `lib/cluster.sh::cluster_member_count` — still uses `max(members, desiredSize)`; biases stale on scale-down.
2. `lib/load.sh:35,66,162,201` — `[ status -ge 200 ] && [ status -lt 400 ]` pattern unchanged at all four sites; counts 304s as success.
3. `08-resources/test-pub-sub.sh:24`, `test-streaming-resources.sh:29` — `grep -q "$STREAM_NAME"` substring match still in place.
4. `lib/cluster.sh:1399` `wait_for_node_count_on` — still reads `coreCount` (the filtered ON_DUTY+HEALTHY signal the generation-snapshot fix was meant to retire).

Full triaged table at `partials/prior-audit-88638126-triage.md`.

---

# §3 CLI usage analysis

## §3.1 Tooling distribution

Approximate split across the 200+ test functions (excludes `lib/`):

| Tool | % | Where it dominates |
|---|---|---|
| `CLI` (via `aether_failover`/`aether_json`/`aether_field` and `cluster.sh` wrappers) | ~55% | Cluster mgmt (00-smoke, 02-chaos, 03-scaling, 07-cluster-mgmt, 10-database, 15-delegation) |
| `curl-direct` | ~30% | Status-code probes, app-route checks, observability raw metric reads (11-observability), 14-storage management endpoints, 05-security route checks |
| `docker` | ~8% | Chaos kills, container introspection (02-chaos), teardown (07-cluster-mgmt) |
| `log-grep` | ~2% | 02-chaos `test_transport_unreachable_event_logged` + `test_no_kv_writes_after_drain_trigger` only |
| `shell` / `mixed` | ~5% | Crypto checksum, file existence, multi-tool composition |

`feedback_prefer_aether_cli` is honoured: curl is used where it should be (liveness probes, app-HTTP, raw status codes); docker is used where it should be (kills, inspect); log-grep is rare and documented. The remaining ~30% of `curl-direct` deserves a closer look — the breakdown follows.

## §3.2 Pattern A — CLI exists but test uses raw HTTP

These tests use `curl` / `api_*` / `http_status` for operations that have a perfectly good CLI command. Migrating reduces the surface tied to REST-route stability and harmonises with `feedback_prefer_aether_cli`.

| Test | Currently uses | Existing CLI command | Notes |
|------|----------------|----------------------|-------|
| `00-smoke/test-cluster-formation.sh::test_liveness_probe` | `assert_http_status .../health/live 200` | `aether nodes health` / `aether nodes health <id>` | CLI returns structured JSON; current curl probe is single-host smoke only |
| `00-smoke/test-slice-deployment.sh::test_app_request_succeeds` | `assert_http_status "${APP_ENDPOINT}/api/echo/health" 200` | n/a (app route) | Legitimate curl use — app-HTTP is the only canonical surface |
| `01-stability/test-soak-4h.sh::test_collect_pre/post_stats` | curl per-node `/api/nodes/status`, sed-extract `uptimeSeconds` | `aether status --format json` (per-node `aether status <id>` available) | Could replace the per-port loop with `aether nodes --format json` once |
| `03-scaling/test-01-quorum-safety.sh::test_reject_scale_to_*` (×3) | `direct_scale_status` — raw curl POST `/api/cluster/scale` | `aether cluster scale <source> --core <N>` | CLI is auth-aware and surfaces structured errors. The reject trio's "5xx accepted" green sticker would close itself since CLI returns the structured `error` payload |
| `04-streaming/test-stream-replication.sh::test_create_stream` | `api_post` to `/api/streams` | **CLI GAP — no `aether streams create`** (see §3.3) |
| `04-streaming/test-stream-replication.sh::test_read_events_from_partition` | `api_get /api/streams/read/...` | **CLI GAP — no `aether streams read`** |
| `04-streaming/test-stream-replication.sh::test_read_from_non_governor_node` | raw `curl -sf` against alternate endpoint | **CLI GAP — `-c/--endpoint` doesn't help (per-port direct)**; could expose `aether --endpoint <url> streams status` |
| `04-streaming/test-stream-under-load.sh::test_sustained_stream_publish` | raw curl POST loop | `aether streams publish` (exists) but the load loop wants throughput. **CLI GAP — no `streams publish-batch` / `streams stress`** |
| `05-security/test-route-security.sh` (all) | raw curl with explicit `-H "X-API-Key: ..."` | Legitimate curl use — these tests verify the auth wire protocol, not the operation |
| `05-security/test-cert-rotation.sh::test_rotation_under_load` | raw curl `/health/live` load | `aether nodes health` exists but the goal is load on auth-required surface |
| `06-deployment/*` promote/list/status tests | `aether_failover deploy ...` (CLI) + some `api_get /api/deploy/...` | All CLI commands exist — already CLI-dominant. The RC1-blocker is assertion logic, not tooling |
| `08-resources/test-http-client.sh` (all) | mix of `api_get` (curl) and `aether_field` (CLI) | The 404 fix uses `assert_http_status` strictly — appropriate for status-code contract testing |
| `08-resources/test-streaming-resources.sh::test_publish_notifications` | raw curl with `Content-Type: application/json` | `aether streams publish` (exists), but the test specifically uses binary base64-encoded payloads — CLI also supports this |
| `09-artifacts/*` (push/resolve/replication) | raw curl PUT/GET to `/repository/...` | `aether artifacts deploy <jar>` / `aether artifacts info` (exist). **Mostly avoidable** — could switch to CLI for everything except the byte-stream resolve in `test_resolve_artifact` (which has no CLI surface — see §3.3) |
| `10-database/*` schema tests | `api_get` / `api_post` via cluster.sh helpers | `aether schema status/migrate/retry/baseline/undo` (all exist). The schema helpers (`schema_status`, `schema_migrate`...) currently use `api_get/post` but the CLI is wired — would harmonise to use it |
| `11-observability/test-prometheus-metrics.sh` | raw curl to `/api/metrics/prometheus` | **CLI GAP — no `aether metrics --format prometheus`** |
| `11-observability/test-transport-metrics.sh` | raw curl to `/api/metrics/transport` | **CLI GAP — no `aether metrics transport`** |
| `11-observability/test-events-cluster-ordering.sh` | raw curl per-node `MGMT_PORT+i` to `/api/events` | `aether events --since=...` exists for cluster-wide; **per-node ordering check has no CLI surface** (need to hit each node's `/api/events`, and `aether events` aggregates) |
| `12-network/test-quic-connectivity.sh` | `api_get /api/cluster/topology` + json_value | `aether cluster topology --format json --field ...` (exists) — could harmonise |
| `12-network/test-gossip-encryption.sh` | `api_get /api/metrics/transport` + json_value | Same gap as `11-observability/test-transport-metrics.sh` — **CLI GAP** |
| `12-network/test-partition-quorum-gate.sh::test_partition_does_not_decommission_within_window` | `api_get /api/nodes/lifecycle/{id}` | `aether nodes lifecycle <id>` (exists) — already covered |
| `12-network/test-swim-detection.sh::test_swim_detection_time` | event-poll via topology.sh against `/api/events` | `aether events --since=...` exists but doesn't filter to specific node | Could close with `aether events --node <id>` |
| `13-edge-cases/test-concurrent-deploys.sh::test_concurrent_deploy` | raw curl POST `/api/streams/publish/<name>` x2 in parallel | `aether streams publish` (exists). Test does it raw to drive both concurrently with `&`; CLI launches a JVM per call which would distort the timing |
| `13-edge-cases/test-disruption-budget.sh::test_drain_*` | raw curl POST `/api/nodes/drain/{id}` | `aether nodes drain <id>` (exists) — already covered via lib helper |
| `13-edge-cases/test-stale-route-cleanup.sh::test_no_502_504_after_cleanup` | 10 × `http_status` on `/api/echo/health` | Legitimate curl use — app-HTTP is the canonical surface |
| `14-storage/test-storage-management.sh` (all) | raw curl to `/api/storage/*` and `/api/cluster/storage/*` | `aether storage list/status/snapshot` (exist). **Should be CLI-driven** — the silent-skip green sticker would close itself since CLI surfaces auth and connection errors loudly |

**Summary:** Two clear categories of curl-when-CLI-exists:
1. **Easy migrations** (1-2 line changes): `01-stability` per-node status, `03-scaling` reject trio, `10-database` schema tests, `12-network/test-quic-connectivity`, `13-edge-cases/test-disruption-budget`, **most of `14-storage`**.
2. **Migration would help, but isn't a green-sticker fix**: `09-artifacts` (functionality works; CLI would just be tidier), `06-deployment` (already CLI; gap is assertion logic).

The 14-storage migration is the highest leverage — it would close the silent-skip RC2 HIGH findings as a side effect.

## §3.3 Pattern B — Actual CLI gaps (REST routes without CLI coverage)

REST routes in `ManagementRoute` that have no CLI invocation site. Some of these gaps force tests to use raw curl; others are operator-facing.

### A. Streaming subsystem (5 gaps)

The entire stream lifecycle except publish/list/status is REST-only:

| REST route | CLI command needed |
|---|---|
| `POST /api/streams` (create) | `aether streams create <name>` |
| `DELETE /api/streams/{name}` | `aether streams delete <name>` |
| `GET /api/streams/{name}/{partition}` | `aether streams partition <name> <partition>` |
| `GET /api/streams/read/{name}/{partition}` | `aether streams read <name> <partition> [--since=offset]` |
| `GET /api/streams/consumers/{name}` | `aether streams consumers <name>` |
| `POST/DELETE /api/consumers/...` (join/leave) | `aether streams consumer join/leave <group> <stream>` |
| `GET /api/consumers/.../status` | `aether streams consumer status <group> <stream>` |

This is the gap that forces `04-streaming` to use raw HTTP for the publish→read invariant (RC1-blocker #1). Closing these unblocks a clean CLI-driven streaming test suite.

### B. Metrics variants (5 gaps)

| REST route | CLI command needed |
|---|---|
| `GET /api/metrics/prometheus` | `aether metrics --format prometheus` (or `aether metrics prometheus`) |
| `GET /api/metrics/transport` | `aether metrics transport` |
| `GET /api/metrics/comprehensive` | `aether metrics --comprehensive` |
| `GET /api/metrics/derived` | `aether metrics derived` |
| `GET /api/metrics/history` | `aether metrics history [--since=...]` |

Forces 11-observability (`test-prometheus-metrics`, `test-transport-metrics`) and 12-network (`test-gossip-encryption`, `test-quic-connectivity`) to use raw curl.

### C. Slice introspection (2 gaps)

| REST route | CLI command needed |
|---|---|
| `GET /api/slices/status` | `aether slices status` |
| `GET /api/slices/topology` | `aether slices topology` |

Currently only `aether slices [--state]` (LIST) is wired.

### D. Cluster governors (1 gap)

| REST route | CLI command needed |
|---|---|
| `GET /api/cluster/governors` | `aether cluster governors` |

### E. Foundation model / TTM (2 gaps)

| REST route | CLI command needed |
|---|---|
| `GET /api/ttm/status` | `aether ttm status` |
| `GET /api/ttm/training-data` | `aether ttm training-data` |

### F. Scheduled tasks per-method state (1 gap)

| REST route | CLI command needed |
|---|---|
| `GET /api/scheduled-tasks/state/{section}/{art}/{method}` | `aether scheduled-tasks state <section> <art> <method>` |

Affects 08-resources/test-scheduled-tasks (RC1-blocker #16 — alongside the inject-endpoint architecture proposed below).

### G. Artifact read (2 gaps)

| REST route | CLI command needed |
|---|---|
| `GET /repository/{g}/{a}/{v}/{file}` (artifact stream) | `aether artifacts get <g:a:v> [--out=<file>]` |
| `POST /repository/{g}/{a}/{v}/{file}` (upload alt form) | (already covered via `artifacts deploy`/`push`) |

Currently 09-artifacts uses raw curl GET for resolve. This is the only legitimate "we have to curl" in that suite.

### H. Blueprint publish artifact form (1 gap)

| REST route | CLI command needed |
|---|---|
| `POST /api/blueprints/publish` (artifact form) | `aether blueprints publish <g:a:v>` (orthogonal to `blueprints apply <file>`) |

Currently dead route — defined in enum but never invoked from CLI.

### I. Format quirks (2 partial gaps)

- `aether cluster export` returns raw TOML; `--format json` is silently ignored for the body. RC2 — the CLI should respect the format flag or document the deviation.
- `aether artifacts versions` returns Maven `maven-metadata.xml` (not JSON). RC2 — convert to structured JSON output or document.

### J. Architectural addition for test ergonomics (1 new endpoint)

By analogy with `/api/alerts/inject` and `/api/traces/inject` (which closed the 11-observability remediation gap):

| REST route | CLI command needed |
|---|---|
| `POST /api/scheduled-tasks/inject` (test-only) | `aether scheduled-tasks inject <section> <art> <method>` |

This would unblock the RC1-blocker for `test_task_last_execution_advances` (gives the test a deterministic way to trigger task execution, replacing the warn-then-pass demotions).

---

## Methodology notes

- **Sources:** 7 parallel agent runs (1 per suite batch + lib audit + CLI inventory + prior-audit triage). Raw outputs preserved under `partials/`.
- **Static analysis only:** No tests were run live; correctness verdicts are based on reading the assertion logic. A live run could surface additional issues (e.g., tests that consistently warn on cloud but never on docker) but is out of scope here.
- **Triage policy:** RC1-BLOCK is reserved for tests where the claim is materially uncoupled from the assertion — they would not detect the regression they advertise. HIGH covers tests where a real regression of bounded severity would silently pass. MEDIUM and LOW capture coverage narrowness, name-vs-check mismatches, and cosmetic issues.
- **No coverage of subsystems missing tests:** This audit catalogues what tests check; it does not measure feature coverage gaps. The `feature-catalog.md` (capability inventory) is the orthogonal reference for coverage.

## Recommended next moves

1. **Fix the 18 RC1-blockers in §2.2.** Three categories:
   - **Pure test bug** (10 items): assertion logic fix only. Items 1, 2, 5-14, 17, 18.
   - **Need a test-only API endpoint** (1 item): item 16 needs `/api/scheduled-tasks/inject`.
   - **Need TLS/security infra** (4 items): items 3, 4, 5-8 need actual identity surfacing in API responses. Cross-check with the RBAC spec.

2. **Close the CLI gaps in §3.3 A and B** before the next audit cycle. These are visible to operators (anyone wanting Prometheus/transport metrics, stream create/delete/read) and the test suite has to work around them with raw curl.

3. **Triage the 4 prior-audit OPEN findings** (§2.5) — these are the only carryovers from 88638126 worth chasing now.

4. **Delete the `drain_node` / `activate_node` shadow definitions** in `lib/cluster.sh` (lib audit headline). Five-line change, removes a maintenance trap.

5. **Re-run this audit after the next significant test-infra rewrite** (estimate: post-RC1, alongside the §3.3 CLI gap closure) to catch new patterns.

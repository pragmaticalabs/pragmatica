# §2.1 lib/ shared helpers

This audit covers all functions in `aether/tests/integration/lib/`: `cluster.sh` (89 fns), `common.sh` (46 fns), `topology.sh` (8 fns), `load.sh` (6 fns), `json.sh` (6 fns), `suite.sh` (5 fns), plus `generation.sh` (6 fns — sourced by `cluster.sh`).

Note on tooling classification: the codebase has substantially migrated toward CLI for cluster mgmt. CURL is now used predominantly for fast-path liveness probes and for raw `_api_call` plumbing under `api_get/post`. The `feedback_prefer_aether_cli` rule allows curl for app-HTTP and general HTTP; that is honored. The dominant remaining smell is **substring grep for JSON fields** instead of CLI-with-jq or the canonical `aether_field`.

---

### lib/common.sh

#### aether_failover (common.sh:56)
- **Purpose:** Run `aether ...` against `MGMT_ENTRY_POINT`; if it doesn't answer `/health/live` in 2s, scan node ports 0..N-1, pick the first live one, run the CLI there for THIS call only (preserves pinning contract).
- **Inputs/Outputs:** Variadic CLI args; stdout = CLI output.
- **Correctness:** SOUND. Reads `/health/live` (correct semantic per spec §3.1).
- **Tooling:** mixed (curl probe + CLI run).
- **Severity:** none.

#### aether_field / aether_json (common.sh:94 / 106)
- **Purpose:** Wrappers that pass `--format value --field` or `--format json` to `aether_failover`.
- **Correctness:** SOUND.
- **Tooling:** CLI.

#### log_info / log_warn / log_error / log_pass / log_step / log_fail (common.sh:122-142)
- **Purpose:** Stamped logging; `log_fail` now also increments `TEST_FAIL_COUNT` so a test that emits `[FAIL]` then returns 0 is still recorded as FAIL.
- **Correctness:** SOUND. The latch is the explicit fix for a real green-sticker pattern.
- **Severity:** none.

#### _resolve_live_endpoint (common.sh:151)
- **Purpose:** Return a live `/health/live` endpoint (current pin if up, else first responding `MGMT_PORT+i`).
- **Correctness:** SOUND.
- **Tooling:** curl (acceptable — liveness probe is the canonical use of curl on the mgmt path).

#### _refresh_mgmt_entry_point (common.sh:178)
- **Purpose:** Export `MGMT_ENTRY_POINT`/`CLUSTER_ENDPOINT` to a live endpoint; return non-zero on failure.
- **Correctness:** SOUND. Critical for the fast-fail in `wait_for_cluster_ready`.

#### api_get / api_post / api_put / api_delete (common.sh:188-235)
- **Purpose:** Generic HTTP wrappers against `CLUSTER_ENDPOINT` (or live-resolved for GET/POST), feeding `_api_call`.
- **Correctness:** SOUND for the most part. `api_put` and `api_delete` use the raw `${CLUSTER_ENDPOINT}` and NOT the resolved live endpoint, which is asymmetric — if the pinned endpoint is dead, a PUT/DELETE will fail while a GET would have succeeded.
- **Green stickers:**
  - L_203 (`api_put`): does not call `_resolve_live_endpoint`. Asymmetric with `api_get`/`api_post`.
  - L_232 (`api_delete`): same. Prior audit flagged this; still present.
- **Severity:** RC2.
- **Suggested fix:** mirror `api_get`/`api_post` to resolve a live endpoint inside `api_put` and `api_delete`.

#### _api_call (common.sh:212)
- **Purpose:** curl wrapper that surfaces status code AND body, log_warns on non-2xx/3xx.
- **Correctness:** SOUND. Explicit fix for `-sf` silently dropping HTTP error bodies. Returns rc=1 (real failure) on 4xx/5xx.

#### node_api_get / node_api_post / direct_api_get / direct_api_post (common.sh:242-263)
- **Correctness:** SOUND.

#### app_get / app_post (common.sh:268-277)
- **Correctness:** SOUND.

#### http_status / http_status_with_body (common.sh:280-305)
- **Correctness:** SOUND.

#### wait_for (common.sh:310)
- **Correctness:** SOUND. The `&& rc=0 || rc=$?` idiom protects against caller's `set -e`. Distinguishes `rc=2|127` (bash error / not found) from `rc=1` (predicate false).

#### assert_eq / assert_ne / assert_gt / assert_ge / assert_contains / assert_http_status / assert_json_field (common.sh:348-410)
- **Correctness:** SOUND for the equality forms. `assert_gt`/`assert_ge` use `2>/dev/null` around the bash arithmetic — if `$actual` is empty/non-numeric the test becomes false silently.
- **Green stickers:**
  - L_370/L_380 (`assert_gt`/`assert_ge`): `2>/dev/null` on bash arithmetic masks parse errors.
- **Severity:** RC2.

#### SSH_OPTS / remote_exec / remote_scp (common.sh:433-455)
- **Correctness:** SOUND.

#### to_node_id / cloud_public_ip / cloud_node_ip / cloud_ssh (common.sh:494-604)
- **Correctness:** SOUND. Parses parallel JSON arrays via awk (no jq dependency).

#### collect_node_metrics (common.sh:612)
- **Correctness:** NARROW. Opt-in (`COLLECT_METRICS=true`).
- **Severity:** WON'T-FIX.

#### run_test / skip_test / print_summary (common.sh:677-750)
- **Correctness:** SOUND. The H2 latch fix is in.

---

### lib/cluster.sh

#### cluster_member_count (cluster.sh:13)
- **Correctness:** SOUND-but-NARROW. Substring grep for `"nodeId"` to count members. The `max(members, desiredSize)` tie-break biases UP — fine for scale-up convergence; misleading during scale-down. `cluster_node_count_quiesced` exists for precise reads.
- **Green stickers:**
  - L_50-52: the `max(members, desired)` heuristic biases UP. Documented.
- **Severity:** RC2 (covered by `_quiesced` variant for callers who care).

#### cluster_leader (cluster.sh:65)
- **Correctness:** SOUND. Distinguishes `"none"` from empty (uses `aether_field`, real JSON parse).

#### cluster_node_count_quiesced (cluster.sh:90)
- **Correctness:** SOUND. Uses canonical `await_generation_quiesced` barrier.

#### cluster_phase / cluster_active_core_count / cluster_quorate / node_lifecycle_state / cluster_status / cluster_health / cluster_events / cluster_node_list / cluster_slices / cluster_config (cluster.sh:102-391)
- **Correctness:** SOUND.
- **Green stickers:**
  - `cluster_active_core_count` (L_119): `api_get ... || true` then `[ -z "$topology" ] && echo 0` — silently returns 0 if the API is unreachable. A genuine 0 (no cores) and "API down" are indistinguishable.
  - `node_lifecycle_state` (L_141-146): substring-shadow risk on hairy sed regex.
- **Severity:** RC2.

#### pick_non_leader (cluster.sh:208)
- **Correctness:** SOUND. The prior audit flagged the `node-1..5` hardcoded fallback; that is GONE. The new code uses CLI server-side filter and `log_fail`s loudly when no candidates available. Re-derives leader from `/api/nodes/status` to close round-robin race.
- **Severity:** none.

#### rotate_mgmt_entry_point (cluster.sh:342)
- **Correctness:** SOUND. Probes `/health/live`.

#### is_cluster_healthy (cluster.sh:396)
- **Correctness:** SOUND. The prior `UP OR healthy` dual-acceptance is REMOVED — strict equality on `status == "healthy"`.

#### is_cluster_ready / wait_for_cluster_ready / _cluster_is_ready (cluster.sh:413-491)
- **Correctness:** SOUND. Three checks: member count ≥ expected, leader elected, active cores ≥ expected-1. Fast-fail probe via `_refresh_mgmt_entry_point`. Prior Property 4 (per-port iteration) REMOVED.
- **Severity:** none.

#### wait_for_node_count / wait_for_node_count_fast / wait_for_node_count_on (cluster.sh:549-641 / 2233)
- **Correctness:** SOUND. Fast variant explicitly cloud-falls-through to the slow CLI variant. `_on` uses `${v:--1}` sentinel.

#### wait_for_leader / wait_for_leader_committed / wait_for_leader_on (cluster.sh:643-685 / 2250)
- **Correctness:** SOUND. Single-read predicate per iteration. Cluster-B floor of 120s prevents cold-boot false-fails.

#### slices_total_instances / slices_active_instances / slices_target_total (cluster.sh:737-950)
- **Correctness:** SOUND. Server-side filter `aether slices --state LOADED+ACTIVE` (fixes prior client-side regex).
- **Green stickers:**
  - L_747/L_939/L_949: `echo "${count:-0}"` defaults to 0 on parse error, conflated with "0 instances".
- **Severity:** RC2.

#### slice_owner_for (cluster.sh:779)
- **Correctness:** SOUND. Explicit awk state machine; well-documented.

#### app_route_wired (cluster.sh:907)
- **Correctness:** SOUND. Replaces prior `<500` (green sticker — accepted 4xx) with body-discriminated logic.
- **Severity:** none.

#### push_blueprint (cluster.sh:952)
- **Correctness:** SOUND. Uses jq when available + grep extractor fallback.

#### deploy_blueprint (cluster.sh:1022)
- **Correctness:** NARROW. `aether_failover blueprints deploy ... 2>/dev/null || api_post ...` — `2>/dev/null` strips CLI failure reason.
- **Green stickers:**
  - L_1028: `2>/dev/null` mask on CLI failure before falling back.
- **Severity:** RC2.

#### publish_blueprint (cluster.sh:1032)
- **Correctness:** SOUND. Fail-closed if blueprint not visible in 10s.

#### deploy_blueprint_file (cluster.sh:1062)
- **Correctness:** Raw `curl -sf` DOES drop the HTTP error body — should route through `_api_call`.
- **Green stickers:**
  - L_1067: `curl -sfk` swallows HTTP error response body.
- **Severity:** RC2.

#### restart_all_nodes (cluster.sh:1237)
- **Correctness:** SOUND. Capture stderr (`restart_out`), fail loud on rc!=0. Strict barriers: `wait_for_node_count`, `wait_for_leader`, `await_generation_quiesced`, `wait_for_cluster_ready`. Cloud branch handles JVM-mode via captured cmdline.

#### kill_node (cluster.sh:1361)
- **Correctness:** SOUND. Explicit empty-id guard; explicit pinned-node guard; explicit `docker update --restart=no` BEFORE kill.

#### drain_node / activate_node (cluster.sh:1523/1529 AND 1545/1550 — **DUPLICATE DEFINITIONS**)
- **Correctness:** GREEN-STICKER (definition shadowing). `drain_node` is defined TWICE (L_1523 and L_1545). The second definition wins. The first uses `/api/nodes/drain` with `{"nodeId":"X"}` body; the second uses `/api/nodes/drain/X` with empty body. Same for `activate_node`. The shadow is dead code AND a maintenance trap.
- **Severity:** RC2 (functional behavior is determined by L_1545/L_1550 — but the duplication is a red flag).
- **Suggested fix:** Delete the first triple, OR clarify which path is canonical.

#### reset_provisioning_circuit (cluster.sh:1587)
- **Correctness:** SOUND.

#### disable_auto_heal / enable_auto_heal / auto_heal_enabled (cluster.sh:1619-1686)
- **Correctness:** SOUND. The verify-after pattern catches "CLI exited 0 but state unchanged" — production-grade defense-in-depth.

#### restore_cluster_baseline (cluster.sh:1717)
- **Correctness:** SOUND on balance. Hard barrier is `cluster_active_core_count >= N-1` — documented as accepting the RC2 MembershipView convergence lag.
- **Severity:** RC2 (documented; depends on `MembershipView` fix).

#### scale_cluster (cluster.sh:1805)
- **Correctness:** SOUND. Fix for prior "rc=0 on `{"error":"quorum unavailable"}`" — separates transport rc from HTTP status.

#### leader_api_post (cluster.sh:1865)
- **Correctness:** SOUND.

#### task_assignment_count / task_group_status (cluster.sh:2003/2017)
- **Correctness:** SOUND. `task_assignment_count` was previously over-counting bare `"group"` tokens; now requires a string value.
- **Green stickers:**
  - `task_group_status` (L_2017): falls back to `UNASSIGNED` on CLI error — conflated with real state. Mitigated by `wait_for_task_assigned`.
- **Severity:** RC2.

#### container_running (cluster.sh:2120)
- **Correctness:** SOUND. Two-stage check (docker `status=running` + curl `/health/live`).

#### deploy_cleanup (cluster.sh:2187)
- **Correctness:** SOUND. `grep -o ... || true` guard against `set -euo pipefail`-induced silent abort.

---

### lib/json.sh

#### json_value / json_array_length etc. (json.sh:18-104)
- **Correctness:** GREEN-STICKER in the technically-precise sense — `json_value` matches FIRST occurrence of key (inner-object shadowing); `json_array_length` over-counts strings containing commas. Documented contract.
- **Green stickers:**
  - `json_value` (L_22-28): inner-object field shadow.
  - `json_array_length` (L_75): awk `},{` split is naive.
- **Severity:** RC2 (documented; mitigated by spec preference for CLI).

---

### lib/load.sh

#### start_load and friends (load.sh:14-210)
- **Correctness:** SOUND in intent (load is metered as success/failure, not asserted on individual responses).
- **Severity:** none.

---

### lib/suite.sh

- **Correctness:** SOUND.

---

### lib/topology.sh

#### observe_quorum_window (topology.sh:176)
- **Correctness:** SOUND. The prior "ok (no events in window)" green sticker is FIXED: default fail-closed; `allow_empty=true` is an explicit caller opt-in.

#### topology_events_since (topology.sh:60+)
- **Correctness:** SOUND.
- **Green stickers:**
  - L_64: `curl -sfk ... 2>/dev/null || continue` — silent per-node drop on union (intentional).

#### wait_for_node_departure / wait_for_replacement_of (topology.sh:121-163)
- **Correctness:** SOUND.

---

### lib/generation.sh

- **Correctness:** SOUND. CLI-preferred (`aether cluster await-quiesced`), REST fallback.

---

### Summary

Of 124 functions audited, the picture is materially improved over the prior 45-finding audit.

**Prior audit findings now FIXED:**
- `pick_non_leader` hardcoded `node-1..5` fallback — removed
- `app_route_wired` accepting `<500` — replaced with body-discriminated check
- `api_delete` swallowing — `_api_call` now log_warns body
- `eval $check_cmd` aborting on set -e — fixed
- Synonym-without-anchor (`UP OR healthy`) — strict
- `observe_quorum_window` empty-events tautology — fail-closed
- Lying log_fail — TEST_FAIL_COUNT latch
- Per-port readiness iteration — removed (Property 4)

### Residual findings (sorted by severity)

| Function | Correctness | Severity |
|---|---|---|
| `drain_node` / `activate_node` (cluster.sh:1523 AND 1545) | GREEN-STICKER (definition shadow) | RC2-BLOCK |
| `api_put` / `api_delete` (common.sh:203,232) | NARROW (asymmetric with get/post) | RC2 |
| `deploy_blueprint` (cluster.sh:1022) | NARROW (`2>/dev/null` strips CLI failure) | RC2 |
| `deploy_blueprint_file` (cluster.sh:1062) | NARROW (raw `curl -sfk` drops error body) | RC2 |
| `cluster_active_core_count` (cluster.sh:117) | NARROW (0 means "either no cores OR API down") | RC2 |
| `slices_*_instances` (cluster.sh:737,928) | NARROW (parse error → 0 conflates with "0 ACTIVE") | RC2 |
| `assert_gt` / `assert_ge` (common.sh:368,378) | NARROW (`2>/dev/null` on arithmetic masks "empty $actual") | RC2 |
| `task_group_status` (cluster.sh:2008) | NARROW (CLI error → `UNASSIGNED` conflated with real state) | RC2 |
| `cluster_member_count` (cluster.sh:13) | NARROW (`max(members, desired)` biases up during scale-down) | RC2 |
| `json_value` / `json_array_length` (json.sh:18,58) | NARROW (substring-first-match, awk `},{` split) | RC2 |

**No RC1-BLOCK findings.** The shadowed `drain_node`/`activate_node` is the only structural defect — everything else is residual NARROW that is well-documented and bounded by the spec.

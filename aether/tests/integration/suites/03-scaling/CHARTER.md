# Suite 03-scaling Charter

**Test-ID convention:** `TC-03-NNN` where `NNN` is a zero-padded 3-digit index assigned in `run_test` invocation order across all scripts in the suite. Scripts run alphabetically (`test-*.sh` glob): `test-01-quorum-safety.sh` → `test-02-scale-up.sh` → `test-03-scale-down.sh`. Numbers are stable across reorganisations; do not reuse retired IDs.

**Charter purpose:** Destructive scale-operation coverage. Validates the leader's scale validator (rejection of unsafe targets), CTM scale-up convergence, scale-down under sustained load, and end-to-end no-data-loss across a 7→5 topology change.

---

## Contracts under test

| ID | Contract | Spec citation |
|---|---|---|
| C1 | Cluster reaches the canonical "ready" state with N members, leader elected | `aether/docs/specs/test-readiness-contract.md §1.1` |
| C2 | `seed_cluster_config` succeeds — operator config is planted in KV-Store for subsequent scale-validator decisions | `aether/docs/specs/cluster-config-spec.md §seed` `[CONTRACT-GAP]` (helper-level contract, not formally specced) |
| C3 | Scale validator rejects targets below minimum quorum (coreCount=1, coreCount=2) with HTTP 4xx | `aether/docs/specs/cluster-deployment-manager-spec.md §scale-validator` `[CONTRACT-GAP]` (validator rules documented only in `ClusterScaleHandler` source); minimum quorum implied by `cluster-membership-fsm-spec.md §quorum` |
| C4 | Scale validator rejects targets above configured maximum (coreCount=20) with HTTP 4xx | `[CONTRACT-GAP]` (max-core configuration referenced only in `ClusterScaleHandler` + `ClusterConfig`) |
| C5 | Cluster shape + health preserved across rejected scale operations | `aether/docs/specs/test-readiness-contract.md §1.1`, `§3 (api/health row)` |
| C6 | Scale up 5→7: CTM provisions 2 additional cores; `member_count == 7` within budget; cluster reports `"healthy"` | `aether/docs/specs/cluster-deployment-manager-spec.md §scale-up`; `aether/docs/specs/test-readiness-contract.md §2.1` `[CONTRACT-GAP]` (CTM scale-up SLA in code) |
| C7 | Scale down 7→5 under sustained load: `member_count == 5` within budget; error rate < operational-event tier 2.0% | `aether/docs/specs/test-readiness-contract.md §4` (Operational events tier = 2.0%) |
| C8 | Final cluster state at 5 nodes is healthy | `aether/docs/specs/test-readiness-contract.md §3 (api/health row)` |
| C9 | No data loss across topology change: artifact pushed pre-scale survives 7→5 with byte-for-byte SHA-256 equality | `aether/docs/specs/dht-replication-spec.md §rebalance` `[CONTRACT-GAP]` (no canonical DHT-rebalance no-loss spec; behavior described in `aether/docs/internal/architecture/dht.md`); contract closed in commit c68a3ec37 — was previously the audit's egregious tautology |

---

## Test-to-contract map

### test-01-quorum-safety.sh

| TC ID | Test function | File:line | Contract(s) | Severity | Notes |
|---|---|---|---|---|---|
| TC-03-001 | `test_seed_config` | `test-01-quorum-safety.sh:10` | C1, C2 | smoke | `wait_for_cluster_ready 60` + `wait_for_leader 60` + `seed_cluster_config`; helper rc propagated via `set -e` |
| TC-03-002 | `test_initial_state` | `test-01-quorum-safety.sh:16` | C1 | smoke | `member_count >= 3` floor (allows previous-suite-degraded cluster) |
| TC-03-003 | `test_reject_scale_to_1` | `test-01-quorum-safety.sh:63` | C3 | core | `direct_scale_status` POSTs to leader (or per-node iteration in docker mode); accepts any `>= 400` as rejection |
| TC-03-004 | `test_reject_scale_to_2` | `test-01-quorum-safety.sh:74` | C3 | core | Same shape as TC-03-003 with `coreCount=2` |
| TC-03-005 | `test_reject_scale_above_max` | `test-01-quorum-safety.sh:85` | C4 | core | Same shape with `coreCount=20` |
| TC-03-006 | `test_cluster_unchanged` | `test-01-quorum-safety.sh:96` | C5 | regression-net | `member_count >= 3` floor (same as initial) + `assert_cluster_healthy`; name overstates — only checks floor |

### test-02-scale-up.sh

| TC ID | Test function | File:line | Contract(s) | Severity | Notes |
|---|---|---|---|---|---|
| TC-03-007 | `test_seed_config` | `test-02-scale-up.sh:10` | C1, C2 | smoke | Idempotent re-seed for suite isolation |
| TC-03-008 | `test_baseline_5_nodes` | `test-02-scale-up.sh:16` | C1 | smoke | `wait_for_node_count_fast 5 60` + strict `assert_eq 5` |
| TC-03-009 | `test_scale_up_to_7` | `test-02-scale-up.sh:23` | C6 | core | `scale_cluster 7` + `wait_for_node_count_fast 7 300` + strict `assert_eq 7`. Fast-poll variant required (CLI/double-curl burned ~4-6s/iter on Hetzner) |
| TC-03-010 | `test_7_nodes_healthy` | `test-02-scale-up.sh:34` | C6, C8 | core | `assert_cluster_healthy` at 7 |
| TC-03-011 | `test_restore_to_5` | `test-02-scale-up.sh:38` | C7 (without load) | core | `scale_cluster 5` + `wait_for_node_count_fast 5 180` + strict `assert_eq 5` |

### test-03-scale-down.sh

| TC ID | Test function | File:line | Contract(s) | Severity | Notes |
|---|---|---|---|---|---|
| TC-03-012 | `test_seed_config` | `test-03-scale-down.sh:31` | C1, C2 | smoke | Idempotent re-seed + `await_generation_quiesced` (soft) before scale ops to avoid "quorum unavailable" during brief re-election window |
| TC-03-013 | `test_seed_marker` | `test-03-scale-down.sh:41` | C9 (seed half) | core | `dd` 4KB random artifact + SHA-256 compute; PUT to `${CLUSTER_ENDPOINT}<repository-path>`; strict `assert_eq status 201`. State ferried via `/tmp/aether-test-03-marker-$$.{bin,sha,path}` (subshell isolation) |
| TC-03-014 | `test_scale_up_to_7` | `test-03-scale-down.sh:70` | C6 | core | Precondition for scale-down — `scale_cluster 7` + `wait_for_node_count_fast 7 180` + strict `assert_eq 7` |
| TC-03-015 | `test_scale_down_under_load` | `test-03-scale-down.sh:82` | C7 | core | `start_load` against `/api/echo/health`, 5s legitimate load-ramp, `scale_cluster 5`, `wait_for_node_count_fast 5 180`, `assert_error_rate_below 2.0` |
| TC-03-016 | `test_5_nodes_healthy` | `test-03-scale-down.sh:107` | C8 | core | Strict `assert_eq count 5` + `assert_cluster_healthy` |
| TC-03-017 | `test_no_data_loss` | `test-03-scale-down.sh:114` | C9 (assertion half) | core | `_refresh_mgmt_entry_point` (rotate off drained node) + GET marker via `${CLUSTER_ENDPOINT}<path>`; strict `assert_eq status 200`, strict size equality, strict SHA-256 equality. **CLOSED in commit c68a3ec37** (was the audit's HIGH-severity tautology `assert_ne "$(cluster_events)" ""`) |

---

## Suite-level invariants

- **Pre-conditions:** Cluster B (destructive, `restart: "no"` policy). 5-node baseline; `test-echo` blueprint deployed by suite harness (required by `test_scale_down_under_load`'s `/api/echo/health` load target). `CLUSTER_ENDPOINT`, `API_KEY`, `MGMT_PORT`, `TARGET_HOST`, `NODE_COUNT=5` exported. `seed_cluster_config` is idempotent and re-runs per script.
- **Side effects:** Pushes a unique random artifact to `/repository/org/test/scale-down-marker/...` (test-03 only); issues actual scale operations through `/api/cluster/scale` (5→7 and 7→5); generates ~`LOAD_RPS * LOAD_DURATION` = ~900 HTTP requests against the slice during scale-down.
- **Cleanup discipline:** `test-03-scale-down.sh` installs `trap cleanup_marker EXIT` to remove `/tmp/aether-test-03-marker-$$.{bin,sha,path,resolved.bin}`. No suite-level baseline-restore trap — relies on later suites' own restore or on the harness's between-suite reset. Final cluster shape after the suite is N=5 (restored by `test_restore_to_5` and `test_5_nodes_healthy`).

---

## Known limitations

| TC ID | Limitation | Tracking |
|---|---|---|
| TC-03-002 | `member_count >= 3` floor — strict 5 would be a stronger entry assertion | Audit §1.6 (WEAK, severity LOW) |
| TC-03-003 | `>= 400` accepts ANY 4xx OR 5xx as "rejection". A 503/500 from a broken validator (server crash) would pass as "rejected". `direct_scale_status` iterates all nodes and returns first non-000; follower "not leader" 4xx is indistinguishable from validator rejection | Audit §1.6 (PARTIAL 5xx-as-rejection, severity MEDIUM) |
| TC-03-004 | Same `>= 400` accepts-any-error issue as TC-03-003 | Audit §1.6 (PARTIAL, severity MEDIUM) |
| TC-03-005 | Same `>= 400` accepts-any-error issue as TC-03-003 | Audit §1.6 (PARTIAL, severity MEDIUM) |
| TC-03-006 | Name claims "unchanged" but only checks `member_count >= 3` floor (same floor as `test_initial_state`); pre/post comparison would be stricter | Audit §1.6 (WEAK name/check mismatch, severity LOW) |
| TC-03-015 | `start_load` counts `200..399` as success — 3xx-as-success in error-rate denominator. Low impact: app route deliberately returns 200 | Audit §1.6 (SOUND-with-3xx-caveat, severity LOW) |
| TC-03-017 | Previously the suite's egregious HIGH-severity tautology (`assert_ne "$(cluster_events)" ""`). **CLOSED in commit c68a3ec37** — rewritten to push a unique SHA-256-tagged artifact pre-scale, refresh entry point post-scale, and assert HTTP 200 + size + SHA-256 equality. The new implementation is the actual no-data-loss contract | Audit §1.6 (was HIGH); CLOSED in c68a3ec37 |

---

## Charter changelog

| Date | Author | Change |
|---|---|---|
| 2026-05-21 | charter author | Initial charter from audit §1.6; TC-03-017 reflects post-c68a3ec37 implementation (SHA-256 marker round-trip) |


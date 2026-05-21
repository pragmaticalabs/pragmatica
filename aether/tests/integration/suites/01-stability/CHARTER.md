# Suite 01-stability Charter

**Test-ID convention:** `TC-01-NNN` where `NNN` is a zero-padded 3-digit index assigned in `run_test` invocation order across all scripts in the suite. Numbers are stable across reorganisations; do not reuse retired IDs.

**Charter purpose:** Long-running soak coverage — sustained HTTP load against a deployed app, and sustained stream publish against the notification hub. Catches steady-state regressions (memory leaks, leader churn, slice-route drift) that single-shot smoke cannot see.

---

## Contracts under test

| ID | Contract | Spec citation |
|---|---|---|
| C1 | Cluster baseline is reachable: `wait_for_cluster_ready` succeeds within budget; `member_count >= NODE_COUNT` (N-floor entry) | `aether/docs/specs/test-readiness-contract.md §1.1` (N-floor lenient usage acknowledged at §1.2) |
| C2 | Soak app endpoint accepts seed PUT with strict 2xx (no 3xx redirect masking) | `aether/docs/specs/test-readiness-contract.md §4.2` (strict 2xx, no 3xx-as-success) |
| C3 | Sustained HTTP soak (4h default) holds error rate below the soak-tier 1.0% noise floor | `aether/docs/specs/test-readiness-contract.md §4` (Soak tier = 1.0%) |
| C4 | Cluster shape is preserved across soak: `member_count >= NODE_COUNT` post-soak | `aether/docs/specs/test-readiness-contract.md §2.1` (member-count semantic) |
| C5 | Cluster health remains `"healthy"` after soak | `aether/docs/specs/test-readiness-contract.md §3 (api/health row)` |
| C6 | Leader is still present after soak (existence-only check — see Known limitations) | `aether/docs/specs/test-readiness-contract.md §1.1 (Property 2)` |
| C7 | Stream list endpoint responds | `aether/docs/specs/streaming-spec.md §publish` `[CONTRACT-GAP]` (stream-existence semantics not spec-pinned) |
| C8 | Sustained 1h stream publish holds error rate below the operational-event 2.0% tier | `aether/docs/specs/test-readiness-contract.md §4` (Operational events tier = 2.0%) |
| C9 | Cluster shape + health preserved across streaming soak | `aether/docs/specs/test-readiness-contract.md §2.1`, `§3 (api/health)` |
| C10 | JVM stats (uptime per node) are collectible — diagnostic baseline for leak inspection | `[CONTRACT-GAP]` — no formal leak-detection spec; current automation is diagnostic-only |

---

## Test-to-contract map

| TC ID | Test function | File:line | Contract(s) | Severity | Notes |
|---|---|---|---|---|---|
| TC-01-001 | `test_cluster_baseline` | `test-soak-4h.sh:45` | C1 | smoke | `wait_for_cluster_ready 60` + `assert_ge $count $NODE_COUNT` (N-floor entry) |
| TC-01-002 | `test_deploy_app` | `test-soak-4h.sh:52` | C1 | smoke | Push + deploy + `wait_for_slices_active 1 120`; soak blueprint = `test-persistence:1.0.0` |
| TC-01-003 | `test_app_reachable` | `test-soak-4h.sh:59` | C2 | core | Seed PUT `${APP_ENDPOINT}${SOAK_PATH}`; strict 2xx (200..299), explicit fail on 3xx |
| TC-01-004 | `test_collect_pre_stats` | `test-soak-4h.sh:76` | C10 | smoke | Per-port `/api/nodes/status` uptime extraction; unconditional `log_pass` |
| TC-01-005 | `test_soak_load` | `test-soak-4h.sh:82` | C3 | core | Dual loader (app + `/health/live`), 4h default; `assert_error_rate_below 1.0` |
| TC-01-006 | `test_collect_post_stats` | `test-soak-4h.sh:103` | C10 | smoke | Diagnostic-only; no pre/post comparison — see Known limitations |
| TC-01-007 | `test_no_node_drift` | `test-soak-4h.sh:110` | C4 | core | `assert_ge $end_nodes $NODE_COUNT`; floor-only (doesn't catch upward drift) |
| TC-01-008 | `test_cluster_still_healthy` | `test-soak-4h.sh:116` | C5 | core | `assert_cluster_healthy` (`/api/health` status == healthy) |
| TC-01-009 | `test_no_leader_change` | `test-soak-4h.sh:120` | C6 | regression-net | Existence check (`assert_ne "$leader" ""`) — name overstates; see Known limitations |
| TC-01-010 | `test_stream_exists` | `test-streaming-soak.sh:17` | C7 | smoke | Empty stream list passes via `if empty → log_info → log_pass anyway`; see Known limitations |
| TC-01-011 | `test_sustained_publish` | `test-streaming-soak.sh:27` | C8 | core | 1h publish to `/api/streams/publish/<name>`; inline strict 2xx classifier; `assert_error_rate_below 2.0` |
| TC-01-012 | `test_cluster_stable_after_stream` | `test-streaming-soak.sh:64` | C4, C9 | core | Hardcoded `assert_eq "$count" "5"` — does NOT respect `NODE_COUNT` env override; see Known limitations |
| TC-01-013 | `test_health_after_stream` | `test-streaming-soak.sh:70` | C5, C9 | core | `assert_cluster_healthy` post-streaming |

---

## Suite-level invariants

- **Pre-conditions:** Cluster A (non-destructive), `NODE_COUNT=5`, 00-smoke has run (blueprint pre-seeded is acceptable but the suite re-pushes its own `test-persistence` blueprint). `SOAK_DURATION` defaults to 14400s (4h), `STREAM_DURATION` to 3600s (1h); both are env-overridable for CI smoke runs.
- **Side effects:** Deploys `test-persistence` blueprint; seeds the soak key under `${SOAK_PATH}`; publishes ≥thousands of stream messages to the `notifications` stream; collects diagnostic stats into `/tmp/soak_stats.txt`, `/tmp/sustained_load_soak.log`, `/tmp/sustained_health_soak.log`. None of these are cleaned up — operators rely on them post-run.
- **Cleanup discipline:** No EXIT trap; no scaling. The suite is intentionally non-destructive; the cluster is expected to be left at its baseline shape (N nodes, healthy, leader present).

---

## Known limitations

| TC ID | Limitation | Tracking |
|---|---|---|
| TC-01-004 | Diagnostic-only — does not assert uptime>0 or any RSS threshold; silently swallows curl failures via `2>/dev/null` and defaults uptime to 0 (stale-fallback pattern) | Audit §1.3 (WEAK diagnostic, severity LOW) |
| TC-01-005 | `start_sustained_load` counts `200..399` as success — a 3xx misroute would pass. Acceptable for soak target that deliberately returns 200, but threshold checked against wrong denominator if redirects occur | Audit §1.3 (PARTIAL, severity LOW) |
| TC-01-006 | File header promises "leak detection" but no diff/threshold assertion vs pre-stats; only operator inspection of `/tmp/soak_stats.txt` would catch a leak. "Data collected ≠ data validated" | Audit §1.3 (WEAK diagnostic, severity MEDIUM) |
| TC-01-007 | `assert_ge` floor — does not catch upward drift (e.g. CTM provisioning extra nodes). Bidirectional check would be stricter | Audit §1.3 (PARTIAL, severity LOW) |
| TC-01-009 | Name claims "no leader CHANGE" but only checks "leader EXISTS". Leader churn during soak is not detected; should record pre-soak leader and compare | Audit §1.3 (WEAK, severity MEDIUM) |
| TC-01-010 | Empty stream list passes via `if empty → log_info` then `log_pass` anyway — green-sticker; cannot detect missing stream endpoint | Audit §1.4 (WEAK, severity MEDIUM) |
| TC-01-012 | Hardcoded literal `5` instead of `${NODE_COUNT:-5}`; misleads when `NODE_COUNT != 5`. Other tests in suite respect the env override | Audit §1.4 (SOUND-with-NODE_COUNT-bug, severity LOW) |

---

## Charter changelog

| Date | Author | Change |
|---|---|---|
| 2026-05-21 | charter author | Initial charter from audit §1.3-§1.4 |


# Suite 00-smoke Charter

**Test-ID convention:** `TC-00-NNN` where `NNN` is a zero-padded 3-digit index assigned in `run_test` invocation order across all scripts in the suite. Numbers are stable across reorganisations; do not reuse retired IDs.

**Charter purpose:** Smoke gate that proves a fresh cluster reaches the canonical "ready" state and that a single blueprint deploy makes its app route serve 200. Any failure here aborts the broader test run.

---

## Contracts under test

| ID | Contract | Spec citation |
|---|---|---|
| C1 | Cluster reaches the canonical "ready" state with N members, leader elected, ≥N-1 active cores | `aether/docs/specs/test-readiness-contract.md §1.1` |
| C2 | Leader-elected predicate: `cluster.leaderId` is a real node id (not `""`, `"none"`, or `"null"`) | `aether/docs/specs/test-readiness-contract.md §3` (StatusResponse contract) |
| C3 | Liveness probe answers 200 on `/health/live` while the JVM process is up | `aether/docs/specs/test-readiness-contract.md §3 (Liveness column)` |
| C4 | Status + events management endpoints return non-empty payloads under healthy steady-state | `aether/docs/specs/test-readiness-contract.md §3 (api/nodes/status, api/events rows)` |
| C5 | Blueprint push is idempotent (uploaded-or-already-present is success) | `aether/docs/specs/unified-deploy-spec.md §3 (artifact push)` `[CONTRACT-GAP]` (no dedicated spec section) |
| C6 | Blueprint deploy provisions ≥1 ACTIVE slice instance | `aether/docs/specs/unified-deploy-spec.md §3`; `aether/docs/specs/slice-lifecycle.md §2 (ACTIVE state)` |
| C7 | Deployed blueprint appears in the blueprint listing | `aether/docs/specs/unified-deploy-spec.md §3` `[CONTRACT-GAP]` (listing semantics not formally specced) |
| C8 | Slice app route is wired (not the synthetic AppHttpServer liveness intercept) and serves 200 with valid API key | `aether/docs/specs/unified-deploy-spec.md §3 (route registry republication)`; `aether/docs/specs/slice-lifecycle.md §2` |

---

## Test-to-contract map

| TC ID | Test function | File:line | Contract(s) | Severity | Notes |
|---|---|---|---|---|---|
| TC-00-001 | `test_nodes_formed` | `test-cluster-formation.sh:9` | C1 | core | Strict `member_count == NODE_COUNT` after `wait_for_cluster_ready 120`; seed-node lifecycle bug fixed |
| TC-00-002 | `test_leader_elected` | `test-cluster-formation.sh:24` | C2 | core | Rejects `""`, `"none"`, `"null"` — guards against the `assert_ne "" ""` tautology |
| TC-00-003 | `test_quorum_established` | `test-cluster-formation.sh:36` | C1 | regression-net | Redundant predicate vs TC-00-001 (audit §1: SOUND but REDUNDANT). Could be upgraded to assert `cluster.quorate` |
| TC-00-004 | `test_liveness_probe` | `test-cluster-formation.sh:48` | C3 | smoke | Hits entry-point only; AppHttpServer's synthetic 200 makes this a single-port availability check |
| TC-00-005 | `test_all_nodes_visible` | `test-cluster-formation.sh:52` | C1 | regression-net | 3rd use of same predicate (audit §1: SOUND but REDUNDANT) |
| TC-00-006 | `test_status_endpoint` | `test-cluster-formation.sh:64` | C4 | smoke | Non-empty + non-empty `nodeId`; tautological — see Known limitations |
| TC-00-007 | `test_events_available` | `test-cluster-formation.sh:73` | C4 | smoke | Non-empty body; `[]` would pass — see Known limitations |
| TC-00-008 | `test_push_artifacts` | `test-slice-deployment.sh:12` | C5 | core | `push_blueprint` rc propagated via `set -e`; helper does its own status parse |
| TC-00-009 | `test_deploy_blueprint` | `test-slice-deployment.sh:17` | C6 | regression-net | Non-empty deploy response; weak — see Known limitations |
| TC-00-010 | `test_slices_provisioned` | `test-slice-deployment.sh:23` | C6 | core | `wait_for_slices_active 1 120` + `slices_total_instances > 0` (strict `assert_gt`) |
| TC-00-011 | `test_blueprint_listed` | `test-slice-deployment.sh:30` | C7 | regression-net | Substring grep against raw `list_blueprints` text; false-positive risk for short names |
| TC-00-012 | `test_app_endpoint_reachable` | `test-slice-deployment.sh:36` | C8 | core | `app_route_wired` distinguishes route-missing 404 from real handler; 4xx-non-route-missing also counts as "wired" |
| TC-00-013 | `test_app_request_succeeds` | `test-slice-deployment.sh:51` | C8 | core | Strict `assert_http_status ... 200` against `/api/echo/health` with API key — the hard contract assertion |

---

## Suite-level invariants

- **Pre-conditions:** Cluster A (non-destructive), `NODE_COUNT=5`, no destructive suite has run before. `CLUSTER_ENDPOINT`, `APP_ENDPOINT`, `API_KEY`, `TEST_BLUEPRINT_COORDS`, `TEST_BLUEPRINT` are exported by the harness. Cluster is expected to come up from a clean `docker-compose-a` start; no prior tests have left ON_DUTY+DRAINING entries.
- **Side effects:** Pushes the `test-echo` blueprint to the cluster artifact store and deploys it (slice remains active for downstream suites). No scale operations.
- **Cleanup discipline:** No explicit cleanup — the deployed slice + pushed artifact are intentionally left in place so subsequent suites can run against a deployed app. No EXIT trap.

---

## Known limitations

| TC ID | Limitation | Tracking |
|---|---|---|
| TC-00-003 | Redundant — same predicate as TC-00-001/TC-00-005; does not verify Rabia quorate field | Audit §1.2 (SOUND but REDUNDANT, severity LOW) |
| TC-00-004 | Single-port availability only; AppHttpServer intercepts `/health/live` with synthetic 200 regardless of slice state. Doesn't iterate per-node ports | Audit §1.2 (WEAK, severity LOW) |
| TC-00-005 | 3rd duplicate of `cluster_member_count -eq NODE_COUNT` predicate | Audit §1.2 (SOUND but REDUNDANT, severity LOW) |
| TC-00-006 | Tautological — only asserts non-emptiness of `cluster_status` + `nodeId`. A `{"nodeId":"x","ok":false}` body would still pass; cannot catch malformed status payload | Audit §1.2 (WEAK, severity MEDIUM) |
| TC-00-007 | Tautological — `[]` is non-empty as a string; cannot detect missing events stream | Audit §1.2 (WEAK, severity MEDIUM) |
| TC-00-009 | Tautological — `assert_ne "$result" ""` on deploy response; an error JSON `{"error":"..."}` would pass | Audit §1.2 (WEAK, severity MEDIUM); partially mitigated by TC-00-010 + TC-00-013 |
| TC-00-011 | Substring grep against raw listing text — would pass if name appeared in error message or unrelated field | Audit §1.2 (WEAK, severity LOW) |
| TC-00-012 | Counts 4xx-non-route-missing (401/403/400) as "wired"; could mask auth/RBAC misconfig. Mitigated in-suite by TC-00-013's strict 200 | Audit §1.2 (PARTIAL, severity LOW) |

---

## Charter changelog

| Date | Author | Change |
|---|---|---|
| 2026-05-21 | charter author | Initial charter from audit §1.2 |


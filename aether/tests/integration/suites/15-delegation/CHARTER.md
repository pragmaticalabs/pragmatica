# Suite 15-delegation Charter

**Test-ID convention:** `TC-15-DELEGATION-NNN` — zero-padded 3-digit, stable across reorgs.

**Scope:** Leader-pinned control plane. The distributed task-group delegation model this suite originally covered (TaskAssignmentCoordinator round-robin-assigning the METRICS / SCALING / STRATEGIES / DEPLOYMENT / STORAGE / STREAMING groups across cluster members, operator force-reassignment, and assignment recovery after node loss) was **REMOVED**. Control-plane components (CDM, scaling, streaming, …) are now **leader-pinned**: they activate on the elected cluster leader rather than being assigned to task groups across nodes. The `/api/cluster/tasks` and `/api/cluster/tasks/{group}/reassign` endpoints no longer exist (404). This suite now verifies the replacement contract: a single stable leader exists, and the leader-pinned control plane is reachable and functional on it.

The suite name (`15-delegation`) is retained for stability across the runner's suite lists and result history.

---

## Contracts under test

| ID | Contract | Spec citation |
|---|---|---|
| C1 | Cluster reaches canonical "ready" state with a leader elected (`wait_for_cluster_ready`: member count ≥ N, leader elected, active core floor ≥ N-1) | `aether/docs/specs/test-readiness-contract.md §1.1` |
| C2 | Exactly one leader is elected and the view is stable across consecutive mgmt-gateway round-robin reads — no split leader view | leader-pin invariant (single ControlLoop owner) |
| C3 | The leader-pinned CDM is functional: the slice deployment surface (`/api/slices`, via `aether slices`) responds | `aether/docs/specs/unified-deploy-spec.md §3` |
| C4 | The leader-pinned deployment-management surface (`aether deploy list`) responds | `aether/docs/specs/unified-deploy-spec.md §3` |

---

## Test-to-contract map

| TC ID | Test function | File:line | Contract(s) | Severity | Notes |
|---|---|---|---|---|---|
| TC-15-DELEGATION-001 | `test_cluster_ready` | `test-01-control-plane-leader.sh:31` | C1 | smoke | `wait_for_cluster_ready 120` + `assert_ne leader ""` + `assert_ne leader "none"`. The control plane has a home. |
| TC-15-DELEGATION-002 | `test_single_stable_leader` | `test-01-control-plane-leader.sh:45` | C2 | core | Reads `cluster_leader` N+1 consecutive times through the round-robin gateway; fails on any disagreement. Catches the leader-pin split-view failure mode. |
| TC-15-DELEGATION-003 | `test_cdm_functional_on_leader` | `test-01-control-plane-leader.sh:65` | C3 | core | `cluster_slices` rc-checked + non-empty (no `\|\| echo ""` masking). Proves the leader-pinned CDM/ControlLoop is serving. |
| TC-15-DELEGATION-004 | `test_deployment_surface_functional` | `test-01-control-plane-leader.sh:79` | C4 | core | `deploy_list` rc-checked. A non-error response (incl. empty list at steady state) proves the leader-pinned deployment manager is serving. |

---

## Suite-level invariants

- **Pre-conditions:** Cluster A non-destructive, 5 nodes, NODE_COUNT=5. No SSH key required — the suite is entirely read-only against the management API.
- **Side effects:** None. The suite only reads `/api/nodes/status` (leader), `/api/slices`, and the deployment list. It does not deploy, scale, reassign, or kill anything.
- **Cleanup discipline:** Nothing to clean up — read-only.
- **Why no leader-handover test here:** Killing the leader to prove the control plane re-pins is destructive and would disrupt co-running cluster-A suites (cluster A runs up to 4 suites in parallel). That continuity behaviour is covered by the destructive, sequential `02-chaos/test-kill-leader` suite (re-election + control-plane continuity after leader loss).

---

## Removed contracts (pre-rescope, for historical reference)

The following contracts tested the deleted distributed task-assignment machinery and no longer apply: per-group assignment of the 6 canonical task groups (was C3/C4), cross-node distribution of assignments (C5), `assignedTo` referential integrity (C6), per-group functional probes (C8), operator force-reassignment via the removed `/reassign` endpoint (C9/C10), and task-group reassignment on node failure (C11). The associated contract gaps (`[CONTRACT-GAP-15.A..D]`) are closed-as-obsolete with the feature removal.

---

## Charter changelog

| Date | Author | Change |
|---|---|---|
| 2026-05-21 | charter pass | Initial charter — 13 tests catalogued from audit §1.17 |
| 2026-05-27 | control-plane removal | Rescoped: distributed task-group delegation removed; suite now verifies the leader-pinned control plane (single stable leader + CDM/deploy surfaces functional on leader). `test-01-task-assignments.sh` + `test-02-reassignment.sh` replaced by `test-01-control-plane-leader.sh`; suite is now non-destructive. |

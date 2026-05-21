# Suite 15-delegation Charter

**Test-ID convention:** `TC-15-DELEGATION-NNN` — zero-padded 3-digit, stable across reorgs.

**Scope:** Control-plane delegation — task-group assignment (METRICS, SCALING, STRATEGIES, DEPLOYMENT, STORAGE, STREAMING) across cluster members, operator-driven reassignment, and assignment recovery after the host node fails. Validates that the 6 canonical control-plane task groups reach ACTIVE state on assigned nodes and that the assignment coordinator survives node loss.

---

## Contracts under test

| ID | Contract | Spec citation |
|---|---|---|
| C1 | Cluster reaches canonical "ready" state with a leader elected; all task groups reach ACTIVE before delegation tests run | `aether/docs/specs/test-readiness-contract.md §1.1`; `aether/docs/specs/control-plane-delegation-spec.md §4.2` (Initial Election) |
| C2 | GET `/api/cluster/tasks` returns a non-empty payload with an `assignments` field listing the current task→node bindings | `aether/docs/specs/control-plane-delegation-spec.md §3.2` (KV Types — TaskAssignment) |
| C3 | All 6 canonical task groups are assigned: METRICS, SCALING, STRATEGIES, DEPLOYMENT, STORAGE, STREAMING — assignment count ≥ 6 | `aether/docs/specs/control-plane-delegation-spec.md §3.1` (TaskGroup Enum); `§4.1` (Round-Robin Assignment) |
| C4 | Every canonical task group reaches `status=ACTIVE` (per-group enumeration, not just count) | `aether/docs/specs/control-plane-delegation-spec.md §3.5` (TaskGroupActivator); `§5.1` (Normal Activation Flow) |
| C5 | Task groups are distributed across **at least 2 nodes** — not all on the leader (audit §1.17 marks current test as `>= 1`, which violates this contract) | `aether/docs/specs/control-plane-delegation-spec.md §4.1` (Round-Robin); `§4.5` (Leader Fallback as exception, not norm) |
| C6 | Every `assignedTo` field references a non-empty NodeId (stronger contract: NodeId belongs to the live cluster — currently only emptiness is asserted) | `aether/docs/specs/control-plane-delegation-spec.md §3.2` (TaskAssignment record) |
| C7 | DEPLOYMENT group ACTIVE ⇒ CDM functional ⇒ `/api/slices` responds with deploy state | `aether/docs/specs/control-plane-delegation-spec.md §6.4` (DEPLOYMENT Group); `aether/docs/specs/unified-deploy-spec.md §3` |
| C8 | METRICS group ACTIVE ⇒ metrics collection is running (stronger contract: a recent sample exists — currently only the assignment is checked) | `aether/docs/specs/control-plane-delegation-spec.md §6.1` (METRICS Group) |
| C9 | Operator can force-reassign a task group via `POST /api/cluster/tasks/{group}/reassign`; the target node becomes the `assignedTo` for that group within a short budget; status returns to ACTIVE | `aether/docs/specs/control-plane-delegation-spec.md §3.4` (TaskAssignmentCoordinator); operator-reassign route in management-api.md |
| C10 | Operator reassignment of one group does NOT disturb the other 5 groups — they remain ACTIVE | `aether/docs/specs/control-plane-delegation-spec.md §5.4` (Race Condition: Assignment During Deactivation); `§4.5` |
| C11 | Killing the node hosting a task group triggers reassignment to a surviving node within `task-active` budget (60s); the killed NodeId is NOT revived (elastic-cluster model) | `aether/docs/specs/control-plane-delegation-spec.md §4.4` (Node Departure); Wave 7 single-writer fix (commit 9b37f4b5c) |

**Contract gaps surfaced by this audit:**
- `[CONTRACT-GAP-15.A]` — **No "tasks distributed across ≥ K nodes" invariant in spec.** §4.1 describes round-robin but does not state a minimum-distribution invariant. TC-15-DELEGATION-005 asserts `≥ 1` because there's no spec sentence to point a stricter check at. Add: "at steady state with N ≥ 2 nodes, assignments MUST span ≥ 2 distinct NodeIds; leader-fallback is permitted only transiently during reconciliation".
- `[CONTRACT-GAP-15.B]` — **`assignedTo` referential integrity is not pinned.** §3.2 defines TaskAssignment but does not say "`assignedTo` MUST be a live, ON_DUTY NodeId". A stale-but-non-empty assignment (e.g. NodeId of a recently-decommissioned node) passes TC-15-DELEGATION-006.
- `[CONTRACT-GAP-15.C]` — **"Functional" probes for METRICS/SCALING/STRATEGIES/STORAGE/STREAMING task groups are absent.** Only DEPLOYMENT has a paired-functional check (CDM serves `/api/slices`). The other 5 groups have ACTIVE-status assertions only; no "is this group actually doing its job?" probe. Add positive-functional contracts per group (e.g. METRICS: recent sample on `/api/metrics`; STORAGE: snapshot epoch advances; STREAMING: subscriber offset advances under publish).
- `[CONTRACT-GAP-15.D]` — **Worker-pool / passive-LB delegation is not represented.** The 6 canonical task groups are control-plane only; if RC2 introduces delegated worker pools or passive-LB membership, the spec needs to enumerate which groups are control-plane-only vs data-plane-shared.

---

## Test-to-contract map

| TC ID | Test function | File:line | Contract(s) | Severity | Notes |
|---|---|---|---|---|---|
| TC-15-DELEGATION-001 | `test_cluster_ready` | `test-01-task-assignments.sh:13` | C1 | smoke | wait_for_cluster_ready 120 + `assert_ne leader ""` + soft `wait_for_all_tasks_active` (warn on miss; matches pattern in other suites). |
| TC-15-DELEGATION-002 | `test_tasks_api_returns_data` | `test-01-task-assignments.sh:25` | C2 | core | `assert_ne ""` + `assert_contains "assignments"`. SOUND. |
| TC-15-DELEGATION-003 | `test_all_groups_assigned` | `test-01-task-assignments.sh:35` | C3 | core | `task_assignment_count >= 6`. SOUND. |
| TC-15-DELEGATION-004 | `test_all_groups_active` | `test-01-task-assignments.sh:44` | C4 | core | wait_for ≥ 6 ACTIVE entries, then per-group `assert_eq status ACTIVE` for METRICS, SCALING, STRATEGIES, DEPLOYMENT, STORAGE, STREAMING. **Strong per-group enumeration prevents "≥ 6 but wrong groups" failure mode.** SOUND. |
| TC-15-DELEGATION-005 | `test_tasks_distributed` | `test-01-task-assignments.sh:60` | C5 | core | **GREEN-STICKER (audit §1.17 HIGH; §2.3 row 6): title says "not all on leader" and comment says "should have ≥ 2", but assertion is `assert_ge unique_nodes 1` — i.e. all-on-leader PASSES.** The original distribution claim is structurally untestable as written. Either tighten to `>= 2` or rename. Tracked as `[CONTRACT-GAP-15.A]`. |
| TC-15-DELEGATION-006 | `test_assignments_point_to_valid_nodes` | `test-01-task-assignments.sh:94` | C6 | regression-net | Audit §1.17 LOW — grep `"assignedTo":""` and fail if found. SOUND for empty-string case. Does NOT verify the NodeId is in the live cluster (a stale-but-non-empty assignment passes). Title overpromises. Tracked as `[CONTRACT-GAP-15.B]`. |
| TC-15-DELEGATION-007 | `test_deployment_group_functional` | `test-01-task-assignments.sh:110` | C7 | core | `status == ACTIVE` + `cluster_slices` rc + non-empty. Prior `\|\| echo ""` RESOLVED (audit §1.17). SOUND. |
| TC-15-DELEGATION-008 | `test_metrics_group_functional` | `test-01-task-assignments.sh:133` | C8 | regression-net | Audit §1.17 MEDIUM (WEAK) — `status == ACTIVE` + `task_group_node METRICS` non-empty. Proves an assignment exists, NOT that collection is actually running. No metric value, no sample timestamp. Tracked as `[CONTRACT-GAP-15.C]`. |
| TC-15-DELEGATION-009 | `test_prerequisite` | `test-02-reassignment.sh:31` | C1 | smoke | wait_for_cluster_ready + `wait_for` ACTIVE count ≥ 6 + `log_pass`. SOUND. |
| TC-15-DELEGATION-010 | `test_operator_reassign` | `test-02-reassignment.sh:42` | C9 | core | Discovers current node, picks a different one, `reassign_task_group`, `wait_for_task_assigned METRICS target 30`, `assert_eq new_node target`. Inline comment addresses the stale-ACTIVE race. SOUND. |
| TC-15-DELEGATION-011 | `test_reassignment_status_active` | `test-02-reassignment.sh:73` | C9 | core | `assert_eq status ACTIVE` post-reassign. SOUND. |
| TC-15-DELEGATION-012 | `test_other_groups_unaffected` | `test-02-reassignment.sh:82` | C10 | core | Per-group `assert_eq status ACTIVE` for the other 5 groups. SOUND. |
| TC-15-DELEGATION-013 | `test_node_failure_reassignment` | `test-02-reassignment.sh:94` | C11 | core | Skips if no SSH key; identifies SCALING host; pre-reassigns to non-leader if needed; captures topology baseline; `kill_node`; **event-driven `wait_for_node_departure` (90s)** replaces prior hardcoded `sleep 5`; `wait_for_task_active SCALING 60`; asserts non-empty + ACTIVE; **deliberately does NOT call `start_node`** per Wave 7 single-writer fix (commit 9b37f4b5c, comment at L154-159). Prior `sleep 5` and `start_node` revival both RESOLVED (audit §1.17). RC1-blocker #27 CLOSED in 3b217a4ab (per task brief). |

---

## Suite-level invariants

- **Pre-conditions:** Cluster A is NOT suitable for `test-02-reassignment.sh` (kills a node — must run on Cluster B). Cluster A is fine for `test-01-task-assignments.sh`. NODE_COUNT=5 assumed so reassignment has ≥ 2 candidate targets. SSH key required for `test_node_failure_reassignment` (skips cleanly if absent — only test in this suite that depends on remote SSH).
- **Side effects:**
  - `test-01-task-assignments.sh` is read-only — only queries `/api/cluster/tasks` and `/api/slices`.
  - `test-02-reassignment.sh` mutates assignment state: explicitly reassigns METRICS to a non-leader, then kills the SCALING host. **Killed NodeId is NOT revived** per Wave 7 single-writer fix — CTM provisions a fresh replacement; restoration to baseline must run a CTM provision cycle (240s wait inside the test).
- **Cleanup discipline:** The kill-then-no-revive pattern means subsequent suites that assume "same 5 NodeIds" must `restore_cluster_baseline`. Reassignment of METRICS is left in place (operator-action is idempotent against subsequent reassign calls).

---

## Known limitations

| TC ID | Limitation | Tracking |
|---|---|---|
| TC-15-DELEGATION-005 | "Tasks distributed across ≥ 2 nodes" actually asserts `≥ 1` — name/check mismatch (audit §2.1 census row 5) | audit §1.17 HIGH / §2.3 row 6; `[CONTRACT-GAP-15.A]` |
| TC-15-DELEGATION-006 | Only checks `assignedTo != ""`; stale-but-non-empty NodeIds pass | audit §1.17 LOW; `[CONTRACT-GAP-15.B]` |
| TC-15-DELEGATION-008 | METRICS "functional" only verifies assignment exists, not sample advancement | audit §1.17 MEDIUM; `[CONTRACT-GAP-15.C]` |
| (suite) | SCALING / STRATEGIES / STORAGE / STREAMING groups have no functional probe — only ACTIVE-status assertions | `[CONTRACT-GAP-15.C]` — RC2 |
| (suite) | Worker-pool / passive-LB delegation not modelled | `[CONTRACT-GAP-15.D]` — RC2 |
| TC-15-DELEGATION-013 | Final assertion `assert_ne new_node ""` is weaker than "SCALING is on a node that is currently ON_DUTY and reachable"; documented rationale: CTM may reuse the logical id at a fresh port | audit §1.17 — RESOLVED; RC1-blocker #27 CLOSED in 3b217a4ab |

---

## Charter changelog

| Date | Author | Change |
|---|---|---|
| 2026-05-21 | charter pass | Initial charter — 13 tests catalogued from audit §1.17 |

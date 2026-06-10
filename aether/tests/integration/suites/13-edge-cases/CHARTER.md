# Suite 13-edge-cases Charter

**Test-ID convention:** `TC-13-EDGE-CASES-NNN` — zero-padded 3-digit, stable across reorgs.

**Scope:** Operational edge cases — concurrent deploys racing each other, the disruption-budget gate blocking unsafe drains, stale-route cleanup after a routes-hosting node dies, and worker-join accounting (a 6th node past coreMax must be invisible to every core denominator). These are the "what happens when the user does something awkward" tests.

---

## Contracts under test

| ID | Contract | Spec citation |
|---|---|---|
| C1 | Cluster reaches canonical "ready" state with ≥ 3 nodes (suite minimum) before any edge-case is exercised | `aether/docs/specs/test-readiness-contract.md §1.1` |
| C2 | Two distinct blueprints can be deployed concurrently and both publish operations return strict 2xx | `aether/docs/specs/unified-deploy-spec.md §3` (deploy lifecycle); `aether/docs/specs/slice-lifecycle.md §2`; `aether/docs/specs/streaming-resources-spec.md §4` (publish semantics) |
| C3 | After concurrent deploys, both blueprints' slices are visible (non-empty `slices` payload with both artifact GAVs present) and at least one instance is ACTIVE | `aether/docs/specs/unified-deploy-spec.md §3`; `aether/docs/specs/slice-lifecycle.md §2` |
| C4 | Concurrent deploys do not break cluster health (`assert_cluster_healthy` post-condition) | `aether/docs/specs/membership-architecture-v2-spec.md §3.3` |
| C5 | Disruption-budget gate accepts the first N-K drains (where K = quorum-preserving minimum) and rejects the (N-K+1)-th with 409 | `[CONTRACT-GAP-13.A]` — no dedicated disruption-budget spec; behavioural contract pinned by `aether/docs/reference/management-api.md` drain endpoint + `membership-architecture-v2-spec.md §5.1` (ON_DUTY+OperatorDecommission graceful drain) |
| C6 | A drained node can be reactivated via the activate endpoint; cluster returns to healthy | `membership-architecture-v2-spec.md §5.1` (DECOMMISSIONED→ON_DUTY revival within TTL) |
| C7 | Killing a node that hosts active routes triggers a generation advance (slice-routing state turnover) | `aether/docs/specs/slice-lifecycle.md §3` (route generation); `unified-deploy-spec.md §3` |
| C8 | After route cleanup, app routes return ZERO 502/504 over a 10-probe window — the killed node's stale entries are pruned from the route table | `aether/docs/specs/slice-lifecycle.md §3`; `aether/docs/specs/membership-architecture-v2-spec.md §3.3` (reconciler) |
| C9 | After kill-and-reconverge, cluster returns to N=5 nodes (CTM provisions replacement) | `membership-architecture-v2-spec.md §3.3`; `quic-transport-spec.md §3.7` |
| C10 | A 6th node joining past coreMax with `AETHER_ROLE=worker` is classified WORKER (FSM descriptor role) and reaches FSM Member | `aether/docs/specs/cluster-topology-overhaul-spec.md §5 Wave 2 (A8, W3, W4, Q3)` |
| C11 | Worker presence does not perturb the CORE quorum domain: generation core membership stays 5, cluster stays quorate/healthy | `cluster-topology-overhaul-spec.md Wave 2 (W1)` |
| C12 | A core-kill with a worker present still heals to 5 COREs — the worker never fills the deficit; the replacement is assigned CORE (explicit role stamping end-to-end) | `cluster-topology-overhaul-spec.md Wave 2 (W2, W3, W4)` |
| C13 | CORE_ONLY slice placement never lands an instance on a worker-role node | `cluster-topology-overhaul-spec.md Wave 2 (W6)` |

**Contract gaps surfaced by this audit:**
- `[CONTRACT-GAP-13.A]` — **No dedicated disruption-budget spec** exists. Contract C5 is pinned only by the management-api reference page and inferred from the FSM `OperatorDecommission` row. The 2xx-or-409 dual-acceptance behaviour on the second drain (TC-13-EDGE-CASES-006) cannot be formally validated against a spec — needs a `disruption-budget-spec.md` clarifying: (a) is `disable_auto_heal` synchronous? (b) what window admits the race-tolerant 409? (c) is the budget calculated against `cluster_member_count` or against `desiredSize`?
- `[CONTRACT-GAP-13.B]` — Stale-route cleanup spec section is implicit. `slice-lifecycle.md §3` describes route generation but does not pin the strict invariant "killing a routes-host advances the generation within K seconds" — TC-13-EDGE-CASES-010 demotes the quiesce-timeout to warn for this reason.
- `[CONTRACT-GAP-13.C]` — KV-store pruning of stale slice records after a kill is not characterised; TC-13-EDGE-CASES-012 reduces to "endpoint up" because no spec sentence pins what the pruned shape should look like.

---

## Test-to-contract map

| TC ID | Test function | File:line | Contract(s) | Severity | Notes |
|---|---|---|---|---|---|
| TC-13-EDGE-CASES-001 | `test_cluster_ready` | `test-concurrent-deploys.sh:17` | C1 | smoke | Setup: wait_for_cluster_ready + push+deploy two distinct blueprints. Multiple soft warns on quiesce timing; acceptable for inherited-churn from prior suite. |
| TC-13-EDGE-CASES-002 | `test_initial_slice_count` | `test-concurrent-deploys.sh:33` | C3 | smoke | Audit §1.15 WEAK — `assert_ne slices ""` only; title says "count" but no count is asserted. Semantic only. |
| TC-13-EDGE-CASES-003 | `test_concurrent_deploy` | `test-concurrent-deploys.sh:39` | C2 | core | Strict 2xx on BOTH parallel publishes. Prior `< 500` RESOLVED (audit §1.15). |
| TC-13-EDGE-CASES-004 | `test_both_blueprints_visible` | `test-concurrent-deploys.sh:105` | C3 | core | **GREEN-STICKER (audit §1.15 HIGH; §2.2 row N/A but §2.3 row 3): empty slices payload `log_pass`'d as "endpoint responds" — subject swap.** RC1-blocker #22 reportedly CLOSED in cbc1f50e3 — verify the empty-path branch now `log_fail`s. |
| TC-13-EDGE-CASES-005 | `test_slices_active_after_concurrent_deploy` | `test-concurrent-deploys.sh:128` | C3 | core | `wait_for_slices_active 1 120` + `slices_total_instances > 0`. SOUND. |
| TC-13-EDGE-CASES-006 | `test_artifact_isolation` | `test-concurrent-deploys.sh:135` | C3 | core | Grep for BOTH artifact GAVs (`test-echo` and `test-persistence`) in `/api/slices`. SOUND. |
| TC-13-EDGE-CASES-007 | `test_cluster_healthy_after_concurrent_deploys` | `test-concurrent-deploys.sh:159` | C4 | core | `assert_cluster_healthy`. SOUND. |
| TC-13-EDGE-CASES-008 | `test_cluster_ready` | `test-disruption-budget.sh:9` | C1 | smoke | Wait_for_cluster_ready + `cluster_member_count >= 3` + `disable_auto_heal` (EXIT trap re-enables). Auto-heal disable is critical to make subsequent drain tests deterministic. |
| TC-13-EDGE-CASES-009 | `test_drain_first_node_allowed` | `test-disruption-budget.sh:42` | C5 | core | curl POST `/api/nodes/drain/{id}`; strict 2xx pass / explicit `log_fail` otherwise. TODO comment about known 503 mode but no silent demotion. |
| TC-13-EDGE-CASES-010 | `test_drain_second_node_allowed` | `test-disruption-budget.sh:81` | C5 | core | Audit §1.15 MEDIUM — accepts 2xx OR 409. Race-tolerant comment claims auto-heal interleave; but prior test disabled auto-heal, so the 409 path may mask a real budget-misallocation bug. Tighten to "2xx only" once `disable_auto_heal` is verified synchronous. |
| TC-13-EDGE-CASES-011 | `test_drain_beyond_budget_rejected` | `test-disruption-budget.sh:102` | C5 | core | Strict 409 only. Prior "every outcome accepted" RESOLVED (audit §1.15). |
| TC-13-EDGE-CASES-012 | `test_quorum_preserved` | `test-disruption-budget.sh:128` | C5 | regression-net | `assert_cluster_healthy` — indirect quorum check (health endpoint, not quorum math). |
| TC-13-EDGE-CASES-013 | `test_reactivate_nodes` | `test-disruption-budget.sh:132` | C6 | core | Iterates drained nodes, calls `activate_node` with stderr capture (prior `2>/dev/null \|\| true` RESOLVED, audit §1.15). Cleanup-class step — ultimate failure surfaces as warn, not test fail. Defensible per audit. |
| TC-13-EDGE-CASES-014 | `test_cluster_ready` | `test-stale-route-cleanup.sh:12` | C1 | smoke | wait_for_cluster_ready + push+deploy + `wait_for_slices_active`. |
| TC-13-EDGE-CASES-015 | `test_slices_deployed` | `test-stale-route-cleanup.sh:24` | C3 | smoke | `wait_for_slices_active 1 120` + `slices_total_instances > 0`. |
| TC-13-EDGE-CASES-016 | `test_app_routes_reachable` | `test-stale-route-cleanup.sh:31` | C7 | core | `app_route_wired` against the prefixed APP path on APP_ENDPOINT. Prior wrong-endpoint (probing mgmt /api/status) RESOLVED, audit §1.15. |
| TC-13-EDGE-CASES-017 | `test_kill_node_hosting_routes` | `test-stale-route-cleanup.sh:48` | C7 | core | **WEAK (audit §1.15 MEDIUM): `await_generation_quiesced` only warns on timeout; the trailing `log_pass "Route cleanup fenced by generation advance"` fires unconditionally.** A non-advancing generation PASSES. Hardcoded `sleep 5` is also a smell. Tighten to fail on quiesce-timeout. |
| TC-13-EDGE-CASES-018 | `test_no_502_504_after_cleanup` | `test-stale-route-cleanup.sh:83` | C8 | core | 10 × `http_status` on `/api/echo/health`; `assert_eq count 0` for 502/504. Prior wrong-endpoint RESOLVED, audit §1.15. 10 polls @ 1Hz may miss a brief window — adequate for catch-stale, weak for catch-flaky. |
| TC-13-EDGE-CASES-019 | `test_kv_store_routes_clean` | `test-stale-route-cleanup.sh:102` | C8 | regression-net | Audit §1.15 WEAK — `assert_ne slices ""` only; doesn't verify the killed-node's slice records are pruned. Title overpromises. Tracked as `[CONTRACT-GAP-13.C]`. |
| TC-13-EDGE-CASES-020 | `test_recovery_complete` | `test-stale-route-cleanup.sh:109` | C9 | core | `wait_for_node_count 5 90` + `assert_cluster_healthy`. SOUND. |
| TC-13-EDGE-CASES-021 | `test_cluster_ready` | `test-worker-join-accounting.sh` | C1 | smoke | Baseline 5-core cluster; re-enables auto-heal if a prior suite left it off. Docker-mode only (skip_test in CLOUD_MODE). |
| TC-13-EDGE-CASES-022 | `test_worker_joins_as_worker` | `test-worker-join-accounting.sh` | C10 | core | Raw `docker run` of a 6th node with `AETHER_ROLE=worker` + 3-part PEERS from live core ids; polls topology fsmMembers for role=worker + FSM Member. |
| TC-13-EDGE-CASES-023 | `test_quorum_unchanged_by_worker` | `test-worker-join-accounting.sh` | C11 | core | `cluster_member_count == 5` (generation member set is FSM coreMembers-derived, worker excluded) + quorate + healthy. |
| TC-13-EDGE-CASES-024 | `test_core_kill_heals_to_five_cores` | `test-worker-join-accounting.sh` | C12 | core | Kills a non-leader CORE (worker excluded from victim pool); `wait_for_node_count 5`; asserts the worker is still role=worker post-heal. |
| TC-13-EDGE-CASES-025 | `test_replacement_assigned_core` | `test-worker-join-accounting.sh` | C12 | core | Finds the `aether.provisioned-by=ctm` container; asserts FSM descriptor role=core AND `aether.role=core` label (the W4 explicit-role chain). |
| TC-13-EDGE-CASES-026 | `test_core_only_placement_excludes_worker` | `test-worker-join-accounting.sh` | C13 | core | Deploys test-echo (default CORE_ONLY); asserts no slice instance carries the worker's nodeId. |
| TC-13-EDGE-CASES-027 | `test_cleanup_baseline` | `test-worker-join-accounting.sh` | — | cleanup | Removes the worker container + `restore_cluster_baseline` (warn-on-failure cleanup-class step). |

---

## Suite-level invariants

- **Pre-conditions:** Concurrent-deploys runs against Cluster A. Disruption-budget and stale-route-cleanup are **destructive** — must run against Cluster B (kills + `docker network` not safe on the parallel cluster). `NODE_COUNT >= 3` is the absolute minimum for the disruption-budget tests; the suite runs at 5 in practice.
- **Side effects:**
  - Concurrent-deploys leaves two blueprints deployed (`test-echo` + `test-persistence`); restore via `restore_cluster_baseline` in EXIT trap if next suite needs a clean slate.
  - Disruption-budget disables CTM auto-heal at suite start (`disable_auto_heal`) and **always** re-enables in EXIT trap. The disabled-window is the determinism gate for the 409 assertion (audit §1.15 — see `[CONTRACT-GAP-13.A]` for the synchronous-vs-async question).
  - Stale-route-cleanup kills a non-leader node; killed NodeId stays decommissioned (elastic-cluster model); CTM provisions a replacement.
- **Cleanup discipline:** All three test files have EXIT traps. Auto-heal re-enable is the highest-priority cleanup — leaving it disabled would silently break every downstream suite.

---

## Known limitations

| TC ID | Limitation | Tracking |
|---|---|---|
| TC-13-EDGE-CASES-002 | "Initial slice count" name implies count but only checks non-empty | audit §1.15 LOW |
| TC-13-EDGE-CASES-004 | RC1-blocker #22 reportedly closed; verify empty-payload branch now `log_fail`s | RC1-blocker #22 CLOSED in cbc1f50e3 (per task brief) |
| TC-13-EDGE-CASES-010 | 2xx OR 409 dual acceptance — needs `disable_auto_heal` synchronicity confirmation | audit §1.15 MEDIUM; `[CONTRACT-GAP-13.A]` |
| TC-13-EDGE-CASES-017 | Quiesce-timeout demoted to warn → unconditional `log_pass` for generation advance | audit §1.15 MEDIUM; `[CONTRACT-GAP-13.B]` |
| TC-13-EDGE-CASES-018 | 10 probes @ 1Hz catches stale but is weak against flaky/intermittent windows | audit §1.15 LOW |
| TC-13-EDGE-CASES-019 | "KV store routes clean" reduces to "endpoint up"; pruning shape not asserted | audit §1.15 LOW; `[CONTRACT-GAP-13.C]` |

---

## Charter changelog

| Date | Author | Change |
|---|---|---|
| 2026-05-21 | charter pass | Initial charter — 20 tests catalogued from audit §1.15 |
| 2026-06-10 | wave-2 worker accounting | Added `test-worker-join-accounting.sh` (TC-021–027, contracts C10–C13) — the cluster-topology-overhaul spec Wave 2 Docker gate (worker invisible to quorum/heal/role-assignment/CORE_ONLY denominators). Docker-mode only; skips in CLOUD_MODE. |

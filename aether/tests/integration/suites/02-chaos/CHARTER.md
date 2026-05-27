# Suite 02-chaos Charter

**Test-ID convention:** `TC-02-NNN` where `NNN` is a zero-padded 3-digit index assigned in `run_test` invocation order across all scripts in the suite. Scripts run alphabetically (`test-*.sh` glob): `test-joining-window-kill.sh` → `test-kill-leader.sh` → `test-kill-multiple.sh` → `test-kill-node.sh` → `test-kill-under-load.sh` → `test-self-drain-quorum-loss.sh`. Numbers are stable across reorganisations; do not reuse retired IDs.

**Charter purpose:** Destructive failure-injection coverage on cluster B. Exercises SWIM failure detection, TransportUnreachable detection (`membership-architecture-spec.md §16` row S01), CTM auto-heal, leader re-election, multi-kill quorum boundaries, in-flight load resilience, and self-drain on quorum loss (`membership-architecture-spec.md §16` rows S19+S20).

---

## Contracts under test

| ID | Contract | Spec citation |
|---|---|---|
| C1 | Cluster reaches the canonical "ready" state with N members, leader elected, ≥N-1 active cores | `aether/docs/specs/test-readiness-contract.md §1.1` |
| C2 | Cluster reaches NORMAL phase (gates SWIM cold-boot suppression of NODE_FAILED events) | `aether/docs/specs/cluster-membership-fsm-spec.md §I5 (Bootstrap-safe — subsumes cold-boot suppression)` |
| C3 | Non-leader kill → surviving nodes observe `NODE_LEFT`/`NODE_FAILED` via `/api/events` within budget | `aether/docs/specs/membership-architecture-spec.md §16 (S02 row, ON_DUTY → DECOMMISSIONED)` |
| C4 | Leader kill → new leader elected within ~150s; new leader id differs from old leader id | `aether/docs/specs/membership-architecture-spec.md §16 (S18 row, leader kill + re-election)` + `§4.6 (Layer 5 — Leader Election)`; re-election timing SLA `[CONTRACT-GAP]` (no canonical leader-election spec pins the ~150s budget; election FSM behavior is in `membership-architecture-spec.md §4.6` / `§7.4`) |
| C5 | Cluster maintains quorum after kill (`member_count` ≥ quorum floor) and `/api/health` reports `"healthy"` | `aether/docs/specs/test-readiness-contract.md §2.1 (cluster_member_count — quorum floor)` + `§3 (api/health row)` |
| C6 | CTM auto-heal restores cluster to exactly N members within budget after kill | `aether/docs/specs/slot-based-membership-convergence-spec.md §2 (The Invariant — exactly S slots, never more than S)` |
| C7 | Two staggered non-leader kills → cluster survives with quorum (`member_count >= 3`), eventually auto-heals to N | `aether/docs/specs/membership-architecture-spec.md §16 (S02 row, staggered)` + C6 |
| C8 | Kill under sustained load → error rate stays below the chaos-tier 10.0% threshold | `aether/docs/specs/test-readiness-contract.md §4` (Simultaneous chaos tier = 10.0%) |
| C9 | JOINING-window TransportUnreachable: replacement R provisioned by CTM, killed before SWIM HEALTHY, reaches `DECOMMISSIONED` in KV within ≤25s budget | `aether/docs/specs/membership-architecture-spec.md §16 (S01 row — Put(DECOMMISSIONED) within ≤25s)`. **Budget drift:** spec S01 = ≤25s; live `test_decommission_within_budget` currently relaxed to 90s, flagged #231 (forward-decommission slowness). Contract number unchanged |
| C10 | Smoking-gun log signature on survivors carries `reason=transport-failure` OR `reason=swim-faulty` for R's NodeId | `aether/docs/specs/membership-architecture-spec.md §16 (S01 row — TransportUnreachable, ungated)`. Note: the `reason=transport-failure` / `reason=swim-faulty` token strings are the code-level signatures, not literal spec text `[CONTRACT-GAP]` |
| C11 | `pick_non_leader()` MUST exclude decommissioned NodeIds (single-writer + MembershipView projection) | `aether/docs/specs/cluster-membership-fsm-spec.md §I2 (Single-writer)` |
| C12 | Quorum loss (3 of 5 killed simultaneously) → each survivor self-drains and exits within 8s threshold + 30s grace + 7s headroom = 45s budget | `aether/docs/specs/membership-architecture-spec.md §16 (S19 row)` + `§16.1 (Self-Drain Protocol — "self-drains when it cannot reach (N/2)+1 peers")`; self-drain *spec proper* `[CONTRACT-GAP]` (the `performExit` state machine lives in code) |
| C13 | Self-drained survivor exits with `Runtime.halt(2)` (exit code exactly 2; distinguishes from clean=0, SIGKILL=137, SIGTERM=143) | `aether/docs/specs/membership-architecture-spec.md §16 (S19 row)` + `§16.1 (Runtime.getRuntime().halt(2))` |
| C14 | `SELF_DRAIN_INITIATED` event published from survivor at `ACTIVE → DRAINING` CAS (soft signal — Rabia publish may lose race vs `Runtime.halt(2)`) | `aether/docs/specs/membership-architecture-spec.md §16 (S19 row)`; T3.1 of `aether/docs/specs/test-readiness-contract.md §6 (resolved)` |
| C15 | No KV/consensus writes from survivor after drain trigger (negative assertion; compile-time guard is the canonical contract) | `aether/docs/specs/membership-architecture-spec.md §16.1 (Key invariants — no KV/consensus writes during self-drain)`; self-drain spec proper `[CONTRACT-GAP]` (asserted via `SelfDrainCoordinatorTest.noConsensusOrKvImports` unit test) |
| C16 | Post-self-drain restart → cluster recovers to N ON_DUTY healthy cores within 60s | `aether/docs/specs/membership-architecture-spec.md §16 (S20 row — ON_DUTY within ≤60s)` |

---

## Test-to-contract map

### test-joining-window-kill.sh (membership-architecture-spec.md §16 S01)

| TC ID | Test function | File:line | Contract(s) | Severity | Notes |
|---|---|---|---|---|---|
| TC-02-001 | `test_initial_state` | `test-joining-window-kill.sh:281` | C1, C2 | smoke | `wait_for_phase NORMAL` is warn-then-continue (DEMOTION) + pre-priming label snapshot |
| TC-02-002 | `test_prime_replacement_via_kill` | `test-joining-window-kill.sh:301` | C3 (priming setup) | smoke | Pick non-leader; `kill_node`; record victim — setup step for S01 |
| TC-02-003 | `test_catch_replacement_in_joining_window` | `test-joining-window-kill.sh:316` | C9 (setup half) | core | Label-set diff to discover R; `wait_for_replacement_in_kv` JOINING/ON_DUTY; kill R by label. JOINING preferred but ON_DUTY widened (documented) |
| TC-02-004 | `test_decommission_within_budget` | `test-joining-window-kill.sh:368` | C9 (timing assertion) | core | `wait_for_kv_decommissioned` ≤25s strict budget against KV-direct `/api/nodes/lifecycle/<R>` |
| TC-02-005 | `test_transport_unreachable_event_logged` | `test-joining-window-kill.sh:394` | C10 | core | docker-logs grep for `reason=transport-failure|reason=swim-faulty` + R's NodeId on survivors; widened acceptance |
| TC-02-006 | `test_pick_non_leader_excludes_decommissioned` | `test-joining-window-kill.sh:411` | C11 | regression-net | Hygiene: if candidates exist, R MUST be excluded; empty-candidate path is SKIP-via-WARN |

### test-kill-leader.sh

| TC ID | Test function | File:line | Contract(s) | Severity | Notes |
|---|---|---|---|---|---|
| TC-02-007 | `test_initial_state` | `test-kill-leader.sh:11` | C1, C2 | smoke | Same phase-warn demotion as siblings |
| TC-02-008 | `test_kill_leader_and_reelect` | `test-kill-leader.sh:21` | C3, C4 | core | Strict `wait_for_node_departure 90` (event-driven) + strict `wait_for_leader 150` (fail-closed, comment notes prior `\|\| log_warn` was removed) + 3 strict assertions pinning new leader identity ≠ old |
| TC-02-009 | `test_cluster_has_quorum` | `test-kill-leader.sh:63` | C5 | core | `member_count >= 4` floor; doesn't verify `cluster.quorate` field directly |
| TC-02-010 | `test_health_with_4_nodes` | `test-kill-leader.sh:69` | C5 | core | `aether_field health status == "healthy"` |
| TC-02-011 | `test_auto_heal` | `test-kill-leader.sh:75` | C6 | core | `wait_for_node_count 5 180` + strict `assert_eq 5` |

### test-kill-multiple.sh

| TC ID | Test function | File:line | Contract(s) | Severity | Notes |
|---|---|---|---|---|---|
| TC-02-012 | `test_initial_state` | `test-kill-multiple.sh:11` | C1, C2 | smoke | Same phase-warn demotion |
| TC-02-013 | `test_kill_two_nodes` | `test-kill-multiple.sh:21` | C3, C7 | core | Two strict event-driven `wait_for_node_departure 90` barriers; quiescence to 5 is warn-then-continue (DEMOTION); final `assert_ge count 3` |
| TC-02-014 | `test_quorum_maintained` | `test-kill-multiple.sh:66` | C5 | core | `aether_field health status == "healthy"` — timing-sensitive (depends on prior quiescence) |
| TC-02-015 | `test_leader_still_active` | `test-kill-multiple.sh:72` | C4 | regression-net | Existence check only (`assert_ne ""`) — name overstates; see Known limitations |
| TC-02-016 | `test_auto_heal` | `test-kill-multiple.sh:78` | C6 | core | `wait_for_node_count 5 240` + strict `assert_eq 5` |

### test-kill-node.sh

| TC ID | Test function | File:line | Contract(s) | Severity | Notes |
|---|---|---|---|---|---|
| TC-02-017 | `test_initial_state` | `test-kill-node.sh:11` | C1, C2 | smoke | Same phase-warn demotion |
| TC-02-018 | `test_kill_non_leader` | `test-kill-node.sh:21` | C3 | core | Strict event-driven `wait_for_node_departure 60`; replaces historical `sleep 10` |
| TC-02-019 | `test_leader_unchanged` | `test-kill-node.sh:48` | C4 | regression-net | Existence check only — name overstates; see Known limitations |
| TC-02-020 | `test_health_with_4_nodes` | `test-kill-node.sh:54` | C5 | core | `aether_field health status == "healthy"` |
| TC-02-021 | `test_auto_heal` | `test-kill-node.sh:60` | C6 | core | `wait_for_node_count 5 180` + strict `assert_eq 5` |

### test-kill-under-load.sh

| TC ID | Test function | File:line | Contract(s) | Severity | Notes |
|---|---|---|---|---|---|
| TC-02-022 | `test_initial_state` | `test-kill-under-load.sh:19` | C1, C2 | smoke | Same phase-warn demotion |
| TC-02-023 | `test_kill_during_load` | `test-kill-under-load.sh:29` | C3, C8 | core | `start_load` against `/api/echo/health`, 5s legitimate load-ramp sleep, kill non-leader, strict event barrier, `assert_error_rate_below 10.0` |
| TC-02-024 | `test_cluster_survives` | `test-kill-under-load.sh:77` | C5 | core | `aether_field health status == "healthy"` post-load |
| TC-02-025 | `test_auto_heal` | `test-kill-under-load.sh:83` | C6 | core | `wait_for_node_count 5 180` + strict `assert_eq 5` |

### test-self-drain-quorum-loss.sh (membership-architecture-spec.md §16 S19+S20)

| TC ID | Test function | File:line | Contract(s) | Severity | Notes |
|---|---|---|---|---|---|
| TC-02-026 | `test_initial_state` | `test-self-drain-quorum-loss.sh:243` | C1, C2 | smoke | `cluster_active_core_count == 5` (strict ON_DUTY) + soft phase-warn |
| TC-02-027 | `test_pick_victims_and_kill_three_simultaneously` | `test-self-drain-quorum-loss.sh:258` | C12 (kill primitive) | core | Single `remote_exec docker kill v1 v2 v3` — three SIGKILLs within ms; survivor_count == 2 strict |
| TC-02-028 | `test_survivors_self_drain_and_exit` | `test-self-drain-quorum-loss.sh:316` | C12 | core | Per-survivor `wait_for_container_exit` with remaining-budget arithmetic; explicit fail if either survivor doesn't exit within 45s |
| TC-02-029 | `test_survivor_exit_codes_are_two` | `test-self-drain-quorum-loss.sh:368` | C13 | core | `docker inspect ExitCode == 2` on each survivor; distinguishes from 0/137/143 |
| TC-02-030 | `test_drain_trigger_log_signature_present` | `test-self-drain-quorum-loss.sh:386` | C14 | regression-net | `wait_for_self_drain_event` per survivor; warn-then-pass DEMOTION (documented — Rabia publish race) |
| TC-02-031 | `test_no_kv_writes_after_drain_trigger` | `test-self-drain-quorum-loss.sh:422` | C15 | regression-net | WARN-ONLY (cannot fail): match → `log_warn`; no match → `log_pass`. Real guard is the compile-time `noConsensusOrKvImports` unit test |
| TC-02-032 | `test_cluster_recovers_to_five_on_duty` | `test-self-drain-quorum-loss.sh:449` | C16 | core | `restart_all_nodes` strict + `wait_for "5 ON_DUTY healthy cores" 60` strict + `assert_cluster_healthy` |

---

## Suite-level invariants

- **Pre-conditions:** Cluster B (destructive, `restart: "no"` policy — `docker kill` is authoritative). 5 nodes baseline. `CLUSTER_ID`, `NODE_COUNT`, `MGMT_ENTRY_POINT`, `TARGET_HOST`, `API_KEY` exported. nginx `aether-b-mgmt-gateway` sidecar owns `MGMT_ENTRY_POINT` host port 5160 with `proxy_next_upstream` — survives any single-core failure (so kill-leader no longer strands the harness).
- **Side effects:** Kills 1-3 containers per test; CTM provisions replacement containers (different host ports than the fixed compose ordinals, see test-readiness-contract.md §1.1 Property 4 retirement). State ferried between `run_test` subshells via `/tmp/*.$$` files (S01 + S19 scripts).
- **Cleanup discipline:** Every kill-* script ends with a `cleanup()` calling `restore_cluster_baseline` (re-enables auto-heal, resets CTM circuit breaker, reactivates DRAINING nodes, scales to NODE_COUNT, waits for N ON_DUTY healthy + generation quiescence + soft phase=NORMAL). S01 + S19 scripts install `trap 'cleanup' EXIT` so cleanup fires even on `set -e` abort from a failed test. `restore_cluster_baseline` non-zero is itself a `log_warn` — subsequent suites may inherit churn if it fails.

---

## Known limitations

| TC ID | Limitation | Tracking |
|---|---|---|
| TC-02-001, TC-02-007, TC-02-012, TC-02-017, TC-02-022, TC-02-026 | `wait_for_phase NORMAL 180` is warn-then-continue across every `test_initial_state`. Cluster stuck in COLD_BOOT triggers `log_warn` and the test still passes; downstream chaos kill may produce UnknownObserved silently | Audit §1.5 (PARTIAL warn-on-phase, severity LOW) |
| TC-02-003 | ON_DUTY pre-kill state widened acceptance — S01 spec specifically targets the `(JOINING, TransportUnreachable)` cell, but the test passes if R races into ON_DUTY before kill (acceptance widened to `(ON_DUTY, TransportUnreachable)` cell with `log_warn`). Documented relaxation, not hidden green-sticker | Audit §1.5 (SOUND-widened, severity LOW) |
| TC-02-005 | Smoking-gun reason regex is strict (`reason=transport-failure\|reason=swim-faulty`) when R is killed in the JOINING window — the only ungated S01 cell. When R races to ON_DUTY before the kill, the gated `transport-failure` / `swim-faulty` cells produce `Outcome.nop` and decommission proceeds via the ungated `SwimDeparted` cell (reason=swim-departed); the smoking-gun assertion `skip_test`s in that branch and the 25s budget assertion (always strict) carries the contract | Audit §1.5 (SOUND-with-branch, severity LOW; F3 fix 2026-05-21) |
| TC-02-004 | Budget drift: C9 contract / spec S01 = ≤25s decommission budget, but `test_decommission_within_budget` currently uses a relaxed 90s budget. Records forward-decommission slowness; contract number unchanged | #231 (forward-decommission) |
| TC-02-006 | If `pick_non_leader` returns no candidates (post-2-kills cluster mid-recovery), exclusion assertion is skipped via `log_warn`. A regression returning "no candidates" universally would not be caught | Audit §1.5 (PARTIAL skip-via-warn, severity LOW) |
| TC-02-013 | Quiescence to 5 after 2 kills is warn-then-continue (`wait_for_node_count 5 240 \|\| log_warn`); residual `assert_ge 3` masks stuck auto-heal in this function. Mitigated downstream by TC-02-016's strict `assert_eq 5` | Audit §1.5 (PARTIAL warn-on-quiesce, severity LOW) |
| TC-02-015 | Name claims "still active" (no churn) but check is existence only (`assert_ne ""`) | Audit §1.5 (WEAK name/check mismatch, severity LOW) |
| TC-02-019 | Same name/check mismatch — "Leader unchanged" only checks "leader exists"; spurious re-election would not be caught | Audit §1.5 (WEAK name/check mismatch, severity MEDIUM) |
| TC-02-023 | `start_load` counts `200..399` as success — 3xx-as-success green-sticker in error-rate denominator. Low impact: app route deliberately returns 200 | Audit §1.5 (SOUND-with-3xx-caveat, severity LOW) |
| TC-02-030 | Warn-then-pass demotion — missing `SELF_DRAIN_INITIATED` event downgrades to `log_warn`. Justified by Rabia publish race vs `Runtime.halt(2)`; hard contract remains exit-code-2 (TC-02-029) | Audit §1.5 (WARN-THEN-PASS, severity LOW) |
| TC-02-031 | WARN-ONLY (cannot fail): positive match on post-drain KV/consensus-write log lines downgrades to `log_warn`. A real KV-write leak would never fail this test. Compile-time `noConsensusOrKvImports` unit test is the canonical guard | Audit §1.5 (WARN-ONLY cannot fail, severity MEDIUM) |
| TC-02-027, TC-02-028, TC-02-029 | **RESOLVED** — victim/survivor selection no longer assumes static compose ordinals. `test_pick_victims_and_kill_three_simultaneously` enumerates the REAL running core containers (ordinal AND KSUID-named CTM replacements) via `docker ps --filter status=running` (test-self-drain-quorum-loss.sh:113-133, :279-292), so a cluster whose slots rotated to KSUID-named replacements is selected correctly. Precedent: `test-readiness-contract.md §1.1` Property-4 retirement (per-port/ordinal probing breaks under CTM auto-heal) | RESOLVED 2026-05-27 (membership-based enumeration) |

---

## Charter changelog

| Date | Author | Change |
|---|---|---|
| 2026-05-21 | charter author | Initial charter from audit §1.5 |
| 2026-05-27 | charter author | Citations repointed to existing specs: S-rows (C3/C7/C9/C10/C12/C13/C14/C16) → `membership-architecture-spec.md §16` + `§16.1`; C2 → `cluster-membership-fsm-spec.md §I5`; C11 → `§I2`; C5 → `§6.4`; C4 → S18 + `§4.6`. C6 de-gapped → `slot-based-membership-convergence-spec.md §2` (exactly-S invariant). Removed dead anchors (`self-drain-spec.md`, `cluster-deployment-manager-spec.md`, `leader-election-spec.md`, internal `architecture/*.md`). Recorded C9 budget drift (spec 25s vs live 90s, #231). S19/S20 ordinal-enumeration limitation (TC-02-027/028/029) marked RESOLVED — suite now enumerates running cores via `docker ps`. |


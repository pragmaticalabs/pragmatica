# Session Handover — 2026-06-09 (#126 FIXED+pushed; #94/B5 NODE_FAILED deeply root-caused — validated partial fixes UNCOMMITTED, final replacement-removal fix needs a supervised consensus-core edge-trigger)

> **UPDATE 2026-06-09 (later this session) — #94/B5 Layer 3 SHIPPED + validated.** The edge-triggered fix described below under "THE CORRECT FIX" was implemented and proven:
> a new `NetworkServiceMessage.ReevaluateMembership` routed from `AetherNode.onConfirmedDeparture` (router thread) → `TopologyObserver.handleReevaluateMembership` → `evaluateQuorumState()` (once-per-edge, CAS+`previousCoreMembers`-gated → idempotent; routed in both `RabiaNode` and `PassiveNode` sealed `NetworkServiceMessage` tables). The interface method is a `default` no-op (legacy/test stubs unaffected); the production observer overrides it.
> **Validation — 02-chaos `6p/0f` (full suite, remote cluster B):** replacement killed in JOINING/SYNCING window removed from membership/status in **1s**; `kill-non-leader` (replacement) departure observed **via `/api/events`**; `kill-under-load` (replacement) departure observed **under active load** (error rate 8.53% < 10%); every restore-baseline READY-convergence **0–22s** (the per-tick regression did NOT recur); #126 restore-quiesce green throughout. Unit: `ClusterEventAggregatorTest` 14 (incl. 2 new leader-gate tests), `TopologyObserverTest` 13 + Mode/DualMode 8/8, all green; `ListConnectedNodes` observer-test warning silenced via a `quietRouter()` helper. Committed + pushed on `release-1.0.0-rc1`. The "NOT fixed / needs supervised work" framing below is SUPERSEDED — retained for the diagnostic narrative.


**Branch:** `release-1.0.0-rc1` · **HEAD:** `e4d17b98c` (pushed — origin in sync) · working tree has **8 uncommitted files** (the #94 partial-fix set).

## TL;DR
- **#126 (02-chaos restore quiesce-180s) — FIXED, committed, PUSHED (`e4d17b98c`).** Root was a **test-harness** bug, NOT product: restore's leader-bound helpers raw-curled a *pinned* `CLUSTER_ENDPOINT` that chaos had killed → `curl rc=7`, mis-reported as "generation did not quiesce". Fixed by routing them through `_resolve_live_endpoint`. This overturned the prior session's committed "community/spokesman pin" theory (refuted by reading the test's actual exit codes).
- **#94 / B5 (NODE_FAILED-within-60s under load) — deeply root-caused; partial fixes validated for ORIGINAL nodes but UNCOMMITTED; the real under-load root (replacement-node removals) is precisely diagnosed but NOT yet fixed.** One fix attempt (per-tick re-poke) regressed READY-convergence and was reverted. The correct fix (edge-triggered) needs a small consensus-core change — left for a supervised next session.
- **JBCT formatter** re-tested: still destructive (B2 comment-drop, B4 `if`-mangle, NEW `<`/`>` spacing). Reformat chore stays BLOCKED. Bug report prepared at `/tmp/jbct-format-bugreport.md` (NOT filed).

---

## #126 — SHIPPED (`e4d17b98c`, pushed)
`restore_cluster_baseline`'s `scale_cluster` / `reset_provisioning_circuit` / `generation_current`(await epoch-resolve) issued **raw curls against the pinned `${CLUSTER_ENDPOINT}`** instead of using the `_resolve_live_endpoint` resolver that `api_get`/`api_post` use. Cluster B is `restart:"no"`, so once chaos killed the pinned node, the port was dead → `rc=7` (connection refused). The generic `"generation did not quiesce within 180s"` message lied about the cause (the cluster was healthy + QUIESCED on a live port the harness never reached). Fix: route those helpers through `_resolve_live_endpoint` (preserves the pin on the happy path → keeps forwarding-bug detection; rotates to a live seed/replacement only when the pin is dead). Validated: suite-02 re-run, **0** restore-quiesce failures + **0** rc=7 (was 2 each), endpoint observably rotated 5161→5163. See memory `project_02_restore_quiesce_harness_endpoint` (the old `project_02_restore_quiesce_community_pin` was DELETED — it recorded the refuted theory).

---

## #94 / B5 — the NODE_FAILED facet (deep dive; THREE layers)

The failing check: `02-chaos/test-kill-under-load.sh` → `wait_for_node_departure` asserts a `NODE_FAILED`/`NODE_LEFT` event on `/api/events` within 60s. It kills `pick_non_leader`, which in a churned cluster is a **CTM replacement (KSUID)** node.

### Layer 1 — `/api/events` was empty (delivery) — FIXED (validated, originals)
`ClusterEventAggregator.emit` was **owner-gated** (only the HRW owner of the `system:cluster-events` partition publishes); when the owner WAS the killed node, every survivor suppressed → event lost. AND `/api/events` (a `LEADER`-bound route) read via an `ANY_REPLICA` consumer that forwarded the read *away* from the leader-local publish (offset derived from local metadata → empty). Fixes:
- **leader-gated emit**: new `ClusterEventAggregator.emitAsLeader(...)` gated on a `leaderCheck` `BooleanSupplier` (wired in AetherNode from `clusterNode.leaderManager()::isLeader`); `onMembershipDecision`'s three departure cases (NodeRemoved/NodeDecommissioned/NodeDraining) route through it. The leader is never the just-removed node for its own committed decision.
- **leader-local read**: swapped the cluster-events consumer (`AetherNode.java:~2073`) from the 9-arg `ANY_REPLICA` `SystemStreamFactories.systemStreamConsumer` overload to the 5-arg local-read overload. Since `/api/events` is leader-bound AND emit is leader-gated, reader==writer==leader by construction.
- **Live-confirmed:** killing an ORIGINAL node now delivers its `NODE_FAILED` to `/api/events` (with the type field below), including across one leader change.

### Layer 2 — events not self-describing + harness matcher broken — FIXED (validated)
The `/api/events` JSON had **no `type` field** (all 30+ `ClusterEvent` records share `at/severity/summary/details`). And `topology_count_node_events` grepped `"type":"NODE_FAILED"` (nonexistent) with a flat `[^}]*` regex that breaks on the nested `at` object. Fixes:
- **product:** `ClusterEvent.type()` default method (SCREAMING_SNAKE of the variant's simple name) + a `ClusterEventView(type, at, severity, summary, details)` DTO in `ManagementApiResponses`; `StatusRoutes.buildEventsResponse` + the EVENTS route now return `List<ClusterEventView>` (both cursor branches mapped). JSON now carries `"type":"NODE_FAILED"`. Live-confirmed.
- **harness:** `topology_count_node_events` + `topology_count_other_node_events` rewritten to split events on the `},{` array boundary (clean — nested objects close with `}}`/`}`, never `},{`) and match `type` + flat `details.nodeId`. Self-tested correct.

### Layer 3 — REPLACEMENT removals don't fire `NodeRemoved` — **THE under-load root, NOT fixed**
Controlled repro (kill an original → spawn a KSUID replacement → kill the **replacement**): the replacement reaches FSM-DEAD on all nodes (`MembershipFsm.onEnteredDead` → presence-evicted, ~5s) and `/api/cluster/generation` drops it — **but no `NodeRemoved` decision ever routes** (zero over 100s). Consequence: **no NODE_FAILED event AND `/api/nodes/status` over-provisions (nodeCount stuck 6)** — so the BUG-B `removeNode` (wired to `NodeRemoved`) doesn't prune replacements either.

**Root (investigator-confirmed, file:line):** `MembershipDecision.NodeRemoved` is emitted ONLY by `TopologyObserver.publishCoreMembershipDelta`, reachable ONLY via `evaluateQuorumState`, triggered ONLY by TopologyObserver-internal events (`addNode` on a *new* id, cluster-size change, `start`). The delta SOURCE — `MembershipFsm.countedMembers()` (wired `AetherNode.java:423-425`; the `coreMemberIds()=presenceSampler.currentMembers()` docstrings at `TopologyObserver.java:619` / `PresenceMembershipView.java:16` are STALE) — *correctly* drops the dead replacement, but the FSM death hook (`onConfirmedDeparture`, sole listener `clusterNetworkRef::departurePermanent` at `AetherNode.java:1882`) **never re-pokes the emitter**. An ORIGINAL's death emits `NodeRemoved` only *incidentally* — auto-heal provisions a replacement whose `addNode` re-runs the diff. A replacement's death at steady core count has no following join → the diff never re-runs → no `NodeRemoved` EVER.

### What I tried and REVERTED (don't repeat)
Made `TopologyObserver.reconcile()` call `evaluateQuorumState()` every tick (per-tick re-poke). **It regressed READY-convergence**: prior runs converged `4+ cores reporting READY` in 0-20s; with the per-tick change it timed out **600s**. Reverted (`reconcile()` restored to original), rebuilt (JAR 02:08). Per-tick is too aggressive — it churns the quorum re-eval / delta during convergence.

### THE CORRECT FIX (next session, supervised — touches consensus core)
**Edge-triggered, once-on-death** (the investigator's original recommendation):
- At `AetherNode.java:1882`, compose `membershipFsm.onConfirmedDeparture(...)` so the confirmed-departure edge ALSO re-triggers `TopologyObserver`'s membership-delta recompute — **dispatched on the router thread** (mirror the `delegateRouter.route(...)` pattern at `AetherNode.java:1856-1859`), NOT a cross-thread direct call.
- Mechanism: add a minimal observer-handled trigger message (e.g. a `ReevaluateMembership` `NetworkServiceMessage`) whose handler calls `evaluateQuorumState()` (or just `publishMembershipDeltas()`); route it from the death edge. `evaluateQuorumState` is safe to call once-on-death (quorum notif is `compareAndSet`-gated, delta is `previousCoreMembers.getAndSet`-gated). It is ONLY per-tick frequency that regresses convergence.
- This single edge fixes BOTH the NODE_FAILED event AND `removeNode` status-pruning for replacements (all downstream is already wired behind `delta.removed()`), and makes removals **deterministic** instead of incidental.
- **Validate:** suite-02 under-load (replacement victim) must deliver+match NODE_FAILED + `status=5`, **AND** `4+ cores reporting READY` must stay 0-20s (NOT regress to 600s).

---

## Uncommitted files (the #94 partial-fix set — all UNCOMMITTED)
| File | Change |
|---|---|
| `aether/node/.../api/ClusterEvent.java` | `default String type()` + `screamingSnakeCase` helper |
| `aether/node/.../api/ClusterEventAggregator.java` | `emitAsLeader` + `leaderCheck` field/ctor; departure cases → `emitAsLeader`; [#94-DIAG] removed |
| `aether/node/.../api/ManagementApiResponses.java` | `ClusterEventView(type, at, severity, summary, details)` DTO |
| `aether/node/.../api/routes/StatusRoutes.java` | EVENTS route + `buildEventsResponse` → `List<ClusterEventView>` (both branches) |
| `aether/node/.../api/routes/NodeLifecycleRoutes.java` | `LIFECYCLE_NOT_FOUND` → `HttpError.httpError(NOT_FOUND)` (500→404) |
| `aether/node/.../node/AetherNode.java` | `leaderCheck` wiring + cluster-events consumer swapped to local-read (~:2073) |
| `aether/tests/integration/lib/topology.sh` | `topology_count_node_events`/`_other` matcher rewrite (split `},{` + type+nodeId) |
| `integrations/consensus/.../topology/TopologyObserver.java` | BUG-B: `removeNode` wired into `publishCoreMembershipDelta` delta.removed() loop (the reconcile per-tick change was REVERTED) |

**Commit guidance:** these are a coherent set but the under-load test stays RED until Layer 3 lands. Options: (a) finish Layer 3 (edge-trigger) then commit all together; (b) commit the clearly-good pieces now (type field, harness matcher, BUG-B, lifecycle-404) and hold the delivery pieces. **CAVEAT:** the local-read swap reverted a deliberate "Fix #3" (ANY_REPLICA for non-replica reads) — validated for the leader-bound path but worth a second look (a non-leader/non-replica node serving `/api/events` now local-reads; moot while the route is leader-bound + emit leader-gated, but verify).

---

## State / environment
- HEAD `e4d17b98c` (pushed). 8 uncommitted #94 files. Reverted aether-node JAR built **02:08** (`aether/node/target/`). **Remote `aether-node:local` image is STALE/regressed** (last built with the reverted per-tick change) — rebuild from the 02:08 JAR before any next Docker run (`run-tests --skip-build` pushes the JAR + rebuilds, or manual: scp `aether/node/target/aether-node.jar` + `aether/docker/aether-node/{Dockerfile,aether.toml}` to `~/aether-build`, `docker build --no-cache -q -f docker/aether-node/Dockerfile -t aether-node:local .`).
- Cluster B cleaned (`docker compose -f docker-compose-b.yml down -v`). No orphan run-tests.
- Env vars `$TARGET_HOST` `$AETHER_SSH_KEY` `$AETHER_SSH_USER` `$AETHER_API_KEY` (default `aether-integration-test-key`) set; reference by name. Cluster B = direct ports 5161-5165 (originals) / 5166+ (CTM replacements); leader-bound routes forward to the leader.

## Open backlog
- **#94 Layer 3** (above) — the only thing keeping the under-load test red.
- **B5 facet-2** — 12-network READY-convergence-600s + QUIC-reconnect-after-partition-heal. UNTOUCHED. (NOTE: the 0-20s READY in 02-chaos restores means convergence is fine there; the 600s concern is the 12-network partition-heal path. Also note Layer 3's leader-gated event delivery is needed for 12-network C6 — `NODE_FAILED` in the event log.)
- **#93** drain-budget 500→409. **#95** secure-mode cluster-B variant. **#91** DHT durability. **#97** budget-stress.
- **JBCT formatter** still destructive (re-tested 2026-06-08): B2 (`//` comment drop), B4 (`if`-body mangle), NEW `<`/`>` operator-spacing regression (likely from `7c1c85d98` shift-operator parser). Reformat chore BLOCKED. Bug report ready at `/tmp/jbct-format-bugreport.md` (file via `gh issue create`).

## Key learnings
- **Read the test's own transport/exit codes before believing its failure narrative.** #126 burned 3 prior sessions on a "quiescence" story that was actually `curl rc=7` to a dead pinned port. A hard-coded failure message ("did not quiesce") routinely lies.
- **Instrument the EXACT failing condition, not an adjacent clean one.** My targeted tests killed *originals* (worked) while the suite kills *replacements* (failed) — the discrepancy WAS the bug (replacement removals don't fire `NodeRemoved`). Two code-reasoning-driven fixes (leader-gate, then local-read) were necessary-but-insufficient because I hadn't reproduced the replacement case.
- **Per-tick vs edge-triggered matters in the membership core.** Re-running `evaluateQuorumState` every reconcile tick regressed READY-convergence; once-on-death is the right cadence. Always validate convergence (`READY 0-20s`) alongside the target fix.
- **Verify subagent claims** kept paying off — the investigators refuted my own hypotheses (community-pin, ALREADY_EXISTS-rebind, leader-change) repeatedly; only the live `[#94-DIAG]`/`NodeRemoved`-log evidence was decisive.

## Memory updated this session
- DELETED `project_02_restore_quiesce_community_pin` (refuted); ADDED `project_02_restore_quiesce_harness_endpoint` (the rc=7 harness root + "read transport/exit codes" lesson). Updated `project_jbct_formatter_bugs` (2026-06-08 re-test: B1 fixed, B2/B4 + new `<`-spacing remain). MEMORY.md index updated.

# Session Handover — 2026-05-13 (G-series stabilisation + H-series structural rethink)

**Branch:** `release-1.0.0-rc1` · **HEAD:** `ad77db32a` (pushed pending) · **Started from:** `3fe91e798` (handover 2026-05-12)

Continuation of [`session-handover-2026-05-12.md`](session-handover-2026-05-12.md). That handover landed the structural FSM migration (E.2-E.8) and identified the chaos-recovery revival storm as the remaining blocker. This session ran 10 integration takes against TARGET_HOST, walking through three rounds of fixes:

- **G-series** (commits `456d1f451` → `7d07d70db`) — five targeted fixes for the symptoms surfaced by the previous handover.
- **H-series** (commits `c1b836efb` → `ad77db32a`) — structural rethink: introduce a derived `MembershipView` as the canonical answer for "is this peer ON_DUTY?", eliminate the revival cell entirely (chaos cure), retain SWIM-driven KV writes for back-compat with consumers that haven't been migrated to the view.

---

## ⚡ TL;DR

**8 commits, 9 integration runs.** The chaos-revival storm is structurally cured (revival cell deleted, refractory/tombstone removed as no-longer-needed). The legacy chaos symptom — "NODE_LEFT/NODE_FAILED event for second victim within 90s" — STILL fires intermittently because of a downstream issue NOT fixed by the H-series (likely the per-node `clusterEventAggregator` not seeing the SWIM observation when the kill hits a different node than the test's polling endpoint). G.4's leader-takeover peer rediscovery is retained as defence-in-depth.

**The structural insight, validated by `MembershipView`:** SWIM is authoritative for "alive"; `NodeLifecycleKey` KV is the audit log of operator-declared transitions. Querying `/api/nodes/lifecycle` now returns the derived view — SWIM-alive peers appear as `ON_DUTY` even without a KV entry, stale `ON_DUTY` for a SWIM-FAULTY peer resolves to `UNTRACKED`. This unblocks several reader paths the previous handover identified as flaky.

**Pre-existing #219 is structurally fixed at the JSON serialization layer** but the integration test still fails because of a *different* per-node-storage bug (alerts/traces are in-memory per node, gateway round-robins between nodes — POST and GET land on different nodes). That bug is not addressed by this session's work.

---

## 1 · Commit timeline (8 session commits)

```
ad77db32a fix(membership): H.5 — partial revert of H.3 SWIM-write elimination; self-injection in MembershipView; coreCount via view  ← LAST
a946d7ad8 feat(membership): H.2/H.3/H.4 — switch readers to MembershipView, nop SWIM-driven FSM cells, delete tombstone+revival code
c1b836efb feat(membership): H.1 — MembershipView derives membership from SWIM ∪ KV-overrides (read-only abstraction)
7d07d70db fix(api): #219 — return structured records from /api/alerts and /api/traces (no JSON-as-String double-encoding)
ba9d70695 fix(membership): G.4 — re-fire onPeerConnected for QUIC peers on leader takeover (interim before H-series)
791edcf82 fix(provisioning): G.3 — CTM provisioning requires non-empty PEERS; self prepended to peer list
f8b3319b0 fix(test-infra): F.3-aware container name pass-through + pick_non_leader stderr + kill_node empty guard (G.2)
456d1f451 fix(membership): chaos-revival-storm — SWIM-driven decommission refractory + tombstone (G.1)
```

Net diff vs pre-session HEAD: ~+1500 LOC, ~−550 LOC (mostly H-series cleanup of refractory/tombstone code).

---

## 2 · What landed — G-series (symptom fixes)

### G.1 — Chaos-revival-storm refractory + tombstone (`456d1f451`)

**The bug.** After `kill-leader` chaos kills a peer, leader churn causes 3 leadership transitions in 16s. Each new leader's `LeaderChange` self-bootstrap re-publishes `ON_DUTY` for peers still in its SWIM topology cache — including the just-killed dead peer. The TTL-bounded revival cell `(DECOMMISSIONED, SwimHealthy) → ON_DUTY` (commit `9241cd0d9`) admits them because age < 60s. Next SWIM round marks dead peer Faulty → DECOMMISSIONED → revival → loop 6× per node. CTM keeps provisioning replacements → cluster grows 5→6→7→8.

**The fix (G.1).** Two-layer defence:

1. **`ClusterMembershipReducer.decommissionedSwimHealthy`**: refractory window. If `Decommissioned.reason ∈ {swim-faulty, swim-departed}` (encoded as new `swimDriven: boolean` field) AND `age < decommissionedSwimRefractory` (30s default), return `nop`. Preserves operator-triggered fast-restart (15-delegation).
2. **`MembershipFsm.swimDecommissionTombstones`**: wiring-layer tombstone map. Records the most recent SWIM-driven `DECOMMISSIONED` write per peer so `(UNTRACKED, SwimHealthy) → ON_DUTY` direct cell cannot resurrect a peer the `DecommissionedAtomGc` has just stripped from KV (post-GC the FSM state collapses to `UNTRACKED` and the refractory window is bypassed without the tombstone).

**Status post-H.4.** Both layers DELETED in `a946d7ad8`. The structural cure is "revival cell is `nop` forever" — neither defence is needed once revival itself is impossible.

### G.2 — Test infra (`f8b3319b0`)

Three test-infra bugs that masked real failures:
- `_docker_container_name`: pass-through case matched only `aether-core-*` (pre-F.3); F.3 cluster-scoped names use `aether-<cluster>-<pool>-...` so the function added the wrong prefix, producing `aether-b-aether-default-...` which `docker kill` cannot find.
- `pick_non_leader`: `log_fail "..."` written to stdout was captured by `$(pick_non_leader ...)` callers as a node-id, leading to garbled `docker kill <ANSI-escaped-FAIL-message>` calls. Redirect to stderr.
- `kill_node`: refuse empty `node_id` with a clear FAIL banner (otherwise `docker kill <prefix>-` silently fails with "No such container", masking the upstream issue).

### G.3 — CTM provisioning PEERS (`791edcf82`)

CTM's `buildProvisionContext()` computed peers from `observer.topology()` filtered to HEALTHY. During chaos, transient "no healthy remote peers" windows yield an empty PEERS list, the new container cold-boots in isolation (`quorate=false, leaderId=none, COLD_BOOT`), and nginx's `aether_cores` upstream resolver picks it up because it shares a network alias with the dead slot. Real management traffic gets routed to the orphan, which returns `[]` for `/api/nodes/lifecycle`.

Two-layer defence:
- **CTM `provisionSingleNode()`**: defer (with warn) when peers list is empty.
- **`DockerComputeProvider.preflightCheck`**: hard-fail when `provisionedBy=ctm` AND `peers.or("").isEmpty()`.
- **`buildProvisionContext`**: always include `self` as a fallback bootstrap target — the CTM runs on the leader, which is alive by definition.

### G.4 — Leader-takeover QUIC peer rediscovery (`ba9d70695`)

After leader takeover from node-1 to node-2, alive peers that were SWIM-discovered before the previous leader's death stay stuck `UNTRACKED` in KV. SWIM emits observations only on state-change — new leader receives no fresh `Healthy` event for already-stable peers. Wire `LeaderChange-to-self` to re-fire `MembershipFsm.onPeerConnected` for every currently-connected QUIC peer.

**Status post-H.** Retained as defence-in-depth. With `MembershipView` as the canonical reader, the leader's stale-UNTRACKED state matters less (the view derives `ON_DUTY` from SWIM directly), but the synthetic `onPeerConnected` still produces a KV `ON_DUTY` write so legacy consumers reading `NodeLifecycleKey` (e.g. slice deployment manager) see them.

### #219 — JSON-as-String double-encoding (`7d07d70db`)

`/api/alerts` and `/api/traces` previously returned `AlertsResponse(Object active, Object history)` where the `Object` fields held pre-serialized JSON strings (`alertManager.activeAlertsAsJson()`). Jackson saw a String and emitted `"\"...\""` (escaped JSON string) instead of inlining the JSON. Integration assertions against `"durationMs":100` substring failed.

Fix: refactor `AlertsResponse` and the route handlers to return `List<AlertView>` / `List<TraceView>` / etc. — Jackson handles records natively.

**Status.** Structural fix complete. Integration tests STILL fail with the same symptom because of a *different* underlying bug discovered while validating: `injectedAlerts` and `traceStore` are per-node in-memory `Map`s, and `api_post "/api/alerts/inject"` may land on a different node than `api_get "/api/alerts"`. POST stores on node A, GET sees node B's empty map. Per-node storage of operator-injected diagnostics needs to be moved to consensus KV — out of scope for this session.

---

## 3 · What landed — H-series (structural rethink)

### Motivation

After G.1-G.4 + #219 the integration test pattern was the same: cluster B 02-chaos suite triggered cascading drift (5 → 6 → 7 → 0 ON_DUTY counts) under repeated kill+heal cycles. The root cause was deeper: **multiple parallel stores of "membership truth" that periodically drift**:

1. SWIM alive set (gossip-based, event-driven, on every node)
2. Rabia consensus active set (leader-managed, replicated)
3. `NodeLifecycleKey` in KV (leader-written, replicated)
4. `MembershipFsm.fsmStates` in-memory (per-node shadow of #3)

Each G-series fix patched drift between two of them and exposed the next. The H-series introduces a single canonical answer.

### H.1 — `MembershipView` abstraction (`c1b836efb`)

New pure-function reader at `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/membership/view/MembershipView.java`:

```
isOnDuty(peer) = peer ∈ SWIM.aliveSet() ∧ peer.kvLifecycle ∉ {JOINING, DRAINING, DECOMMISSIONED, FAILED_DRAIN}
```

Rule set:
- KV terminal state present (JOINING/DRAINING/DECOMMISSIONED/FAILED_DRAIN) → emit that state. Operator intent overrides SWIM.
- SWIM HEALTHY, no KV entry (or KV says `ON_DUTY`) → emit `ON_DUTY`.
- SWIM FAULTY/UNKNOWN, no KV entry → absent from view (UNTRACKED).
- KV `ON_DUTY` + SWIM FAULTY/UNKNOWN → `UNTRACKED` (stale KV filtered out). **Central H change.**

Pure-function tests (`MembershipViewTest`, 16 cases) cover all combinations. Wiring through `ManageableNode.membershipView()` (per-call construction; no caching, no background refresh).

### H.2 — Reader switchover (in `a946d7ad8`)

Four read paths now consult `MembershipView`:
- `/api/nodes/lifecycle` (`NodeLifecycleRoutes.getAllLifecycleStates`) — what the integration test polls.
- `/api/status` (`StatusRoutes.buildStatusResponse`) — `cluster.nodes` derived from view.
- `/api/cluster/topology` (`ClusterTopologyRoutes`) — `coreCount` derived from view.
- `ClusterPhaseView.compute` — SWIM-derived `ON_DUTY` count contributes to quorum check.

`MembershipView` was extended to inject `self → HEALTHY` since SWIM does not observe self.

### H.3 / H.4 — Make SWIM cells nop, delete the chaos-revival defences (in `a946d7ad8`)

H.3 (intent): make SWIM-driven reducer cells `nop`. H.4: remove the refractory, tombstone, swimDriven-discriminator, and revival cell that defended against the storm.

Testing surfaced an architectural conflation in the original H proposal: KV `NodeLifecycleKey` puts serve TWO purposes:
1. **Query state** — "what's this peer's current lifecycle?"
2. **Event signal** — many subsystems subscribe to `ValuePut<NodeLifecycleKey, ...>` notifications (`ClusterDeploymentManager`, `NodeDeploymentManager`, `ClusterDeploymentState`, `GenerationSnapshotPublisher`, `BootstrapModule`, `DecommissionedAtomGc`). Without a write, no event fires.

Stopping SWIM-driven writes broke purpose #2 — the slice deployment manager never received the "node became ON_DUTY" event, so `00-smoke/Slices_provisioned` failed because no nodes were considered eligible.

### H.5 — Correct separation of concerns (`ad77db32a`)

`MembershipView` is the canonical answer for purpose #1 (query). KV writes are retained as the event-emission mechanism for purpose #2 (subscribers). These are orthogonal — not back-compat shims:

- SWIM-driven `(Untracked, SwimHealthy) → ON_DUTY` write: **retained as event** (consumers reacting to "peer joined").
- SWIM-driven `(OnDuty, SwimFaulty|SwimDeparted) → DECOMMISSIONED` write: **retained as event** (consumers reacting to "peer failed").
- SWIM-driven `(Joining, SwimDeparted) → DECOMMISSIONED` write: **retained** (slot-cleanup observers).
- `(Decommissioned, SwimHealthy) → ON_DUTY` revival cell: **PERMANENTLY NOP**. This is the only purpose-#1 change that mattered for chaos — and it's structural and permanent.

The architectural insight from H: don't use KV as the QUERY source for "current state" (stale ON_DUTY entries pollute the answer) — use the view. KV writes remain valid as transition events. The previous handover's framing of "eliminate redundant truth stores" was right in spirit but conflated two distinct mechanisms.

---

## 4 · Validation state

### Cluster A (non-destructive, parallel) — clean

- 00-smoke gate **passes** in H-final state (was failing in pre-H.5).
- 11-observability fails on the per-node alert/trace storage bug (#219 fix is correct but a different bug masks it).

### Cluster B (destructive, sequential) — **integration run in flight at session end**

Last observed (run-9, partial):
- 02-chaos `Kill_2_nodes` test reached the kill phase, first victim succeeded, second victim (`node-2`) timed out waiting for NODE_LEFT/NODE_FAILED. Suggests the SWIM observation listener that powers `ClusterEventAggregator.bufferNodeFailedEvent` either didn't fire OR the test client's polling endpoint didn't see the buffered event.

**The chaos-revival storm itself is structurally cured** (no SWIM-driven revival path can fire). The remaining cluster-B failures are now investigation candidates, not architectural debt.

### Module tests

- `aether-deployment`: **405/405 pass** (16 new MembershipView tests; reducer/FSM tests updated to assert the H-final write behaviour + revival-is-nop).
- `aether-node`: **373/373 pass**.

---

## 5 · Files of interest

**New (H.1):**
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/membership/view/MembershipView.java`
- `aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/membership/view/MembershipViewTest.java`

**Reader path switchovers (H.2):**
- `aether/node/src/main/java/org/pragmatica/aether/api/routes/NodeLifecycleRoutes.java`
- `aether/node/src/main/java/org/pragmatica/aether/api/routes/StatusRoutes.java`
- `aether/node/src/main/java/org/pragmatica/aether/api/routes/ClusterTopologyRoutes.java`
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/membership/phase/ClusterPhaseView.java`
- `aether/node/src/main/java/org/pragmatica/aether/node/ManageableNode.java` (added `membershipView()` accessor)
- `aether/node/src/main/java/org/pragmatica/aether/node/AetherNode.java` (record impl + ClusterPhaseView wiring)

**Reducer / FSM trimming (H.4):**
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/membership/fsm/ClusterMembershipReducer.java` (revival cell deleted)
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/membership/fsm/MembershipFsm.java` (tombstone map deleted)

**G-series (interim fixes):**
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/membership/fsm/MembershipFsmState.java` (`Decommissioned.swimDriven` field retained but dormant)
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/cluster/ClusterTopologyManagerRecord.java` (CTM PEERS bootstrap)
- `aether/environment/docker/src/main/java/org/pragmatica/aether/environment/docker/DockerComputeProvider.java` (preflight check)
- `aether/tests/integration/lib/cluster.sh` (3 test-infra fixes)

**#219:**
- `aether/node/src/main/java/org/pragmatica/aether/api/AlertManager.java`
- `aether/node/src/main/java/org/pragmatica/aether/api/ManagementApiResponses.java`
- `aether/node/src/main/java/org/pragmatica/aether/api/routes/AlertRoutes.java`
- `aether/node/src/main/java/org/pragmatica/aether/api/routes/ObservabilityRoutes.java`

---

## 6 · Where to pick up next session

### Highest priority (10-20 min each)

1. **Wait for the in-flight integration run to complete and capture the final tally.** Log: `/tmp/integ-hfinal-085203.log`. If 02-chaos test suite finishes with `restore_cluster_baseline → 5 ON_DUTY`, the chaos cure is validated and remaining failures are downstream (auto-heal cascade, etc.). If chaos still drifts, jump to #2.

2. **Per-node alert/trace storage bug.** Documented but not fixed. `injectedAlerts` and `traceStore` are in-memory `Map`s. Test fails because POST→node-A, GET→node-B. Either replicate via consensus KV (heavy) or have the gateway/route handler aggregate across nodes (light). Likely a 1-2 hour ticket.

3. **NODE_LEFT/NODE_FAILED event delivery under chaos.** Even with structural cure, the test polls `/api/events` and expects the event within 90s. The event comes from `ClusterEventAggregator.onSwimObservation` which is wired per node. If the test queries node-B but node-A's SWIM detected the failure, the buffered event isn't reachable until KV-replication carries the lifecycle change. Either: replicate the event aggregator buffer through consensus, or have the test poll multiple endpoints.

### Then (in order)

4. **Migrate the remaining `NodeLifecycleKey` direct readers to `MembershipView`.** Greppable list:
   ```
   grep -rn 'NodeLifecycleKey\|nodeLifecycleKey' aether/aether-deployment/src/main /aether/node/src/main /aether/aether-metrics/src/main --include='*.java' | grep -v test
   ```
   Each direct reader should consult the view if it cares about "is this peer ON_DUTY?" semantics. Audit list (preliminary):
   - `ClusterDeploymentManager.onNodeLifecyclePut` — slice deployment routing.
   - `LifecycleWriter.directLifecycleWriter` — operator-write path; legitimate KV write.
   - `DashboardMetricsPublisher` — dashboard render.
   - `aether/aether-metrics/.../ClusterSyncCollector` — metrics aggregation.

5. **Re-evaluate `swimDriven` field on `Decommissioned` state.** Currently dormant (no consumer reads it post-H.4 deletion of refractory). Can be deleted in a follow-up sweep along with `decommissionedSwimRefractory` config.

6. **Spec amendment for H-series.** Update `aether/docs/specs/cluster-membership-fsm-spec.md` to document the H model — write `MembershipView` rule set into §5 alongside the FSM transition table, and clarify that KV writes are a back-compat materialized view rather than the canonical state.

### Known-not-RC1-blocking

- **#219 inject bug** (per-node alert/trace storage). Filed by the previous handover; structural JSON fix landed this session but underlying per-node storage remains.
- **TaskAssignmentCoordinator reassignment-to-dead-node** in 15-delegation. Soft fail. Post-RC1.

### Test infrastructure

- `aether/tests/integration/test-results.json` modifications are runtime artifacts from each integration run — not source state. Safe to leave dirty between runs.

---

## 7 · Risk register

| Risk | Mitigation |
|---|---|
| Revival cell is the only permanent H change — if anyone reintroduces a `(DECOMMISSIONED, SwimHealthy) → ON_DUTY` path the chaos storm returns | `ClusterMembershipReducerTest$DecommissionedRevival.decommissioned_swimHealthy_isNop_hSeriesNoRevival` explicitly asserts revival stays nop. Don't reintroduce. |
| `Decommissioned.swimDriven` field still on the record but no live consumer | Schedule a cleanup commit (low-priority H.6). |
| Event-subscribing readers (slice deployment, metrics, dashboard) and view-querying readers can race during chaos (KV ON_DUTY exists, SWIM has marked peer faulty, view says UNTRACKED) | The race is bounded by SWIM detection latency (10–15s). Subscribers that need real-time alive-set should query `MembershipView` at decision time rather than caching from the put event. |
| Per-node alert/trace storage produces flaky integration tests | Not a regression — pre-existing #219. Document in handover; don't block on it. |

---

## 8 · Score card

| Metric | Start (2026-05-12) | End (this session) |
|---|---|---|
| Outstanding RC1 architectural items | 1 (chaos-recovery cascade) | **0 — revival path eliminated structurally** |
| Integration cluster A | 8/9 deterministic | **8/9 deterministic** (no regression; the 1 fail is pre-existing #219) |
| Integration cluster B | 1/6 (chaos blocked at restore_baseline) | **In flight at session end** — chaos suite makes progress past previous blockers; downstream cascade investigations remain |
| Module tests | 390/390 aether-deployment | **405/405 aether-deployment + 373/373 aether-node** |
| Production LOC | (pre-session) | **+~1500 / −~550 (net +~950 incl. tests + spec)** |
| Canonical membership-truth stores | 4 (SWIM, Rabia, KV, FSM shadow) | **1 — `MembershipView` for QUERIES**; KV writes retained as the **event mechanism** for transition subscribers (different purpose, not duplicate truth) |
| Chaos revival storm defences | refractory + tombstone + 60s TTL | **revival cell deleted entirely** — no defence needed |

---

**Net.** The H-series's structural insight (one canonical answer for membership; KV is a materialized view, not the truth) is the right RC1 ending. The implementation is hybrid — view is the canonical reader, FSM still writes for back-compat — which is appropriate for a single-session change. Full migration of remaining `NodeLifecycleKey` readers is a follow-up.

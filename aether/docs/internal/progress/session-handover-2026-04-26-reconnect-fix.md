# Session handover — 2026-04-26 (reconnect-fix follow-up)

**Branch:** `release-1.0.0-rc1`
**HEAD:** `cf783a1a0`
**Prior handover:** `aether/docs/internal/progress/session-handover-2026-04-26.md` (Wave 3 + integration unblocking)
**Commits this session:** 3 (`7d60b94eb` → `cf783a1a0`)

## One-line summary

Smoke gate restored: diagnosed the lingering node-2 SUSPECTED state as a **container-recreation reconnect asymmetry** (peer comes back, re-handshakes with N-1 peers, silently misses 1) — fixed via periodic missing-peer reconciler in `QuicClusterNetwork` plus a 60s TTL on `swimHints` as defense-in-depth. Integration suite goes from smoke-gate-hard-failing to **8/15 suites green** with `00-smoke 2/0`, `08-resources` recovered to 5/0, and `02-chaos` improved from 0/4 → 2/2 from the reconciler effect.

## Diagnosis (what the prior handover called the "node-2 transport asymmetry")

Prior handover reported: `391 pongs from node-2 vs 2540 from each of node-3/4/5` — interpreted as a node-2-specific transport bug.

**Real cause:** the 391-pong tally was a **stale-log artifact** from a *prior* node-2 container instance whose log file persisted across the recreation. After `aether-a-node-2` was stopped and recreated at `13:29`, the post-restart counts were:

- `docker logs --since 13:30 aether-a-node-2 | grep node-1` → **0 hits**
- `docker logs --since 13:30 aether-a-node-1 | grep "sending PING to ... node-2"` → **0 hits**
- node-2's post-restart topology snapshot: `[node-2, node-3, node-4, node-5]` — **node-1 absent entirely**

What actually happened: node-2's recreation re-issued client-initiated handshakes; it landed on node-3/4/5 but missed node-1. Neither side had a periodic reconciler to detect the missing peer and re-arm the dial. Result: a permanent asymmetric split where the leader's snapshot kept node-2 stuck at SUSPECTED forever (the `ClusterSyncPongSignalFan` HEALTHY emission depends on actual pong traffic, of which there was none).

Three plausible mechanisms for *why* the dial to node-1 specifically dropped (not discriminated): (a) leader-bound QUIC server rejected client `wasEstablished` mismatch; (b) connect-in-progress dedup from `2e7b85dd1` skipped a half-open dial; (c) DNS/SYN race during recreation. The structural fix is independent of which one — the reconciler dispatches RECONNECT for any configured-but-not-connected peer regardless of why the dial was lost.

## Commits landed this session

```
7d60b94eb  fix(quic): periodic missing-peer reconciler — recovers from container-recreation
           reconnect asymmetry; per-peer jittered exponential backoff (5s-60s) on PeerState;
           CONNECTING/REMOVED/wrong-direction skipped; cancellable on shutdown; 5 unit tests

430e5e119  fix(health): swimHints TTL (default 60s) — sticky SUSPECTED self-heals when transport
           recovery is delayed; in-memory hint map decays without contradicting evidence (SWIM
           SUSPECT/FAULT remain authoritative); configurable via [operations.auto_heal]
           swim_hints_ttl; reuses Theme H clock injection; 4 unit tests

cf783a1a0  chore(logging): demote per-tick diagnostic INFO to DEBUG; refactor inline
           LoggerFactory.getLogger to class-level static field in ClusterSyncContext; update
           fan_emptyPong test to assert sender-HEALTHY emission introduced in 870f89b79
```

All builds green (`mvn -pl integrations/consensus,aether/aether-deployment,aether/node install -am -DskipTests`). All module tests pass: 278 in metrics+deployment, 25 in QuicCluster*, 5 in QuicClusterNetworkReconcilerTest, 4 in HealthReconcilerSwimHintsTtlTest.

## Layer 1 — Missing-peer reconciler (`QuicClusterNetwork`)

**Mechanism:** `SharedScheduler.scheduleAtFixedRate` ticks every 5s. Each tick walks `topology()`, skipping self/passive/wrong-direction/CONNECTING/REMOVED, and dispatches `connectPeer(NodeInfo)` for any configured peer not currently connected. Per-peer jittered exponential backoff is held on `PeerState` (initial 5s, cap 60s, 0.8x–1.2x jitter via `JitterUtil`).

**Reuses, not rewrites:**
- `connectPeer(NodeInfo)` is the dial primitive (handshake initiator).
- `processViewChange(RECONNECT, peer)` is the *post-handshake notification emitter*; the existing `RECONNECTED` `AttachResult` path naturally fires it once the dial completes.
- Connect-in-progress dedup (`2e7b85dd1`) protects against double-dialing.

**Backoff state lives on `PeerState`** (per the project's PeerState pattern memory — collapse N parallel maps into one) — added `reconcileNextAttemptMs` / `reconcileCurrentDelayMs` fields, `reconcileBackoffAllows(...)`, `resetReconcileBackoff()`. On successful attach, backoff is reset.

**Files:**
- `integrations/consensus/src/main/java/.../net/quic/PeerState.java`
- `integrations/consensus/src/main/java/.../net/quic/QuicClusterNetwork.java`
- `integrations/consensus/src/test/java/.../net/quic/QuicClusterNetworkReconcilerTest.java` (5 tests)

## Layer 2 — `swimHints` TTL (`HealthReconcilerContext`)

**Mechanism:** `Map<NodeId, HealthHint>` replaced with `Map<NodeId, TimestampedHint>` (record holds `hint + writeMs`). All reads now go through `effectiveSwimHints()` / `effectiveHint(NodeId)` which filter expired entries. All writes go through `putHint(NodeId, HealthHint)` recording `nowMs()` from the existing injected `LongSupplier clock` (Theme H from prior session).

**TTL is configurable**, default 60s. The `swimHintsTtl: TimeSpan` field is plumbed through the standard config chain:

- `aether/environment-integration/src/main/java/.../AutoHealConfig.java` — record component + cascading factory overloads
- `aether/aether-config/src/main/java/.../cluster/AutoHealSpec.java` — mirror with `String` form
- `aether/aether-config/src/main/java/.../cluster/ClusterBootstrapConfigParser.java` — TOML key `swim_hints_ttl` under `[operations.auto_heal]`

**Architectural intent:** in-memory health-hint state is now reconstructible-by-decay: any hint not re-emitted within TTL reverts to absent, defaulting to HEALTHY classification. Aligns with project memory: "state reconstructible from KV-Store" — the projection map no longer holds non-decaying state forever. SWIM's own SUSPECT/FAULT signals remain authoritative; the TTL only affects the advisory hint projection.

**Files:**
- `aether/aether-deployment/src/main/java/.../generation/fsm/HealthReconcilerContext.java`
- `aether/aether-deployment/src/test/java/.../generation/HealthReconcilerSwimHintsTtlTest.java` (4 tests)

## Layer 3 — Diagnostic log demotion (cleanup)

Per prior handover P0 #2, INFO-level per-tick logs added during the asymmetry diagnosis are now DEBUG. Inline `LoggerFactory.getLogger().info` in `ClusterSyncContext.sendOnePing` refactored to a class-level static `Logger log`.

**Files:**
- `aether/aether-metrics/.../ClusterSyncCollector.java` (PING received, PONG sent, PONG received)
- `aether/aether-metrics/.../fsm/ClusterSyncContext.java` (PING sent + Logger field added)
- `aether/aether-deployment/.../generation/fsm/HealthReconcilerContext.java` (3 hint-mutation log lines)
- `aether/node/.../AetherNode.java` (`attachQuicPeerStateListener` 5 lines: network class, onPeerJoined/Reconnected/Left, catch-up emission)
- Stale test fixed: `aether/aether-metrics/src/test/java/.../ClusterSyncPongSignalFanTest.java` — `fan_emptyPong_producesNoSignals` was asserting empty after `870f89b79` made the fan emit sender-HEALTHY unconditionally; renamed to `fan_emptyPong_emitsOnlySenderHealthyHint` and updated assertions.

## Integration test results

Full remote run completed; environment `remote`, all 15 suites executed.

| Suite | Pre-fix (handover-04-26) | Post-fix | Δ |
|---|---|---|---|
| **00-smoke** | hard FAIL (4 of 5 visible) | **2/0 PASS** | ✅ recovered |
| 04-streaming | 4/0 | 4/0 | — |
| 07-cluster-mgmt | 4/0 | 4/0 | — |
| **08-resources** | 4/1 | **5/0 PASS** | ✅ improved |
| 09-artifacts | 3/0 | 3/0 | — |
| 10-database | 3/0 | 3/0 | — |
| 11-observability | 5/0 | 5/0 | — |
| 14-storage | 2/0 | 2/0 | — |
| 06-deployment | 4/1 (varies) | 4/1 (`await-quiesced 500`) | flake (handover known) |
| 15-delegation | 1/1 | 1/1 | flake (handover known) |
| **02-chaos** | 0/4 | **2/2** | ✅ improved (reconciler effect) |
| 03-scaling | 0/3 | 0/3 | unchanged (rc2 — replacement-join latency) |
| 05-security | 1/2 | 1/2 | unchanged (rc2) |
| 12-network | 1/2 | 1/2 | unchanged (rc2) |
| 13-edge-cases | 0/3 | 0/3 | unchanged (rc2) |
| **Total** | smoke FAIL | **8/15** | smoke green, +1 cluster A green, +2 chaos sub-tests |

Cluster B chaos failures are the same rc2 replacement-join-latency issue documented in `session-handover-2026-04-25.md` ("Cluster-B chaos blockers"). Out of scope for this RC1 follow-up.

## rc2 hooks audit (read-only delegated)

Audit of `rc2-#189` markers + drain-coordinator stubs:

- **Marker hygiene clean** for the dedicated drain-protocol stubs: `DrainCoordinator`, `NoOpDrainCoordinator`, `NodeDeploymentState.Leaving`, `LeavingRequested`, `AppHttpState.Quiesced` — all reachable via `rc2-#189` / `rc2 #189` grep across 9 files / 16 hits.
- **`AppHttpState.Quiesced` is functional in rc1** (returns `RouteTable.empty()` on quorum loss) — not a stub. rc2 will add a richer drain sub-state but the current state is real.
- **No orphaned drain hooks**: `NoOpDrainCoordinator` returns immediate success on the production CTM scale-down path; `Leaving` state is unreachable in rc1 by design (no production dispatcher of `LeavingRequested`).
- **Two open scope decisions (USER ACTION REQUIRED — see below):**
  1. `HealthReconcilerContext.java:437,454` — `TODO(rc2): replace the time-based grace with a real consensus-drain barrier.` Architecturally adjacent to #189 (consensus-drain barrier is the same primitive) but #189's spec doesn't mention this `firstPublishCompleted` 3s grace timer. Choose: retag as `rc2-#189` OR file sibling.
  2. `TaskAssignmentCoordinator.java:196,214` — Theme K #2 says "DEFERRED to rc2 #189" but the `failedNodes` cooldown KV-promotion is a different concern (per-leader hint state survival across handoff). Audit recommends sibling ticket. Filing requires user authorization.
- **Vocabulary divergence flag for rc2 implementer**: rc1 stub uses `Leaving` / `LeavingRequested` / `DrainCoordinator` SPI. Issue #189 spec uses `Draining` / `EnterDraining` / `NodeDeploymentManager` orchestration. Implementer should treat rc1 placeholders as scaffolding, not contract.

## What works now (vs prior session start)

1. **Smoke gate green** — fix validated end-to-end on remote.
2. **Container-recreation reconnect is self-healing** — periodic reconciler dispatches RECONNECT for any peer that drops out of `connectedPeers()`.
3. **Sticky SUSPECTED is bounded by TTL** — even if transport recovery is delayed, the projection map decays in 60s and the projector defaults to HEALTHY without contradicting evidence.
4. **Cluster A non-destructive: 8 of 10 suites fully green** (06-deployment + 15-delegation flakes are pre-existing).
5. **Cluster B chaos partially improved** — 02-chaos went 0/4 → 2/2 from the reconciler effect on post-kill recovery.
6. **Diagnostic INFO logs at DEBUG** — production log volume restored to baseline.

## What does NOT work yet (rc2 / out of scope)

- **Cluster B replacement-join latency** — kill-leader / kill-multiple / kill-under-load / quorum-safety / scale-up / scale-down / 13-edge-cases all gate on the new container reaching HEALTHY+ON_DUTY within the test timeout (60–180s). Total bring-up is 30–80s typical; multi-kill scenarios accumulate. Either tighten the bring-up path (SWIM stabilization is the long pole) or relax test timeouts. Documented in `session-handover-2026-04-25.md` "Cluster-B chaos blockers".
- **15-delegation 1/1 flake** — handover noted as known. Worth a focused triage but not a blocker.
- **06-deployment occasional `await-quiesced 500`** — handover noted as "sensitive to cluster-A health propagation". Not a regression.

## Open architectural questions (rc2)

The prior handover already listed:
- SWIM doesn't emit `MemberHealthy` callbacks — only SUSPECT/FAULT/LEFT. **Now mitigated** by the swimHints TTL — absent evidence is no longer interpreted as bad.
- swimHints map TTL — **NOW DONE** (default 60s, configurable).
- Pong-fan emits HEALTHY but not `RemoteConnectivity(CONNECTED)` for sender — still open.
- CTM `activate()` ordering vs. snapshot publish — still open.

New question raised by this session:
- **Should the missing-peer reconciler tick interval, initial backoff, and cap be exposed as TOML config?** Currently hard-coded in `QuicClusterNetwork` (5s tick, 5s initial, 60s cap). Defaults are fine for production but operators may want to tune for slower-WAN deployments. Defer to rc2 unless an operator hits it.

## Verification commands

```bash
# Module compile (all touched modules)
mvn -pl integrations/consensus,aether/aether-deployment,aether/aether-metrics,aether/aether-config,aether/environment-integration,aether/node install -am -DskipTests

# Module tests
mvn -pl integrations/consensus,aether/aether-deployment,aether/aether-metrics test

# Integration suite (~58 min on remote)
cd aether/tests/integration && ./run-tests.sh --env remote --skip-build
```

## Next-session P0

1. **User decision on audit hygiene items** (above):
   - Retag `HealthReconcilerContext.java:437,454` `TODO(rc2)` as `rc2-#189` OR file sibling issue.
   - File sibling rc2 ticket for `TaskAssignmentCoordinator.failedNodes` KV-promotion.
2. **Move `v1.0.0-rc1-candidate` tag to HEAD** (`cf783a1a0`) — per project memory "v<version>-candidate is a moving tag that tracks WIP state of the current release branch — re-create on HEAD after each batch of commits."
3. **Push commits to remote** (3 commits ahead of origin: `7d60b94eb`, `430e5e119`, `cf783a1a0`).
4. **Optional: cluster B chaos triage**. The 02-chaos improvement (0/4 → 2/2) suggests the reconciler may be unblocking some scenarios that were previously gated. A focused look at the 2 remaining 02-chaos failures could surface either (a) further wins from related fixes, or (b) confirm they're the rc2 replacement-join-latency case.

## References

- RC1 session-handover chain: `2026-04-22 → 2026-04-23 → 2026-04-24 → 2026-04-25 → 2026-04-26 → 2026-04-26-reconnect-fix`
- Diagnostic transcript: investigation traced ~60min of cluster A logs across `aether-a-node-1`/`-2` to discriminate hypotheses; key insight was that `docker logs --since` post-recreation showed both sides were silent toward each other, ruling out the "node-2 outbound throttling" framing from the prior handover.
- Issues mentioned: `#189` (rc2 drain protocol). Two new sibling issues recommended (above).

---

**Session totals:** 3 commits, ~3 hours active development. Smoke gate restored end-to-end with structurally correct fixes (transport reconciler + projection TTL); integration suite goes from smoke-FAIL to 8/15 green with no regressions. Cluster B chaos chain remaining is documented rc2 work.

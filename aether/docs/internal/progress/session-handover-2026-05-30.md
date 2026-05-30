<!--
SPDX-License-Identifier: BUSL-1.1
Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
-->

# Session Handover — 2026-05-30 — NTT convergence (swim tracker collapsed onto NTT) + QUIC guard + §5.4 PEERS→SWIM-only; first wedge Docker-validated, §5.4 unit-green / Docker-pending

**Branch:** `release-1.0.0-rc1`. **HEAD:** `a97a9e753`. **7 commits LOCAL/unpushed** atop pushed `3233a92eb` (the ULID merge).

## ⭐ START HERE
This session corrected a wrong-spec-base in the membership work and executed a large convergence. **The authoritative membership design is `aether/docs/specs/membership-architecture-v2-spec.md` (NTT-centric — "Derive-from-Reality"), NOT `membership-unification-spec.md` (now marked ⛔ SUPERSEDED).** The unification proposal had spawned a *parallel* `swim.MembershipTracker` + consensus injection + a second spec — a design explosion. We **collapsed it back onto NTT** and pushed two further v2-architecture fixes. Plan of record: `aether/docs/internal/progress/membership-ntt-convergence-2026-05-30.md`. Full spec↔impl audit: the 6-agent reverse-reference audit summarized in this session (see §5 below).

**The single most important next action: re-run Docker `--suites 00,02` on `a97a9e753`** to validate §5.4 (it is unit-green but NOT Docker-validated — see §3).

## 1. Commits this session (atop pushed `3233a92eb`)
```
a97a9e753 feat(membership): §5.4 — PEERS→SWIM-only (static coreNodes no longer seed QUIC-dialed nodeStatesById; discovery+self only); migrate consensus formation test harness to inject SWIM discovery
540cb6aa3 fix(quic): P4 — guard unresolved/null peer address in initiateConnection (clean retryable failure, not SockaddrIn NPE)
593588c18 refactor(membership): P2/P3 — collapse swim MembershipTracker onto NTT (single tracker); seed provisioning from live members (wedge fix); ClusterPhaseView from quorum+leader; delete swim.membership package + consensus injection
6ee4e316e feat(membership): P1 — NTT absorbs smoothed sample+delta+hysteresis FSM (replaces per-peer timers); convergence plan; supersede unification spec
6a8c3cd3e test(02-chaos): re-source test-kill-leader to v2 membership-absence (wait_for_node_removed); promote helper trio to lib/topology.sh; refresh lint baseline
ef90af3ac refactor(id): route 14 KSUID.ksuid() sites through IdGenerator.generate() (now ULID); drop dead KSUID imports
ae8e8e43d fix(membership): reconciler drain guard — union-dedup effective capacity + hard floor never drains below configuredCoreCount
```
Earlier pushed this session: `3233a92eb` (PR #237 ULID merge). **Push the 7 when ready.**

## 2. What the convergence did (all unit-green)
- **NTT is THE tracker.** `NodeTopologyTracker` absorbed the periodic-sample + delta + asymmetric-hysteresis FSM (up=2 fast / down=`ceil(nttDepartureTimeout/1s)`), replacing per-peer `ScheduledFuture` timers. The parallel `swim.MembershipTracker` + `MembershipTrackerConfig` + `TrackerBackedGenerationSnapshotSource` + the whole `swim.membership` package were **deleted**; consensus quorum uses `localQuorumCount` (QUIC) per v2 §4, not the tracker.
- **Reconciler/CTM/CDM/phase re-pointed to NTT.** `LeaderReconciler` takes `NodeTopologyTracker`; **`provisionReplacement` seeds PEERS from the live `clusterMembers`** (the wedge fix); `ClusterPhaseView` derives phase from `(inQuorum, haveLeader)`.
- **QUIC null-addr guard** (`QuicClusterClient.initiateConnection`): unresolved DNS → clean retryable failure, not `SockaddrIn` NPE.
- **§5.4 PEERS→SWIM-only** (`a97a9e753`): `TopologyObserver` no longer static-seeds `config.coreNodes()` into the QUIC-dialed `nodeStatesById` — that map is now **SWIM-discovery + self only** (`handleDiscoveredNodes` is the entry point). `coreNodeIds`/quorum denominator stay config-derived. Consensus formation tests migrated to inject discovery (consensus has no SWIM, so the shared harness `RabiaNetworkPerformanceTest.NetworkNode.start()` now drives `handleDiscoveredNodes`).

## 3. Validation state — READ CAREFULLY
**Docker-validated on `540cb6aa3` (collapse + QUIC guard, BEFORE §5.4):**
- 00-smoke **13/13** (cold-start + slice placement intact under NTT).
- 02-chaos `test-joining-window-kill` **5/0**, `test-kill-leader` **5/0**.
- **First baseline-restore hit 4+ ON_DUTY in 0s → the original DNS-DOA wedge is FIXED** (lowercase ULID names + live-member seeds + QUIC guard).
- **BUT the FINAL baseline-restore WEDGED at 3 healthy** — root-caused live to **(a) the §5.4 static-seed re-dial** (leader re-dialed dead *configured* node-5 every 5s forever: `Missing-peer reconciler: re-dialing configured peer node-5 (phase=INIT)`), and **(b) a secondary consensus catch-up failure** (the provisioned replacement was `behind by 211 phases, Triggering resync` and got SIGKILLed before catching up). Evidence saved to `/tmp/collapse-evidence/` (ephemeral).

**§5.4 (`a97a9e753`) targets cause (a) — but is UNIT-GREEN ONLY, NOT Docker-validated.** Next session MUST re-run Docker `--suites 00,02` to confirm the final baseline-restore now converges. If it does, then run the **full suite** (user's standing instruction: 00+02 pass → run all 15).

## 4. Open issues for next session (priority order)
1. **Docker-validate §5.4** (rebuild node JAR → clear host → `--suites 00,02`; if green → full suite). This is the gate.
2. **Secondary consensus catch-up bug (cause (b), UNRESOLVED):** a freshly-provisioned replacement boots at Rabia Phase 0 and cannot catch up to the running log (200+ phases behind → resync loop → killed). This is a **Rabia state-transfer / snapshot-install gap**, independent of membership/dial. MAY be unblocked by §5.4 (if the leader isn't pinned re-dialing dead peers, the replacement may get rounds to sync) — confirm in the §5.4 Docker run; if it still fails, it's a real consensus-layer fix (snapshot-install for new joiners).
3. **observed-IP dialing (`ResolvedNodeInfo`) — DESIGNED, NOT IMPLEMENTED.** The clean follow-on to §5.4: SWIM captures the observed source IP (`packet.sender().getAddress()`, currently discarded at `NettySwimTransport:275`/`SwimProtocol:345`) → a *local-only* `ResolvedNodeInfo`/`NodeState` field (NEVER gossiped — observed IP is per-observer truth) → QUIC dials observed-IP + advertised-QUIC-port. Removes DNS from QUIC entirely. Layering: registry/field injected so consensus never depends on swim. See the design discussion in this session's transcript.
4. **P4 cleanup (deferred):** lying comments on safety paths — `ConsensusBridge:32-33`/`RabiaEngine:99-101` claim the cold-start QUIC path was "removed" (it's the LIVE path — fix the comment); gutted `AetherNode.membershipView()` comment (claims it consults a reachability snapshot it no longer reads). Orphaned reachability pipeline still serialized per `ClusterSyncPing` (`AggregatedReachabilitySnapshot` + `ClusterSyncCollector.{lastReachabilitySnapshot,bestSnapshot,emitPeriodicConnectivity}`). Stale `LocalQuorumWatcher` doc refs in 5 files. `reachability-aggregator-spec.md` cited by live code but describes a deleted component.

## 5. The reverse-reference audit (6 agents, spec↔impl) — what it found
Most HIGH/CRITICAL items were the multi-tracker seam, now resolved by the collapse. Still-relevant residue:
- **`healthyOnDutyCount()`/`onDutyMemberIds()`** historically returned the FULL member set (not ON_DUTY∩HEALTHY) feeding CTM scale math — re-check post-collapse (the swim tracker that exposed this is deleted; CTM now reads NTT/snapshot — verify the count semantics).
- **Two `MembershipView` interfaces** (consensus `topology.MembershipView` vs `aether.deployment.membership.view.MembershipView`) — name collision; consider rename.
- **count-smoothing** for scale magnitude (spec §4) — the tracker has set-hysteresis; verify whether the reconciler still wants a separate count-smoother (was flagged MISSING; v2-arch relies on action-site quorum-safety instead — likely a non-issue under the NTT design, confirm).
- **CDM reads the replicated snapshot** for allocation membership (stale-by-a-round) while LeaderReconciler reads NTT live — re-check if this still matters.

## 6. Traps / state
- **AetherNode** = ~3260-line single `assembleNode`, truncation-magnet — **direct Read+Edit on specific regions only; never full-file Read/Write, never delegate a whole-file edit.** `aetherNode` is an inner record; shutdown deps are record components.
- **Consensus tests have NO SWIM** (module DAG: consensus < swim). Any consensus formation/integration test must inject discovery via `TopologyObserver.handleDiscoveredNodes(NetworkMessage.DiscoveredNodes(...))` — that's the §5.4 harness pattern (`RabiaNetworkPerformanceTest`). New formation tests need the same.
- **Build hang trap:** consensus formation tests that don't inject discovery HANG ("Timeout waiting for decision N"). Always run consensus tests with `timeout <N> mvn ... -DforkedProcessTimeoutInSeconds=120 -Dmaven.test.failure.ignore=true` and iterate per-class (`-Dtest=...`), never the whole slow suite repeatedly.
- Build: `mvn -pl aether/node -am clean install -Dmaven.test.skip=true` for the JAR; **NEVER `mvn verify`** (HCLOUD failsafe); **NEVER `-Djbct.skip=true`**.
- Docker: clear host first (`docker rm -f` aether-/forge- containers + aether networks + aether/pgdata volumes), rebuild node JAR, `cd aether/tests/integration && ./run-tests.sh --env remote --suites 00,02 --skip-build` (rebuilds remote image from JAR). The harness's final baseline-restore is where the §5.4 fix must show convergence.
- Delegate consensus/swim/deployment Java to `jbct-coder`; AetherNode wiring stays direct.

## 7. References
- Authoritative spec: `aether/docs/specs/membership-architecture-v2-spec.md` (NTT = the design)
- Superseded: `aether/docs/specs/membership-unification-spec.md` (⛔)
- Convergence plan: `aether/docs/internal/progress/membership-ntt-convergence-2026-05-30.md`
- Prior handover: `aether/docs/internal/progress/session-handover-2026-05-29d.md`
- Memory: `[[project_membership_v2_redesign]]`

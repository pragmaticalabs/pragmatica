# Cluster Topology Management — Architecture Audit

**Date:** 2026-06-10 · **Branch:** `release-1.0.0-rc1` · **HEAD:** `d3ebb3c8d`
**Method:** 5 parallel audit agents (SWIM layer, consensus net transport, topology core + leadership, Aether consumers, spec-vs-implementation), synthesized. All paths relative to `pragmatica-clone/`. Full agent reports in appendices A–E.

---

## Verdict

The cluster stack is functionally strong (14/15 integration suites) but architecturally **over-determined**: liveness truth is co-owned by at least three layers (QUIC transport, SWIM, MembershipFsm) connected by **circular feedback loops**, and membership state is held in **six concurrent views** that must agree by timing rather than by construction. Every recent production incident — #94 flap, #94 under-load (#245), connectedPeerCount=3, the three documented SWIM mis-diagnoses — is a manifestation of this one property. The June hardening wave (de1adb9c1, 2b2fff2d4, 3d0a5afcf, c6b88e27a) fixed real bugs but each patch added another trigger/guard to the same tangled feedback structure, which is why the bug class keeps recurring.

The single highest-leverage decision: **restore SWIM probe-ack as the sole "alive" authority and make the transport's PeerState machine the sole emission source of connection events**. This is not a new idea — it is what the approved keystone spec already mandates (`swim-driven-topology-spec.md:100-107`, Decisions 6–8) and what the code deliberately reversed.

---

## 1. The root pattern: circular liveness with one-directional bias

Three independent agents found the same loop from different ends:

1. **Transport pushes SWIM toward "alive", never toward "dead".** Every QUIC attach emits `PeerJoined` → `recordTransportHint(PeerReachable)` (`AetherNode.java:2618-2627`) and, via the health FSM, `markAliveFromTransport` (`SwimHealthState.java:147-181` → `SwimProtocol.java:1055-1083`) which flips the member ALIVE and **clears its suspect clock** (`:1079-1080`). But local failure paths emit nothing upstream: `evictStaleConnection` (`QuicClusterNetwork.java:1256-1283`), `onChannelClosed` (`:1295-1309`), the zombie sweep (`:1416-1419`), and `onConnectFailed` (`:865-878`) are all silent. REMOVE is only ever emitted on paths *driven by* SWIM/membership verdicts (`:526-611`). So any spurious attach source suppresses failure detection indefinitely, and the transport can never correct itself.

2. **The SWIM-side guard against this is circular.** `markAliveFromTransport`'s only gate is the tombstone (`SwimProtocol.java:1067`) — but tombstones are created **only on the FAULTY edge** (`:583-590, :711`), which is exactly the transition the override suppresses. The evidence needed to refuse the override can never accumulate while the override fires.

3. **The dial set is fed by the thing it feeds.** Gossip-learned member → `MemberDiscovered` → QUIC dial set (`SwimProtocol.java:951-956`) → successful dial → `PeerConnected` → ALIVE → gossiped cluster-wide. Liveness can be self-sustaining without any probe-ack. The reconciler's FSM desired-set path performs **no** SWIM gating ("A REMOVED desired peer is readmitted unconditionally", `QuicClusterNetwork.java:1578-1583`), and the FSM itself is fed by transport hints — so the spec's Decision 8 gate (transport must consult SWIM before dialing) is bypassed in the production wiring.

**Spec status:** `swim-driven-topology-spec.md:100-101` (Decision 6) explicitly ordered this bridge **removed**: "any transport-level reconnection loop... could resurrect a dead peer in the FSM." It was re-introduced twice (markAliveFromTransport; PeerReachable hints) with no spec documenting it. #245 is the spec's prediction coming true verbatim.

---

## 2. #245 mechanism — explained except for one link

The in-code half of the ~10s `PeerJoined[victim]` loop is now fully traced:

- **Provenance loss:** re-dial of an evicted peer goes EVICTED→CONNECTING (`PeerState.java:208-216`); `attach` from CONNECTING returns ACCEPTED (`:234-240`), erasing the EVICTED provenance → `processViewChange(ADD)` → duplicate `PeerJoined` (`QuicClusterNetwork.java:927-929, 968`). This violates the class's own stated invariant ("reconnect MUST NOT emit duplicate nodeAdded", `:277-281`); RECONNECT suppression only works for acceptor-side inbound attaches.
- **Self-sustaining period:** each attach resets the inbound-TTL clock (`PeerState.java:230-237, 261-267`) **and both backoffs** (`QuicClusterNetwork.java:944-946`). Cycle: attach → silence → TTL evicts at ~8s (`:185, 1412-1414`) → reconciler re-dials ≤5s later at initial backoff → attach → PeerJoined. Period 8–13s ≈ observed ~10s.
- **SWIM's kill switch is disabled:** the `DisconnectNode` protection window is `helloTimeout×3` = 15s (`TopologyConfig.java:42-43`; `QuicClusterNetwork.java:533-539`) — longer than the loop period, so SWIM's authoritative disconnect is *always* rejected as "connection is fresh" while the loop runs. The dead node is unkillable from above and unevictable from below.

**The one unconfirmed link:** what completes a QUIC handshake claiming `hello.sender()==victim` after `docker kill`. Candidates: identity-reusing relaunch (harness JVM-relaunch tooling, `cluster.sh:1486-1500`), late pre-kill handshakes, or address confusion enabled by the missing dialer-side identity check — `completePeerConnection` keys the connection by `hello.sender()` and **never checks it against the dialed peerId** (`QuicClusterClient.java:536-537`). With dial-time DNS re-resolution (`QuicClusterNetwork.java:827`), a misdirected dial attaches under whatever answers.

**Decisive next step (per handover's "transport-layer investigation"):** instrument expected-vs-actual sender at `QuicClusterClient.java:536`. The check is simultaneously the diagnostic for #245's missing link AND the fix for the identity-verification hole. Also identify the fixed ~120s timer that ends the loop (DNS cache TTL? harness?) — the handover's own lesson: a constant timing ⇒ a fixed timer you haven't identified.

---

## 3. Findings register (cross-layer, ranked)

### Critical

| # | Finding | Evidence |
|---|---------|----------|
| C1 | Transport→SWIM liveness bridge re-introduced against approved spec Decision 6; circular tombstone gate; structural amplifier of #245 | `SwimProtocol.java:1055-1083`, `SwimHealthState.java:147-181`, spec `:100-101` |
| C2 | One-directionally biased transport emission: connects always reported up, local failures never — any spurious attach suppresses failure detection indefinitely | `QuicClusterNetwork.java:1256-1283, 1295-1309, 1416-1419, 865-878` |
| C3 | Re-dial loses EVICTED provenance → duplicate `PeerJoined`; attach resets TTL + both backoffs → self-sustaining ~10s loop; SWIM disconnect blocked by 15s protection window | `PeerState.java:208-240`, `QuicClusterNetwork.java:927-968, 944-946, 533-539` |
| C4 | Fabricated third-party incarnation: `applyAliveFromAck` bumps the *remote* member's incarnation and gossips it — a single ack/transport event out-orders every legitimate `Suspect(X,k)` cluster-wide and silences X's own self-advertisement. Known-unsound (the fix was tried, reverted as "wrong layer") and still in tree | `SwimProtocol.java:1076-1081, 1163` |

### High

| # | Finding | Evidence |
|---|---------|----------|
| H1 | **Operator scale is wedged on stable clusters**: `LeaderReconciler.onConfigChange` is an uncalled placeholder; `ClusterConfigKey` change notification only writes dead state (`realActualStableSinceMs` set, never read); with the periodic tick removed, `aether cluster scale` does nothing until unrelated SWIM churn | `LeaderReconciler.java:385-395`, `AetherNode.java:2007`, `ClusterTopologyManagerRecord.java:273,368,595` |
| H2 | **MembershipFsm DEPARTING is an inescapable trap**: down-hysteresis path dispatches only `DownHysteresisMet` → Departing, which ignores `SwimHealthy` and everything else; member never reaches DEAD, stays broadcast-eligible forever, can never rejoin | `MembershipFsm.java:345-347, 470-479`, `MembershipState.java:128, 151` |
| H3 | **Terminal eviction of a healed member + permanent incarnation fencing**: co-confirmed death flags cleared only on `SwimHealthy`; transport-first heal still evicted by the 8s backstop (`Stopped` terminal even from MEMBER); rejoin then requires inc > terminalIncarnation, which a never-restarted node never bumps; `livenessGoneSeen` sticky indefinitely after a pure-transport blip | `MembershipFsm.java:313-315, 514-520, 578-582, 746-754`, `MembershipState.java:103, 122, 219` |
| H4 | **Leader writes unfenced**: `viewSequence` dropped at the proposal handler; `LeaderKey` is last-write-wins; election topology is raw local `TransportObservation`, so a minority-flapped node can overwrite the cluster-wide leader | `RabiaNode.java:341-342, 383-388, 584-595`, `LeaderElectionState.java:363-367` |
| H5 | **Cold-boot election wedge**: successful-but-rejected proposal (lex-first configured node dead) neither reschedules nor bumps the stuck counter; Electing wedges until incidental topology event | `LeaderElectionState.java:455-467, 520-543, 714-728` |
| H6 | **No dialer-side Hello identity verification** (see §2); misdirected dial can supersede a healthy incumbent via adopt-newer | `QuicClusterClient.java:310, 536-537`, `PeerState.java:254-263` |
| H7 | **#245 consumer-layer damage**: during the ghost window the victim is *unallocatable yet routable* — pong-staleness excludes it from allocation in ~3 ping intervals, but the forward `AccessibilityFilter` (FSM-fed) keeps routing to it (full timeout burn) and CDM sees instance counts met, so **no replacement instances** for the whole window | `AetherNode.java:1218-1222, 1505-1514`, `ClusterDeploymentState.java:1988-1989` |
| H8 | **SWIM FAULTY residency is dead code**: `expireSuspectIfOverdue` removes the timestamp `transitionToFaulty` just stamped; the same-tick sweep treats missing timestamp as expired → FAULTY members swept immediately despite the designed `suspectTimeout×3` residency; `DepartedObserved` fires ~instantly and death-memory is erased, re-arming suppression gates | `SwimProtocol.java:462, 511-515, 637-655, 693, 710` |
| H9 | **`PeerReachable` hint inverts semantics**: backdating a SUSPECT's clock to the 3s floor accelerates *eviction* (exit-from-SUSPECT-via-expiry = FAULTY) — a transport-confirmed-reachable peer can be the one evicted fastest | `SwimProtocol.java:400-431, 700-705`, `AetherNode.java:2618-2620` |
| H10 | **Per-dial timeout discards late successes**: `Promise.timeout` fails the shared promise (its own javadoc warns against this); handshake completing after 15s → asymmetric topology + orphaned 8-lane connection | `QuicClusterNetwork.java:851-854`, `Promise.java:487-500`, `QuicClusterServer.java:454` |

### Medium

| # | Finding | Evidence |
|---|---------|----------|
| M1 | Two quorum denominators: operator-settable `effectiveClusterSize` (observer/Rabia) vs `ClusterConfigKey.coreCount` (presence latch); `SetClusterSize` mutates only the former — silent divergence | `TopologyObserver.java:316, 845`, `RabiaEngine.java:1017`, `PresenceGenerationSnapshotSource.java:74` |
| M2 | `healthyOnDutyCount` = members.size() incl. SUSPECT — a ghost node inflates resize gating for the full detection window | `PresenceMembershipView.java:50-52`, `TopologyObserver.java:830-841` |
| M3 | CTM's documented minority-partition provisioning stop (`inQuorum`) is construction-only dead code — a false safety claim; real protection is `quorumSafe` (admitted stale window, TODO) + consensus-gated writes | `ClusterTopologyManager.java:92-116`, `ClusterTopologyManagerRecord.java:74-162`, `LeaderReconciler.java:598-602` |
| M4 | Empty-supplier mass-cleanup hazard: `cdmCountedMembersSupplier` returns `Set.of()` before FSM wiring; any cleanup in that window classifies *every* entry stale; only construction order protects it | `AetherNode.java:1124-1126, 1792-1797`, `ClusterDeploymentState.java:1409-1473` |
| M5 | Fast rejoin of a wiped node (same NodeId, <15s) leaves phantom ACTIVE slice entries no cleanup path detects | `ClusterDeploymentState.java` (handleNodeRemoval/cleanup paths) |
| M6 | SWIM concurrency: ten parallel maps, three thread domains, read-then-put updates (lost-update races against the FAULTY>SUSPECT>ALIVE priority rule); periodic ticks not serialized | `SwimProtocol.java:109-194, 1095-1196, 707-716`, `SharedScheduler.java:26-30` |
| M7 | TTL correctness depends on SWIM probe cadence, which degrades under load — false evictions → mass re-dials → reachable-hint flood masking the genuinely dead node (consistent with #245 load-dependence) | `QuicClusterNetwork.java:177-185` |
| M8 | Netty/QUIC transports semantically divergent behind one interface (netty: REMOVE on every TCP close, ADD on every Hello, no PeerState/reconciler/sweep) | `NettyClusterNetwork.java:206, 220-233, 414-428` |
| M9 | Descriptor last-wins is unversioned; the 2b2fff2d4 address-downgrade guard blocks only empty-erase — a stale late `NodeInfo` still overwrites a newer address. Network identity should be incarnation-fenced like lifecycle | `MemberDescriptor.java:21`, `MembershipFsm.java:759-770` |
| M10 | Dead FSM events (`DrainRequested`, `JoinGraceExpiredNeverHealthy` — zero dispatchers); operator drain bypasses the "authoritative" FSM entirely; OBSERVED-never-healthy ghosts retained and broadcast-eligible forever | `MembershipFsm.java`, `DrainProcedure`/`DrainCommandRegistry` |
| M11 | ANNOUNCE drift cluster: stop condition inverted (inbound-probe vs spec's connection-count), `JOIN_TIMEOUT` never delivered programmatically, receive-path order inverted (rate-limit before known-NodeId drop, which doesn't exist — known peers exhaust source buckets), inbound QUIC Hello is an unvalidated second join path | `SwimProtocol.java:885, 1007-1026`, `NettySwimTransport.java:343-348`, `QuicClusterNetwork.java:~976-980`, spec `:79, 151-156` |
| M12 | Leader FSM violates its own no-dispatch-inside-actions contract (`adoptLeaderFromKvIfPresent` inside `tx.handle`); spurious proposal + `proposalInFlight` leaked into Led | `TransitionRequest.java:77`, `LeaderElectionState.java:336-339, 455-458` |

### Low (selected)

- Piggyback buffer lacks per-node supersession; per-tick self-entries compete for the 16-slot cap under churn (`PiggybackBuffer.java:64-99`).
- `Ack.from()` never checked against probed target (`SwimProtocol.java:816-823`).
- Seeds born SUSPECT with armed clock; `startupDelay == suspectTimeout` ±20% jitter means standalone SWIM formation *depends* on the C1 transport promotion (`SwimConfig.java:74-84`, `SwimProtocol.java:279-283, 340-342, 1040-1046`).
- `NodeRemoved` cleanup failure drops the triggered reconcile — healing waits for the periodic timer (`ClusterDeploymentState.java:278, 1366-1368`).
- Drain grace-terminate ignores deactivation/leader change; heartbeat-only drain has no failover handover (`ClusterTopologyManagerRecord.java:522-533`).
- `peers` map unbounded under ULID churn (REMOVED resident by design, never pruned) (`QuicClusterNetwork.java:593-599`).
- Drain-victim selection is reverse-NodeId order, ignoring load/zone (`LeaderReconciler.java:843-845`).
- `activePeers` counts EVICTED as active — quorum can count a dead node through a flap loop (`QuicClusterNetwork.java:683-694, 1812-1828`).

---

## 4. Spec drift (summary of Appendix E)

- **Keystone spec architecturally obsolete while marked "Approved for RC1"**: its two central invariants (transport never sources membership truth; SWIM gates all re-dials) were deliberately reversed by the June hardening wave. #245 is the predicted cost.
- **Three-way contradiction on who drives topology**: keystone (SWIM→QUIC direct mapping) vs membership-architecture-v2 (FSM removed) vs code (FSM deepened, desired-set mediated). Neither spec marked superseded.
- **cluster-generation-spec wrong on shipped mechanics**: `HealthReconciler` doesn't exist, `NodeLifecycleKey` deleted, snapshot distribution is presence-derived not leader-piggyback; it is the only document for generation/quiescence semantics.
- **Load-bearing mechanisms with no spec home**: zombie TTL sweep, CONNECTING staleness, dial-time DNS re-resolution, LRP probe scheduling, address-downgrade guard, incarnation-gated REMOVED re-admission, tombstone-clear on self-ANNOUNCE.
- Stale comments cite the nonexistent `HealthReconciler` as "sole writer" of a deleted key (`NettyClusterNetwork.java:199, 412`; `QuicClusterNetwork.java:963`).

---

## 5. Simplification roadmap (consensus across agents, ordered by leverage)

1. **Single alive-authority (fixes the #245 class, not the instance).** Demote transport→SWIM feedback to non-clock-resetting advisory (or evidence-bounded: one promotion per suspect window, or promotion requires a probe-ack within N rounds). SWIM probe-ack becomes the sole alive truth. This alone breaks the #245 suppression even if spurious attaches continue. A completed handshake is not app-liveness proof — the blackhole test substrate itself demonstrates this (`QuicClusterNetwork.java:127-133`).
2. **PeerState transitions as the single emission source.** Every transition (including evictions and dial failures) produces exactly one typed event; `processViewChange` becomes a pure projection. Eliminates provenance loss (C3) and emission asymmetry (C2) structurally, and collapses the six scattered emission sites with per-site suppression flags.
3. **Dialer-side identity check** (`hello.sender()` vs dialed peerId) — fix and #245 diagnostic in one.
4. **Make MembershipFsm exhaustive and the only membership authority.** Give DEPARTING an exit (timeout→Stopped; SwimHealthy→rejoin); make death-flag clearing symmetric on any SUSPECT exit; retire `PresenceSampler.stableMembers` as a parallel set (its own doc admits the deferred migration, `PresenceSampler.java:73-78`) and TopologyObserver's four overlapping resurrect guards; route operator drain through the FSM.
5. **Feed leader election from `MembershipDecision`, not raw transport** — eliminates the local-flap → cluster-wide leader-swap path and one whole topology copy. Fence `LeaderKey` writes with the already-computed viewSequence/term (compare-and-put).
6. **One quorum denominator** sourced from `ClusterConfigKey`, consumed by observer, latch, and Rabia.
7. **SWIM internals:** remove incarnation fabrication; add provenance to SUSPECT (SEED/PROBE/GOSSIP/HINT) and a single logged `admit(update, evidence) → verdict` decision function replacing the six scattered gates (the structural property behind three consecutive mis-diagnoses: state without provenance, policy without a single decision point); collapse the ten maps into one per-member record updated via `compute()`; fix the FAULTY-residency dead code.
8. **Dead-code strip:** ~half of `ClusterTopologyManagerRecord` (retired slot loop), `inQuorum`, write-only stability anchors, four factory overloads; split `ClusterDeploymentState.java` (2,229 lines); decide netty transport's fate (retire or bring under PeerState).
9. **Spec reconciliation:** rewrite the keystone to the FSM-mediated desired-set model (or revert the code to the spec before chasing #245 further); mark v1/v2 supersessions explicitly; document the TTL sweep, LRP, staleness eviction, and downgrade guard.

---

## 6. Open questions

1. What completes a handshake claiming the victim's identity after `docker kill`, and what fixed timer ends it at ~120s? (Instrument `QuicClusterClient.java:536`.)
2. Should the forward `AccessibilityFilter` also consult pong-staleness to bound ghost-window routing damage independently of the transport fix?
3. Is `onLivenessGone` ≡ QUIC disconnect intentional? FSM docs describe it as "no probe-ack within the liveness window" — a different, stronger signal (`MembershipFsm.java:327-331`, `AetherNode.java:1863`).
4. Mixed-wipe restart: node with persisted Rabia/KV state joining a `down -v`-wiped cluster — term regression vs `MembershipDecision` logIndex monotonicity (`TopologyObserver.java:696-698, 725`); defined anywhere?
5. Does NDM reconcile KV-claimed ACTIVE slices against an empty local store on boot (M5 recovery)?
6. Is the same-tick FAULTY sweep (H8) load-bearing for any NODE_FAILED-latency SLO?
7. Should F1/F2-class FSM fixes (H2/H3) land before #245, given the QUIC re-dial fix increases `PeerConnected` re-assertion frequency — the exact trigger of both?

---

## Appendix A — SWIM layer report (agent swim-audit)

- **Loop:** `tick()` = refresh self-ALIVE → expire suspects → sweep FAULTY → probe ONE least-recently-probed member (`SwimProtocol.java:459-464`, LRP at `:726-733`). Probe→ack→indirect `PingReq`→SUSPECT (`:749-798`); SUSPECT→FAULTY after `suspectTimeout` (`:670-694`). Conforms to base SWIM cadence.
- **Dissemination:** FIFO piggyback buffer with dissemination-count eviction (`PiggybackBuffer.java:64-88`); per-round self-ALIVE re-advertisement (`SwimProtocol.java:481-487`).
- **State:** one `SwimMember` record + ten parallel concurrent maps (`members`, `pendingProbes`, `pendingRelays`, `suspectTimestamps`, `memberFirstSeenAt`, `lastProbedAt`, `tombstones`, `everSeenHealthy`, `lastEmittedHealth`, `transportHints`, `SwimProtocol.java:109-194`).
- **Transport coupling — two channels with opposite philosophies:** (a) advisory hints biasing timers (`recordTransportHint`, `:383-431`); (b) direct state mutation: QUIC `ConnectionEstablished` → `onNodeConnected` (`AetherNode.java:1960-1964`) → FSM `PeerConnected` → `markAliveFromTransport` (`SwimHealthState.java:147-181`) → `applyAliveFromAck` (`SwimProtocol.java:1055-1083`).

**F1 — CRITICAL: Liveness truth co-owned; transport override gated only by a tombstone the override itself prevents.** `TransportObservation.java:22-29` declares transport signals "advisory only — SWIM remains the canonical health source". `markAliveFromTransport` violates this: a QUIC connection event directly flips a member ALIVE and clears its suspect clock (`:1079-1080`). Its only guard is the tombstone (`:1067`) — created only on the FAULTY edge (`:583-590, :711`), precisely the transition transport promotion suppresses. The gate is circular. Evidence cycle: gossip-learned member → `MemberDiscovered` feeds QUIC dial set (`:951-956`) → successful dial → `PeerConnected` → ALIVE → gossiped cluster-wide.

**F2 — CRITICAL: Fabricated third-party incarnation.** `applyAliveFromAck` bumps the remote member's incarnation (`:1076-1078`) and gossips it (`:1081`). A single ack — or one transport event via F1 — emits `Alive(X, k+1)` that out-orders every legitimate `Suspect(X, k)` (`:1163`); X's own `refreshSelfAlive` advertisements carry lower incarnation and are silently discarded — the authoritative node loses authority over its own record. The no-fabrication patch was reverted as "wrong layer"; the unsound mechanism is still in tree.

**F3 — HIGH: FAULTY residency window is dead code.** `expireSuspectIfOverdue` calls `transitionToFaulty` (stamps `suspectTimestamps`, `:710`) then unconditionally removes the stamp (`:693`). `cleanupFaultyMembers` runs same tick (`:462`), treats missing timestamp as expired (`.or(true)`, `:637-639`) → member removed immediately despite the designed `suspectTimeout × 3` threshold (`:515`). `DepartedObserved` fires ~instantly after `FaultyObserved`; `clearDeathMemory` (`:650-655`) immediately erases `everSeenHealthy`, re-arming cold-boot/join-grace gates.

**F4 — HIGH: `PeerReachable` hint accelerates FAULTY eviction.** `applyReachableHint` backdates a SUSPECT peer's clock to expire within the 3s floor (`:400-431`); exit-from-SUSPECT via expiry = FAULTY. Both Reachable and Unreachable hints shorten the window. Usually masked by `markAliveFromTransport`, but `onPeerJoined`→`PeerReachable` (`AetherNode.java:2618-2620`) arrives on a separate path with no promotion.

**F5 — MEDIUM: No aggregate atomicity across ten maps, three thread domains.** Ticks on virtual threads (`SharedScheduler.java:26-30`, periodic bodies not serialized), inbound on Netty loop (`NettySwimTransport.java:223`), FSM on dispatcher threads. `applyExistingMember` is read-then-put (`:1095-1196`) — its FAULTY>SUSPECT>ALIVE priority (`:1188-1191`) violable by lost updates; `transitionToFaulty` (`:707-716`) can clobber a concurrent ack-promotion.

**F6 — MEDIUM: Indistinguishable SUSPECT provenances — the mis-diagnosis enabler.** Seed-birth (`:340-342`) and probe-failure (`:790-798`) SUSPECT are byte-identical. Six interacting gates — tombstone, join-grace, cold-boot, everSeenHealthy, edge-dedup, hints — decide a transition's fate in scattered guards. State without provenance, policy without a single decision point.

**F7 — LOW: Piggyback lacks per-node supersession**; stale `Suspect(X,k)` circulates after `Alive(X,k+1)`; self-entries compete for `maxSize*2=16` (`PiggybackBuffer.java:96-99`).

**F8 — LOW: misc.** `Ack.from()` unchecked (`:816-823`); `emitDeparted` at-most-once guard admitted non-functional (`:1380-1391`); `startupDelay(10s)==suspectTimeout(10s)` ±20% jitter — seeds born SUSPECT can expire before first probe; formation depends on F1's transport promotion (doc admits, `:1040-1046`); `SwimObservation.java:68-70` claims unimplemented "k-of-n" confirmation.

**Paper conformance:** base SWIM conformant. Deliberate deviations: ANNOUNCE join, tombstones, gating, fixed (non-log-N) suspect timeout. Accidental: F2, F3. **No Lifeguard** (local-health multiplier, buddy system) — relevant since #94's symptoms are exactly what Lifeguard addresses.

## Appendix B — Transport report (agent net-audit)

Per-peer lifecycle in `PeerState` (INIT→CONNECTING→CONNECTED⇄EVICTED→REMOVED, per-peer synchronized, `PeerState.java:80-86`). 5s reconciler (`QuicClusterNetwork.java:164, 1371-1432`): zombie sweep (1394-1419), re-dial via legacy topology path (1435-1468) or FSM desired-set path (1476-1494), gated by backoff + SWIM gates (1560-1571) — gates absent on the desired-set path. Dials defer DNS to dial time (808-855). Events leave via `processViewChange` (1738-1810).

**F1 — CRITICAL (#245 in-code half): dialer-side reconnect loses provenance → duplicate `PeerJoined`.** (Detail in §2 above.)

**F2 — CRITICAL: each attach resets all three clocks → self-sustaining ~10s loop; 15s DisconnectNode protection window > loop period → SWIM disconnect permanently ignored.** (Detail in §2.)

**F3 — CRITICAL (architectural root): one-directionally biased emission.** Each of the four recent patches added an eviction *trigger*; none added an eviction *signal* — the asymmetry is untouched, so the bug class survives every patch.

**F4 — HIGH: no Hello identity verification on dialer.** `QuicClusterClient.java:536-537`; `datagramChannels` keyed under expected id (`:310`) → eviction can't close the right channel; misdirected dial can supersede a healthy incumbent via adopt-newer (`PeerState.java:254-263`).

**F5 — HIGH: per-dial timeout discards late successes** (`QuicClusterNetwork.java:851-854`; `Promise.java:487-500` warns; orphaned 8-lane connection until next dial closes the channel, `QuicClusterClient.java:280-282`).

**F6 — MEDIUM: circular liveness gating across three authorities; four definitions of "connected"** (`PeerState.CONNECTED`; `activePeers` counting EVICTED, `:683-694, 1812-1828`; SWIM-alive; FSM membership) + `broadcastMembership` as a fourth membership input (`:235-252`). `PeerReconnected` also emits a reachable hint, undercutting the no-connectivity-observation-on-RECONNECT rationale (`:1788-1804`).

**F7 — MEDIUM: TTL correctness depends on SWIM probe cadence, which degrades under load** (documented, `:177-185`) → false evictions → mass re-dials → spurious PeerJoined → hint flood masking the dead node.

**F8 — MEDIUM: netty/quic divergence** (`NettyClusterNetwork.java:206, 220-233, 414-428`).

**F9 — LOW: unbounded `peers` map under ULID churn** (`:593-599`).

**#245 verdict:** in-code half confirmed; unconfirmed: what completes a handshake claiming `hello.sender()==victim` after `docker kill` with `restart: "no"` (`docker-compose-b.yml:59`). Candidates: identity-reusing relaunch (`cluster.sh:1486-1500`), late pre-kill handshakes, address-confusion + F4. Instrument `QuicClusterClient.java:536`.

**Simplification:** essential = explicit liveness (QUIC idle timeout disabled, `QuicClusterClient.java:155`), single-dialer direction, offline buffering. Accidental = three backoff mechanisms; three timers derived from `helloTimeout×3` doing different jobs; emission scattered across six sites; four membership inputs into one class. Highest-leverage: PeerState transitions as single emission source; second: demote transport→SWIM feedback.

## Appendix C — Topology core report (agent topology-audit)

Note: `MembershipFsm` lives in `aether/aether-deployment/.../membership/fsm/`, not `integrations/consensus/topology`.

**Map.** Liveness ingest: SWIM edges (`routeSwimEdgeToMembershipFsm`, `AetherNode.java:2724-2735`) + QUIC taps (`:1856, 1863`) feed per-member `MembershipFsm` (OBSERVED→MEMBER→SUSPECT→DEPARTING→DEAD; `MembershipState.java:48`) and bias `PresenceSampler` (`PresenceSampler.java:287-308`). Death needs co-confirmation (SWIM-FAULTY ∧ liveness-gone) + 8s backstop (`MembershipFsm.java:578-582`). Quorum chain: `countedMembers()` (MEMBER+SUSPECT) → `PresenceGenerationSnapshotSource` (one-way latch, `:72-83`) → `MembershipView` → `TopologyObserver.haveQuorum()` (`:666-679`) → `RabiaEngine` activation (`RabiaNode.java:319-321`); Rabia vote quorum separately `effectiveClusterSize/2+1` (`TopologyManager.java:47-49`, `RabiaEngine.java:1017`). Leader election: `LeaderManager` consumes raw local `TransportObservation` (`RabiaNode.java:383-388`) into an 8-state FSM; election = unfenced `Put(LeaderKey, candidate)` (`RabiaNode.java:589`); adoption via push + 500ms KV pull (`LeaderElectionState.java:540-551`); rank-staircase anti-herding (`:595-606`). Generations: `observedRabiaTerm` stamps `MembershipDecision` (`TopologyObserver.java:725`); rejoin fencing via SWIM incarnation high-water marks (`MembershipContext.java:58-60`).

**Ownership of truth: six concurrent views** — SWIM health map; `PresenceSampler.stableMembers` (still feeds boot latch + reconciler peak, `PresenceSampler.java:73-78`); `MembershipFsm` (documented authority); `TopologyObserver` dial set + `coreNodeIds` + `previousCoreMembers` (`:257-275`); `LeaderElectionContext.currentTopology` (raw transport, `:148`); KV (`LeaderKey`, lifecycle atoms, `ClusterConfigKey`). Wiring is circular: FSM evicts the sampler (`MembershipFsm.java:592-594`), sampler's down-crossing feeds the FSM (`AetherNode.java:1813`), FSM counts feed the snapshot the observer's quorum reads, which activates the consensus whose term stamps membership decisions.

**F1 — HIGH: transport-recovered member terminally evicted; possible permanent fencing.** (H3 in main register.)
**F2 — HIGH: DEPARTING is an inescapable trap.** (H2 in main register.)
**F3 — HIGH: leader writes unfenced.** (H4.)
**F4 — HIGH: election wedge on successful-but-rejected proposal.** (H5.) Stuck-threshold fallback (`LeaderElectionContext.java:455-467`; `LeaderElectionState.java:726`) unreachable here.
**F5 — MEDIUM: two quorum denominators.** (M1.)
**F6 — MEDIUM: `healthyOnDutyCount` is presence, not health.** (M2.)
**F7 — MEDIUM: dead FSM events; operator drain bypasses the FSM; OBSERVED-never-healthy ghosts unbounded.** (M10.)
**F8 — MEDIUM: unversioned descriptor last-wins; downgrade guard is a symptom.** (M9.)
**F9 — MEDIUM: leader FSM violates its own dispatch contract.** (M12.)
**F10 — LOW:** ctx mutations outside `tx.handle` (observability loss, `Fsm.java:132-135`); `UP_HYSTERESIS=1` makes streak machinery dead weight (`MembershipFsm.java:116, 679-683`).

**Open:** mixed-wipe restart (term regression vs logIndex monotonicity, `TopologyObserver.java:696-698, 725`; `isDecommissioned` may suppress legitimate reseeds); `onLivenessGone` ≡ QUIC disconnect vs documented probe-window semantics; fix ordering vs #245.

## Appendix D — Aether consumers report (agent consumers-audit-2)

**Map.** SWIM → `PresenceSampler` (debounce, peak latch; `PresenceSampler.java:287, 443, 479`) → `MembershipFsm.countedMembers`. Consumers: CDM `activeNodes()` = countedMembers minus passive (`ClusterDeploymentState.java:660-667`; wired `AetherNode.java:1124-1126`); `MembershipDecision` → `cdm.onMembershipDecision` (`AetherNode.java:3427-3443`); `NodeRemoved/Decommissioned` → `handleNodeRemoval` (KV cleanup; `ClusterDeploymentState.java:1342-1372`) then `reconcile()` (`:278-282`); same decisions drive DHT-ring prune + QUIC disconnect (`AetherNode.java:1617-1622, 1900-1903`). `LeaderReconciler`: leader-pinned, event-only (periodic tick deliberately removed, `LeaderReconciler.java:41-47`); 100ms CAS-debounce (`:125, 479-499`); leader-activation quiesce 1.5×nttDepartureTimeout (22.5s; `MembershipConfig.java:27`); deficit debounce 15s (`:934-958`); in-flight TTL 45s; quorumSafe gate (`:563-623`); actuates CTM (now pure actuator, slot loop retired, `ClusterTopologyManagerRecord.java:252-256`). Routing: forward `AccessibilityFilter` = `membershipFsm.reachableMembers` (`AetherNode.java:1505-1514`). Readiness plane: pong fan, leader-cached, stale-swept at pingInterval×3 (`AetherNode.java:1130-1138, 1218-1222, 1237-1244`); `allocatableNodes()` = activeNodes ∩ readyNodes (`ClusterDeploymentState.java:691-697`). Generation: leader `Epoch(term, counter)` per ping interval (`AetherNode.java:1229-1234`); reached-full pre-latch on re-election (`LeaderReconciler.java:290-294`); quiescence verdict pure (`ClusterQuiescenceEvaluator.java:29-46`). Rebalance: 1 move/reconcile, 2-tick hysteresis (`ClusterDeploymentState.java:2003-2093`). Note: `aether/slice/.../topology/SliceTopology.java` is the *application* dependency graph, unrelated to cluster topology despite the package name.

**H1 — operator scale wedge** (H1 in main register). **H2 — ghost window: unallocatable yet routable; no replacement instances** (H7). **M3 — CTM `inQuorum` dead code / false safety claim** (M3). **M4 — empty-supplier mass-cleanup hazard** (M4). **M5 — fast-rejoin phantom ACTIVE entries** (M5). **L6 — cleanup failure drops triggered reconcile.** **L7 — drain grace-terminate ignores leader change; heartbeat-only drain has no failover handover** (`ClusterTopologyManager.java:78-81`). **L8 — debounce follow-up forges NTT_FIRE trigger identity** (`LeaderReconciler.java:496-498`).

**Consistency verdict:** three signal planes — FSM countedMembers (membership/routing/cleanup), MembershipDecision deltas (discrete actions), heartbeat readiness (allocation/drain). (1)+(2) coherent if differently debounced; (3) independent and *faster* on hard kills → routable-but-unallocatable divergence for the full ghost window.

**Simplification:** strip CTM dead state (≈half the record); collapse four factory overloads; split `ClusterDeploymentState.java` (2,229 lines); rename `slice/topology`.

## Appendix E — Spec-vs-implementation report (agent spec-audit)

**C1.** Transport→SWIM bridge re-introduced against `swim-driven-topology-spec.md:100-101` (Decision 6, ordered full revert); bridge in `SwimProtocol.java:1055` + `SwimHealthState.java:177` + `AetherNode.java:2619-2642`; `MembershipFsm.onPeerConnected` still exists though spec step S5 (`spec:235`) ordered removal. No spec documents the bridge.

**C2.** Decision 8's SWIM health gate bypassed: gate optional ("Defaults to empty → allows all reconnects", `QuicClusterNetwork.java:221`); production path is the gate-free desired-set (`AetherNode.java:1953`; `QuicClusterNetwork.java:1578-1583`). FSM "already encodes membership+health" rationale is circular per C1.

**H1.** Transport autonomy invariants (Decision 7: "transport must not initiate CONNECTING on its own... stops at EVICTED"; "QUIC never removes a NodeId on its own", `spec:103-104, 180-183`) broken by three undocumented mechanisms: zombie TTL sweep with same-tick re-dial (`QuicClusterNetwork.java:1379-1420`); CONNECTING-staleness force-evict + per-dial timeout (`:1505-1525, 847-870`); dial-time DNS re-resolution (`:795-835`) + close-listener eviction (`~:946`).

**H2.** Who drives topology — three-way contradiction: keystone (SWIM→QUIC direct, `spec:172-178`, Approved RC1) vs membership-architecture-v2 (FSM removed; "Implementation pending") vs code (FSM deepened, desired-set mediated, `AetherNode.java:2720-2735, 3162`). `cluster-generation-spec.md:477` claims NodeRemoved comes exclusively from `TopologyObserver.publishMembershipDeltas`, yet `processViewChange` emits cluster-visible PeerJoined (#245 log evidence).

**H3.** ANNOUNCE stop condition inverted (spec `:156` — own connection count, not ACK; impl stops on `inboundProbeReceived`, `SwimProtocol.java:1007-1010`); `JOIN_TIMEOUT` (spec `:92, 137, 209`) never delivered programmatically (`:1018-1026`); cadence 2 Hz vs spec'd 1 Hz (window matches).

**H4.** ANNOUNCE receive-path order inverted ("load-bearing" per spec `:151-156`): rate-limit first at transport (`NettySwimTransport.java:343-348`); known-NodeId drop absent (`JoinAnnounced` unconditional, `SwimProtocol.java:885`); cluster-mismatch WARN per-datagram not once-per-source (`:843-846`); S6 gossip piggyback of Announce not implemented.

**H5.** "ANNOUNCE is the only join path" (spec `:79`) false: `onPeerConnected` accepts unknown inbound nodes via Hello (`buildUnknownNodeInfo`, `QuicClusterNetwork.java:~976-980`), unvalidated by ANNOUNCE gates. (KV-replay seeding removal, Decision 9 — compliant.)

**M1.** cluster-generation-spec wrong on mechanics: `HealthReconciler` doesn't exist; `NodeLifecycleKey` deleted (`AetherNode.java:3343`; v2 spec `:281`); snapshot distribution presence-derived (`PresenceGenerationSnapshotSource`, `AetherNode.java:440`) not leader MetricsPing piggyback; §13 deletions never happened (`TopologyObserver` retains `tombstonedNodes` `:266, 415`, `handleSetClusterSize` `:104, 821`).

**M2.** Undocumented load-bearing mechanisms: address-downgrade guard (`MembershipFsm.java:759-767`); LRP scheduling (`SwimProtocol.java:143-163, 726-738`); incarnation-gated REMOVED re-admission (`QuicClusterNetwork.java:1545-1560`); tombstone-clear on self-ANNOUNCE (`SwimProtocol.java:850-856`).

**Cosmetic.** Stale `HealthReconciler` comments (`NettyClusterNetwork.java:199, 412`; `QuicClusterNetwork.java:963`); cluster-management vs cluster-bootstrap incompatible TOML schemas; keystone line refs stale.

**Verdict:** keystone spec architecturally obsolete while marked Approved for RC1. Rewrite to the FSM-mediated desired-set + transport-hint model (or revert per spec before chasing #245), mark supersessions explicitly.

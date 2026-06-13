# Session Handover — 2026-05-01

## ⚡ Top Priority Next Session

**Implement the membership-architecture redesign per [`aether/docs/specs/membership-architecture-spec.md`](../../specs/membership-architecture-spec.md).**

Begin with Phase R1 (Rabia `Paused` state). Full phased plan in §9 of the spec. ~15-20 days of work; each phase is independently testable.

The current architecture has chronic cross-layer signal-flow bugs that no incremental fix has resolved. The spec proposes a strict 8-layer one-way signal flow with first-class cold-boot phase. Architectural commits already on this branch (`5c29a104f` through `d53b0021e`) are forward-compatible and serve as the starting point.

## TL;DR (this session)

Multi-day investigation into rc1 cold-boot regression. Identified the root cause (QUIC promoted to canonical for membership in Q6 series, 2026-04-25 — broke noise filtering). Landed a chain of architectural fixes. **02-chaos still 1P/3F** — the underlying cold-boot QUIC handshake storm now propagates through SWIM/HealthReconciler instead of QUIC directly. Architectural cleanup is real and good; deeper redesign is required for full chaos test stability — see TOP PRIORITY above.

## Branch state

- Branch: `release-1.0.0-rc1`
- HEAD: `d53b0021e`
- Tags:
  - `rc1-checkpoint-quic-fix-2026-04-30` (`65f50bf5b`) — pre-experiment baseline
  - `rc1-pre-option-b-2026-05-01` (`d53b0021e`) — pre-Option-B fallback (Option B was attempted then discarded)

## Commits landed this session (chronological, kept)

| Hash | Subject |
|---|---|
| `cff7d4a35` | fix(consensus): re-register peer in finalizeReconnect when topology forgot it |
| `65f50bf5b` | chore(integration): add remote_scp wrapper, SSH/SCP keepalive to detect TCP stalls |
| `c20fb9d43` | experiment(ctm): Fix C — drop stability anchor bumps on raw QUIC peer events |
| `a36b137f7` | experiment(consensus): revert step-F proposal timeout bumps (8s→3s, 10s→5s) |
| `064dda9a4` | Revert "experiment(consensus): revert step-F proposal timeout bumps" |
| `ed53a2cfb` | chore(integration): add --skip-image-push flag to reuse remote image across reruns |
| `8e244f33a` | Revert "experiment(ctm): Fix C — drop stability anchor bumps" |
| `5c29a104f` | experiment(health): demote QUIC from canonical HEALTHY source — restore SWIM-driven membership flow |
| `1ee034355` | fix(consensus): gate leader-proposal on KV-sync — prevent fresh-boot self-proposal race |
| `9a0ad5409` | chore(integration): wait_for_all_nodes_ready before next test — guarantee post-restart readiness on every cluster-B node |
| `1ffc3f91a` | fix(consensus): independent peer-observation timer in Electing — preempt own proposal when peer leader committed |
| `3c66e9e65` | fix(quorum): TopologyObserver becomes canonical QuorumStateNotification publisher — phase A completion for the loss path |
| `f26ee30e2` | fix(quorum): defer TopologyObserver.evaluateQuorumState until start() — prevent ctor-time NPE |
| `d53b0021e` | fix(quorum): TopologyObserver no longer reacts to QUIC connection events — quorum signals only from authoritative membership |

## Commits attempted then discarded

- **Option B** (`3d1509163`, `refactor(membership): decouple QUIC transport from authoritative membership`) — full architectural cleanup adding `TopologyMembershipPublisher` to drive `addNode`/`removeNode`/`TopologyChangeNotification` from KV `NodeLifecycleKey` writes, demoting QUIC entirely. Compiled clean, all unit tests passed (220+533+351+29 = 1133 tests green), but **02-chaos still showed the same cascade**. Cause: SWIM/HealthReconciler reactions to cold-boot QUIC noise generate authoritative KV writes that the new publisher faithfully translates to `evaluateQuorumState` calls. The bug moved one layer up. Discarded via `git reset --hard rc1-pre-option-b-2026-05-01`.

## Architectural narrative

### Where we started

Investigation traced the rc1 cold-boot regression to the **Q6 commit series (2026-04-25)** in CHANGELOG.md. The Q6 series promoted QUIC to canonical for HEALTHY observations, displacing SWIM. Combined with step-F (`8569b39f3`), this made every QUIC handshake event a *membership* signal — bypassing SWIM's intentional noise-filtering. Cold-boot QUIC handshake storms cascaded into RabiaEngine resets via `clusterDisconnected()`.

### What we built

A coherent demotion of QUIC from the membership pipeline:

1. **Phase A** (`5c29a104f`): SWIM is canonical for HEALTHY observations. QUIC's `processViewChange` no longer writes to `PeerObservationStore`. SWIM Stopped/Starting peer-event handlers restored.
2. **Fix-1 / KV-sync grace** (`1ee034355`): New `AwaitingKvSync` FSM state defers leader-proposal for 3s on cold boot to absorb peer KV-sync. Short-circuits to `Led(thatLeader)` if a peer-committed leader is observed.
3. **Fix A / peer-observation timer** (`1ffc3f91a`): `Electing` state now polls `currentLeaderFromKvSupplier` every 500ms (independent of `proposalInFlight` gate). FSM can preempt its own self-proposal when a peer leader is observed.
4. **Option C — canonical quorum publisher** (`3c66e9e65` + `f26ee30e2`): `TopologyObserver.evaluateQuorumState()` becomes the canonical publisher of `QuorumStateNotification`. QUIC and Netty both stop publishing. Latch-based edge-transition logic. Startup-NPE fix gates evaluation on `started=true`.
5. **Phase A completion** (`d53b0021e`): `TopologyObserver` no longer triggers `evaluateQuorumState` on raw QUIC connection events (`handleConnectionFailed`/`handleConnectionEstablished`). Only authoritative membership signals (addNode/removeNode/handleSetClusterSize) drive quorum decisions.

### Validated finding

**The cold-boot regression has multiple layers.** Phase A + Fix-1 + Fix A + Option C + Phase A completion together resolve:
- ✅ Cold-boot consensus stall reduced from 240+s to 4–15s
- ✅ Initial cluster formation goes from 304s to 11s
- ✅ Blueprint deploy: 159s → 3s
- ✅ test-kill-leader (the chaos test that previously failed) now passes its main assertions

**What's still broken**: between cluster-B sub-tests, `restart_all_nodes`'s compose-down/up cycle leaves node-1 in `consensus=DOWN` for ~10 minutes. `/health/ready` shows: consensus DOWN ("Consensus not established"), routes UP, quorum UP (Connected peers: 4). Quorum publisher fires `established` then `disappeared` then `established` again repeatedly. The latch flips false→true via authoritative KV writes from HealthReconciler reacting to SWIM observations during the cold-boot QUIC handshake noise.

### Why Option B didn't help

Even with QUIC fully demoted (no `register/unregisterPeer`, no `TopologyChangeNotification` emission, new KV-driven publisher), the cascade still occurred. The chain: cold-boot QUIC handshake noise → SWIM observations → HealthReconciler writes flapping KV `NodeLifecycleKey` states → `TopologyMembershipPublisher` faithfully translates → `evaluateQuorumState` flips. The bug source moved up the stack but the visible cascade is identical.

## Test results progression

| Run | Config | 02-chaos | Notes |
|---|---|---|---|
| Baseline (HEAD `263f13a6d`) | rc1 status quo | 2P/2F | full suite 8P/7F |
| QUIC fix only (`cff7d4a35`) | + finalizeReconnect | 2P/2F | full suite 9P/6F (+1: 06-deployment) |
| Fix C + 3s timeouts | experiment | 2P/2F | full suite 5P/10F (regression) — discarded |
| Fix C + 8s timeouts | experiment | 3P/1F | only known passing 02-chaos config |
| Phase A | demote QUIC HEALTHY | 1P/3F | `restart_all_nodes` now hard-fails on un-ready node-1 |
| Fix-1 (KV-sync grace) | + AwaitingKvSync | 1P/3F | exposes Bug B (Electing ignores peer leader) |
| Fix A (peer-observation timer) | + 500ms KV poll | 1P/3F | same outcome — bug deeper |
| Option C (canonical publisher) | + TopologyObserver publisher | 1P/3F | same — QUIC connection events still drive evaluateQuorumState |
| Phase A completion (`d53b0021e`) | drop QUIC connection event triggers | 1P/3F | same — register/unregisterPeer paths still active |
| Option B (full decouple) | discarded | 1P/3F | same — SWIM/HealthReconciler is the new noise source |

## Open issues for next session

### High priority

1. **Cold-boot SWIM/HealthReconciler noise generates flappy KV writes.** The architectural cleanup is now solid (QUIC → SWIM canonical → HealthReconciler → KV → publisher), but during the cold-boot QUIC handshake storm, SWIM observations cause HealthReconciler to write transient `NodeLifecycleKey` states that flap. Investigation needed:
   - Does HealthReconciler debounce SWIM observations or react immediately?
   - What's the SWIM suspect window during cold boot?
   - Can HealthReconciler distinguish "node was never up" from "node went down"?
   - Memory note `project_destructive_compose_restart_policy.md` may have context

2. **`/health/ready` reports DOWN consensus on node-1 for ~10 minutes after `compose down -v && up -d`.** Even after Option B's removal of QUIC's direct membership mutations, the engine resets to Phase 0 mid-boot. Capture node-1 logs with `org.pragmatica.consensus.rabia.RabiaEngine=DEBUG` during a compose cycle to see what triggers the reset.

3. **Test-harness `restart_all_nodes` regression masked under earlier rc1 versions.** With the new `wait_for_all_nodes_ready` check (`9a0ad5409`), the harness now correctly catches that node-1 isn't reaching `/health/ready=UP` within 90s. Pre-Phase-A this was masked because `restart_all_nodes` rotated `MGMT_ENTRY_POINT` to a different node and the bar was lower.

### Medium priority

4. **`AetherNode.attachQuicPeerStateListener` still emits `HealthSignal.SwimHint(HEALTHY)` via SwimHint pathway** (flagged at `aether/node/src/main/java/.../AetherNode.java:1502-1518`). This is a side-channel from QUIC events into health observations — not addressed by Phase A's PeerObservationStore demotion. May be redundant with current SWIM canonical path; investigate and consider Phase D.

5. **Inter-test slowness root cause** (originally chased as a separate bug): The 3-min `proposal_timeout` cascade observed earlier appears to be a side-effect of the consensus reset, not an independent bug. Should resolve naturally if SWIM/HealthReconciler debouncing fix lands.

### Low priority

6. **JBCT format drift**: 66 files in `integrations/consensus` have pre-existing format issues (zero new from this session's work). Land a separate `style(consensus): apply jbct:format across module` cleanup commit when convenient.

7. **NettyClusterNetwork quorum publisher**: still has `quorumEstablished.get()` startup oscillation guard removed in Option C. Netty is benchmark-only (not production transport — QUIC is), so low risk. Re-audit if Netty becomes production again.

## Files of interest (architectural touchpoints)

### Production (changed this session)

- `integrations/consensus/src/main/java/org/pragmatica/consensus/topology/TopologyObserver.java` — canonical QuorumStateNotification publisher; latch + edge-transition logic
- `integrations/consensus/src/main/java/org/pragmatica/consensus/leader/fsm/LeaderElectionContext.java` — DEFAULT_KV_SYNC_GRACE_DELAY (3s), DEFAULT_PEER_OBSERVATION_INTERVAL (500ms), DEFAULT_PROPOSAL_TIMEOUT (3s, restored)
- `integrations/consensus/src/main/java/org/pragmatica/consensus/leader/fsm/LeaderElectionState.java` — AwaitingKvSync state, peer-observation timer in Electing/ReElecting
- `integrations/consensus/src/main/java/org/pragmatica/consensus/net/quic/QuicClusterNetwork.java` — finalizeReconnect peer re-registration; reportPeerHealthy/Faulty calls removed; QuorumStateNotification publishing removed
- `integrations/consensus/src/main/java/org/pragmatica/consensus/net/netty/NettyClusterNetwork.java` — symmetric to QUIC
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/cluster/ClusterTopologyManagerRecord.java` — onQuicPeerJoined/Left now no-ops; HIGH-18 anchor restored only on real topology events
- `aether/node/src/main/java/org/pragmatica/aether/node/AetherNode.java` — attachQuicHealthReporter wiring deleted; KV NodeLifecycleKey listener (partial wiring; expected to be enriched in Phase D)
- `aether/node/src/main/java/org/pragmatica/aether/node/health/fsm/SwimHealthState.java` — Stopped/Starting peer-event handlers restored

### Test infrastructure (changed this session)

- `aether/tests/integration/lib/cluster.sh` — `wait_for_all_nodes_ready` function with diagnostic logging
- `aether/tests/integration/lib/common.sh` — `remote_scp` wrapper, SSH keepalive, `SSH_OPTS` array
- `aether/tests/integration/run-tests.sh` — `--skip-image-push` flag, all SCP/SSH calls now use wrappers

### Investigation references

- `aether/docs/internal/progress/session-handover-2026-04-28.md` — prior session
- This file: `session-handover-2026-05-01.md`

## Suggested order for next session

### TASK 1 (priority): Implement the membership-architecture redesign

A complete specification has been written at **[`aether/docs/specs/membership-architecture-spec.md`](../../specs/membership-architecture-spec.md)**. This is the principled answer to the chronic cross-layer signal-flow bugs we chased all session. **Read it first, then begin implementation.**

The spec proposes:
- Strict 8-layer one-way signal flow (Transport → SWIM → HealthReconciler → TopologyObserver → Rabia → Leader Election → Auto-Heal → Node Lifecycle)
- First-class `BOOTING` / `NORMAL` / `RECOVERING` cluster phase, broadcast via consensus
- Rabia state durable across transient quorum loss (new `Paused` state — no Phase 0 reset)
- HealthReconciler with quorum-of-observations + cooldown + cold-boot suppression
- TopologyObserver as a pure projection of KV atoms (writeable APIs removed)
- Rank-staircase leader election (eliminates cold-boot election storm structurally)
- Auto-heal suspended in BOOTING/RECOVERING
- Test contract aligned with operator-visible signals only

**Phased rollout (R1 through R10) is described in §9 of the spec.** Estimated 15–20 days of work plus 3–5 days of integration testing. Each phase is independently testable.

**Start with Phase R1** (Rabia Paused state) — it unblocks the rest of the architecture by eliminating the engine-reset class of bugs. Then R2 (SWIM canonical), R3 (HealthReconciler quorum), R4 (TopologyObserver pure projection), R5 (Transport narrowed). Phases R6–R10 are smaller and can run in parallel once R1–R4 are landed.

The architectural cleanup commits already on `release-1.0.0-rc1` (`5c29a104f` Phase A through `d53b0021e` Phase A completion) are forward-compatible with the redesign and serve as the starting point — DO NOT roll them back.

### Fallback paths (if redesign is deferred)

**Path P3:** Run the full 15-suite integration test on remote with current HEAD `d53b0021e` to measure the broader impact of all architectural changes already landed. The accumulated work has improved cold-boot from 240+s to seconds and added a coherent SWIM-canonical layering — other suites likely improved too. Use the data to decide which residual issues are RC1-blockers vs. nice-to-haves.

**Path P1 (chaos-specific):** If P3 shows other suites regressed, consider rolling back to `rc1-checkpoint-quic-fix-2026-04-30` (the QUIC fix + harness only) and starting fresh with Option A as the cold-boot fix. Smaller surface, easier to ship — but does not solve the chronic cross-layer signal-flow class of bugs (those need the redesign in TASK 1).

**Path P2 (architecturally pure but partial):** Investigate SWIM/HealthReconciler cold-boot debouncing. Find why authoritative KV writes flap during compose-up. Less scope than the full redesign but addresses only the most-observed symptom; doesn't fix the architectural cycles.

## Build & verification commands

```bash
# Build aether-node JAR
mvn -pl aether/node install -DskipTests -am

# Unit tests for affected modules
mvn -pl integrations/consensus -am test
mvn -pl aether/node -am test
mvn -pl aether/aether-deployment -am test

# JBCT lint (consensus module — needs explicit -Djbct.skip=false)
mvn jbct:check -pl integrations/consensus -Djbct.skip=false

# Integration test (remote, single suite)
cd aether/tests/integration && ./run-tests.sh --env remote --suites 02 --skip-build [--skip-image-push]

# Full suite
cd aether/tests/integration && ./run-tests.sh --env remote --skip-build [--skip-image-push]

# Rollback to checkpoint
git reset --hard rc1-checkpoint-quic-fix-2026-04-30   # all work discarded
git reset --hard rc1-pre-option-b-2026-05-01           # current HEAD
```

## Key data points captured

- Cold-boot Initial_5_nodes: was 240+s (pre-Phase A), now 3-15s
- Blueprint deploy: was 159s, now 3s
- Cluster formation: was 304s, now 11s
- 533 unit tests pass on `integrations/consensus`
- 1118+ unit tests pass across consensus + cluster + aether-node + aether-deployment
- Q6 series identified at commits `373359dc3 → 5016f2b3f → 52ab4153c → 7052bb1a0 → 736934d24` (2026-04-25) — the architectural inflection
- Step-F at `8569b39f3` (2026-04-27) — added CTM-on-LeaderChange, QUIC-left → reconcile, proposal timeouts 3s→8s

## Memory updates needed

If you do `/ndx-recall-handover` next session, mark these as new:

- `project_session_handover_20260501.md` — pointer to this file
- `feedback_quic_demotion_pattern.md` — Phase A's principle: transport (QUIC) is for transport hygiene; membership (SWIM/HealthReconciler/KV atoms) is canonical. Don't conflate.
- `project_swim_healthreconciler_coldboot_noise.md` — open architectural concern: SWIM observations during cold-boot QUIC storm cause HealthReconciler to write flapping authoritative KV states. Real fix needed for full chaos test stability.

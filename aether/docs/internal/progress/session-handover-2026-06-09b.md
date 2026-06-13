# Session Handover — 2026-06-09b (RC1 stabilization: #126/#94/#130 shipped; #131 never-READY root nailed + fix in progress)

**Branch:** `release-1.0.0-rc1` · **HEAD:** `f64ac97b5` (pushed, origin in sync) · tag `v1.0.0-rc1-candidate` at HEAD.

## TL;DR
Three fixes shipped + pushed + Docker-validated this session; a fourth (#131 never-READY) has its root precisely identified and a fix being implemented (consensus-core, with a known regression trap).

## Shipped + pushed + validated
1. **#126 restore-quiesce harness fix** (`e4d17b98c`) — prior session; restore's leader-bound helpers raw-curled a pinned dead endpoint → `curl rc=7` mis-read as "did not quiesce". Fixed via `_resolve_live_endpoint`.
2. **#94 NODE_FAILED edge-trigger** (`992139096`→`d2352f449`) — a CTM replacement dying at steady core size never fired `MembershipDecision.NodeRemoved` (the membership delta is recomputed only via `evaluateQuorumState`, re-run only on `addNode`/`removeNode`/death/start; a replacement death has no following join). Fix: `MembershipFsm.onConfirmedDeparture` routes a new `NetworkServiceMessage.ReevaluateMembership` to `TopologyObserver` (router thread) → `handleReevaluateMembership` → `evaluateQuorumState()` (idempotent, CAS+`previousCoreMembers`-gated). Once-per-edge, NOT per-tick (per-tick regressed READY-convergence 0-20s→600s). Plus leader-gated delivery + `ClusterEvent.type()`/`ClusterEventView`. **Validated: 02-chaos 6p/0f.**
3. **#130 cluster-canonical event gating owner→leader** (`bcdd3f3c7`/`f64ac97b5`) — generalized #94's delivery insight: `NODE_JOINED` (onPeerJoined), `LEADER_*` (onLeaderChange), `QUORUM_*` (onQuorumStateChange), `NODE_LIFECYCLE_CHANGED`, `GENERATION_CHANGED` flipped from `emit` (owner-gated) to `emitAsLeader` (leader-gated) in `ClusterEventAggregator`. Owner-gating dropped events when the partition-0 owner was mid-churn/not-observing. NODE_JOINED stays sourced from transport `PeerJoined` (the membership delta doesn't fire for a not-yet-counted JOINING replacement). Operational events stay owner-gated; per-node facts stay `emitLocal`. **Validated: 12-network `Replacement … observed on /api/events` passes; unit 18 aggregator tests.**

## Full-suite snapshot (HEAD before #130, image ~f64ac97b5): 15 suites, 10 pass / 5 fail
- 02-chaos 5p/1f — NODE_FAILED-under-load (SWIM-latency, separate); READY-600s (=#131)
- 03-scaling 2p/1f — scale-down 74% error-rate under load (drain-under-load, separate)
- 05-security 1p/2f — **#95** (TLS NOT_CONFIGURED + admin-auth bypassed under INSECURE_DEV_MODE)
- 12-network 2p/2f — READY-600s (=#131); connectedPeerCount=3 (env-transient); NODE_JOINED-replacement (FIXED by #130); NODE_FAILED-replacement (SWIM-latency)
- 13-edge-cases 0p/3f — likely cascade off degraded cluster-B state + #93 drain-budget 404≠409 + a kill-of-already-gone-container artifact

## #131 NEVER-READY — root CONFIRMED, two wrong-layer fixes reverted, fix direction identified (NOT yet implemented)
**Symptom:** after a partition restore, `4+ healthy cores present` passes in 0s but only 2-3 of 5 report READY for >600s. `READY = !draining && consensusActive && subsystemsReady` (`NodeReportedStateHolder`). `consensusActive` = `RabiaEngine.isActive()` sampled LIVE (self-heals; docstring 18-27 documents the edge→level migration that killed this "stuck SYNCING" class for the consensus conjunct). `subsystemsReady` = one-shot latch set only on NDM `Dormant→Active` (`NodeDeploymentState.Active.onEntry`→`markSubsystemsReady`), which fires only on `QuorumEstablished`.

**CONFIRMED BOTTLENECK (live, two repros):** after a **5s** dual-signal partition+heal, SWIM *health* recovers in ~4s (12-net S06 passes) but the rejoined node's `TopologyObserver.quorumEstablished` stays **false for ~4 MIN** (node-4: lost 08:24:31→re-est 08:28:38; and in the fix-run lost 09:41:37→re-est 09:45:45). The 2nd repro had a `reconcileQuorumLevel` re-evaluating EVERY tick and it STILL couldn't flip — because it gates on `haveQuorum()` = `view.healthyOnDutyCount() >= quorumSize()`, which was **false the whole window**. So the bottleneck is UPSTREAM of the quorum bit: the rejoined node's **membership view (`healthyOnDutyCount`) doesn't recover quorum-many ON_DUTY healthy peers for ~4 min**.

**LEADING HYPOTHESIS — circular self-sustaining dependency:** `inQuorum()` = `quorumEstablished::get` (`TopologyObserver:457`), set from `haveQuorum()` (membership view). Per `TopologyObserver:127`, `quorumEstablished`/`inQuorum()` **gates whether the node advertises/counts ON_DUTY peers**, and `healthyOnDutyCount` (ON_DUTY count) **feeds** `haveQuorum()`→`quorumEstablished`. Once quorum is lost: `!quorumEstablished` → ON_DUTY suppressed → `healthyOnDutyCount` low → `haveQuorum` false → `quorumEstablished` stays false. Self-sustaining until SWIM's slow evict+re-add (~4 min) breaks it. (NEEDS one confirmation: exact `MembershipView` ON_DUTY-vs-`inQuorum` gating — whether `!inQuorum` zeroes the node's count of OTHERS' ON_DUTY vs only suppresses its OWN advertisement.)

**FIX DIRECTION (user's instinct, sound):** RabiaEngine already emits `ConsensusActive`/`ConsensusPassive` on true consensus-active edges (`notifyConsensusStateTransition` `RabiaEngine:966`) and is the documented authoritative `ClusterStateNotification` source in steady state (`RabiaEngine:103`). Consensus-active = actual voter connectivity + log progress — **OUTSIDE** the membership-view circular loop. Gating NDM activation (and/or `inQuorum`) on RabiaEngine `ConsensusActive` would break the deadlock + converge fast — **IF Rabia recovers faster than the membership view after heal.**

**DECISIVE INSTRUMENTATION (do FIRST, no blind fix):** on the rejoined node across the partition-heal window capture (a) `RabiaEngine.isActive()`/`ConsensusActive` emit timing, (b) QUIC connection state to the majority, (c) `healthyOnDutyCount`.
- **Outcome A** (Rabia recovers FAST, membership lags) → fix = drive activation off RabiaEngine `ConsensusActive` + break the circular ON_DUTY gate.
- **Outcome B** (Rabia ALSO lags ~4 min) → root is **QUIC-reconnect-after-docker-network-heal** (same as 12-net `connectedPeerCount=3`) → fix the transport reconnect.
- Weak signal: node-4 re-activated via the TopologyObserver "cold-start/resume originator" path, NOT an earlier RabiaEngine `ConsensusActive` → leans **B**, not conclusive.

**TWO WRONG-LAYER ATTEMPTS (both reverted to `f64ac97b5`, harmless-but-ineffective):** (1) NDM `reconcileQuorumLevel` gated on `inQuorum()` — false whole window; (2) TopologyObserver `reconcileQuorumLevel` extracted from `evaluateQuorumState` (CAS+route minus `publishMembershipDeltas`) called on `initReconcile` tick — `haveQuorum()` false whole window (this PROVED the upstream bottleneck). Validation: 02-chaos all restores fast (no regression) but 12-net partition-heal STILL ~4 min. **Reliable trigger = 12-net partition-quorum-gate; 02-chaos does NOT reliably reproduce (don't validate on it).** `env -u HCLOUD_TOKEN`; never `verify`/`./build.sh` with HCLOUD_TOKEN; build-runner owns maven.

## State / env
- HEAD `f64ac97b5`, tree CLEAN. Cluster B cleaned (`down -v`). No orphan run-tests.
- `$TARGET_HOST`/`$AETHER_SSH_KEY`/`$AETHER_SSH_USER`/`$AETHER_API_KEY`(default `aether-integration-test-key`) set; reference by name. Cluster B = 5161-5165 originals / 5166+ replacements; leader-bound routes forward.
- Capture script for the stuck condition: `/tmp/capture-neverready.sh` (the /api/nodes/status grep parser undercounts — fix the per-node parse if reused).
- JBCT formatter still destructive (don't run `jbct:process`); bug report `/tmp/jbct-format-bugreport.md` unfiled.

## Open backlog
#131 (this fix, in progress); #94 NODE_FAILED-**under-load** (SWIM-detection-latency for replacement deaths — separate from the edge-trigger, still red); #95 secure-mode cluster-B variant; #93 drain-budget 404≠409; #91 DHT durability; #97 budget-stress; 03 scale-down-under-load 74% error rate; 13-edge cascade.

## Key learnings
- **Validate against the RELIABLE trigger.** 02-chaos doesn't reliably reproduce #131; 12-network partition-heal does. A fix "passing" on the unreliable suite is inconclusive (burned a cycle here).
- **The quorum/membership evaluation is edge-triggered throughout** (`evaluateQuorumState`), and that's the recurring bug class: #94 (delta not re-poked on death), #131 (quorum bit not re-poked on partition-heal). The level-vs-edge migration done for `consensusActive` is the template.
- **Per-tick `evaluateQuorumState` regresses convergence** — the culprit is `publishMembershipDeltas`, so level-recovery must exclude it.

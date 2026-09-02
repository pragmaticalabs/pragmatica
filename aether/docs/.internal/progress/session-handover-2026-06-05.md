# Session Handover — 2026-06-05

**Branch:** `release-1.0.0-rc1` · **HEAD:** `e65766772` · **4 commits UNPUSHED** (the membership-FSM Phase-1/Fix-A/Phase-2 set; everything ≤ `4a074a21b` is already pushed). Tree clean.

## TL;DR
The headline is the **membership-convergence FSM**, taken from spec → shadow → **live cutover** in one session. The per-member FSM (`OBSERVED/MEMBER/SUSPECT/DEPARTING/DEAD`) is now the **live authority for member death/eviction** — its DEAD edge drives `ntt.evict`, consensus-independent (SWIM + liveness). This **deleted the LeaderReconciler death flag-tangle** (`swimFaulty`/`livenessGone`/`terminalIncarnation` + co-confirmation methods) and the shadow divergence reporter — **net −501 lines**. It **fixed the under-load `NODE_FAILED` wedge**: 02-chaos `5p/1f → 6p/0f`, kill-under-load error rate `~40% → 0%`, no regression. Earlier in the session (already pushed): node-naming fixes (R1/R4/R5), incarnation unified onto SWIM (#1), HLC packed-long overflow fixed, incarnation-fenced eviction (#2, Docker-validated same-id rejoin). **Full 15-suite run done** (`bh327yk4n`): cluster A all-green, 02-chaos 6/0 (wedge fixed), all remaining cluster-B failures pre-existing (CTM scale-up, security dev-mode, QUIC-reconnect) — **zero new regression**.

## Commits this session
### Pushed (origin synced through `4a074a21b`)
| Commit | What |
|--------|------|
| `87f1aef52` | fix(node): require explicit stable node-id (fail-fast) + canonical blank-cluster prefix (R1+R4) |
| `3343dc0bc` | fix(test-harness): canonical `aether-<cluster>-node-` cleanup filters + bootable plain compose (R5) |
| `0d27e2e89` | feat(membership): unify incarnation onto SWIM self-incarnation; drop nanoTime `BootEpoch` (§9.4 #1) |
| `bac67512d` | docs(spec): §9.4 #1 done (G1+G2) |
| `6ee0eaaf4` | fix(hlc): epoch-millis physical clock fixes packed-long overflow + restores strict monotonicity |
| `521b8aa91` | feat(membership): incarnation-fenced eviction — higher incarnation un-fences same-id rejoin (§9.4 #2) |
| `7a7e81fd2` | docs(spec): §9.4 #2 done |
| `4a074a21b` | docs(spec): strike PASSIVE-as-learner (#3 rejected), #2 Docker-validated, #4 deferred |

### UNPUSHED (HEAD = `e65766772`, 4 commits)
| Commit | What |
|--------|------|
| `267e5aa50` | feat(membership): Phase-1 shadow FSM — state machine + shadow manager + divergence reporter (observe-only) |
| `ff6382281` | feat(membership): wire shadow FSM read-only taps behind `AETHER_MEMBERSHIP_FSM_SHADOW` flag (default off) |
| `dd1439285` | fix(membership): edge-driven promotion + seed-on-activate (fixes shadow `effective=0` found by chaos run) |
| `e65766772` | **feat(membership): Phase-2 cutover — FSM is live eviction authority (DEAD→`ntt.evict`); delete LeaderReconciler death flag-tangle + shadow reporter** |

## The membership FSM — Phase 0 → 1 → 2 (the spine of this session)
Spec: `aether/docs/specs/membership-convergence-fsm.md`. New code: `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/membership/fsm/` — `MembershipState` (sealed `FsmState`, 5 states, exhaustive transition table per §3.3, DEAD carries terminalIncarnation, DEAD+higher-incarnation→OBSERVED rejoin), `MembershipEvent` (13 sealed events), `MembershipContext`, `MembershipFsm` (manager). Built on the existing `integrations/statemachine` (`Fsm`/`FsmState`/`FsmObserver`/`FsmTestHarness`), cloning the `LeaderElectionState` idiom.

- **Phase 0 (model):** spec only.
- **Phase 1 (shadow):** ran the FSM in parallel, observe-only, leader-gated, behind a flag, logging `MEMBERSHIP-FSM-DIVERGENCE`. Docker-validated **zero regression** (5p/1f unchanged). The shadow surfaced **its own** modeling bug deterministically: `effective=0` always — leader-gated activation happens *after* formation (misses the formation-time `HealthyObserved` *edges*), and the 2-sample up-hysteresis never fires on *edges*. → **Fix A**.
- **Fix A:** promotion is now **edge-driven** (first `HealthyObserved` promotes OBSERVED→MEMBER) + **seed-on-activate** from `ntt.currentMembers()`; death stays SWIM/liveness-driven & independent.
- **Phase 2 (cutover, `e65766772`):** FSM is the **live authority**. Central entry-to-DEAD detection in `MemberTracking.dispatch` (fresh was-not-Dead→is-Dead edge) fires a **leader-gated, idempotent** `ntt.evict(nodeId)` covering all three DEAD paths (co-confirmed, graceful departed, join-grace-expired). Deleted the LeaderReconciler death machinery (3 fields + 7 methods) and the divergence reporter; removed the shadow flag (FSM always-on). **Provisioning untouched** (reads only `ntt.currentMembers()`).

### Why the cutover fixes the under-load wedge (the key insight)
Membership is **presence-derived**: `TopologyObserver.coreMemberIds() = ntt.currentMembers()` (it is the *single* `MembershipDecision` emitter). So the FSM's DEAD→`ntt.evict` mutates `stableMembers` → the presence-view drops the node → `TopologyObserver` routes `NodeRemoved` → all ~20 consumers + the `/api/events` `NODE_FAILED` emit. **No new emission code.** The under-load root was a **flag-tangle bug** (the FSM uses the *same* `swimFaulty ∧ livenessGone` signals but the clean state machine doesn't drop the gate under load) — confirming the spec's thesis.

### Spec decisions locked this session
- **#1 (incarnation) DONE:** single SWIM `selfIncarnation` authority; deleted the `nanoTime` `BootEpoch` (arbitrary per-JVM origin, not monotonic across restarts).
- **#2 (fencing) DONE + Docker-validated:** higher incarnation un-fences same-id rejoin (killed→evicted→`docker start` same id → rejoined READY in 16s).
- **#3 (PASSIVE-as-learner) REJECTED (user):** "a foreign artifact from raft-based systems. PASSIVE is for workers, the node is constructed differently, there is no simple way to transition between modes — by design." Rejoin is handled by incarnation fencing + single-snapshot sync; no learner. Spec §9.3 corrected.
- **#4 (serialize membership changes) DEFERRED:** evidence didn't support it as the churn fix; joint-consensus framing is another Raft import.

## Validation — full 15-suite run, post-cutover, clean remote (`bh327yk4n`)
**Cluster A (non-destructive): all green.** 00-smoke, 04-streaming, 06-deployment, 07-cluster-mgmt, 08-resources, 09-artifacts, 10-database, 11-observability, 14-storage, 15-delegation — **0 fails across every file.** The ≥1MB/cross-node artifact resolve (09) and 08-resources stay green under FSM authority.

**Cluster B (destructive):**
| Suite | Result | Note |
|-------|--------|------|
| **02-chaos** | **6 files / 0 fails** | **was 5p/1f** — under-load `NODE_FAILED` FIXED; kill-under-load **0%** error; leader-kill / Kill_2 / non-leader / S20-restart all green. This is the membership/death-detection suite — fully green under FSM authority. |
| 03-scaling | 2 files green, 1 file **4p/2f** | **pre-existing** scale-up-5→7-stuck (CTM provisioning; NOT membership) |
| 05-security | 1 file green, 2 files **4p/1f** | **pre-existing** TLS-rotation + admin-auth dev-mode skip paths (NOT membership) |
| 12-network | 2 files green, 1 file **1p/3f** | **pre-existing** QUIC-reconnect (`connectedPeerCount=2`) — leader can't see a node when transport is already degraded; **amplified here** by shared-cluster-B contamination (12 runs last in the destructive chain, so it inherits the degraded transport from 02/03/05). In the isolated 00+02+12 run 12-network was 3p/1f. NOT membership; `SWIM_detection_time` PASSes when the cluster is connected. |

**Verdict: zero new regression from the cutover.** All 7 cluster-B failures sit in suites with documented pre-existing roots (CTM scale-up, security dev-mode auth, QUIC transport); none touch membership-FSM code. The suite that most directly exercises death detection (02-chaos) is fully green.

## Key finding (kept temporary by user clarification)
The observable `NODE_FAILED` on `/api/events` is **published through a Rabia-replicated event log** (`ClusterEventLogPublisher.publish` → KV Put **through consensus** → replays → RingBuffer → `/api/events`). So consensus quietly re-enters the *event* path even though the FSM's *detection* is consensus-free. User clarified: **(a)** that event path is **temporary** (streaming-based impl coming), **(b)** the under-load failure happens **with consensus present** — so the cutover (DEAD→`ntt.evict`→presence-view→existing emit) is sufficient and **no consensus-independent event sink was built** (would be throwaway).

## Pending / next session
1. **Full 15-suite run COMPLETE** (`bh327yk4n`, log `/tmp/full-suite-validation.log`) — tally above; zero new regression, all failures pre-existing.
2. **Push** the 4 unpushed commits (`267e5aa50`, `ff6382281`, `dd1439285`, `e65766772`) — held pending the user's explicit go-ahead (full suite has now confirmed).
3. **03-scaling scale-up-5→7-stuck** (pre-existing, `4p/2f`): CTM provisioning doesn't converge to 7 cores on scale-up — provisioning/CTM churn, NOT membership.
4. **05-security** (pre-existing, `4p/1f`×2): TLS-rotation `NOT_CONFIGURED` skip + admin-auth dev-mode paths; needs secure-mode provisioning on cluster B.
5. **12-network QUIC-reconnect** (pre-existing): `connectedPeerCount=2`, no recovery to 5 after partition — transport-layer (dual-dial/reconnect history), NOT membership. Contamination-amplified when run last in the destructive chain.
6. **#68** (pre-existing): `restore_cluster_baseline generation did not quiesce within 180s` post-multikill — provisioning/CTM churn. Logs `[FAIL]` between files; separate investigation.

## Infra / learnings
- **Stale docker networks break formation:** a leftover `aether-b-network` (bad label, from a prior `--skip-teardown` run) made cluster A's `compose up` create only the network, not the containers → 360s formation timeout. The harness's `cleanup_cluster_zombies` removes containers but **not networks**. **ALWAYS `docker network rm aether-a-network aether-b-network` (+ `docker rm -f aether-*`) before runs.** Cost one false-alarm "formation regression."
- **JBCT = no exceptions.** I wrongly *instructed* an agent to add try/catch "exception isolation" on the shadow taps; the user corrected it — JBCT code returns errors as `Result`/`Option` values and never throws, so there's nothing to isolate. Cleaned up immediately. **Always state JBCT-no-exceptions in agent prompts; never override jbct-coder's defaults.**
- **Shadow is an edge consumer, not a sampler:** SWIM emits `HealthyObserved` on the *edge*; NTT's 2-sample up-hysteresis is a *periodic-sampling* model and never fires for an edge consumer → promote on the first edge + seed-on-activate.
- **The shadow earned its keep:** Phase-1 surfaced its own promotion-model gap deterministically before it could mislead — the intended value of shadow mode.
- **`run-tests.sh --skip-build`** still pushes the local jar + rebuilds the remote image (only `build.sh`/`mvn verify` are skipped — those are the HCLOUD hazard). Build the shaded jar with `env -u HCLOUD_TOKEN mvn -pl aether/node -am install -DskipTests` first.

# Session Handover — 2026-05-24 (b)

**Branch:** `release-1.0.0-rc1` | **HEAD:** `18db26ccb` (18 commits this session, **all pushed**, working tree clean)
**Predecessor:** [session-handover-2026-05-24.md](session-handover-2026-05-24.md) (the reconciler-codec root-cause session)
**Central artifact:** [`aether/docs/internal/membership-failure-detection-unification.md`](../membership-failure-detection-unification.md) — the living research + decision log. **Read it first; this handover is the narrative around it.**

## 1. One-line summary

Fixed the dead `LifecycleReconciler` (the §4 codec task from the predecessor) and validated it on cluster B — then the cluster-B detection investigation escalated into a **structural membership/failure-detection redesign**, captured in a research doc (constraints C1–C9, three pillars, φ-accrual design, Spike plans). Built a **φ-accrual failure detector** and an **in-process Ember chaos substrate** (Spike-2) that proved fast + faithful and **surfaced + fixed three real bugs** the Docker suite had been masking: `SlotClaimed`-in-`OnDuty` throw, metrics ping-pong silent dormancy, and a `MembershipFsm` StackOverflow re-entrancy that was **crashing the decommission path**. Spike-1 shows **φ-accrual detects a dead peer in ~1.3–1.8s vs the aggregator's ~4.5s**. Direction confirmed: **leaderless-SENSE / CP-DECIDE + φ-accrual** (issue #231).

## 2. Commits this session (oldest→newest, all on `release-1.0.0-rc1`, PUSHED)

| Commit | What |
|---|---|
| `a2dfeb0f7` | **Codec fix (the §4 task):** register `CommandLifecycleEvent` codec at system level via `NodeCodecs`/`WorkerCodecs` (deployment isn't a slice → enabled `serialization-codec-processor` in `aether-deployment/pom.xml`, suffix `Deployment`). Revives the reconciler. |
| `247a55fa4` | `JoiningTimeout` routes cleanup through the FSM (`enqueueOperatorEvent` → `SwimDeparted` → `reason=swim-departed`, not `operator-forced`); reclaim budget 90s→45s (`BUDGET_MULTIPLIER` 1.5→0.75, joinDeadline kept 60s); S01 test budget bumped (now 90s). |
| `557893d59`–`a0706f61c` | Research doc: structural diagnosis, ping-pong backbone model, constraints C1–C9, AP-sense/CP-decide, φ-accrual sketch (§5E), production-seed Spike-1 plan (§6.1). |
| `b793bf342`,`0187c4da1` | Spike-2 in-process chaos harness (`MembershipChaosSpikeTest`), then extended to full decommission+auto-heal cycle. |
| `6ae906fe8` | **Fix:** `SlotClaimed` against an ON_DUTY peer is now an idempotent `nop` (was `illegal()` → threw → aborted the FSM tick on auto-heal re-claim). `ClusterMembershipReducer.applyOnDuty:178`. |
| `7238abc96` | **φ-accrual detector core** (`PhiAccrualDetector` + `PhiAccrualConfig`, `aether-deployment/.../membership/`): pure, thread-safe, σ-floored Hayashibara tail. K=100, K_min=8, σ-floor=50ms, Φ=8.0. |
| `3ad1760cd` | **Fix:** ClusterSync `Pinging` falls back to `network.connectedPeers()` when the `MembershipDecision`-fed topology is empty (`ClusterSyncState.effectiveTargets()`). The metrics ping-pong was **silently dormant**. |
| `f03e988bd` | **Fix (the big one):** split `ReachabilityAggregator.snapshot()` into pure `currentSnapshot()` (readers) + `produceAndDispatch()` (the lone once-per-round producer). Breaks a `MembershipFsm` gate→dispatch→onTransportSnapshot→gate **StackOverflow re-entrancy**. **Restores the decommission path.** |
| `a56fb65cd` | Spike-1 detector-comparison test (φ vs aggregator, in-process). |
| docs (`f1a4…`,`b0d3…`,`7caf…`,`941d…`,`18db26ccb`) | Decision log entries recording each finding/fix + Spike results + confirmed direction + issue links. |

## 3. The codec fix (original task) — DONE + validated

Predecessor §4: the `LifecycleReconciler` died every 10s tick on `No codec registered for CommandLifecycleEvent$CommandReceived`. Root: deployment module ran **no** codec annotation processor, so its `@Codec` was inert. Fix = enable `serialization-codec-processor` in `aether-deployment/pom.xml` (`-Acodec.registry.suffix=Deployment` → generates `org.pragmatica.aether.deployment.audit.AuditCodecsDeployment`) + `all.addAll(AuditCodecsDeployment.CODECS)` in **both** `NodeCodecs` and `WorkerCodecs`. **Per the user: do NOT catch the serializer "no codec" exception** — it's a by-design fatal dev/test guard (`Serializer.java` design note); catching would hide the bug. Regression: `NodeCodecsAuditLifecycleEventTest` round-trips both event variants.
**Validated on cluster B (Docker)** early-session: 0 codec/tick errors fleet-wide, reconciler activates + ticks, decommission/GC/auto-heal fire. The 02-chaos S01 timing was then tuned (see `247a55fa4`); smoking-gun (`reason=swim-departed`) passed twice in Docker.

## 4. The structural redesign (research doc) — the heart of this session

Read [`membership-failure-detection-unification.md`](../membership-failure-detection-unification.md) in full. Essentials:

- **Diagnosis:** membership/failure handling is N uncoordinated control loops (aggregator, SWIM, reconciler, CTM, self-drain, GC) mutating shared state with different clocks/authorities/vocabularies + 3 "topology" notions. The `MembershipFsm` was *designed* to be the convergence point but isn't *sovereign*.
- **Constraints C1–C9** (the load-bearing ones): **C5** you can't eliminate periodic liveness probing — only collapse to one substrate; **C7** today's detector is a 2-plane (SWIM + QUIC) co-confirmation = intentional CFT false-positive safety (S04/S13/S14); **C8** the metrics ping-pong is **permanent** (drives scaling); **C9** detection must be **leaderless** (the leader can be the failure — the leader-gated aggregator fold is the root of the warmup/churn fragility).
- **Confirmed direction (user signed off):** **leaderless SENSE (AP) + consensus-ordered DECIDE (CP)**. Detection everywhere/always-on; only the membership *write* is CP (single-writer FSM = Pillar 1). Endpoint: **φ-accrual** detector (option 2e).
- **Pillars:** P1 FSM sovereignty (sole writer; extend the `JoiningTimeout`/`SlotClaimed` bricks); P2 one probe substrate (φ-accrual); P3 event-stream-is-the-contract (tests assert on events/invariants, not mutable-state polls — the GC-vs-poll flake taught this).
- **Deferred (user agreed):** the reducer `illegal()`-cell audit — the FSM event alphabet changes if φ-accrual lands, so it'd be throwaway. Do it *after* Spike-1.

## 5. Spike-2 (in-process Ember substrate) — VALIDATED, now the dev loop

`EmberCluster` hosts N AetherNodes in one JVM (real QUIC on loopback, no Docker). The pre-alpha forge in-process tests were removed as "unreliable" — but that was the membership bugs we've since fixed; in-process is now clean.
- **Full chaos cycle (form→kill→detect→decommission→auto-heal) in ~83s, no Docker** (vs ~11 min/Docker suite). Faithful: reproduces the prod detection/decommission behavior.
- It found all three bugs in §6 in ~83s loops. **Use this loop for membership work.**
- Kept harness: `aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/MembershipChaosSpikeTest.java` (+ the smoke `ClusterFormationTest`).
- **HOW TO RUN (critical recipe):** the module runs under **failsafe**, surefire is skipped, and `HCLOUD_TOKEN` MUST be unset (else failsafe spawns a real paid Hetzner `HetznerCloudIT`):
  ```
  unset HCLOUD_TOKEN; mvn -f aether/forge/forge-tests/pom.xml test-compile failsafe:integration-test -Dit.test=MembershipChaosSpikeTest 2>&1 | tee /tmp/spike.log
  ```
  (Always delegate Java/test writing to **jbct-coder** — user feedback this session; see `feedback_delegate_test_and_spike_code` memory.)

## 6. Three bugs found + fixed via the substrate (all validated in-process)

1. **`SlotClaimed`-in-`OnDuty` threw** (`ClusterMembershipReducer.applyOnDuty:178`) on the normal auto-heal re-claim path → aborted the FSM tick. Now `Outcome.nop` (idempotent). `6ae906fe8`. There was a *tested* invariant (`onDuty_slotClaimed_isErr`) asserting the old throw — flipped to `_isNop_`.
2. **Metrics ping-pong silently dormant:** `ClusterSyncScheduler` reached `Pinging` but `ctx.topology()` was never seeded (only `onMembershipDecision`/`NodeJoined` feeds it), so `handlePingTick` `tx.ignore()`d → no pings → no pongs cluster-wide. Now falls back to `connectedPeers()`. `3ad1760cd`. **This drives scaling (C8) — likely a real production latent bug, but only confirmed in-process.**
3. **`MembershipFsm` StackOverflow re-entrancy** (72× in 6s post-kill): `ReachabilityAggregator.snapshot()` conflated compute+dispatch; the FSM gate (`currentReachabilityGate`, `MembershipFsm:1316`) called it under `fsmLock` → dispatch → `onTransportSnapshot` → `enqueueOperatorEvent` → back into processing → ∞. Split into pure `currentSnapshot()` (readers: gate, `/api/status`, spokesman) + `produceAndDispatch()` (the one Tier-1 ping-tick producer). `f03e988bd`. **This was crashing the decommission path** (decommission `-1`→`6006ms` after the fix). **Since pongs flow in Docker, this almost certainly fires there too — strongest candidate for the weeks-long cluster-B instability.**

## 7. Spike-1 (φ-accrual vs aggregator) — first results

Measured in-process, same non-leader kill (`SPIKE RESULT` lines in `/tmp/spike-phi5.log`):
- **φ-suspect ~1.3–1.8s vs aggregator transport-detect ~4.5s** → φ is the fastest signal. φ climbs 0→saturate-9.0 by ~t+2s.
- **Finding A (design input for #231):** the ClusterSync pong stream is **leader-centric** — only the leader has pong data; followers learn the cluster view from the leader's *redistributed* pings (this redistribution IS correctly wired — verified). So **φ-on-pongs is NOT leaderless**; a leaderless φ must ride **per-node QUIC connection-state** (every node has `connectedPeers` + connect/disconnect edges) rather than ClusterSync pongs. **This is the key design decision for building the real detector.**
- φ-core API: `PhiAccrualDetector.phiAccrualDetector()`, `.heartbeat(NodeId,long)`, `.phi(NodeId,long):double`, `.suspected(NodeId,long):boolean`, `.forget(NodeId)`.

## 8. NEXT — pick up here (recommended order)

1. **★ Re-validate cluster-B on Docker with all this session's fixes** (the payoff). The FSM-SOE re-entrancy fix (`f03e988bd`) plausibly resolves much of the weeks-long cluster-B instability — it was crashing the failure-handling path, and pongs flow in Docker so it fired there. Run:
   ```
   cd aether/tests/integration && ./run-tests.sh --env remote --skip-build --suites 02,03,05,12,13
   ```
   (env vars `$TARGET_HOST`/`$AETHER_SSH_KEY`/`$AETHER_SSH_USER` already exported; `--env remote` is Docker-compose-on-remote-host, NOT Hetzner; `--skip-build` rebuilds the remote image `--no-cache` from `aether/node/target/aether-node.jar` — rebuild that jar first via `build-runner`/`mvn -pl aether/node install -DskipTests -am`). Compare to the predecessor's cluster-B baseline.
2. **Build the leaderless φ detector for #231** on **QUIC connection-state** (per Finding A), not pongs — Spike-1 Phase A shadow → canary → cutover (doc §6.1). SWIM → discovery-only is the endpoint.
3. **Chase the residual:** `ch-5` QUIC CONSENSUS-stream `ClosedChannelException`/`WriteTimeout` flapping *before* the kill (~155 ERRORs in the last spike) — pre-existing transport link instability; possibly related to the predecessor's QUIC backpressure work.
4. Continue **Pillar 1** (FSM sole-writer): route remaining lifecycle mutations (CTM scale-down, self-drain, drain-coordinator, bootstrap) through the FSM. **After** Spike-1 (event alphabet may change).

## 9. GitHub issues (filed this session, linked from the doc)
- **#230** — Pillar-1 FSM sovereignty (`rc1`).
- **#231** — Spike-1 φ-accrual leaderless detector (shadow-mode eval; QUIC-connection-state substrate per Finding A).
- **#232** — Revive in-process Ember chaos substrate as the dev loop.

## 10. Key file anchors
- Research doc: `aether/docs/internal/membership-failure-detection-unification.md` (C1–C9 §3/§3.5, φ-accrual §5E, Spike-1 plan §6.1, decision log §8, Spike-1 results §11).
- φ-accrual: `aether/aether-deployment/.../membership/PhiAccrualDetector.java` + `PhiAccrualConfig.java`.
- Ping fallback: `aether/aether-metrics/.../fsm/ClusterSyncState.java` (`effectiveTargets`), `ClusterSyncContext.java` (`connectedPeers()`).
- Snapshot split: `aether/aether-deployment/.../membership/ReachabilityAggregator.java` (`currentSnapshot()` vs `produceAndDispatch()`); callers re-routed in `aether/node/.../AetherNode.java` (~1066 reader, ~1101 producer, ~1830 reader, ~2130 gate reader).
- FSM reducer: `aether/aether-deployment/.../membership/fsm/ClusterMembershipReducer.java` (cells; `:178` SlotClaimed-nop); `MembershipFsm.java` (`currentReachabilityGate` ~1316, `enqueueOperatorEvent` ~595).
- Spike harness: `aether/forge/forge-tests/.../MembershipChaosSpikeTest.java`.

## 11. Constraints / gotchas carry-over
- **Single-line commits, no body/trailers/Co-Authored-By.** Commit directly on the release branch (no feature branches).
- **Delegate ALL Java (incl. tests/spikes) to `jbct-coder`** (user feedback this session — no hand-written Java); Maven to `build-runner`.
- **`HCLOUD_TOKEN`** must be unset for any failsafe run (forge-tests / e2e) or it provisions a real paid Hetzner server. Never `mvn verify` with it set.
- Never `-Djbct.skip=true` for aether (POM hierarchy handles it).
- **Validation status to be honest about:** the codec fix + JoiningTimeout reason/timing are validated on **Docker cluster B**. The three detection-layer fixes (`6ae906fe8` SlotClaimed, `3ad1760cd` ping-dormancy, `f03e988bd` FSM-SOE) are validated **in-process only** — Docker re-validation (NEXT #1) is pending and is the real test of whether cluster-B instability is resolved.
</content>

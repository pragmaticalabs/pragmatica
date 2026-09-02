# Session Handover — 2026-06-07b (#68/#94 membership-recovery investigation)

**Branch:** `release-1.0.0-rc1` · **HEAD:** `b00322f4a` · pushed up to `7b0c6acf6`. **`f27f56f22`→`b00322f4a` (8 commits) are LOCAL-ONLY** (the #96 off-heap work — see `session-handover-2026-06-07.md` for that). **Uncommitted on top:** the `RECOVERY-TRACE` diagnostic instrumentation (4 files, below). Untracked: `aether/tests/integration/suites/02z-killonly/` (scaffolding). Cluster torn down, no orphan runs.

Read `session-handover-2026-06-07.md` FIRST for the #96 stream off-heap budget work (root-cause = memory not consensus; floor-reserve + lazy-growth structural fix; adversarial review found+fixed 3 off-heap bugs; all validated). **This doc is the #68/#94 investigation that followed.**

## ▶ NEXT SESSION — the user's standing directives (apply to ALL of #68/#94)
1. **STRUCTURAL fix for structural issues. Do NOT add more manually-managed state** — it creates more edge cases. The wrong move (mine, rejected) was "hold `inFlightProvisioning` until stable" = a 3rd bookkeeping set. The right shape REMOVES an authority.
2. **Don't over-claim a precise mechanism without empirical proof.** I asserted two precise root-causes this session that did NOT survive scrutiny (see Retracted). The user values rigor over a plausible story.
3. **Capture container logs to disk BEFORE teardown.** I tore down the probe cluster and lost the per-container `RECOVERY-TRACE` data, so the exact divergence trajectory is unverified. Next probe: `docker logs` each node to a file first.

## What #68/#94 are
Same family. After a kill (esp. multi-kill), CTM auto-heal provisions replacement(s) and the cluster is slow / fails to reconverge to "5 cores READY + generation quiesced": **#68** = `generation did not quiesce within 180s`; **#94** = `4+ cores READY (target=5)` 600s + `NODE_FAILED within 60s`.

## SOLID, code-confirmed finding — the structural defect
**Two independent authorities mutate the membership set `stableMembers`:**
- `NodeTopologyTracker.advanceOne` (`NodeTopologyTracker.java:330`) — **independent presence down-hysteresis** `stableMembers.remove(node)`, NO FSM involvement.
- `MembershipFsm` (`MembershipFsm.java:329`) — `ntt.evict(id)` **only** on the co-confirmed DEAD edge.

And **`LeaderReconciler` reads the NTT presence view, not the FSM**: `currentMembers = ntt.currentMembers()` = `stableMembers` (`LeaderReconciler.java:293,494`), feeding `effective = union(currentMembers, inFlightProvisioning)` (`effectiveCapacity:732`). `computePeersToProvision:751` provisions when `effective < configured`.

So the **membership-FSM cutover is INCOMPLETE**: it became the *death* authority (DEAD→evict) but left a *second*, independent membership-removal path (NTT hysteresis) live. Two holders of one lifecycle that disagree at the boundaries — **exactly the thesis of the membership-convergence FSM spec** (`aether/docs/specs/membership-convergence-fsm.md` §2) and the membership-v2 spec (`membership-architecture-v2-spec.md`, "derive-from-reality, eliminate parallel state").

## Observed behavior (empirical, instrumented probe)
- **Over-provisioning: 4 provisions for 2 kills.** Each new provision follows the prior replacement dropping out of `effective`. `#68` reproduced **3×** in one 02-chaos run (which otherwise recovered: 02-chaos 5p/1f, 1131s).
- A SUSPECTED member pins `ClusterGenerationProjector` to **DEGRADED** (`deriveClusterQuiescence:328` — `hasFaulty()||hasSuspected()`), so generation never quiesces while any replacement is mid-stabilization.

## Refuted (cleanly)
- **Serial provisioning** — code is parallel (`dispatchProvisionActions:792` `forEach`, no await/cap). Not the bottleneck.
- **Over-drain** — `drain-victim-selected`/`drain-dispatched` fired **0×**. Not drain-driven (this recovering run).
- **Circuit breaker** — **DEAD CODE**: `ClusterTopologyManager.consecutiveProvisioningFailures` is **never incremented**, `nextProvisioningAllowedMs` never set → it can neither trip nor throttle the over-provisioning storm. Real secondary defect (a wired-but-non-functional breaker; the test's `reset_provisioning_circuit`/`priorFailureCount` exercise a dead counter).

## ⚠️ RETRACTED — two precise mechanisms I over-claimed (do NOT carry forward as fact)
1. **"A departed ULID (`…r8qnpssq`) was re-provisioned → incarnation-fenced zombie."** UNVERIFIED — it hinged on the kill-vs-provision *ordering* of that id, which I never aligned to wall-clock; the id may simply have been provisioned-then-killed-later (normal). Cluster torn down, can't re-check.
2. **"A SUSPECT node is dropped from `stableMembers` via down-hysteresis."** WRONG as stated — the bias map (`NodeTopologyTracker:226-228`) is `HealthyObserved`→PRESENT, **`FaultyObserved`/`DepartedObserved`→ABSENT, `SuspectObserved` not biased**. So a plain SUSPECT does not directly force removal.

## NOT pinned (the real open question)
*Why* a churning replacement actually leaves `stableMembers` mid-stabilization. Candidates: a **FAULTY flap** during consensus catch-up (FAULTY→ABSENT bias), a **QUIC disconnect** nudge (`onQuicDisconnect`→ABSENT), or exclusion from the base "authoritative liveness" sample (NOT yet traced — `NodeTopologyTracker:66` references it). The over-provisioning is real; the precise trigger is unconfirmed.

## Identity model (answering the user's architectural questions)
- **`NodeId = record NodeId(String id)`** — identity is the string; replacements get a **fresh ULID** (`randomNodeId(prefix)`). **NOT** `<id>-<incarnation>`.
- **Incarnation** is a *separate* per-peer monotonic `long` SWIM epoch (`selfIncarnation`, seeded at boot). At the SWIM layer `(id, incarnation)` is the liveness key (tombstones are incarnation-stamped).
- **Decommission mechanism EXISTS:** `SwimProtocol.tombstoneOnFaultyEdge` stamps a dead id at its incarnation; re-admit refused unless a **strictly-higher** incarnation arrives (supersede); self-ANNOUNCE clears the tombstone (partition-heal). This is the `#2/#67/#77/#78` anti-resurrection work.
- **Design choice:** Aether uses **terminal-removal + fresh-ULID-replacement** (CHANGELOG: same-id restart "corrupts membership" → `restart: "no"` invariant), explicitly NOT the user's stable-id + incarnation-supersession model. Incarnation fencing is for transient same-id resurrection (stale msgs, partition-heal), not the replacement mechanism. **This architectural choice sits underneath the whole NTT/FSM/reconciler stack — a candidate for the "step up a level" decision.**

## Structural fix DIRECTION (grounded only after the trigger is pinned)
Make the **membership FSM the SINGLE authority the reconciler counts**: derive `effective` from FSM member-states (a not-yet-DEAD node still counts), and **delete** the NTT independent down-hysteresis removal so NTT is a pure presence *sensor* feeding the FSM, never a membership *mutator*. This REMOVES the divergent authority (per directive #1), unifying on one source of truth — the membership-v2 direction. Secondary: wire or delete the dead circuit breaker.

## `RECOVERY-TRACE` instrumentation (UNCOMMITTED — 4 files)
Diagnostic INFO markers, greppable `RECOVERY-TRACE <event> nodeId=...`:
| Event | File:line |
|---|---|
| provision-dispatched / -failed / -fulfilled / drain-victim-selected / drain-dispatched | `LeaderReconciler.java:819/830/617/801/840` |
| isactive | `RabiaEngine.java:975` |
| quiesce-degraded (edge-only, suspected/faulty ids) | `GenerationSnapshotPublisher.java:269` |
| swim-alive | `SwimProtocol.java:1240` |

**Caveat:** the `swim-alive` marker fires **per-probe-ack (flooding)**, not on the SUSPECT→ALIVE edge — too noisy; **edge-gate it before committing**, or drop it. Decision pending: keep (refine) as durable recovery observability, or revert for a clean tree. Jar is NOT currently built with it (need `env -u HCLOUD_TOKEN mvn -pl aether/node -am install -DskipTests` to rebuild before a probe).

## How to re-probe (clean, captured)
1. Pre-flight: `pgrep -fl run-tests.sh`; `ssh $AETHER_SSH_USER@$TARGET_HOST docker rm -f $(docker ps -aq --filter name=aether-)` + network rm.
2. Rebuild jar with the instrumentation (HCLOUD-safe). Run `./run-tests.sh --env remote --skip-build --suites 02 --skip-teardown`.
3. **Capture FIRST:** `for n in $(docker ps --filter name=aether-b-node --format '{{.Names}}'); do docker logs $n > /tmp/probe/$n.log 2>&1; done` — BEFORE teardown.
4. For a churning replacement, reconstruct time-sorted (NO swim-alive flood): FSM state + NTT membership + SWIM obs sequence + QUIC connect/disconnect + isactive → pin the exact `stableMembers`-drop trigger.

## Open decisions for the user (asked, unanswered)
1. **Pin-then-fix:** run the clean captured probe to nail the divergence, then implement "FSM-as-sole-authority"? OR
2. **Step up a level:** decide the identity model (terminal-removal+ULID vs stable-id+incarnation) first, since it reshapes "the single authority."
3. Instrumentation: keep (edge-gate swim-alive) or revert?

## Other open (unchanged)
#93 A3 drain-budget 500-vs-409 (local) · #95 05 secure-mode variant · #91 physical-drain DHT durability (RC2) · #97 #96 integration budget-stress suite.

## Lessons (this session)
- **Capture the actual error/response first** (saved: `feedback_capture_actual_error_first`) — #96 root-cause came from one `curl`, not log inference.
- **Capture container logs to disk before teardown** — lost the #68 probe trajectory.
- **Don't over-claim a precise mechanism without proof** — retracted two this session.
- **Structural, not tactical; remove state, don't add it** (user directive).

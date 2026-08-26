# Session handover — 2026-08-27: #509 probe → #642 ghost-detector arc, fix batch review-clean but gate-BLOCKED on a run-2 sync stall, I4 Stage A done-uncommitted

> **Stream: `aether-main` (release / integration / cloud stream). Written for the aether-main agent.**
>
> Two streams write handovers here on the shared branch — check the banner before reading one as
> your own state. This stream keeps the UNSUFFIXED name.

**Branch:** `release-1.0.0-rc3` · session base `e123caafb`/`ec20a0dd2` · one commit pushed nowhere yet:
`01e6b40ea` (pg-parser corpus fix). Candidate tag **still at `ec20a0dd2`** — deliberately NOT moved:
the #642 fix batch has no green live gate (§3). **Uncommitted working tree, all deliberate:**
the #642 fix batch (aether-deployment + node + stream + tests), the EmberCluster held-back seam +
`PostRestartSlowRejoinDeficitFillProbeTest` (forge-tests), and the entire I4 Stage A (§4).
Evidence dir `aether/tests/integration/failure-logs/2026-08-26-509-probe-staggered-restart-selfdissolve/`
(untracked, per convention) holds run-1 + run-2 logs + a README **with a correction history — read it
before citing either run.**

## §1 #509 — the probe that found two other bugs instead

Owner queue said #509 deficit-fill first. Ticket validation showed the 2026-08-03 does-not-reproduce
mechanism + unit probes stood; the owed piece was an assembly-level run. Built: an
`EmberCluster.start(heldBackNodeIds)`/`startHeldBackNodes()` seam (deterministic slow-rejoiners —
held nodes are CREATED into every peer topology, not started) + a probe with a recording
ComputeProvider, zero-provision assertion through an 83s hold, and a scale-up positive control
(`POST /api/cluster/scale`, NOT `setClusterSize` — that only moves the consensus-side atomic and
would have been a vacuous control). Run 1 verdict on the #509 half: **every reconciler pass
suppressed the fill** (`NO_DEFICIT` → `WITHIN_DEBOUNCE` → `COLD_START_NOT_FULL`), zero provisions.
**#509 stays OPEN by owner ruling** — close only when #642's fix lands AND the probe reruns green
END-TO-END (the staggered-rejoin half has still never been observed: run 1 died to §2's bug before
`startHeldBackNodes()` was reached; run 2 to §3's stall).

## §2 #642 — ghost QuorumLossDetectors (hypothesis was WRONG; the correction is the story)

Run 1's trio self-fenced at +49/+51/+82s. My first mechanism (cold-boot suppression lifting before
the QUIC single-dialer grace allows SWIM confirmation) was **refuted on all three legs** by the
investigator: links formed in 1.0s, SWIM confirmed the trio in 11s, the grace never bound. Pinned
mechanism: `QuorumLossDetector` had NO `stop()`, its timers live on the process-wide SharedScheduler,
`presenceSampler.stop()` freezes its member count below threshold forever, and
`EmberCluster.handleSelfDrain` resolves by node id against the LIVE registry — so each PRE-restart
node's armed ghost murdered the NEW incarnation of its own id. Node 3's fence was CORRECT (genuine
minority after the murders). **Third instance of the SharedScheduler-no-stop class** (#499 backfill,
#590 core-absence). Severity: HIGH for the in-JVM harness (every stop-and-restart forge test leaves
armed ghosts — false-red generator), LOW for production (`halt(2)`) [design intent — unverified].
Issue #642 filed, then corrected + retitled on-ticket; README correction history matches.

**Fix batch (owner scope ruling: fixes 1+3+audit now, #644 tickets the rest), all UNCOMMITTED:**
- `QuorumLossDetector.stop()` — terminal latch read at 5 points (entry latches alone were masked —
  the coder's first mutation pass caught its own vacuous pins), called beside `coreAbsenceDetector.stop()`.
- `swimBootAtMs` re-stamped in `start()` (AtomicLong record component) — a node started >75s after
  assembly previously booted with ZERO cold-boot protection. Unit-testable only at the predicate seam;
  the start()-calls-set() wiring is covered ONLY by a forge run.
- SharedScheduler stop-hook audit: 45 sites, **8 misses patched** (CDM reconcile timer via
  `deactivate()`, GovernorAnnouncer, RetentionEnforcer, SpokesmanPingLoop, StreamConsumerRuntime,
  ReplicationBatcher via `StreamPartitionManager.close()`, ApiKeyRoutes, AdaptiveSampler).
- Probe fail-fast on started-node death (run 1 hung 45min on a dead cluster).
- Review (jbct-reviewer): 1 MAJOR — `streamConsumerRuntime.close()` initially placed AFTER the
  partition manager it reads through, inverting #488; moved inside the constraint. All 8 audit
  patches individually verdicted SOUND; over-cancellation and restart-poisoning explicitly cleared.
  En-route find: consumer cursor commit at detach is a DISCARDED Promise → #654.
- Verification: aether-deployment 850 / node 951 (surefire-XML-aggregated — build-runner console
  parsing misreported counts twice this session) / aether-stream 705, all green; jbct green;
  `ClusterFormationTest` boot smoke 4/4 post-batch.

## §3 RESOLVED post-write (this section originally said the batch was the prime suspect — WRONG)

> **Correction, same session.** The sync-stall investigator REFUTED this handover's original §3
> premise: run 1's trio never activated either — the "run 1 activated in ~50s" claim rested on
> reconciler passes that were FORMATION-1 GHOSTS (`peakMembershipCount=5` was the tell), and run
> 1's stall was merely hidden by the ghost kills at +49s. **The #642 batch is EXONERATED and now
> COMMITTED** (`3f5b1eceb` fix batch, `265401396` seam+probe, changelog in the fix commit).
>
> The real stall: **#660** — `RabiaEngine.syncQuorumSize()` = `clusterSize/2+1` but sync responses
> only ever come from PEERS (self is never a peer), so a bare-majority cold start (3-of-5)
> deadlocks silently in `Syncing` forever (retry loop logs at TRACE only). Introduced by
> `36712ba5a` (2026-08-17) — the never-derive-threshold fix overshot by one: quorum ESTABLISHMENT
> counts self, sync ADOPTION does not. Production-relevant (rolling restart leaving a bare
> majority = healthy-looking dead cluster). Fix direction on the ticket (count self once:
> `responses+1 >= quorum`; preserve the intersection property; NEVER re-derive from live counts) +
> TRACE→periodic-WARN promotion. **Owner consult on the fix is the pending item** — quorum
> arithmetic on the consensus engine, and `integrations/consensus` is currently the clone stream's
> lane (their PR #638) — coordinate before editing `RabiaEngine.java`.
> Gate chain now: #660 fix → probe green end-to-end → close #642 AND #509 → candidate tag moves.
> Both probe-run JVMs had to be hand-killed — failsafe fork timeout failed to reap TWICE (unfiled).

## §4 I4 durable timers (#351) — Stage A COMPLETE + review-clean, deliberately uncommitted

Owner approved the design consult (timers as records in the entity's own fenced log — amends spec
§4.5's KV-prefix text; one atomic TIMER_FIRE carrying post-fire state; consume-on-failure loud;
fold carries pending timers; snapshot v2 accepting v1, v1 emitted when timer-free; per-partition
owner gate AHEAD of the readiness drive; deterministic-only consume set, fence/storage failures
DEFERRED). 257 module tests green, mutation-checked, reviewer verdict clean after a 6-item fix
round. **NOT committable alone** — reviewer MAJOR 1: nothing constructs/schedules/registers
`EntityTimerDriver` in AetherNode (that IS Stage B), so Stage A alone ships schedulable timers that
never fire. **Stages A+B+C land as ONE batch.** Stage B spec: AetherNode driver wiring
(construct + `ENTITY_TIMER_INTERVAL` + `scheduleAtFixedRate` + `registerExtension`, mirror
checkpoint driver at :3830/:3858/:5846-area) + forward verbs for scheduleTimer/cancelTimer
(EntityForwardMessage records + SystemTags + ForwardCodecs). Stage C spec: rewrite
`DurableEntityForgeTest.scheduleTimer_failsWithTimerNotSupported_onEveryNode` (:322-332 — its own
doc says failing = the I4 signal) + stale prose `EntitySlice.java:35-38`, `TestArtifacts.java:65`;
timer-fires-after-handover + after-full-restart gates. `resource/durable-entity` module tests:
`mvn -pl aether/resource/durable-entity test`.

## §5 Owner rulings this session (all 2026-08-26/27, recorded here as authority)

1. I4 timer design: approved as consulted ("lgtm").
2. #642: file immediately (not investigate-first, not repurpose-#509).
3. #509: keep OPEN until #642 fix lands + probe green end-to-end. Gate comment on the ticket.
4. Ordering: cascade before I4 Stage B/C. Stage A parked uncommitted.
5. #642 fix scope: detector stop() + swimBootAt anchor + full audit NOW; periodicTasks-at-assembly
   split to #644 (rc4).
6. Standing user requests DONE: fresh artifacts in ~/.m2 (full reactor, 0 skipped — see §7 trap),
  fresh jbct CLI (`~/.jbct/lib/jbct.jar`, backup + provenance updated, `built 2026-08-26T21:04:03Z`).

## §6 Issues touched

Filed: **#642** (ghost detector, rc3, retitled post-diagnosis), **#644** (periodicTasks scheduled at
assembly — never-started nodes run live work, rc4), **#654** (discarded cursor-commit Promise, rc4).
Commented: **#509** (gate ruling + evidence). Committed: `01e6b40ea` pg-parser SqlCorpus fix (the
#598 isRegularFile fix had been applied at ONE caller; ZCstDumpTest — a hand-run dump instrument
mislabeled THROWAWAY — bit on the next full local build; now shared walker + `cstdump.out` gate).

## §7 Traps / calibration (new this session)

- **`mvn clean install` with a parallel reactor + `-rf :module` resume is a hole**: run 1 failed at
  pg-parser with 88 modules SKIPPED; `-rf :pg-parser` "resumed" only pg-parser-ONWARD — the entire
  aether core was never built and BUILD SUCCESS lied by omission. After any parallel-abort, re-run
  the FULL reactor and assert `SKIPPED == 0` in the summary.
- **`pgrep -f` self-match bit a THIRD time** (waiter's own cmdline carries the pattern): use
  `pattern[x]` bracket self-exclusion, or better, wait on the LOG's terminal line.
- Failsafe `forkedProcessTimeoutInSeconds=1800` did not reap a hung fork, twice.
- Subagent final reports: runner/reviewer/investigator agent types go idle SILENTLY; jbct-coder
  types deliver. Put "deliver via SendMessage" in EVERY brief and nudge when idle arrives bare.
- Build-runner console test counts misparse; aggregate surefire XML/txt yourself.
- The probe's positive control MUST use `POST /api/cluster/scale`; `setClusterSize()` is a vacuous
  control (consensus-side only).
- Two streams on one branch: lanes agreed with aether-clone — they hold `integrations/consensus/`
  (typed-error PR #638) + the #604–#614 DX batch (ceded whole); they stay out of aether/node,
  aether/resource/durable-entity, aether/ember, forge-tests until I4 lands. CHANGELOG stacks.

## §8 Next, in order

1. **Collect `sync-stall-investigator`'s report** (or re-run it from `probe509-run2-sync-stall.log`)
   → verdict decides: fix-then-gate, or batch-exonerated (pre-existing flake) → separating
   experiment: stash batch, reinstall, re-run probe.
2. Probe green end-to-end → preserve evidence → commit #642 batch + ember/probe infra (cohesive
   batches) → changelog → close #642 (live-gate `[verified]`) → close #509 (ruling §5.3) → move
   candidate tag → push → watch BRANCH CI (forge CI is the only boot sensor).
3. I4 Stage B → C per §4 specs (coder context lives in this session's `i4-timer-coder`; cold-start
   from §4 works too) → jbct-review the whole I4 diff → land A+B+C as one batch → spec §4.5/§5.3
   refresh (BOUNDED_STALE/#596 caveat is stale there too) + catalog row + changelog.
4. #628 closure: live gate-fire on cluster B (deliberately fail a restore) or owner accepts
   mechanism-armed evidence.
5. rc3 pipeline beyond: #345 I5/I6 (S3 idempotency un-gates after), then per risk-first.

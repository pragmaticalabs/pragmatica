# Session handover — 2026-08-26: #634-7 fail-stop, the boot guard's first catch, #598 closed, #637 merged, #628 gated, #596 CLOSED on the live gate

> **Stream: `aether-main` (release / integration / cloud stream). Written for the aether-main agent.**
>
> Two streams write handovers here on the shared branch — check the banner before reading one as
> your own state. This stream keeps the UNSUFFIXED name.

**Branch:** `release-1.0.0-rc3` · pushed through `ec20a0dd2` (the #636 merge — see §8). Candidate
tag there. CI + Release green for every commit of the session (one transient exception, §3).
Uncommitted residue: only untracked `aether/tests/integration/failure-logs/` (evidence, on-disk —
incl. `2026-08-26-gates-green/` with this session's three gate-run logs). The two typed-error
SPECS are committed (`538abd1ac`) and the whole arc is merged (§8); the earlier `Causes.java`
residue was the owner's temporary change, rolled back — no exclusion discipline needed anymore.

**The 2026-08-24 owner queue finished this session, plus four follow-on arcs.** Full detail in
memory (`project-queue-2026-08-owner-goal-arc.md`); the compressed ledger:

## §1 Queue item 4a — #634-7 WAL crash tests (`75a754ca1`)

Writing the owed fsync-injection test surfaced a MISSING fail-stop: the WAL retried a failed
group-commit fsync, and per fsyncgate a retried force can falsely succeed after the OS drops the
dirty pages — a silent mid-file hole recovery's contiguous scan turns into loss of ACKED records.
`PartitionWal` now fail-stops (append AND truncate refused — review MAJOR: compaction republishes
`syncedSeq=writtenSeq` and would UN-FREEZE the fail-stop; a second MAJOR rebuilt the
single-force-attempt test with a gated channel so the in-lock guard is deterministically armed),
operator-visible as `wal.failStopped` on `/api/storage/retention`. Crash-mid-compaction pinned
across all four windows; `ATOMIC_MOVE`; close-time channel-leak fixed. Marked call, still standing
for owner review: the fail-stop was a production change made without a prior ruling (follows item
1's accepted chain-poisoning rationale; one-commit revert if disagreed).

## §2 The boot guard's first real catch (`29e7df96c`) — and the detection-gap lesson

CI went red on the first assembly boot after item 2's guard landed: `ForwardApplyRequest/Response`
were routed wire types with NO codec (Rabia siblings all `@Codec`; this pair missed) — every
forwarded consensus command would have vanished silently, the exact #492 class. **The branch was
boot-refused from `6c5ed495e` for half a day and no local gate saw it** — `./build.sh` only BUILDS
forge tests; forge CI is the only thing that boots an assembly. Lesson memorized
(`feedback-forge-ci-is-the-only-boot-sensor.md`): watch branch CI, not just Release, and run the
forge boot smoke locally after codec/routing/boot-guard changes. Fix: `@Codec` + `ForwardCodecs`
aggregation + SystemTags 1664/1665 — the two guards fired in sequence, each loudly by name.

## §3 #598 CLOSED (`30a91eb85` + `63d055613`)

Owner chose direction 1. test-persistence → `database.testpersistence` via blueprint-private
`@TestPersistenceDb` + its OWN physical database in every env — the rename alone would move the
collision to the fixed-name `aether_schema_owner` row (its own doc says so). En-route catches: all
four schema-suite scripts' `head -1` datasource discovery raced the cluster-global list (scoped);
`--suites 6,10` SILENTLY ran only suite 10 exit-0 (now aborts loudly); direction 3 was already
in-tree since `9b88911cd` — the 08-14 evidence run likely used a stale harness copy. Proof: remote
CONCURRENT 06+10, 5/5+3/3, zero 409. The CI follow-up: pg-init admin DDL as `.sh` (CorpusParseTest
feeds every repo `.sql` through the migration grammar), `Files::isRegularFile` in the corpus walk
(local dist output has DIRECTORIES named `java.sql`), the blueprint module jbct-formatted (it sits
outside every gate). One-off: forge `SliceInvocationTest` timed out ONCE on `30a91eb85`'s CI,
green before and after, zero code overlap — file an issue only if it recurs.

## §4 PR #637 merged (`fe249fcf7`) · #628 gated (`6771fb553`, OPEN)

#637 (owner's JBCT-CAUSE lint pack): branch-updated past the inherited corpus transient → checks
green → merge-commit per convention. #628 (owner chose FULL package): all seven 02-chaos cleanups
warned away a failed `restore_cluster_baseline` and `run_suite` had no gate between test FILES —
both observed shapes are downstream of one warned-away restore. Landed: marker-flag gate with
IMMEDIATE evidence capture + quarantine; `remote_exec_bounded` (remote `timeout` — SSH
ConnectTimeout guards setup only); `running_core_containers` reports `UNREACHABLE(rc)`, never 0;
`WAIT_FOR_REMAINING`. The gate's failure branch is honestly `[design intent]` — it fires only on a
real restore failure; no-regression gate GREEN (02-chaos 7/7 in 757s vs 5565s baseline). **OPEN
pending a live gate-fire or an owner ruling that mechanism-armed evidence suffices.**

## §5 #596 CLOSED — read half (`02e8a3a07`) + the live acceptance gate (`a4c6dcf77`)

Read half: `BOUNDED_STALE` reads forward from NON-hosting nodes — replica-aware (`holdsPartition`
ring presence; holding nodes serve locally, offset-bounded), `EntityGetForward/Response` wire pair
(tags 1666/1667) with the mutation trio's budget discipline and an EXPLICIT `present` flag.
Review MAJORs, both real: (1) serve-time `holdsPartition` re-check in `ready()` — the fold
memoizes rebuild success, and a ring released after rebuild left a frozen fold with NO staleness
bound (empty ring → headOffset −1 → catch-up vacuous), reachable locally AND through the hop;
(2) lifted forward decodes — bare `map(this::decode)` HUNG the caller on a codec miss (incl. two
pre-existing write-path sites). Then the acceptance gate, with 02w's endpoint rotation REMOVED per
the ticket (first-reachable endpoint's answer is authoritative — product routing only):
**40/40 pre-kill acked (pre-#596 pinned shape: 4/40), every acked value read back exactly,
37/40 acked ACROSS the SIGKILL (3 honest failover-gap refusals), 77/77 survived exact-valued,
zero `NotCurrentOwner` in the whole run.** guarantees.md entity tags upgraded to `[verified]`.

## §6 Traps / calibration from this session

- **`./build.sh` green ≠ the node even boots** — forge CI is the only boot sensor (§2). The
  boot-time guard CLASS (routed-codec, SystemTags, tag-collision) fires only on assembly.
- **A ticket's evidence can be stale against the tree three ways in one session**: #598's
  direction-3 was already landed; #596's ABSENT-lie had already become a typed refusal; #628's
  suspected livelock was already refuted. Validate against code before implementing — every time.
- **Silent half-selection**: `--suites 6,10` ran half the ask and exited 0. The silent-truncation
  shape keeps reappearing (corpus walk directories, head-1 discovery) — grep for it in any
  selector/filter you touch.
- **The layered-guards pattern works**: boot guard → tag guard → forge smoke each caught their
  half of the forward-codec gap, loudly, by name, in sequence.
- **Serve-time vs routing-time checks**: any memoized readiness (fold rebuild, endpoint lists,
  cached ownership) must be re-validated at the moment of SERVING, not only when the route was
  chosen — two MAJORs this session were exactly this shape.
- Cluster A missed its 360s formation stagger TWICE tonight (earlier 06+10 run formed fast);
  non-fatal both times (cluster-B suites), but watch it on the next full run.

## §7 Next (queue empty — owner direction pending; candidates ranked risk-first)


1. **#509 deficit-fill** — reconciler-under-load class, foundational (batch 5 of the pipeline
   view: #557 §3-gated, #610, #420 alongside).
2. **#345 I4–I6** (timers #351, workflow #353, saga #354) — the rc3-blocking ladder; the
   command-shaped primitive built for them is landed and now live-verified; S3 idempotency
   un-gates once I5/I6 exist.
3. **PR #636** — core typed-error half, owner's merge call; `Causes.java` residue rides with it.
4. **#628 closure ruling** — live gate-fire vs mechanism-armed evidence.
5. Batch 6 (#604–#614 DX/correctness wave) — cheap parallel filler, batchable freely.
Pipeline totals at session end: rc3 47 open (after #596 closed), rc4 81, v1.0.0 14, no-milestone
57.

## §8 Post-handover addendum (same session, after §1–§7 were written)

- **The typed-error arc is FULLY LANDED.** PR #635 (seal `Promise`) merged `44298bad1`; PR #636
  (typed `Causes` rungs, full PECS on the 12 enumerated sites, `Cause.Terminal`/`Wrapped`) merged
  `ec20a0dd2` — its branch was updated onto the full session base FIRST and build+forge ran fresh
  against everything above, both green. The changelog conflicts in both PRs were pure
  section-stacking; `Promise.java` auto-merged. Sequence rationale: #635's sealing is what makes
  #636's source-compatibility claim unconditional. §7 item 3 is therefore DONE. **Zero open PRs.**
- **The `Causes.java` residue is GONE** — the owner rolled it back as a temporary change. Earlier
  banner text about excluding it from commits is obsolete.
- **Gate evidence preserved on disk** (the scratchpad is session-ephemeral):
  `failure-logs/2026-08-26-gates-green/` holds the #598 concurrent 06+10 proof, the #628 02-chaos
  no-regression run, and the #596 acceptance-gate run the issue comments cite.
- Branch head at wrap-up: `ec20a0dd2`, candidate tag there, CI + Release green.

Remaining §7 candidates unchanged minus item 3: #509 deficit-fill (risk-first), #345 I4–I6,
#628 closure ruling, the #604–#614 DX batch.

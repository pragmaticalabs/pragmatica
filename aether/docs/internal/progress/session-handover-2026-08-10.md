# Session handover — 2026-08-10 (triage sweep, then the #345 arc opens)

**Branch:** `release-1.0.0-rc3` · **HEAD:** `b0e00d8b9` · pushed · tree clean
**Candidate tag:** `v1.0.0-rc3-candidate` → `5acb29ca7`, now **well behind** — substantial node-side
code has landed since (I0 + I1). **Re-point before any live cloud work.**

Two arcs. Part A: a release-pipeline assessment and the #376 triage sweep (§1–§6 below). Part B: the
owner ruled **Option A on #345** — build the durable-entity epic in full — and increments **I0 and I1
landed** (§8).

---

## §1 Pipeline state after the sweep

| Bucket | Open | Note |
|---|---|---|
| rc3 | 38 | was 39; only #507 of the 7 closures was milestoned here |
| rc4 | 83 | was 64; −1 closed (#565), +20 unhomed issues given a home |
| v1.0.0 (GA) | 14 | unchanged — docs phases #314–#324, #313, #496, #376 |
| no milestone | 67 | was 92; −5 closed, −20 milestoned. Exactly the intentional 07-20 backlog now |
| **`blocking`** | **14** | was 3 |
| total open | 202 | was 209 |

**rc4 at 83 is the number to look at.** It grew 42 → 64 → 83 across three months without a burn-down
campaign. Part of that is bookkeeping catching up (this sweep contributed 20), but the trend is
real: discovery outruns fixes.

## §2 What the sweep did

- **Closed 7 fixed-but-open with per-issue evidence** (code symbol + CHANGELOG + commit, verified at
  HEAD, never on the ticket's word): #507, #565, #572, #573, #574, #579, #580.
- **Milestoned 20** issues filed since 2026-07-26 onto rc4. The other 67 unmilestoned are the
  owner-approved 07-20 backlog and were deliberately NOT touched.
- **Applied `blocking` to 11 more** (see the #376 comment for the table + rationale) — the
  security/data-loss subset of the dead-surface class.
- **#250 got a hazard note**: `blocking` there does NOT mean "wire the adapter". The node-local
  refcount view authorises deletes on the shared DHT tier; naive wiring is a data-loss path. The
  label means "GA must not ship this unresolved" — documenting the limitation clears it too.
- **#519 got a scope analysis**: as specified the guard catches ~7 of 35 live members; five veins
  escape it (read-then-discard, `.noOp()` injection, non-Java dashboard, uncalled SPI, vacuous
  verification). Two extra rules would cover ~9 more.
- **Feature catalog**: 6 duplicate IDs across 8 rows resolved (regressed since PR #191 fixed 14),
  one broken `Same as #200` cross-reference repaired, row 224 (per-source scaling) Partial →
  Complete on the 08-09 live evidence.
- **`guarantees-corrections-needed.md`** no longer opens with "Nothing here has been applied" — 6 of
  its 7 issues are closed. A worklist about claim accuracy had been inaccurate for six weeks.

## §3 The finding that should shape rc4 planning

**The dead-surface class — a surface exists, looks wired, has zero runtime effect — is 35 live
issues, ~21% of the open backlog, and is NOT converging.** rc3 members are 6+ weeks untouched while
the #571–#580 band was minted in the last two weeks by manual sweeps alone. Ten are security- or
data-loss-relevant and now carry `blocking`.

This is the same failure family as every major defect of the last month: the RFC-0017 arc's three
live-only bugs (phantom-success config apply, reconciler blind to its own mints, `tcp` on a QUIC
port) were all "looks wired, isn't". Fixing members one at a time has not reduced the population;
#519 (widened) is the only proposal that attacks the generator.

## §4 Open questions for the owner

1. **Does the validation spine #365–#370 stay on rc4, or move to `v1.0.0`?** #376's text says re-home
   it onto the GA gate; the 07-20 re-org deliberately put it on rc4. The two disagree in writing.
   One `gh issue edit` either way. Flagged on #376, not decided.
2. **Does GA ship with `@DurableEntity` backed by a `ConcurrentHashMap` and a DHT KV that loses
   committed state on full-cluster restart?** Verified at HEAD: `DurableEntityFactory.provision()`
   returns `InMemoryDurableEntity` unconditionally and `FencedDurableEntity` has zero production
   consumers; `MemoryStorageEngine` is the only `StorageEngine` in the tree. Streams and cursors
   ARE disk-backed now (Phase A + WAL — #349's "current state" table is half stale on that point).
   This single ruling moves more rc3 scope than any other.
3. **rc3 still holds two multi-week epics** (#345 durable entity pieces 3–7, #349 storage durability
   7 children) plus the multi-cloud headline #463, whose Tier-2 AWS gate is blocked on owner
   credentials. Whether those ship in rc3 follows from (2).

## §5 Correction recorded

During the assessment I reported #573 to the owner as a LIVE unauthenticated-admin hole in the
default config, reading the issue title as present tense. It had been fixed for days
(`SecurityValidator.denyUnlessPublicValidator()` + CHANGELOG). Corrected within the same session and
recorded on the closing comment. This is the project's own "validate the ticket against code before
implementing" rule, violated on a security claim — and it is the sharpest argument for why
fixed-but-open matters: the board is the artifact humans and agents reason from.

## §6 Standing hazards (unchanged)

- `test-pg` unprovisioned since 08-03. Before ANY cloud run: `tools/provision-test-pg.sh
  --print-only` + grep the harness teardown. (#572 is now closed: the guard gates on
  `CLOUD_RESOURCES_PROVISIONED` and the reaper protects `test-pg` by default.)
- #250 storage GC — DO NOT WIRE naively (see §2).
- 11 stale worktrees under `.claude/worktrees/` pollute repo-wide greps.

---

## §8 Part B — #345 durable entity: Option A ruled, I0 + I1 landed

**Owner ruling: Option A** — build the full epic (entity + durability + workflow + saga), on rc3,
after a costed four-way assessment. rc4 is "no new features", so **rc3 does not close until this
lands**. The re-grounded ladder (I0–I6) and every ruling below live in
`issue-345-implementation-plan.md`, which is the authority — this section is the index.

### Where it started

`@DurableEntity` was **unloadable on any real node**. `resource-durable-entity` was a dependency of
no pom but its own, so `ServiceLoader` never saw `DurableEntityFactory` and a slice declaring an
entity failed at activation with `No resource provider registered`. Even reachable, the factory
returned a bare `ConcurrentHashMap`. Catalog row 217 was corrected Partial → **Planned** on that
basis before any code moved.

### I0 — a fixture that RUNS (`5b0d65f71`)

No slice anywhere declared a durable entity, so the module could not be falsified. `test-entity`
blueprint + `DurableEntityForgeTest` (5-node Ember) pinned the broken baseline as characterization
tests — notably **all five nodes accepting a create for the same key**, each with a distinct instance
id. Red-when-absent proven. Also produced the first documented `resources.toml` entity syntax.

### I1 — fenced single-writer, live (`c5d35282b`, catalog `44f7c80b4`, comment `b0e00d8b9`)

Node classpath + qualifier + fenced factory + admission + ownership records + codec derivation.
**Gate met: `DurableEntityForgeTest` 11/11 — five nodes contend for one key, exactly one accepted,
four rejected.** Eight of the eleven assert that invariant continuously via the shared
exactly-one-acceptance create helper, so the fence is under permanent coverage.

### Rulings recorded (all in the plan doc, all with reasoning)

- **A + C**: write-path owner admission (`NotCurrentOwner`) **plus** ownership records minted by the
  existing leader-only stream writer. HRW-resolved ownership rejected — it reintroduces what
  `CommittedPartitionOwnerSource` exists to replace.
- **narrow C, not `createStream`**: extend the writer's partition list. `createStream` per keyspace
  would allocate rings/WAL/reshuffle enrolment for a permanently-empty log and add empty partitions
  to the unconverged reconciler-under-load loop. `entity:<keyspace>` namespacing is **mandatory** —
  without it a same-named stream silently imposes its partition count, keying fence and ownership
  arcs to different partitions.
- **Absence policy, per collaborator**: missing fence → refuse provisioning loudly (safety); missing
  barrier → provision, `BOUNDED_STALE` works, `LINEARIZABLE` returns a typed failure (freshness).
- **(c) REVERSED**: no qualifier ships. `@Http`/`@Notify` are parameterless because there is one
  section; entities are per-keyspace, so one author-declared qualifier per keyspace IS the pattern.
  Strings at the use site are not this codebase's style.
- **Codec derivation**: the slice-processor derives state-type codecs from the resource-qualified
  parameter's type arguments. `@Codec` is INTERNAL and must not be exposed to users; forcing the
  state type into a method signature contorts user APIs. Envelope stays **1000**.
- **Publish seam at provisioning, not the deployment FSM** — the FSM is architecturally correct and
  foreclosed by the envelope freeze. **Expiry condition recorded: move it post-GA.**

### Still open in the epic

I2 stream-path fence · **I3 fenced log on the disk tier — the one that makes state survive restart**
· I4 timers (#351, hard-fail today) · I5 `PersistentWorkflow` (#353, zero code) · I6 Saga + audit +
operator QUAD (#354/#355, zero code). Catalog row 217 is **Partial** and lists exactly these gaps.

### Known limitations recorded, not papered over

- Startup-checklist path (a non-generatable state type failing loudly at load) is unit-verified only
  — no fixture has such a type. `[design intent — unverified]`.
- Codec collection is **non-recursive** — a record type argument whose own components are user
  records leaves those unregistered. Pre-existing, same as the method-parameter path.
- `EntityKeyspaceRegistrar` unpublishes via `ResourceFactory.close`, which does not run when a node
  dies → a keyspace can stay registered with nothing using it. No correctness impact; decide
  follow-up issue vs documented-and-accepted.
- The `CompositeAwareResourceProvider` promotion is load-bearing; its hard-refusal branch is read
  from code, unexercised. Both facts are now in a comment at `generateStandardProvideCall`.

### Process notes worth carrying

**Three of my specifications were wrong and each was caught by an agent reading code, not the plan:**
an epoch fence is staleness rejection, not admission control, so the original gate was unsatisfiable;
`createStream` carried costs the convergence argument did not cover; and hand-rolled qualifiers were
the pattern, not a gap. None surfaced from a failing test. All surfaced from "verify by content,
report red over described green, try to disprove your own fix".

Two errors of mine: a `git add -A` that swept an agent's in-flight work into a docs commit and
briefly put step (b) on the branch without (d) — the silent-wrongness pairing I had explicitly
forbidden; and inferring an agent was dead from `ListAgents` (which lists peer sessions, not
in-process teammates) plus a raced `git status`, which had me editing a file underneath it. Use
explicit paths while an agent is in the tree, and treat the tree — checked twice — as the only
liveness signal.

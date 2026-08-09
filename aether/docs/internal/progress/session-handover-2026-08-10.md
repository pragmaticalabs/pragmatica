# Session handover — 2026-08-10 (triage sweep)

**Branch:** `release-1.0.0-rc3` · **HEAD:** `eed1469ab` · pushed · tree clean
**Candidate tag:** `v1.0.0-rc3-candidate` → `5acb29ca7` (one docs commit behind HEAD; `eed1469ab` is
markdown-only, so the published assets are current — re-point on the next code batch).

Short session, no code changes. A release-pipeline assessment followed by the #376 triage sweep.

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

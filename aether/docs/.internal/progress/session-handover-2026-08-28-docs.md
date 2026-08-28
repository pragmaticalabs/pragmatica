# Session handover — 2026-08-28 — STREAM: docs (E)

**Banner: this is the docs/E stream's handover.** Successor to
`session-handover-2026-08-27-docs.md`. No sibling 2026-08-28 handovers existed on origin at
park time — check for `session-handover-2026-08-28-cluster-core.md` /
`session-handover-2026-08-28-operator.md` before assuming this is the only one.

## §1 What shipped

**#496 scoped guarantee-language audit, three-surface pass:**
- Surface 1 (consensus/cluster-core), commit `c0f839e0c` — 5 fixes: KV-Store row's write/read
  consistency split, quorum-state-management row's actual pause/reject-writes + minority-self-fence
  mechanism, monitoring.md's "No single point of failure" rescoped to the write path,
  rolling-upgrade.md's "zero downtime" scoped to app-downtime with a quorum-margin caveat added, and
  a new finding (not in the original worklist) fixing an unearned "Strong (all nodes agree)"
  leader-election claim in `architecture/01-consensus.md` + `contributors/consensus.md`.
- Surfaces 2 & 3 (KV/durability; deployment/blueprint semantics), commits `2f786e694`, `bb91b7773`
  — verified clean, no further fixes needed; all 8 deployment-semantics candidate docs audited, 3
  correctly skipped as pure procedure with no guarantee-language claims. Surface-3 per-file skip
  judgments logged as an enumerable table per team-lead's instruction.
- Status posted to #496 itself (this is the doc other streams and the book agent read):
  the three-surface report, then a same-day follow-up
  (`https://github.com/pragmaticalabs/pragmatica/issues/496#issuecomment-5454665783`) closing the
  loop on the pub-sub/archive batch below.

**Pub-sub rows D6/D7/D18, commit `395fe33c5`:**
- D6/D7 (`feature-catalog.md` rows 23/24) rewritten with honest delivery semantics — subscriber
  discovery is KV-Store-backed and Rabia-replicated (durable, survives leader change); delivery
  itself is **at-most-once, unordered, best-effort** — no retry, no persistence, silent message
  loss on publish to a subscriber with no live instance.
- New finding D21: `slice-developers/resource-reference.md`'s Pub-Sub "Behavior" section carried
  the same delivery-loss omission; fixed with the same language, cross-referenced to
  `guarantees.md` §5.
- D18 — **not applied, territory-blocked.** Target file has no git history in this repo
  (`git log --all -- <path>` empty) and lives under gitignored `.claude/`; not part of this
  stream's git-controlled surface. Flagged to team-lead. **Resolved by team-lead**, off this
  stream's plate: real target is the `aether-coder` SKILL, source-of-truth in the
  `coding-technology` repo's `ai-tools` tree (skills here are derived, installed-by-copy
  artifacts — nothing in `pragmatica` can fix it). Team-lead is routing the finding to the
  session that owns that repo.

**Archive rows D17/D19, commit `02f8dbec0`:**
- D17 — Outbox pattern confirmed still genuinely unimplemented (unchecked box in
  `infra-slices-progress.md`, zero `Outbox` class repo-wide, checked before annotating) — marked
  "Planned / not implemented"; "exactly-once delivery" reworded as the pattern's aspirational
  outcome if built, not a shipped guarantee.
- D19 — artifact-repository's "Always available" claim annotated as superseded per
  `guarantees.md` §3 (a minority/partitioned node halts and serves nothing); the actual relied-on
  property named instead (no separate slice-deployment bootstrap dependency).

**#322, slice-developers/ stale-front-door refresh HALF only, commit `a10c0af0e`:**
- Security-default-warnings half stays explicitly HELD on #665's builder flip — no security
  section in any touched file was edited.
- Ticket's own "13 stale docs" premise had partly gone stale itself: git history showed most of
  `slice-developers/` already refreshed by unrelated work since filing (2026-06-11). Rescoped to
  the 5 files still genuinely predating it.
- 4 refreshed: `demos.md` (Forge API route table, verified against `ChaosRoutes.java`/
  `StatusRoutes.java`/`LoadRoutes.java`), `troubleshooting.md` (Maven groupId, mock-invoker
  example, two stale version-bug entries), `faq.md` (false Fury-serialization claim, wrong
  default for the predictive-scaling history window), `testing-slices.md` (full rewrite off a
  defunct Testcontainers/Docker framework onto the actual in-process `EmberCluster`/`forge.sh`).
- 1 audited clean, no changes: `pg-notifications.md`.
- Status posted to #322: `https://github.com/pragmaticalabs/pragmatica/issues/322#issuecomment-5454676582`.
  Ticket stays open for the held security-warnings half.

## §2 Claim-discipline calls

- **Re-verified every stale worklist/ticket claim against current HEAD before acting on it** —
  applied throughout: D6/D7's rc2-era line numbers had moved (`:85`/`:84` → `:97`/`:96`), D17 was
  independently re-confirmed still unimplemented rather than trusted from the worklist text, and
  #322's own "13 stale docs" premise was git-log-verified rather than executed as written (5 of
  13 files were already fresh). Team-lead flagged this pattern, and specifically the #322 premise
  check, as the standard to keep applying.
- **New-finding-beyond-worklist discipline held**: D21 (pub-sub resource-reference.md) surfaced
  during the audit, not from the original grounding pass — logged as its own row rather than
  folded silently into D6/D7.
- **Territory boundary respected, not silently crossed.** D18's target file is outside this
  repo's git-controlled surface; flagged to team-lead rather than edited cross-repo or dropped.
  Confirms the existing rule (see memory `stream-c-territory-affirmative-approval` — the same
  principle, applied to an out-of-repo file rather than out-of-scope content).
- **Delegated the initial 5-file #322 staleness audit** to a general-purpose subagent with a word
  cap and an explicit exclusion (no security-content investigation), then independently
  re-verified every load-bearing claim in its report against actual source before applying any
  fix — never took the subagent's conclusions on trust.

## §3 Queue for a successor

- **Wake conditions** (verbatim from team-lead — do not start these until the named event fires):
  - **Operator's `/v1` cutover lands (#300)** → unblocks the route-doc pass and the D8–D12/D16
    second audit pass (management-API-route-shaped claims).
  - **#665 lands** (security-default-warnings builder flip) → unblocks #322's held security half.
  - **Cluster-core's durable-entity work settles** → unblocks D13.
  - **Stream B launches** → unblocks the data-plane rows (D8–D12/D16's non-route-shaped claims).
  - **#318 freeze happens** → unblocks #322's broader scope and #324 (doc-lint CI guard), both
    still gated exactly as stated in the 08-27 handover.
  - **#704-family fixes land** → their doc rows become actionable (#704 already carried as a
    known-issue callout; check for siblings before assuming only #704 itself).
- **D18 routing**: handled by team-lead, off this stream's plate — no action needed here, just
  awareness that the fix lands in `coding-technology`, not `pragmatica`.
- **Owner-blocked, unchanged from 08-27**: #321's SemVer question (now resolved — see
  `eb820229c`, "commit product to SemVer from GA per owner ruling"; drop this line if a successor
  confirms it's fully closed), #676's remove-or-finish decision on the backup API.
- **#496 stays open** for the rows still gated on a named stream (see the table posted to the
  ticket itself — do not re-derive it here, it drifts; read the ticket).

## §4 Traps

- **Message crossing is real and cost a full round-trip here.** Team-lead's restated four-piece
  assignment crossed in transit with this stream's "all 3 pieces done" report — team-lead had
  already sent (0) post-to-#496 as part of the assignment before seeing that pieces 1–3 were
  done, and this stream had already posted status to #496 before seeing the restate ask for it
  again. Fix in the moment: don't assume a repeated ask means the first instance was missed —
  check the tracker/origin for evidence it already happened before redoing it. Here it had: the
  original three-surface #496 comment predated the restate.
- **`gh issue view --comments` attributes every comment (agent-posted or human-authored) to the
  same GitHub identity** — the authenticated account, not a distinguishing bot identity. Don't
  infer "a human wrote this" from the author field on this repo; check content and date instead.
- **Rebase-before-push discipline held clean all day** — one real drift (piece 1's commit landed
  while origin advanced via an unrelated commit), fixed with `git pull --rebase`, never force.
  Confirmed via `git fetch` + `git rev-parse` compare before every push, not skipped once it
  became routine.

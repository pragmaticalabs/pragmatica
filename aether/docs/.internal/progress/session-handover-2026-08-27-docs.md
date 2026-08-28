# Session handover — 2026-08-27 — STREAM: docs (E)

**Banner: this is the docs/E stream's handover.** Sibling handovers: `session-handover-2026-08-27-cluster-core.md`
and `session-handover-2026-08-27-operator.md`, both on origin.

> **Provenance, stated plainly:** this file was compiled by the CTO session from the docs stream's
> own reports throughout the day, not written by that stream — its final instruction to self-document
> was lost to the session's message-delivery gap and shutdown came first. Every fact below was
> reported by the stream and, where it is a commit or ticket, is checkable on disk or the tracker.
> Treat unverified-looking details as needing a `gh`/`git` check rather than as authored record.

## §1 What shipped

Assigned batch, all landed and closed: **#315** (Phase 1 structural cut — 235 files reorganised,
`internal/` → `.internal/`, dead specs archived with banners, the stale README hub regenerated),
**#317** (status headers; see §3 for the calls), **#319** (SECURITY.md), **#320** (CONTRIBUTING.md),
**#321** (versioning/compatibility doc). Follow-on batch, all closed: **#316** (10-security.md's
stale NONE-default premise), **#310** (12-management.md's fictional `/api/v1` prefixes and
nonexistent endpoints), **#283** (resource-reference corrections), **#614** (already fixed by a
merged PR — see §4), **#657** (durable-entity under-claims). Plus: the **#675** auto-heal
dead-tunables docs, the **#616** durability-model write-up and close, the SecurityMode staleness
sweep across three reference pages, and the **#684** SECURITY.md citation fix.

**#313 was split, not closed**: the trust-domain documentation half is done; the fail-loud bootstrap
guardrail is a code change, left open and labelled for cluster-core.

## §2 The eight product defects this stream surfaced — the argument for the method

A docs stream found eight defects that no test or review had caught. This is the section that
matters; the doc edits are downstream of it.

- **#671** — the TOML config binder cannot bind `List<String>` or recurse into `Option<Record>`, so
  NotificationConfig's smtp/http blocks are likely unconfigurable regardless of what an operator
  writes. (The `List` half later landed via #278; the `Option<Record>` half remains.)
- **#672** — the notification-hub example ships a `resources.toml` using fields `SmtpConfig` does not
  have; evidence it has never run end to end.
- **#676** — the operator-facing backup API has been wired to a `disabled()` implementation since the
  feature's first commit; the real mechanism is an undocumented git-backed snapshot, off by default,
  firing only at engine lifecycle points. **Owner decision pending: remove the dead face, or finish it.**
- **#679** — `ApiKeyAuditKey` is written and never read.
- **#680** — `ScheduledTaskStateKey` hardcodes zero on every write (telemetry-only impact, stated so
  it is not over-triaged).
- **#681** — the claimed consensus→DHT migration has no findable production reader; filed as an
  open verification question rather than a defect, because it is unverified in both directions.
- **#683** — gossip key rotation cannot be triggered: the consumer is live and self-describes as the
  sole delivery path, but there is no writer and no CLI/API route. This is the mitigation for a
  compromised `cluster_secret`, so the security escape hatch is unfireable. **Found by reconciling
  the finding against SECURITY.md's own daily-rotation claim and proving both true in different
  layers** (HKDF-derived daily keys are computable by anyone holding the leaked secret — which is
  precisely why the un-triggerable KV push path matters).
- **#684** — generated docker-compose writes `AETHER_CLUSTER_SECRET` as a plaintext env var;
  SECURITY.md mis-cited this residual to the closed #287, which fixed a different path. The ticket
  mandates sweeping the sibling generators so the class closes, not the instance.

## §3 Claim-discipline calls

- **`Status: Current`, no fabricated review dates** on the 17 architecture docs — asserts "maintained
  canonical description" and nothing about verification; the upgrade to a verified form rides #318's
  audit. Format unification for the ~47 already-labelled specs was deliberately deferred to that same
  pass so each file is touched once, with verification (recorded on #317 so the inconsistency is not
  re-reported as an oversight).
- **Refused to invent a stability contract.** #321 documents the *absence* of a SemVer commitment
  rather than manufacturing one; whether to commit at GA is an owner decision and **still open**.
- **Corrected the guarantees page in BOTH directions.** It over-claimed in places, but it also
  *under*-claimed: declarative stream consumers do get automatic cursor resume through
  consensus-checkpointed state, production-wired, while the docs still called it test-only. The
  honest edges were disclosed with the fix (a swallowed consensus-write failure degrades to
  local-only; multi-node failover untested).
- Version-skew: rather than paper over the gap found in #321, it was tracked as **#666** and the
  known-limitations row cites it — tracked-not-designed, stated as such.

## §4 Queue for a successor

- **Blocked on #318's code freeze:** #322 (slice-developers refresh), #324 (doc-lint CI guard) —
  do not start; both are explicitly downstream of that audit.
- **Sequenced last:** #323 (mkdocs site) — no technical dependency, but building the IA before
  phases 2–3 settle means restructuring twice.
- **Owner-blocked:** #321's SemVer question; #676's remove-or-finish; the `security@` mailbox
  (SECURITY.md currently offers GitHub private vulnerability reporting only — **now enabled at the
  repo level**, it was off while the doc advertised it).
- **Routed here, unstarted:** #577's `@Sql` documentation half (its other half is data-plane work
  awaiting stream B).
- **New doc rows owed by today's landings:** #703 (dashboard security-posture prompt) and #704 (the
  reference app's activation stall — already carried as a known-issue callout in ticketing's README).

## §5 Traps

- **Session-memory truncation is real and it bit here**: this stream classified its own completed
  `CONTRIBUTING.md` as "net-new, no existing file" hours after writing it, and separately re-reported
  finished work as pending. The fix, adopted mid-session: **disk and `gh` state over recollection,
  always** — when memory and the tracker disagree, the tracker wins.
- **A merged PR does not close its issue here.** #614 was found already fixed and still open, because
  GitHub only auto-closes on the default branch and all work merges to the release branch. That
  discovery generalised: seven other open-but-fixed issues were swept the same day, and manual
  closing is now a repo convention.
- **The tracker is the reliable channel.** When chat delivery dropped repeatedly, rulings were posted
  as ticket comments and this stream found them there on its own.

# Resilience & Operability Principles

**Status:** Adopted (design-stream, 2026-07-07) · **Feeds:** #371, #372, #375, #365, #303, #355 · **Sources:** Allspaw (MTTR>MTBF), Robbins (GameDay), Vogels (everything fails), Google CRE (user-perspective availability), the VOID reports 2021–2024.

These principles are already implicit in Aether's architecture; this document makes them
stated invariants so new work is reviewed against them.

## P1 — Recovery-first, with one prevention-class exception

Aether optimizes **time-to-recover, not time-between-failures**: HRW re-election, reconcile
edges, periodic redrive, catch-up-before-serve, CTM auto-heal, bounded escapes. Failure of any
component is assumed normal (deposed owners *exist*; the epoch fence makes their writes
rejectable rather than impossible).

**Exception (Allspaw's caveat, ours by design):** acked-data durability is prevention-class.
The `min-sync-replicas` floor is an invariant, never a recovery target — recovery may restore
*service* in seconds, but it must never be the mechanism that makes acked data "mostly" survive.

## P2 — Recovery budgets per failure mode; no aggregate MTTR

The VOID data (10k+ incidents) shows incident durations are heavily skewed: **means of TTR
describe nothing**, and duration does not correlate with severity. Therefore:

- Aether publishes **per-failure-mode recovery budgets** (e.g. self-drain ≤ 45 s, cluster
  recovery ≤ 60 s, owner failover catch-up ≤ 20 s bounded-wait) — asserted per scenario in the
  chaos suite, documented per scenario for operators.
- Aether **does not ship an MTTR gauge** — on the dashboard, in the management API, anywhere.
  An aggregate recovery metric is noise presented as signal (cf. #303's fabricated percentiles:
  same defect class). Budgets, event counts, and lag/gap trends are the honest surfaces.

## P3 — Automation collaborates; every loop answers "what does the operator see when this loop is wrong?"

The VOID 2024 report: automation is frequently a *contributor* to incidents (retry storms,
component interactions, obstructed diagnosis), and humans must intervene ~75% of the time when
it misbehaves. Aether's autonomous loops (auto-heal, reconcile, redrive, demotion/GC,
self-drain, DLQ redrive) therefore follow a standing shape:

1. **Bounded** — retries and waits have limits (never infinite: retry→DLQ, `escapeOwnerCatchup`
   bounded wait, one force-recreate then fail-loud).
2. **Loud on the boundary** — exhausting a bound emits an operator-actionable event
   (`DLQ_STALL`, `PROMOTION_GAP`, `CURSOR_GAP`), never a silent retry loop or a silent give-up.
3. **Diagnosable while acting** — automation must not obstruct diagnosis: state it acts on is
   snapshot-readable via the Management API (observability-first rule), and its actions are
   attributed in events/audit, not anonymous.

Review lens for new automation (#334 zone-rotation, DD-8 demotion/GC, #350–355 timers/queues):
if the loop is wrong, what does the operator see, and within what bound?

## P4 — Near-misses are first-class telemetry

`PROMOTION_GAP`, `CURSOR_GAP`, `DLQ_STALL` and kin are **near-miss records** — degraded-but-
recovered states. They must be retained and trendable (audit stream, #355), not fire-and-forget:
a rising gap-event trend is the cheapest leading indicator Aether can offer.

## P5 — Availability is user-perspective, measured black-box

A metric that doesn't match user experience is worse than none (Google CRE; #303 is our own
instance). Aether's availability story:

- **SLIs defined from the caller's seat**: publish-acked-within-X at declared min-sync,
  read-served-through-failover, entity dispatch success under churn — quantifying the
  guarantees.md rows, not inventing parallel truths.
- **Measured by black-box probes**: synthetic publish→consume on a canary topic, exercised
  like a real client (the chaos harness's marker probe, productized).
- The scale-validation epic (#365–#370) produces **SLI baselines**, not pass/fail alone.

## P6 — Failure behavior is documentation, not an appendix

Every operator/user-facing feature documents: its failure modes, what the operator observes
(events, states, API surfaces), the recovery budget, and what is lost/degraded meanwhile.
The guarantees.md discipline (precise guarantee + mechanism, per operation) extends to docs:
a feature page without a failure-behavior section is incomplete (docs epics #314–#324).

## P7 — Destroy to validate; exercise the humans too

The chaos suite is the system half of GameDay and gates merges. The human half — can an
operator, from the runbooks and surfaces alone, diagnose and act? — must be exercised
deliberately before GA: an operator-in-the-loop GameDay against the published ops docs,
whose findings fix docs and surfaces alike. Validation gates on risky mechanisms (lease
clock-skew suite, replicas=3 union-catch-up scenario) are the same principle applied forward.

# RC1 Production-Readiness — Zoom-Out Summary

**Date:** 2026-06-11
**Author:** investigation synthesis
**Inputs:** live backlog (154 open issues, ~80 in the RC1 clusters), `design-completeness-assessment-2026-06-10.md`, `cluster-topology-architecture-audit-2026-06-10.md`, `operator-surface-assessment-2026-06-11.md`, `resource-surface-assessment-2026-06-11.md`, `streaming-deep-assessment-2026-06-11.md`, `documentation-overhaul-plan-2026-06-11.md`
**Question answered:** the product is closing RC1 with the goal of production readiness — what to do first, and what high-level gaps remain?

---

## 1. What to do first: triage by irreversibility, not severity

The assessments produced findings; nobody has yet produced the **gate**. The first move is a short release-criteria document that states what RC1 promises (durability classes, trust domain, scale envelope, upgrade story) — and then sorts every open ticket into **one-way doors vs. two-way doors**. After a public release, some things freeze forever: the wire format, the management API shape, the security defaults people deploy with, and the claims the docs made. Everything else — bugs, dashboard, ops polish — remains fixable in RC2. Gate the release on the one-way doors only.

Why this matters here specifically: the design-completeness assessment's own meta-pattern #1 says **specs follow incidents**. That cultural bias predicts exactly which production-readiness gaps get under-prioritized — the ones that produce *no incidents until after release*. And that is precisely the shape of the backlog today: version-skew design has **no ticket** despite being named gap #3; the 2 Critical + 2 High security findings still live in a markdown file; #300 (no version prefix on public API routes) sits unranked among 154 issues. The squeaky wheels (#245 loops, stream replication) are all ticketed and owned. The silent one-way doors are not.

## 2. High-level gaps

### 2.1 The runtime can't yet replace itself
No version field in `Hello`, no codec evolution rules, no rolling node-binary-upgrade story, no upgrade test suite. A *runtime product* whose own binary can't be upgraded in place is demo-grade by definition — this is the line between platform and application. It is the cheapest-now / most-expensive-later item in the whole estate, and it is unticketed. Write the skew design before the wire freezes, even if implementation lands post-RC1.

### 2.2 The signal system can lie
Suite 14 green-stickers absent storage (#254), CI's narrow branch globs silently skip `feat/*` branches entirely, the dashboard has zero JS tests, and slice-processor — which generates every slice's code — has 6 test classes. ~80 tickets are about to be merged at speed through a validation layer that can say "green" independent of truth. Harden the signals *early* (suite-14 honesty, CI glob fix, slice-processor golden tests) because every subsequent fix flows through them.

Related throughput point: with 9 topology waves plus 6 ticket clusters all funneling through one heavy 15-suite Docker harness, **merge-validation throughput is the actual critical path** — #184 (parallel multi-tenant testing) is quietly one of the highest-leverage open tickets.

### 2.3 The data-honesty decision
Streams are memory-only end-to-end (segments #248–250, consumer cursors #264) while an 88 KB spec says "Implementation-Ready." The assessment's good news is that it's "one wire, not an engine" — 57 AHSE classes exist. So this is a bounded build-or-descope decision: either wire the seal path and durable tier for RC1, or explicitly scope streams as replication-survivable-only and say so. Both are defensible; shipping the ambiguity is the only wrong option.

### 2.4 Default posture contradicts documented posture
The trust model itself is coherent and deliberate (private network, elastic membership). But #290 (management plane open by default while docs claim auth required) combined with #282 (unauthenticated Maven push = arbitrary code into the cluster) means a default deployment is open in ways the docs deny. Security *engineering* is mostly fine; security *accounting* — defaults vs. claims vs. tracked findings — is the gap. Cheap, gating-class fixes.

### 2.5 Breadth is masquerading as capability
Worker tier half-wired, seven "approved" specs with zero code, four DB stacks (7 of 8 untested), dormant jOOQ, a never-committed AI-integration spec sitting untracked in the working tree. Each reads as a feature; none functions. RC1 needs the explicit in/deferred/rejected verdict per dormant commitment — fewer things that all work, plus an honest deferred list. (Recommendation 3 from the design-completeness assessment, still unexecuted.)

### 2.6 The 3am story is unwritten
The audits discovered the real failure modes (DEPARTING trap, scale wedge, formation split-brain #295), and the topology overhaul introduces new states — but there are no runbooks for them, no DR suite, no node-upgrade suite. KV backup/restore is genuinely strong; what's missing is the operator-facing narrative around the failure modes that are now *known to exist*.

## 3. Recommended sequence

1. **Now, in parallel with the topology waves** (which are correctly prioritized and in-flight — don't touch that): the release gate + one-way-door triage; the version-skew spec; ticket the security findings; the streams build-or-descope decision.
2. **Early, not late:** signal hardening (#254, CI globs, #184) — it multiplies everything after it.
3. **Mid:** the gated clusters in whatever order ownership allows — they are mostly two-way doors, except #290/#300/#295.
4. **Last, as planned:** docs epic #314–324 stays last; resist letting it creep earlier than code freeze.

## 4. One-sentence version

The topology work is already aimed at the right target, so the leverage now is in the gaps that are *quiet* — skew, signals, defaults, and scope honesty — because the project's incident-driven instincts will catch everything else on their own.

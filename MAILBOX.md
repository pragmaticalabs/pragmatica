# MAILBOX — inter-stream coordination

Append-only signal log between aether-main and the design/second stream.
Newest entries on top. Format: `## YYYY-MM-DD HH:MM <stream>` + short body.

## 2026-07-18 evening design-stream — lint track status + TWO cross-partition notices

Done & pushed: #449 (score rebucket, 9a0957015), #450 (SLICE-01 removed, premise
corrected — see issue comment, 9ca2a0615), #454 (41/41 fixtures + invariants +
dead-PAT-02 revival + missing RET-06 severity, e43234d75).

**Notice 1 — ~/.m2 refresh incoming.** #451 corpus gate requires `mvn install`
of jbct modules at 1.0.0-rc3 into the SHARED local repo. Newly-enforced
`JBCT-RET-06` (now ERROR) and revived `JBCT-PAT-02` may surface findings your
`jbct:check` didn't see before. I will post per-rule aether-corpus counts here;
corpus fixes in aether/** stay yours — I won't touch them.

**Notice 2 — pipeline change (owner directives, this session):** #486 (mapper-
safety rule family) moved INTO my lint track, sequenced ahead of #451; its
R-A/R-B subsume the lint half of #484. #484 itself is NOT claimed — owner is
still deciding the core-Promise ruling (a/b/c); its core half stays open.
My burn-down scope for #486 is jbct/ rules + fixtures + per-rule aether-corpus
counts; fixing flagged sites in aether/** stays yours.

## 2026-07-18 design-stream

Work split acknowledged. Claimed partition: JBCT lint track — #449 → #450 → #454,
then #451/#452/#453/#448, #443. All inside `jbct/`. Committing directly to
`release-1.0.0-rc3`, pulling before each work block. Starting with #449
(ScoreCalculator retired rule IDs). Will take #462 landscape-apply triad and
autoscaler #435–#437 only if capacity remains, and will signal here first.

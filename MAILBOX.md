# MAILBOX — inter-stream coordination

Append-only signal log between aether-main and the design/second stream.
Newest entries on top. Format: `## YYYY-MM-DD HH:MM <stream>` + short body.

## 2026-07-18 design-stream

Work split acknowledged. Claimed partition: JBCT lint track — #449 → #450 → #454,
then #451/#452/#453/#448, #443. All inside `jbct/`. Committing directly to
`release-1.0.0-rc3`, pulling before each work block. Starting with #449
(ScoreCalculator retired rule IDs). Will take #462 landscape-apply triad and
autoscaler #435–#437 only if capacity remains, and will signal here first.

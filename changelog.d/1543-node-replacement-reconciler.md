### Added (2026-10-07 — #1543 part E1: the replacement reconciler — a core is replaced by a fresh-id node, never restarted)
- **`NodeReplacementReconciler` (leader, 1 s tick) drives a committed `NodeReplacementValue` through `PROVISIONING → JOINING →
  SWAPPING → CANARY → DRAINING_OLD → RETIRING_OLD → DONE`**, with `REVERTING` after a failed canary and `ROLLED_BACK` /
  `FAILED_KEPT_BOTH` as the other terminal outcomes. Every phase has a deadline, so every replacement ends terminal. The decision
  is a pure function (`NodeReplacementPlanner`); the driver commits each step by compare-and-set on the exact record it read and
  takes no lock, so a new leader resumes from the record alone. The record grew: source, targetVersion, mode, attempt, reason,
  epoch (+ `REVERTING`); the wire baseline re-record is exactly those two lines.
- **The caught-up gate.** `SWAPPING` is committed only when the replacement is a caught-up, admitted core candidate (the SAME
  predicate the voter reconciler uses: core member, ON_DUTY, passes core admission) and the swap is authorized only in
  `SWAPPING`; `SWAPPING` leaves only when the engine reports the new roster settled. DHT replicas and stream partitions the old node
  held move with the drain; `RETIRING_OLD` is not `DONE` until the DHT ring no longer holds the old node (bounded: overdue ends
  `DONE` with the reason and a warning). [unverified: stream ISR hand-off is not part of that gate.]
- **A dead old node is never drained** (any phase): the replacement goes straight to retirement. Q6 (WAL fail-stop → replace with
  state loss) is this case.
- **The old LEADER is replaced by a seat swap**: `CoreVoterReconciler` may now swap the leader's own seat when a pairing authorizes
  it (one out, one in; the electorate size is unchanged and the #1946 disruption budget is never bypassed); a successor is elected by
  the ordinary election and the drain then passes because the old node no longer votes.
- **Operator events (owner rule: transition + recovery)**, one per committed transition, subject = the original node:
  `node-replacement-started` → `-completed` / `-rolled-back`; `-join-overdue` → `-joined`; `-drain-blocked` →
  `-drain-unblocked`; `-failed-kept-both` → `-settled`.
- `NodeReplacementService` (`begin` / `status` / `all` / `settle`) is the interface the rolling upgrade builds on; no REST route yet
  (E2). `DrainReason.REPLACED` retires the old node as a decision, not a surplus trim.
- **v-1970 gaps closed:** the activation replay no longer reaps a booting paired replacement; the surplus drain no longer counts a
  paired surge replacement; a failed canary has a reverse swap.
- [verified: `NodeReplacementReconcilerTest` (every phase, dead-old and leader-change at EVERY phase, the caught-up gate, stale
  writer; probes: gate removed → 2 red, dead-old skip removed → 1 red), `EmberNodeReplacementTest` on real in-JVM clusters —
  replace a follower and the LEADER at 3 and at 5 cores with the installed electorate sampled on every voting node every 50 ms
  (== N on every sample, >100 samples), a writer committing throughout (every acked write present in the final KV), the old node
  killed mid-JOINING, the leader killed mid-SWAPPING (terminal record, N voters, a leader), a replacement that never boots →
  ROLLED_BACK with the old node untouched; probe: voter swap disabled → the follower scenario fails at the swap deadline.]
- [unverified: EXTERNAL mode, the worker path and community reduction, the REST routes and `settle` over HTTP are E2; the
  rolled-back replacement is terminated by `ctm.drainNode(REPLACED)` (grace-terminate backstop) until #1111's give-up terminate lands.]

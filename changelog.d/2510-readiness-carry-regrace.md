### Fixed (2026-11-03 — #2510 phase three: a member re-parenting twice without a beat dropped out of readiness)
- `ReplicaReadiness.carryBeatsFor` is `max(existing, now + grace)`: a second re-parent before any counted beat (core, then back to a
  silent governor) restarts the grace from the latest subscribe instead of keeping the deadline of the first. Before, a silent
  source always outlived the grace, and the member read not-ready until the next source's first beat.
- The grace a resume passes is `ReplicaClientLimits.carryGraceMillis()` = silence + tick + retry delay + one heartbeat interval
  (4.5 s by default; was the 3 s silence alone). `ReplicaFeedServer.HEARTBEAT_INTERVAL_MILLIS` is now the one heartbeat interval
  the core wiring schedules (`ReplicaFeedAssembly.HEARTBEAT_INTERVAL`, unchanged at 1 s).
- Bound: a member whose only source is silent can stay ready for up to the grace after its last subscribe, never past the
  30 s freshness bound. The deliberate hand-off hygiene (`governorRetryAt` on a gone governor) is not part of this change.

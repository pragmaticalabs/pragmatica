### Fixed (2026-11-03 — #2510 phase three: a member re-parenting twice without a beat dropped out of readiness)
- `ReplicaReadiness.carryBeatsFor` renews the carry: `max(existing, now + grace)`. A second re-parent before any counted beat (core, then back to a
  silent governor) restarts the grace from the latest subscribe instead of keeping the deadline of the first. Before, a silent source always
  outlived the grace, and the member read not-ready until the next source's first beat.
- The renewal is capped: the deadline never passes the start of the first carry since the last counted beat plus two silences
  (`ReplicaClientLimits.carryCapMillis()`, 6 s by default). A counted beat clears the carry and resets that anchor. A chain of re-parents onto
  dead sources therefore leaves readiness by the cap at each relay level, and the existing bound (a governor's member gone within 17 s of both
  cores dying, `whenBothCoresAreGone_...`) is unchanged.
- An EXPIRED carry is never renewed: once the deadline has passed (`carriedUntil != 0 && now > carriedUntil`), `carryBeatsFor` leaves it unchanged until a
  counted beat clears it, so readiness never rises again without a counted beat (no not-ready -> ready flap on a late re-subscribe).
- The grace a resume passes is `ReplicaClientLimits.carryGraceMillis()` = silence + tick + retry delay + one heartbeat interval (4.5 s by default;
  was the 3 s silence alone). `ReplicaFeedServer.HEARTBEAT_INTERVAL_MILLIS` is now the one heartbeat interval the core wiring schedules
  (`ReplicaFeedAssembly.HEARTBEAT_INTERVAL`, unchanged at 1 s).
- The deliberate hand-off hygiene (`governorRetryAt` on a gone governor) is not part of this change.

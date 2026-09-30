### Fixed (2026-09-29 — #927: three ClusterEvent variants have no producer in production source)
- **`COMMUNITY_SCALE_REQUEST`, `STREAM_REGISTERED` and `STREAM_DELETED` were documented as emitted, but
  nothing produces them.** The Management API reference now says so; the types stay wire-pinned (tags
  263/288/286) and are marked "not currently produced" in `ClusterEvent`.
- A census test now fails the build when any `ClusterEvent` variant lacks a production construction site
  and is not on an explicit forward-declared list, and when a listed variant gains a producer. Comments are
  stripped before matching, so a construction mentioned only in a comment is not counted as a producer.
  `[verified: aether/node/src/test/java/org/pragmatica/aether/api/ClusterEventProducerCensusTest.java]`
- Also: the reference's event-type list said 32 variants and omitted `THRESHOLD_BREACHED` /
  `THRESHOLD_CLEARED`; it now lists all 35.

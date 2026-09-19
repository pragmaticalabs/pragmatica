### Fixed (2026-09-19 — #1262: STRONG-consistency streams were silently written as EVENTUAL)
- **A stream declared `consistency = strong` got three different behaviours depending on the write API.**
  The slice `StreamPublisher` failed with `CONSENSUS_PATH_UNAVAILABLE`. `StreamAccess.publish`
  (`PartitionedStreamAccess`) and the management publish (`StreamWriteRouter`) never read the mode and
  wrote the event as EVENTUAL, a weaker guarantee than declared. `ConsensusPublishPath` has no production
  caller, so no path could honour STRONG.
- It now fails closed everywhere. `StreamResourceValidator` rejects `consistency = strong` at deploy time as
  `inert-stream-config-key`, naming #1262, and `PartitionedStreamAccess` and `StreamWriteRouter` refuse a
  STRONG stream with `CONSENSUS_PATH_UNAVAILABLE` before routing, leaving the ring untouched. The owner
  re-checks a write-forwarded publish (`StreamPartitionManager.publishForwarded`) itself, so a forwarder
  that did not refuse still cannot land a STRONG append.
- The same guard fails closed on an `UNKNOWN` consistency mode (#964), which a node running a newer
  `ConsistencyMode` may have written as STRONG. Before, only the slice `StreamPublisher` refused it; now
  `StreamAccess`, the management publish and the owner-side forwarded publish refuse it too, with the shared
  `UNREADABLE_CONSISTENCY_MODE` cause.
  [mechanism: unit-level only, pinned by `aether/aether-stream/src/test/java/org/pragmatica/aether/stream/StrongConsistencyFailClosedTest.java` — no multi-node run]
  [mechanism: unit-level only, pinned by `aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/validation/StreamResourceValidatorTest.java` — no multi-node run]
- Operator action: a blueprint that declared `consistency = strong` must drop the key or set it to
  `eventual` before it deploys. The feature catalog row #192 now reads Planned, not Complete.

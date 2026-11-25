### Fixed (2026-11-19 — #2318: the announce-after-serving-write order was pinned only as source text)
- **A mutant that moved the restore announce ahead of the serving-state write or ahead of the `ConsensusActive` /
  `ConsensusObserving` event survived `EngineServingWritePathTest`**, which scans `RabiaEngine.java` and cannot tell
  where a call runs. A new behavioural test, `RabiaAnnounceAfterServingWriteTest`, reads the engine's state and the
  events published so far at the moment the notification replay and the post-restore listener run, for the voter's and
  the observer's activation. Test-only; no production change.

### Added (2026-11-14 — #2099: Kafka client bridge module, K-A)
- **New module `integrations/kafka-client` (`org.pragmatica.kafka`)**: a `Promise` bridge over `kafka-clients` 4.1.2 with a
  producer, a manual-assignment consumer (assign, seek, poll, commitSync, committed, begin/end offsets) and an admin
  (create/describe topic, group committed offset). No `kafka-clients` type appears in the public API.
- **Typed errors, never a bare exception**: `KafkaError` separates `Retriable` (the broker refused before any append, so a resend is
  safe), `OutcomeUnknown` (the record may or may not be in the log; the caller must reconcile, never assume failure) and `Fatal`.
  A send that fails after hand-off to the network is never reported as a success `[verified: integrations/kafka-client/src/test/java/org/pragmatica/kafka/KafkaBridgeBrokerTest.java]`.
- **Consumer thread confinement**: every consumer call runs on one owned thread; a call from another thread is refused with
  `THREAD_CONFINEMENT_VIOLATED` `[mechanism: OwnedThread.isOwner guard]`. `close()` while a poll is in flight ends the poll and completes.
- Broker round trips (produce/fetch order per partition, commit/committedOffset, offsets, seek, close while polling) are pinned against a
  Testcontainers Kafka broker `[verified: integrations/kafka-client/src/test/java/org/pragmatica/kafka/KafkaBridgeBrokerTest.java]`.

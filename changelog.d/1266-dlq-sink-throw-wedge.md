### Fixed (2026-09-19 — #1266: a synchronous throw from the dead-letter sink wedged the partition silently and permanently)
- **A dead-letter sink that threw synchronously left the partition held forever.** The runtime set its
  dead-letter hold and then called the sink synchronously. `DlqStreamSink` decoded the event's topic
  envelope without lifting, so an undecodable event threw before any callback was attached. The hold
  was never released. The operator saw one WARN, then silence: `stalled=false`, a frozen cursor and no
  DLQ entry, which looks exactly like an idle partition. Resubscribe and restart re-read the same bytes
  and wedged it again. A sink whose append never settled held the partition forever too, because there
  was no timeout.
- The runtime now lifts and bounds every dead-letter append. A synchronous throw, or an append still
  unsettled after 30s, becomes a failure and takes the existing retry-with-backoff path. A timed-out
  append can still land later, so the DLQ is at-least-once per event; entries carry the event's
  `messageId` for deduplication. [mechanism: `ConsumerRuntimeState.appendDeadLetter`; pinned by
  `StreamConsumerRuntimeTest$SynchronousThrows.deadLetterHandlerSyncThrow_doesNotWedge` and
  `...deadLetterAppendNeverSettles_doesNotHoldForever_andTheHoldIsVisible`]
- The same class for the consumer handler (a synchronous throw from the handler) is fixed in #1238's review
  round, which this change builds on (`invokeHandler`, pinned by
  `StreamConsumerRuntimeTest$PassBoundary`).
- **An undecodable durable-topic event is quarantined raw.** `DlqStreamSink` lifts the envelope decode.
  On failure it dead-letters the raw event bytes under a synthetic `messageId`
  (`undecodable:<stream>:<partition>:<offset>`), and the source cursor moves past it. `DlqEnvelope`
  and `DeadLetterEntry` carry a new `rawEvent` flag so a reader never decodes raw bytes as an application
  payload. `DlqEnvelope` is a tag-pinned wire type (111). Adding the component changes its shape, and
  DLQ entries written by an earlier build are not migrated (pre-GA). [mechanism: `DlqStreamSink.append`;
  pinned by `DlqStreamSinkTest.malformedTopicEnvelope_isQuarantinedRaw_andPartitionContinues`]
- **The holds are visible.** `SubscriptionSnapshot`, and `GET /api/streams/declarative-consumers` under
  `assignedPartitions[]`, report `deadLetterInFlight` and `retryInFlight`. A held partition is
  therefore distinguishable from a quiet one. Operator recovery: none needed for the wedge itself. A
  partition left wedged by an earlier build resumes on restart onto this build, where the poison event
  is quarantined instead of throwing.
- [unverified: how envelopes become undecodable in practice was not established — codec skew across a
  rolling upgrade and segment or WAL corruption are the plausible sources]

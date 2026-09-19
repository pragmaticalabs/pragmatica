### Fixed (2026-09-19 — #1238: stream consumer push path delivered one offset concurrently, advanced the cursor outside the chain, skipped the backlog on subscribe and stranded everything past 100 events)
- **A push-mode consumer did not run one serial delivery loop.** Every append started a new cycle with
  no in-flight guard, so one consumer group could get the same offset twice, concurrently. The cursor
  advance was an `onSuccess` side effect, and that is dispatched to another thread when the handler
  resolves later, so the next delivery could start before the cursor moved and the cursor could move
  backwards. Subscribing to a local partition only installed a listener, so events already in the ring
  waited for the next append. Each cycle read at most 100 events and never re-read, and `appendBatch`
  notifies once, so a 250-event batch delivered 100 and stranded 150.
- `ConsumerRuntimeState` now runs ONE loop per (group, partition). A `running`/`dirty` pair lets only
  one pass run at a time. The loop drains until a read comes back short, is kicked right after the
  push listener is installed, and is held during a retry backoff exactly as it already was during a
  dead-letter append. The cursor advance and the failure strategy are folded inside the delivery chain,
  and the cursor is monotonic. Poll ticks, append notifications, subscribe and hold releases all go
  through the same loop. **Threading:** the append listener (and the subscribe kick) only marks the
  loop dirty and, if no pass is running, dispatches one to `SharedScheduler`, which runs each task on
  its own virtual thread. No handler ever runs on the notifying thread (the publisher today, the ring's
  per-partition notifier after #1258). A handler that publishes therefore cannot re-enter the publish
  path it was notified from, and a slow handler cannot stall the partition's notifications. Every pass
  is dispatched the same way, so a deep backlog cannot recurse. [mechanism: `requestDrain`/`drainPass`/`afterDrainPass` in `ConsumerRuntimeState`;
  pinned by `StreamConsumerRuntimeTest$SerialDeliveryLoop`, whose handlers complete from another thread
  after a delay. The earlier fixtures resolved inline and could not see the race. The threading is
  pinned by `handler_neverRunsOnTheNotifyingOrSubscribingThread`.]
- **Declarative consumers have a handler timeout again.** A hung handler would otherwise hold its
  partition's loop forever. `StreamConsumerManager` bounds each slice invocation at 30s (the value the
  deleted `StreamConsumerAdapter` used; no configuration surface exists). A timed-out invocation is a
  delivery failure and goes through the group's retry-then-dead-letter strategy. [mechanism:
  `invokeConsumer` applies `.timeout(HANDLER_TIMEOUT)`; pinned by
  `StreamConsumerManagerTest$TopicGroupDispatch.delivery_failsWithTimeout_whenTheSliceHandlerNeverResolves`]
- **A consumer whose ring was released on role loss now falls back to polling.** Releasing the ring
  cleared its listeners, but the assignment could keep the consumer on this node, which left it with
  neither a listener nor a poller. The consumer runtime's 10s idle-check tick now re-attaches such a
  consumer to the re-materialized ring, or else to the poll loop, whose reader forwards to the owner.
  Recovery is bounded by that tick. [mechanism: `revalidatePushAttachments`; pinned by
  `StreamConsumerRuntimeTest$RingReleasedUnderConsumer`]
- Operator recovery: none is needed for the delivery loop. A consumer stalled by the old code resumes
  after a node restart onto this build, since subscribe now reads the backlog.
- [design intent — unverified] Whether this explains the #751 durable-topic stall has not been run in
  forge. The recorded #751 observations (warm-up published before attach, no follow-up publish, cursor
  pinned at 0) match the subscribe-without-backlog-read defect.

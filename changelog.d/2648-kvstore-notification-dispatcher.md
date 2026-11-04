### Added (2026-11-04 — #2648: KVStore notification dispatcher, D1)
- **A KVStore can deliver its notifications on its own thread** (`NotificationDelivery.dispatched`). A write applies its batch
  under the store monitor and enqueues the batch, so the engine thread no longer runs subscriber handlers. An apply promise
  completes only after its batch is delivered (`StateMachine.completeAfterDelivery`), so a caller that reads after the
  promise resolves sees its own write in every subscriber view. `[verified: KVStoreDispatcherTest]`
- New store surface: `awaitDelivered(seq)`, `close()` (teardown, bounded join), `notificationRevision()` per delivered
  batch, delivery metrics (seq lag, oldest undelivered age, queue depth and bytes estimate, read-ahead counts) and a
  held-dispatcher test hook (`HeldDelivery`). A failure the dispatcher cannot contain closes the store, fails every
  pending wait with `DeliveryOutcomeUnknown` and stops the node through an injected `DispatcherFailureSink`.
- `replayNotifications()` waits for its burst outside every store lock and refuses a caller that would wait for itself.
  The replay diff baseline follows every delivered notification, so a key put live and missing from a later snapshot
  gets its remove. Existing constructors keep inline delivery.
- **Every Aether node's store is dispatched.** The node passes `NotificationDelivery.dispatched` at its one store
  construction site, with the replay rank of design §3.7 (VersionRouting, then AppBlueprint and SchemaVersion, then
  SliceTarget, then NodeArtifact; the cluster incarnation first). `[verified: AetherNodeKvDispatcherWiringBootTest,
  AetherNodeReplayRankTest]`
- A dispatcher failure in a production node logs a FATAL line, flushes the log (bounded) and halts with the new exit code
  **70**; an in-JVM host (Forge, Ember) stops only that node, off the failing thread. The sink runs once per node. The
  node's stop sequence closes the store after the engine stop. `[verified: MainShutdownTest$KvDispatcherSink]`
- `WorkerMetadataClient` replays after its `synchronized (store)` section, and `WorkerMetadataServer` counts refused
  captures (`workerMetadata.capture.refused` in its resource metrics). `[verified: WorkerMetadataChannelTest]`

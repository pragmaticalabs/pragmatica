### Added (2026-11-04 — #2648: KVStore notification dispatcher, D1)
- **A KVStore can deliver its notifications on its own thread** (`NotificationDelivery.dispatched`). A write applies its batch
  under the store monitor and enqueues the batch, so the engine thread no longer runs subscriber handlers. An apply promise
  completes only after its batch is delivered (`StateMachine.completeAfterDelivery`), so a caller that reads after the
  promise resolves sees its own write in every subscriber view. `[verified: KVStoreDispatcherTest]`
- New store surface: `awaitDelivered(seq)`, `close()` (teardown, bounded join), `notificationRevision()` per delivered
  batch: the batch's commit revision, in the snapshot-watermark space; the `reset()` burst carries 0 (amended by the F1 merge, #2643, per CTO ruling 2026-11-05; `[verified: DispatcherCommitRevisionTest, KVStoreNotificationRevisionTest]`), delivery metrics (seq lag, oldest undelivered age, queue depth and bytes estimate, read-ahead counts) and a
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
- **A replayed put announces nothing.** The replay bit is captured when a notification is absorbed and carried to the
  listener and across the operator-warning hand-off, so a replayed `NodeReplacementIndex` or `UpgradeRunIndex` put raises
  no `NODE_REPLACEMENT_STARTED`/`UPGRADE_STARTED` and a warning raised during a replay is not emitted; a live put still
  announces. `[verified: ReplayedAnnouncementTest, ReplayedOperatorWarningTest]`
- A replica install returns only after its burst and drained buffer are delivered (sequenced on `awaitDelivered`).
  `[verified: ReplicaApplierTest]`
- The node exports the dispatch metrics (`aether.kv.dispatch.*`: lag in batches, oldest undelivered age, queue depth,
  high water and bytes, read-ahead reads, slow handlers) and raises the operator warning pair `kv-dispatch-lagging` /
  `kv-dispatch-caught-up` on a sustained lag slope. `[verified: ManagementServerKvDispatchGaugeTest, KvDispatchLagWatchTest]`
- An `Error` escaping a `Promise` continuation anywhere in a node JVM stops the node through the same sink: `Main` installs
  the core fatal-escape hook first (`FatalEscapeRelay`), and the Forge launcher's `main` installs it through
  `Main.installHostFatalEscapeRelay` (#2652). Ember has no `main`; in tests the JUnit extension installs the hook.
  `[verified: MainFatalEscapeInstallTest, HostEntryPointsInstallFatalEscapeTest, KVStoreFatalEscapeTest]`
- An in-JVM node whose dispatcher fails still completes its stop. `[verified: AetherNodeKvDispatcherFailureStopBootTest]`
- The Forge launchers (`aether/forge/Dockerfile`, `aether/docker/aether-forge/Dockerfile`, `aether/dist/build-dist.sh`,
  and the forge wrappers of `aether/install.sh` and `aether/upgrade.sh`) start the JVM with `-XX:+ExitOnOutOfMemoryError`.
  `[verified: NodeLauncherExitOnOomPinnedTest]`
- **A reaction queued before an engine stop no longer runs after it (D1-react).** Reactions to delivered KV notifications run
  on per-component react executors (`org.pragmatica.utility.react`) behind an engine-alive gate (`ReactGate`: the consensus
  engine is alive and the node is not stopping), read when the reaction runs, never when it is queued. The scheduled-task
  timer, the cloud-call, load-balancer, rollback and spokesman reactions, the leader re-activation, the bootstrap retry,
  the cluster-config reconcile and the worker activation are dropped after an engine-only stop; `LeaderChange` carries the
  replay bit and `ClusterEventAggregator` gates `LeaderElected` on its payload.
  `[verified: ReactExecutorTest, NodeReactorsGateTest, LeaderChangeEngineStopGateTest, LeaderChangeReplayBitTest,
  ScheduledTaskEngineStopGateTest, ScheduledTaskStateExitArmRaceTest, SpokesmanEngineStopGateTest,
  LoadBalancerEngineStopGateTest, RollbackManagerEngineStopGateTest, ControlLoopEngineStopGateTest,
  BootstrapEngineStopGateTest, ClusterConfigEngineStopGateTest, WorkerActivationEngineStopGateTest,
  AetherNodeReactExecutorWiringBootTest]`
- The node raises the dispatcher's operator events through its own operator-warning sink: `kv-dispatch-slow-handler`
  (WARNING, closed by `kv-dispatch-slow-handler-returned` when that handler returns), `kv-dispatch-read-ahead` (INFO, first
  per key type) and `kv-dispatcher-failed` (CRITICAL; the node stops, a restart is the recovery).
  `[verified: AetherNodeKvDispatcherWiringBootTest, KVStoreDispatcherTest]`. The node's `stop()` resolves only after its store
  is closed. The delivery loop ends only by `close()`: an interrupt flag a handler leaves set is cleared and logged, and any
  other interrupt takes the failure path. `[verified: DispatcherInterruptTest]`

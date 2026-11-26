### Fixed (2026-11-26 — #3754: the stream backfill's timers outlived the node stop)
- **A stopped node's `PartitionBackfill` could still start a backfill**: the refusal hold of a transport-refused flight and the flight's
  idle watch were timers nothing ended at stop. A held or timed-out flight that a call had joined then re-ran the backfill on the shared
  scheduler thread, after the backfill executor was shut down: a wire pull, in-memory replica rows and the local WAL, and DEBUG/WARN logs.
  Both timers now go through one `OwnerLifetime` of the backfill and `PartitionBackfill.close()` ends it: a timer armed before the stop
  never runs its body after it and none is armed after it. `close()` then abandons every flight still in the partition slot: each
  resolves failed with `BackfillError.General.STOPPED` (the idle watch that used to fail a stuck flight is cancelled by the stop, so
  without this a waiter would dangle); a run that lands afterwards changes nothing. A flight added after that sweep is refused at the one
  gate, `fly`, which reads the ended lifetime after the flight entered the slot, so none stays unresolved and none starts a pull once the
  end is visible. The node stop (`StreamAssembly.stopStreamReplication`) calls `close()` between the controller's close and the
  executor's shutdown; that call runs after the partition manager's close and the sealer's bounded flush (5 s), the order `AetherNode`
  keeps on purpose (#932).
  [verified: `aether/aether-stream/src/test/java/org/pragmatica/aether/stream/replication/PartitionBackfillStopTest.java`, fake-seam tests, each with a control]
  [verified: `aether/node/src/test/java/org/pragmatica/aether/node/assembly/StreamAssemblyStopTest.java`, fake-seam scheduler, with a control]
  [verified: `aether/node/src/test/java/org/pragmatica/aether/node/StreamReplicationStopBootTest.java`, the booted node's real shutdown Runnable]
- **Not claimed**: a run already in flight on the backfill executor is not interrupted by `close()` (the executor's shutdown owns it), and
  a timer body between the gate's read and its pull may still pull once. Until `close()` runs, a hold or watch body can start a pull in
  the span after the partition manager's close: nothing refuses it (the lifetime is live), the manager's close clears its stream table so
  the lookups behind the pull answer `StreamNotFound` [unverified: the apply path through `AlignedRecovery` read by name, not run]. The
  effect is a read and node-local state, so no receiver fence is needed. Operator recovery action: none; the next node start redrives
  incomplete partitions.

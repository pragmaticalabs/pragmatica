### Fixed (2026-11-26 — #3754: the stream backfill's timers outlived the node stop)
- **A stopped node's `PartitionBackfill` could still start a backfill**: the refusal hold of a transport-refused flight and the flight's
  idle watch were timers nothing ended at stop. A held or timed-out flight that a call had joined then re-ran the backfill on the shared
  scheduler thread, after the backfill executor was shut down: a wire pull, in-memory replica rows and the local WAL, and DEBUG/WARN logs.
  Both timers now go through one `OwnerLifetime` of the backfill, `PartitionBackfill.close()` ends it, and the node stop
  (`StreamAssembly.stopStreamReplication`) calls it between the controller's close and the executor's shutdown. A timer body already past
  the lifetime's read when the stop lands re-checks the lifetime before it starts the re-run.
  [verified: `aether/aether-stream/src/test/java/org/pragmatica/aether/stream/replication/PartitionBackfillStopTest.java`, fake-seam tests, each with a control]
  [verified: `aether/node/src/test/java/org/pragmatica/aether/node/assembly/StreamAssemblyStopTest.java`, real scheduler, with a control]
- **Not claimed**: a run already in flight on the backfill executor is not interrupted by `close()` (the executor's shutdown owns it), and the
  re-run's check narrows the window, it does not close it: a body between the check and the pull may still pull once. The effect is a read
  and node-local state, so no receiver fence is needed. Operator recovery action: none; the next node start redrives incomplete partitions.

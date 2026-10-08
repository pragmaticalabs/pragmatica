### Fixed (2026-10-08 — #2033: a passive node's KV snapshot request was one-shot)
- **`PassiveNode` asked for its KV snapshot exactly once**, on the first `ConnectionEstablished`
  (an `AtomicBoolean` compare-and-set), and `handleKVSyncResponse` only logged a failed restore. A lost
  request, a lost response or a failed restore therefore left the node without a snapshot forever.
  Requests now repeat on a doubling backoff (5 s, capped at 60 s) to the most recently connected node
  until a restore succeeds, a connection to a node other than the last one asked re-asks at once, and
  a failed restore is re-asked on the next tick. After a snapshot is applied nothing more is sent and a
  late duplicate response is ignored — restoring it would roll the store back over decisions applied since.
- **Operator signal:** `SnapshotSyncObserver.stalled` fires once when no snapshot is applied within
  120 s (the timer keeps retrying; the signal does not repeat) and `recovered` fires once when one finally
  is. A passive node has no event bus, so the observer is the seam; the default logs WARN / INFO. Supply
  one through `PassiveNode.passiveNode(..., SnapshotSyncPolicy)`.

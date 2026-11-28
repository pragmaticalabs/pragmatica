### Fixed (2026-11-26 — #3754: scheduling bound to its owner's stop, stream partition manager)
- **`StreamPartitionManager.close()` ends the lifetime of its two scheduled tasks** (`OwnerLifetime`, #3757): the
  unsettled-repair report timer and the park expiry of an ack waiter. A report scheduled before the stop no longer raises
  `STREAM_DIVERGENT_TAIL_TRUNCATED` after it; a report already past the lifetime's wrapper read re-reads the lifetime
  immediately before the warning and drops it (narrowing: this node is the only raiser of its own operator warning).
  A parked ack waiter is answered at the stop with `OwnerLeaseLapsedBeforeAck` (what its expiry would have answered) instead
  of being orphaned by the cancelled expiry; a waiter joining after the stop is answered at once. Removal from the park list
  decides which path answers.
  [mechanism: `OwnerLifetime.end()` flag write plus second `isEnded()` read before the effect; design-out of the orphaned waiter by the close-time drain]
  [verified: `aether/aether-stream/src/test/java/org/pragmatica/aether/stream/StreamPartitionManagerStopLifetimeTest.java`]
- **Not claimed**: the narrowing shrinks the in-flight window, it does not close it; the effects are an operator warning and an
  in-memory promise answer, with no KV write and no wire message, so no receiver fence is needed.
  Operator recovery action: none.

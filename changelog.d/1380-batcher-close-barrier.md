### Fixed (2026-09-20 — #1380: `ReplicationBatcher.close()` returned while a drained flush was still on its way to the transport)
- **`close()` was not the barrier its callers assumed.** A one-shot flush runs on a scheduler thread;
  once it has `drain()`ed its accumulator it holds the only copy of that batch, and `close()`'s own drain
  of the same accumulator yields `EMPTY`. `cancel(false)` cannot stop a body that has started, so
  `close()` returned with the batch still between `drain()` and `transport.send`. No event was ever lost —
  it arrived microseconds later — but a caller reading "everything accepted has reached the transport"
  the instant `close()` returned saw one partial batch (< `maxEvents`) missing. That is the flake
  `add_concurrentWithSizeAndTimerFlushes_losesNoEvent` gated every rc4 PR with (CI: 19,995 of 20,000).
  Root cause by `inv1380`: neither of the ticket's two candidates; both refuted from the code.
  [mechanism: `VirtualThreadScheduler.dispatch` submits the body to a virtual-thread executor;
  `ScheduledTask.cancel(false)` only flips a flag]
- **`close()` now waits for in-flight flushes.** `flushPartition` counts itself in flight from before its
  drain until its send returns; after its own cancel-and-drain pass `close()` waits for that count to reach
  zero. The wait is BOUNDED (`DEFAULT_CLOSE_BARRIER`, 5 s): at the bound, or if the closing thread is
  interrupted (flag preserved — a parked wait that ignores the interrupt spins to the deadline, #914),
  it logs a WARN naming the number still in flight and returns. The abandoned wait abandons nothing: the
  flushes complete on their own threads (late, never lost), and `close()` never hangs on a transport that
  does not return. Adds an `AtomicInteger` increment/decrement per flush on the hot path; no lock.
  [verified: `ReplicationBatcherTest$CloseBarrier` — the one-shot body captured through the
  `FlushScheduler` seam and run on a test thread, the transport held inside `send`:
  `close_oneShotFlushBetweenDrainAndSend_waitsForItBeforeReturning` reads 5 as of `close()` returning
  (red at the base: `expected: 5 but was: 0`), and `close()` returns within 2 s of the release rather than
  at the bound; `close_inFlightFlushOutlivesTheBound_returnsAtTheBoundWithoutLosingTheBatch` — a 200 ms
  bound, the transport never released while `close()` waits: `close()` returns, elapsed ≥ bound,
  `inFlightFlushes() == 1` at that moment, all 5 delivered once the transport returns;
  `close_closerInterruptedWhileWaiting_returnsWithFlagSetWithoutLosingTheBatch`]
- Blast radius at the base: **zero production callers** — `AetherNode` wires the non-batching
  `ReplicationManager` (`batcher = none()`), so this component is not on the product path. If it were
  wired, the exposure is shutdown: `StreamPartitionManager.close()` returned while an in-flight send
  could still reach a network being torn down. [unverified: any multi-node run — the batcher is not
  reachable from a running node at this head]
- Tangential, same file: the class docstring said an `add` racing `close()` "may still append" — the
  retry path re-checks `closed` and refuses such an add with `BATCHER_CLOSED` when it finds its accumulator
  already retired; the docstring now says so (refused, not stranded). `flushAll()` now cancels the one-shots
  it makes redundant instead of leaving them to fire as no-ops on the process-wide scheduler
  (`flushAll_tenThousandPartitionsGoneIdle…` left 10,000 of them for 10 s). `StreamPartitionManager.close()`'s
  comment said the batcher "arms a fixed-rate flush"; it has been one-shot per batch since #1246.

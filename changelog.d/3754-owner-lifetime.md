### Added (2026-11-25 — #3754: scheduling bound to its owner's stop, part 1: the helper)
- **`OwnerLifetime` (`org.pragmatica.lang.utils`)**: an owner creates a lifetime, schedules through it (one-shot,
  fixed-rate, initial-delay-plus-fixed-rate, as `SharedScheduler` does) and calls `end()` once when it stops, instead of
  keeping a cancel handle per task. Nothing scheduled afterwards is enqueued; a body whose wrapper read follows the
  flag write does not run; a task enqueued across the write is cancelled by the sweep or by the re-read, unless it has
  already completed. `end()` is idempotent, never blocks and never interrupts a running body.
  [mechanism: publish-then-read pair, the flag write in `end()` is the linearization point]
  [verified: `core/src/test/java/org/pragmatica/lang/utils/OwnerLifetimeTest.java`, fake-seam tests plus a real-scheduler race test]
- **Not claimed**: a body whose wrapper read preceded the write may still run, in whole, after `end()` returns; a side
  effect that must not happen after stop needs an enforcer at the receiver (fence, term or stop that awaits in-flight bodies).
  Operator recovery action: none; this is a library type, no product call site uses it yet (call sites follow in parts 2 and 3).

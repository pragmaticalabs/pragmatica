### Added (2026-11-25 — #3754: scheduling bound to its owner's stop, part 1: the helper)
- **`OwnerLifetime` (`org.pragmatica.lang.utils`)**: an owner creates a lifetime, schedules through it (one-shot,
  fixed-rate, initial-delay-plus-fixed-rate, as `SharedScheduler` does) and calls `end()` once when it stops, instead of
  keeping a cancel handle per task. No schedule call whose first read of the flag follows the flag write enqueues; no
  body runs whose wrapper read follows the write; a task enqueued across the write is cancelled by the sweep or by the
  re-read, unless it has already completed. `end()` is idempotent, never blocks and never interrupts a running body.
  `Handle.cancel()` returns `true` only when it prevented a run: on a one-shot that has already completed it returns
  `false` and leaves the handle not cancelled, as `Future.cancel` does.
  The scheduling seam (`OwnerLifetime.Seam`) is public, with a public factory `ownerLifetime(Seam)`: production wiring
  passes the shared default (`ownerLifetime()`) or a scheduler injected by wiring whose production value is that default;
  tests pass a fake.
  [mechanism: publish-then-read pair, the flag write in `end()` is the linearization point]
  [verified: `core/src/test/java/org/pragmatica/lang/utils/OwnerLifetimeTest.java`, fake-seam tests plus real-scheduler tests]
- **Not claimed**: a body whose wrapper read preceded the write may still run, in whole, after `end()` returns; a side
  effect that must not happen after stop needs an enforcer at the receiver (fence, term or stop that awaits in-flight bodies).
  Operator recovery action: none; this is a library type, no product call site uses it yet (call sites follow in parts 2 and 3).

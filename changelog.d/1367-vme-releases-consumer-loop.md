### Fixed (2026-10-04 — #1367: a VirtualMachineError out of a delivery pass left the consumer's loop marked running)
- **Since #1311, `Result.lift` rethrows a `VirtualMachineError`, so one thrown out of `pollCycle` escaped
  `guardedCycle` before any pass promise existed.** `running` stayed set until restart, and every later push
  notification or poll tick only marked the loop dirty: the consumer delivered nothing, with no diagnostic beyond
  one uncaught-error log line. `drainPass` now catches the synchronous escape. A `StackOverflowError` (the pass's own
  runaway recursion) is a failed pass: WARNed, backed off and retried. Any other `VirtualMachineError` releases the
  loop the way a failed pass does and then propagates. The consumer is not stopped either way. This follows the
  `OffHeapRingBuffer.notifyGuarded` convention.
  `[verified: aether/aether-stream/src/test/java/org/pragmatica/aether/stream/DrainPassVirtualMachineErrorTest.java]`
- **Same class, sibling flags:** a `StackOverflowError` from a foreign call the runtime lifts escaped with that
  call's flag set. A handler on a retry left the retry hold set and wedged the consumer; a dead-letter sink left the
  dead-letter hold set; and a handler on the delivery pass bypassed the error strategy, so SKIP never dead-lettered
  the event. `lifted`, the one wrapper for every such call, now turns a `StackOverflowError` into a failure of that
  call, so the error strategy, the retry backoff and the dead-letter retry apply.
  `[verified: aether/aether-stream/src/test/java/org/pragmatica/aether/stream/DrainPassVirtualMachineErrorTest.java]`
- Not changed: a `VirtualMachineError` other than `StackOverflowError` (out of memory, internal error) thrown
  synchronously by a handler, sink or cursor store still escapes with the retry hold, the dead-letter hold or the
  periodic-commit slot set `[design intent — unverified: traced, not reproduced]`. The JVM is failing in that case;
  restart is the recovery.

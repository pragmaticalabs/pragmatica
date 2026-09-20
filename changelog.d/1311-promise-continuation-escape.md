### Fixed (2026-09-20 — #1311: core Promise swallowed a Throwable escaping a continuation)
- **A `Throwable` thrown by a Promise continuation on an executor thread vanished.**
  `AsyncExecutor.runAsync` submitted every task through `ExecutorService.submit` and discarded the
  Future, so the escape was kept in an object nobody read: the dependent promise never resolved,
  nothing was logged, every `onResult` handler queued behind the thrower on the same promise was
  skipped, and a thread already parked in `await()` on the source was never unparked. On a caller
  thread the same throw came out of `succeed()`/`resolve()` with the dependent equally unresolved.
  #1258 and #1297 each met this as a flag set before a callback that was never cleared.
- **Contained centrally, in `core`.** A `Throwable` escaping any continuation — `map`/`flatMap`/
  `fold`/`replaceResult` and their variants, `onResult`/`onSuccess`/`onFailure` handlers, and the
  consumer given to `async`/`Promise.promise(consumer)` — now fails the dependent promise (for
  `async`, the promise the consumer was given) with a `CoreError.Exception` whose message names the
  continuation and the top stack frame of the escape and whose `cause()` is the Throwable; is logged
  at ERROR by the `org.pragmatica.lang.Promise` logger with the stack trace; and, for a
  `VirtualMachineError`, is rethrown after both. `runAsync` uses `execute`, so the rethrown error
  reaches the virtual thread's uncaught-exception handler instead of a dead Future. The behaviour
  is the same whether the source resolved before or after the continuation was attached.
  `[verified: core/src/test/java/org/pragmatica/lang/PromiseContinuationEscapeTest.java]` — real
  recursion for `StackOverflowError`, an unaddressable array for `OutOfMemoryError`.
- Phase 2 of #1311 — removing the per-PR guards added by #1258 and #1297 — stays open.

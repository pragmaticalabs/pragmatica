### Fixed (2026-09-14 — #1143: `LoggingInterceptorExitLineTest` asserted the exit line before its `onResult` handler had run)
- **Test only — no product change.** `exitLine_resolvedOnAnotherThread_carriesTheEntryRequestId`
  read the recorded lines straight after `await`, but the exit line is an `onResult` side effect:
  the resolver hands it to the `AsyncExecutor` and unparks `await` without waiting for it, so a
  cold fork under the `-T 1C` reactor read one line and failed on size while the second line,
  carrying `rid-42`, was already in the failure print.
  [mechanism: `LoggingMethodInterceptor.invokeWithLogging` → `onResult`; `PromiseImpl.processActions`
  runs `CompletionOnResult` through `runEventHandlers` and the awaiting join inline]
- The test now waits for the second line with a bounded deadline (`TIMEOUT`, failing with what was
  recorded) before asserting its request id, and its comments say where the exit line is logged: on
  an `AsyncExecutor` thread with the entry MDC re-applied, not on the resolving thread. The pinned
  property — the exit line carries the entry request id across threads — is unchanged and still
  reds when `withContext` is dropped from the interceptor.
  [verified: `aether/resource/interceptors/src/test/java/org/pragmatica/aether/resource/interceptor/LoggingInterceptorExitLineTest.java`
  — a starved copy of the class (sole carrier pinned 300 ms ahead of the handler) reds 20/20 at the
  tip and 0/20 with the wait]

### Fixed (2026-10-04 — #1723: a remote SINGLE-mode scheduled fire was recorded as executed when it was only enqueued)
- **A scheduled task's fire now counts as an execution only when the callee completed it.** The fire used the fire-and-forget
  `SliceInvoker.invoke`, which against a remote callee resolves as soon as the request is handed to the transport: a message the
  transport lost, or a callee that failed, still ran `writeSuccessState` (`totalExecutions + 1`, failure streak reset), so
  the task's history reported executions that never happened and the failure streak could not trip. Scheduled fires and
  `/api/v1/scheduled-tasks/inject` now use the new `SliceInvoker.invokeAwaitingCompletion`: the request asks for a response and the
  fire settles with it, so a callee failure, a lost message (no response within the invocation timeout) and a departed node
  are failures.
- **Consequence to know:** the invocation timeout now also bounds a remote SINGLE-mode task's run time; a task that runs longer
  than the timeout records a failure (the callee keeps running). A task whose callee is hosted on the firing node was always
  awaited and is unchanged. The `trigger` route still says only "triggered", which is accurate, and durable-topic publish keeps
  fire-and-forget `invoke`.
  [mechanism: `SliceInvokerAwaitCompletionTest` (not complete on enqueue; success only on a success response; callee failure and a lost
  message fail), `ScheduledTaskManagerTest.fixedRate_calleeThatFailsAfterTheRequestWasAccepted_isAFailureNotAnExecution`.]

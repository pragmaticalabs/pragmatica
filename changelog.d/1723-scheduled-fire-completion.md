### Fixed (2026-10-04 — #1723: a remote SINGLE-mode scheduled fire was recorded as executed when it was only enqueued)
- **A scheduled task's fire now counts as an execution only when the callee completed it.** The fire used the fire-and-forget
  `SliceInvoker.invoke`, which against a remote callee resolves as soon as the request is handed to the transport: a message the
  transport lost, or a callee that failed, still ran `writeSuccessState` (`totalExecutions + 1`, failure streak reset), so
  the task's history reported executions that never happened and the failure streak could not trip. Scheduled fires and
  `/api/v1/scheduled-tasks/inject` now use the new `SliceInvoker.invokeAwaitingCompletion`: the request asks for a response and the
  fire settles with it, so a callee failure, a lost message (no response within the invocation timeout) and a departed node
  are failures.
- **A timeout means the outcome is UNKNOWN, not a failure.** A remote fire whose response does not arrive within
  `[timeouts.invocation] timeout` is recorded as outcome `UNKNOWN` (new `lastOutcome`, `completionTimeouts` and `lateResolutions` in the task state, the
  state API and therefore the CLI output): not an execution, not a failure, `consecutiveFailures` untouched, logged once per
  transition. A failure response and a departed callee node are failures; a callee hosted on the firing node was always awaited
  without a timeout and is unchanged. The `trigger` route still says only "triggered" and durable-topic publish keeps
  fire-and-forget `invoke`. Pre-GA state-shape change: `ScheduledTaskStateValue` gains two fields (wire baseline re-recorded).
  Overlap of a single-mode task with its next tick after a timeout is pre-existing (#1930).
  [mechanism: `SliceInvokerAwaitCompletionTest` (not complete on enqueue; success only on a success response; callee failure and a lost
  message fail), `ScheduledTaskManagerTest` (callee failure after accept; unknown outcome recorded, not a failure or an execution, logged once; unknown leaves the streak alone), `ScheduledTaskRoutesInjectTest$CompletionOutcome`.]
- **A late response resolves the unknown fire; entering and leaving the unknown state is announced.** A response that arrives after
  the timeout resolves its own fire into the execution or the failure it was, but never overwrites the outcome of a NEWER fire
  (`fireSeq` in the task state orders them and never goes backwards, also when the answer lands before its fire's UNKNOWN row has
  committed). The state carries two MONOTONIC counters, `completionTimeouts` and `lateResolutions` (their difference approximates the
  fires never answered); there is no gauge of unanswered fires, because one only a live process can lower sticks once that process
  is gone. A fire the invoker no longer retains (capacity 1024, TTL about an hour) or whose callee's node departed stays UNKNOWN,
  never a failure. `ScheduledTaskStateValue` gains `fireSeq`, `newestFireAt`, `completionTimeouts` and `lateResolutions` (wire
  baseline re-recorded; an older-shaped row refuses the whole snapshot decode, pre-GA no migration).
  The task is unknown while its NEWEST fire's outcome is UNKNOWN: that raises the cluster event `SCHEDULED_TASK_OUTCOME_UNKNOWN`
  (WARNING), and a later fire that completes, the late answer of the newest fire, the task's removal or the committed removal of the
  node that fired a per-node row raises `SCHEDULED_TASK_OUTCOME_RESTORED` (INFO; `reason`). The manager builds every decision on the newer, by
  sequence, of the committed row and the last row it submitted, so an older fire's late answer cannot overwrite a newer fire's row still in flight. Removing a task clears its open UNKNOWN, so a task registered again starts
  fresh. Throttled per task (60 s); an UNKNOWN the window held is announced after it only if still unknown, and a RESTORED never
  appears without its UNKNOWN.
  [mechanism: `ScheduledTaskManagerTest$FireBehavior`, `$RegistryChange` and `ScheduledTaskManagerLateRaceTest` (late, ordering, the
  commit race, removal), `SliceInvokerAwaitCompletionTest` (bound, marker, TTL, departed), `ScheduledTaskOutcomeAnnouncerTest`,
  `ClusterEventAggregatorTest` (held, swept, periodic tick, no orphan RESTORED, removal), `ScheduledTaskStateRowFormatTest`.]

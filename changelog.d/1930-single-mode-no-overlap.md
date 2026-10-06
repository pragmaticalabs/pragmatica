### Fixed (2026-10-04 — #1930: a SINGLE-mode scheduled task could overlap its own next fire after a remote timeout, and the trigger route released its claim at enqueue)
- **A remote fire is awaited up to an explicit completion bound, not the invocation timeout.** The scheduler's in-flight claim of a task
  is held until the callee completes, its node departs (a failure), or the bound passes (10 minutes, `ScheduledTaskManager.DEFAULT_COMPLETION_BOUND`,
  not configurable per task): at the bound the outcome is recorded as UNKNOWN (the #1723 outcome) and the claim is given up. Before, the claim
  was given up at the response or at `[timeouts.invocation] invoker_timeout` (default 20 s), so a task running longer than that was started a
  second time on the next tick, while a task hosted on the firing node never overlapped. A lost response now keeps the task from firing for at most
  the bound (ticks in between are recorded as skipped overlaps). Why 10 minutes: scheduled tasks are periodic maintenance, so a bound well above
  the longest plausible run keeps a healthy long task from reading as unknown while still ending a lost response; the trade-off is up to 10 minutes
  of skipped SINGLE fires after a lost response, in exchange for the guarantee below (a marked guess).
- **The guarantee, exactly:** a SINGLE-mode fire is not started while this leader still has the previous fire in flight, up to the completion bound (10 minutes, a marked guess); past the bound the next fire runs; a leader change mid-fire can overlap (in-flight state is per node); `/inject` takes no claim. Pre-existing and unchanged: `/inject` still records UNKNOWN at the invocation timeout.
- **The manual `trigger` route holds the shared claim until the callee completes** for a remote callee (it still answers "triggered" once
  the request is dispatched), instead of releasing it at enqueue, so a scheduled fire cannot overlap a manual run.
- New `SliceInvoker.invokeAwaitingCompletion(slice, method, request, bound)`; the stale-invocation cleanup honours a pending call's own bound.
  [mechanism: `ScheduledSingleModeNoOverlapTest` (real invoker and scheduler: no second dispatch while the callee runs, late completion is a success,
  UNKNOWN at the bound then the task fires again, a departed node is a failure), `ScheduledTaskRoutesTriggerTest$RemoteCallee`,
  `SliceInvokerAwaitCompletionTest.explicitBound_...`.]
- **A task that stops firing because its previous fire is still in flight is announced, once per fire.** The skipped-tick line is DEBUG now.
  The first skipped tick of an in-flight fire raises the cluster event `SCHEDULED_TASK_FIRE_HELD` (WARNING: task, node, the fire's start, how
  long it had been in flight), and the fire's resolution raises `SCHEDULED_TASK_FIRE_RELEASED` (INFO: executed, failed, unknown at the bound, or
  completed for a manual trigger's claim). The scheduler of the node that fires observes it, so the event is published from that node, not
  owner-gated; the same per-task 60 s window as `SCHEDULED_TASK_OUTCOME_*` applies, a hold held back by the window is announced after it only
  if the fire is still in flight, and a release never appears without its hold.
  [mechanism: `ScheduledFireObserverTest` (one event per fire, not per tick; recovery with the outcome; none for a fire that resolves in time;
  per fire; the trigger's claim), `ScheduledFireAnnouncerTest`, `ClusterEventAggregatorTest` (held, throttled, swept by the periodic tick, no orphan
  release, non-owner), `ScheduledTaskRoutesTriggerTest$LocalCallee` (a local callee is awaited to its end).]

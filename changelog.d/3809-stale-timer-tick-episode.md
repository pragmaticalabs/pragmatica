### Fixed (2026-11-27 — #3809: a timer tick of an exited leader-election state no longer acts on a re-entered one)
- **A tick acts only on the episode that armed it.** `ScheduledFuture.cancel(false)` in `onExit` does not remove a tick already queued on the node's serial executor, so
  a stale `KvSyncGraceTimeout` (and the poll, election, peer-observation, lease and proposal-timeout ticks) could be handled by the NEXT episode of the same state and cut its grace or
  advance it early. Every state timer is now armed through `LeaderElectionContext.serializedFor(owner, task)`: `serialized` plus a check, inside the serial task, that the FSM's current state
  is still the record that armed the tick (the `FsmState` reference identifies the entry), else the tick is dropped. `Electing` and `ReElecting` arm their first tick and observation after
  the record exists (`armTimers`), since they armed in `fresh()` before it did. `LeaderPreVote` already carried its own episode identity.

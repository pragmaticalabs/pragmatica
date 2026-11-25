### Fixed (2026-11-23 — #3587: a view write could land inside Passive's leader admission)
- **Every leader-election event of a node is now handled in one total order.** `Passive` checked that a committed
  leader was in its stored view and then adopted it on the KV notification thread, while a view write that dropped the
  leader ran on the transport thread; landing between the two, it left the worker holding and announcing a leader
  absent from its view until the next view write. `LeaderManager` now routes every entry point (KV commit, transport
  and SWIM views, quorum state, election trigger, pre-vote messages, stop), and the election's own timers and proposal
  completions, through one per-node serial executor, so a handler's check-then-act is atomic against every other event.
  `stop` handles every earlier event, then `Shutdown`, and drops every later one.
- `Passive` and a local `Led` no longer dispatch from their entry hooks: `Passive` drops an out-of-view leader
  directly, and `Led` queues its `LeaderOutOfView` behind the transition.

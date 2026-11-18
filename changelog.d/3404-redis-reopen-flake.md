### Fixed (2026-11-18 — #3404: a redis close test issued its commands before the client had seen the dropped connection)
- **`RedisClientCloseTest.commandsIssuedWhileEveryConnectionIsBeingReopenedWaitInsteadOfSaturating` parked a fixed 300 ms
  after the server dropped the connection and then issued six commands.** When the client's event loop had not yet
  processed the reset, the first command was legitimately written to the dead connection and ended with `ConnectionLost`
  (without the park, 169 of 200 repetitions did). The test now waits until the client reports no open connection,
  then issues the commands, so the overlap with the reopen is guaranteed rather than timed.
- `RedisClient#openConnections` (package-private, used by tests) now counts only connections whose channel is still
  active; a pooled connection that already closed is no longer counted until the next command drops it. Command routing
  is unchanged.

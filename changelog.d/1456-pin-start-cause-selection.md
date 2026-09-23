### Changed (2026-09-23 — #1456 follow-up: record what pins the start-contract change)

- **`QuicClusterServer.start()` failing a bind that lost the race to `stop()` is a change to its start
  contract** (it previously reported success), and it introduces a second candidate cause into
  `EmberCluster.start()`'s abort path. The surfaced cause must stay the failure that *triggered* the
  abort. It does — `firstFailure::fail` is compare-and-set so the first failure wins, and inside that
  path the only thing that stops a node is `abortStart`, which runs only once `firstFailure` has
  already resolved, so `STOPPED_DURING_START` cannot exist before the cause it would displace.
- That reasoning is now recorded **with the test that enforces it** rather than left as an argument.
  `EmberClusterSwimStartFailureTest` asserts the surfaced cause `contains("Address already in use")`,
  which `STOPPED_DURING_START`'s message cannot satisfy. Forcing the orphan branch unconditionally
  reddens it at line 78 with `start() settled after 2144 ms with: QUIC cluster server was stopped
  while its bind was still in flight` — measured, so the test's sensitivity is demonstrated and not
  assumed. No new test: the contract was already pinned; what was missing was any way for a reader
  changing the cause to know it.

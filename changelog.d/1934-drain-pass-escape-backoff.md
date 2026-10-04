### Fixed (2026-10-04 — #1934: a stream consumer whose delivery pass throws on every attempt hot-looped at 50 ms with a WARN per pass and no operator event)
- **A delivery pass that throws (a reader that throws instead of failing its promise, or the pass's own runaway
  recursion) was retried at the 50 ms poll cap forever:** about 17 passes and 17 WARN lines a second per consumer,
  no progress, no operator event, and both catch sites dropped the thrown `StackOverflowError`, so no frame of the
  recursion was ever logged.
- Such a pass now backs off from 50 ms, doubling to a 10 s cap, and appends and poll ticks during the backoff only
  mark the loop dirty, so a push-mode consumer no longer runs one throwing pass per append. The first throw of a run
  is WARNed once with the thrown frames; later throws are DEBUG. After 5 consecutive throws the operator warning
  `stream-consumer-drain-failing` is raised once (subject `stream[partition]/group`, naming what was thrown and its
  top frames), and `stream-consumer-drain-restored` once when a pass reads the partition again.
  `[verified: aether/aether-stream/src/test/java/org/pragmatica/aether/stream/DrainPassEscapeRunTest.java, in-process,
  clock and scheduler seams]`. The node hands the runtime its warning sink
  `[mechanism: pinned by OperatorWarningWiringTest.assembly_givesTheSinkToTheStreamConsumerRuntime]`.
- A handler or dead-letter sink that overflows its stack now fails that call with its top three frames, so the retry
  log and the dead-letter entry name the recursing method `[verified: DrainPassEscapeRunTest]`.
- Not changed: a failed READ (a `Cause`, such as `PARTITION_NOT_LOCAL` while a ring materializes) keeps the 50 ms
  poll backoff and its DEBUG line and raises nothing, because it is routine during materialization and failover.
- `[design intent — unverified: the 10 s cap and the threshold of 5 throws are guesses, not derived from a measured
  recovery time or failure distribution.]` `[unverified: that a VirtualMachineError other than StackOverflowError
  still propagates after releasing the loop is not observable in-process; the scheduler's task swallows it.]`

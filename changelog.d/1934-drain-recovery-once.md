### Fixed (2026-10-07 — #1934 follow-up: a cancelled consumer's `stream-consumer-drain-failing` alert could be followed by two recoveries, or by one raised before it)
- **`stream-consumer-drain-restored` now follows its `stream-consumer-drain-failing` alert exactly once, after it, even when a cancel (detach, abandon, idle reap, runtime close) races the raise.**
  A cancel landing while the alert was being raised could make the raw operator-warning sink see a recovery ahead of the failure it ends, then a second one; the aggregator absorbed it, but the sink and the logs showed it.
  `[verified: aether/aether-stream/src/test/java/org/pragmatica/aether/stream/DrainPassEscapeRunTest.java, deterministic through the clock seam]`

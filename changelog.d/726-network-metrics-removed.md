### Removed (2026-09-04 — #726: dead NetworkMetricsHandler chain)

- Removed `NetworkMetricsHandler`, `NetworkMetricsAggregator`, and `NetworkMetrics` — a Netty
  duplex handler constructed once in `AetherNode` but never installed in any pipeline, making
  every value it fed permanently zero (a silent-zero instrument).
  [mechanism: repo-wide grep for `NetworkMetricsHandler`, `NetworkMetricsAggregator`, and
  `NetworkMetrics` returns zero hits outside two historical/dated documents (`CHANGELOG.md` and
  a 2026-04-13 audit) that correctly describe past state and were left untouched]
- Removed the now-orphaned `backpressureRate` field from `DerivedMetrics`/`DerivedMetricsCalculator`
  and the `network` field from `ComprehensiveSnapshot`, since both were sourced exclusively from
  the dead handler.
  [verified: `env -u HCLOUD_TOKEN mvn -q -DskipTests package` from the repo root, no `-pl` — full
  142-module reactor, all SUCCESS, 0 failed/skipped, confirming no other module referenced the
  removed symbols]

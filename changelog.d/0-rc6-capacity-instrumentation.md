### Added (rc6 — arrival instrumentation and capacity stream definitions)
- **Arrival counters before admission**, per artifact and per method/route, external (HTTP ingress) and internal
  (slice-to-slice, topic, scheduled) kept apart: `arrivals`, `accepted`, `rejected` (draining, route rate limit, slice
  absent), `completed`, `failed`. Counted on each node, cumulative, and carried on `PerMethodMetrics` and
  `PerSliceMetrics` (`external`, `internal`). Retries cannot be identified at the receiving side and are not counted. Requests refused before a route resolves (authentication, no route, upload cap) or lost in
  forwarding (forward failure, forwarded re-authorization refusal) are not counted per artifact.
- **`CapacitySample`** record (`minute, community, artifact, arrivals, accepted, completed, failed, rejected, p95,
  instances, quality`) with `CapacityQuality` (`FULL`, `THROUGHPUT_ONLY`, `UNKNOWN`), wire tags 2207-2209.
- **System streams `capacity-minute` and `capacity-hour`** (1.0.0) in `SystemStreams.ALL`, each with its own retention
  (35 days and 3 years); `SystemStreamBootstrap` now applies a stream's own retention and falls back to the default
  (`cluster-events` unchanged). Blueprints cannot write them (the `system:` write gate).

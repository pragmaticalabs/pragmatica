### Added (2026-11-13 — #2583 G1 P0: frames lost after `Sent` are counted)
- **Nine new counters, `droppedAfterSent.<lane>.<site>`**, one per post-`Sent` loss site of the QUIC transport (offline-buffer
  drain, pending lane write, backpressure retry give-up, write failure, offline-buffer and lane-open eviction, replaced
  connection, backstop eviction, retry for a removed peer). They are separate from the backpressure-drop and write-failure
  counters, which a refused send also moves. Read them with `QuicTransportMetrics.droppedAfterSentCount` or
  `droppedAfterSentSnapshot`; they are not in `snapshot()` (the exported key set stays fixed).
- **A refused send is counted per lane** (`QuicTransportMetrics.refusedCount`), and its WARN is limited to one per peer and
  lane per 30 s. The retry give-up (D3), formerly DEBUG, is a WARN under the same limit.
- **A refused frame on a fast-fail lane is no longer encoded first**: the writability check now precedes the encode on every
  lane except CONSENSUS and DHT, which retry.
- **`QuicClusterNetwork.consensusRetriesInFlight()`**: the number of CONSENSUS backpressure retries in flight (DHT excluded),
  the pressure signal for the later consensus gate.
- Test seams: `OutboundFaultMode.DROP_AFTER_SENT` for the outbound fault filter (drop a frame and report `Sent`), and
  package-private `offerOutboundForTest`, `laneOpenFailedForTest`, `handleWriteResultForTest`.
- Residual: `droppedAfterSent.<lane>.offlineOldest` is attributed to the lane of the displacing frame, because the offline
  buffer does not report the lane of the frame it evicts.

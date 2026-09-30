### Fixed (2026-09-30 — #1478: `/info` totals came from the answering node's own rings; metadata `partitionCount` was a constant)
- **`GET /api/v1/streams/{ns}/{stream}/{ver}/info` reported `totalEvents: 0` for a stream whose owner held every event**, because the route is delegate-routed and summed only the answering node's own materialized rings. Each partition is now answered through `StreamReadRouter#bounds` (local ring if held, else the owner over the read-forward path) and `totalEvents` is the sum of those counts. A partition whose owner cannot answer fails the request instead of reading as empty. `totalBytes` remains the answering node's own allocation.
  [verified: `aether/node/src/test/java/org/pragmatica/aether/api/routes/StreamApiRoutesStreamInfoTest.java`]
- **`GET /api/v1/streams/{ns}/{stream}/{ver}` reported `partitionCount: 4` for every stream.** It now reads the declared count from the committed stream config, falls back to the count this node materialized, and answers 409 when neither knows it.
  [verified: `StreamApiRoutesStreamInfoTest`]
- **Harness:** 04-streaming reads `totalEvents` from `/info` and the name from the metadata `stream` field, and fails with "field absent" instead of rendering an absent field as a measured 0.
  [verified: `aether/tests/integration/test/test-stream-info-fields.sh`]
- [unverified: not run against a cluster; `/partitions/{n}` has the same non-holder blind spot and is not changed here]

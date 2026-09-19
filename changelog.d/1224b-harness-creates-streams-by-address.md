### Fixed (2026-09-18 — #1224: two harness checks that could not fail, and one that asserted an impossibility)
- **`04-streaming` created streams through the refused flat route**, and asserted success by matching
  the stream name inside the 200 response body — which the response contains whether or not anything
  was registered. `Create_stream_with_replication` reported PASS on every run while the stream did not
  exist. It now creates at a catalog address via `stream_create` and asserts the stream is
  **resolvable in the catalog**, which is the store publishing actually reads.
- **`08-resources/test_stream_exists_or_created` could not fail for two independent reasons.** It
  grepped the catalog listing for `"name":"<stream>"` — the listing emits `"stream":"..."`, never
  `"name"`, so the match could never succeed and the else branch ran every time, in every
  environment, since the 2026-09-02 migration. And **both** branches called `log_pass`: a found
  stream passed, a missing stream passed as "Stream list endpoint responds".
- **Its else branch also asserted something untrue** — "publishing will auto-create". Publishing
  resolves a name to a catalog coordinate first, so a publish cannot create what the lookup must
  already find. The real failure surfaced four assertions later as `expected '25', got '0'`, which is
  why it read as a streaming defect rather than a missing fixture.
- `lib/cluster.sh` gains `stream_create`, addressing by `namespace:stream:version` like every other
  catalog verb since #1044. Integration fixtures use the `integration` namespace — `system` is
  rejected by the server, and operator-created streams state their namespace explicitly rather than
  inheriting a default that silently meant `system:<name>:1.0.0`.

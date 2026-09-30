### Fixed (2026-09-30 — #1478 harness: stream coordinate collision, transient 503 on publish, and a fixed sleep before the streaming count)
- **`stream_coordinate` no longer picks a stream by bare name across namespaces.** Cluster A holds
  `integration/test-events` and the `org.pragmatica.aether.test.test-persistence/test-events` its deployed
  blueprint declares; the namespace-blind `head -1` over an unordered catalog, re-resolved per publish, split one
  test's writes across both streams (6 + 14 of 20, the batch's 48 in the other) and read as data loss. It now
  returns the exact `integration/<name>/1.0.0` address when present (in either catalog order), a lone match
  otherwise, and FAILS naming the namespaces when a bare name matches several; the exact address is resolved
  once per test process.
- **`stream_publish` retries only a 503 "refused before writing".** 250ms to 1s backoff, 10s budget, each
  absorbed 503 logged; anything else, in particular a 500 "Publish outcome unknown ... FORWARD_TIMEOUT",
  returns after exactly one request (#1750). `13 test-concurrent-deploys` uses the same policy through
  `stream_publish_status`.
- **`test_publish_and_verify_count` polls `/info` `totalEvents` (30s) instead of `sleep 2` and one read, and
  records every publish's offset, partition and answering endpoint.** On a shortfall (also in the batch-of-50 and
  Publish-10-events tests) it prints the acked offsets, the `/info` body, `/replicas/0` and `replicas-local` from
  every node.
  [verified: `aether/tests/integration/test/test-stream-publish-retry.sh` (14 stub tests against the real
  functions); mutations: no retry, retry on any non-2xx, gating on any 5xx while keeping the substring, a single
  read, the capture removed, and the namespace-blind `head -1` each redden the test that pins them. No cloud run.]

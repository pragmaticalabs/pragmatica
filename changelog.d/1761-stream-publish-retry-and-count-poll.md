### Fixed (2026-09-30 — #1478 harness: transient 503 on stream publish, and a fixed sleep before the streaming count)
- **`stream_publish` now retries a transient 503.** A 503 whose body says "retry"/"retryable" (owner not yet
  promoted, not enough replicas) is retried with backoff for up to 10s, then fails with the last body. A 500,
  including "Publish outcome unknown ... FORWARD_TIMEOUT", still fails immediately: without a message id a
  resend can duplicate the publish (#1750). `13 test-concurrent-deploys` uses the same policy through the new
  `stream_publish_status`.
- **`test_publish_and_verify_count` polls `/info` `totalEvents` (30s) instead of `sleep 2` and one read.**
  On timeout it fails quoting the full `/info` body (partitionDetails) and `/replicas/0`, so the next red
  separates a stuck visible offset from a ring-tail undercount.
  [verified: `aether/tests/integration/test/test-stream-publish-retry.sh` (7 stub tests against the real
  functions); no retry reddens 2, retry on 5xx reddens 2, a single read reddens 1. No cloud run.]

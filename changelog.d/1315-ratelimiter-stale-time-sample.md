### Fixed (2026-09-29 — #1315: RateLimiter reused a stale time sample and could mint a full bucket)
- **`tryAcquire()` sampled the clock once, before reading state and before its CAS loop.** When another
  caller advanced `lastRefill` in between, the masked `(now - lastRefill)` turned the small negative
  interval into ~2^48 ns and refilled the bucket, so a caller could be granted permits the limiter had
  just handed out.
- The clock is now sampled after the state read on every CAS attempt, so it is never earlier than the
  `lastRefill` it is compared with; `retryAfter()` uses the same order.
  [verified: `core/src/test/java/org/pragmatica/lang/utils/RateLimiterTest.java`
  `staleTimeSample_afterAnotherCallerExhaustsTheBucket_mintsNoPermit` — a deterministic interleaving, no sleeps]

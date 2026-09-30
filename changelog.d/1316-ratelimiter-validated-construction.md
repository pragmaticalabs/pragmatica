### Changed (2026-09-29 — #1316: RateLimiter accepted configurations its packed state cannot represent)
- **Breaking (pre-GA): `RateLimiter.rateLimiter(...)`, the deprecated `RateLimiter.create(...)`, `builder()...timeSource(...)` and
  `withDefaultTimeSource()` now return `Result<RateLimiter>`.** A configuration the packed
  `[tokens:16 | lastRefill:48]` state cannot represent fails with the typed
  `RateLimiterError.InvalidConfiguration` instead of being truncated or dividing by zero later:
  rate below 1, negative burst, `rate + burst` above 65535, a missing period or time source, a period
  shorter than one nanosecond per permit, and one permit per 2^48 ns or more (which never refilled).
  [verified: `core/src/test/java/org/pragmatica/lang/utils/RateLimiterTest.java`]
- The rate-limit interceptor and the `RateGuard` resource now fail PROVISIONING with that cause when
  their configuration passes its own checks but exceeds what the limiter can represent (for example
  `maxRequests + burst = 65536`); the boundary value 65535 still provisions and grants in full.
  [verified: `aether/resource/interceptors/src/test/java/org/pragmatica/aether/resource/interceptor/RateLimitProvisioningTest.java`]
- Operator action: a slice whose rate-limit configuration is refused fails to provision with the message
  naming the limit; lower `maxRequests + burst` to 65535 or less.

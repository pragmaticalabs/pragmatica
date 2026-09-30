### Fixed (2026-09-30 — integration harness: 02w's post-crash create made one attempt and hid the refusal's cause)
- **02w-entity-crash's `create_entity` retries allow-listed transient refusals for 30s.** It made a single attempt,
  so a post-failover "not ready yet" answer read as "the cluster does not accept creates". A 503 (the product's
  answer for a `Cause.Transient` once app routes map it, #1737/#1765) or a refusal whose `failureType` is on the
  explicit transient allow-list (now including `StorageUnavailable`, #1766) is retried with backoff; the status
  and body of a non-2xx answer are kept (`_api_call` prints a body only for a 2xx, which would have hidden a 503
  refusal). `ForwardRefused` and `StorageFailed` are deliberately not allow-listed, and an empty answer is not
  retried.
- **Failure bodies are no longer cut to 200 bytes** in the create and read diagnostics: the truncation deleted the
  inner cause that distinguishes a transient failover refusal from an ownership fault.
  [verified: `aether/tests/integration/test/test-entity-create-retry.sh` (9 stub tests on the real functions);
  no retry reddens 4, dropping the non-2xx body capture reddens the 503 tests, allow-listing `StorageFailed`
  reddens 2. No cloud run.]

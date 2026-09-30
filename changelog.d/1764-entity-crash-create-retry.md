### Fixed (2026-09-30 — integration harness: 02w's post-crash create made one attempt and hid the refusal's cause)
- **02w-entity-crash's `create_entity` retries allow-listed transient refusals for 30s.** It made a single attempt,
  so a post-failover "not ready yet" answer read as "the cluster does not accept creates". A 503 (the product's
  answer for a `Cause.Transient` once app routes map it, #1737/#1765) or a refusal whose `failureType` is on the
  explicit transient allow-list (now including `StorageUnavailable`, #1766) is retried with backoff; the status
  and body of a non-2xx answer are kept (`_api_call` prints a body only for a 2xx, which would have hidden a 503
  refusal). `ForwardRefused` and `StorageFailed` are deliberately not allow-listed, and an empty answer is not
  retried.
- **The read path (`read_amount`) gets the same treatment.** Once app routes answer 503 for a transient cause, a
  transient read refusal (`FoldInProgress`) would have read as "no node answered"; it now keeps the status and body
  and is retried on a 503 or an allow-listed type until `TRANSIENT_READ_DEADLINE_S`. The readiness probe
  (`wait_for ... entity_post_any`) is left as it was: it measures "is the service answering yet".
- **Failure bodies are no longer cut to 200 bytes** in the create and read diagnostics: the truncation deleted the
  inner cause that distinguishes a transient failover refusal from an ownership fault.
  [verified: `aether/tests/integration/test/test-entity-create-retry.sh` (16 stub tests on the real functions, creates and reads);
  no retry reddens 4, dropping the non-2xx body capture reddens the 503 tests, allow-listing `StorageFailed`
  reddens 2. No cloud run.]

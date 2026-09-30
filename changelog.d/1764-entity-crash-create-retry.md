### Fixed (2026-09-30 — integration harness: 02w's post-crash create made one attempt and hid the refusal's cause)
- **02w-entity-crash's `create_entity` retries allow-listed transient refusals for 30s.** It made a single attempt,
  so a post-failover "not ready yet" answer read as "the cluster does not accept creates". A refusal whose
  `failureType` is on the explicit transient allow-list is retried with backoff; `ForwardRefused` and
  `StorageFailed` are deliberately not allow-listed, and an empty answer is not retried.
- **Failure bodies are no longer cut to 200 bytes** in the create and read diagnostics: the truncation deleted the
  inner cause that distinguishes a transient failover refusal from an ownership fault.
  [verified: `aether/tests/integration/test/test-entity-create-retry.sh` (6 stub tests on the real functions);
  no retry reddens 2, allow-listing `StorageFailed` reddens 1, restoring the truncation reddens 1. No cloud run.]

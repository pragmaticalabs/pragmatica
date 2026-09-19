### Fixed (2026-09-19 — #1224: `aether streams create` returned "created" for a stream that never appeared in the catalog)
- **`aether streams create <name>` reported `{"status":"created"}` but never registered the stream in the catalog**, so `aether streams list` never showed it. The legacy handler called only `StreamPartitionManager#createStream` (materializing the rings) and never touched `StreamRegistry` — the two writes a real create needs, doing just the one nobody reads from `streams list`.
- **New `aether stream create <namespace:stream:version> [--partitions N]`** (singular, catalog-addressed) replaces it, backed by a new `STREAMS_CREATE` management route (`POST /streams/{namespace}/{stream}/{version}`, same 3-path-param shape as `STREAMS_DELETE`). The handler materializes the rings **and** registers a `StreamRegistryEntry` in one call, so a created stream is immediately visible to `streams list`. Idempotent on a repeat create for the same address (`"exists"`, no duplicate registration, no refcount bump). [mechanism: `StreamApiRoutesCreateStreamTest.createStream_newAddress_registersInCatalogAndAppearsInSnapshot`, `createStream_repeatedAddress_isIdempotentAndDoesNotDuplicateRegistration`]
- **Operator-created streams get a permanent catalog reference.** A new `RegisteredByKind.OPERATOR` marks the entry so nothing in the deployment-release path (which resolves release targets from `BlueprintStreamBindings`, never populated by an operator create) can ever reach and release it — the entry is removable only by explicit `stream delete`. [mechanism: `StreamRegistryEntryTest.operatorFactorySetsRegisteredByAndInitialRef`; the registry itself does not special-case the kind in `releaseReference` — the permanence is structural unreachability, not a registry-level guard, which `InMemoryStreamRegistryTest.releaseOnOperatorEntry_registryDoesNotDistinguishKind_removesLikeAnyOther` pins explicitly] [mechanism: `StreamApiRoutesCreateStreamTest.createStream_thenDeleteStream_removesFromCatalogSnapshot` — the explicit `stream delete` route does remove it]
- **The old `aether streams create` (plural, body-carried) now refuses unconditionally**, naming both remedies (the #1044 message shape): the exact `aether stream create <namespace>:<name>:<version>` retype form, and `aether streams list` to find existing catalog addresses. It is not deleted from the CLI tree — only its behavior changed to a refusal. [mechanism: `StreamsLifecycleCommandTest.createCommand_call_refusesAndNamesRetypeForm`]
- **Mutation-probed**: removing the registry-write call (`.flatMap(_ -> registerCatalogEntry(addr))`) from the create handler reddens all three `StreamApiRoutesCreateStreamTest` cases; restoring it returns them to green.

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

### Fixed (2026-09-21 — #968: `POST /api/v1/streams` answered `"created"` for streams that were never registered)

- **The body-carried create wrote the ring buffers and the `StreamConfigKey`, never the catalog.**
  `GET /api/v1/streams`, `/api/v1/streams/namespaces` and every catalog-addressed read consult the
  `StreamRegistryKey` catalog, which the handler did not touch, so the created stream was invisible on
  every node while the response said `"created"`. `POST /api/v1/streams` now parses `name` as a catalog
  address `namespace:stream:version` and runs the same materialize-and-register chain as
  `POST /api/v1/streams/{namespace}/{stream}/{version}`; the created stream is readable back from the
  catalog `[verified: aether/node/src/test/java/org/pragmatica/aether/api/routes/StreamCreateCatalogRegistrationTest.java]`.
  A bare name (no catalog address) is refused with `400` naming the form to retype, never answered
  `"created"`; a missing name is `400` (was `500`). The idempotent `"exists"` branch keeps reporting the
  existing partition count.
- **`KvBackedStreamRegistry.register` fired its consensus put and returned success without awaiting
  it**, logging a refused apply and dropping it — so the catalog-addressed create (#1229) also answered
  `"created"` for an entry that never committed. It now awaits the commit (10 s bound, matching the
  stream-config commit) and returns the failure
  `[verified: aether/slice/src/test/java/org/pragmatica/aether/slice/stream/KvBackedStreamRegistryCommitOutcomeTest.java]`.
  Both create routes answer `"created"` only after both puts committed; on a refused catalog put the
  ring stays materialized and a retry registers it. The `SystemStreamRegistrar` bootstrap leg now sees
  a refused put and retries instead of latching DONE `[mechanism: bootstrap() returns the registry's failure; the registrar retries any leg that fails]`.

### Fixed (2026-09-21 — #968: `POST /api/v1/streams` answered `"created"` for streams that were never registered)

- **The body-carried create wrote the ring buffers and the `StreamConfigKey`, never the catalog.**
  `GET /api/v1/streams`, `/api/v1/streams/namespaces` and every catalog-addressed read consult the
  `StreamRegistryKey` catalog, which the handler did not touch, so the created stream was invisible on
  every node while the response said `"created"`. `POST /api/v1/streams` now parses `name` as a catalog
  address `namespace:stream:version` and runs the same materialize-and-register chain as
  `POST /api/v1/streams/{namespace}/{stream}/{version}`; the created stream is readable back from the
  catalog `[verified: aether/node/src/test/java/org/pragmatica/aether/api/routes/StreamCreateCatalogRegistrationTest.java]`.
- **`KvBackedStreamRegistry.register` fired its consensus put and returned success without awaiting
  it**, logging a refused apply and dropping it — so the catalog-addressed create (#1229) also answered
  `"created"` for an entry that never committed. It now awaits the commit (10 s bound, matching the
  stream-config commit) and returns the failure
  `[verified: aether/slice/src/test/java/org/pragmatica/aether/slice/stream/KvBackedStreamRegistryCommitOutcomeTest.java]`.
  Consequence at bootstrap: **system-stream registration now fails loudly.** The `SystemStreamRegistrar`
  bootstrap leg used to latch DONE on a `system:cluster-events` catalog put that consensus had refused,
  and never retried; it now sees the failure and retries on its backoff until the put commits
  `[verified: aether/node/src/test/java/org/pragmatica/aether/node/SystemStreamRegistrarCatalogCommitTest.java]`.
  The seam these pins use is the registry's `ClusterNode`, whose `apply` can fail; a registry that
  registers synchronously (`StreamNamespacesService.inMemory()`, used by #1229's tests) cannot see the
  drop.
- **The contract of `POST /api/v1/streams`, per outcome** (the HTTP status is the handler's):

  | Outcome | Status | Body |
  |---|---|---|
  | Created — `StreamConfigKey` **and** `StreamRegistryKey` both committed, both awaited | `200` | `{"name", "partitions", "status": "created"}` |
  | Already in the catalog (idempotent repeat) | `200` | `{"name", "partitions": <the existing count>, "status": "exists"}` |
  | `name` missing | `400` (was `500`) | `Missing stream name` |
  | `name` is a bare name (no catalog address), e.g. `batch2probe-app` | `400` | names the `namespace:stream:version` form to retype; nothing minted — a bare name has no catalog address, so nothing could list, read or delete the stream it would mint (no default namespace is invented, consistent with #1224's declined option) |
  | `name` carries a reserved stream-kind prefix (`system:`, `topic:`, `entity:`) | `400` | `ReservedStreamName`; runs before the existence check (#1282), never `"exists"` — unchanged |
  | `name` is an enumerated system stream, bare or catalog spelling | `405` | `system:*` write gate — unchanged |
  | Stream config commit failed or timed out (10 s) | `500` | the cause; nothing registered |
  | Catalog entry commit failed or timed out (10 s) | `500` | the cause; the ring stays materialized and a retry registers it |

- **A fixture that encoded the defect was replaced, not deleted.**
  `StreamRoutesCreateSystemStreamTest.createStream_ordinaryAppStreamName_stillSucceeds` created the bare
  name `orders` and asserted only that the engine map held it — the half-write itself, specified as
  intended behaviour. It is replaced by `createStream_bareAppStreamName_isRefusedWith400AndNothingIsMinted`
  and `createStream_ordinaryAppStreamAddress_stillSucceedsAndIsCatalogued`.

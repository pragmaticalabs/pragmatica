### Fixed (2026-09-21 — #968: `POST /api/v1/streams` answered `"created"` for streams that were never registered)

- `POST /api/v1/streams` (body-carried `name`) now parses `name` as a catalog address
  `namespace:stream:version` and runs the same materialize-and-register chain as
  `POST /api/v1/streams/{namespace}/{stream}/{version}`, so a created stream is visible in
  `GET /api/v1/streams` and `/api/v1/streams/namespaces` on every node. Before, it only materialized
  ring buffers and committed the `StreamConfigKey`; the `StreamRegistryKey` catalog every read route
  consults was never written. A bare name (no catalog address) is refused with `400` naming the form
  to retype, never answered `"created"`; a missing name is `400` (was `500`). The idempotent `"exists"`
  branch keeps reporting the existing partition count.
- `KvBackedStreamRegistry.register` awaits the consensus commit (10 s bound, matching the stream
  config commit) instead of firing it, logging the failure and returning success. Both create routes
  now answer `"created"` only after the catalog entry committed; a refused or timed-out commit is the
  request's failure, and the `SystemStreamRegistrar` bootstrap leg retries instead of latching DONE
  on a put that never landed.

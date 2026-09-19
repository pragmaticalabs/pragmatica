### Fixed (2026-09-19 — #1282: Management API could create streams under reserved prefixes with arbitrary config before the real resource existed)
- **An operator could plant a stream under a runtime-reserved name.** Stream kind is carried by the
  engine-name prefix: `system:` for system streams, `topic:` for durable topics and their DLQs, and
  `entity:` for entity keyspace logs. Runtime rules key off that prefix. The Management API could mint a
  stream under any of these prefixes with a management-default config, so the real durable topic, entity
  keyspace or system stream would later find it already in place. There were four such paths:
  - `POST /api/v1/streams`: the existing guard refused only the enumerated system streams, so
    `system:foo:1.0.0`, `topic:…` and `entity:…` all went through;
  - `POST /api/v1/streams/{namespace}/{stream}/{version}` with a `topic` or `entity` namespace;
  - both publish auto-create fallbacks.
- All four now refuse an engine name with a reserved prefix. The response is `400 Bad Request` with a
  typed `ManagementServerError.ReservedStreamName` naming the prefix, and nothing is created or
  registered. The prefixes are read from their canonical owners (`ResourceAddress.SYSTEM_NAMESPACE`,
  `DurableTopicNames.TOPIC_STREAM_PREFIX`, `EntityPartitionArc.ARC_PREFIX`). The enumerated system
  streams keep their existing `405`. [mechanism: `ReservedStreamNames.requireUnreserved` runs before each
  mint; pinned in-JVM by `StreamRoutesReservedPrefixTest`]
- Unchanged:
  - Internal provisioning, which calls `StreamPartitionManager` directly, still creates these streams.
  - A publish that finds a committed config for a reserved name adopts it, because that config belongs
    to the real resource.
  - A `system`-namespace catalog address is not reserved: it reduces to a bare flat-stream name.

  [mechanism: the guard sits only in the Management-API routes and only on the management-default
  branch; pinned in-JVM by `StreamRoutesReservedPrefixTest`]
- Docs: `management-api.md` gains a *Reserved stream-name prefixes* section and documents the catalog
  create endpoint. `cli.md` documents `aether stream create` and marks the removed
  `aether streams create` as removed, which #1224 had left stale.
- Operator action: create a durable topic, entity keyspace or system stream through its own declaration
  (blueprint/resources), never through the stream-create API.

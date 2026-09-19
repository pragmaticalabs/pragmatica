### Fixed (2026-09-19 — #1282: Management API could create streams under reserved prefixes with arbitrary config before the real resource existed)
- **An operator could plant a stream under a runtime-reserved name.** Stream kind is carried by the
  engine-name prefix: `system:` for system streams, `topic:` for durable topics and their DLQs, and
  `entity:` for entity keyspace logs. Runtime rules key off that prefix. The Management API could mint a
  stream under any of these prefixes with a management-default config, so the real durable topic, entity
  keyspace or system stream would later find it already in place. The unguarded paths were:
  - `POST /api/v1/streams`: the existing guard refused only the enumerated system streams, so
    `system:foo:1.0.0`, `topic:…` and `entity:…` all went through;
  - `POST /api/v1/streams/{namespace}/{stream}/{version}` with a `topic` or `entity` namespace;
  - the catalog publish auto-create fallback, for `publish` and `publish-batch`. The legacy
    `StreamRoutes` auto-create is guarded too, although it has no production caller;
  - a blueprint `[streams.X]` section with `source = "topic:…"` or `"entity:…"`. The slice's stream
    factories mint whatever engine key the binding resolves to, and an `entity:` name can exactly match a
    real keyspace log, because keyspace names may contain `:`.
- The Management-API paths now refuse a reserved engine name with `400 Bad Request` and a typed
  `ManagementServerError.ReservedStreamName` naming the prefix, and nothing is created or registered.
  The enumerated system streams keep their existing `405`. [mechanism: `ReservedStreamNames.requireUnreserved`
  runs before each mint; pinned in-JVM by `StreamRoutesReservedPrefixTest`]
- **No existence oracle.** On both create routes the refusal runs before the existence check, so an
  existing reserved name is refused instead of being answered `"exists"`. [mechanism: pinned by
  `StreamRoutesReservedPrefixTest`]
- **Batch publish answers `400`, not `500`.** `publish-batch` ensures the stream once, before the
  per-item fan-out. The refusal therefore surfaces as itself, rather than inside a composite cause that
  is not `HttpStatusAware` and was rendered as 500. [mechanism: pinned by `StreamRoutesReservedPrefixTest`]
- **Blueprints:** a reserved `External` source is refused at blueprint validation with the typed
  `StreamSourceError.ReservedKindSource` under rule `source-reserved-kind`. A validation failure publishes
  empty bindings, so the alias then fails as `UnboundStreamAlias` rather than minting. A
  `system`-namespace source and another blueprint's namespace still parse. No legitimate reference
  exists, for two reasons: spec §11.2 limits External sources to another blueprint's namespace or
  `system`, and a real durable-topic stream is not addressable in the three-part form. [mechanism:
  pinned by `StreamConfigParserReservedSourceTest` and `StreamResourceValidatorTest`]
- The prefixes are declared once, as `StreamEngineKey.RESERVED_KIND_PREFIXES` in `slice-api`, because
  the blueprint parser cannot see the modules that own these names. `ReservedStreamNamesTest` pins that
  list against `ResourceAddress.SYSTEM_NAMESPACE`, `DurableTopicNames.TOPIC_STREAM_PREFIX` and
  `EntityPartitionArc.ARC_PREFIX`.
- Unchanged:
  - Internal provisioning, which calls `StreamPartitionManager` directly, still creates these streams.
  - A publish that finds a committed config for a reserved name adopts it, because that config belongs
    to the real resource.
  - A `system`-namespace catalog address is not reserved: it reduces to a bare flat-stream name.

  [mechanism: the guard sits only in the Management-API routes and only on the management-default
  branch; pinned in-JVM by `StreamRoutesReservedPrefixTest`]
- Internal provisioning is pinned separately for each kind: durable topic (`DurableTopicSubstrate`),
  entity keyspace (`StreamEntityLogSubstrate`), and a system stream created directly through
  `StreamPartitionManager`.
- Docs: `management-api.md` gains a *Reserved stream-name prefixes* section and documents the catalog
  create endpoint. `cli.md` documents `aether stream create`, including the error text it prints, and
  marks the removed `aether streams create` as removed, which #1224 had left stale. The CLI needs no code
  change: it already prints the server's problem detail. Dashboard: no surface, because this is only an
  error response on a write path. The dormant slot is deliberate.
- Operator action: create a durable topic, entity keyspace or system stream through its own declaration
  (blueprint/resources), never through the stream-create API.

### Fixed (2026-10-11 — #1338: a blueprint External `system:` source resolved to a bare name no stream exists under)
- **A blueprint that consumed a system stream bound to a stream that never existed.** A `[streams.X]` section with
  `source = "system:cluster-events:1.0.0"` resolved (`BlueprintStreamAddresses.engineKeyFor`) to the bare name
  `cluster-events`, while the framework creates, publishes and consumes the system stream under its full address
  `system:cluster-events:1.0.0`. The consumer polled a ring nobody writes to, with no error. It now resolves to the
  full address. Every other namespace is unchanged: the two spellings only ever differed for `system`.
  [mechanism: `BlueprintPublishOwnershipTest$StreamBindings#publish_externalSystemSource_resolvesToTheNameTheSystemStreamIsCreatedUnder`
  publishes a real blueprint and looks the resolved key up in a real `StreamPartitionManager` holding the stream
  created under `SystemStreams.CLUSTER_EVENTS.asString()`]
- **Decision: a `system:` External source stays allowed.** Spec `event-stream-namespaces-spec.md` §6.1 and §11.2 make
  system-stream reads open to slices, and `StreamConfigParserReservedSourceTest#externalSource_systemNamespace_stillParses`
  pins that the #1282/#1336 reserved-kind gate admits it. The `system:` entry in `RESERVED_KIND_PREFIXES` guards the
  Management-API mint paths, not this read binding. The gate is unchanged.
- Not changed, and still divergent: `StreamEngineKey.engineKey` (Management-API routes and the system write gate)
  still reduces a `system` address to its bare name, so those routes address a name the system stream is not created
  under. That is a wider question than this fix.

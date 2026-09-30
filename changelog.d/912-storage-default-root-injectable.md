### Fixed (2026-09-29 — #912: a node test wrote under the machine-global /data/aether)
- **The default storage root was the fixed `/data/aether`, with no way to point it elsewhere**, so a test that
  boots a node from a config with no storage paths wrote there. #1276 made `StorageFactory`'s own tests
  hermetic, but a canary run of `aether/node`, `integrations/storage` and `aether/aether-config` (12,953
  tests) still found one writer: `ShippedConfigBootTest`, which boots from the shipped image config and
  created `storage`, `content` and a live stream WAL under the default root.
- The root is now injectable: the JVM system property `aether.storage.defaultRoot` replaces `/data/aether`
  for every default `disk_path`/`snapshot_path`, and `ConfigLoader` takes its defaults from the same source
  instead of its own copy of the literals. `ShippedConfigBootTest` roots it in a temp directory and asserts
  the node's stream directory landed there. Production behaviour without the property is unchanged.
  [verified: `aether/node/src/test/java/org/pragmatica/aether/node/ShippedConfigBootTest.java`, plus a canary
  re-run of the same three modules that creates nothing under the default root]

### Fixed (2026-09-29 — #856: storage instances that omitted their paths silently shared one directory)
- **Every `[storage.<name>]` section that omits `disk_path`/`snapshot_path` resolves the same
  `/data/aether/...` default**, so an explicit instance without paths beside the synthesized `artifacts`
  shared its directories: each `LocalDiskTier` counted the other's bytes against its own `disk_max_bytes`
  (demotion and GC pressure triggered early), and both wrote metadata snapshots into one directory.
- The node now **refuses to boot** when two instances' disk or snapshot directories coincide or one lies
  inside the other, naming both instances and paths, before any instance is built.
  Recovery: give every `[storage.<name>]` section its own `disk_path` and `snapshot_path`.
  [verified: `aether/node/src/test/java/org/pragmatica/aether/node/StorageFactoryPathOverlapTest.java`
  — through the real `StorageFactory.createAll`, including the production defaults]
- **Also fixed: a `[storage.streams]` section crashed the boot.** It is how `wal_path` is set (#634-3),
  but `createAll` built it as a second `streams` instance beside the built-in one, and collecting them
  threw `IllegalStateException: Duplicate key streams`. The section now only carries `wal_path`.
  [verified: `aether/node/src/test/java/org/pragmatica/aether/node/StorageFactoryStreamsSectionTest.java`
  — the exact `createAll` call `AetherNode` makes; not a full node boot]

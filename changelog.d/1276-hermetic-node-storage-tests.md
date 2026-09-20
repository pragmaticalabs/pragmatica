### Fixed (2026-09-19 — #1276: node tests read and wrote the machine-global /data/aether/storage)
- **`aether/node` tests resolved `StorageConfig.storageConfig()`'s absolute `/data/aether/...` default,
  so on any host where `/data` is writable they shared persistent storage across runs, trees and
  branches.** One run's encryption marker made every later keyring-less run refuse to boot: 32
  `aether/node` tests failed on such a host with "encrypted under key id 'key-1' but no encryption
  keyring is configured". Every node test now roots its storage in its own `@TempDir` through a
  test helper, `HermeticStorage`. The root sits under a regular file, so its disk tier cannot be
  created on any host, root included. That is exactly the memory+DHT degradation these tests always
  had on CI, where `/data` is not writable.
- `StorageFactory.createAll` always synthesizes `artifacts` and `content` from the production default
  when a node's config lacks them. It gains a package-private overload that takes the `defaults` to
  synthesize from, so the tests that pin the synthesis itself run hermetically instead of relying on
  `/data` being unwritable. Production callers are unchanged and still use
  `StorageConfig.storageConfig()`. [mechanism: the existing overloads delegate with
  `StorageConfig.storageConfig()`]
- **`EmberCluster` without `withDataBaseDir` now ALWAYS runs its nodes without the disk tier and
  without the stream WAL: storage is memory + DHT, and streaming is not crash-durable.** Before this,
  that was true only where `/data` was not writable. Where it WAS writable, those nodes resolved the
  same production default, and every cluster on the machine shared one `/data/aether` directory across
  runs, trees and branches. Each cluster now roots its nodes' storage under a regular file in its own
  temp dir, so nothing can be created there on any host. It creates that dir lazily and deletes it on
  `stop()` or after a failed start. Clusters that call `withDataBaseDir` are unchanged: they keep a writable disk tier and WAL.
  [mechanism: `EmberCluster.perNodeStorageConfig` falls back to `unwritableStorageBase()` instead of an
  empty map; pinned by `EmberClusterHermeticStorageTest`]
- The production default path itself is unchanged; see #1276 for the separate decision on it.

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
- The production default path itself is unchanged; see #1276 for the separate decision on it.

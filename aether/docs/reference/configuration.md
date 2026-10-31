# Configuration Reference

Complete reference for configuring Aether nodes, cluster, and runtime behavior.

## Node Configuration

### AetherNodeConfig

Main configuration for an Aether cluster node. There is no all-args or partial-args static
factory — construction is only via a staged `builder()` chain; each stage's setter returns the
next stage's type. `self` through `quicTls` are required (no default); `.build()` becomes
available starting at `certificateProvider`, and every stage from there defaults if skipped.

```java
var config = AetherNodeConfig.builder()
    .self(self)                       // NodeId - unique node identifier
    .coreNodes(coreNodes)             // List<NodeInfo> - cluster peers
    .managementPort(managementPort)   // int - HTTP API port (0 to disable)
    .sliceConfig(sliceConfig)         // SliceConfig - slice repository configuration
    .artifactRepo(artifactRepoConfig) // DHTConfig - artifact repository settings
    .coreMax(coreMax)                 // int - target cluster size (0 = coreNodes.size())
    .appHttp(appHttpConfig)           // AppHttpConfig - slice-route HTTP server
    .tls(Option.none())               // Option<TlsConfig> - cluster/HTTP TLS
    .quicTls(quicTlsConfig)           // TlsConfig - QUIC cluster transport TLS
    .build();                         // remaining stages take their per-stage default
```

| Stage (required, no default) | Type | Description |
|---|---|---|
| `self` | `NodeId` | Unique node identifier |
| `coreNodes` | `List<NodeInfo>` | Cluster peers |
| `managementPort` | `int` | HTTP management API port (0 to disable) |
| `sliceConfig` | `SliceConfig` | Slice repository configuration |
| `artifactRepo` | `DHTConfig` | Artifact repository settings |
| `coreMax` | `int` | Target cluster size (0 uses `coreNodes.size()`) |
| `appHttp` | `AppHttpConfig` | Application HTTP server for slice routes |
| `tls` | `Option<TlsConfig>` | TLS for cluster and HTTP |
| `quicTls` | `TlsConfig` | QUIC cluster transport TLS |

Stages from `certificateProvider` onward default if skipped (each is also settable explicitly
before calling `.build()`):

| Stage | Default when `.build()` is called early |
|---|---|
| `certificateProvider` | `Option.none()` |
| `configProvider` | `Option.none()` |
| `environment` | `Option.none()` |
| `managementHttpProtocol` | `HttpProtocol.H1` |
| `storageConfig` | `Map.of()` |
| `backupConfig` | `Option.none()` |
| `membership` | `Option.none()` |
| `streaming` | `StreamingConfig.streamingConfig()` |
| `protocol` | `ProtocolConfig.defaultConfig()` |
| `sliceAction` | `SliceActionConfig.sliceActionConfig()` |
| `cache` | `DHTConfig.CACHE_DEFAULT` |
| `ttm` | `TtmConfig.ttmConfig()` |
| `rollback` | `RollbackConfig.rollbackConfig()` |
| `controllerConfig` | `ControllerConfig.DEFAULT` |
| `autoHeal` | `AutoHealConfig.DEFAULT` |
| `observability` | `ObservabilityConfig.DEFAULT` |
| `atomicity` | `DeploymentAtomicity.ALL_OR_NOTHING` |
| `activationGated` | `false` |
| `timeouts` | `TimeoutsConfig.timeoutsConfig()` |
| `workerConfig` | `Option.none()` |
| `deploymentDefaults` | `DeploymentDefaults.DEFAULT` |
| `clusterFormation` | `ClusterFormationConfig.defaults()` |

Three fields have no builder stage at all — they're set on the built config, because their
authoritative source is resolved outside the builder (see the `withAutoHeal`/`withClusterName`/
`withStorageEncryption` doc comments in `AetherNodeConfig.java`):

| Method | Type | Description |
|---|---|---|
| `withClusterName()` | `Option<ClusterName>` | Cluster identity, stamped from `AETHER_CLUSTER_NAME` |
| `withAutoHeal()` | `AutoHealConfig` | Auto-heal retry configuration (overrides the builder default) |
| `withStorageEncryption()` | `Option<StorageEncryptionConfig>` | Storage-tier encryption keyring |

See `Main.java`'s `run()` method for the full production wiring, including these three post-build calls.

When `[tls]` `auto_generate = false` with operator-provided `cert_path`/`key_path`, the node
**refuses to start** if `AETHER_INSECURE_DEV_MODE=true` is also set — insecure dev-mode is
incompatible with real operator certificates. See
[TLS Certificate Management](../operators/tls-certificates.md).

## Slice Configuration

### SliceActionConfig

Controls slice lifecycle timeouts and behavior.

```java
SliceActionConfig.sliceActionConfig();
SliceActionConfig.sliceActionConfig(frameworkJarsPath);
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `loadingTimeout` | 2 minutes | Max time for slice loading |
| `activatingTimeout` | 1 minute | Max time for slice activation |
| `deactivatingTimeout` | 30 seconds | Max time for slice deactivation |
| `unloadingTimeout` | 2 minutes | Max time for slice unloading |
| `startStopTimeout` | 5 seconds | Max time for start/stop |
| `repositories` | Local repository | Artifact repositories |
| `artifact_archive_retention` | `7d` | Minimum time a version must have been stored in the built-in artifact store before it may be archived (`aether artifacts archive`). Must be a positive duration; an unparseable or non-positive value fails config validation |
| `artifact_max_versions` | `10000` | Bound on the PRESENT (not archived) versions of one artifact in the built-in store. A new version past it is refused (`409`, nothing uploaded); existing versions are never dropped and archiving frees room. Must be at least 1 |
| `frameworkJarsPath` | none (`Option.empty()`) | Custom framework JARs path |

## Controller Configuration

### ControllerConfig

Controls automatic scaling behavior.

```java
var defaults = ControllerConfig.DEFAULT;  // Pre-validated production defaults
ControllerConfig.controllerConfig(
    cpuScaleUpThreshold,       // double - CPU % to trigger scale up
    cpuScaleDownThreshold,     // double - CPU % to trigger scale down
    callRateScaleUpThreshold,  // double - calls/sec to trigger scale up
    evaluationIntervalMs       // long - evaluation frequency
);  // Returns Result<ControllerConfig> with validation
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `cpuScaleUpThreshold` | 0.8 (80%) | CPU utilization to trigger scale up |
| `cpuScaleDownThreshold` | 0.2 (20%) | CPU utilization to trigger scale down |
| `callRateScaleUpThreshold` | 2000 | Calls/sec to trigger scale up |
| `evaluationIntervalMs` | 1000 | Evaluation interval (ms) |
| `warmUpPeriodMs` | 30000 | Warm-up period before scaling (ms) |
| `sliceCooldownMs` | 10000 | Cooldown between scaling actions (ms) |

### Runtime Configuration via API

```bash
# View current config
curl http://localhost:8080/api/v1/controller/config

# Update config
curl -X POST http://localhost:8080/api/v1/controller/config \
  -H "Content-Type: application/json" \
  -d '{
    "cpuScaleUpThreshold": 0.75,
    "cpuScaleDownThreshold": 0.15,
    "evaluationIntervalMs": 2000
  }'
```

## Topology Configuration

### TopologyConfig

Cluster topology and node discovery.

| Parameter | Default | Description |
|-----------|---------|-------------|
| `self` | required | This node's identifier |
| `clusterSize` | required | Number of nodes in the cluster |
| `reconciliationInterval` | 5 seconds | Cluster state sync interval |
| `pingInterval` | 1 second | Health check interval |
| `helloTimeout` | 5 seconds | Connection handshake timeout |
| `coreNodes` | required | List of cluster peers |
| `tls` | none | TLS configuration |
| `backoff` | defaults | Connection backoff configuration |

### Peer Format

Peers are specified as `NodeInfo` objects:

```java
// NodeId.nodeId and NodeAddress.nodeAddress return Result; unwrap() is for brevity in this example
var node1 = NodeInfo.nodeInfo(NodeId.nodeId("node-1").unwrap(),
                              NodeAddress.nodeAddress("192.168.1.1", 8090).unwrap());
```

Or in string format for CLI/Podman:
```
node-1:192.168.1.1:8090,node-2:192.168.1.2:8090,node-3:192.168.1.3:8090
```

## Protocol Configuration

### ProtocolConfig

Rabia consensus protocol settings.

```java
ProtocolConfig.defaultConfig();   // Production defaults
ProtocolConfig.testConfig();      // Faster sync retry for tests
```

| Parameter | Production | Test | Description |
|-----------|------------|------|-------------|
| `cleanupInterval` | 60s | 60s | Interval for cleaning up old phases |
| `syncRetryInterval` | 5s | 100ms | State sync retry interval |
| `removeOlderThanPhases` | 100 | 100 | Remove phases older than N behind current |

## DHT Configuration

### DHTConfig

Distributed hash table for artifact storage.

```java
var standard = DHTConfig.DEFAULT;      // 3 replicas, quorum of 2
var full = DHTConfig.FULL;             // Full replication (all nodes)
var single = DHTConfig.SINGLE_NODE;    // Single-node testing
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `replicationFactor` | 3 | Number of replicas (0 = full replication) |
| `writeQuorum` | 2 | Write quorum size |
| `readQuorum` | 2 | Read quorum size |
| `operationTimeout` | 30 seconds | Operation timeout |

## Storage Encryption Configuration

### `[storage.encryption]` — #253

At-rest encryption for block-storage tiers, keyed through the node's existing `SecretsProvider`
(no dedicated key-management service; Vault/#119 integration is a deferred follow-up).

```toml
[storage.encryption]
active_key_id = "k1"
streams_encrypted = false        # opts the built-in `streams` instance's segment-block tiers in

[storage.encryption.keys]
k1 = "${secrets:storage-key-v1}"
k0 = "${secrets:storage-key-v0}"  # retired key, kept resolvable for reads only
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `active_key_id` | required if section present | Key id (must be a key in `keys`) new writes encrypt under |
| `streams_encrypted` | false | Opts the built-in `streams` instance's segment-block tiers into encryption — `streams` has no `[storage.streams] encrypted` field of its own |
| `keys.<id>` | none | `${secrets:<path>}` reference resolved via `SecretsProvider`, one per key id. Secret value must be a Base64-encoded 32-byte AES-256 key |

Per-instance opt-in (any `[storage.<name>]` section, e.g. `[storage.artifacts]`):

| Parameter | Default | Description |
|-----------|---------|-------------|
| `encrypted` | false | Wraps this instance's disk/DHT tiers in `EncryptingStorageTier`. Requires `[storage.encryption]` to be present with at least one key |
| `prefetch` | true | Cross-node block prefetching for this instance: the node gossips hints about the blocks it reads often (one instance, one random peer, once per SWIM probe round) and copies hinted blocks into its own memory tier. `false` switches it off for the instance. Not read for `streams`, which prefetches its own segments |
| `max_block_bytes` | 16777216 (16 MiB) | The largest block this instance accepts. A `put`, `putRef` or `swapRef` of more bytes is refused with `BlockTooLarge` before anything is claimed or written; a `ContentStore` whose `chunkSizeBytes` would make larger blocks (the chunk plus 7 bytes of envelope, plus the size header and codec expansion when compressed) is refused when it is provisioned, naming both values. Must be a positive integer; a value that is not is refused at load. Not read for `streams`, whose sealed segments are not capped |
| `anchor_retention` | 8 | How many versions of each durable anchor (the records a cold boot uses to find a namespace's catalog root) the instance keeps below the newest one it wrote; older versions are pruned in the background after each write. Must be an integer of at least 2; a value that is not is refused at load. The default is provisional, pending the catalog commit benchmark |
| `prefetch_max_in_flight` | 4 | The most prefetches running at once on this instance. A hint that finds every slot taken is dropped and counted (`droppedInFlight` under `prefetch` in `GET /api/v1/storage/{name}`), never queued. Must be a positive integer; a value that is not is refused at load. Not read for `streams` |

Per-instance kill switch for background tier promotion (any `[storage.<name>]` section except `streams`, which never promotes):

| TOML Key | Default | Description |
|----------|---------|-------------|
| `promotion` | true | Copies this instance's hot blocks (more than 50 reads in 5 minutes) from a slower tier into the node's faster tier, on EVERY node, on the `[timeouts.storage_maintenance]` tick. Never fills a faster tier past 70% of its budget, so promotion and demotion do not trade the same blocks. `false` turns it off for the instance. Only blocks this node holds a lifecycle record for are candidates: those it wrote or restored from its snapshot |

**Boot behavior.** Every key in `keys` is resolved through the live `SecretsProvider` once, at boot,
bounded by a 30-second timeout. Boot fails — naming the key id, never the secret value — if any key
fails to resolve (malformed `${secrets:...}` reference, provider failure, invalid Base64, wrong
decoded length), if `active_key_id` is absent from `keys`, or if encryption is requested
(`encrypted = true` on any instance, or `streams_encrypted = true`) but `[storage.encryption]` is
missing entirely. If the node's environment has no `SecretsProvider` at all while
`[storage.encryption]` is present, boot fails with `NoSecretsProviderForStorageEncryption`.

**Coverage.** Encrypts block payloads (`get`/`put`) on `LocalDiskTier` and `DhtStorageTier` for any
instance with `encrypted = true`, and on the `streams` instance's segment-block tiers when
`streams_encrypted = true`. The auto-synthesized default `artifacts` and `content` instances
(created when no explicit `[storage.artifacts]`/`[storage.content]` section is configured) track
node-wide keyring presence: encrypted whenever `[storage.encryption]` is configured with a
resolvable keyring, plaintext otherwise — an explicit `[storage.artifacts]`/`[storage.content]`
section is only needed to opt out or to fine-tune other settings, not merely to get coverage
(`content` joined this coverage in #783; it previously bypassed the config/keyring wiring, and
demotion/GC, entirely via a separate factory path). Does **not** cover: `MemoryTier` (in-process,
never touches disk); metadata/refs/snapshot files (`MetadataStore`/`SnapshotManager`) for any
instance, `streams` included; the `streams` instance's write-ahead log (`walPath`, #634-3) — a
separate directory from the segment-block tiers that `streams_encrypted` gates, and never wrapped
in `EncryptingStorageTier`.

**`content` encryption is triggered by keyring presence, not by opting in.** The synthesized
`content` instance takes `encrypted = <a keyring is configured>`, exactly like the synthesized
`artifacts` default. Two consequences worth knowing before you upgrade or reconfigure:

- Enabling `[storage.encryption]` for any instance turns on `content` encryption too. If `content`
  already holds plaintext blocks, they become unreadable — the disk tier refuses at boot and the DHT
  tier fails per read (`LegacyPlaintextBlock`). There is no migration path in rc4 (#831). Set
  `[storage.content] encrypted = false` explicitly to opt out. See
  [`known-limitations.md`](known-limitations.md) for the upgrade note in full.
- The synthesized `content` default derives its `disk_path`/`snapshot_path` as a sibling of whatever
  `artifacts` resolves to (`<artifacts disk_path>/../content/{blocks,snapshots}`), and it ignores an
  explicit `[storage.artifacts] snapshot_path`. If `artifacts` sits on a mounted volume, content's
  tier may land outside it. Configure `[storage.content]` explicitly to control placement.

**Storage paths are per instance (#856, #912, #249).** Paths resolve under the default root, `/data/aether`; the JVM
system property `aether.storage.defaultRoot` names another root. `[storage.artifacts]` that omits `disk_path` /
`snapshot_path` takes `<root>/storage` and `<root>/metadata-snapshots` (and `content` its sibling, below). A section of
any other name that sets **neither** key gets its own directories beside `artifacts`, placed as `content` is:
`<name>/blocks` and `<name>/snapshots` next to `artifacts`' disk path (`<root>/<name>/...` by default, and they move
with it when `[storage.artifacts] disk_path` is set). Not `<root>/storage/<name>`: that would lie inside `artifacts`'
directory, whose disk tier counts everything under it. A section that sets only one of the two keys resolves the other to the `artifacts`
default. Two instances whose disk or snapshot directories coincide, or where one lies inside the other, are refused
at boot with `StoragePathsOverlap` naming both. Give each instance its own paths.

**A disk tier that cannot open (A0b).** What happens depends on who placed it.
- **An operator-written `disk_path` fails the boot, and is never created.** A `[storage.<name>]` section that writes
  `disk_path` asked for that tier; if the path does not exist, or cannot be opened (permissions, a file in the way), the node
  refuses to start with `[storage.<name>] disk_path = '...' cannot be opened (...)`. The node never creates an operator-set
  path: on a volume that was not mounted, creating it would put the tier on the ephemeral layer underneath. Create or mount the
  directory, fix the permissions, or remove `disk_path` to take the node's default placement (which the node may create).
- **A tier the node placed degrades, loudly.** The synthesized `artifacts` and `content` defaults, a derived path, and a
  section without `disk_path` keep running on memory plus the shared tier, as before. They now also raise the
  `storage-disk-tier-unavailable` operator warning (a cluster event, subject = the instance) and set the gauge
  `aether.storage.disk.degraded{instance}` to 1. Writes to such an instance are acknowledged without a durable local copy.
- `streams` is unchanged: a production boot refuses an unopenable stream directory unless
  `-Daether.allowNonDurableStreams=true` (#1567 F12); a directly built node that degrades raises the same warning.

**One node per mount (A0b).** A node claims its disk tier's directory by taking an exclusive operating-system file lock on
`.mount-claim` in it (`FileChannel.tryLock`), held for as long as the tier is in use. A second node on the same directory
refuses to boot (`Storage mount ... is already claimed by node '...'`), because two nodes sharing a mount would each collect the
other's blocks as orphans. The kernel drops the lock when its holder exits, however it exits, so nothing guesses whether a
holder is alive and no stale claim is ever reclaimed or deleted; the node id and process id inside the file only name the
holder in the refusal. A graceful shutdown (and a boot refused after the claim was taken) empties the file and drops the lock.
What this does and does not guarantee:
- It excludes every process that takes the claim on the same host kernel, including other containers that bind-mount the same
  directory. Inside one JVM (Ember, Forge) a second node id on a held mount is refused too; the same node id taking its mount
  again is a node restarted in that JVM and takes over.
- The lock is advisory: a process that does not take the claim (an operator's `cp`, another program) is not excluded.
- A mount shared between hosts (NFS and other network filesystems) is excluded only as far as that filesystem's lock service
  is: it must enforce byte-range locks across clients, and a lock service that loses state can release a live holder's lock.
  A filesystem without lock support fails the claim with an I/O error rather than passing silently; what that does to the boot
  depends on who placed the tier: a node-placed (default or derived) tier degrades to memory plus the shared tier, with the
  `storage-disk-tier-unavailable` warning, while an operator-written `disk_path` refuses the boot.
- A refusal that names a process which no longer exists means the lock service still holds a dead client's lock; restart that
  service or use another directory.
`streams` keeps its per-node directory instead.

**Memory tiers are bounded by the heap (A0b).** The memory tiers of all instances together may take at most 25% of the JVM's
maximum heap (the `streams` hot ring of 16 MiB counts against it). When the configured `memory_max_bytes` values add up to more,
each instance is cut in proportion to what it asked for, never below 1 MiB, with a WARN and the `storage-memory-tier-clamped`
operator warning naming the configured and the effective bytes. At the stock `-Xmx256m` the 25% is 64 MiB, of which the `streams` ring takes 16 MiB, so the instances' memory tiers share
48 MiB and the default 256 + 256 MiB is cut. The 1 MiB floor is applied after the cut, so with many instances the total can
exceed the 25%. Raise `-Xmx`, or lower `memory_max_bytes`, to quiet it.

**Not the same as `[streams.X].encryption_key_id`.** That per-stream blueprint key is unrelated and
was already found structurally inert and rejected at validation (`#576`, 2026-08-27) —
`StorageSegmentSink`'s own segment-payload pipeline has no encryptor wired to it, and `#253` does not
change that. `streams_encrypted` above only affects the generic storage-tier framework's `streams`
instance.

**Wire format.** Each encrypted block is stored as
`MAGIC("AEC1") | VERSION | KEY_ID_LEN | KEY_ID | NONCE(12B) | CIPHERTEXT+GCM_TAG`. Everything before
the nonce is authenticated (AES-GCM AAD) but not encrypted — editing the header, including swapping
in a different valid key id, fails decryption closed. A block with no recognizable header (fewer
than 4 bytes, or a non-matching magic) is treated as **legacy plaintext**: enabling encryption over a
local-disk directory that already holds unmarked block files is refused at boot; a DHT tier has no
directory to scan for that forward-direction check, so an unmarked plaintext block on the DHT tier is
instead caught per block on read, exactly as before this ruling. The DHT tier does still have its own
check, but in the opposite direction — see "Reverse-direction refusal" below. A block whose
header names a key id absent from the configured keyring fails with `UnknownKeyId` rather than a
truncated read.

**Reverse-direction refusal.** The legacy-plaintext checks above guard *enabling* encryption over
existing plaintext; boot also refuses the opposite move — *disabling* encryption (or omitting
`[storage.encryption]` entirely) over a tier that already holds ciphertext. Both local-disk and DHT
tiers write a `.encryption-enabled` marker the first time encryption is enabled over them (a file at
the disk directory's root; a key under the DHT tier's `<name>-blocks` namespace). If that marker is
present and no keyring resolves for the instance, boot fails with `EncryptedTierRequiresKeyring`
naming the instance and the key id blocks were last written under, instead of silently handing back
framed `AEC1...` bytes as if they were plaintext content on every read. Recovery: restore
`[storage.encryption]` with that key id still resolvable in `[storage.encryption.keys]`, or migrate
to a fresh, unmarked directory or DHT namespace (`#831`). This marker/refusal pair covers the
per-instance disk/DHT path above and, since `#849`, both sides of the built-in `streams` segment
tiers: the `stream-segments` DHT namespace is stamped at `stream-segments/.encryption-enabled` on
the same post-formation admission as every `<name>-blocks` namespace, and refuses the same way.
**A refused boot leaves no disk marker behind (`#852`).** Storage construction is two-phase: the
guards of every configured instance *and* of the built-in `streams` segment tiers all run first, and
only when all of them pass are the `.encryption-enabled` disk markers written. So a boot refused by
any one of them — legacy plaintext under an `encrypted = true` instance, `streams_encrypted` turned
on over a segments directory that already holds plaintext, or either of the reverse-direction
refusals — stamps no directory at all, and backing the others out to `encrypted = false` afterwards
starts, because there is no marker for their reverse guard to trip on. Two windows stay outside this
guarantee, and both open only AFTER the boot has been admitted: an I/O error on a marker file, or a
crash part-way through writing the set, leaves the markers already written (re-running the same
config completes them; a back-out to `encrypted = false` does not), and the DHT marker is checked
post-formation (see next paragraph), so its refusal follows disk markers an admitted construction
already wrote.
**Timing differs by tier (`#858`/`#874`):** the local-disk marker is checked synchronously during
storage construction, before the node object exists — a disk read needs no cluster. The DHT marker
cannot be: its `DHTClient` can only route once cluster formation resolves, so that check runs from
`start()`, after formation resolves. It does **not** run before the node's HTTP surfaces come up —
`managementServer`/`appHttpServer` start earlier in the same `start()` chain and can already accept
traffic, including the Maven protocol handler's writes, while this check is in flight. What actually
blocks during that window is every DHT-tier operation on the pending namespace — `get`, `put`,
`delete` and `exists` all gate on the same internal check (not observable as a separate config knob),
so a write cannot land ahead of the check and persist plaintext into a namespace the check later finds
already encrypted.
**Only a definite refusal is fatal (`#1052`).** If the DHT check reads a marker and no keyring is
configured, it fails with `EncryptedTierRequiresKeyring`. That stops the node the same way any other
`start()` failure does: exit code `1`, the generic "fatal startup failure" path (`Main#exitWithError`).
See [`node-operations.md`](node-operations.md#exit-codes) for the full table. If the check cannot
complete (a marker get/put timing out after 30 s, or no DHT quorum while the ring is still converging),
that is not an answer. The node stays in the cluster and retries with exponential backoff: 1 s,
doubling to a 30 s cap, jittered, with no give-up bound. Each attempt that fails logs one WARN line
naming the instance and the attempt number (`DHT encryption-marker check attempt 3 for instance
'content' did not complete: ...`). [mechanism: only `EncryptedTierRequiresKeyring` is a `Cause.Terminal`, and the retry loop stops only on
a terminal cause.] Until the check completes:
- every operation on that instance's DHT tier stays gated and is refused with `TierNotAdmitted` after
  30 s;
- the node stays in lifecycle state `JOINING` (never `ACTIVE`), so `/health/ready` answers 503;
- the `dht-admission` readiness component names the instances still pending.

[mechanism: the tier gate and the self-ready signal wait on the same per-instance gate, which only the
check's final outcome resolves.]

The node does not exit on a timeout. [design intent — unverified on a multi-node cluster under churn:
that such a node then verifies and becomes ready once the ring converges.]
**Recovery:** a pending check clears on its own once the DHT answers. If `dht-admission` stays DOWN,
the DHT cannot reach quorum for that namespace. Check cluster membership (`/api/v1/nodes`) and the
node's WARN attempt lines. A refusal clears only as described above: restore the keyring, or migrate.
A stop (SIGTERM) while the check is still retrying is logged at INFO as a stop, not as a start failure,
and the shutdown hook owns the exit.

**Nonce bound.** Each block's 12-byte (96-bit) nonce is drawn from `SecureRandom`, not a counter.
NIST SP 800-38D caps random-96-bit-nonce AES-GCM at 2³² encryptions under one key before nonce
collision risk becomes significant; a single collision under the same key is a full break, not a
graceful degradation. Rotate `active_key_id` well before any one instance approaches that many
encrypted blocks — see `security-subsystem-spec.md` §5.4/§5.7 for the full analysis and the
misuse-resistant-AEAD follow-up this motivates.

**Key rotation.** Add a new `keys.<id>` entry, flip `active_key_id`; every prior key remains
resolvable for existing blocks. Re-encrypting already-written blocks under the new active key
(including on tier demote/promote) is not part of this fix.

**Migration limitation.** There is no path from an existing plaintext tier to an encrypted one in
rc4 — enabling `encrypted = true` (or `streams_encrypted = true`) is new-instance/fresh-data only.

## Shared Storage Tier Configuration

### `[storage.<instance>.shared]` — #249

Chooses the cluster-shared **last tier** of a storage instance's stack: memory → local disk → shared tier.
Nothing above the storage tier (content store, artifacts, uploads, slices) sees which kind is configured.

```toml
[storage.content.shared]
kind = "s3"                                         # "dht" (default) | "s3"
endpoint = "https://s3.eu-central-1.amazonaws.com"
region = "eu-central-1"
bucket = "aether-content"
prefix = "cluster-a/"
path_style = false                                  # true for MinIO and most S3-compatibles
credentials = "env"                                 # "env" | "file:<path>" | "instance"
max_inflight = 8
request_timeout = "30s"
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `kind` | `"dht"` | `"dht"`: the in-memory DHT tier under `<instance>-blocks` — today's behaviour, and what a config without the section gets. `"s3"`: an S3-compatible bucket. Any other value (including `"dht-durable"`, reserved for the durable DHT tier) is refused at config load |
| `endpoint` | required for `s3` | Base URL of the S3 API, scheme included |
| `region` | required for `s3` | Signing region |
| `bucket` | required for `s3` | The bucket must already exist; Aether never creates buckets |
| `prefix` | `""` | Every key of the instance lives under it; a non-empty prefix gets a trailing `/` if it has none. Give each cluster and instance its own prefix |
| `path_style` | `false` | `true`: `<endpoint>/<bucket>/<key>`. `false`: `<bucket>.<endpoint host>/<key>` (virtual-hosted) |
| `credentials` | required for `s3` | Where the signing keys come from (below). **Inline secrets are rejected** |
| `max_inflight` | `8` | Most concurrent requests this node sends to the bucket for the instance; others wait without holding a thread |
| `request_timeout` | `"30s"` | Bound on each request; an expired request fails as unavailable (retriable) |

A section is validated at load: missing required keys, an unknown key, a non-positive `max_inflight`,
a malformed `request_timeout` or a non-boolean `path_style` refuse the config, naming the instance and
the key. A sub-table or a dotted key under the section (`[storage.<instance>.shared.<x>]`,
`aws.secret_access_key = ...`) is refused too. `[storage.streams.shared]` accepts only `kind = "dht"`: stream
storage is being redesigned separately, and until then the streams instance keeps the DHT as its shared tier.
`[storage.encryption.shared]` is refused: `encryption` names the keyring section, not an instance. Every other
`[storage.<instance>.<sub>]` section, and every `[storage.encryption.<sub>]` other than `[storage.encryption.keys]`,
is **refused at load**, naming each offending section, so a typo such as `[storage.content.shard]` or
`[storage.encryption.kyes]` stops the boot instead of silently dropping the tier or the keys. A dotted key inside an
instance section (`[storage.content]` with `foo.bar = 1`) is such a section too (`[storage.content.foo]`). An
explicit blank `disk_path`/`snapshot_path` is refused (omit the key to use the default).

**Without `[storage.<instance>]`.** A `[storage.content.shared]` section alone is enough: the instance is
placed as if its own section were absent (for `artifacts` and `content`, the synthesized defaults described
above, including keyring-tracking encryption). A section for any other name creates that instance in its own
directories beside `artifacts` (`<root>/<name>/blocks` and `<root>/<name>/snapshots` by default), as an empty
`[storage.<instance>]` section does.
The paths are derived once, when the node starts,
and everything that hangs off them reads the derived ones: with `[storage.artifacts.shared]` alone, the stream
segments and the stream WAL stay under the default root, exactly as with no storage section at all.

**Credentials.** Secrets never live in the TOML. A key that can only hold a secret (`secret_access_key`,
`access_key_id`, `session_token`, `password`, ...) is refused with `InlineSecretRejected`, in any case and
also as a dotted key or inside a sub-table, and a
`credentials` value that is not one of the three forms is refused without echoing it.
- `"env"` — `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, optional `AWS_SESSION_TOKEN`, read once at boot.
- `"file:<path>"` — the `[default]` profile of a file in the AWS shared-credentials format
  (`aws_access_key_id`, `aws_secret_access_key`, optional `aws_session_token`), read once at boot.
  Rotating the file requires a restart.
- `"instance"` — the EC2 instance profile through the instance metadata service, IMDSv2 only. The
  temporary credentials are fetched on first use and refreshed five minutes before they expire.

Aether signs requests itself (SigV4); there is no AWS SDK in the node, so these three are the whole chain.
Credentials are never logged and never appear in a refusal message.

**Boot probe.** At storage construction, before the node joins anything, each S3 tier is probed: PUT,
GET and DELETE of `<prefix>.aether-probe/<nodeId>`, with the GET required to return the bytes written.
A probe that fails — endpoint unreachable, credentials refused, bucket missing, or the read-back
differing — **fails the boot**, naming the instance, endpoint and bucket. A configured durable tier that
is absent is a misconfiguration, never a silent fallback. The node's identity needs `s3:PutObject`,
`s3:GetObject` and `s3:DeleteObject` on the prefix. Redirects are not followed: an endpoint answering
for another region fails the probe.

**What an acknowledged write is worth.** Blocks are written whole, one S3 object per content-addressed
block (no multipart). A write is acknowledged only after the bucket answered the PUT with 200, and the
S3 tier counts as durable (blocks may be demoted to it from the local disk; it could back a sealed
stream range, but the streams instance cannot be configured with S3 in this release). **What that 200 is worth is the provider's durability, not Aether's**: AWS S3 documents
redundant storage across availability zones for its standard class; an S3-compatible endpoint provides
whatever its deployment provides, and a single-disk MinIO provides a single disk. A provider outage
makes the data **unavailable** — reads and writes fail with a retriable `Unavailable` cause, which the
upload layer is specified to answer as 503 — and is never reported as an absent block: only a 404 for
the object itself reads as "not there". A request the provider refuses for what it is (403, a missing
bucket, another 4xx) fails as `Rejected`, terminal.

**Consistency the endpoint must provide.** Read-after-write for new keys: a block is immutable and
content-addressed, so a GET after the PUT returned must see it. AWS S3 provides this since December 2020;
for an S3-compatible product it must be checked per product and version. The boot probe exercises it
once; it is not a proof. List-after-write: the encryption guard below lists the prefix to find existing
blocks, so an endpoint that lists stale results can miss a block written just before; the cluster catalog's
anchors (a later phase) will need it too.

**Encryption.** With `encrypted = true` (or the keyring-tracking default of `artifacts`/`content`) the S3
tier is wrapped in `EncryptingStorageTier` exactly like the DHT tier: the bucket holds ciphertext. The
guards follow the local disk, during storage construction (an object store needs no cluster):
- a boot with no keyring over a prefix carrying the marker `<prefix>.encryption-enabled` is refused with
  `EncryptedTierRequiresKeyring`;
- a boot with a keyring over an unmarked prefix that already holds objects is refused with
  `EnablingOverExistingPlaintext`: those blocks would be stranded as legacy plaintext. There is no migration;
  use a fresh prefix;
- over an empty prefix, the marker (the active key id) is written only after every storage instance of the
  boot has passed its guards (#852): a boot refused by any instance stamps no bucket, so backing the keyring
  out afterwards boots. Other nodes' boot-probe objects (`<prefix>.aether-probe/...`) do not count as existing
  objects.
- **Several nodes enabling encryption over one empty prefix at the same moment can refuse one of them.** A node
  that read "no marker" can then list the first ciphertext block another node has just written and refuse with
  `EnablingOverExistingPlaintext`. Nothing is lost and nothing is stamped by the refused node; restarting it
  boots cleanly, because the marker is then present. To avoid the refusal, enable encryption on one node first.
- **A boot refused after formation keeps the bucket's marker.** When the post-formation DHT marker check refuses a
  boot (#2268), the node rolls back the disk markers that boot stamped, but never `<prefix>.encryption-enabled`:
  the bucket is shared by the cluster, and other nodes may already rely on it. Backing the keyring out after such a
  refusal therefore still meets the marker on the bucket (`EncryptedTierRequiresKeyring`); use a fresh prefix for
  an unencrypted boot.

**Garbage collection.** Node-local GC never deletes from the S3 tier, as from the DHT: one node's refcount
cannot say another node no longer references a block. Explicit deletes (content deletion, stream retention)
do reach the bucket, one object per block. Collection of unreferenced blocks in the bucket is cluster-wide
GC, a later phase: until then the bucket only grows by what explicit deletes leave behind.

**Block size.** The block size is the `ContentStore` resource's `chunkSizeBytes` (`ContentStoreConfig`,
default 4 MiB), unchanged by this section. It must fit the instance's `max_block_bytes` (default 16 MiB) once
framed; a larger `chunkSizeBytes` is refused when the store is provisioned. Each block is one PUT and one GET, so per-request cost and
latency dominate on S3 (a 5 GiB object at 4 MiB is 1280 PUTs); S3-backed content stores may want larger
blocks. No default changes here.

**Streaming content store settings.** The streaming store (`ContentStore`, local mode) reads three more keys from the
same `ContentStoreConfig` section; each has a default, so an existing section binds unchanged.

| Key | Default | Meaning |
|-----|---------|---------|
| `namespace` | `default` | The scope the store is bound to when it is provisioned. Objects, names and listings are confined to it. 1 to 63 characters of `a-z`, `0-9`, `.`, `_`, `-`, starting with a letter or digit. An invalid value is refused when the store is provisioned |
| `write_window` | 4 | Blocks a writer may have being stored while it fills the next one. At least 1. A writer holds at most `write_window + 1` chunks of the object on the heap |
| `read_ahead` | 2 | Blocks a read stream fetches ahead of the one being served. 0 or more. A stream holds at most `read_ahead + 1` blocks on the heap |

**Renaming an object (local mode).** `bind` of a second name to a named object is never unnamed: it binds the new name first, then
retires the old one, and returns after both. While it runs, and after a crash between the two steps, both names resolve to the
same object; no block is reclaimed in either case, and deleting the object retires both names. A binding whose object was deleted reads as not found and is replaced by the next bind of that name. Objects hold one name outside that window. A later cluster catalog makes the
rename atomic.

## Environment Variables

For container deployment, configuration via environment variables:

| Variable | Default | Description |
|----------|---------|-------------|
| `NODE_ID` | auto-generated | Unique node identifier |
| `AETHER_CLUSTER_NAME` | **required** | Cluster name (1-63 lowercase DNS label, no trailing hyphen; single-character names allowed). Resolved from this env var or bootstrap-seeded KV — the node refuses to start if it resolves to missing/empty |
| `AETHER_INSECURE_DEV_MODE` | false | Enables dev-only inject/test endpoints. Incompatible with operator-provided TLS certificates — see TLS Configuration |
| `CLUSTER_PORT` | 8090 | Cluster communication port |
| `MANAGEMENT_PORT` | 8080 | HTTP API port |
| `CLUSTER_PEERS` | required | Cluster peer list |
| `JAVA_OPTS` | `-Xmx256m` | JVM options |
| `AETHER_CLUSTER_SECRET` | none | Shared secret used to generate TLS certificates; fallback for `tls.cluster_secret` |
| `AETHER_CLUSTER_SECRET_FILE` | none | Path to a file holding the cluster secret (trailing line breaks are stripped, nothing else); preferred over the plain variable because the value is then in no argv or container environment. Both set to different values is refused at start (exit 65) |
| `AETHER_API_KEYS_FILE` | none | Path to a file holding the accepted API keys in the `AETHER_API_KEYS` format; wins over the plain variable, and both set to different values is refused at start (exit 65). Every `${env:NAME}` reference in a node config also accepts `NAME_FILE`; only the three file-delivered variables (`AETHER_CLUSTER_SECRET`, `AETHER_API_KEY`, `AETHER_API_KEYS`) are refused at start when their file is broken, any other name leaves the reference as its literal placeholder |

There are no `TLS_ENABLED` / `TLS_CERT_PATH` / `TLS_KEY_PATH` environment variables — TLS is
configured via the `[cluster] tls` and `[tls]` TOML keys (`auto_generate`, `cert_path`,
`key_path`, `ca_path`), not environment variables.

A node **fails to start** (loud error, non-zero exit) when its cluster name is missing or empty.
The name is resolved from `AETHER_CLUSTER_NAME` or, for bootstrapped clusters, from the
bootstrap-seeded KV entry. The same validation regex (1-63 lowercase DNS label, no trailing
hyphen, single-character names permitted) is applied uniformly across the node, CLI, and config.

## CLI Arguments

Command-line arguments for `aether-node`:

```bash
java -jar aether-node.jar \
    --node-id=node-1 \
    --port=8090 \
    --management-port=8080 \
    --peers=node-1:host1:8090,node-2:host2:8090
```

| Argument | Description |
|----------|-------------|
| `--node-id` | Node identifier |
| `--port` | Cluster communication port |
| `--management-port` | HTTP API port (0 to disable) |
| `--peers` | Cluster peer list |

## Configuration Examples

### Minimal 3-Node Cluster

```java
var peers = List.of(
    NodeInfo.nodeInfo(NodeId.nodeId("node-1").unwrap(), NodeAddress.nodeAddress("192.168.1.1", 8090).unwrap()),
    NodeInfo.nodeInfo(NodeId.nodeId("node-2").unwrap(), NodeAddress.nodeAddress("192.168.1.2", 8090).unwrap()),
    NodeInfo.nodeInfo(NodeId.nodeId("node-3").unwrap(), NodeAddress.nodeAddress("192.168.1.3", 8090).unwrap())
);

var config = AetherNodeConfig.builder()
    .self(NodeId.nodeId("node-1").unwrap())
    .coreNodes(peers)
    .managementPort(8090)
    .sliceConfig(SliceConfig.sliceConfig())
    .artifactRepo(DHTConfig.DEFAULT)
    .coreMax(peers.size())
    .appHttp(AppHttpConfig.appHttpConfig())
    .tls(Option.none())
    .quicTls(quicTlsConfig)
    .build();
```

### Production with TLS

```java
var config = AetherNodeConfig.builder()
    .self(NodeId.nodeId("node-1").unwrap())
    .coreNodes(peers)
    .managementPort(8080)
    .sliceConfig(SliceConfig.sliceConfig())
    .artifactRepo(DHTConfig.DEFAULT)
    .coreMax(peers.size())
    .appHttp(AppHttpConfig.appHttpConfig())
    .tls(TlsConfig.server(certPath, keyPath))
    .quicTls(TlsConfig.server(certPath, keyPath))
    .build();
```

### Custom Timeouts

Since `SliceActionConfig` is a record, custom timeouts can be constructed directly (7 fields —
there is no serializer-provider field; slice serialization is not part of this config):

```java
var sliceActionConfig = new SliceActionConfig(
    timeSpan(5).minutes(),    // loadingTimeout - longer for large slices
    timeSpan(2).minutes(),    // activatingTimeout
    timeSpan(1).minutes(),    // deactivatingTimeout
    timeSpan(5).minutes(),    // unloadingTimeout
    timeSpan(2).minutes(),    // startStopTimeout
    List.of(localRepository()),
    Option.empty()            // frameworkJarsPath
);

var config = AetherNodeConfig.builder()
    .self(nodeId)
    .coreNodes(peers)
    .managementPort(port)
    .sliceConfig(sliceConfig)
    .artifactRepo(artifactRepoConfig)
    .coreMax(peers.size())
    .appHttp(AppHttpConfig.appHttpConfig())
    .tls(Option.none())
    .quicTls(quicTlsConfig)
    // Stages are positional: each one before sliceAction takes its default here.
    .certificateProvider(Option.none())
    .configProvider(Option.none())
    .environment(Option.none())
    .managementHttpProtocol(HttpProtocol.H1)
    .storageConfig(Map.of())
    .backupConfig(Option.none())
    .membership(Option.none())
    .streaming(StreamingConfig.streamingConfig())
    .protocol(ProtocolConfig.defaultConfig())
    .sliceAction(sliceActionConfig)
    .build();
```

### Test Configuration

There is no canned test or forge factory — tests build the minimal required chain directly and
skip everything from `certificateProvider` onward to take its defaults:

```java
var config = AetherNodeConfig.builder()
    .self(nodeId)
    .coreNodes(peers)
    .managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
    .sliceConfig(SliceConfig.sliceConfig())
    .artifactRepo(DHTConfig.DEFAULT)
    .coreMax(peers.size())
    .appHttp(AppHttpConfig.appHttpConfig())
    .tls(Option.none())
    .quicTls(quicTlsConfig)
    .build();
```

## Worker Configuration

```toml
[worker]
group_name = "default"
zone = "local"
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `group_name` | string | `"default"` | Logical group name for this worker pool. Parsed, validated and stored; **read by no production code** (see below) |
| `zone` | string | `"local"` | Zone identifier. Parsed, validated and stored; **read by no production code** (see below). Not the same knob as the `AETHER_ZONE` environment variable |
| `max_group_size` | — | — | **Removed (#673, 2026-09-14).** A present key is refused at parse: worker group splitting was never wired, and communities are one per source (`<source>-w-0`). Remove the key from any `[worker]` table. |

Both keys above are parsed, validated and stored on `AetherNodeConfig`, and then **read by nothing**. Their only
consumer was the worker group-splitting chain (`GroupMembershipTracker` → `GroupAssignment.computeGroups`), which
was never wired into a live node and was deleted in #673 (2026-09-14). Setting either key changes no behaviour
today; they are documented because they are still accepted, not because they do anything.

NodeId-derived zones were removed in #592 (2026-08-17). A node's zone is no longer inferred by splitting its id at
the last dash — that was identifier parsing rather than zone awareness, and it put `node-1` in a zone called
`node`.

`[worker] zone` is a **different knob** from the `AETHER_ZONE` environment variable, and only the latter is live:
`Main` maps `AETHER_ZONE` onto `NodeInfo.LABEL_ZONE`, the Hello handshake propagates that label into
`SwimMember.labels`, and it is read for observability by `ClusterTopologyManagerRecord` and `ClusterTopologyRoutes`.
`AETHER_ZONE` is stamped on every provisioned node from that node's own source, never inherited from the
provisioning host (#1650): the source's single configured zone, or nothing for a source listing several `zones`,
because the landing zone is chosen by capacity rotation after the node's user-data is rendered. `AETHER_SOURCE` is
stamped the same way, always.

Workers self-organize into groups deterministically from SWIM membership. Same membership produces identical groups on every worker — no coordination needed. Each group elects its own governor (lowest ALIVE NodeId).

## Backup Configuration

```toml
[backup]
enabled = true
path = "/data/backups"
remote = ""
restore = "auto"
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `enabled` | boolean | `false` | Enable the change-triggered KV backup and the cold-start restore (also needs a non-blank `path`) |
| `path` | string | env-dependent | Directory holding the backup repository `<path>/kv-backup` |
| `remote` | string | `""` | Git remote the leader pushes to, fast-forward only |
| `restore` | string | `"auto"` | `auto`: a cold start restores the backup head (fresh when empty); `fresh`: ignore the backup |

Each key is overridable by an environment variable: `AETHER_BACKUP_ENABLED`, `AETHER_BACKUP_PATH`, `AETHER_BACKUP_REMOTE`, `AETHER_BACKUP_RESTORE`.
**The environment wins over the TOML, per key**; a blank variable counts as unset. When the environment value differs from the TOML value the node logs a
WARN at startup naming the key and both sources (never the values). A Docker node has no TOML of its own and is configured by these variables alone.

The leader writes the backup on change (#1532); a whole-cluster restart is a regular start of fresh cores
followed by the restore (#1533). See the [backup-recovery runbook](../operators/runbooks/backup-recovery.md).

**Backup RPO.** The flush timing is not configurable: there is no `[backup]` key for it. A change to a backed-up key is flushed after
500 ms of quiet and at most 5 s after the first unflushed change (`KvBackupService.Timing.DEFAULT`, `quietMillis` and `maxDelayMillis`;
an incarnation change is flushed at once), so the backup trails the cluster state by up to about 5.5 s (`maxDelay` + `quiet`),
plus push time while the remote is reachable, or until it is again.
- **Graceful stop:** the leader flushes the backup before its stop completes, bounded by 10 s (`AetherNode.BACKUP_STOP_FLUSH_BOUND`).
  A flush that fails or overruns is logged and raised as the operator warning `backup-stop-flush-failed`; the stop proceeds, and the
  backup then trails by the window above. The 10 s bound equals Docker's default stop grace period and Ember's per-node stop timeout, so a hanging remote can use the whole grace: with Docker or Kubernetes, set the stop grace period above about 15 s (the `Main` shutdown hook allows 30 s). A follower flushes nothing.
- **Crash or kill:** nothing is flushed. Changes inside the window are lost to a whole-cluster restart, and a stream created inside it
  is not in the restored state: the restart mints a new incarnation for it and the acknowledged records in the old
  `<stream>@<incarnation>` WAL directory stay on disk unread. The node raises `storage-stream-wal-orphaned` for that directory and
  never deletes it, sealed segments included. Closing this case is storage-identity adoption, #1569.

Default `path` by environment:
- LOCAL: `./aether-backups`
- DOCKER: `/data/backups`
- KUBERNETES: `/var/aether/backups`

## DHT Replication Configuration

```toml
[dht.replication]
cooldown_delay_ms = 10000
cooldown_rate = 10000
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `cooldown_delay_ms` | long | `10000` | Delay (ms) after node startup before replication warmup starts |
| `cooldown_rate` | int | `10000` | Max entries/sec during replication warmup |

The DHT's replication factor and confirmation factor are not node-local settings: they come from the
cluster's committed `[replication]` section, and the cache namespace's from `[cache]` (#1777; see
[bootstrap-config.md](bootstrap-config.md#replication--default-replication-factors-cluster-wide-1564)). The former
`target_rf` key is removed, and a node config that still sets it is refused at load.

## Streaming Configuration

```toml
[streaming]
publish_forward_timeout = "5s"
read_forward_timeout = "2s"
max_read_response_bytes = "28MB"
reshuffle_concurrency = 2
caught_up_max_lag_offsets = 1024
# segment_disk_max_bytes = "200GB"   # unset: derived from the disk at boot
# dht_segment_max_bytes = "128MB"    # unset: max(64 MiB, max heap / 4)
isr_lag_max = "30s"
promotion_escape_after = "120s"
seal_run_bytes = "1MB"
seal_run_events = 4096
seal_run_age = "5s"
seal_merge_bytes = "4MB"
```

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `publish_forward_timeout` | timespan | `5s` | Wait for a publish forwarded to the partition owner |
| `read_forward_timeout` | timespan | `2s` | Wait for a read forwarded to the partition owner |
| `max_read_response_bytes` | data size | `28MB` | Cap on a single forwarded-read response |
| `reshuffle_concurrency` | int | `2` | Partitions one node may hold in materialize+backfill at once. Must be `>= 1` |
| `caught_up_max_lag_offsets` | long | `1024` | How far a `CAUGHT_UP` replica may trail the freshest peer watermark and still serve reads or count toward the ring-release catch-up gate. Must be `>= 0` |
| `segment_disk_max_bytes` | data size | derived | Cap on this node's local-disk tier for sealed stream segments. Unset (or `0`): 40% of the usable space on the filesystem holding the stream data directory at boot, clamped to at least 1 GiB and at most usable space minus 2 GiB (the WAL usually shares the disk); the chosen value is logged at INFO. A value above the usable space is kept, with a WARN. Must be `>= 0` |
| `dht_segment_max_bytes` | data size | derived | Cap on the live bytes of sealed stream segments this node's DHT engine holds (the `stream-segments` prefix). A replica refuses a client put that would take it above the cap, so seals fail with `TierFull` instead of the heap filling; repair writes (catch-up, migration, hand-off, re-homing) are accepted and counted, so a node can pass it after a re-replication, and concurrent puts overshoot by at most the bytes in flight. Tombstones are never capped; they count toward the `stream-segment-dht-near-full` warning (85%, cleared below 80%). Unset (or `0`): the larger of 64 MiB and a quarter of the maximum heap (128 MiB on the shipped `-Xmx512m` launchers). Per node, not cluster-wide. The refusal is always on. Must be `>= 0` |
| `isr_lag_max` | duration | `30s` | How long a member of a partition's in-sync replica set (ISR) may stay behind the owner's head before the owner asks for it to leave the ISR (#1730; Kafka's `replica.lag.time.max.ms`). **Liveness only:** the ISR changes only by a committed write and a `confirmation_factor` ≥ 2 acknowledgement waits for every committed ISR member, so no value makes an acknowledgement unsafe. Smaller drops a slow replica sooner (shorter ack stalls, fewer in-sync copies); larger keeps it longer (an acknowledgement can wait up to this long for a lagging member) |
| `seal_run_bytes` | data size | `1MB` | The segment sealer seals a partition's run of evicted events once their bytes reach this (#2201 C-02). With the next two, whichever comes first. A guess, as are the other three: settled by the seal rate and WAL size trend of a baseline run |
| `seal_run_events` | int | `4096` | ... or once the run holds this many events (bounds the decode of a block of tiny events) |
| `seal_run_age` | duration | `5s` | ... or once the run's oldest event has waited this long, at most `1d`. A read of a pending offset seals its run at once. Without a WAL this is also how much longer an acknowledged event can be lost to a crash; a graceful stop first seals every pending run, waiting up to 5 s, and raises `stream-seal-stop-incomplete` for what it could not seal |
| `seal_merge_bytes` | data size | `4MB` | Cap on one sealed block when a backlog has queued behind a slow seal; a single larger hand-over still seals alone. At most `256MB` (a block is one array) |
| `promotion_escape_after` | duration | `120s` | How long a stream partition's promotion (the owner activation gate, the replica promotion contest) waits for members that do not answer (each unreachable member continuously for this long, on its own clock) before a candidate named in the partition's COMMITTED in-sync set goes ahead without them (#2080). A candidate outside the set, or a record with no committed set, never goes ahead. Refused if below the alarm bounds it follows: two `swim.suspect_timeout` windows (20 s at the default) and the contest's source wait (20 s at the default). **The default is a guess**: a lower value risks escaping past a node that only boots slowly after a full-cluster cold restart, which can leave acknowledged records behind (retained in a recovery segment and reported); the measured boot spread of a cold restart would settle it. Each escape raises the CRITICAL operator event `stream-promotion-past-unreachable-peers` naming this bound and the time elapsed |

`reshuffle_concurrency` paces backfill work so a large reshuffle cannot flood a node. Raise it when
partitions queue behind slow backfills; lower it when backfill traffic competes with serving. A partition
that cannot get a slot is queued, not rejected, and the caller sees a retryable paced error naming this
key. A slot held past a bounded tenure while others are queued is preempted — the backfill continues but
stops counting against this limit — so a stalled backfill cannot starve the queue indefinitely.

`caught_up_max_lag_offsets` exists because the `CAUGHT_UP` replication state never downgrades: nothing
moves a replica out of it when it stops acking, so under a partition the state does not go stale — it
FREEZES at its last good value and reads as healthy indefinitely. Without a freshness bound a replica
that readers can still reach but which stopped acking to the owner keeps serving increasingly stale data
with no error, and the ring-release gate over-counts it, so an owner can release its partition believing
enough replicas are caught up.

Lag is measured in OFFSETS relative to the freshest peer watermark, deliberately not as a time-to-live: a
replica's watermark advances only on acks and backfill milestones, and nothing refreshes it on a quiet
partition, so a time-based rule would age out every replica of a write-idle stream and stop serving reads
from the healthiest streams in the cluster.

Raise it if healthy replicas are being skipped as read sources under heavy write bursts — normal
asynchronous replication means a healthy replica is transiently behind on every write. Lower it to demand
tighter read freshness at the cost of forwarding more reads to the owner. `0` demands exact watermark
parity and is legitimate but very strict. Two limits are inherent to a relative measure: a partition with
a single registered peer has nothing to compare against and is always considered fresh, and if every
replica freezes together their lags stay equal and none is flagged.

**The default is not a measured value.** It has not been derived from an observed steady-state lag
distribution; it is set above a typical in-flight batch depth and exposed as a knob so it can be relieved
without a rebuild.

## Cloud Configuration

Cloud provider integration is configured via the `[cloud]` TOML section. See [Cloud Integration](cloud-integration.md) for the full operator guide.

> **Provisioning a new cluster?** `[cloud.*]` below is *generated output* — `aether cluster bootstrap`
> composes it from a separate `[source.<name>]` schema you write once. See the
> [Bootstrap Config Reference](bootstrap-config.md) for that schema and a minimal working example; you
> should not hand-write `[cloud.*]` in a bootstrap-config file.

```toml
[cloud]
provider = "hetzner"                          # Required: hetzner | aws | gcp | azure

[cloud.credentials]                            # Provider-specific authentication
api_token = "${env:HCLOUD_TOKEN}"         # Supports ${env:VAR} interpolation

[cloud.compute]                                # Instance provisioning parameters
# EXAMPLE instance type — providers retire types; check your provider's current catalogue.
server_type = "cx22"
image = "ubuntu-24.04"
# EXAMPLE region — the region decides where your data resides; choose it deliberately.
region = "fsn1"
user_data = "#!/bin/bash\n..."
ssh_key_ids = "12345,67890"                    # Comma-separated lists
network_ids = "11111"
firewall_ids = "22222"

[cloud.load_balancer]                          # Optional: LB target registration
load_balancer_id = "99999"
destination_port = "8090"

[cloud.discovery]                              # Optional: tag/label-based peer discovery
cluster_name = "production"
poll_interval_ms = "15000"

[cloud.secrets]                                # Optional: secrets backend config
```

| Section | Purpose | Required |
|---------|---------|----------|
| `[cloud]` | Provider selection | Yes (if using cloud) |
| `[cloud.credentials]` | Authentication keys | Yes |
| `[cloud.compute]` | VM/instance parameters | Yes |
| `[cloud.load_balancer]` | Load balancer integration | No |
| `[cloud.discovery]` | Peer auto-discovery | No |
| `[cloud.secrets]` | Secrets resolution backend | No |

**Environment variable interpolation:** Any value matching `${env:VAR_NAME}` in credentials or compute sections is resolved from the process environment at config load time.

**Supported providers:** Hetzner, AWS, GCP, Azure. Each provider module registers via ServiceLoader. Provider-specific credential and compute keys are documented in the [Cloud Integration](cloud-integration.md) reference.

## Database Configuration & Schema Migration

### Multi-Datasource Convention

The `[database]` section configures the default datasource, used by `@Sql` and by migration scripts in the `schema/` root directory. Named datasources use `[database.<name>]` sections, corresponding to `schema/<name>/` subdirectories and `@ResourceQualifier(config="database.<name>")` annotations.

**`@Sql` takes no argument** — it is `@ResourceQualifier(type = SqlConnector.class, config = "database")` fixed at the annotation definition
[verified: `aether/resource/api/.../db/Sql.java`], so it always resolves the flat `[database]` section and only that one. `@Sql("name")` is not valid Java for this annotation and will not compile. To reach a *named* datasource, use `@ResourceQualifier(config = "database.<name>")` directly, as in `schema/analytics/` below — `@Sql` itself cannot select one (#577).

```toml
[database]                        # default datasource (used by @Sql and schema/ root)
name = "default"
type = "POSTGRESQL"
host = "localhost"
port = 5432
database = "myapp"
username = "app"
password = "secret"

[database.analytics]              # named datasource (used by schema/analytics/ and @ResourceQualifier(config="database.analytics"))
name = "analytics"
type = "POSTGRESQL"
host = "analytics-host"
port = 5432
database = "analytics"
username = "reader"
password = "secret"
```

**Strict resolution:** Every schema directory must have a corresponding config section. A missing section causes an explicit failure — there is no fallback or derivation between sections. This prevents silent misconfiguration where migrations run against the wrong database.

**Single-datasource zero-config:** Slices using only `@Sql` place migration scripts directly in `schema/` (no subdirectory). The scripts map to `[database]` — the same section `@Sql` resolves from.

**Bootstrapping a cluster:** a bootstrap-config `[source.<name>] databases.<name> = "url"` entry composes into a *named* `[database.<name>]` section on the provisioned node, never the flat `[database]` — a common trap for slices that expect the default datasource. See [Bootstrap Config Reference](bootstrap-config.md#c-databasesx-vs-flat-database).

## Blueprint Resources (`resources.toml`)

Application-level configuration that travels with the blueprint artifact. Loaded into ConfigService at GLOBAL scope when the blueprint is deployed.

Example:
```toml
[datasources.orders_db]
database = "orders"
schema_version = 12
migrations = "schema/orders_db"
pool.max_connections = 30

[datasources.analytics_db]
database = "analytics"
pool.max_connections = 10
```

## Infrastructure Endpoints (`[endpoints.*]`)

Node-level infrastructure endpoints configured in `aether.toml`. Loaded at NODE scope, overriding matching GLOBAL-scope keys from `resources.toml`.

Example:
```toml
[endpoints.orders_db]
host = "db.prod.internal"
port = 5432
username = "app"
password = "${env:DB_ORDERS_PASSWORD}"

[endpoints.analytics_db]
host = "analytics.prod.internal"
port = 5432
username = "reader"
password = "${env:DB_ANALYTICS_PASSWORD}"
```

### Config Merge Hierarchy

When a slice requests configuration (e.g., `@ResourceQualifier(config = "database.orders_db")`), the ConfigService resolves values in priority order:

1. **SLICE** scope — per-slice overrides (highest priority)
2. **NODE** scope — from `aether.toml` `[endpoints.*]` sections
3. **GLOBAL** scope — from blueprint's `resources.toml` `[datasources.*]` sections

This allows blueprints to define "what" (database name, pool size, schema) while nodes define "where" (host, port, credentials).

## Configuration Best Practices

### Production

1. **Use TLS** for all cluster communication
2. **Set appropriate timeouts** based on slice complexity
3. **Configure replication** based on cluster size
4. **Use separate ports** for management and cluster traffic

### Development

1. **Use test configuration** for faster iteration
2. **Disable TLS** for simplicity
3. **Use local artifact repository** for faster loading

### Testing

1. **Use `testConfig()`** for shorter timeouts
2. **Use full replication** for simplicity
3. **Disable management port** if not needed

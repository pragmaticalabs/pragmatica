# Artifact Repository

## Purpose

The Aether artifact repository is an **internal cluster artifact cache** for deployed slices. It is **not** a
general-purpose Maven proxy or artifact manager.

**What it does:**
- Stores slices that have been deployed to the cluster
- Distributes artifact data across nodes using DHT (Distributed Hash Table)
- Provides fast in-cluster artifact resolution for slice loading
- Enables air-gapped deployments without external dependencies

**What it is not:**
- Not a Maven Central mirror
- Not a general artifact cache
- Not a replacement for Nexus/Artifactory in your CI/CD pipeline

## Security Model

### Authentication

The artifact repository relies on **mTLS (mutual TLS)** for node authentication. All cluster communication is encrypted
and authenticated at the transport layer.

### Authorization

**Important limitation:** The repository has no explicit authorization layer.

The security model makes a deliberate simplifying assumption:

> **Cluster network access implies deployment permission.**

This means:
- Any node that can join the cluster can read/write artifacts
- Authorization is handled at the network isolation level
- There is no per-user or per-artifact access control

**Recommendations for production:**
- Use network segmentation to isolate cluster nodes
- Control who can access management endpoints
- Consider API gateway with authentication for external access
- Document which systems/users have cluster access

### Why No Explicit Authorization?

1. **Internal traffic** - The repository serves cluster-internal artifact distribution
2. **Simplified operations** - No identity management overhead
3. **Performance** - No authorization checks on hot paths
4. **Trust boundary** - Joining the cluster is the trust boundary

If you need fine-grained authorization (multi-tenant, external access), implement it at the
ManagementServer/API gateway level.

## Environment Strategies

Configure the repository source based on your deployment environment:

| Environment | Repository Config | Behavior |
|-------------|------------------|----------|
| Production | `["builtin"]` | Cluster artifacts only, no local repository access |
| Development | `["local"]` | Maven local repository (~/.m2/repository) only |
| Forge | `["local"]` | Same as development |
| Hybrid | `["local", "builtin"]` | Try local first, fallback to cluster |

### When to Use Each

**Production** (`["builtin"]`):
- Slices must be explicitly deployed to cluster
- Prevents accidental local-only dependencies
- Consistent across all nodes

**Development** (`["local"]`):
- Fast iteration with `mvn install`
- No deployment step needed
- Single-node or Forge testing

**Hybrid** (`["local", "builtin"]`):
- Development with some production slices
- Useful for integration testing
- Local artifacts take precedence

## Maven Workflow

### Development Workflow

During development, use the local repository strategy:

```bash
# Build and install slice to local Maven repo
mvn clean install

# Slice is now available at ~/.m2/repository
# Aether will load it automatically when configured with repositories = ["local"]
```

### Production Deployment

For production, push artifacts to the cluster repository. The built-in store takes **release versions only**
and writes each coordinate once (see below), so build the version you intend to keep:

```bash
# First, install to local Maven repo
mvn clean install

# Then push to cluster
aether artifacts push com.example:my-slice:1.0.0
```

Alternatively, deploy a JAR directly without Maven:

```bash
# Deploy JAR file directly to cluster
aether artifacts deploy target/my-slice-1.0.0.jar \
  -g com.example \
  -a my-slice \
  -v 1.0.0
```

### Maven Protocol Support

The repository exposes a Maven-compatible HTTP endpoint:

```bash
# Deploy via Maven (alternative to CLI)
mvn deploy -DaltDeploymentRepository=aether::default::http://localhost:8080/repository

# Or via curl
curl -X PUT http://localhost:8080/repository/com/example/my-slice/1.0.0/my-slice-1.0.0.jar \
  --data-binary @target/my-slice-1.0.0.jar
```

## Write-Once Coordinates, No SNAPSHOT, Archive Instead of Delete

The built-in store holds **immutable coordinates only** (#1778). Every file of a coordinate is written once,
its content is bound once in consensus, and an archived version is never resurrected by a read, a repair or a re-put.
The exceptions are listed under the known limits below.

| Request | Answer |
|---------|--------|
| `PUT` a coordinate that is not stored | `200`, `"status":"uploaded"` |
| `PUT` the same content again | `200`, `"status":"already-present"`; nothing is uploaded or rewritten, and an interrupted first deploy is completed (the file and version registrations are re-asserted) |
| `PUT` different content to a stored coordinate | `409`, naming exactly what differs (size and/or each digest, stored and offered); the stored content is kept. Publish the change under a new version |
| `PUT` a NEW version when the artifact already holds `artifact_max_versions` present versions | `409`, nothing uploaded; archive old versions or raise the cap |
| `PUT` a `-SNAPSHOT` version (any file, including Maven's timestamped names) | `400`; nothing is written. SNAPSHOTs stay available through the Local repository (development, Forge) |
| `PUT` to an archived version (identical content or not, any file) | `409`; an archived coordinate is never reused |
| `GET` an archived file (or its `.sha1`/`.md5` sidecar) | `410 Gone`. `404` still means "never written" |
| `GET maven-metadata.xml` | lists the versions that are stored and not archived |
| `DELETE /repository/{groupPath}/{artifactId}/{version}` (`aether artifacts archive`) | `200`, `"status":"archived"`; `409` when the version has been stored for less than the retention period; `404` when nothing was ever stored; requires OPERATOR or ADMIN |

The comparison is by content: size, MD5, SHA-1 and SHA-256 must all match.

**Archive, not delete.** Nothing is ever removed. Archiving writes an `archived` marker for the version and flags it
in the versions list; every key is kept, so "absent" for an artifact means "never written". An archived version
stops resolving (`410`; the built-in repository answers "not in store" so other repositories can still serve it) and
is delisted. Archiving is allowed only once the version has been stored for at least the **minimum retention
period**, default **7 days**, set with `artifact_archive_retention` in the `[slice]` section (`"7d"`, `"36h"`, ...).
Archiving twice is a no-op. The chunk bytes of an archived version are retained: nothing reclaims them yet.

**State order and what it guarantees.** An artifact is `never-written`, then `present`, then `archived`, and the
store only ever moves up that order. The metadata key is written once; the archive marker is a key of its own that is
written once and never rewritten or removed; and the **versions of each artifact live in the consensus KV plane**
(`artifact-versions/{groupId}:{artifactId}`), not in the DHT. A publish or an archive is one `Put` of the entry it
adds, and the Rabia applier MERGES it into the committed set (a union that takes the higher state per version). The
consensus log orders the writers, so two nodes publishing different versions of one artifact at the same instant
both land, and a stale add can never un-archive a version. The artifact bytes and the per-version metadata stay in
the DHT. The binding of a coordinate to its content is decided in consensus too: before anything is uploaded, an
uploader proposes `(coordinate file -> size, MD5, SHA-1, SHA-256)` under `artifact-content/...`, and the applier keeps the FIRST
digest committed. An uploader whose digest lost gets `409` naming both digests and uploads nothing, so only the winner
ever writes a file's metadata and a reader can never resolve a loser's bytes. A winner that died after binding and
before writing leaves a bound coordinate without metadata; an identical re-put completes it, a different one is refused. The first proposer's deploy time is kept with the binding, so completing a write (or rewriting metadata a read could not find) never changes a file's recorded deploy time: the metadata is write-once too. Reads of an artifact resolve to the highest state any answering replica holds ("present beats absent" is the
DHT's read rule), so a replica that missed the archive write cannot make an archived version resolve; a resolve also
obeys the consensus archived flag, so losing the marker on every replica a read reaches does not either. The versions
set is bounded: `[slice] artifact_max_versions` (default 10,000) caps the PRESENT versions of one artifact, because
a dev loop that pushes a fresh version per push (the scaffold's `deploy-test.sh`) would otherwise grow one KV value
without limit. The applier refuses a NEW version past the cap and the writer reports it (`409` naming the cap, before
anything is uploaded); it never drops a present version, never un-archives one, and an archive is always accepted and
frees room. Archived entries are kept FOREVER (owner ruling, #1778): one small KV entry per archived version for the life of
the cluster, never compacted, so the archived guard never rests on the DHT marker alone. Revisit only if a real cluster
shows the size matters. The versions
index is part of the cluster state that a KV backup carries, because the DHT keys it indexes survive a restart.

Known limits, stated so they are not mistaken for guarantees:

- **The per-version file list** (in the DHT) can lose an entry when two nodes add files to one version at once. It
  only dates the version for the retention check, and a missing entry makes that check stricter, never looser.
- **Mixed versions (known, [unverified]).** The merges are changes to the consensus applier; like the other applier
  fences they are not version gated. Before GA there is no mixed-version operation; the GA rolling-upgrade contract has
  to gate them together with the other applier changes.
- **Coordinates stored before this change** have no consensus binding. Re-putting such a coordinate is checked
  against its stored metadata only when that read finds it; a read that answers absent binds the new content, which then
  overwrites. Their metadata is in the pre-SHA-256 6-part format, which this version does not parse: such a coordinate
  neither resolves nor accepts a re-put (`500`, metadata unparseable). Pre-GA nothing migrates them; republish under
  a new version.
- **The version cap is per node.** `artifact_max_versions` travels in each publish command, so every replica decides a
  command alike, but each node enforces its OWN configured cap: configure it identically on every node.
- **Digest strength.** Content is compared by size, MD5, SHA-1 and SHA-256, so a collision of the two older digests alone does not pass. The integrity check on resolve still verifies SHA-1 only.

## Configuration

Configure repository sources in `aether.toml`:

```toml
[slice]
# Development/Forge - use local Maven repository
repositories = ["local"]

# Production - use cluster-internal repository only
# repositories = ["builtin"]

# Hybrid - try local first, then cluster
# repositories = ["local", "builtin"]

# Minimum time a version must have been stored in the built-in artifact store before it may be archived
# (default 7 days)
# artifact_archive_retention = "7d"
```

### Repository Types

| Type | Config Name | Location | Use Case |
|------|------------|----------|----------|
| LOCAL | `"local"` | `~/.m2/repository` | Development, Forge |
| BUILTIN | `"builtin"` | DHT-backed cluster storage | Production |

## CLI Commands

### Deploy Artifacts

```bash
# Push from local Maven repository to cluster
aether artifacts push <groupId:artifactId:version>

# Deploy JAR directly (without Maven)
aether artifacts deploy <jar-path> -g <groupId> -a <artifactId> -v <version>
```

### Query Artifacts

```bash
# List all artifacts in cluster
aether artifacts list

# List versions of specific artifact
aether artifacts versions <groupId:artifactId>

# Show artifact details
aether artifacts info <groupId:artifactId:version>
```

### Manage Artifacts

```bash
# Archive a version: it stops resolving and is delisted, its keys are kept.
# Allowed once the version has been stored for the minimum retention period (default 7 days).
# `aether artifacts delete` is accepted as an alias.
aether artifacts archive <groupId:artifactId:version>
```

### Metrics

```bash
# Show storage and deployment metrics
aether artifacts metrics
```

## Metrics

The artifact repository exposes the following metrics:

| Metric | Description |
|--------|-------------|
| `artifact.count` | Number of distinct artifacts stored |
| `artifact.chunks.total` | Total chunks stored (64KB each) |
| `artifact.memory.bytes` | Total memory used by chunks |
| `artifact.deployed.count` | Artifacts currently deployed (active in cluster) |

### Programmatic Access

```java
// Get metrics collector
ArtifactMetricsCollector collector = node.artifactMetricsCollector();

// Collect all metrics
Map<String, Double> metrics = collector.collectMetrics();

// Query specific values
ArtifactStore.Metrics storeMetrics = collector.storeMetrics();
int artifactCount = storeMetrics.artifactCount();
int chunkCount = storeMetrics.chunkCount();
long memoryBytes = storeMetrics.memoryBytes();

// Deployment queries
Set<Artifact> deployed = collector.deployedArtifacts();
boolean isDeployed = collector.isDeployed(artifact);
```

## Integrity Verification

The repository implements automatic integrity verification:

### On Deploy

When an artifact is deployed:
1. MD5, SHA-1 and SHA-256 hashes are computed
2. Hashes stored in artifact metadata
3. Chunk count and total size recorded

### On Resolve

When an artifact is loaded:
1. All chunks are retrieved from DHT
2. Content reassembled to original byte array
3. SHA-1 hash recomputed and verified against stored hash
4. If hash mismatch: `CorruptedArtifact` error returned, slice load fails

### Error Handling

```java
// Integrity failure produces specific error
sealed interface ArtifactStoreError extends Cause {
    record CorruptedArtifact(Artifact artifact) implements ArtifactStoreError {
        @Override
        public String message() {
            return "Corrupted artifact: " + artifact.asString();
        }
    }
}
```

## Chunk Storage

Artifacts are stored as fixed-size chunks for efficient DHT distribution:

### Chunk Size

- Fixed **64KB** (65,536 bytes) per chunk
- Last chunk may be smaller (stores remaining bytes)
- Chunk size is not configurable (optimized for DHT performance)

### Key Format

Every file of a Maven coordinate is its own entry — the jar, the pom and each classified file
(`-sources.jar`, `-javadoc.jar`, …) are keyed separately, so they never collide:

```
# Metadata of one file (hashes, size, chunk ids); {file} is [{classifier}.]{extension}
artifacts/{groupId}/{artifactId}/{version}/{file}/meta
#   e.g. …/1.0.0/jar/meta, …/1.0.0/pom/meta, …/1.0.0/sources.jar/meta

# Files deployed for a version: a grow-only set, used to date the version for the archive retention check
artifacts/{groupId}/{artifactId}/{version}/files

# Archive marker for a version (#1778): present = archived; value = archive time in epoch millis.
# Written once, never rewritten or removed.
artifacts/{groupId}/{artifactId}/{version}/archived

```

The versions of an artifact are not a DHT key: they are the consensus KV entry `artifact-versions/{groupId}:{artifactId}`,
a grow-only set whose entries carry the archived flag.

Chunk content is not keyed by coordinate: each 64KB chunk is stored in the node's `artifacts`
storage instance under its content hash (`BlockId`), and the file's `meta` entry lists the chunk
ids in order. Identical chunks are shared between files; archiving a version keeps its `meta` entries and does
not release its chunks.

### Example

For a 200KB artifact:
- 4 chunks created (64KB + 64KB + 64KB + 8KB)
- Each chunk stored separately in DHT
- Chunks may be distributed across different nodes
- Retrieval fetches all chunks in parallel

### DHT Replication

Chunk replication is controlled by `DHTConfig`:

| Mode | Replication Factor | Use Case |
|------|-------------------|----------|
| `DEFAULT` | 3 replicas (quorum 2) | Production |
| `FULL` | All nodes | Testing/Forge |
| `SINGLE_NODE` | 1 replica | Development |

## Limitations

### No Persistence

**Artifacts are lost on cluster restart.** This is intentional:
- Prevents stale artifact versions from lingering
- Forces explicit deployment to production
- Avoids complex persistence/recovery logic
- Production deployments should have CI/CD re-deployment capability

### No Authentication Beyond mTLS

- Node identity verified via mTLS certificates
- No user-level authentication
- No per-artifact access control
- Relies on network isolation for security

### No Rate Limiting

- Internal cluster traffic assumed
- No throttling on artifact operations
- External access should go through rate-limited API gateway

### Retention

- Artifacts remain until an operator archives them; archiving is allowed only after the minimum retention period
  (default 7 days, `artifact_archive_retention` in `[slice]`)
- No automatic cleanup of old versions: archiving is always an explicit request
- No garbage collection of unreferenced or archived chunks

### Memory-Only Storage

Current implementation uses in-memory storage:
- Fast access, suitable for runtime operation
- Limited by node memory
- Future: persistent storage engine option

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  CLI / ManagementServer                                         │
│  └─ aether artifacts push/deploy/list/archive                    │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  ArtifactStore                                                   │
│  └─ deploy(), resolve(), exists(), archive()                     │
│  └─ Chunk splitting, hash computation                            │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  Distributed Hash Table (DHT)                                    │
│  └─ Consistent hashing for key distribution                      │
│  └─ Configurable replication (1, 3, or all nodes)               │
└─────────────────────────────────────────────────────────────────┘
```

### BuiltinRepository

Adapter between `Repository` interface and `ArtifactStore`:
- Resolves artifacts from DHT-backed storage
- Writes resolved content to temporary files for ClassLoader
- Logs resolution with SHA-1 hash and size

### RepositoryFactory

Creates `Repository` instances from configuration:
- `LOCAL` -> `LocalRepository` (Maven ~/.m2)
- `BUILTIN` -> `BuiltinRepository` (DHT-backed)

## Related Documentation

- [Slice Developer Guide](../slice-developers/slice-patterns.md) - How to write slices, including built-in infrastructure services
- [CLI Reference](../reference/cli.md) - Complete CLI documentation

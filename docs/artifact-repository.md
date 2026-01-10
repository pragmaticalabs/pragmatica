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

For production, push artifacts to the cluster repository:

```bash
# First, install to local Maven repo
mvn clean install

# Then push to cluster
aether artifact push com.example:my-slice:1.0.0
```

Alternatively, deploy a JAR directly without Maven:

```bash
# Deploy JAR file directly to cluster
aether artifact deploy target/my-slice-1.0.0.jar \
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
aether artifact push <groupId:artifactId:version>

# Deploy JAR directly (without Maven)
aether artifact deploy <jar-path> -g <groupId> -a <artifactId> -v <version>
```

### Query Artifacts

```bash
# List all artifacts in cluster
aether artifact list

# List versions of specific artifact
aether artifact versions <groupId:artifactId>

# Show artifact details
aether artifact info <groupId:artifactId:version>
```

### Manage Artifacts

```bash
# Remove artifact from cluster
aether artifact delete <groupId:artifactId:version>
```

### Metrics

```bash
# Show storage and deployment metrics
aether artifact metrics
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
1. MD5 and SHA-1 hashes are computed
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

```
# Chunk content
artifacts/{groupId}/{artifactId}/{version}/content/{chunkIndex}

# Artifact metadata (hash, size, chunk count)
artifacts/{groupId}/{artifactId}/{version}/meta

# Version list per artifact
artifacts/{groupId}/{artifactId}/versions
```

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

### No Retention Policies

- Artifacts remain until manually deleted
- No automatic cleanup of old versions
- No garbage collection of unreferenced chunks
- Manual management via CLI required

### Memory-Only Storage

Current implementation uses in-memory storage:
- Fast access, suitable for runtime operation
- Limited by node memory
- Future: persistent storage engine option

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  CLI / ManagementServer                                         │
│  └─ aether artifact push/deploy/list/delete                     │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│  ArtifactStore                                                   │
│  └─ deploy(), resolve(), exists(), delete()                      │
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

- [Infrastructure Services](infrastructure-services.md) - Overview of built-in services
- [Slice Developer Guide](slice-developer-guide.md) - How to write slices
- [CLI Reference](guide/cli-reference.md) - Complete CLI documentation

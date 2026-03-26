# Declarative Cluster Management Specification

## Version: 1.0
## Status: Implementation-Ready
## Target Release: 0.22.0
## Last Updated: 2026-03-26
## Ticket: #74

---

## Table of Contents

1. [Overview](#1-overview)
2. [Configuration Format](#2-configuration-format)
3. [KV-Store Types](#3-kv-store-types)
4. [Bootstrap Flow](#4-bootstrap-flow)
5. [Apply (Diff + Converge)](#5-apply-diff--converge)
6. [Export](#6-export)
7. [Scale](#7-scale)
8. [Upgrade](#8-upgrade)
9. [Status](#9-status)
10. [Drain and Destroy](#10-drain-and-destroy)
11. [Cluster Registry](#11-cluster-registry)
12. [Authentication](#12-authentication)
13. [Config Inheritance](#13-config-inheritance)
14. [Management API Endpoints](#14-management-api-endpoints)
15. [Error Handling](#15-error-handling)
16. [User-Data Template](#16-user-data-template)
17. [Implementation Layers](#17-implementation-layers)
18. [Type Catalog](#18-type-catalog)
19. [References](#19-references)

---

## 1. Overview

### 1.1 Motivation

Aether manages slice lifecycle declaratively (blueprint.toml -> CDM converges). Cluster lifecycle (node provisioning, scaling, upgrades) is currently imperative -- manual API calls, scattered config, no single source of truth. This spec introduces declarative cluster management: describe desired cluster state in `aether-cluster.toml`, let Aether converge.

### 1.2 Core Concept

Two-section config separating logical structure from physical deployment:

```
[deployment]  ->  HOW to provision (cloud, instance types, zones, ports, TLS)
[cluster]     ->  WHAT to provision (node count, roles, distribution, auto-heal)
```

Same logical cluster can deploy to any supported environment without changing the `[cluster]` section.

### 1.3 Scope

| Phase | Contents | Target |
|-------|----------|--------|
| Phase 1 (this spec) | Cloud deployment (Hetzner first), bootstrap, apply, scale, status, export, destroy | 0.22.0 |
| Phase 2 | Kubernetes deployment type, on-premises (SSH-based), config inheritance | 0.23.0 |
| Phase 3 | Forge/Ember as embedded deployment type | 0.24.0 |

### 1.4 Design Principles

- REQ-DESIGN-01: Config format is TOML. All examples use TOML.
- REQ-DESIGN-02: `[deployment]` section is provider-specific; `[cluster]` section is provider-independent.
- REQ-DESIGN-03: After bootstrap, canonical config lives in consensus KV-Store. Local file is the seed.
- REQ-DESIGN-04: All mutations go through the management API. CLI is a thin client.
- REQ-DESIGN-05: Quorum safety is enforced at every layer -- config validation, API, CTM.

---

## 2. Configuration Format

### 2.1 Complete Reference

```toml
# aether-cluster.toml -- complete example (Hetzner)

[deployment]
type = "hetzner"                              # REQ-CFG-01: required, one of: hetzner | aws | gcp | azure | kubernetes | on-premises | embedded

[deployment.instances]
core = "cx21"                                 # REQ-CFG-02: provider-specific instance size for core nodes
worker = "cx11"                               # REQ-CFG-03: provider-specific instance size for workers (future)

[deployment.runtime]
type = "container"                            # REQ-CFG-04: container | jvm
image = "ghcr.io/pragmaticalabs/aether-node:0.21.1"  # REQ-CFG-05: required when runtime.type = "container"
jvm_args = ""                                 # REQ-CFG-06: optional, extra JVM arguments

[deployment.zones]                            # REQ-CFG-07: logical -> physical zone mapping (optional)
zone-a = "fsn1-dc14"
zone-b = "fsn1-dc15"

[deployment.ports]                            # REQ-CFG-08: logical -> physical port mapping
cluster = 6000                                # default: 8090 (PortsConfig.DEFAULT_CLUSTER_PORT)
management = 5150                             # default: 8080 (PortsConfig.DEFAULT_MANAGEMENT_PORT)
app-http = 8070                               # default: 8070
swim = 6100                                   # default: cluster + 100

[deployment.tls]                              # REQ-CFG-09: TLS configuration
auto_generate = true                          # default: true when tls section present
cluster_secret = "${secrets:cluster-secret}"  # REQ-CFG-10: secret reference for CA generation
cert_ttl = "720h"                             # default: 720h (30 days)

[cluster]
name = "production"                           # REQ-CFG-11: required, unique cluster identifier
version = "0.21.1"                            # REQ-CFG-12: Aether version to deploy

[cluster.core]
count = 5                                     # REQ-CFG-13: desired core node count (must be odd, >= 3)
min = 3                                       # REQ-CFG-14: minimum core nodes (quorum safety floor)
max = 9                                       # REQ-CFG-15: maximum core nodes (growth cap)

[cluster.workers]                             # REQ-CFG-16: future -- worker pool support
count = 0                                     # default: 0

[cluster.distribution]
strategy = "balanced"                         # REQ-CFG-17: balanced | manual
zones = ["zone-a", "zone-b"]                  # REQ-CFG-18: zone list for balanced distribution

[cluster.auto_heal]
enabled = true                                # REQ-CFG-19: enable auto-healing
retry_interval = "60s"                        # REQ-CFG-20: interval between heal attempts (maps to AutoHealConfig.retryInterval)
startup_cooldown = "15s"                      # REQ-CFG-21: cooldown before first heal check (maps to AutoHealConfig.startupCooldown)

[cluster.upgrade]
strategy = "rolling"                          # REQ-CFG-22: rolling | blue-green (default: rolling)
```

### 2.2 Validation Rules

| Rule ID | Field | Constraint | Error |
|---------|-------|-----------|-------|
| VAL-01 | `deployment.type` | Must be one of the 7 supported types | `InvalidDeploymentType` |
| VAL-02 | `cluster.name` | Non-empty, `[a-z0-9][a-z0-9-]*`, max 63 chars | `InvalidClusterName` |
| VAL-03 | `cluster.core.count` | Odd number >= 3 | `InvalidCoreCount` |
| VAL-04 | `cluster.core.min` | Odd number >= 3, <= `core.count` | `InvalidCoreMin` |
| VAL-05 | `cluster.core.max` | Odd number >= `core.count` | `InvalidCoreMax` |
| VAL-06 | `cluster.version` | Valid semver `X.Y.Z` | `InvalidVersion` |
| VAL-07 | `deployment.instances.core` | Non-empty string | `MissingInstanceType` |
| VAL-08 | `deployment.runtime.type` | `container` or `jvm` | `InvalidRuntimeType` |
| VAL-09 | `deployment.runtime.image` | Required when `runtime.type = "container"` | `MissingContainerImage` |
| VAL-10 | `deployment.ports.*` | Port range 1-65535 | `InvalidPort` |
| VAL-11 | `deployment.zones` | All zone names referenced in `cluster.distribution.zones` must have a physical mapping | `UnmappedZone` |
| VAL-12 | `cluster.distribution.strategy` | `balanced` or `manual` | `InvalidDistributionStrategy` |
| VAL-13 | `cluster.auto_heal.retry_interval` | Parseable duration >= 5s | `InvalidRetryInterval` |
| VAL-14 | `deployment.tls.cluster_secret` | Must start with `${secrets:` or be a literal | `InvalidSecretReference` |

### 2.3 Defaults

| Field | Default | Notes |
|-------|---------|-------|
| `deployment.instances.worker` | Same as `deployment.instances.core` | Workers use core size if unspecified |
| `deployment.runtime.type` | `container` | |
| `deployment.ports.cluster` | `8090` | From `PortsConfig.DEFAULT_CLUSTER_PORT` |
| `deployment.ports.management` | `8080` | From `PortsConfig.DEFAULT_MANAGEMENT_PORT` |
| `deployment.ports.app-http` | `8070` | |
| `deployment.ports.swim` | `deployment.ports.cluster + 100` | |
| `deployment.tls.auto_generate` | `true` | |
| `deployment.tls.cert_ttl` | `720h` | |
| `cluster.core.min` | `3` | |
| `cluster.core.max` | `cluster.core.count` | No growth cap by default |
| `cluster.workers.count` | `0` | |
| `cluster.distribution.strategy` | `balanced` | |
| `cluster.auto_heal.enabled` | `true` | |
| `cluster.auto_heal.retry_interval` | `60s` | |
| `cluster.auto_heal.startup_cooldown` | `15s` | |
| `cluster.upgrade.strategy` | `rolling` | |

### 2.4 Provider-Specific Examples

#### 2.4.1 Hetzner

```toml
[deployment]
type = "hetzner"

[deployment.instances]
core = "cx21"           # 2 vCPU, 4 GB RAM
worker = "cx11"         # 1 vCPU, 2 GB RAM

[deployment.runtime]
type = "container"
image = "ghcr.io/pragmaticalabs/aether-node:0.21.1"

[deployment.zones]
zone-a = "fsn1-dc14"    # Falkenstein DC14
zone-b = "nbg1-dc3"     # Nuremberg DC3

[deployment.ports]
cluster = 6000
management = 5150

[deployment.tls]
auto_generate = true
cluster_secret = "${secrets:cluster-secret}"

[cluster]
name = "hetzner-prod"
version = "0.21.1"

[cluster.core]
count = 5
min = 3
max = 9

[cluster.distribution]
strategy = "balanced"
zones = ["zone-a", "zone-b"]

[cluster.auto_heal]
enabled = true
retry_interval = "30s"
```

Cloud credentials: `HETZNER_API_TOKEN` environment variable. Maps to existing `HetznerEnvironmentConfig` / `HetznerComputeProvider`.

#### 2.4.2 AWS

```toml
[deployment]
type = "aws"

[deployment.instances]
core = "t3.medium"      # 2 vCPU, 4 GiB RAM
worker = "t3.small"     # 2 vCPU, 2 GiB RAM

[deployment.runtime]
type = "container"
image = "123456789.dkr.ecr.us-east-1.amazonaws.com/aether-node:0.21.1"

[deployment.zones]
zone-a = "us-east-1a"
zone-b = "us-east-1b"
zone-c = "us-east-1c"

[deployment.ports]
cluster = 6000
management = 5150

[deployment.tls]
auto_generate = true
cluster_secret = "${secrets:aether/cluster-secret}"

[deployment.aws]                              # Provider-specific section
vpc_id = "vpc-0123456789abcdef0"
subnet_ids = ["subnet-aaa", "subnet-bbb", "subnet-ccc"]
security_group_id = "sg-0123456789abcdef0"
key_pair = "aether-nodes"

[cluster]
name = "aws-prod"
version = "0.21.1"

[cluster.core]
count = 5
min = 3
max = 9

[cluster.distribution]
strategy = "balanced"
zones = ["zone-a", "zone-b", "zone-c"]
```

Cloud credentials: `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION`.

#### 2.4.3 GCP

```toml
[deployment]
type = "gcp"

[deployment.instances]
core = "e2-medium"
worker = "e2-small"

[deployment.runtime]
type = "container"
image = "gcr.io/my-project/aether-node:0.21.1"

[deployment.zones]
zone-a = "us-central1-a"
zone-b = "us-central1-b"

[deployment.gcp]
project_id = "my-project"
network = "default"
subnetwork = "default"

[cluster]
name = "gcp-prod"
version = "0.21.1"

[cluster.core]
count = 5
```

Cloud credentials: `GOOGLE_APPLICATION_CREDENTIALS` (path to service account JSON) or `GOOGLE_CLOUD_PROJECT`.

#### 2.4.4 Azure

```toml
[deployment]
type = "azure"

[deployment.instances]
core = "Standard_B2s"
worker = "Standard_B1s"

[deployment.runtime]
type = "container"
image = "myregistry.azurecr.io/aether-node:0.21.1"

[deployment.zones]
zone-a = "eastus-1"
zone-b = "eastus-2"

[deployment.azure]
resource_group = "aether-rg"
vnet = "aether-vnet"
subnet = "aether-subnet"

[cluster]
name = "azure-prod"
version = "0.21.1"

[cluster.core]
count = 5
```

Cloud credentials: `AZURE_SUBSCRIPTION_ID`, `AZURE_TENANT_ID`, `AZURE_CLIENT_ID`, `AZURE_CLIENT_SECRET`.

#### 2.4.5 Kubernetes

```toml
[deployment]
type = "kubernetes"

[deployment.instances]
core = "2cpu-4gi"                             # Maps to resource requests: cpu=2, memory=4Gi

[deployment.runtime]
type = "container"
image = "ghcr.io/pragmaticalabs/aether-node:0.21.1"

[deployment.zones]
zone-a = "topology.kubernetes.io/zone=us-east-1a"
zone-b = "topology.kubernetes.io/zone=us-east-1b"

[deployment.kubernetes]                       # Provider-specific section
namespace = "aether"
storage_class = "gp3"
storage_size = "10Gi"
service_type = "ClusterIP"                    # ClusterIP | LoadBalancer | NodePort

[cluster]
name = "k8s-prod"
version = "0.21.1"

[cluster.core]
count = 5

[cluster.distribution]
strategy = "balanced"
zones = ["zone-a", "zone-b"]
```

REQ-K8S-01: Kubernetes deployment generates a StatefulSet manifest. `deployment.instances.core` parses as `<cpu>cpu-<memory>` and maps to container resource requests/limits. Zone references map to `topologySpreadConstraints`.

[ASSUMPTION] Kubernetes deployment is Phase 2. This section defines the config format only; generator implementation is deferred.

#### 2.4.6 On-Premises

```toml
[deployment]
type = "on-premises"

[deployment.runtime]
type = "jvm"
jvm_args = "-Xmx4g -XX:+UseZGC"

[deployment.nodes]                            # Static node inventory
core = ["10.0.1.1", "10.0.1.2", "10.0.1.3", "10.0.1.4", "10.0.1.5"]

[deployment.ports]
cluster = 6000
management = 5150

[deployment.tls]
auto_generate = true
cluster_secret = "${secrets:cluster-secret}"

[deployment.ssh]                              # SSH config for bootstrap
user = "aether"
key_path = "~/.ssh/aether_ed25519"
port = 22

[cluster]
name = "onprem-prod"
version = "0.21.1"

[cluster.core]
count = 5
```

REQ-ONPREM-01: `deployment.nodes.core` list size must equal `cluster.core.count`.
REQ-ONPREM-02: Bootstrap uses SSH to push JAR + config to each node. Implementation deferred to separate ticket (Phase 2).

#### 2.4.7 Embedded (Forge)

```toml
[deployment]
type = "embedded"

[cluster]
name = "local-dev"
version = "0.21.1"

[cluster.core]
count = 3
min = 3
max = 3
```

REQ-EMBED-01: Embedded deployment starts in-process nodes. No cloud provisioning, no SSH. Uses existing Forge infrastructure. Phase 3.

---

## 3. KV-Store Types

### 3.1 ClusterConfigKey

New variant of the existing `AetherKey` sealed interface.

```java
/// Key for cluster-wide configuration stored in consensus.
/// Format: cluster-config/{config-version}
record ClusterConfigKey(long configVersion) implements AetherKey {
    private static final String PREFIX = "cluster-config/";

    @Override
    public String asString() {
        return PREFIX + configVersion;
    }

    /// The "current" key always points to the latest version.
    public static final ClusterConfigKey CURRENT = new ClusterConfigKey(0);

    public static ClusterConfigKey clusterConfigKey(long configVersion) {
        return new ClusterConfigKey(configVersion);
    }
}
```

REQ-KV-01: `ClusterConfigKey.CURRENT` (version=0) always holds the active cluster config. Versioned keys (version>0) are historical snapshots retained for audit.

### 3.2 ClusterConfigValue

```java
/// Stores the canonical cluster configuration in consensus KV-Store.
///
/// The TOML content is stored as a string for human readability and
/// round-trip fidelity (comments are stripped, but field ordering is preserved).
/// Structured fields are extracted for quick access without parsing.
record ClusterConfigValue(
    String tomlContent,           // Full TOML string (comments stripped)
    String clusterName,           // Extracted from [cluster].name
    String version,               // Extracted from [cluster].version
    int coreCount,                // Extracted from [cluster.core].count
    int coreMin,                  // Extracted from [cluster.core].min
    int coreMax,                  // Extracted from [cluster.core].max
    String deploymentType,        // Extracted from [deployment].type
    long configVersion,           // Monotonically increasing version for optimistic concurrency
    long updatedAt                // Epoch millis
) implements AetherValue {}
```

REQ-KV-02: `configVersion` is incremented on every successful `apply`. Used for optimistic concurrency -- apply fails if stored version differs from expected.

REQ-KV-03: `tomlContent` stores the full config as a TOML string. This enables `export` to reconstruct the exact file. Secrets placeholders are **not** resolved in the stored content -- `${secrets:cluster-secret}` is stored verbatim.

### 3.3 ClusterApiKeyValue

```java
/// Stores the cluster API key hash in KV-Store.
/// Key: ConfigKey("cluster-api-key", Option.none())
record ClusterApiKeyValue(
    String keyHash,               // SHA-256 hash of the API key
    long createdAt,
    long expiresAt                // 0 = never expires
) implements AetherValue {}
```

REQ-KV-04: The actual API key is returned once during bootstrap and never stored in plaintext. Only the hash is persisted. Validation compares `SHA-256(provided-key)` against stored hash.

### 3.4 Version Tracking

REQ-KV-05: Every `apply` operation reads the current `ClusterConfigValue.configVersion`, increments it, and writes back. If a concurrent `apply` has incremented the version in the meantime, the consensus write will succeed (last-writer-wins within consensus), but the CLI detects the version mismatch via a pre-read and warns the user.

The optimistic concurrency flow:

1. CLI reads current `ClusterConfigValue` from cluster
2. CLI computes diff, confirms with user
3. CLI sends `POST /api/cluster/config` with `expected_version` field
4. Server compares `expected_version` against stored `configVersion`
5. If match: apply changes, increment version, write
6. If mismatch: return `ClusterConfigError.VersionConflict` with current version

---

## 4. Bootstrap Flow

### 4.1 Cloud-Init Bootstrap (Cloud Providers)

This is the primary bootstrap mechanism for cloud deployments (Hetzner, AWS, GCP, Azure).

**Command:**
```bash
aether cluster bootstrap aether-cluster.toml
```

**Sequence (REQ-BOOT-01 through REQ-BOOT-12):**

```
Step 1: Parse and Validate Config (REQ-BOOT-01)
  |  Read aether-cluster.toml
  |  Apply validation rules (VAL-01 through VAL-14)
  |  Fail fast on any validation error
  |
Step 2: Resolve Secrets (REQ-BOOT-02)
  |  Scan config for ${secrets:xxx} placeholders
  |  Resolve via SecretsProvider (env-specific: Vault, AWS SM, etc.)
  |  For cloud credentials: read from environment variables
  |  Fail if any secret cannot be resolved
  |
Step 3: Generate Node Configs (REQ-BOOT-03)
  |  For each node i in 0..core.count-1:
  |    Generate aether.toml with:
  |      - node_id: KSUID (unique per node)
  |      - cluster port: deployment.ports.cluster (same for all, cloud LB or direct)
  |      - management port: deployment.ports.management
  |      - seed_peers: all other node addresses (populated after provisioning)
  |      - tls config from deployment.tls
  |      - zone assignment per distribution strategy
  |
Step 4: Generate User-Data Scripts (REQ-BOOT-04)
  |  For each node, generate cloud-init script from template (section 16)
  |  Script installs runtime, writes config, starts Aether
  |
Step 5: Provision Instances (REQ-BOOT-05)
  |  For each node (parallel, up to core.count concurrent):
  |    Call ComputeProvider.provision(ProvisionSpec) with:
  |      - instanceType: InstanceType.ON_DEMAND
  |      - instanceSize: deployment.instances.core
  |      - pool: "core"
  |      - tags: {aether-cluster: <name>, aether-role: core, aether-node-id: <ksuid>}
  |      - userData: generated cloud-init script
  |    Record InstanceInfo (id, addresses)
  |
Step 6: Wait for DNS/Network (REQ-BOOT-06)
  |  Poll instance addresses until reachable (TCP connect to management port)
  |  Timeout: 5 minutes per instance
  |
Step 7: Update Seed Peers (REQ-BOOT-07)
  |  Now that all IPs are known, push updated seed-peer list to each node
  |  via management API: POST /api/config with seed-peers value
  |  [ASSUMPTION] Alternatively, use DiscoveryProvider for tag-based discovery
  |  so seed-peers are not needed in config
  |
Step 8: Wait for Quorum (REQ-BOOT-08)
  |  Poll GET /health/ready on each node
  |  Wait until (core.count / 2 + 1) nodes report consensus = UP
  |  Timeout: 10 minutes total
  |
Step 9: Store Cluster Config in KV-Store (REQ-BOOT-09)
  |  POST /api/cluster/config to leader node
  |  Body: full aether-cluster.toml content + extracted fields
  |  configVersion = 1
  |
Step 10: Generate and Store API Key (REQ-BOOT-10)
  |  Generate random 256-bit API key (Base64-encoded)
  |  Store SHA-256 hash in KV-Store via ConfigKey("cluster-api-key")
  |  The plaintext key is returned to the user ONCE
  |
Step 11: Register in Local Registry (REQ-BOOT-11)
  |  Write entry to ~/.aether/clusters.toml:
  |    [clusters.<name>]
  |    endpoint = "https://<leader-ip>:<management-port>"
  |    api_key_env = "AETHER_<NAME>_API_KEY"
  |  Set as current context if no current exists
  |
Step 12: Print Connection Info (REQ-BOOT-12)
  |  Output:
  |    Cluster "<name>" bootstrapped successfully.
  |    Nodes: 5/5 healthy
  |    Leader: <node-id>
  |    Endpoint: https://<ip>:<port>
  |    API Key: <key> (save this -- it will not be shown again)
  |
  |  Set AETHER_<NAME>_API_KEY=<key> in your environment.
```

### 4.2 SSH-Based Bootstrap (On-Premises)

REQ-BOOT-SSH-01: Spec'd for completeness. Implementation deferred to Phase 2 / separate ticket.

**Differences from cloud-init:**
- Step 4: Generate systemd unit file instead of cloud-init script
- Step 5: SSH to each node in `deployment.nodes.core`, upload JAR + config, start service
- Step 6: Same -- poll management port
- Step 7-12: Identical to cloud-init flow

### 4.3 Bootstrap Idempotency

REQ-BOOT-IDEMP-01: Running `bootstrap` against an existing cluster name fails with `ClusterAlreadyExists`. User must `destroy` first or use `apply`.

REQ-BOOT-IDEMP-02: If bootstrap fails partway (e.g., 3 of 5 nodes provisioned), the CLI outputs the partial state and instructions:
```
Bootstrap failed: 3 of 5 nodes provisioned.
Provisioned instances: [instance-abc, instance-def, instance-ghi]
To retry: aether cluster bootstrap aether-cluster.toml
To cleanup: aether cluster destroy --force
```

The retry will detect existing tagged instances (via `ComputeProvider.listInstances(tagFilter)`) and skip re-provisioning them.

---

## 5. Apply (Diff + Converge)

### 5.1 Command

```bash
aether cluster apply aether-cluster.toml              # Apply changes
aether cluster apply aether-cluster.toml --dry-run    # Show planned changes, do not execute
```

### 5.2 Diff Algorithm

REQ-APPLY-01: `apply` compares the new TOML against the `ClusterConfigValue` stored in KV-Store. Fields are compared structurally, not by string diff.

### 5.3 Field-to-Action Matrix

| Changed Field | Action | Mechanism | Risk |
|---------------|--------|-----------|------|
| `cluster.core.count` (increase) | Scale up | `CTM.setDesiredSize(newCount)` via `POST /api/cluster/scale` | Low |
| `cluster.core.count` (decrease) | Scale down | `CTM.setDesiredSize(newCount)` -- drains excess nodes | Medium |
| `cluster.version` | Rolling upgrade | `RollingUpdateManager.startUpdate()` via management API | High |
| `cluster.core.min` | Update limits | Store in KV-Store, CTM reads on next reconcile | Low |
| `cluster.core.max` | Update limits | Store in KV-Store, CTM reads on next reconcile | Low |
| `cluster.auto_heal.enabled` | Toggle auto-heal | Update `AutoHealConfig` via management API | Low |
| `cluster.auto_heal.retry_interval` | Update interval | Update `AutoHealConfig` via management API | Low |
| `cluster.auto_heal.startup_cooldown` | Update cooldown | Update `AutoHealConfig` via management API | Low |
| `cluster.upgrade.strategy` | Store preference | KV-Store update only (used on next upgrade) | None |
| `deployment.tls.cert_ttl` | Cert rotation | `CertificateRenewalScheduler` picks up new TTL | Low |
| `deployment.tls.cluster_secret` | Full cert rotation | Re-derive CA, issue new certs to all nodes | High |
| `deployment.ports.*` | **Rejected** | Port changes require re-bootstrap | N/A |
| `deployment.type` | **Rejected** | Provider changes require re-bootstrap | N/A |
| `deployment.instances.*` | **Rejected** | Instance type changes require re-bootstrap | N/A |
| `deployment.zones.*` | Store mapping | KV-Store update (rebalancing is future) | None |
| `cluster.name` | **Rejected** | Name is immutable after bootstrap | N/A |

REQ-APPLY-02: Changes to immutable fields (marked "Rejected" above) produce an error:
```
Error: Field 'deployment.type' is immutable after bootstrap.
To change deployment type, destroy and re-bootstrap the cluster.
```

### 5.4 Apply Execution

```
1. Parse and validate new config
2. Fetch current ClusterConfigValue from GET /api/cluster/config
3. Compute diff (field-by-field comparison)
4. If --dry-run: print planned actions and exit
5. Validate immutability constraints (REQ-APPLY-02)
6. Check optimistic concurrency (expected_version matches stored version)
7. Execute actions in order:
   a. KV-Store-only changes (limits, preferences)
   b. Auto-heal config changes
   c. Scale changes (via CTM)
   d. Upgrade (via RollingUpdateManager -- blocks until complete or detaches)
   e. TLS changes (cert rotation)
8. Store updated ClusterConfigValue with incremented configVersion
9. Print summary of applied changes
```

### 5.5 Dry-Run Output Format

```
$ aether cluster apply cluster-prod.toml --dry-run

Cluster: production (version 1 -> 2)
Planned changes:
  [SCALE]   core.count: 5 -> 7  (add 2 nodes)
  [UPDATE]  auto_heal.retry_interval: 60s -> 30s
  [NOOP]    cluster.version: 0.21.1 (unchanged)

2 changes, 0 rejected.
Run without --dry-run to apply.
```

---

## 6. Export

### 6.1 Command

```bash
aether cluster export                          # Print TOML to stdout
aether cluster export > cluster-backup.toml    # Save to file
aether cluster export --with-status            # Include runtime state as TOML comments
```

### 6.2 Behavior

REQ-EXPORT-01: Fetch `ClusterConfigValue` from `GET /api/cluster/config`. Return the `tomlContent` field as-is.

REQ-EXPORT-02: With `--with-status`, append runtime state as comments:

```toml
# --- Exported from cluster "production" at 2026-03-26T14:30:00Z ---
# Config version: 7
# Actual core nodes: 5/5 healthy
# Leader: 2SjKf4mVqR1bN8tW
# Certificate expires: 2026-04-25T14:30:00Z

[deployment]
type = "hetzner"
# ...rest of config...
```

REQ-EXPORT-03: The exported TOML is a valid `aether-cluster.toml` that can be used with `apply` or `bootstrap` (for a new cluster).

---

## 7. Scale

### 7.1 Command

```bash
aether cluster scale --core 7                 # Scale core nodes to 7
aether cluster scale --core 7 --dry-run       # Show what would happen
```

### 7.2 Behavior

REQ-SCALE-01: `scale` is a thin wrapper around `apply`. It:
1. Fetches current `ClusterConfigValue`
2. Modifies only `cluster.core.count` to the new value
3. Delegates to the same `apply` logic

REQ-SCALE-02: Quorum safety validation:
- Never below `cluster.core.min` (default 3)
- Never below absolute minimum of 3 (hardcoded)
- Never above `cluster.core.max`
- New count must be odd

REQ-SCALE-03: Scale-down drains excess nodes before terminating. Node selection for removal: newest nodes first (by KSUID ordering), preferring nodes in over-represented zones.

### 7.3 REST Flow

```
CLI: POST /api/cluster/scale { "core_count": 7, "expected_version": 5 }
Server:
  1. Validate quorum safety
  2. CTM.setDesiredSize(7)
  3. Update ClusterConfigValue in KV-Store
  4. Return { "success": true, "previous": 5, "new": 7, "config_version": 6 }
```

---

## 8. Upgrade

### 8.1 Command

```bash
aether cluster upgrade --version 0.22.0                  # Rolling upgrade
aether cluster upgrade --version 0.22.0 --strategy blue-green  # Override strategy
```

### 8.2 Behavior

REQ-UPGRADE-01: Upgrade modifies `cluster.version` in the config and triggers a rolling upgrade of the Aether node software itself (not slice upgrades -- those use the existing `RollingUpdateManager`).

REQ-UPGRADE-02: Node upgrade sequence (rolling):
1. For each node (starting with non-leader nodes):
   a. Drain the node (`POST /api/node/drain/<nodeId>`)
   b. Wait for drain completion (slices migrated)
   c. Update the node's container image or JVM binary
   d. Restart the node
   e. Wait for node to rejoin and become healthy (`GET /health/ready`)
   f. Activate the node (`POST /api/node/activate/<nodeId>`)
2. Upgrade the leader last
3. Update `cluster.version` in KV-Store `ClusterConfigValue`

REQ-UPGRADE-03: For cloud deployments, "update the node" means:
- Container runtime: `ComputeProvider.restart(instanceId)` with updated user-data containing new image tag
- JVM runtime: SSH to node, replace JAR, restart process (Phase 2)

REQ-UPGRADE-04: Upgrade is non-atomic. If it fails mid-way, the cluster is in a mixed-version state. The `status` command shows per-node versions. Re-running `upgrade` retries from where it left off (skipping already-upgraded nodes).

REQ-UPGRADE-05: Version downgrade is allowed but requires `--force` flag.

---

## 9. Status

### 9.1 Command

```bash
aether cluster status                          # Full cluster status
aether cluster status --json                   # JSON output
```

### 9.2 Output

```
Cluster: production
Version: 0.21.1 (config version: 7)
State: CONVERGED

Nodes (5/5 healthy):
  ID                    Role    Status    Zone      Version
  2SjKf4mVqR1bN8tW     core    ON_DUTY   zone-a    0.21.1    (leader)
  3TkLg5nWrS2cO9uX     core    ON_DUTY   zone-b    0.21.1
  4UmMh6oXsT3dP0vY     core    ON_DUTY   zone-a    0.21.1
  5VnNi7pYtU4eQ1wZ     core    ON_DUTY   zone-b    0.21.1
  6WoOj8qZuV5fR2xA     core    ON_DUTY   zone-a    0.21.1

Slices: 12 deployed, 36 instances
Certificate: expires 2026-04-25 (29 days remaining)
Auto-heal: enabled (interval: 30s)
```

### 9.3 Data Sources

REQ-STATUS-01: Status aggregates from multiple management API endpoints:

| Data | Source Endpoint |
|------|----------------|
| Cluster info, leader, uptime | `GET /api/status` |
| Node list, roles | `GET /api/nodes` |
| Health, quorum | `GET /api/health` |
| Node lifecycle states | `GET /api/nodes/lifecycle` |
| Topology (core count, limits) | `GET /api/cluster/topology` |
| Certificate expiry | `GET /api/certificate` |
| Cluster config (desired state) | `GET /api/cluster/config` (new) |
| Slice deployment info | `GET /api/slices` |

REQ-STATUS-02: Desired vs actual comparison:
- If `core.count` in config != actual healthy core count: show `(desired: N, actual: M)`
- If `version` in config != all nodes' version: show per-node version column

---

## 10. Drain and Destroy

### 10.1 Drain

```bash
aether cluster drain <node-id>                 # Graceful drain single node
```

REQ-DRAIN-01: Delegates to existing `POST /api/node/drain/<nodeId>`. No new logic.

### 10.2 Destroy

```bash
aether cluster destroy                         # Interactive confirmation
aether cluster destroy --force                 # Skip confirmation
aether cluster destroy --force --keep-instances # Remove registry only
```

REQ-DESTROY-01: Destroy sequence:
1. Confirm: `This will permanently destroy cluster "production". Type the cluster name to confirm:`
2. Drain all nodes (parallel): `POST /api/node/drain/<nodeId>` for each
3. Wait for all drains to complete (slices unloaded)
4. Terminate all instances (parallel): `ComputeProvider.terminate(instanceId)` for each
5. Remove cluster from local registry (`~/.aether/clusters.toml`)
6. Print confirmation

REQ-DESTROY-02: If some instances fail to terminate, print warning with instance IDs for manual cleanup:
```
Warning: Failed to terminate 2 instances: [instance-abc, instance-def]
Clean up manually via cloud provider console.
Cluster "production" removed from local registry.
```

REQ-DESTROY-03: `--keep-instances` skips steps 2-4, only removes the registry entry. Used when instances are managed externally.

---

## 11. Cluster Registry

### 11.1 File Format

Location: `~/.aether/clusters.toml`

```toml
[current]
context = "production"                        # Active cluster name

[clusters.production]
endpoint = "https://203.0.113.10:5150"
api_key_env = "AETHER_PRODUCTION_API_KEY"     # Env var name containing the API key
bootstrapped_at = "2026-03-26T14:30:00Z"

[clusters.staging]
endpoint = "https://198.51.100.20:5150"
api_key_env = "AETHER_STAGING_API_KEY"
bootstrapped_at = "2026-03-20T10:00:00Z"

[clusters.local]
endpoint = "http://localhost:8080"
# No api_key_env -- local clusters may not require auth
```

### 11.2 Commands

```bash
aether cluster list                            # List all registered clusters
aether cluster use <name>                      # Switch active context
aether cluster remove <name>                   # Remove from registry (does not destroy)
```

### 11.3 List Output

```
$ aether cluster list

  NAME          ENDPOINT                         BOOTSTRAPPED
* production    https://203.0.113.10:5150        2026-03-26
  staging       https://198.51.100.20:5150       2026-03-20
  local         http://localhost:8080             2026-03-18

* = active context
```

### 11.4 Endpoint Override

REQ-REGISTRY-01: `--endpoint` flag on any command overrides the registry:
```bash
aether --endpoint=https://10.0.1.1:5150 cluster status
```

REQ-REGISTRY-02: `--endpoint` does not persist to the registry.

REQ-REGISTRY-03: If no current context and no `--endpoint`, CLI prints:
```
Error: No active cluster context. Use 'aether cluster use <name>' or '--endpoint'.
```

---

## 12. Authentication

### 12.1 Cloud Credentials

REQ-AUTH-01: Cloud provider credentials come exclusively from environment variables. Never from the TOML file.

| Provider | Required Environment Variables |
|----------|-------------------------------|
| Hetzner | `HETZNER_API_TOKEN` |
| AWS | `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION` |
| GCP | `GOOGLE_APPLICATION_CREDENTIALS` or `GOOGLE_CLOUD_PROJECT` + default credentials |
| Azure | `AZURE_SUBSCRIPTION_ID`, `AZURE_TENANT_ID`, `AZURE_CLIENT_ID`, `AZURE_CLIENT_SECRET` |

REQ-AUTH-02: Missing cloud credentials produce a clear error before any provisioning:
```
Error: HETZNER_API_TOKEN not set. Required for deployment type "hetzner".
```

### 12.2 Cluster API Key

REQ-AUTH-03: After bootstrap, all management API calls require an API key in the `Authorization` header:
```
Authorization: Bearer <api-key>
```

REQ-AUTH-04: The API key is resolved from:
1. `AETHER_API_KEY` environment variable (highest priority)
2. Environment variable named in registry's `api_key_env` field
3. `--api-key` CLI flag (lowest priority, not recommended for production)

REQ-AUTH-05: Bootstrap generates the initial API key. Additional keys can be generated via:
```bash
aether cluster api-key generate                # Generate new key, output plaintext
aether cluster api-key revoke <key-prefix>     # Revoke a key by its first 8 chars
aether cluster api-key list                    # List key prefixes and creation dates
```

[ASSUMPTION] Multi-key support (generate/revoke/list) is deferred. Phase 1 supports a single API key set during bootstrap.

---

## 13. Config Inheritance

### 13.1 Command

```bash
aether cluster apply --base cluster-base.toml --overlay cluster-prod.toml
aether cluster bootstrap --base cluster-base.toml --overlay cluster-prod.toml
```

### 13.2 Merge Semantics

REQ-INHERIT-01: Deep merge at the TOML table level. Overlay sections replace base sections at the **table** level, not field level.

Example:
```toml
# cluster-base.toml
[cluster.core]
count = 5
min = 3
max = 9

# cluster-prod.toml (overlay)
[cluster.core]
count = 7
```

Result: `cluster.core = { count = 7 }` -- `min` and `max` are **dropped** because the overlay replaced the entire `[cluster.core]` table.

REQ-INHERIT-02: To inherit individual fields, the overlay should only specify the fields it wants to change at the **leaf table** level. For field-level overrides within a table, the overlay must include all fields of that table.

REQ-INHERIT-03: Array fields from overlay replace (not concatenate):
```toml
# base
[cluster.distribution]
zones = ["zone-a", "zone-b"]

# overlay
[cluster.distribution]
zones = ["zone-a", "zone-b", "zone-c"]

# result: ["zone-a", "zone-b", "zone-c"] (overlay replaces)
```

REQ-INHERIT-04: Top-level sections not present in overlay are inherited from base unchanged. Top-level sections present in overlay fully replace the base section.

### 13.3 Merge Algorithm

```
function merge(base: TomlDocument, overlay: TomlDocument) -> TomlDocument:
    result = copy(base)
    for each top-level table T in overlay:
        result[T] = overlay[T]    // full replacement at top-level table
    return result
```

[ASSUMPTION] Config inheritance is Phase 2. Phase 1 supports only a single config file.

---

## 14. Management API Endpoints

### 14.1 New Endpoints

These endpoints are added to `ManagementServer` via a new `ClusterConfigRoutes` route source.

#### POST /api/cluster/config

Store or update the cluster configuration in KV-Store.

**Request:**
```json
{
  "toml_content": "...",
  "expected_version": 5
}
```

**Response (200):**
```json
{
  "config_version": 6,
  "cluster_name": "production",
  "core_count": 5,
  "updated_at": 1711461000000
}
```

**Errors:**
- `409 Conflict`: `expected_version` mismatch (concurrent modification)
- `400 Bad Request`: Invalid TOML or validation failure
- `401 Unauthorized`: Missing or invalid API key

#### GET /api/cluster/config

Retrieve the current cluster configuration.

**Response (200):**
```json
{
  "toml_content": "...",
  "cluster_name": "production",
  "version": "0.21.1",
  "core_count": 5,
  "core_min": 3,
  "core_max": 9,
  "deployment_type": "hetzner",
  "config_version": 6,
  "updated_at": 1711461000000
}
```

**Errors:**
- `404 Not Found`: No cluster config stored (cluster not bootstrapped via declarative config)

#### GET /api/cluster/status

Full cluster status including desired vs actual state.

**Response (200):**
```json
{
  "cluster_name": "production",
  "desired_version": "0.21.1",
  "desired_core_count": 5,
  "actual_core_count": 5,
  "state": "CONVERGED",
  "leader_id": "2SjKf4mVqR1bN8tW",
  "nodes": [
    {
      "node_id": "2SjKf4mVqR1bN8tW",
      "role": "core",
      "lifecycle_state": "ON_DUTY",
      "zone": "zone-a",
      "version": "0.21.1",
      "is_leader": true
    }
  ],
  "slices_deployed": 12,
  "slice_instances": 36,
  "certificate_expires_at": "2026-04-25T14:30:00Z",
  "certificate_days_remaining": 29,
  "auto_heal_enabled": true,
  "config_version": 6,
  "uptime_seconds": 86400
}
```

#### POST /api/cluster/scale

Adjust core node count.

**Request:**
```json
{
  "core_count": 7,
  "expected_version": 6
}
```

**Response (200):**
```json
{
  "success": true,
  "previous_count": 5,
  "new_count": 7,
  "config_version": 7
}
```

**Errors:**
- `400 Bad Request`: Quorum safety violation, invalid count
- `409 Conflict`: Version mismatch

#### POST /api/cluster/upgrade

Initiate a version upgrade.

**Request:**
```json
{
  "target_version": "0.22.0",
  "strategy": "rolling",
  "expected_version": 7
}
```

**Response (202 Accepted):**
```json
{
  "upgrade_id": "2SjKf4mVqR...",
  "from_version": "0.21.1",
  "to_version": "0.22.0",
  "strategy": "rolling",
  "status": "IN_PROGRESS"
}
```

#### GET /api/cluster/upgrade/{upgradeId}

Poll upgrade progress.

**Response (200):**
```json
{
  "upgrade_id": "2SjKf4mVqR...",
  "status": "IN_PROGRESS",
  "nodes_upgraded": 3,
  "nodes_total": 5,
  "current_node": "4UmMh6oXsT3dP0vY",
  "started_at": 1711461000000
}
```

### 14.2 Existing Endpoints Used

| Endpoint | Used By |
|----------|---------|
| `GET /api/status` | `status` command |
| `GET /api/nodes` | `status` command |
| `GET /api/health` | `status`, bootstrap health polling |
| `GET /health/ready` | Bootstrap quorum check |
| `GET /health/live` | Bootstrap liveness check |
| `GET /api/nodes/lifecycle` | `status` command |
| `GET /api/cluster/topology` | `status` command |
| `GET /api/certificate` | `status` command |
| `POST /api/node/drain/{nodeId}` | `drain`, `destroy`, `upgrade` commands |
| `POST /api/node/activate/{nodeId}` | `upgrade` command |
| `POST /api/node/shutdown/{nodeId}` | `destroy` command |
| `GET /api/slices` | `status` command |

### 14.3 Route Source

```java
/// Routes for declarative cluster configuration management.
public final class ClusterConfigRoutes implements RouteSource {
    private final Supplier<AetherNode> nodeSupplier;

    public static ClusterConfigRoutes clusterConfigRoutes(Supplier<AetherNode> nodeSupplier) {
        return new ClusterConfigRoutes(nodeSupplier);
    }

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(
            Route.post("/api/cluster/config").withBody(...).to(this::storeConfig).asJson(),
            Route.get("/api/cluster/config").toJson(this::getConfig),
            Route.get("/api/cluster/status").toJson(this::getClusterStatus),
            Route.post("/api/cluster/scale").withBody(...).to(this::scale).asJson(),
            Route.post("/api/cluster/upgrade").withBody(...).to(this::startUpgrade).asJson(),
            Route.get("/api/cluster/upgrade").withPath(aString()).to(this::getUpgrade).asJson()
        );
    }
}
```

Register in `ManagementServer` alongside existing route sources:
```java
ClusterConfigRoutes.clusterConfigRoutes(nodeSupplier)
```

### 14.4 RBAC Permissions

All new endpoints require OPERATOR or ADMIN role (existing RBAC Tier 1):

| Endpoint | Method | Required Role |
|----------|--------|---------------|
| `/api/cluster/config` | GET | VIEWER |
| `/api/cluster/config` | POST | OPERATOR |
| `/api/cluster/status` | GET | VIEWER |
| `/api/cluster/scale` | POST | OPERATOR |
| `/api/cluster/upgrade` | POST | ADMIN |
| `/api/cluster/upgrade/{id}` | GET | VIEWER |

---

## 15. Error Handling

### 15.1 Error Types

New sealed interface added to `aether-deployment` module:

```java
/// Errors specific to declarative cluster management operations.
public sealed interface ClusterConfigError extends Cause {

    record InvalidConfig(String field, String message) implements ClusterConfigError {
        @Override public String message() {
            return "Invalid cluster config: " + field + " -- " + message;
        }
    }

    record VersionConflict(long expected, long actual) implements ClusterConfigError {
        @Override public String message() {
            return "Config version conflict: expected " + expected + ", actual " + actual;
        }
    }

    record ClusterAlreadyExists(String name) implements ClusterConfigError {
        @Override public String message() {
            return "Cluster '" + name + "' already exists. Use 'apply' to modify or 'destroy' first.";
        }
    }

    record ClusterNotFound(String name) implements ClusterConfigError {
        @Override public String message() {
            return "Cluster '" + name + "' not found in registry.";
        }
    }

    record BootstrapFailed(String phase, int nodesProvisioned, int nodesTotal,
                           String detail) implements ClusterConfigError {
        @Override public String message() {
            return "Bootstrap failed at " + phase + " (" + nodesProvisioned + "/" + nodesTotal
                   + " nodes provisioned): " + detail;
        }
    }

    record QuorumSafetyViolation(int requested, int minimum) implements ClusterConfigError {
        @Override public String message() {
            return "Quorum safety violation: requested " + requested + " nodes, minimum is " + minimum;
        }
    }

    record ImmutableFieldChange(String field) implements ClusterConfigError {
        @Override public String message() {
            return "Field '" + field + "' is immutable after bootstrap. Destroy and re-bootstrap to change.";
        }
    }

    record UpgradeInProgress(String upgradeId) implements ClusterConfigError {
        @Override public String message() {
            return "Upgrade already in progress: " + upgradeId + ". Wait for completion or rollback.";
        }
    }

    record SecretResolutionFailed(String placeholder) implements ClusterConfigError {
        @Override public String message() {
            return "Failed to resolve secret: " + placeholder;
        }
    }

    record CloudCredentialsMissing(String provider, String envVar) implements ClusterConfigError {
        @Override public String message() {
            return provider + " credentials missing. Set environment variable: " + envVar;
        }
    }

    record ProvisionTimeout(String instanceId, long timeoutSeconds) implements ClusterConfigError {
        @Override public String message() {
            return "Instance " + instanceId + " did not become reachable within "
                   + timeoutSeconds + " seconds.";
        }
    }

    record QuorumTimeout(int healthyNodes, int requiredNodes,
                         long timeoutSeconds) implements ClusterConfigError {
        @Override public String message() {
            return "Quorum not established: " + healthyNodes + "/" + requiredNodes
                   + " healthy nodes after " + timeoutSeconds + " seconds.";
        }
    }

    // Seal completeness
    record unused() implements ClusterConfigError {
        @Override public String message() { return ""; }
    }
}
```

### 15.2 Failure Scenarios

#### 15.2.1 Partial Bootstrap Failure

| Phase | Failure Mode | Recovery |
|-------|-------------|----------|
| Provision (step 5) | 3 of 5 instances created | Retry: detects existing tagged instances, provisions remaining 2 |
| Network wait (step 6) | 1 instance unreachable after timeout | Print instance ID, suggest: terminate and retry |
| Quorum (step 8) | Only 2 of 5 nodes healthy after 10min | Print node-level health, suggest: check cloud console logs |
| KV-Store write (step 9) | Consensus not accepting writes | Retry automatically (3 attempts, 10s backoff) |
| API key generation (step 10) | Write failure | Retry automatically; if persistent, cluster is usable but key must be set manually |

#### 15.2.2 Apply Failure

| Phase | Failure Mode | Recovery |
|-------|-------------|----------|
| Config fetch | Network unreachable | Retry with backoff. CLI exits with connection error. |
| Version conflict | Concurrent modification | Re-fetch, re-compute diff, prompt user to confirm |
| Scale (via CTM) | CTM rejects (e.g., already reconciling) | Wait for CTM to reach CONVERGED, retry |
| Upgrade (rolling) | Node fails to rejoin after restart | Pause upgrade, print status, offer rollback |
| TLS rotation | Cert generation fails | Retry; if persistent, log and continue (old certs still valid) |

#### 15.2.3 Network Unreachable During Apply

REQ-ERR-01: If the management API becomes unreachable mid-apply:
1. CLI retries each HTTP call 3 times with exponential backoff (1s, 3s, 9s)
2. If all retries fail, print what was applied and what was not:
```
Applied 2 of 4 changes:
  [OK]      auto_heal.retry_interval: 60s -> 30s
  [OK]      core.min: 3 -> 5
  [FAILED]  core.count: 5 -> 7  (connection refused)
  [SKIPPED] version: 0.21.1 -> 0.22.0

Re-run 'aether cluster apply' when connectivity is restored.
```
3. The KV-Store config is NOT updated with the partial changes. Only a fully successful apply updates the stored config.

---

## 16. User-Data Template

### 16.1 Cloud-Init Script Template

```bash
#!/bin/bash
set -euo pipefail

# --- Aether Node Cloud-Init ---
# Generated by: aether cluster bootstrap
# Cluster: {{cluster_name}}
# Node ID: {{node_id}}
# Role: core

AETHER_VERSION="{{version}}"
AETHER_IMAGE="{{image}}"
AETHER_NODE_ID="{{node_id}}"
AETHER_CLUSTER_SECRET="{{cluster_secret}}"
AETHER_CLUSTER_PORT={{cluster_port}}
AETHER_MANAGEMENT_PORT={{management_port}}
AETHER_SWIM_PORT={{swim_port}}
AETHER_SEED_PEERS="{{seed_peers}}"
AETHER_TLS_ENABLED={{tls_enabled}}

# --- Install Docker (if not present) ---
if ! command -v docker &> /dev/null; then
    curl -fsSL https://get.docker.com | sh
fi

# --- Write Aether config ---
mkdir -p /opt/aether/config
cat > /opt/aether/config/aether.toml <<'AETHER_CONFIG'
[cluster]
environment = "docker"
nodes = {{core_count}}
tls = {{tls_enabled}}

[cluster.ports]
management = {{management_port}}
cluster = {{cluster_port}}

[node]
heap = "{{heap}}"
gc = "zgc"

{{#if tls_enabled}}
[tls]
cluster_secret = "{{cluster_secret}}"
{{/if}}
AETHER_CONFIG

# --- Pull and run ---
docker pull "${AETHER_IMAGE}"
docker run -d \
    --name aether-node \
    --restart unless-stopped \
    --network host \
    -e AETHER_NODE_ID="${AETHER_NODE_ID}" \
    -e AETHER_CLUSTER_SECRET="${AETHER_CLUSTER_SECRET}" \
    -e AETHER_SEED_PEERS="${AETHER_SEED_PEERS}" \
    -v /opt/aether/config:/config:ro \
    "${AETHER_IMAGE}" \
    --config /config/aether.toml

# --- Signal readiness ---
echo "Aether node ${AETHER_NODE_ID} starting on ports: cluster=${AETHER_CLUSTER_PORT}, mgmt=${AETHER_MANAGEMENT_PORT}"
```

### 16.2 Template Variables

| Variable | Source | Example |
|----------|--------|---------|
| `{{cluster_name}}` | `cluster.name` | `production` |
| `{{node_id}}` | Generated KSUID | `2SjKf4mVqR1bN8tW` |
| `{{version}}` | `cluster.version` | `0.21.1` |
| `{{image}}` | `deployment.runtime.image` | `ghcr.io/.../aether-node:0.21.1` |
| `{{cluster_secret}}` | Resolved from `deployment.tls.cluster_secret` | `base64-encoded-secret` |
| `{{cluster_port}}` | `deployment.ports.cluster` | `6000` |
| `{{management_port}}` | `deployment.ports.management` | `5150` |
| `{{swim_port}}` | `deployment.ports.swim` | `6100` |
| `{{seed_peers}}` | Comma-separated `host:port` of all other nodes | `10.0.1.2:6000,10.0.1.3:6000` |
| `{{tls_enabled}}` | `true` if `deployment.tls` section present | `true` |
| `{{core_count}}` | `cluster.core.count` | `5` |
| `{{heap}}` | Derived from instance size or explicit config | `2g` |

### 16.3 Provider-Specific Adaptations

| Provider | User-Data Mechanism | Notes |
|----------|-------------------|-------|
| Hetzner | `user_data` field in server create API | Max 32KB, base64-encoded |
| AWS | EC2 `UserData` | Max 16KB, base64-encoded; for larger scripts use S3 |
| GCP | `metadata.startup-script` | Max 256KB |
| Azure | `customData` on VM create | Max 64KB, base64-encoded |

REQ-TEMPLATE-01: The template is rendered at bootstrap time by the CLI. The rendered script is passed to `ProvisionSpec.withUserData(renderedScript)`.

REQ-TEMPLATE-02: For the two-phase bootstrap problem (seed-peers not known until all instances have IPs), use DiscoveryProvider-based discovery instead of static seed-peers. The cloud-init script configures the node to discover peers via cloud tags:
- Hetzner: label filter `aether-cluster=<name>`
- AWS: EC2 tag filter
- GCP: label filter
- Azure: tag filter

This eliminates the need for Step 7 (Update Seed Peers) in the bootstrap flow.

---

## 17. Implementation Layers

### Layer 1: Config Format + Parser + Validation + KV-Store Types

**Gate: Config can be parsed, validated, and round-tripped through TOML.**

| Component | Module | Description |
|-----------|--------|-------------|
| `ClusterManagementConfig` | `aether-config` | Record representing parsed `aether-cluster.toml` |
| `DeploymentConfig` | `aether-config` | Record for `[deployment]` section |
| `ClusterSpec` | `aether-config` | Record for `[cluster]` section |
| `ClusterConfigParser` | `aether-config` | TOML parser producing `ClusterManagementConfig` |
| `ClusterConfigValidator` | `aether-config` | Validates all rules VAL-01 through VAL-14 |
| `ClusterConfigKey` | `aether/slice` | New `AetherKey` variant |
| `ClusterConfigValue` | `aether/slice` | New `AetherValue` variant |
| `ClusterConfigError` | `aether-deployment` | Sealed error types |

**Deliverables:**
- Parse any valid `aether-cluster.toml` into structured types
- Validate all constraints
- Serialize/deserialize KV-Store types
- Unit tests for parsing, validation, round-trip

### Layer 2: CLI Framework (cluster subcommand group) + Registry

**Gate: `aether cluster list/use/remove` works against `~/.aether/clusters.toml`.**

| Component | Module | Description |
|-----------|--------|-------------|
| `ClusterCommand` | `aether/cli` | Picocli `@Command` group for `cluster` subcommands |
| `ClusterListCommand` | `aether/cli` | List registered clusters |
| `ClusterUseCommand` | `aether/cli` | Switch active context |
| `ClusterRemoveCommand` | `aether/cli` | Remove from registry |
| `ClusterRegistry` | `aether/cli` | Read/write `~/.aether/clusters.toml` |

**Deliverables:**
- `aether cluster list`, `cluster use`, `cluster remove` functional
- Registry file created/read/updated correctly
- `--endpoint` override works

### Layer 3: Status + Export (read-only, no provisioning)

**Gate: `aether cluster status` and `aether cluster export` return correct data.**

| Component | Module | Description |
|-----------|--------|-------------|
| `ClusterStatusCommand` | `aether/cli` | Aggregates data from multiple API endpoints |
| `ClusterExportCommand` | `aether/cli` | Fetches and prints `ClusterConfigValue.tomlContent` |
| `ClusterConfigRoutes` (partial) | `aether/node` | `GET /api/cluster/config`, `GET /api/cluster/status` |

**Deliverables:**
- `GET /api/cluster/config` and `GET /api/cluster/status` endpoints
- CLI `status` and `export` commands
- `--with-status` flag on export
- `--json` flag on status

### Layer 4: Apply (diff + converge via management API)

**Gate: `aether cluster apply` computes correct diff and executes actions.**

| Component | Module | Description |
|-----------|--------|-------------|
| `ClusterApplyCommand` | `aether/cli` | Parse, diff, confirm, execute |
| `ClusterConfigDiff` | `aether-config` | Computes field-level diff between two configs |
| `ClusterConfigRoutes` (complete) | `aether/node` | `POST /api/cluster/config`, `POST /api/cluster/scale` |

**Deliverables:**
- Diff algorithm with field-to-action matrix
- Dry-run mode
- Optimistic concurrency (version check)
- Immutability enforcement
- Apply execution for scale, auto-heal, and limit changes

### Layer 5: Scale + Upgrade (thin wrappers)

**Gate: `aether cluster scale` and `aether cluster upgrade` work end-to-end.**

| Component | Module | Description |
|-----------|--------|-------------|
| `ClusterScaleCommand` | `aether/cli` | Thin wrapper around apply |
| `ClusterUpgradeCommand` | `aether/cli` | Initiate and monitor rolling upgrade |
| Upgrade endpoint | `aether/node` | `POST /api/cluster/upgrade`, `GET /api/cluster/upgrade/{id}` |
| `NodeUpgradeOrchestrator` | `aether-deployment` | Coordinates per-node drain/update/restart/activate |

**Deliverables:**
- `scale --core N` with quorum safety
- `upgrade --version X.Y.Z` with rolling strategy
- Upgrade progress polling
- Version mismatch detection in status

### Layer 6: Bootstrap (cloud-init)

**Gate: `aether cluster bootstrap` provisions a cluster from zero to healthy.**

| Component | Module | Description |
|-----------|--------|-------------|
| `ClusterBootstrapCommand` | `aether/cli` | Orchestrates full bootstrap flow |
| `UserDataTemplate` | `aether/cli` | Renders cloud-init scripts |
| `BootstrapOrchestrator` | `aether/cli` | Steps 1-12 of bootstrap flow |
| `ApiKeyGenerator` | `aether/cli` | Generates and stores initial API key |

**Deliverables:**
- Full bootstrap flow for Hetzner (primary target)
- Cloud-init template rendering
- Health polling with timeout
- API key generation and storage
- Registry auto-registration
- Partial failure handling and retry

### Layer 7: Drain + Destroy

**Gate: `aether cluster destroy` tears down a cluster cleanly.**

| Component | Module | Description |
|-----------|--------|-------------|
| `ClusterDrainCommand` | `aether/cli` | Delegates to existing drain API |
| `ClusterDestroyCommand` | `aether/cli` | Full teardown sequence |

**Deliverables:**
- Interactive confirmation
- Parallel drain + terminate
- Registry cleanup
- Partial failure reporting

### Layer 8: On-Premises Bootstrap (SSH-based) -- Separate Ticket

**Gate: `aether cluster bootstrap` works with `deployment.type = "on-premises"`.**

| Component | Module | Description |
|-----------|--------|-------------|
| `SshBootstrapOrchestrator` | `aether/cli` | SSH-based node setup |
| `SystemdUnitTemplate` | `aether/cli` | Generates systemd service file |

**Deliverables:**
- SSH connection management
- JAR + config upload
- Service start/stop
- Health polling

### Layer Dependencies

```
Layer 1 ─────> Layer 2 ─────> Layer 3 ─────> Layer 4 ─────> Layer 5
                                                  |
                                                  v
                                              Layer 6 ─────> Layer 7
                                                                |
                                                                v
                                                            Layer 8
```

Layers 1-3 can be delivered independently. Layer 4 depends on 1-3. Layers 5-7 depend on 4. Layer 8 is independent of 6-7 (separate ticket).

---

## 18. Type Catalog

### 18.1 New Types (by module)

#### aether-config

| Type | Kind | Description |
|------|------|-------------|
| `ClusterManagementConfig` | record | Root type for `aether-cluster.toml` |
| `DeploymentConfig` | record | `[deployment]` section |
| `DeploymentType` | enum | `HETZNER, AWS, GCP, AZURE, KUBERNETES, ON_PREMISES, EMBEDDED` |
| `RuntimeConfig` | record | `[deployment.runtime]` |
| `RuntimeType` | enum | `CONTAINER, JVM` |
| `ZoneMapping` | record | `Map<String, String>` wrapper for zone mapping |
| `PortMapping` | record | `[deployment.ports]` (cluster, management, app-http, swim) |
| `TlsDeploymentConfig` | record | `[deployment.tls]` |
| `ClusterSpec` | record | `[cluster]` section |
| `CoreSpec` | record | `[cluster.core]` (count, min, max) |
| `WorkerSpec` | record | `[cluster.workers]` (count) |
| `DistributionConfig` | record | `[cluster.distribution]` (strategy, zones) |
| `DistributionStrategy` | enum | `BALANCED, MANUAL` |
| `AutoHealSpec` | record | `[cluster.auto_heal]` (enabled, retry_interval, startup_cooldown) |
| `UpgradeSpec` | record | `[cluster.upgrade]` (strategy) |
| `UpgradeStrategy` | enum | `ROLLING, BLUE_GREEN` |
| `ClusterConfigParser` | class | TOML to `ClusterManagementConfig` |
| `ClusterConfigValidator` | class | Validation logic |
| `ClusterConfigDiff` | record | Diff result between two configs |
| `ConfigChangeAction` | sealed interface | `Scale, UpdateLimits, UpdateAutoHeal, Upgrade, RotateCerts, NoOp, Rejected` |

#### aether/slice (kvstore)

| Type | Kind | Description |
|------|------|-------------|
| `ClusterConfigKey` | record (AetherKey variant) | Key for cluster config in KV-Store |
| `ClusterConfigValue` | record (AetherValue variant) | Value storing full config + extracted fields |

#### aether-deployment

| Type | Kind | Description |
|------|------|-------------|
| `ClusterConfigError` | sealed interface (Cause) | Error types for cluster management |
| `NodeUpgradeOrchestrator` | interface | Coordinates rolling node upgrades |

#### aether/node (routes)

| Type | Kind | Description |
|------|------|-------------|
| `ClusterConfigRoutes` | class (RouteSource) | REST endpoints for cluster config management |

#### aether/cli

| Type | Kind | Description |
|------|------|-------------|
| `ClusterCommand` | class (picocli) | Parent command group |
| `ClusterBootstrapCommand` | class (picocli) | Bootstrap subcommand |
| `ClusterApplyCommand` | class (picocli) | Apply subcommand |
| `ClusterStatusCommand` | class (picocli) | Status subcommand |
| `ClusterExportCommand` | class (picocli) | Export subcommand |
| `ClusterScaleCommand` | class (picocli) | Scale subcommand |
| `ClusterUpgradeCommand` | class (picocli) | Upgrade subcommand |
| `ClusterDrainCommand` | class (picocli) | Drain subcommand |
| `ClusterDestroyCommand` | class (picocli) | Destroy subcommand |
| `ClusterListCommand` | class (picocli) | List clusters subcommand |
| `ClusterUseCommand` | class (picocli) | Switch context subcommand |
| `ClusterRemoveCommand` | class (picocli) | Remove from registry subcommand |
| `ClusterRegistry` | class | Manages `~/.aether/clusters.toml` |
| `BootstrapOrchestrator` | class | Orchestrates bootstrap steps |
| `UserDataTemplate` | class | Cloud-init script renderer |
| `ApiKeyGenerator` | class | Generates random API keys |

### 18.2 Modified Types

| Type | Module | Change |
|------|--------|--------|
| `AetherKey` | `aether/slice` | Add `ClusterConfigKey` variant |
| `AetherValue` | `aether/slice` | Add `ClusterConfigValue` variant |
| `AetherCli` | `aether/cli` | Add `ClusterCommand` to subcommands list |
| `ManagementServer` | `aether/node` | Register `ClusterConfigRoutes` |
| `RoutePermissionRegistry` | `aether/node` | Add permissions for new endpoints |

---

## 19. References

### Technical Documentation

- [ComputeProvider SPI](/aether/environment-integration/src/main/java/org/pragmatica/aether/environment/ComputeProvider.java) -- Cloud instance provisioning interface
- [DiscoveryProvider SPI](/aether/environment-integration/src/main/java/org/pragmatica/aether/environment/DiscoveryProvider.java) -- Tag-based peer discovery
- [SecretsProvider SPI](/aether/environment-integration/src/main/java/org/pragmatica/aether/environment/SecretsProvider.java) -- Secret resolution for `${secrets:xxx}`
- [ClusterTopologyManager](/aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/cluster/ClusterTopologyManager.java) -- Node count reconciliation state machine
- [NodeLifecycleManager](/aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/cluster/NodeLifecycleManager.java) -- Cloud instance lifecycle operations
- [RollingUpdateManager](/aether/aether-invoke/src/main/java/org/pragmatica/aether/update/RollingUpdateManager.java) -- Two-stage rolling update orchestration
- [AetherKey](/aether/slice/src/main/java/org/pragmatica/aether/slice/kvstore/AetherKey.java) -- KV-Store key types
- [AetherValue](/aether/slice/src/main/java/org/pragmatica/aether/slice/kvstore/AetherValue.java) -- KV-Store value types
- [AetherConfig](/aether/aether-config/src/main/java/org/pragmatica/aether/config/AetherConfig.java) -- Existing runtime configuration
- [ClusterConfig](/aether/aether-config/src/main/java/org/pragmatica/aether/config/ClusterConfig.java) -- Existing cluster configuration
- [ProvisionSpec](/aether/environment-integration/src/main/java/org/pragmatica/aether/environment/ProvisionSpec.java) -- Instance provisioning specification with userData support
- [AutoHealConfig](/aether/environment-integration/src/main/java/org/pragmatica/aether/environment/AutoHealConfig.java) -- Auto-heal configuration record
- [NodeReconcilerState](/aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/cluster/NodeReconcilerState.java) -- Reconciliation state machine: INACTIVE, FORMING, CONVERGED, RECONCILING
- [CertificateProvider](/integrations/net/tcp/src/main/java/org/pragmatica/net/tcp/security/CertificateProvider.java) -- Node certificate issuance
- [HetznerComputeProvider](/aether/environment/hetzner/src/main/java/org/pragmatica/aether/environment/hetzner/HetznerComputeProvider.java) -- Hetzner cloud implementation
- [CloudConfig](/aether/environment-integration/src/main/java/org/pragmatica/aether/environment/CloudConfig.java) -- Generic cloud provider configuration
- [EnvironmentError](/aether/environment-integration/src/main/java/org/pragmatica/aether/environment/EnvironmentError.java) -- Environment integration error types
- [ManagementServer](/aether/node/src/main/java/org/pragmatica/aether/api/ManagementServer.java) -- HTTP management API server
- [StatusRoutes](/aether/node/src/main/java/org/pragmatica/aether/api/routes/StatusRoutes.java) -- Existing status/health endpoints
- [ClusterTopologyRoutes](/aether/node/src/main/java/org/pragmatica/aether/api/routes/ClusterTopologyRoutes.java) -- Existing topology endpoints
- [NodeLifecycleRoutes](/aether/node/src/main/java/org/pragmatica/aether/api/routes/NodeLifecycleRoutes.java) -- Existing drain/activate/shutdown endpoints
- [AetherCli](/aether/cli/src/main/java/org/pragmatica/aether/cli/AetherCli.java) -- CLI with picocli, 29 existing subcommands

### Internal References

- [Management API docs](/aether/docs/reference/management-api.md) -- REST API reference
- [CLI docs](/aether/docs/reference/cli.md) -- CLI command reference
- [Feature catalog](/aether/docs/reference/feature-catalog.md) -- Feature inventory
- [RBAC spec](/aether/docs/specs/rbac-spec.md) -- Role-based access control

### External Standards

- [TOML Specification](https://toml.io/en/v1.0.0) -- Configuration file format
- [Cloud-init Documentation](https://cloudinit.readthedocs.io/) -- Cloud instance initialization
- [Hetzner Cloud API](https://docs.hetzner.cloud/) -- Server provisioning
- [AWS EC2 User Data](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/user-data.html) -- EC2 instance initialization

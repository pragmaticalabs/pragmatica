# Cross-Environment Fluid Migration Specification

**Version:** 1.0
**Status:** Implementation-Ready
**Target Release:** 0.23.0+
**Last Updated:** 2026-03-27

---

## Table of Contents

1. [Overview](#1-overview)
2. [Migration Scenarios](#2-migration-scenarios)
3. [Prerequisites](#3-prerequisites)
4. [Fluid Migration Protocol](#4-fluid-migration-protocol)
5. [NodeLifecycleValue Enhancement](#5-nodelifecyclevalue-enhancement)
6. [DNS Provider SPI](#6-dns-provider-spi)
7. [Configuration](#7-configuration)
8. [CLI Commands](#8-cli-commands)
9. [Rollback](#9-rollback)
10. [Cross-Cloud Networking Requirements](#10-cross-cloud-networking-requirements)
11. [Monitoring During Migration](#11-monitoring-during-migration)
12. [Error Model](#12-error-model)
13. [Operator Runbook](#13-operator-runbook)
14. [Implementation Layers](#14-implementation-layers)
15. [Open Questions](#15-open-questions)
16. [References](#16-references)

---

## 1. Overview

### 1.1 Motivation

Aether clusters currently run within a single cloud provider. Migrating between providers (AWS to GCP), between environments (cloud to on-premises), or between regions within the same provider requires manual orchestration with significant downtime risk. Aether's architecture -- Rabia consensus over QUIC/UDP, shared `clusterSecret` for auto-TLS, and CDM-driven slice distribution -- already supports heterogeneous node membership. The missing piece is tooling and protocol to orchestrate the transition.

### 1.2 Core Insight

Aether nodes are cloud-agnostic. A node on AWS and a node on GCP can participate in the same consensus group as long as they share the same `clusterSecret` and have UDP connectivity. Migration is therefore not a "move" operation but a "expand, redistribute, shrink" operation:

1. Add target-environment nodes to the existing cluster
2. Let CDM redistribute slices across all nodes (source + target)
3. Drain and terminate source-environment nodes

This is **fluid migration**: the cluster flows from one environment to another without interruption.

### 1.3 Goals

- **G-1:** Zero-downtime migration between any two supported environments
- **G-2:** Composable from existing primitives (`scale`, `drain`, `destroy`) or as a single `migrate` command
- **G-3:** Rollback at any point during migration
- **G-4:** Provider-aware node tracking via `NodeLifecycleValue.provider` field
- **G-5:** DNS Provider SPI for automated DNS cutover
- **G-6:** Observable migration progress via metrics and management API
- **G-7:** JBCT compliance: sealed interfaces, records, `Promise<T>`, no business exceptions

### 1.4 Non-Goals

- Multi-region federation (nodes in different regions serving different data partitions)
- Live state transfer (Rabia consensus handles state replication natively)
- Kubernetes-to-Kubernetes migration (handled by K8s operators)
- Automated cross-cloud VPN setup (operator responsibility)

### 1.5 Design Principles

- REQ-DESIGN-01: Migration builds on existing primitives. No new consensus protocol changes.
- REQ-DESIGN-02: The `[deployment]` section in `aether-cluster.toml` is per-provider. Migration uses two deployment sections.
- REQ-DESIGN-03: All migration state is persisted in KV-Store for leader failover recovery.
- REQ-DESIGN-04: Disruption budget is respected at every step. Never drain more nodes than the budget allows.
- REQ-DESIGN-05: DNS cutover is a separate, optional phase. Migration works without DNS automation.

---

## 2. Migration Scenarios

### 2.1 Supported Scenarios

| ID | Scenario | Source | Target | Example |
|----|----------|--------|--------|---------|
| MS-01 | Cloud to Cloud | Any cloud provider | Any cloud provider | AWS us-east-1 to GCP europe-west1 |
| MS-02 | Cloud to On-Premises | Any cloud provider | Bare metal / VM | AWS to private datacenter |
| MS-03 | On-Premises to Cloud | Bare metal / VM | Any cloud provider | Datacenter to Azure |
| MS-04 | On-Premises to On-Premises | Datacenter A | Datacenter B | DC migration |
| MS-05 | Same-Cloud Region Migration | Provider region A | Provider region B | AWS us-east-1 to AWS eu-west-1 |
| MS-06 | Same-Cloud Instance Upgrade | Old instance type | New instance type | Hetzner cx21 to cx31 |

### 2.2 Scenario Characteristics

| Scenario | Cross-Cloud Network Required | DNS Change Required | Typical Latency During Transition |
|----------|------------------------------|--------------------|------------------------------------|
| MS-01 | Yes (VPN/peering/public) | Yes | 50-200ms (cross-region) |
| MS-02 | Yes (VPN to DC) | Yes | 10-100ms (depends on proximity) |
| MS-03 | Yes (VPN to DC) | Yes | 10-100ms |
| MS-04 | Yes (DC-to-DC link) | Possibly | 1-50ms (LAN-like) |
| MS-05 | No (same VPC/peering) | Yes | 20-100ms (cross-region) |
| MS-06 | No (same region) | No | <5ms |

### 2.3 Latency Considerations for Consensus

Rabia consensus tolerates variable latency but performance degrades with high RTT:

| Cross-node RTT | Consensus Impact | Recommendation |
|----------------|-----------------|----------------|
| <10ms | Nominal | No special handling |
| 10-50ms | Slight throughput reduction | Acceptable for migration window |
| 50-200ms | Noticeable throughput reduction | Minimize migration window duration |
| >200ms | Significant impact, possible timeouts | NOT RECOMMENDED -- use smaller batches, increase timeouts |

[ASSUMPTION] Operators accept temporary throughput reduction during the migration window when source and target nodes span high-latency links.

---

## 3. Prerequisites

### 3.1 Network Connectivity

| Requirement | Port | Protocol | Direction | Purpose |
|-------------|------|----------|-----------|---------|
| REQ-NET-01 | Cluster port (default 6000) | UDP (QUIC) | Bidirectional | Consensus + data replication |
| REQ-NET-02 | SWIM port (default 6100) | UDP | Bidirectional | Failure detection |
| REQ-NET-03 | Management port (default 5150) | TCP (HTTP) | CLI to any node | Management API |

All three ports must be open between every source node and every target node before migration begins.

### 3.2 TLS and Identity

- REQ-TLS-01: Same `clusterSecret` on all nodes (source and target). The shared secret derives the same CA, so auto-TLS works cross-cloud without certificate exchange.
- REQ-TLS-02: Target nodes must be bootstrapped with the same `clusterSecret` value. This is typically configured via `deployment.tls.cluster_secret` in the cluster config.

### 3.3 Credentials

- REQ-CRED-01: CLI must have credentials for **both** source and target environments during migration.
- REQ-CRED-02: For cloud providers, credentials are resolved via environment variables or the `SecretsProvider` SPI.
- REQ-CRED-03: Credential mapping:

| Provider | Credentials |
|----------|-------------|
| Hetzner | `HETZNER_API_TOKEN` |
| AWS | `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION` |
| GCP | `GOOGLE_APPLICATION_CREDENTIALS` or `gcloud` auth |
| Azure | `AZURE_SUBSCRIPTION_ID`, `AZURE_TENANT_ID`, `AZURE_CLIENT_ID`, `AZURE_CLIENT_SECRET` |
| On-Premises | SSH key for target hosts |

### 3.4 DNS (Optional)

- REQ-DNS-01: If automated DNS cutover is desired, the `DnsProvider` facet must be available for the DNS hosting provider.
- REQ-DNS-02: DNS TTL should be lowered before migration to minimize stale-record impact. Recommended: 60s TTL, set at least 24h before migration.

### 3.5 Cluster Health

- REQ-HEALTH-01: All source nodes must be ON_DUTY before migration starts.
- REQ-HEALTH-02: No active rolling updates or deployments in progress.
- REQ-HEALTH-03: Quorum must be healthy (no ongoing reconciliation).

---

## 4. Fluid Migration Protocol

### 4.1 State Machine

```
IDLE -> PRE_FLIGHT -> PROVISIONING -> JOINING -> REDISTRIBUTING
     -> VERIFYING -> DRAINING -> TERMINATING -> DNS_CUTOVER -> CLEANUP -> COMPLETED
```

Each state transition is recorded in KV-Store as a `MigrationStateValue`.

### 4.2 Detailed Steps

#### Step 1: PRE_FLIGHT

| ID | Check | Failure Action |
|----|-------|---------------|
| PF-01 | Source cluster healthy (all nodes ON_DUTY) | Abort with diagnostic |
| PF-02 | No active deployments or rolling updates | Abort |
| PF-03 | Target environment credentials valid | Abort |
| PF-04 | Cross-cloud connectivity test (UDP probe to target network) | Abort with network diagnostic |
| PF-05 | DNS provider available (if DNS cutover requested) | Warn (migration proceeds without DNS) |
| PF-06 | Source and target `clusterSecret` match | Abort |
| PF-07 | Disruption budget allows at least 1 node drain | Abort |
| PF-08 | Sufficient capacity in target environment | Abort (provider quota check) |

CLI output:
```
$ aether cluster migrate --from aws --to gcp --config migration.toml

Pre-flight checks:
  [OK] Source cluster healthy (5/5 nodes ON_DUTY)
  [OK] No active deployments
  [OK] GCP credentials valid (project: my-project)
  [OK] Cross-cloud connectivity (5 nodes reachable from GCP network)
  [OK] DNS provider available (Cloud DNS, zone: example.com)
  [OK] Cluster secret matches
  [OK] Disruption budget: 2 nodes (40%)
  [OK] GCP quota sufficient (5x e2-medium in europe-west1)

All pre-flight checks passed. Proceed with migration? [y/N]
```

#### Step 2: PROVISIONING

Provision target nodes using the target environment's `ComputeProvider`:

- REQ-PROV-01: Provision `cluster.core.count` nodes in the target environment.
- REQ-PROV-02: Each node is tagged with `aether-cluster=<cluster-name>`, `aether-role=core`, `aether-migration=<migration-id>`, and `aether-provider=<target-provider>`.
- REQ-PROV-03: Target nodes are configured with the same `clusterSecret` and seed peers pointing to existing source nodes.
- REQ-PROV-04: Provisioning uses the target `[deployment]` section for instance type, zones, and runtime config.

```
Provisioning 5 GCP nodes (e2-medium, europe-west1)...
  [1/5] gcp-node-a1 (10.0.1.10) ... OK
  [2/5] gcp-node-a2 (10.0.1.11) ... OK
  [3/5] gcp-node-b1 (10.0.2.10) ... OK
  [4/5] gcp-node-b2 (10.0.2.11) ... OK
  [5/5] gcp-node-c1 (10.0.3.10) ... OK
All target nodes provisioned.
```

#### Step 3: JOINING

Target nodes join the existing cluster via QUIC:

- REQ-JOIN-01: Each target node connects to seed peers (source nodes) and participates in Rabia consensus.
- REQ-JOIN-02: CTM (ClusterTopologyManager) detects new nodes via SWIM and updates topology.
- REQ-JOIN-03: Wait until all target nodes report `ON_DUTY` in KV-Store `NodeLifecycleValue`.
- REQ-JOIN-04: Timeout: `verify_timeout` (default 120s). If not all nodes join, abort and offer rollback.

```
Waiting for target nodes to join cluster...
  gcp-node-a1: JOINING -> ON_DUTY (12s)
  gcp-node-a2: JOINING -> ON_DUTY (14s)
  gcp-node-b1: JOINING -> ON_DUTY (11s)
  gcp-node-b2: JOINING -> ON_DUTY (13s)
  gcp-node-c1: JOINING -> ON_DUTY (15s)
All 5 target nodes are ON_DUTY. Cluster size: 10 (5 AWS + 5 GCP)
```

#### Step 4: REDISTRIBUTING

CDM redistributes slices across all nodes:

- REQ-REDIST-01: CDM's desired size is updated to include all nodes (source + target).
- REQ-REDIST-02: CDM automatically rebalances slices. No manual intervention needed.
- REQ-REDIST-03: Wait for rebalance to complete (all slices have replicas on target nodes).
- REQ-REDIST-04: The migration does NOT need to wait for full rebalance. Once target nodes hold sufficient replica coverage, draining can begin.

#### Step 5: VERIFYING

- REQ-VERIFY-01: All target nodes healthy (ON_DUTY, serving traffic).
- REQ-VERIFY-02: Slice distribution includes target nodes.
- REQ-VERIFY-03: Application-level health checks pass (if configured).
- REQ-VERIFY-04: Metrics show requests being served by target nodes.
- REQ-VERIFY-05: Verification duration: configurable `verify_duration` (default 60s) to observe target node stability.

```
Verifying target nodes (60s observation window)...
  Request distribution: AWS 52%, GCP 48%
  Error rate: 0.01% (within threshold)
  P99 latency: 12ms AWS, 45ms GCP (cross-region expected)
  All health checks passing.
Verification passed.
```

#### Step 6: DRAINING

Drain source nodes one at a time, respecting disruption budget:

- REQ-DRAIN-01: Drain nodes sequentially using existing `POST /api/node/drain/{nodeId}` endpoint.
- REQ-DRAIN-02: Respect disruption budget: never drain more nodes simultaneously than the budget allows.
- REQ-DRAIN-03: Wait for each node to reach `DECOMMISSIONED` before draining the next.
- REQ-DRAIN-04: Timeout per node: `drain_timeout` (default 300s).
- REQ-DRAIN-05: If drain fails for a node, pause and offer: continue, skip, or rollback.
- REQ-DRAIN-06: Batch mode: drain `batch_size` nodes per step (default 1). Each batch respects the disruption budget independently.

```
Draining source nodes (strategy: rolling, batch_size: 1)...
  [1/5] aws-node-1: DRAINING -> DECOMMISSIONED (45s)
  [2/5] aws-node-2: DRAINING -> DECOMMISSIONED (38s)
  [3/5] aws-node-3: DRAINING -> DECOMMISSIONED (42s)
  [4/5] aws-node-4: DRAINING -> DECOMMISSIONED (40s)
  [5/5] aws-node-5: DRAINING -> DECOMMISSIONED (41s)
All source nodes drained. Cluster size: 5 (0 AWS + 5 GCP)
```

#### Step 7: TERMINATING

Terminate source cloud instances:

- REQ-TERM-01: Terminate each decommissioned source instance via the source `ComputeProvider`.
- REQ-TERM-02: Removal is idempotent. If instance already terminated, succeed silently.
- REQ-TERM-03: Update KV-Store to remove source node lifecycle entries.

#### Step 8: DNS_CUTOVER (Optional)

If DNS automation is configured:

- REQ-DNS-CUT-01: Update DNS records to point to target node IPs.
- REQ-DNS-CUT-02: Wait for DNS propagation (configurable `dns_propagation_wait`, default 120s).
- REQ-DNS-CUT-03: Verify DNS resolution returns target IPs.

#### Step 9: CLEANUP

- REQ-CLEAN-01: Remove migration state from KV-Store.
- REQ-CLEAN-02: Update cluster config in KV-Store to reflect target deployment.
- REQ-CLEAN-03: Remove `aether-migration` tags from target nodes (they are now the primary nodes).
- REQ-CLEAN-04: Update load balancer configuration if `LoadBalancerProvider` is available.
- REQ-CLEAN-05: Update local `aether-cluster.toml` with target deployment section.

### 4.3 Migration Strategies

| Strategy | Description | Use Case |
|----------|-------------|----------|
| `rolling` | Drain source nodes one at a time | Default. Safest, slowest. |
| `batch` | Drain `batch_size` nodes per step | Faster for large clusters. |
| `canary` | Drain 1 node, verify for `canary_duration`, then drain rest | Extra caution for critical workloads. |

---

## 5. NodeLifecycleValue Enhancement

### 5.1 Current Definition

```java
record NodeLifecycleValue(NodeLifecycleState state,
                          long updatedAt,
                          String host,
                          int port) implements AetherValue
```

### 5.2 Enhanced Definition

```java
/// @param state     the current lifecycle state
/// @param updatedAt timestamp of last state transition
/// @param host      the node's cluster address host
/// @param port      the node's cluster address port
/// @param provider  the environment provider identifier (e.g., "aws", "gcp", "hetzner", "on-premises")
record NodeLifecycleValue(NodeLifecycleState state,
                          long updatedAt,
                          String host,
                          int port,
                          String provider) implements AetherValue {

    /// Compact constructor: normalize nulls for backward compatibility.
    public NodeLifecycleValue {
        if (host == null) {
            host = "";
        }
        if (provider == null) {
            provider = "";
        }
    }

    /// Creates a new lifecycle value with current timestamp and provider.
    public static NodeLifecycleValue nodeLifecycleValue(NodeLifecycleState state,
                                                         String host,
                                                         int port,
                                                         String provider) {
        return new NodeLifecycleValue(state, System.currentTimeMillis(), host, port, provider);
    }

    /// Backward-compatible factory (no provider).
    public static NodeLifecycleValue nodeLifecycleValue(NodeLifecycleState state) {
        return new NodeLifecycleValue(state, System.currentTimeMillis(), "", 0, "");
    }

    /// Backward-compatible factory (no provider, with timestamp).
    public static NodeLifecycleValue nodeLifecycleValue(NodeLifecycleState state, long updatedAt) {
        return new NodeLifecycleValue(state, updatedAt, "", 0, "");
    }

    /// Backward-compatible factory (no provider, with address).
    public static NodeLifecycleValue nodeLifecycleValue(NodeLifecycleState state,
                                                         String host,
                                                         int port) {
        return new NodeLifecycleValue(state, System.currentTimeMillis(), host, port, "");
    }

    /// Returns true if this value carries a valid cluster address.
    public boolean hasAddress() {
        return !host.isEmpty() && port > 0;
    }

    /// Returns true if this value carries a provider identifier.
    public boolean hasProvider() {
        return !provider.isEmpty();
    }

    /// Returns a new value with updated state, preserving address and provider.
    public NodeLifecycleValue withState(NodeLifecycleState newState) {
        return new NodeLifecycleValue(newState, System.currentTimeMillis(), host, port, provider);
    }
}
```

### 5.3 Serialization Compatibility

- REQ-SER-01: The `provider` field defaults to `""` when deserialized from old format (Fory handles missing fields as defaults).
- REQ-SER-02: Old nodes reading new format ignore the unknown `provider` field gracefully.
- REQ-SER-03: Existing `KVStoreSerializer` tests must be updated to cover the new field.

### 5.4 Provider Identifiers

Canonical provider identifiers (matches `deployment.type` values):

| Identifier | Environment |
|------------|-------------|
| `hetzner` | Hetzner Cloud |
| `aws` | Amazon Web Services |
| `gcp` | Google Cloud Platform |
| `azure` | Microsoft Azure |
| `digitalocean` | DigitalOcean |
| `on-premises` | Bare metal / self-managed VMs |
| `embedded` | Forge/Ember (local development) |
| `""` | Unknown / legacy node |

---

## 6. DNS Provider SPI

### 6.1 New Facet on EnvironmentIntegration

```java
public interface EnvironmentIntegration {
    Option<ComputeProvider> compute();
    Option<SecretsProvider> secrets();
    Option<LoadBalancerProvider> loadBalancer();
    Option<DiscoveryProvider> discovery();

    /// Returns the DNS provider facet, if supported.
    default Option<DnsProvider> dns() {
        return Option.empty();
    }
}
```

### 6.2 DnsProvider Interface

```java
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;

/// SPI for managing DNS records.
/// Implementations integrate with cloud DNS services (Route53, Cloud DNS, Azure DNS)
/// or self-hosted DNS (e.g., PowerDNS API).
public interface DnsProvider {

    /// Create or update a DNS record.
    Promise<Unit> upsertRecord(DnsRecord record);

    /// Delete a DNS record.
    Promise<Unit> deleteRecord(DnsRecordId recordId);

    /// List all records in a zone matching optional filters.
    Promise<List<DnsRecord>> listRecords(String zone);

    /// List records matching a specific name and type.
    Promise<List<DnsRecord>> listRecords(String zone, String name, DnsRecordType type);
}
```

### 6.3 DNS Types

```java
/// Unique identifier for a DNS record (provider-specific).
public record DnsRecordId(String value) {
    public static Result<DnsRecordId> dnsRecordId(String value) {
        return Result.success(new DnsRecordId(value));
    }
}

/// A DNS record.
public record DnsRecord(DnsRecordId id,
                         String zone,
                         String name,
                         DnsRecordType type,
                         String value,
                         int ttl) {
    public static Result<DnsRecord> dnsRecord(String zone,
                                               String name,
                                               DnsRecordType type,
                                               String value,
                                               int ttl) {
        return Result.success(new DnsRecord(DnsRecordId.dnsRecordId("").unwrap(),
                                             zone, name, type, value, ttl));
    }
}

/// Supported DNS record types.
public enum DnsRecordType {
    A,
    AAAA,
    CNAME,
    SRV
}
```

### 6.4 Provider Implementations

| Provider | DNS Service | Module |
|----------|------------|--------|
| AWS | Route 53 | `aether/environment/aws` |
| GCP | Cloud DNS | `aether/environment/gcp` |
| Azure | Azure DNS | `aether/environment/azure` |
| Hetzner | Hetzner DNS | `aether/environment/hetzner` |
| On-Premises | PowerDNS / RFC 2136 | `aether/environment/on-premises` (future) |

### 6.5 DNS Error Types

Added to `EnvironmentError`:

```java
record DnsUpdateFailed(String zone, String name, Throwable cause) implements EnvironmentError {
    @Override
    public String message() {
        return "DNS update failed for " + name + " in zone " + zone + ": " + cause.getMessage();
    }
}

record DnsPropagationTimeout(String name, String expectedValue) implements EnvironmentError {
    @Override
    public String message() {
        return "DNS propagation timeout: " + name + " did not resolve to " + expectedValue;
    }
}
```

---

## 7. Configuration

### 7.1 Migration-Specific Config

```toml
# migration.toml -- migration configuration overlay

[migration]
source = "aws"                    # REQ-MIG-CFG-01: source provider identifier
target = "gcp"                    # REQ-MIG-CFG-02: target provider identifier
strategy = "rolling"              # REQ-MIG-CFG-03: rolling | batch | canary
batch_size = 1                    # REQ-MIG-CFG-04: nodes to drain per step (for batch/rolling)
verify_timeout = "120s"           # REQ-MIG-CFG-05: wait for target nodes to become ON_DUTY
verify_duration = "60s"           # REQ-MIG-CFG-06: observation window after all target nodes join
drain_timeout = "300s"            # REQ-MIG-CFG-07: max drain time per node
canary_duration = "300s"          # REQ-MIG-CFG-08: canary observation time (only for canary strategy)

[migration.dns]
enabled = true                    # REQ-MIG-CFG-09: enable automated DNS cutover
zone = "example.com"              # REQ-MIG-CFG-10: DNS zone to update
records = ["cluster.example.com"] # REQ-MIG-CFG-11: record names to update
ttl = 60                          # REQ-MIG-CFG-12: TTL for updated records
propagation_wait = "120s"         # REQ-MIG-CFG-13: wait time for DNS propagation

[migration.rollback]
auto_rollback = true              # REQ-MIG-CFG-14: auto-rollback on verification failure
keep_source_instances = true      # REQ-MIG-CFG-15: do not terminate source until explicit confirm

# Target deployment configuration (same schema as [deployment])
[migration.target]
type = "gcp"

[migration.target.instances]
core = "e2-medium"

[migration.target.runtime]
type = "container"
image = "gcr.io/my-project/aether-node:0.21.1"

[migration.target.zones]
zone-a = "europe-west1-b"
zone-b = "europe-west1-c"
zone-c = "europe-west1-d"

[migration.target.ports]
cluster = 6000
management = 5150

[migration.target.tls]
auto_generate = true
cluster_secret = "${secrets:cluster-secret}"

[migration.target.gcp]
project_id = "my-project"
network = "default"
subnetwork = "aether-subnet"
```

### 7.2 Composable Approach (No Migration Config)

For operators who prefer composing from existing commands, no migration-specific config is needed. The existing `aether-cluster.toml` is used with `--provider` flags:

```bash
# Step 1: Create a second cluster config for target environment
# Step 2: Scale up target, scale down source using existing commands
aether cluster scale --provider gcp --count 5 --config target-cluster.toml
aether cluster drain --provider aws
aether cluster destroy --provider aws
```

### 7.3 Validation Rules

| Rule ID | Field | Constraint | Error |
|---------|-------|-----------|-------|
| MIG-VAL-01 | `migration.source` | Must match a known provider identifier | `InvalidSourceProvider` |
| MIG-VAL-02 | `migration.target` | Must match a known provider identifier | `InvalidTargetProvider` |
| MIG-VAL-03 | `migration.source` != `migration.target` (unless region migration) | Source and target must differ | `SameSourceAndTarget` |
| MIG-VAL-04 | `migration.strategy` | One of: `rolling`, `batch`, `canary` | `InvalidMigrationStrategy` |
| MIG-VAL-05 | `migration.batch_size` | >= 1, <= `cluster.core.count` | `InvalidBatchSize` |
| MIG-VAL-06 | `migration.target` section | Must pass all standard `[deployment]` validation rules | Standard validation errors |
| MIG-VAL-07 | `migration.dns.zone` | Required when `migration.dns.enabled = true` | `MissingDnsZone` |

---

## 8. CLI Commands

### 8.1 Composite Migration Command

```
aether cluster migrate --config <migration.toml> [--dry-run] [--yes] [--json]
```

**Options:**

| Flag | Description | Default |
|------|-------------|---------|
| `--config` | Path to migration config file | Required |
| `--dry-run` | Run pre-flight checks only, do not execute | `false` |
| `--yes` | Skip interactive confirmation | `false` |
| `--json` | Output raw JSON progress | `false` |

**Example:**

```
$ aether cluster migrate --config migration.toml

Pre-flight checks:
  [OK] Source cluster healthy (5/5 nodes ON_DUTY)
  [OK] No active deployments
  [OK] GCP credentials valid
  [OK] Cross-cloud connectivity verified
  [OK] Disruption budget: 2 nodes

Migration plan:
  Source: aws (5 nodes in us-east-1)
  Target: gcp (5 nodes in europe-west1)
  Strategy: rolling (batch_size: 1)
  DNS cutover: cluster.example.com -> GCP IPs

Proceed? [y/N] y

Phase 1: Provisioning target nodes...
  [5/5] provisioned

Phase 2: Waiting for target nodes to join cluster...
  [5/5] ON_DUTY (cluster size: 10)

Phase 3: Verifying target nodes (60s)...
  Passed.

Phase 4: Draining source nodes...
  [5/5] decommissioned (cluster size: 5)

Phase 5: Terminating source instances...
  [5/5] terminated

Phase 6: DNS cutover...
  Updated cluster.example.com -> [10.0.1.10, 10.0.1.11, 10.0.2.10, 10.0.2.11, 10.0.3.10]
  Waiting for DNS propagation (120s)...
  Verified.

Phase 7: Cleanup...
  Migration state cleared.
  Cluster config updated.

Migration completed successfully.
  Duration: 14m 32s
  Source: aws (terminated)
  Target: gcp (5 nodes, all ON_DUTY)
```

### 8.2 Migration Status

```
aether cluster migrate --status [--json]
```

**Example:**

```
$ aether cluster migrate --status

Migration: aws -> gcp
  ID: mig-20260327-143200
  State: DRAINING
  Progress: 3/5 source nodes drained
  Source nodes: 2 ON_DUTY, 3 DECOMMISSIONED
  Target nodes: 5 ON_DUTY
  Cluster size: 7
  Started: 2026-03-27 14:32:00 UTC
  Elapsed: 8m 15s
```

### 8.3 Migration Rollback

```
aether cluster migrate --rollback [--yes]
```

**Example:**

```
$ aether cluster migrate --rollback

Current migration: aws -> gcp (state: DRAINING, 3/5 drained)

Rollback plan:
  - Reactivate 3 decommissioned AWS nodes (if still running)
  - Drain 5 GCP target nodes
  - Terminate GCP instances
  - Restore original cluster config

Proceed with rollback? [y/N] y

Reactivating source nodes...
  aws-node-1: DECOMMISSIONED -> ON_DUTY
  aws-node-2: DECOMMISSIONED -> ON_DUTY
  aws-node-3: DECOMMISSIONED -> ON_DUTY

Draining target nodes...
  [5/5] decommissioned

Terminating target instances...
  [5/5] terminated

Rollback complete. Cluster restored to original state.
  Cluster size: 5 (5 AWS, 0 GCP)
```

### 8.4 Provider-Aware Existing Commands

Enhancement to existing commands with `--provider` filter:

```bash
# Drain all nodes from a specific provider
aether cluster drain --provider aws [--wait] [--timeout 300]

# Scale within a specific provider
aether cluster scale --provider gcp --core 5

# Destroy nodes from a specific provider
aether cluster destroy --provider aws [--yes]

# Status filtered by provider
aether cluster status --provider gcp
```

**Implementation:** These commands use the `provider` field in `NodeLifecycleValue` to filter nodes. The `--provider` flag maps to a query parameter on the management API: `GET /api/nodes/lifecycle?provider=aws`.

### 8.5 Management API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/cluster/migrate` | Start migration (body: migration config JSON) |
| `GET` | `/api/cluster/migrate/status` | Get current migration status |
| `POST` | `/api/cluster/migrate/rollback` | Initiate rollback |
| `POST` | `/api/cluster/migrate/pause` | Pause migration (finish current step, hold) |
| `POST` | `/api/cluster/migrate/resume` | Resume paused migration |
| `GET` | `/api/nodes/lifecycle?provider={id}` | List nodes filtered by provider |

---

## 9. Rollback

### 9.1 Rollback by Migration State

| Current State | Rollback Action | Risk |
|---------------|----------------|------|
| PRE_FLIGHT | No-op (nothing to undo) | None |
| PROVISIONING | Terminate provisioned target instances | None |
| JOINING | Drain + terminate target nodes | Low (target nodes hold no primary data yet) |
| REDISTRIBUTING | Drain + terminate target nodes | Low (CDM rebalances back) |
| VERIFYING | Drain + terminate target nodes | Low |
| DRAINING | Reactivate drained source nodes + drain target nodes | Medium (source nodes must still be running) |
| TERMINATING | Cannot reactivate terminated instances; must re-provision source | High (data loss risk if source terminated) |
| DNS_CUTOVER | Revert DNS records; drain target nodes if needed | Medium (DNS propagation delay) |
| CLEANUP | Migration complete; rollback is a new reverse migration | Full reverse migration required |
| COMPLETED | No active migration; manual reverse migration | Full reverse migration required |

### 9.2 Automatic Rollback

When `migration.rollback.auto_rollback = true`:

- REQ-ROLLBACK-01: If verification fails (Step 5), automatically drain target nodes and terminate target instances.
- REQ-ROLLBACK-02: If a drain step fails and cannot be retried, pause and wait for operator input.
- REQ-ROLLBACK-03: Auto-rollback does NOT trigger after DNS_CUTOVER (too disruptive).

### 9.3 Source Instance Retention

When `migration.rollback.keep_source_instances = true`:

- REQ-RETAIN-01: Source instances are NOT terminated after draining. They remain in DECOMMISSIONED state.
- REQ-RETAIN-02: Operator must explicitly run `aether cluster destroy --provider <source>` to terminate.
- REQ-RETAIN-03: This provides a manual rollback window: reactivate source nodes if target fails.

---

## 10. Cross-Cloud Networking Requirements

### 10.1 Network Topologies

#### Option A: Public IPs with Firewall Rules

Simplest setup. Each node has a public IP; firewall rules restrict UDP ports to known IPs.

| Pro | Con |
|-----|-----|
| No VPN setup required | Exposes cluster ports to internet (behind firewall) |
| Works across any provider | Higher latency (public routing) |
| Simple to configure | IP addresses may change on restart |

**Firewall rules:**
```
# Source nodes -> Target nodes (and vice versa)
Allow UDP 6000 (cluster) from <source-ips> to <target-ips>
Allow UDP 6100 (SWIM) from <source-ips> to <target-ips>
Allow TCP 5150 (management) from <cli-ip> to <all-node-ips>
```

#### Option B: VPN / WireGuard Tunnel

Private connectivity between environments. Recommended for production.

| Pro | Con |
|-----|-----|
| Private, encrypted tunnel | VPN setup complexity |
| Stable IPs | VPN throughput limits |
| Lower attack surface | Additional infrastructure to manage |

#### Option C: Cloud Peering (Same Provider, Different Regions)

For same-cloud region migration (MS-05):

| Pro | Con |
|-----|-----|
| Native cloud integration | Same-provider only |
| Low latency, high throughput | Peering costs |
| No additional infrastructure | VPC CIDR must not overlap |

### 10.2 Latency Guidelines

| Migration Window | Cross-node RTT | Recommendation |
|-----------------|----------------|----------------|
| < 30 minutes | < 100ms | Proceed normally |
| 30 min - 2 hours | 100-200ms | Increase timeouts, use batch_size=1 |
| > 2 hours | > 200ms | NOT recommended. Consider staged migration via intermediate region |

### 10.3 Bandwidth Requirements

During redistribution, CDM replicates slice state to new nodes. Bandwidth needed:

```
bandwidth = total_slice_state_size / acceptable_redistribution_time
```

For a typical 5-node cluster with 1 GB total state:
- 10 Mbps link: ~14 minutes for full redistribution
- 100 Mbps link: ~1.4 minutes
- 1 Gbps link: ~8 seconds

---

## 11. Monitoring During Migration

### 11.1 KV-Store Migration State

```java
/// Migration state persisted in KV-Store for leader failover recovery.
record MigrationStateValue(MigrationPhase phase,
                            String migrationId,
                            String sourceProvider,
                            String targetProvider,
                            long startedAt,
                            long lastUpdatedAt,
                            int sourceNodesTotal,
                            int sourceNodesDrained,
                            int targetNodesTotal,
                            int targetNodesReady,
                            String strategy) implements AetherValue {

    public static MigrationStateValue migrationStateValue(String migrationId,
                                                           String sourceProvider,
                                                           String targetProvider,
                                                           int sourceNodesTotal,
                                                           int targetNodesTotal,
                                                           String strategy) {
        return new MigrationStateValue(MigrationPhase.PRE_FLIGHT, migrationId,
                                        sourceProvider, targetProvider,
                                        System.currentTimeMillis(), System.currentTimeMillis(),
                                        sourceNodesTotal, 0, targetNodesTotal, 0, strategy);
    }
}

enum MigrationPhase {
    PRE_FLIGHT,
    PROVISIONING,
    JOINING,
    REDISTRIBUTING,
    VERIFYING,
    DRAINING,
    TERMINATING,
    DNS_CUTOVER,
    CLEANUP,
    COMPLETED,
    ROLLED_BACK,
    FAILED
}
```

### 11.2 Metrics

| Metric | Labels | Description |
|--------|--------|-------------|
| `aether_migration_phase` | `migration_id` | Current phase (gauge, ordinal of MigrationPhase) |
| `aether_migration_source_nodes_total` | `migration_id`, `provider` | Total source nodes |
| `aether_migration_source_nodes_drained` | `migration_id`, `provider` | Drained source nodes |
| `aether_migration_target_nodes_total` | `migration_id`, `provider` | Total target nodes |
| `aether_migration_target_nodes_ready` | `migration_id`, `provider` | ON_DUTY target nodes |
| `aether_migration_duration_seconds` | `migration_id` | Elapsed time |
| `aether_node_requests_total` | `provider` | Requests per provider (existing metric, new label) |
| `aether_node_latency_seconds` | `provider` | Latency per provider (existing metric, new label) |

### 11.3 Management API Status Response

```json
{
  "migrationId": "mig-20260327-143200",
  "phase": "DRAINING",
  "sourceProvider": "aws",
  "targetProvider": "gcp",
  "startedAt": 1711546320000,
  "elapsed": "8m 15s",
  "sourceNodes": {
    "total": 5,
    "onDuty": 2,
    "draining": 0,
    "decommissioned": 3
  },
  "targetNodes": {
    "total": 5,
    "onDuty": 5
  },
  "clusterSize": 7,
  "strategy": "rolling",
  "batchSize": 1,
  "nextAction": "Draining aws-node-4"
}
```

### 11.4 Dashboard

The migration panel shows:

- Source and target provider labels on each node in the topology view
- Migration progress bar (phases completed / total phases)
- Per-provider request distribution pie chart
- Per-provider latency comparison
- Alert banner if target nodes are unhealthy

---

## 12. Error Model

### 12.1 Migration-Specific Errors

Added to `EnvironmentError`:

```java
record MigrationPreFlightFailed(String check, String detail) implements EnvironmentError {
    @Override
    public String message() {
        return "Migration pre-flight failed: " + check + " — " + detail;
    }
}

record MigrationNodeJoinTimeout(String nodeId, String provider) implements EnvironmentError {
    @Override
    public String message() {
        return "Node " + nodeId + " (" + provider + ") did not reach ON_DUTY within timeout";
    }
}

record MigrationVerificationFailed(String reason) implements EnvironmentError {
    @Override
    public String message() {
        return "Migration verification failed: " + reason;
    }
}

record MigrationDrainFailed(String nodeId, String detail) implements EnvironmentError {
    @Override
    public String message() {
        return "Failed to drain node " + nodeId + " during migration: " + detail;
    }
}

record MigrationRollbackFailed(String phase, String detail) implements EnvironmentError {
    @Override
    public String message() {
        return "Migration rollback failed during " + phase + ": " + detail;
    }
}

record MigrationAlreadyInProgress(String migrationId) implements EnvironmentError {
    @Override
    public String message() {
        return "Migration already in progress: " + migrationId;
    }
}
```

### 12.2 Error Recovery Matrix

| Error | Recovery |
|-------|----------|
| Pre-flight failure | Fix the issue and retry |
| Provisioning failure (partial) | Terminate provisioned target nodes, retry |
| Node join timeout | Check network connectivity; terminate stuck nodes, retry |
| Verification failure | Auto-rollback if configured; otherwise manual decision |
| Drain timeout | Increase timeout; force-drain if safe; rollback |
| Termination failure | Retry; manual cleanup via cloud console |
| DNS update failure | Manual DNS update; migration still succeeds (data layer migrated) |

---

## 13. Operator Runbook

### 13.1 AWS to GCP Migration

**Prerequisites:**
1. VPN tunnel or public IPs between AWS VPC and GCP VPC
2. Firewall rules allowing UDP 6000, 6100 and TCP 5150 between all nodes
3. GCP credentials configured (`GOOGLE_APPLICATION_CREDENTIALS`)
4. DNS zone managed by Cloud DNS (if DNS cutover desired)
5. Lower DNS TTL to 60s at least 24h before migration

**Steps:**

```bash
# 1. Verify source cluster health
aether cluster status
# Ensure all nodes ON_DUTY, no active deployments

# 2. Test cross-cloud connectivity (from a GCP VM or local machine with access)
# UDP probe to each AWS node on port 6000
nc -u -z <aws-node-ip> 6000

# 3. Create migration config
cat > migration.toml << 'EOF'
[migration]
source = "aws"
target = "gcp"
strategy = "rolling"
batch_size = 1
verify_timeout = "120s"
verify_duration = "60s"
drain_timeout = "300s"

[migration.dns]
enabled = true
zone = "example.com"
records = ["cluster.example.com"]
ttl = 60
propagation_wait = "120s"

[migration.rollback]
auto_rollback = true
keep_source_instances = true

[migration.target]
type = "gcp"

[migration.target.instances]
core = "e2-medium"

[migration.target.runtime]
type = "container"
image = "gcr.io/my-project/aether-node:0.21.1"

[migration.target.zones]
zone-a = "europe-west1-b"
zone-b = "europe-west1-c"

[migration.target.ports]
cluster = 6000
management = 5150

[migration.target.tls]
auto_generate = true
cluster_secret = "${secrets:cluster-secret}"

[migration.target.gcp]
project_id = "my-project"
network = "default"
subnetwork = "aether-subnet"
EOF

# 4. Dry run
aether cluster migrate --config migration.toml --dry-run

# 5. Execute migration
aether cluster migrate --config migration.toml

# 6. Monitor progress (in another terminal)
watch -n 5 aether cluster migrate --status

# 7. After migration completes, verify
aether cluster status
# All nodes should be GCP, all ON_DUTY

# 8. Clean up source instances (if keep_source_instances was true)
aether cluster destroy --provider aws --yes
```

### 13.2 Cloud to On-Premises Migration

**Prerequisites:**
1. VPN or direct link from cloud to datacenter
2. Target bare-metal/VM hosts provisioned with JVM and Aether runtime
3. SSH access to target hosts from CLI machine
4. Same `clusterSecret` on all target hosts

**Steps:**

```bash
# 1. Prepare target hosts (SSH-based bootstrap)
# Each target host needs: JVM 25+, aether-node.jar, systemd unit

# 2. Bootstrap target nodes with seed peers pointing to source cluster
# On each target host:
aether-node --config node-config.toml \
  --cluster-port 6000 \
  --management-port 5150 \
  --seed-peers <source-node-1>:6000,<source-node-2>:6000

# 3. Wait for target nodes to appear in cluster
aether cluster status
# Should show source + target nodes

# 4. Drain source nodes one by one
for node_id in $(aether cluster status --provider aws --json | jq -r '.[].nodeId'); do
  aether cluster drain $node_id --wait --timeout 300
  echo "Drained $node_id"
done

# 5. Terminate source instances
aether cluster destroy --provider aws --yes

# 6. Update DNS manually or via automation
# Point cluster.example.com to on-premises IPs
```

### 13.3 Same-Cloud Region Migration (AWS us-east-1 to eu-west-1)

```bash
# Same as cloud-to-cloud but source and target are both "aws"
# Use region-specific deployment sections

cat > migration.toml << 'EOF'
[migration]
source = "aws"
target = "aws"
strategy = "rolling"
batch_size = 1

[migration.target]
type = "aws"

[migration.target.instances]
core = "t3.medium"

[migration.target.runtime]
type = "container"
image = "123456789.dkr.ecr.eu-west-1.amazonaws.com/aether-node:0.21.1"

[migration.target.zones]
zone-a = "eu-west-1a"
zone-b = "eu-west-1b"

[migration.target.aws]
region = "eu-west-1"
vpc_id = "vpc-eu-west-xxxxx"
subnet_ids = ["subnet-eu-a", "subnet-eu-b"]
security_group_id = "sg-eu-xxxxx"
key_pair = "aether-nodes-eu"
EOF

aether cluster migrate --config migration.toml
```

### 13.4 Rollback Procedures

**Scenario: Migration failed during DRAINING phase**

```bash
# 1. Check current state
aether cluster migrate --status

# 2. Rollback
aether cluster migrate --rollback

# 3. Verify cluster restored
aether cluster status
# All source nodes should be ON_DUTY, target nodes terminated
```

**Scenario: Migration completed but target is unhealthy**

```bash
# This is a reverse migration, not a rollback
# 1. Create reverse migration config (target -> source)
# 2. Execute reverse migration
aether cluster migrate --config reverse-migration.toml
```

### 13.5 Troubleshooting

| Symptom | Cause | Resolution |
|---------|-------|------------|
| Target nodes stuck in JOINING | Network connectivity issue | Check firewall rules for UDP 6000/6100 |
| High latency during migration | Cross-region consensus | Expected; will resolve when source nodes are drained |
| Drain timeout on source node | Slices cannot relocate | Check target node capacity; increase drain_timeout |
| DNS not resolving to new IPs | Propagation delay | Wait; check TTL was lowered; verify DNS provider config |
| Migration status shows FAILED | Check error message | Review management API logs; fix issue; resume or rollback |
| Consensus stalls during migration | Too many nodes (>9) or high latency | Reduce cluster size; use batch strategy |

---

## 14. Implementation Layers

### Layer 1: NodeLifecycleValue `provider` Field + CLI `--provider` Filter

**Scope:**
- Add `provider` field to `NodeLifecycleValue` record
- Update all factory methods and `withState` to preserve `provider`
- Update `KVStoreSerializer` for new field
- Add `?provider=` query parameter to `GET /api/nodes/lifecycle`
- Add `--provider` flag to `ClusterDrainCommand`, `ClusterDestroyCommand`, `ClusterStatusCommand`
- Update serialization tests

**Files modified:**
- `aether/slice/src/main/java/org/pragmatica/aether/slice/kvstore/AetherValue.java`
- `aether/slice/src/main/java/org/pragmatica/aether/slice/kvstore/KVStoreSerializer.java`
- `aether/node/src/main/java/org/pragmatica/aether/api/routes/NodeLifecycleRoutes.java`
- `aether/cli/src/main/java/org/pragmatica/aether/cli/cluster/ClusterDrainCommand.java`
- `aether/cli/src/main/java/org/pragmatica/aether/cli/cluster/ClusterDestroyCommand.java`
- `aether/cli/src/main/java/org/pragmatica/aether/cli/cluster/ClusterStatusCommand.java`
- `aether/node/src/main/java/org/pragmatica/aether/node/AetherNode.java` (set provider on lifecycle write)

**Effort:** ~2 days

### Layer 2: DNS Provider SPI

**Scope:**
- Add `DnsProvider` interface to `aether/environment-integration`
- Add `DnsRecord`, `DnsRecordId`, `DnsRecordType` types
- Add `dns()` facet to `EnvironmentIntegration`
- Update `FacetedEnvironment` record
- Add DNS error types to `EnvironmentError`
- Implement `HetznerDnsProvider` (Hetzner DNS API)

**Files created:**
- `aether/environment-integration/src/main/java/org/pragmatica/aether/environment/DnsProvider.java`
- `aether/environment-integration/src/main/java/org/pragmatica/aether/environment/DnsRecord.java`
- `aether/environment-integration/src/main/java/org/pragmatica/aether/environment/DnsRecordId.java`
- `aether/environment-integration/src/main/java/org/pragmatica/aether/environment/DnsRecordType.java`

**Files modified:**
- `aether/environment-integration/src/main/java/org/pragmatica/aether/environment/EnvironmentIntegration.java`
- `aether/environment-integration/src/main/java/org/pragmatica/aether/environment/EnvironmentError.java`

**Effort:** ~3 days (SPI) + ~2 days per provider implementation

### Layer 3: Migration State Machine + `aether cluster migrate`

**Scope:**
- `MigrationStateValue` and `MigrationPhase` in KV-Store types
- `MigrationOrchestrator` in `aether-deployment` (leader-driven, CDM pattern)
- Migration config parser (TOML `[migration]` section)
- Management API endpoints for migrate/status/rollback/pause/resume
- `ClusterMigrateCommand` in CLI
- Pre-flight check framework

**Files created:**
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/migration/MigrationOrchestrator.java`
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/migration/MigrationConfig.java`
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/migration/PreFlightCheck.java`
- `aether/node/src/main/java/org/pragmatica/aether/api/routes/MigrationRoutes.java`
- `aether/cli/src/main/java/org/pragmatica/aether/cli/cluster/ClusterMigrateCommand.java`

**Effort:** ~5 days

### Layer 4: Migration Monitoring Dashboard Panel

**Scope:**
- Migration progress widget (phase indicator, progress bars)
- Per-provider node view (color-coded by provider)
- Per-provider metrics comparison panel
- Migration alert rules

**Effort:** ~3 days

### Layer 5: Runbook + Documentation

**Scope:**
- Operator runbook for each migration scenario
- CLI reference updates
- Management API reference updates
- Architecture documentation updates

**Effort:** ~2 days

### Total Estimated Effort

| Layer | Effort | Dependencies |
|-------|--------|-------------|
| Layer 1 | 2 days | None |
| Layer 2 | 3-5 days | None (parallel with Layer 1) |
| Layer 3 | 5 days | Layer 1, Layer 2 |
| Layer 4 | 3 days | Layer 3 |
| Layer 5 | 2 days | Layer 3 |
| **Total** | **15-17 days** | |

---

## 15. Open Questions

| ID | Question | Impact | Status |
|----|----------|--------|--------|
| OQ-01 | Should `MigrationOrchestrator` run on the leader only (like CDM) or on any node? Leader-only is simpler but means migration pauses during leader election. | Architecture | [ASSUMPTION] Leader-only, matching CDM pattern. Re-election is virtually instantaneous. |
| OQ-02 | Should we support migrating a subset of nodes (e.g., 3 of 5) instead of full cluster migration? | Scope | [TBD] Full migration only in v1. Partial migration is a future extension. |
| OQ-03 | For on-premises targets, how are nodes provisioned? SSH-based bootstrap (like `SshBootstrapOrchestrator`) or manual? | UX | [ASSUMPTION] Reuse existing `SshBootstrapOrchestrator` for on-premises targets. |
| OQ-04 | Should the migration config be a separate file or embedded in `aether-cluster.toml`? | Config design | [ASSUMPTION] Separate file. Migration is a transient operation, not part of steady-state cluster config. |
| OQ-05 | How to handle same-provider region migration where `source` and `target` are both "aws"? | Config design | Region differentiator in `[migration.target.aws].region`. Validation relaxed for same-provider. |

---

## 16. References

### Internal References

- [Cloud Integration SPI Spec](cloud-integration-spi-spec.md) -- SPI architecture for ComputeProvider, LoadBalancerProvider, DiscoveryProvider, SecretsProvider
- [Cluster Management Spec](cluster-management-spec.md) -- Declarative cluster management, TOML config format, CLI commands
- [Canary & Blue-Green Spec](canary-blue-green-spec.md) -- Deployment strategies (rolling, canary, blue-green)
- `aether/environment-integration/src/main/java/org/pragmatica/aether/environment/EnvironmentIntegration.java` -- Faceted SPI entry point
- `aether/environment-integration/src/main/java/org/pragmatica/aether/environment/ComputeProvider.java` -- Compute provisioning SPI
- `aether/environment-integration/src/main/java/org/pragmatica/aether/environment/LoadBalancerProvider.java` -- Load balancer SPI
- `aether/environment-integration/src/main/java/org/pragmatica/aether/environment/DiscoveryProvider.java` -- Peer discovery SPI
- `aether/environment-integration/src/main/java/org/pragmatica/aether/environment/EnvironmentError.java` -- Sealed error hierarchy
- `aether/slice/src/main/java/org/pragmatica/aether/slice/kvstore/AetherValue.java` -- KV-Store types including NodeLifecycleValue
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/cluster/ClusterTopologyManager.java` -- Cluster topology reconciliation
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/cluster/NodeLifecycleManager.java` -- Cloud instance lifecycle operations
- `aether/cli/src/main/java/org/pragmatica/aether/cli/cluster/ClusterDrainCommand.java` -- Existing drain CLI command
- `aether/cli/src/main/java/org/pragmatica/aether/cli/cluster/ClusterScaleCommand.java` -- Existing scale CLI command
- `aether/cli/src/main/java/org/pragmatica/aether/cli/cluster/ClusterDestroyCommand.java` -- Existing destroy CLI command

### Technical Documentation

- [QUIC RFC 9000](https://www.rfc-editor.org/rfc/rfc9000) -- QUIC transport protocol
- [TLS 1.3 RFC 8446](https://www.rfc-editor.org/rfc/rfc8446) -- TLS 1.3 specification
- [Rabia Consensus](https://dl.acm.org/doi/10.1145/3477132.3483582) -- Rabia: Simplifying State-Machine Replication Through Randomization
- [SWIM Protocol](https://www.cs.cornell.edu/projects/Quicksilver/public_pdfs/SWIM.pdf) -- Scalable Weakly-consistent Infection-style Process Group Membership Protocol

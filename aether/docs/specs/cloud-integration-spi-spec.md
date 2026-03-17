# Cloud Integration SPI Architecture Specification

**Version:** 1.0
**Status:** Draft
**Target Release:** 0.22.0+
**Author:** spec-writer agent
**Date:** 2026-03-17

---

## Table of Contents

1. [Overview and Goals](#1-overview-and-goals)
2. [Existing State Analysis](#2-existing-state-analysis)
3. [SPI Architecture](#3-spi-architecture)
4. [Lifecycle Integration](#4-lifecycle-integration)
5. [Configuration Model](#5-configuration-model)
6. [Error Model](#6-error-model)
7. [Provider Implementation Sheet Template](#7-provider-implementation-sheet-template)
8. [Hetzner Provider Sheet (Reference)](#8-hetzner-provider-sheet-reference)
9. [Testing Strategy](#9-testing-strategy)
10. [Implementation Plan](#10-implementation-plan)
11. [Open Questions](#11-open-questions)
12. [References](#12-references)

---

## 1. Overview and Goals

### 1.1 Purpose

This specification defines four SPI contracts that cloud providers must implement to integrate with the Aether distributed runtime. The SPIs cover compute provisioning, load balancer management, peer discovery, and secret resolution. Together they enable Aether to manage its own infrastructure across multiple cloud providers.

### 1.2 Goals

- **G-1:** Extend the existing `ComputeProvider` and `LoadBalancerProvider` interfaces without breaking the Hetzner implementation
- **G-2:** Introduce a new `DiscoveryProvider` SPI that replaces static peer lists with tag/label-based discovery
- **G-3:** Extend the existing `SecretsProvider` with caching, TTL, and rotation notification
- **G-4:** Maintain JBCT compliance: sealed interfaces, records, `Promise<T>`-based async, no business exceptions
- **G-5:** Support incremental provider implementation (one provider at a time, one facet at a time)
- **G-6:** All SPI contracts must be cloud-agnostic; provider-specific details live in implementation modules

### 1.3 Non-Goals

- Terraform/IaC generation (see `aether/docs/operators/infrastructure-design.md` for that layer)
- Multi-region federation (single-region scope; see development priorities FUTURE section)
- Expense tracking (future work, depends on this spec)
- Container orchestration integration (Kubernetes operator is separate)

### 1.4 Design Principles

- **Extend, don't replace.** Existing `ComputeProvider`, `LoadBalancerProvider`, and `SecretsProvider` interfaces are the foundation. New methods are additive.
- **Optional facets.** `EnvironmentIntegration` already uses `Option<T>` for each facet. `DiscoveryProvider` follows the same pattern.
- **Provider isolation.** Each cloud provider is a separate Maven module under `aether/environment/`. No cross-provider dependencies.
- **Sealed error hierarchy.** Every cloud operation failure is a sealed `Cause` variant. No raw exceptions cross the SPI boundary.

---

## 2. Existing State Analysis

### 2.1 Current Module Structure

```
integrations/cloud/
  hetzner/              -- Low-level Hetzner REST API client (HetznerClient)

aether/environment-integration/  -- SPI interfaces (cloud-agnostic)
  ComputeProvider.java
  LoadBalancerProvider.java
  SecretsProvider.java
  EnvironmentIntegration.java
  EnvironmentError.java
  InstanceId.java, InstanceInfo.java, InstanceStatus.java, InstanceType.java
  RouteChange.java, LoadBalancerState.java
  AutoHealConfig.java

aether/environment/hetzner/      -- Hetzner SPI implementation
  HetznerComputeProvider.java
  HetznerLoadBalancerProvider.java
  HetznerEnvironmentIntegration.java
  HetznerEnvironmentConfig.java

aether/aether-deployment/        -- CDM + LoadBalancerManager (consumers of the SPIs)
  ClusterDeploymentManager.java  -- Uses ComputeProvider for auto-heal
  LoadBalancerManager.java       -- Uses LoadBalancerProvider for target sync
```

### 2.2 Current SPI Interfaces

#### ComputeProvider (existing)

```java
public interface ComputeProvider {
    Promise<InstanceInfo> provision(InstanceType instanceType);
    Promise<Unit> terminate(InstanceId instanceId);
    Promise<List<InstanceInfo>> listInstances();
    Promise<InstanceInfo> instanceStatus(InstanceId instanceId);
}
```

**Gaps identified:**
- No restart operation (useful for reconfiguration without full terminate/provision cycle)
- No node pool concept (core vs elastic/spot)
- No instance size/flavor parameter beyond `InstanceType` (ON_DEMAND vs SPOT)
- No tag/label management on instances
- No image/snapshot management

#### LoadBalancerProvider (existing)

```java
public interface LoadBalancerProvider {
    Promise<Unit> onRouteChanged(RouteChange routeChange);
    Promise<Unit> onNodeRemoved(String nodeIp);
    Promise<Unit> reconcile(LoadBalancerState state);
}
```

**Gaps identified:**
- No LB create/delete (assumes pre-existing LB)
- No health check configuration
- No weighted routing sync (Aether rolling update weights -> cloud LB weights)
- No TLS termination configuration
- No graceful drain with configurable delay
- No LB status/info retrieval

#### SecretsProvider (existing)

```java
public interface SecretsProvider {
    Promise<String> resolveSecret(String secretPath);
}
```

**Gaps identified:**
- No caching or TTL
- No rotation notification
- No batch resolution
- No secret metadata (version, expiry)

#### EnvironmentIntegration (existing)

```java
public interface EnvironmentIntegration {
    Option<ComputeProvider> compute();
    Option<SecretsProvider> secrets();
    Option<LoadBalancerProvider> loadBalancer();
}
```

**Gap:** No `DiscoveryProvider` facet.

### 2.3 Current Hetzner Implementation Status

| Facet | Status | Notes |
|-------|--------|-------|
| Compute | Complete | Provision, terminate, list, status. Maps Hetzner server models to SPI types. |
| Load Balancer | Complete | IP-based target management on pre-existing LB. Reconciliation via diff. |
| Secrets | Not implemented | `HetznerEnvironmentIntegration.secrets()` returns `Option.empty()` |
| Discovery | Not implemented | No interface exists yet |

### 2.4 Integration Points (Consumers)

| Consumer | Uses | Trigger |
|----------|------|---------|
| `ClusterDeploymentManager` | `ComputeProvider` | Auto-heal when cluster size < target |
| `LoadBalancerManager` | `LoadBalancerProvider` | Route change, node departure, leader election |
| `AetherNode` (Main bootstrap) | `SecretsProvider` | `${secrets:...}` placeholder resolution at config load |
| `AetherNode` (Main bootstrap) | Static peer list | Currently `coreNodes` from TOML; to be replaced by `DiscoveryProvider` |

---

## 3. SPI Architecture

### 3.1 ComputeProvider SPI (Extended)

**Module:** `aether/environment-integration`
**Package:** `org.pragmatica.aether.environment`

```java
/// SPI for provisioning and managing compute instances.
/// Implementations handle the actual instance lifecycle (Forge local nodes, cloud VMs, etc.).
/// CDM uses this interface to auto-heal when cluster size drops below target.
public interface ComputeProvider {
    // --- Existing methods (unchanged) ---

    /// REQ-C-01: Provision a new instance of the given type.
    Promise<InstanceInfo> provision(InstanceType instanceType);

    /// REQ-C-02: Terminate an instance by ID.
    Promise<Unit> terminate(InstanceId instanceId);

    /// REQ-C-03: List all managed instances.
    Promise<List<InstanceInfo>> listInstances();

    /// REQ-C-04: Get current status of a specific instance.
    Promise<InstanceInfo> instanceStatus(InstanceId instanceId);

    // --- New methods ---

    /// REQ-C-05: Provision a new instance with detailed specification.
    /// Falls back to provision(instanceType) if provider does not support detailed specs.
    default Promise<InstanceInfo> provision(ProvisionSpec spec) {
        return provision(spec.instanceType());
    }

    /// REQ-C-06: Restart an instance (provider-specific: reboot, stop+start, or replace).
    default Promise<Unit> restart(InstanceId instanceId) {
        return terminate(instanceId).flatMap(_ -> provision(InstanceType.ON_DEMAND))
                                    .map(_ -> Unit.unit());
    }

    /// REQ-C-07: Apply or update tags/labels on an existing instance.
    default Promise<Unit> applyTags(InstanceId instanceId, Map<String, String> tags) {
        return Promise.success(Unit.unit()); // No-op default for providers without tagging
    }

    /// REQ-C-08: List instances filtered by tag/label selector.
    default Promise<List<InstanceInfo>> listInstances(TagSelector selector) {
        return listInstances().map(all -> filterByTags(all, selector));
    }

    private static List<InstanceInfo> filterByTags(List<InstanceInfo> instances, TagSelector selector) {
        return instances.stream()
                        .filter(selector::matches)
                        .toList();
    }
}
```

#### New Supporting Types

```java
/// REQ-C-09: Detailed provisioning specification.
public record ProvisionSpec(InstanceType instanceType,
                            String instanceSize,
                            String pool,
                            Map<String, String> tags,
                            Option<String> imageId,
                            Option<String> userData) {
    public static Result<ProvisionSpec> provisionSpec(InstanceType instanceType,
                                                       String instanceSize,
                                                       String pool,
                                                       Map<String, String> tags) {
        return success(new ProvisionSpec(instanceType, instanceSize, pool,
                                         Map.copyOf(tags), Option.empty(), Option.empty()));
    }
}

/// REQ-C-10: Tag/label selector for filtering instances.
public record TagSelector(Map<String, String> requiredTags) {
    public static Result<TagSelector> tagSelector(Map<String, String> requiredTags) {
        return success(new TagSelector(Map.copyOf(requiredTags)));
    }

    public boolean matches(InstanceInfo instance) {
        return instance.tags()
                       .entrySet()
                       .containsAll(requiredTags.entrySet());
    }
}
```

**InstanceInfo extension** (new field):

```java
/// REQ-C-11: Extended InstanceInfo with tags.
public record InstanceInfo(InstanceId id,
                           InstanceStatus status,
                           List<String> addresses,
                           InstanceType type,
                           Map<String, String> tags) {
    // Backward-compatible constructor
    public InstanceInfo(InstanceId id, InstanceStatus status,
                        List<String> addresses, InstanceType type) {
        this(id, status, addresses, type, Map.of());
    }
}
```

### 3.2 LoadBalancerProvider SPI (Extended)

**Module:** `aether/environment-integration`
**Package:** `org.pragmatica.aether.environment`

```java
/// SPI for managing external load balancer configuration.
/// Implementations synchronize route and target changes with a cloud load balancer
/// (e.g., Hetzner LB, AWS ALB) so that external traffic reaches the correct nodes.
public interface LoadBalancerProvider {
    // --- Existing methods (unchanged) ---

    /// REQ-LB-01: Notify provider of a route change (targets added/removed for a route).
    Promise<Unit> onRouteChanged(RouteChange routeChange);

    /// REQ-LB-02: Notify provider that a node has been removed.
    Promise<Unit> onNodeRemoved(String nodeIp);

    /// REQ-LB-03: Full reconciliation of LB state against desired state.
    Promise<Unit> reconcile(LoadBalancerState state);

    // --- New methods ---

    /// REQ-LB-04: Create a new load balancer with the given specification.
    /// Returns the provider-assigned LB identifier.
    default Promise<LoadBalancerInfo> createLoadBalancer(LoadBalancerSpec spec) {
        return new CloudError.OperationNotSupported("createLoadBalancer").promise();
    }

    /// REQ-LB-05: Delete a load balancer by its provider-assigned ID.
    default Promise<Unit> deleteLoadBalancer(String loadBalancerId) {
        return new CloudError.OperationNotSupported("deleteLoadBalancer").promise();
    }

    /// REQ-LB-06: Get current LB info (targets, health, algorithm).
    default Promise<LoadBalancerInfo> loadBalancerInfo() {
        return new CloudError.OperationNotSupported("loadBalancerInfo").promise();
    }

    /// REQ-LB-07: Configure health check parameters for the load balancer.
    default Promise<Unit> configureHealthCheck(HealthCheckConfig healthCheck) {
        return Promise.success(Unit.unit()); // No-op default
    }

    /// REQ-LB-08: Sync weighted routing from Aether rolling update weights to cloud LB.
    /// weightsByIp maps node IP -> weight (0-100). Weight 0 means drain.
    default Promise<Unit> syncWeights(Map<String, Integer> weightsByIp) {
        return Promise.success(Unit.unit()); // No-op default (equal distribution)
    }

    /// REQ-LB-09: Deregister a target with graceful drain (connection draining delay).
    /// The provider should wait for active connections to complete before removing the target.
    default Promise<Unit> deregisterWithDrain(String nodeIp, TimeSpan drainTimeout) {
        return onNodeRemoved(nodeIp); // Default: immediate removal
    }

    /// REQ-LB-10: Configure TLS termination on the load balancer.
    default Promise<Unit> configureTls(TlsTerminationConfig tlsConfig) {
        return new CloudError.OperationNotSupported("configureTls").promise();
    }
}
```

#### New Supporting Types

```java
/// REQ-LB-11: Specification for creating a load balancer.
public record LoadBalancerSpec(String name,
                               String algorithm,
                               List<ServicePort> servicePorts,
                               Option<String> region,
                               Map<String, String> tags) {
    public record ServicePort(String protocol, int listenPort, int destinationPort) {
        public static Result<ServicePort> servicePort(String protocol,
                                                       int listenPort,
                                                       int destinationPort) {
            return success(new ServicePort(protocol, listenPort, destinationPort));
        }
    }

    public static Result<LoadBalancerSpec> loadBalancerSpec(String name,
                                                             String algorithm,
                                                             List<ServicePort> servicePorts) {
        return success(new LoadBalancerSpec(name, algorithm, List.copyOf(servicePorts),
                                            Option.empty(), Map.of()));
    }
}

/// REQ-LB-12: Information about a managed load balancer.
public record LoadBalancerInfo(String id,
                                String name,
                                String publicIp,
                                String status,
                                List<TargetInfo> targets) {
    public record TargetInfo(String ip, String healthStatus, int weight) {
        public static Result<TargetInfo> targetInfo(String ip, String healthStatus, int weight) {
            return success(new TargetInfo(ip, healthStatus, weight));
        }
    }

    public static Result<LoadBalancerInfo> loadBalancerInfo(String id, String name,
                                                             String publicIp, String status,
                                                             List<TargetInfo> targets) {
        return success(new LoadBalancerInfo(id, name, publicIp, status, List.copyOf(targets)));
    }
}

/// REQ-LB-13: Health check configuration for load balancer targets.
public record HealthCheckConfig(String protocol,
                                 int port,
                                 String path,
                                 TimeSpan interval,
                                 TimeSpan timeout,
                                 int healthyThreshold,
                                 int unhealthyThreshold) {
    public static final HealthCheckConfig DEFAULT = new HealthCheckConfig(
        "http", 8080, "/health/ready",
        timeSpan(10).seconds(), timeSpan(5).seconds(), 3, 3);

    public static Result<HealthCheckConfig> healthCheckConfig(String protocol, int port,
                                                               String path, TimeSpan interval,
                                                               TimeSpan timeout,
                                                               int healthyThreshold,
                                                               int unhealthyThreshold) {
        return success(new HealthCheckConfig(protocol, port, path, interval, timeout,
                                              healthyThreshold, unhealthyThreshold));
    }
}

/// REQ-LB-14: TLS termination configuration.
public record TlsTerminationConfig(String certificateId,
                                    Option<String> privateKeyPath,
                                    boolean redirectHttp) {
    public static Result<TlsTerminationConfig> tlsTerminationConfig(String certificateId) {
        return success(new TlsTerminationConfig(certificateId, Option.empty(), true));
    }
}
```

### 3.3 DiscoveryProvider SPI (New)

**Module:** `aether/environment-integration`
**Package:** `org.pragmatica.aether.environment`

```java
/// REQ-D-01: SPI for tag/label-based peer discovery.
/// Replaces static peer lists in TOML configuration.
/// Implementations query cloud provider APIs to find Aether cluster peers
/// by matching tags/labels, enabling dynamic cluster formation.
public interface DiscoveryProvider {

    /// REQ-D-02: Discover current peers matching the configured selector.
    /// Returns a list of peer addresses (host:port) for cluster formation.
    Promise<List<PeerAddress>> discoverPeers();

    /// REQ-D-03: Watch for membership changes.
    /// The callback is invoked whenever the set of discovered peers changes.
    /// Returns a handle that can be used to stop watching.
    /// Implementations may poll at a configurable interval or use cloud-native push mechanisms.
    Promise<WatchHandle> watchPeers(PeerChangeCallback callback);

    /// REQ-D-04: Register the current node with the discovery system.
    /// Tags the node so that other peers can discover it.
    Promise<Unit> registerSelf(PeerAddress selfAddress, Map<String, String> metadata);

    /// REQ-D-05: Deregister the current node from the discovery system.
    Promise<Unit> deregisterSelf();
}
```

#### Supporting Types

```java
/// REQ-D-06: Address of a discovered peer.
public record PeerAddress(String host, int port, Map<String, String> metadata) {
    public static Result<PeerAddress> peerAddress(String host, int port) {
        return success(new PeerAddress(host, port, Map.of()));
    }

    public static Result<PeerAddress> peerAddress(String host, int port,
                                                    Map<String, String> metadata) {
        return success(new PeerAddress(host, port, Map.copyOf(metadata)));
    }
}

/// REQ-D-07: Callback for peer change events.
@FunctionalInterface
public interface PeerChangeCallback {
    void onPeersChanged(List<PeerAddress> currentPeers);
}

/// REQ-D-08: Handle to cancel a peer watch.
@FunctionalInterface
public interface WatchHandle {
    Promise<Unit> cancel();
}

/// REQ-D-09: Configuration for discovery.
public record DiscoveryConfig(Map<String, String> tagSelector,
                               TimeSpan pollInterval,
                               Option<String> clusterName) {
    public static final DiscoveryConfig DEFAULT = new DiscoveryConfig(
        Map.of("aether-cluster", "default"),
        timeSpan(10).seconds(),
        Option.empty());

    public static Result<DiscoveryConfig> discoveryConfig(Map<String, String> tagSelector,
                                                            TimeSpan pollInterval) {
        return success(new DiscoveryConfig(Map.copyOf(tagSelector), pollInterval, Option.empty()));
    }
}
```

### 3.4 SecretsProvider SPI (Extended)

**Module:** `aether/environment-integration`
**Package:** `org.pragmatica.aether.environment`

```java
/// SPI for resolving secrets from external backends (Vault, AWS Secrets Manager, etc.).
/// Used internally by the resource provisioning pipeline to resolve ${secrets:...} placeholders
/// in configuration before passing to ResourceFactory implementations.
///
/// Application code never interacts with this interface directly.
public interface SecretsProvider {
    // --- Existing method (unchanged) ---

    /// REQ-S-01: Resolve a secret path to its value.
    Promise<String> resolveSecret(String secretPath);

    // --- New methods ---

    /// REQ-S-02: Resolve a secret with metadata (version, expiry).
    default Promise<SecretValue> resolveSecretWithMetadata(String secretPath) {
        return resolveSecret(secretPath).map(value -> new SecretValue(value,
                                                                       Option.empty(),
                                                                       Option.empty()));
    }

    /// REQ-S-03: Resolve multiple secrets in a single call (batch).
    /// Implementations may optimize with batch APIs. Default resolves sequentially.
    default Promise<Map<String, String>> resolveSecrets(List<String> secretPaths) {
        var futures = secretPaths.stream()
                                 .map(path -> resolveSecret(path).map(value -> Map.entry(path, value)))
                                 .toList();
        return Promise.allOf(futures)
                      .map(SecretsProvider::collectEntries);
    }

    /// REQ-S-04: Register a callback for secret rotation events.
    /// Default: no-op (provider does not support rotation notifications).
    default Promise<WatchHandle> watchRotation(String secretPath,
                                                SecretRotationCallback callback) {
        return Promise.success(() -> Promise.success(Unit.unit()));
    }

    private static Map<String, String> collectEntries(List<Result<Map.Entry<String, String>>> results) {
        // Implementation collects successful entries into a map
        var map = new java.util.HashMap<String, String>();
        for (var result : results) {
            result.onSuccess(entry -> map.put(entry.getKey(), entry.getValue()));
        }
        return Map.copyOf(map);
    }
}
```

#### Supporting Types

```java
/// REQ-S-05: A resolved secret with optional metadata.
public record SecretValue(String value,
                           Option<String> version,
                           Option<java.time.Instant> expiresAt) {
    public static Result<SecretValue> secretValue(String value) {
        return success(new SecretValue(value, Option.empty(), Option.empty()));
    }
}

/// REQ-S-06: Callback for secret rotation events.
@FunctionalInterface
public interface SecretRotationCallback {
    void onRotated(String secretPath, String newValue);
}

/// REQ-S-07: Caching wrapper for SecretsProvider.
/// Wraps any SecretsProvider with an in-memory TTL cache.
public interface CachingSecretsProvider extends SecretsProvider {
    static SecretsProvider cachingSecretsProvider(SecretsProvider delegate, TimeSpan ttl) {
        // Implementation: ConcurrentHashMap with timestamp-based eviction
        return new CachingSecretsProviderRecord(delegate, ttl);
    }
}
```

### 3.5 EnvironmentIntegration (Extended)

```java
/// Faceted SPI entry point for all deployment environment interactions.
/// Each facet is Option<T> -- implementations return only the facets they support.
public interface EnvironmentIntegration {
    Option<EnvironmentIntegration> SPI = Option.from(
        ServiceLoader.load(EnvironmentIntegration.class).findFirst());

    Option<ComputeProvider> compute();
    Option<SecretsProvider> secrets();
    Option<LoadBalancerProvider> loadBalancer();

    /// REQ-EI-01: New facet for tag/label-based peer discovery.
    default Option<DiscoveryProvider> discovery() {
        return Option.empty();
    }

    // Existing factory methods remain unchanged, plus:

    /// Create an EnvironmentIntegration with all four facets.
    static EnvironmentIntegration environmentIntegration(Option<ComputeProvider> compute,
                                                          Option<SecretsProvider> secrets,
                                                          Option<LoadBalancerProvider> loadBalancer,
                                                          Option<DiscoveryProvider> discovery) {
        // Extended FacetedEnvironment record with discovery field
    }
}
```

---

## 4. Lifecycle Integration

### 4.1 Cluster Formation with Discovery

```
[Node Startup]
    |
    v
[ConfigLoader loads aether.toml]
    |
    +---> [cloud.discovery] section present?
    |         |
    |         YES --> EnvironmentIntegration.discovery()
    |         |         |
    |         |         v
    |         |    DiscoveryProvider.registerSelf(selfAddress, tags)
    |         |         |
    |         |         v
    |         |    DiscoveryProvider.discoverPeers()
    |         |         |
    |         |         v
    |         |    [Use discovered peers as coreNodes]
    |         |         |
    |         |         v
    |         |    DiscoveryProvider.watchPeers(callback)
    |         |         |
    |         |         v
    |         |    [On peer change: notify TopologyManager]
    |         |
    |         NO --> [Use static coreNodes from [cluster.peers]]
    |
    v
[RabiaEngine quorum formation]
```

**Key design decision:** Discovery replaces the `coreNodes` list at startup. Once the cluster is formed, SWIM gossip handles ongoing membership detection. Discovery is used for:
1. Initial cluster formation (finding seed nodes)
2. Cluster recovery (nodes restarting and finding the current cluster)
3. Elastic scaling (new nodes finding the cluster to join)

### 4.2 Auto-Heal with Extended ComputeProvider

```
[CDM detects cluster size < target]
    |
    v
[ComputeProvider present?]
    |
    YES --> [Build ProvisionSpec from blueprint + cloud config]
    |           pool: "core" or "elastic"
    |           instanceSize: from config
    |           tags: { "aether-cluster": clusterName, "aether-role": role }
    |           imageId: from config (pre-baked image for fast boot)
    |
    +-----> ComputeProvider.provision(spec)
    |           |
    |           v
    |       [InstanceInfo returned with addresses]
    |           |
    |           v
    |       [New node boots, discovers cluster via DiscoveryProvider or seeds]
    |           |
    |           v
    |       [Node joins cluster, CDM assigns role (core/worker)]
```

### 4.3 Load Balancer Lifecycle

```
[Node Join + Slice Activation]
    |
    v
[NDM registers routes in KV-Store (NodeRoutesKey)]
    |
    v
[LoadBalancerManager.onNodeRoutesPut]  (runs on leader)
    |
    v
[LoadBalancerProvider.onRouteChanged(routeChange)]
    |
    v
[Cloud LB target registered with node IP]

---

[Rolling Update - Weight Transition]
    |
    v
[CDM sets weights for old/new versions]
    |
    v
[LoadBalancerManager detects weight change]
    |
    v
[LoadBalancerProvider.syncWeights(weightsByIp)]
    |
    v
[Cloud LB updates weighted distribution]

---

[Node Departure]
    |
    v
[SWIM detects node down OR CDM initiates drain]
    |
    v
[LoadBalancerManager.onTopologyChange(NodeRemoved)]
    |
    v
[LoadBalancerProvider.deregisterWithDrain(nodeIp, drainTimeout)]
    |
    v
[Cloud LB drains connections, then removes target]
```

### 4.4 Secrets Resolution at Config Load

```
[Main.java bootstrap]
    |
    v
[ConfigLoader.load(path)]
    |
    v
[AetherNode construction]
    |
    v
[EnvironmentIntegration.secrets() present?]
    |
    YES --> [ConfigurationProvider.withSecretResolution(provider, sp::resolveSecret)]
    |           |
    |           v
    |       [${secrets:db/password} --> SecretsProvider.resolveSecret("db/password")]
    |           |
    |           v
    |       [Resolved config passed to ResourceFactory pipeline]
    |
    NO  --> [Config used as-is, ${secrets:...} placeholders remain unresolved]
```

**REQ-LC-01:** `CachingSecretsProvider` wraps the raw provider so repeated resolutions of the same path (e.g., during rolling restart of multiple nodes) hit the cache.

**REQ-LC-02:** When `watchRotation` is supported, the runtime should invalidate the cached secret and re-resolve affected configurations. [TBD: Exact mechanism for config hot-reload on secret rotation]

### 4.5 Node Shutdown Sequence

```
[Graceful Shutdown initiated]
    |
    v
[1. LoadBalancerProvider.deregisterWithDrain(selfIp, drainTimeout)]
    |   -- Wait for drain to complete
    v
[2. DiscoveryProvider.deregisterSelf()]
    |
    v
[3. CDM/NDM deactivation (existing)]
    |
    v
[4. SWIM departure notification (existing)]
    |
    v
[5. Process exit]
```

---

## 5. Configuration Model

### 5.1 TOML Schema

```toml
# aether.toml - Cloud Integration Configuration

[cloud]
# REQ-CF-01: Cloud provider identifier.
# Used to select the EnvironmentIntegration implementation via ServiceLoader.
# Values: "hetzner", "aws", "gcp", "azure", "digitalocean", "vultr"
provider = "hetzner"

[cloud.compute]
# REQ-CF-02: Default instance size for provisioning.
instance_size = "cx22"
# REQ-CF-03: OS image or snapshot ID for new instances.
image = "ubuntu-24.04"
# REQ-CF-04: Cloud region for provisioning.
region = "fsn1"
# REQ-CF-05: User data script executed on instance boot.
user_data = """
#!/bin/bash
curl -sSL https://install.aether.dev | bash -s -- --cluster-secret ${secrets:cluster/secret}
"""
# REQ-CF-06: Pre-baked image/snapshot ID for fast boot (~30s vs ~3min).
# When set, new instances use this instead of base image + user_data.
snapshot_id = ""

[cloud.compute.core_pool]
# REQ-CF-07: Core node pool (on-demand instances, consensus participants).
instance_type = "on_demand"
instance_size = "cx22"
min = 3
max = 7

[cloud.compute.elastic_pool]
# REQ-CF-08: Elastic node pool (spot/preemptible instances, worker-only).
instance_type = "spot"
instance_size = "cx22"
min = 0
max = 20

[cloud.compute.tags]
# REQ-CF-09: Tags applied to all provisioned instances.
"aether-cluster" = "production"
"aether-version" = "0.22.0"
"environment" = "prod"

[cloud.load_balancer]
# REQ-CF-10: Load balancer management mode.
# "managed" = Aether creates/deletes LBs. "external" = pre-existing LB.
mode = "external"
# REQ-CF-11: Provider-specific LB ID (required when mode = "external").
id = "12345"

[cloud.load_balancer.health_check]
# REQ-CF-12: Health check configuration for LB targets.
protocol = "http"
port = 8080
path = "/health/ready"
interval = "10s"
timeout = "5s"
healthy_threshold = 3
unhealthy_threshold = 3

[cloud.load_balancer.drain]
# REQ-CF-13: Connection drain timeout when removing a node from the LB.
timeout = "30s"

[cloud.load_balancer.tls]
# REQ-CF-14: TLS termination at the load balancer.
# certificate_id is provider-specific (e.g., ACM ARN for AWS, cert ID for Hetzner).
certificate_id = ""
redirect_http = true

[cloud.discovery]
# REQ-CF-15: Enable tag/label-based peer discovery.
# When enabled, [cluster.peers] is ignored; peers discovered dynamically.
enabled = false
# REQ-CF-16: Poll interval for peer discovery.
poll_interval = "10s"
# REQ-CF-17: Cluster name used in discovery tags.
cluster_name = "default"

[cloud.discovery.tags]
# REQ-CF-18: Tag selector for discovering cluster peers.
# Only instances matching ALL tags are considered peers.
"aether-cluster" = "production"
"aether-role" = "core"

[cloud.secrets]
# REQ-CF-19: Secret backend type.
# Values: "env" (environment variables), "vault" (HashiCorp Vault),
#         "aws-sm" (AWS Secrets Manager), "gcp-sm" (GCP Secret Manager),
#         "azure-kv" (Azure Key Vault)
backend = "env"
# REQ-CF-20: Cache TTL for resolved secrets. "0s" disables caching.
cache_ttl = "5m"
# REQ-CF-21: Enable rotation watching (backend must support it).
watch_rotation = false

[cloud.secrets.vault]
# REQ-CF-22: HashiCorp Vault-specific configuration.
address = "https://vault.example.com:8200"
token = "${env:VAULT_TOKEN}"
mount = "secret"
namespace = ""

[cloud.secrets.aws]
# REQ-CF-23: AWS Secrets Manager-specific configuration.
region = "us-west-2"
# Uses default AWS credential chain (env, instance profile, etc.)

# --- Provider-specific sections ---

[cloud.hetzner]
# REQ-CF-24: Hetzner-specific configuration.
api_token = "${secrets:hetzner/api_token}"
ssh_key_ids = [1234, 5678]
network_ids = [100]
firewall_ids = [200]

[cloud.aws]
# REQ-CF-25: AWS-specific configuration.
# region = "us-west-2"  (inherits from cloud.compute.region)
vpc_id = "vpc-xxxx"
subnet_ids = ["subnet-aaa", "subnet-bbb"]
security_group_ids = ["sg-xxx"]
key_pair_name = "aether-key"
iam_instance_profile = "aether-node-role"
# ALB/NLB selection
load_balancer_type = "nlb"  # "alb" or "nlb"

[cloud.gcp]
# REQ-CF-26: GCP-specific configuration.
project = "my-project"
zone = "us-central1-a"
network = "default"
subnetwork = "default"
service_account = "aether-node@my-project.iam.gserviceaccount.com"

[cloud.azure]
# REQ-CF-27: Azure-specific configuration.
subscription_id = "..."
resource_group = "aether-rg"
location = "westus2"
vnet_name = "aether-vnet"
subnet_name = "aether-subnet"
```

### 5.2 Environment Variable Overrides

All TOML values can be overridden via environment variables following the pattern:

```
AETHER_CLOUD_PROVIDER=aws
AETHER_CLOUD_COMPUTE_INSTANCE_SIZE=m6i.large
AETHER_CLOUD_COMPUTE_REGION=us-west-2
AETHER_CLOUD_DISCOVERY_ENABLED=true
AETHER_CLOUD_SECRETS_BACKEND=vault
```

**REQ-CF-28:** Environment variables take precedence over TOML values but are overridden by CLI flags.

### 5.3 Precedence Order (highest first)

1. CLI flags (`--cloud-provider=aws`)
2. Environment variables (`AETHER_CLOUD_PROVIDER=aws`)
3. TOML config file values
4. Environment-specific defaults (e.g., DOCKER/KUBERNETES defaults)

---

## 6. Error Model

### 6.1 CloudError Sealed Hierarchy

**Module:** `aether/environment-integration`
**Package:** `org.pragmatica.aether.environment`

```java
/// REQ-E-01: Sealed error hierarchy for cloud integration operations.
/// Extends EnvironmentError with cloud-specific failure categories.
public sealed interface CloudError extends Cause {

    // --- Transient errors (retryable) ---

    /// REQ-E-02: Rate limit exceeded. Retry after the specified duration.
    record RateLimited(String provider,
                       String operation,
                       TimeSpan retryAfter) implements CloudError {
        @Override
        public String message() {
            return provider + " rate limited on " + operation
                   + ", retry after " + retryAfter;
        }
    }

    /// REQ-E-03: Network timeout communicating with cloud API.
    record Timeout(String provider,
                   String operation,
                   TimeSpan elapsed) implements CloudError {
        @Override
        public String message() {
            return provider + " " + operation + " timed out after " + elapsed;
        }
    }

    /// REQ-E-04: Cloud API returned a transient server error (5xx).
    record TransientApiError(String provider,
                              int statusCode,
                              String operation,
                              String detail) implements CloudError {
        @Override
        public String message() {
            return provider + " " + operation + " failed with " + statusCode + ": " + detail;
        }
    }

    // --- Permanent errors (not retryable) ---

    /// REQ-E-05: Authentication or authorization failure.
    record AuthenticationFailed(String provider, String detail) implements CloudError {
        @Override
        public String message() {
            return provider + " authentication failed: " + detail;
        }
    }

    /// REQ-E-06: Resource not found (instance, LB, secret path, etc.).
    record ResourceNotFound(String provider,
                             String resourceType,
                             String resourceId) implements CloudError {
        @Override
        public String message() {
            return provider + " " + resourceType + " not found: " + resourceId;
        }
    }

    /// REQ-E-07: Invalid request parameters.
    record InvalidRequest(String provider,
                           String operation,
                           String detail) implements CloudError {
        @Override
        public String message() {
            return provider + " " + operation + " invalid: " + detail;
        }
    }

    /// REQ-E-08: Operation not supported by this provider.
    record OperationNotSupported(String operation) implements CloudError {
        @Override
        public String message() {
            return "Operation not supported: " + operation;
        }
    }

    // --- Quota/capacity errors (requires human intervention) ---

    /// REQ-E-09: Instance quota or resource limit reached.
    record QuotaExceeded(String provider,
                          String resourceType,
                          String detail) implements CloudError {
        @Override
        public String message() {
            return provider + " quota exceeded for " + resourceType + ": " + detail;
        }
    }

    /// REQ-E-10: Spot/preemptible instance reclaimed by provider.
    record SpotReclaimed(String provider,
                          InstanceId instanceId,
                          String detail) implements CloudError {
        @Override
        public String message() {
            return provider + " spot instance " + instanceId.value() + " reclaimed: " + detail;
        }
    }

    // --- Classification helpers ---

    /// REQ-E-11: Check if this error is retryable.
    default boolean isRetryable() {
        return switch (this) {
            case RateLimited _, Timeout _, TransientApiError _ -> true;
            default -> false;
        };
    }

    /// REQ-E-12: Check if this error requires human intervention.
    default boolean requiresHumanIntervention() {
        return switch (this) {
            case QuotaExceeded _, AuthenticationFailed _ -> true;
            default -> false;
        };
    }

    record unused() implements CloudError {
        public static Result<unused> unused() {
            return Result.success(new unused());
        }

        @Override
        public String message() {
            return "";
        }
    }
}
```

### 6.2 Retry Policy

**REQ-E-13:** CDM and LoadBalancerManager apply the following retry policy to retryable `CloudError` variants:

| Error Type | Retry Strategy | Max Retries | Backoff |
|------------|---------------|-------------|---------|
| `RateLimited` | Wait `retryAfter`, then retry | 5 | Fixed (from header) |
| `Timeout` | Immediate retry with exponential backoff | 3 | 1s, 2s, 4s |
| `TransientApiError` | Exponential backoff | 3 | 1s, 2s, 4s |
| `QuotaExceeded` | Log alert, do not retry | 0 | N/A |
| `AuthenticationFailed` | Log alert, do not retry | 0 | N/A |
| `ResourceNotFound` | Do not retry | 0 | N/A |
| `SpotReclaimed` | Provision replacement (different instance) | 1 | Immediate |

### 6.3 Relationship to Existing EnvironmentError

`CloudError` is a **sibling** sealed hierarchy to the existing `EnvironmentError`. Both implement `Cause`. The provider adapter layer maps provider-specific errors (e.g., `HetznerError`) to `CloudError` variants. The existing `EnvironmentError` types (`ProvisionFailed`, `TerminateFailed`, etc.) remain for backward compatibility but may wrap `CloudError` instances as their cause.

---

## 7. Provider Implementation Sheet Template

Each cloud provider implementation fills in this template to document the mapping between SPI methods and cloud API calls.

### Template

```
# Provider: [NAME]
# Module: aether/environment/[name]/
# Tier: [1 = major cloud, 2 = secondary]
# Status: [Planned | In Progress | Complete]

## Authentication Model
- Authentication method: [API key | IAM role | Service account | Managed identity]
- Credential source: [Environment variable | Instance metadata | Config file | Vault]
- Token refresh: [None | Automatic | Manual]

## API Mapping

### ComputeProvider

| SPI Method | Cloud API | Notes |
|------------|-----------|-------|
| provision(InstanceType) | | |
| provision(ProvisionSpec) | | |
| terminate(InstanceId) | | |
| restart(InstanceId) | | |
| listInstances() | | |
| instanceStatus(InstanceId) | | |
| applyTags(InstanceId, tags) | | |
| listInstances(TagSelector) | | |

### LoadBalancerProvider

| SPI Method | Cloud API | Notes |
|------------|-----------|-------|
| onRouteChanged(RouteChange) | | |
| onNodeRemoved(String) | | |
| reconcile(LoadBalancerState) | | |
| createLoadBalancer(spec) | | |
| deleteLoadBalancer(id) | | |
| loadBalancerInfo() | | |
| configureHealthCheck(config) | | |
| syncWeights(weightsByIp) | | |
| deregisterWithDrain(ip, timeout) | | |
| configureTls(config) | | |

### DiscoveryProvider

| SPI Method | Cloud API | Notes |
|------------|-----------|-------|
| discoverPeers() | | |
| watchPeers(callback) | | |
| registerSelf(addr, metadata) | | |
| deregisterSelf() | | |

### SecretsProvider

| SPI Method | Cloud API | Notes |
|------------|-----------|-------|
| resolveSecret(path) | | |
| resolveSecretWithMetadata(path) | | |
| resolveSecrets(paths) | | |
| watchRotation(path, callback) | | |

## Rate Limits and Quotas

| API | Rate Limit | Quota | Notes |
|-----|-----------|-------|-------|
| | | | |

## Regional Considerations

| Feature | Regional? | Zone? | Notes |
|---------|-----------|-------|-------|
| Compute | | | |
| Load Balancer | | | |
| Discovery | | | |
| Secrets | | | |

## Provider-Specific Features

| Feature | Supported | Notes |
|---------|-----------|-------|
| Spot/Preemptible instances | | |
| Placement groups | | |
| Custom images/snapshots | | |
| VPC/private networking | | |
| Weighted LB targets | | |
| LB health check customization | | |
| Native discovery service | | |
| Secret rotation notification | | |

## Estimated Effort

| Facet | Effort | Notes |
|-------|--------|-------|
| Compute | | |
| Load Balancer | | |
| Discovery | | |
| Secrets | | |
| Total | | |
```

---

## 8. Hetzner Provider Sheet (Reference)

```
# Provider: Hetzner Cloud
# Module: aether/environment/hetzner/
# Tier: 2
# Status: Partial (Compute + LB complete, Discovery + Secrets planned)

## Authentication Model
- Authentication method: API token (Bearer)
- Credential source: TOML config `cloud.hetzner.api_token` or `${secrets:hetzner/api_token}`
- Token refresh: None (static token, manually rotated in Hetzner console)

## API Mapping

### ComputeProvider

| SPI Method | Cloud API | Notes |
|------------|-----------|-------|
| provision(InstanceType) | POST /servers | **Implemented.** Maps to CreateServerRequest with config defaults. |
| provision(ProvisionSpec) | POST /servers | Extend existing: use spec.instanceSize for server_type, spec.imageId for image. |
| terminate(InstanceId) | DELETE /servers/{id} | **Implemented.** Server ID is numeric string. |
| restart(InstanceId) | POST /servers/{id}/actions/reboot | New. Hetzner has native reboot action. |
| listInstances() | GET /servers | **Implemented.** Paginated; current impl fetches first page only. |
| instanceStatus(InstanceId) | GET /servers/{id} | **Implemented.** |
| applyTags(InstanceId, tags) | PUT /servers/{id} (labels field) | Hetzner uses `labels` map on server. |
| listInstances(TagSelector) | GET /servers?label_selector=key=value | Hetzner supports label_selector query param. |

### LoadBalancerProvider

| SPI Method | Cloud API | Notes |
|------------|-----------|-------|
| onRouteChanged(RouteChange) | POST /load_balancers/{id}/actions/add_target | **Implemented.** IP-based targets. |
| onNodeRemoved(String) | POST /load_balancers/{id}/actions/remove_target | **Implemented.** |
| reconcile(LoadBalancerState) | GET /load_balancers/{id} + diff | **Implemented.** Computes add/remove diff. |
| createLoadBalancer(spec) | POST /load_balancers | HetznerClient already has createLoadBalancer. Wire to SPI. |
| deleteLoadBalancer(id) | DELETE /load_balancers/{id} | HetznerClient already has deleteLoadBalancer. Wire to SPI. |
| loadBalancerInfo() | GET /load_balancers/{id} | HetznerClient already has getLoadBalancer. Map response. |
| configureHealthCheck(config) | PUT /load_balancers/{id}/services | Hetzner health checks are per-service. |
| syncWeights(weightsByIp) | Not natively supported | Hetzner LB uses round_robin or least_connections. No per-target weights. Degrade gracefully: log warning, skip. |
| deregisterWithDrain(ip, timeout) | POST /load_balancers/{id}/actions/remove_target | No native drain. Implement via: remove target, sleep(timeout). |
| configureTls(config) | PUT /load_balancers/{id}/services (HTTPS + cert) | Hetzner supports uploaded certificates. |

### DiscoveryProvider

| SPI Method | Cloud API | Notes |
|------------|-----------|-------|
| discoverPeers() | GET /servers?label_selector=aether-cluster=X | Filter by cluster label. Extract private IPs. |
| watchPeers(callback) | Poll GET /servers?label_selector=... at interval | No native push; poll-based. |
| registerSelf(addr, metadata) | PUT /servers/{id} (set labels) | Tag self with cluster name + role. |
| deregisterSelf() | PUT /servers/{id} (remove labels) | Remove cluster labels from self. |

### SecretsProvider

| SPI Method | Cloud API | Notes |
|------------|-----------|-------|
| resolveSecret(path) | N/A | Hetzner has no secrets service. Use env vars or external backend (Vault). |
| resolveSecretWithMetadata(path) | N/A | Not applicable. |
| resolveSecrets(paths) | N/A | Not applicable. |
| watchRotation(path, callback) | N/A | Not applicable. |

## Rate Limits and Quotas

| API | Rate Limit | Quota | Notes |
|-----|-----------|-------|-------|
| All endpoints | 3,600 req/hour | Per-token | 429 with Retry-After header |
| Server creation | Part of global limit | Varies by account | Project-level server limits |
| LB operations | Part of global limit | 25 LBs/project (default) | Increase via support ticket |

## Regional Considerations

| Feature | Regional? | Zone? | Notes |
|---------|-----------|-------|-------|
| Compute | Yes (location) | No zones | Locations: fsn1, nbg1, hel1, ash, hil |
| Load Balancer | Yes (location) | No zones | Must be same location as targets |
| Discovery | Global (label filter) | N/A | Labels are global; filter by location in tags |
| Secrets | N/A | N/A | No native service |

## Provider-Specific Features

| Feature | Supported | Notes |
|---------|-----------|-------|
| Spot/Preemptible instances | No | Hetzner has no spot market |
| Placement groups | Yes | Spread placement for HA |
| Custom images/snapshots | Yes | Create from running server, use for fast boot |
| VPC/private networking | Yes | Hetzner Networks (RFC 1918) |
| Weighted LB targets | No | Round-robin or least-connections only |
| LB health check customization | Yes | Per-service health check (protocol, port, path, interval) |
| Native discovery service | No | Use label_selector on /servers |
| Secret rotation notification | No | No secrets service |

## Estimated Effort

| Facet | Effort | Notes |
|-------|--------|-------|
| Compute | 1 day | Extend existing: ProvisionSpec, tags, restart, pagination |
| Load Balancer | 2 days | Wire create/delete/info, health check config, drain simulation |
| Discovery | 2 days | New: label_selector queries, poll-based watch, self-register |
| Secrets | 0 days | Not applicable (use external backend) |
| Total | **5 days** | Incremental on existing implementation |
```

---

## 9. Testing Strategy

### 9.1 Test Layers

```
Layer 1: Unit Tests (mock provider)
    - Every SPI method has a test with canned responses
    - Error mapping tests (cloud-specific error -> CloudError variant)
    - Retry policy tests (mock clock, verify retry counts and backoff)
    - Cache tests (CachingSecretsProvider TTL eviction)

Layer 2: Integration Tests (real API, tagged for CI exclusion)
    - Requires real cloud credentials (env vars)
    - Run with Maven profile: -Pwith-cloud-tests
    - Ordered test sequences (create -> use -> delete)
    - Clean up resources even on test failure (@AfterAll)

Layer 3: E2E Tests (full cluster with cloud infra)
    - Cloud cluster formation via discovery
    - Node failure + auto-heal via ComputeProvider
    - LB target management during rolling update
    - Secret resolution during config load
```

### 9.2 Mock Provider for Unit Tests

```java
/// REQ-T-01: Mock implementation of all SPI interfaces for unit testing.
public final class MockCloudProvider implements ComputeProvider, LoadBalancerProvider,
                                                 DiscoveryProvider, SecretsProvider {
    // Canned responses, configurable per-test
    public Promise<InstanceInfo> provisionResponse = Promise.success(defaultInstance());
    public Promise<Unit> terminateResponse = Promise.success(Unit.unit());
    public Promise<List<InstanceInfo>> listResponse = Promise.success(List.of());
    public Promise<List<PeerAddress>> discoverResponse = Promise.success(List.of());
    public Promise<String> secretResponse = Promise.success("secret-value");

    // Call tracking
    public final List<ProvisionSpec> provisionCalls = new ArrayList<>();
    public final List<InstanceId> terminateCalls = new ArrayList<>();
    public final List<String> secretCalls = new ArrayList<>();

    // ... method implementations that record calls and return canned responses
}
```

### 9.3 Integration Test Requirements per Provider

Each provider must pass this minimum test set:

| Test ID | Test | Validates |
|---------|------|-----------|
| IT-01 | `provision_success_returnsRunningInstance` | ComputeProvider.provision |
| IT-02 | `terminate_success_removesInstance` | ComputeProvider.terminate |
| IT-03 | `listInstances_returnsProvisionedInstance` | ComputeProvider.listInstances |
| IT-04 | `instanceStatus_matchesProvisionedInstance` | ComputeProvider.instanceStatus |
| IT-05 | `applyTags_tagsVisible` | ComputeProvider.applyTags |
| IT-06 | `listInstancesByTag_filtersCorrectly` | ComputeProvider.listInstances(TagSelector) |
| IT-07 | `addTarget_registersInLb` | LoadBalancerProvider.onRouteChanged |
| IT-08 | `removeTarget_deregistersFromLb` | LoadBalancerProvider.onNodeRemoved |
| IT-09 | `reconcile_syncsState` | LoadBalancerProvider.reconcile |
| IT-10 | `healthCheck_configurable` | LoadBalancerProvider.configureHealthCheck |
| IT-11 | `discoverPeers_findsTaggedInstances` | DiscoveryProvider.discoverPeers |
| IT-12 | `registerSelf_makesDiscoverable` | DiscoveryProvider.registerSelf |
| IT-13 | `resolveSecret_returnsValue` | SecretsProvider.resolveSecret |

### 9.4 Test Naming Convention

Per project convention: `methodName_scenario_expectation()`

Examples:
- `provision_withOnDemandType_returnsRunningInstance()`
- `terminate_withInvalidId_returnsResourceNotFound()`
- `discoverPeers_withMatchingTags_returnsFilteredList()`

### 9.5 Existing Test Coverage

The Hetzner provider already has:
- `HetznerComputeProviderTest` -- 14 tests covering provision, terminate, list, status, address collection, status mapping
- `HetznerCloudIT` -- 7 integration tests (requires `HETZNER_API_TOKEN`)
- `LoadBalancerManagerTest` -- Tests for the LoadBalancerManager consumer

These tests serve as the pattern for new provider test classes.

---

## 10. Implementation Plan

### 10.1 Phase 1: SPI Extension (3-5 days)

**Scope:** Extend SPI interfaces, add new types, update EnvironmentIntegration.

| Task | Module | Effort |
|------|--------|--------|
| Add `ProvisionSpec`, `TagSelector` to ComputeProvider | environment-integration | 0.5 day |
| Add `tags` field to `InstanceInfo` (backward-compatible) | environment-integration | 0.5 day |
| Add new methods to `LoadBalancerProvider` with defaults | environment-integration | 0.5 day |
| Add `LoadBalancerSpec`, `LoadBalancerInfo`, `HealthCheckConfig`, `TlsTerminationConfig` | environment-integration | 0.5 day |
| Create `DiscoveryProvider` + supporting types | environment-integration | 1 day |
| Extend `SecretsProvider` with cache, batch, rotation | environment-integration | 0.5 day |
| Add `CloudError` sealed hierarchy | environment-integration | 0.5 day |
| Update `EnvironmentIntegration` with `discovery()` facet | environment-integration | 0.5 day |

### 10.2 Phase 2: Hetzner Extension (5 days)

**Scope:** Extend existing Hetzner implementation to cover new SPI methods.

| Task | Module | Effort |
|------|--------|--------|
| ComputeProvider: ProvisionSpec, restart, tags, pagination | environment/hetzner | 1 day |
| LoadBalancerProvider: create/delete/info, health check, drain | environment/hetzner | 2 days |
| DiscoveryProvider: label_selector queries, poll watch | environment/hetzner | 2 days |

### 10.3 Phase 3: Config Integration (3 days)

**Scope:** Parse `[cloud]` TOML sections, wire to EnvironmentIntegration.

| Task | Module | Effort |
|------|--------|--------|
| Extend ConfigLoader for `[cloud]` sections | aether-config | 1.5 days |
| Wire DiscoveryProvider into node bootstrap (replace static peers) | node | 1 day |
| Wire CachingSecretsProvider | node | 0.5 day |

### 10.4 Phase 4: Cloud Testing (2-3 days)

**Scope:** Validate against real Hetzner infrastructure.

| Task | Effort |
|------|--------|
| Cloud cluster formation via discovery | 1 day |
| LB target management end-to-end | 0.5 day |
| Auto-heal with real provisioning | 0.5 day |
| Rolling update with LB integration | 0.5 day |

### 10.5 Phase 5: AWS Provider (5-7 days) [Future]

| Task | Effort |
|------|--------|
| AWS SDK integration (EC2, ELB, SM) | 1 day |
| ComputeProvider (EC2) | 1.5 days |
| LoadBalancerProvider (ALB/NLB) | 2 days |
| DiscoveryProvider (EC2 tags) | 1 day |
| SecretsProvider (Secrets Manager) | 0.5 day |

### 10.6 Phase 6: GCP/Azure Providers [Future]

Similar structure to AWS, estimated 5-7 days each.

---

## 11. Open Questions

| ID | Question | Impact | Status |
|----|----------|--------|--------|
| Q-1 | Should `DiscoveryProvider` completely replace static peer lists, or fall back to static peers when discovery fails? | Cluster formation reliability | [TBD] |
| Q-2 | How should secret rotation trigger config hot-reload? Options: (a) restart affected slices, (b) inject via DynamicConfigManager, (c) signal ResourceFactory to re-provision | Secret rotation UX | [TBD] |
| Q-3 | Should weighted routing sync be pushed from LoadBalancerManager or pulled by provider on reconcile? | LB integration architecture | [ASSUMPTION: Pushed by LoadBalancerManager, since it already drives route changes] |
| Q-4 | For providers without native drain support (Hetzner), should we implement drain delay in the SPI layer or leave it to the provider? | Graceful shutdown consistency | [ASSUMPTION: Provider implements delay internally, since drain semantics vary per cloud] |
| Q-5 | Should `CloudError` extend `EnvironmentError` or be a sibling hierarchy? | Error handling consistency | [ASSUMPTION: Sibling hierarchy; both implement Cause. Migration path: providers return CloudError variants, existing consumers continue to catch EnvironmentError] |
| Q-6 | Pagination support: should `listInstances()` return all instances (auto-paginate) or support cursor-based pagination? | API design for large fleets | [ASSUMPTION: Auto-paginate internally; SPI consumers always get full list] |

---

## 12. References

### Technical Documentation

- [Hetzner Cloud API Reference](https://docs.hetzner.cloud/) -- REST API docs for servers, LBs, networks, labels
- [AWS EC2 API Reference](https://docs.aws.amazon.com/AWSEC2/latest/APIReference/) -- EC2 instance and tag operations
- [AWS Elastic Load Balancing API](https://docs.aws.amazon.com/elasticloadbalancing/latest/APIReference/) -- ALB/NLB target group management
- [AWS Secrets Manager API](https://docs.aws.amazon.com/secretsmanager/latest/apireference/) -- Secret resolution and rotation
- [GCP Compute Engine API](https://cloud.google.com/compute/docs/reference/rest/v1) -- VM instances and labels
- [GCP Secret Manager API](https://cloud.google.com/secret-manager/docs/reference/rest) -- Secret resolution
- [Azure Virtual Machines API](https://learn.microsoft.com/en-us/rest/api/compute/virtual-machines) -- VM lifecycle and tags
- [Azure Key Vault API](https://learn.microsoft.com/en-us/rest/api/keyvault/) -- Secret resolution
- [HashiCorp Vault HTTP API](https://developer.hashicorp.com/vault/api-docs) -- KV secrets engine

### Internal References

- `aether/environment-integration/src/main/java/org/pragmatica/aether/environment/` -- Existing SPI interfaces
- `aether/environment/hetzner/` -- Hetzner SPI implementation (reference for new providers)
- `integrations/cloud/hetzner/` -- Low-level Hetzner REST client
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/cluster/ClusterDeploymentManager.java` -- CDM auto-heal consumer
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/loadbalancer/LoadBalancerManager.java` -- LB manager consumer
- `aether/node/src/main/java/org/pragmatica/aether/Main.java` -- Bootstrap and config loading
- `aether/docs/internal/progress/development-priorities.md` -- Priority #1: Cloud Integration
- `aether/docs/operators/infrastructure-design.md` -- Terraform layer design (separate from SPI)
- `aether/docs/reference/configuration.md` -- Existing TOML configuration reference

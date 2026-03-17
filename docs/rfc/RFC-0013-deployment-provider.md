---
RFC: 0013
Title: DeploymentProvider SPI
Status: Draft
Author: Aether Team
Created: 2026-03-02
Updated: 2026-03-02
Affects: [environment-integration, hetzner, cloud-tests, aether-setup, node]
---

## Summary

Introduces a `DeploymentProvider` SPI that bridges the gap between cloud compute provisioning (`ComputeProvider`) and running Aether nodes on provisioned infrastructure. The SPI defines two deployment strategies -- container-based and binary-based -- with a common interface for health verification, log access, graceful shutdown, and rolling upgrades. The existing ad-hoc deployment code in `aether/cloud-tests/` is extracted into a reusable SPI implementation, and `DeploymentProvider` becomes a new facet on `EnvironmentIntegration`.

## Motivation

### The Missing Layer

Aether's infrastructure story has two well-defined layers today:

| Layer | What it does | Where it lives |
|-------|-------------|----------------|
| **Compute provisioning** | Create/terminate VMs | `ComputeProvider` SPI (`environment-integration`) |
| **Static config generation** | Generate Docker Compose, K8s manifests, shell scripts | `Generator` pattern (`aether-setup`) |

Neither layer answers the question: *"Given a running VM with an IP address, how do I get an Aether node running on it and confirm it joined the cluster?"*

That question is answered today only by ad-hoc test code:

```
HetznerCloudCluster.provision()
  → createServers()           // ComputeProvider territory
  → waitForServersRunning()   // ComputeProvider territory
  → waitForSshConnectivity()  // *** DEPLOYMENT GAP ***
  → verifyJavaInstalled()     // *** DEPLOYMENT GAP ***
  → deployJars()              // *** DEPLOYMENT GAP ***
  → startAllNodes()           // *** DEPLOYMENT GAP ***
  → awaitAllHealthy()         // *** DEPLOYMENT GAP ***
```

File: `aether/cloud-tests/src/test/java/org/pragmatica/aether/cloud/HetznerCloudCluster.java`

### Current Pain Points

1. **No reusable deployment abstraction** -- Every consumer (cloud-tests, future CLI `aether deploy`, CDM auto-heal) must re-implement SSH/SCP/health-check logic.
2. **No container deployment path at runtime** -- `DockerGenerator` and `KubernetesGenerator` produce static files. Nothing orchestrates `docker run` or `docker run` against a live target host.
3. **Binary deployment is test-only** -- The SCP + nohup pattern in `HetznerCloudCluster` and `CloudNode` is not accessible from production code.
4. **No rolling upgrade automation** -- The deployment runbook (`aether/docs/operators/runbooks/deployment.md`) documents a manual stop-copy-start cycle.
5. **No integration with CDM** -- `ClusterDeploymentManager` can call `ComputeProvider.provision()` to create VMs but has no way to deploy Aether onto them.

### Goals

- **REQ-D01**: Define a `DeploymentProvider` SPI with two strategy variants (container, binary).
- **REQ-D02**: Integrate as a facet on `EnvironmentIntegration` alongside `ComputeProvider`, `LoadBalancerProvider`, `SecretsProvider`.
- **REQ-D03**: Return `Promise<T>` for all async operations; no nested error channels.
- **REQ-D04**: Model deployment failures as sealed `Cause` types.
- **REQ-D05**: Support the full lifecycle: deploy, health-verify, log-access, upgrade, shutdown.
- **REQ-D06**: Extract `cloud-tests` ad-hoc code into a `BinaryDeploymentProvider` implementation.
- **REQ-D07**: Provide a `ContainerDeploymentProvider` implementation for Docker/Docker.
- **REQ-D08**: Enable CDM auto-heal to provision + deploy in a single orchestrated flow.

## Design

### Boundaries

- **`environment-integration`**: Owns the `DeploymentProvider` SPI interface, `DeploymentStrategy`, `DeploymentConfig`, `NodeDeployment`, and `DeploymentError` types. Also extends `EnvironmentIntegration` with the new facet.
- **`hetzner`**: Implements `HetznerBinaryDeploymentProvider` and `HetznerContainerDeploymentProvider` using existing SSH/SCP utilities and Hetzner-specific container runtime commands.
- **`cloud-tests`**: Migrates from inline deployment code to consuming the `DeploymentProvider` SPI. `HetznerCloudCluster` becomes a thin orchestrator.
- **`aether-setup`**: Generators remain unchanged; they produce static artifacts. `DeploymentProvider` is the runtime complement.
- **`node`**: `AetherNode` and `ClusterDeploymentManager` gain access to deployment operations through the `EnvironmentIntegration` facet.

### 1. DeploymentProvider SPI

```java
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;

/// SPI for deploying and managing Aether node processes on provisioned instances.
/// Implementations handle the runtime deployment lifecycle: install, start, verify,
/// upgrade, and stop Aether nodes on target hosts.
///
/// Works with InstanceInfo from ComputeProvider — after provisioning creates the
/// infrastructure, DeploymentProvider puts Aether on it.
public interface DeploymentProvider {

    /// Deploy an Aether node to the target instance.
    /// Returns a handle representing the deployed node.
    Promise<NodeDeployment> deploy(DeploymentRequest request);

    /// Query the deployment status of a previously deployed node.
    Promise<NodeDeployment> status(InstanceId instanceId);

    /// List all active deployments managed by this provider.
    Promise<List<NodeDeployment>> listDeployments();

    /// Perform a rolling upgrade on a single deployed node.
    /// Deploys the new version, verifies health, then stops the old process.
    Promise<NodeDeployment> upgrade(InstanceId instanceId, DeploymentArtifact newArtifact);

    /// Gracefully shut down the Aether process on the target instance.
    /// Initiates drain, waits for slice migration, then stops the process.
    Promise<Unit> shutdown(InstanceId instanceId);

    /// Forcefully stop the Aether process on the target instance.
    /// No drain or graceful handoff -- immediate termination.
    Promise<Unit> kill(InstanceId instanceId);
}
```

### 2. DeploymentStrategy

A sealed interface distinguishing between deployment modes. Each variant carries the configuration specific to its deployment model.

```java
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Result;

import java.nio.file.Path;
import java.util.List;

import static org.pragmatica.lang.Result.success;

/// Deployment strategy determines HOW the Aether runtime is placed on a target host.
public sealed interface DeploymentStrategy {

    /// Deploy via container runtime (Docker, Docker, or orchestrator).
    record Container(String image,
                     String registry,
                     ContainerRuntime runtime,
                     List<String> extraArgs) implements DeploymentStrategy {
        public static Result<Container> container(String image,
                                                   String registry,
                                                   ContainerRuntime runtime,
                                                   List<String> extraArgs) {
            return success(new Container(image, registry, runtime, List.copyOf(extraArgs)));
        }
    }

    /// Deploy by uploading a JAR binary and starting a JVM process.
    record Binary(ArtifactSource artifactSource,
                  JdkBootstrap jdkBootstrap,
                  ProcessManager processManager) implements DeploymentStrategy {
        public static Result<Binary> binary(ArtifactSource artifactSource,
                                             JdkBootstrap jdkBootstrap,
                                             ProcessManager processManager) {
            return success(new Binary(artifactSource, jdkBootstrap, processManager));
        }
    }

    /// How to obtain the container image or JAR artifact.
    sealed interface ArtifactSource {
        /// Upload a local file to the target via SCP/rsync.
        record LocalFile(Path path) implements ArtifactSource {
            public static Result<LocalFile> localFile(Path path) {
                return success(new LocalFile(path));
            }
        }

        /// Pull from a remote URL (GitHub Releases, artifact repo).
        record RemoteUrl(String url) implements ArtifactSource {
            public static Result<RemoteUrl> remoteUrl(String url) {
                return success(new RemoteUrl(url));
            }
        }

        /// Already present on the target (pre-baked image).
        record PreInstalled(Path remotePath) implements ArtifactSource {
            public static Result<PreInstalled> preInstalled(Path remotePath) {
                return success(new PreInstalled(remotePath));
            }
        }
    }

    /// How to bootstrap a JDK on the target host if needed.
    sealed interface JdkBootstrap {
        /// JDK already available on the target (pre-baked image or cloud-init).
        record PreInstalled() implements JdkBootstrap {
            public static Result<PreInstalled> preInstalled() {
                return success(new PreInstalled());
            }
        }

        /// Install JDK via cloud-init user data script.
        record CloudInit(String script) implements JdkBootstrap {
            public static Result<CloudInit> cloudInit(String script) {
                return success(new CloudInit(script));
            }
        }

        /// Download and install JDK from Adoptium API.
        record Adoptium(int majorVersion) implements JdkBootstrap {
            public static Result<Adoptium> adoptium(int majorVersion) {
                return success(new Adoptium(majorVersion));
            }
        }
    }

    /// How to manage the Aether process on the target.
    sealed interface ProcessManager {
        /// Use systemd for process management.
        record Systemd(String unitName) implements ProcessManager {
            public static Result<Systemd> systemd(String unitName) {
                return success(new Systemd(unitName));
            }
        }

        /// Use nohup for simple background process (test/dev only).
        record Nohup() implements ProcessManager {
            public static Result<Nohup> nohup() {
                return success(new Nohup());
            }
        }
    }

    /// Supported container runtimes.
    enum ContainerRuntime {
        DOCKER,
        PODMAN
    }
}
```

### 3. DeploymentRequest

Encapsulates everything needed to deploy a single Aether node.

```java
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;

/// A request to deploy an Aether node to a target instance.
///
/// Combines the target instance information (from ComputeProvider),
/// the deployment strategy, and the node-specific configuration.
public record DeploymentRequest(InstanceInfo target,
                                DeploymentStrategy strategy,
                                NodeDeploymentConfig nodeConfig) {
    public static Result<DeploymentRequest> deploymentRequest(InstanceInfo target,
                                                               DeploymentStrategy strategy,
                                                               NodeDeploymentConfig nodeConfig) {
        return success(new DeploymentRequest(target, strategy, nodeConfig));
    }
}
```

### 4. NodeDeploymentConfig

Per-node configuration for the Aether process. Generates either environment variables (container) or TOML config (binary).

```java
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.List;

import static org.pragmatica.lang.Result.success;

/// Configuration for a single Aether node deployment.
/// Contains all parameters needed to start an Aether node process.
public record NodeDeploymentConfig(String nodeId,
                                   int clusterPort,
                                   int managementPort,
                                   List<PeerAddress> peers,
                                   String javaOpts,
                                   Option<String> configToml) {
    /// A peer address in the cluster.
    public record PeerAddress(String nodeId, String host, int port) {
        public static Result<PeerAddress> peerAddress(String nodeId, String host, int port) {
            return success(new PeerAddress(nodeId, host, port));
        }

        /// Format as the peer list string expected by Aether CLI args.
        public String toCliFormat() {
            return nodeId + ":" + host + ":" + port;
        }
    }

    public static Result<NodeDeploymentConfig> nodeDeploymentConfig(String nodeId,
                                                                     int clusterPort,
                                                                     int managementPort,
                                                                     List<PeerAddress> peers,
                                                                     String javaOpts,
                                                                     Option<String> configToml) {
        return success(new NodeDeploymentConfig(nodeId, clusterPort, managementPort,
                                                List.copyOf(peers), javaOpts, configToml));
    }

    /// Format the peer list as a comma-separated string for --peers argument.
    public String peerListString() {
        return peers.stream()
                    .map(PeerAddress::toCliFormat)
                    .reduce((a, b) -> a + "," + b)
                    .orElse("");
    }

    /// Generate environment variables for container deployment.
    public List<EnvVar> toEnvVars() {
        return List.of(
            new EnvVar("NODE_ID", nodeId),
            new EnvVar("CLUSTER_PORT", String.valueOf(clusterPort)),
            new EnvVar("MANAGEMENT_PORT", String.valueOf(managementPort)),
            new EnvVar("PEERS", peerListString()),
            new EnvVar("JAVA_OPTS", javaOpts));
    }

    public record EnvVar(String name, String value) {}
}
```

### 5. DeploymentArtifact

Represents a versioned artifact to deploy (used for upgrades).

```java
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;

/// A versioned deployment artifact.
/// For container strategy: an image tag. For binary strategy: a JAR location.
public record DeploymentArtifact(String version,
                                  DeploymentStrategy.ArtifactSource source) {
    public static Result<DeploymentArtifact> deploymentArtifact(String version,
                                                                 DeploymentStrategy.ArtifactSource source) {
        return success(new DeploymentArtifact(version, source));
    }
}
```

### 6. NodeDeployment

Represents an active deployment. Provides operations on a deployed node.

```java
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;

/// Handle to a deployed Aether node.
/// Provides status queries and operational commands against the running deployment.
public record NodeDeployment(InstanceId instanceId,
                              String nodeId,
                              String address,
                              int managementPort,
                              DeploymentStatus status,
                              String version) {
    public static Result<NodeDeployment> nodeDeployment(InstanceId instanceId,
                                                         String nodeId,
                                                         String address,
                                                         int managementPort,
                                                         DeploymentStatus status,
                                                         String version) {
        return success(new NodeDeployment(instanceId, nodeId, address,
                                          managementPort, status, version));
    }

    /// Management API base URL for this deployment.
    public String managementUrl() {
        return "http://" + address + ":" + managementPort;
    }
}
```

### 7. DeploymentStatus

Lifecycle states of a deployment (distinct from `InstanceStatus` which tracks VM state and `NodeLifecycleState` which tracks consensus-level state).

```java
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;

/// Deployment-level status of an Aether node.
///
/// This is distinct from:
///   - InstanceStatus (VM lifecycle: PROVISIONING → RUNNING → STOPPING → TERMINATED)
///   - NodeLifecycleState (consensus-level: JOINING → ON_DUTY → DRAINING → DECOMMISSIONED)
///
/// DeploymentStatus tracks the software installation and process state.
public sealed interface DeploymentStatus {

    /// Artifact is being transferred to the target.
    record Installing() implements DeploymentStatus {
        public static Result<Installing> installing() {
            return success(new Installing());
        }
    }

    /// Process is starting, not yet responding to health checks.
    record Starting() implements DeploymentStatus {
        public static Result<Starting> starting() {
            return success(new Starting());
        }
    }

    /// Health check passed, node is operational.
    record Healthy() implements DeploymentStatus {
        public static Result<Healthy> healthy() {
            return success(new Healthy());
        }
    }

    /// Health check failing or node unresponsive.
    record Unhealthy(String reason) implements DeploymentStatus {
        public static Result<Unhealthy> unhealthy(String reason) {
            return success(new Unhealthy(reason));
        }
    }

    /// Node is draining before shutdown.
    record Draining() implements DeploymentStatus {
        public static Result<Draining> draining() {
            return success(new Draining());
        }
    }

    /// Process has been stopped.
    record Stopped() implements DeploymentStatus {
        public static Result<Stopped> stopped() {
            return success(new Stopped());
        }
    }

    /// Deployment failed.
    record Failed(String reason) implements DeploymentStatus {
        public static Result<Failed> failed(String reason) {
            return success(new Failed(reason));
        }
    }

    DeploymentStatus INSTALLING = Installing.installing().unwrap();
    DeploymentStatus STARTING = Starting.starting().unwrap();
    DeploymentStatus HEALTHY = Healthy.healthy().unwrap();
    DeploymentStatus DRAINING = Draining.draining().unwrap();
    DeploymentStatus STOPPED = Stopped.stopped().unwrap();

    record unused() implements DeploymentStatus {
        public static Result<unused> unused() {
            return success(new unused());
        }
    }
}
```

### 8. DeploymentError

Sealed error types for deployment failures, following the pattern of `EnvironmentError` and `CloudTestError`.

```java
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Cause;

import java.time.Duration;

/// Error causes for deployment operations.
/// Follows the same pattern as EnvironmentError (sealed, implements Cause).
public sealed interface DeploymentError extends Cause {

    /// SSH/SCP connection to target failed.
    record ConnectionFailed(String host, String detail) implements DeploymentError {
        @Override
        public String message() {
            return "Connection to " + host + " failed: " + detail;
        }
    }

    /// SSH connectivity was not established within the timeout.
    record ConnectionTimeout(String host, Duration timeout) implements DeploymentError {
        @Override
        public String message() {
            return "SSH connection to " + host + " timed out after " + timeout;
        }
    }

    /// Artifact upload (SCP, rsync, or pull) failed.
    record ArtifactTransferFailed(String host, String detail) implements DeploymentError {
        @Override
        public String message() {
            return "Artifact transfer to " + host + " failed: " + detail;
        }
    }

    /// JDK bootstrap failed on target.
    record JdkBootstrapFailed(String host, String detail) implements DeploymentError {
        @Override
        public String message() {
            return "JDK bootstrap on " + host + " failed: " + detail;
        }
    }

    /// Container image pull failed.
    record ImagePullFailed(String image, String host, String detail) implements DeploymentError {
        @Override
        public String message() {
            return "Image pull of " + image + " on " + host + " failed: " + detail;
        }
    }

    /// Aether process failed to start.
    record ProcessStartFailed(String nodeId, String host, String detail) implements DeploymentError {
        @Override
        public String message() {
            return "Process start for " + nodeId + " on " + host + " failed: " + detail;
        }
    }

    /// Health check did not pass within the allotted time.
    record HealthCheckTimeout(String nodeId, String host, Duration timeout) implements DeploymentError {
        @Override
        public String message() {
            return "Health check for " + nodeId + " on " + host
                   + " did not pass within " + timeout;
        }
    }

    /// Health check endpoint returned an error.
    record HealthCheckFailed(String nodeId, String host, String detail) implements DeploymentError {
        @Override
        public String message() {
            return "Health check for " + nodeId + " on " + host + " failed: " + detail;
        }
    }

    /// Graceful shutdown failed (drain did not complete).
    record ShutdownFailed(String nodeId, String host, String detail) implements DeploymentError {
        @Override
        public String message() {
            return "Shutdown of " + nodeId + " on " + host + " failed: " + detail;
        }
    }

    /// Upgrade failed at some stage.
    record UpgradeFailed(String nodeId, String fromVersion,
                         String toVersion, String detail) implements DeploymentError {
        @Override
        public String message() {
            return "Upgrade of " + nodeId + " from " + fromVersion
                   + " to " + toVersion + " failed: " + detail;
        }
    }

    /// Log retrieval failed.
    record LogRetrievalFailed(String nodeId, String detail) implements DeploymentError {
        @Override
        public String message() {
            return "Log retrieval for " + nodeId + " failed: " + detail;
        }
    }

    /// Deployment not found for the given instance.
    record DeploymentNotFound(InstanceId instanceId) implements DeploymentError {
        @Override
        public String message() {
            return "No deployment found for instance: " + instanceId.value();
        }
    }
}
```

### 9. EnvironmentIntegration Extension

Add `DeploymentProvider` as a new facet:

```java
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.ServiceLoader;

import static org.pragmatica.lang.Option.empty;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;

/// Faceted SPI entry point for all deployment environment interactions.
///
/// Each facet is Option<T> — implementations return only the facets they support.
/// Discovered via ServiceLoader.
public interface EnvironmentIntegration {
    Option<EnvironmentIntegration> SPI = Option.from(ServiceLoader.load(EnvironmentIntegration.class)
                                                                  .findFirst());

    Option<ComputeProvider> compute();

    Option<SecretsProvider> secrets();

    Option<LoadBalancerProvider> loadBalancer();

    /// NEW: Deployment of Aether runtime onto provisioned instances.
    Option<DeploymentProvider> deployment();

    /// Create an EnvironmentIntegration with all specified facets.
    static EnvironmentIntegration environmentIntegration(Option<ComputeProvider> compute,
                                                         Option<SecretsProvider> secrets,
                                                         Option<LoadBalancerProvider> loadBalancer,
                                                         Option<DeploymentProvider> deployment) {
        return FacetedEnvironment.facetedEnvironment(compute, secrets, loadBalancer, deployment)
                                 .unwrap();
    }

    record FacetedEnvironment(Option<ComputeProvider> compute,
                              Option<SecretsProvider> secrets,
                              Option<LoadBalancerProvider> loadBalancer,
                              Option<DeploymentProvider> deployment) implements EnvironmentIntegration {
        public static Result<FacetedEnvironment> facetedEnvironment(
                Option<ComputeProvider> compute,
                Option<SecretsProvider> secrets,
                Option<LoadBalancerProvider> loadBalancer,
                Option<DeploymentProvider> deployment) {
            return success(new FacetedEnvironment(compute, secrets, loadBalancer, deployment));
        }
    }
}
```

The existing `withCompute()` convenience method and the 3-argument `environmentIntegration()` method will be retained as deprecated overloads during migration, delegating to the new 4-argument version with `deployment = empty()`.

### 10. Container Strategy Implementation Sketch

The container deployment flow for a Hetzner VM with Docker/Docker installed:

```java
package org.pragmatica.aether.environment.hetzner;

/// Hetzner container-based deployment.
/// Assumes the target VM has a container runtime (docker or docker) pre-installed
/// via cloud-init or pre-baked image.
public record HetznerContainerDeploymentProvider(
        SshClient sshClient,
        DeploymentStrategy.Container strategy,
        HealthChecker healthChecker) implements DeploymentProvider {

    public static Result<HetznerContainerDeploymentProvider> hetznerContainerDeploymentProvider(
            SshClient sshClient,
            DeploymentStrategy.Container strategy,
            HealthChecker healthChecker) {
        return success(new HetznerContainerDeploymentProvider(sshClient, strategy, healthChecker));
    }

    @Override
    public Promise<NodeDeployment> deploy(DeploymentRequest request) {
        var host = primaryAddress(request.target());
        var config = request.nodeConfig();

        return pullImage(host)                                          // Step 1: pull image
            .flatMap(_ -> runContainer(host, config))                   // Step 2: start container
            .flatMap(_ -> healthChecker.awaitHealthy(host, config))     // Step 3: verify health
            .map(_ -> toNodeDeployment(request, DeploymentStatus.HEALTHY));
    }

    // --- Leaf: pull container image on remote host ---
    private Promise<Unit> pullImage(String host) {
        var cmd = strategy.runtime().name().toLowerCase()
                  + " pull " + strategy.registry() + "/" + strategy.image();
        return sshClient.execute(host, cmd).mapToUnit();
    }

    // --- Leaf: run container on remote host ---
    private Promise<Unit> runContainer(String host, NodeDeploymentConfig config) {
        var rt = strategy.runtime().name().toLowerCase();
        var envFlags = config.toEnvVars().stream()
                             .map(e -> "-e " + e.name() + "=" + e.value())
                             .reduce((a, b) -> a + " " + b)
                             .orElse("");
        var cmd = rt + " run -d --name aether-" + config.nodeId()
                  + " --network host"
                  + " " + envFlags
                  + " " + String.join(" ", strategy.extraArgs())
                  + " " + strategy.registry() + "/" + strategy.image();
        return sshClient.execute(host, cmd).mapToUnit();
    }

    // ... shutdown, upgrade, etc.
}
```

### 11. Binary Strategy Implementation Sketch

Extracted from the current `HetznerCloudCluster` and `CloudNode` code:

```java
package org.pragmatica.aether.environment.hetzner;

/// Hetzner binary-based deployment.
/// Extracted from aether/cloud-tests HetznerCloudCluster + CloudNode.
public record HetznerBinaryDeploymentProvider(
        SshClient sshClient,
        DeploymentStrategy.Binary strategy,
        HealthChecker healthChecker) implements DeploymentProvider {

    public static Result<HetznerBinaryDeploymentProvider> hetznerBinaryDeploymentProvider(
            SshClient sshClient,
            DeploymentStrategy.Binary strategy,
            HealthChecker healthChecker) {
        return success(new HetznerBinaryDeploymentProvider(sshClient, strategy, healthChecker));
    }

    @Override
    public Promise<NodeDeployment> deploy(DeploymentRequest request) {
        var host = primaryAddress(request.target());
        var config = request.nodeConfig();

        return ensureJdk(host)                                          // Step 1: JDK
            .flatMap(_ -> transferArtifact(host))                       // Step 2: upload JAR
            .flatMap(_ -> generateConfig(host, config))                 // Step 3: TOML config
            .flatMap(_ -> startProcess(host, config))                   // Step 4: start JVM
            .flatMap(_ -> healthChecker.awaitHealthy(host, config))     // Step 5: health check
            .map(_ -> toNodeDeployment(request, DeploymentStatus.HEALTHY));
    }

    // --- Leaf: ensure JDK is available ---
    private Promise<Unit> ensureJdk(String host) {
        return switch (strategy.jdkBootstrap()) {
            case DeploymentStrategy.JdkBootstrap.PreInstalled() ->
                sshClient.execute(host, "java -version").mapToUnit();
            case DeploymentStrategy.JdkBootstrap.CloudInit(var script) ->
                Promise.success(Unit.unit()); // Already applied via cloud-init userData
            case DeploymentStrategy.JdkBootstrap.Adoptium(var version) ->
                installAdoptium(host, version);
        };
    }

    // --- Leaf: install Adoptium JDK ---
    // Extracted from HetznerCloudCluster.CLOUD_INIT_SCRIPT
    private Promise<Unit> installAdoptium(String host, int majorVersion) {
        var script = """
            mkdir -p /opt/java && cd /opt/java && \
            wget -q https://api.adoptium.net/v3/binary/latest/%d/ga/linux/x64/jdk/hotspot/normal/eclipse \
              -O jdk.tar.gz && \
            tar xzf jdk.tar.gz --strip-components=1 && \
            ln -sf /opt/java/bin/java /usr/local/bin/java
            """.formatted(majorVersion);
        return sshClient.execute(host, script).mapToUnit();
    }

    // --- Leaf: transfer artifact to target ---
    private Promise<Unit> transferArtifact(String host) {
        return switch (strategy.artifactSource()) {
            case DeploymentStrategy.ArtifactSource.LocalFile(var path) ->
                sshClient.upload(host, path, "/opt/aether/aether-node.jar");
            case DeploymentStrategy.ArtifactSource.RemoteUrl(var url) ->
                sshClient.execute(host, "wget -q " + url + " -O /opt/aether/aether-node.jar")
                         .mapToUnit();
            case DeploymentStrategy.ArtifactSource.PreInstalled(_) ->
                Promise.success(Unit.unit());
        };
    }

    // --- Leaf: start the JVM process ---
    // Extracted from CloudNode.startNode()
    private Promise<Unit> startProcess(String host, NodeDeploymentConfig config) {
        var command = switch (strategy.processManager()) {
            case DeploymentStrategy.ProcessManager.Nohup() -> nohupCommand(config);
            case DeploymentStrategy.ProcessManager.Systemd(var unit) -> systemdCommand(unit);
        };
        return sshClient.execute(host, command).mapToUnit();
    }

    // Mirrors CloudNode.startNode() exactly
    private String nohupCommand(NodeDeploymentConfig config) {
        return "nohup java " + config.javaOpts()
               + " -jar /opt/aether/aether-node.jar"
               + " --node-id=" + config.nodeId()
               + " --port=" + config.clusterPort()
               + " --management-port=" + config.managementPort()
               + " --peers=" + config.peerListString()
               + " > /opt/aether/node.log 2>&1 &";
    }

    private String systemdCommand(String unitName) {
        return "systemctl start " + unitName;
    }

    // ... shutdown, upgrade, etc.
}
```

### 12. HealthChecker

A shared utility used by both deployment strategies. Extracted from `HetznerCloudCluster.awaitAllHealthy()` and `CloudNode.getHealth()`.

```java
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.time.Duration;

/// Verifies that a deployed Aether node is healthy and has joined the cluster.
/// Used by both container and binary deployment strategies.
public interface HealthChecker {

    /// Poll the /api/health endpoint until the node reports ready.
    Promise<Unit> awaitHealthy(String host, NodeDeploymentConfig config);

    /// Poll the /api/nodes endpoint until the node appears in the cluster.
    Promise<Unit> awaitClusterJoin(String host, NodeDeploymentConfig config);

    /// Single health check (no polling).
    Promise<HealthResult> checkHealth(String host, int managementPort);

    /// Health check result.
    record HealthResult(boolean ready, String rawResponse) {
        public static HealthResult healthResult(boolean ready, String rawResponse) {
            return new HealthResult(ready, rawResponse);
        }
    }
}
```

Default implementation uses HTTP client (extracted from `CloudNode.httpGet()`):

```java
/// Default health checker using HTTP polling.
/// Polls GET /api/health and checks for {"ready":true}.
///
/// Extracted from:
///   - CloudNode.getHealth() — single health check
///   - HetznerCloudCluster.awaitAllHealthy() — polling loop
public record HttpHealthChecker(Duration pollInterval,
                                 Duration timeout) implements HealthChecker {
    public static Result<HttpHealthChecker> httpHealthChecker(Duration pollInterval,
                                                               Duration timeout) {
        return success(new HttpHealthChecker(pollInterval, timeout));
    }

    // Implementation polls http://{host}:{managementPort}/api/health
    // Returns Promise.success when ready:true, or DeploymentError.HealthCheckTimeout
}
```

### 13. SshClient Interface

Abstraction over SSH/SCP, extracted from `RemoteCommandRunner`:

```java
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.nio.file.Path;

/// Abstraction for remote command execution and file transfer.
/// Extracted from aether/cloud-tests RemoteCommandRunner.
public interface SshClient {

    /// Execute a command on the remote host.
    Promise<String> execute(String host, String command);

    /// Upload a local file to the remote host.
    Promise<Unit> upload(String host, Path localFile, String remotePath);

    /// Download a remote file to a local path.
    Promise<String> download(String host, String remotePath);

    /// Wait until SSH connectivity is established.
    Promise<Unit> awaitConnectivity(String host);
}
```

### 14. Orchestration Flow

The complete provision-to-ready flow, showing how `ComputeProvider` and `DeploymentProvider` work together:

```
 CDM / CLI / Test
      │
      ▼
 ┌─────────────────────────┐
 │  1. ComputeProvider      │
 │     .provision()         │
 │                          │
 │  Returns: InstanceInfo   │
 │    id: "12345"           │
 │    status: RUNNING       │
 │    addresses: [1.2.3.4]  │
 │    type: ON_DEMAND       │
 └─────────┬───────────────┘
           │
           ▼
 ┌─────────────────────────┐
 │  2. Build DeploymentReq  │
 │                          │
 │  target: instanceInfo    │
 │  strategy: Binary(...)   │
 │  nodeConfig:             │
 │    nodeId: "node-3"      │
 │    clusterPort: 8090     │
 │    peers: [node-0:...,   │
 │            node-1:...,   │
 │            node-2:...]   │
 └─────────┬───────────────┘
           │
           ▼
 ┌─────────────────────────┐
 │  3. DeploymentProvider   │
 │     .deploy(request)     │
 │                          │
 │  Binary strategy:        │
 │    → ensureJdk           │
 │    → transferArtifact    │
 │    → generateConfig      │
 │    → startProcess        │
 │    → awaitHealthy        │
 │                          │
 │  Container strategy:     │
 │    → pullImage           │
 │    → runContainer        │
 │    → awaitHealthy        │
 │                          │
 │  Returns: NodeDeployment │
 │    status: HEALTHY       │
 └─────────┬───────────────┘
           │
           ▼
 ┌─────────────────────────┐
 │  4. Cluster integration  │
 │                          │
 │  Node auto-transitions:  │
 │    JOINING → ON_DUTY     │
 │  (via consensus layer)   │
 │                          │
 │  LoadBalancerProvider     │
 │    .onRouteChanged(...)  │
 │  (adds node as target)   │
 └─────────────────────────┘
```

### 15. Rolling Upgrade Flow

Orchestrated by the caller (CDM or CLI). Per-node:

```
 ┌─────────────────────────────────────────────────────────────────┐
 │ For each node (one at a time, preserving quorum):               │
 │                                                                 │
 │  1. DeploymentProvider.status(instanceId)                       │
 │     → Verify current deployment is HEALTHY                      │
 │                                                                 │
 │  2. NodeLifecycleRoutes: POST /api/node/drain/{nodeId}          │
 │     → Transition: ON_DUTY → DRAINING                            │
 │     → Wait for slices to migrate off                            │
 │                                                                 │
 │  3. HealthChecker.awaitClusterJoin() on remaining nodes         │
 │     → Verify quorum maintained                                  │
 │                                                                 │
 │  4. DeploymentProvider.upgrade(instanceId, newArtifact)         │
 │     Binary:                                                     │
 │       → Stop old process (kill java or systemctl stop)          │
 │       → Transfer new JAR                                        │
 │       → Start new process                                       │
 │       → awaitHealthy                                            │
 │     Container:                                                  │
 │       → Stop old container                                      │
 │       → Pull new image                                          │
 │       → Run new container                                       │
 │       → awaitHealthy                                            │
 │                                                                 │
 │  5. Node auto-transitions: JOINING → ON_DUTY                   │
 │                                                                 │
 │  6. LoadBalancerProvider.reconcile(...)                          │
 │     → Re-add node as LB target                                  │
 │                                                                 │
 │  7. Verify full cluster health before proceeding to next node   │
 └─────────────────────────────────────────────────────────────────┘
```

### 16. Three-Status Layering

It is important to understand that three independent status models coexist, each tracking a different concern:

| Status Type | Tracks | Owner | Values |
|------------|--------|-------|--------|
| `InstanceStatus` | VM/server lifecycle | `ComputeProvider` | PROVISIONING, RUNNING, STOPPING, TERMINATED |
| `DeploymentStatus` | Software installation & process | `DeploymentProvider` | INSTALLING, STARTING, HEALTHY, UNHEALTHY, DRAINING, STOPPED, FAILED |
| `NodeLifecycleState` | Consensus cluster membership | Aether consensus layer | JOINING, ON_DUTY, DRAINING, DECOMMISSIONED, SHUTTING_DOWN |

A fully operational node has: `InstanceStatus.RUNNING` + `DeploymentStatus.HEALTHY` + `NodeLifecycleState.ON_DUTY`.

The shutdown sequence progresses bottom-up:
1. `NodeLifecycleState.ON_DUTY → DRAINING → DECOMMISSIONED` (slices migrate)
2. `DeploymentStatus.HEALTHY → DRAINING → STOPPED` (process stops)
3. `InstanceStatus.RUNNING → STOPPING → TERMINATED` (VM destroyed)

## Hetzner Implementation Plan

### Phase 1: Extract SSH utilities (REQ-D06)

**Source files:**
- `aether/cloud-tests/src/test/java/org/pragmatica/aether/cloud/RemoteCommandRunner.java`
- `aether/cloud-tests/src/test/java/org/pragmatica/aether/cloud/CloudNode.java`

**Target:** `aether/environment/hetzner/src/main/java/.../hetzner/ssh/`

| Source code | Destination |
|------------|-------------|
| `RemoteCommandRunner.ssh()` | `ProcessSshClient.execute()` |
| `RemoteCommandRunner.scp()` | `ProcessSshClient.upload()` |
| `RemoteCommandRunner.waitForSsh()` | `ProcessSshClient.awaitConnectivity()` |
| `CloudNode.httpGet()` | `HttpHealthChecker.checkHealth()` |
| `CloudNode.getHealth()` | `HttpHealthChecker.checkHealth()` |
| `CloudNode.startNode()` | `HetznerBinaryDeploymentProvider.startProcess()` |
| `CloudNode.uploadJar()` | `HetznerBinaryDeploymentProvider.transferArtifact()` |
| `HetznerCloudCluster.CLOUD_INIT_SCRIPT` | `JdkBootstrap.Adoptium` default script |
| `HetznerCloudCluster.buildPeerList()` | `NodeDeploymentConfig.peerListString()` |

### Phase 2: Binary deployment provider

Implement `HetznerBinaryDeploymentProvider` with all `DeploymentProvider` methods. Wire into `HetznerEnvironmentIntegration` as a facet.

### Phase 3: Container deployment provider

Implement `HetznerContainerDeploymentProvider` using the same SSH layer but executing container runtime commands instead of direct JVM management.

### Phase 4: Migrate cloud-tests

Replace inline deployment code in `HetznerCloudCluster` with calls to `DeploymentProvider`:

```java
// Before (current)
public void provision() {
    createSshKey();
    createServers();
    waitForServersRunning();
    waitForSshConnectivity();
    verifyJavaInstalled();
    deployJars();
    startAllNodes();
}

// After (with DeploymentProvider SPI)
public void provision() {
    createSshKey();
    createServers();            // ComputeProvider
    waitForServersRunning();    // ComputeProvider
    deployAllNodes();           // DeploymentProvider
}

private void deployAllNodes() {
    var peerList = buildPeerAddresses();
    for (int i = 0; i < nodes.size(); i++) {
        var request = buildDeploymentRequest(nodes.get(i), peerList);
        var deployment = deploymentProvider.deploy(request).await();
        deployments.add(deployment);
    }
}
```

### Phase 5: CDM integration

Enable `ClusterDeploymentManager` to use `DeploymentProvider` when auto-healing:

```
CDM detects node failure
  → ComputeProvider.provision(ON_DEMAND)     // create replacement VM
  → DeploymentProvider.deploy(request)       // deploy Aether on it
  → Node auto-joins cluster (JOINING → ON_DUTY)
  → LoadBalancerProvider.reconcile(state)    // update LB targets
```

## Testing Strategy

### Unit Tests

| Component | Test Approach |
|-----------|---------------|
| `NodeDeploymentConfig` | Verify `peerListString()`, `toEnvVars()` formatting |
| `DeploymentStrategy` | Verify sealed type construction and factory methods |
| `DeploymentError` | Verify `message()` formatting for all variants |
| `HealthChecker` | Mock HTTP responses, verify polling and timeout behavior |
| `SshClient` | Mock interface, verify command construction |

### Integration Tests

| Scenario | Infrastructure |
|----------|---------------|
| Binary deploy to local VM | Testcontainers + SSH server |
| Container deploy to local VM | Testcontainers + Docker-in-Docker |
| Health check polling | Embedded HTTP server with configurable responses |

### Cloud Tests (existing)

The existing `aether/cloud-tests/` suite becomes the E2E validation:
- `HetznerCloudCluster` uses `HetznerBinaryDeploymentProvider`
- Tests verify full lifecycle: provision → deploy → health → operations → terminate
- Add new test: container strategy using Hetzner with Docker pre-installed

### Upgrade Test

New test in `aether/cloud-tests/` or `aether/e2e-tests/`:
1. Deploy 3-node cluster with version N
2. Upload version N+1 artifact
3. Rolling upgrade via `DeploymentProvider.upgrade()` one node at a time
4. Verify cluster health after each upgrade step
5. Verify all nodes on version N+1

## Alternatives Considered

### 1. Merge deployment into ComputeProvider (rejected)

**Approach:** Add `deploy()` / `startNode()` methods to `ComputeProvider`.

**Problems:**
- Violates single responsibility: `ComputeProvider` manages VMs, not software.
- Container deployment doesn't require VM provisioning at all (Kubernetes).
- Would bloat an already well-focused interface.

**Chosen:** Separate `DeploymentProvider` facet. Clean separation of concerns.

### 2. Generator-only approach (rejected)

**Approach:** Extend `DockerGenerator` / `KubernetesGenerator` to handle runtime deployment.

**Problems:**
- Generators produce static files. They have no concept of a running host.
- No feedback loop (health checks, cluster join verification).
- Cannot support auto-healing or rolling upgrades.

**Chosen:** `DeploymentProvider` is a runtime SPI, complementary to generators.

### 3. Ansible/Terraform for deployment (rejected)

**Approach:** Shell out to Ansible or Terraform for deployment orchestration.

**Problems:**
- External tool dependency.
- No integration with Promise-based async model.
- Slow execution (Terraform init/plan/apply cycle).
- Poor error reporting through process exit codes.

**Chosen:** Native Java SPI with SSH abstraction. Faster, better error modeling, Promise-native.

### 4. Agent-based deployment (deferred)

**Approach:** Install a deployment agent on each VM that receives instructions from the cluster.

**Advantages:**
- No SSH needed after initial agent install.
- Agent can report health and logs proactively.
- Better for large-scale deployments.

**Decision:** Deferred to a future RFC. The SSH-based approach works well for the current scale (3-10 nodes). Agent-based can be added as an alternative `DeploymentProvider` implementation later.

## Migration

### Step 1: Add new types (non-breaking)

Add all new interfaces and types to `environment-integration`. Extend `EnvironmentIntegration` with `deployment()` facet, defaulting to `empty()`.

### Step 2: Implement Hetzner deployment providers

Create `HetznerBinaryDeploymentProvider` and `HetznerContainerDeploymentProvider` in the `hetzner` module.

### Step 3: Migrate cloud-tests

Update `HetznerCloudCluster` to use the SPI instead of inline code. `RemoteCommandRunner` and `CloudNode` can be deprecated (their logic lives in the SPI implementations).

### Step 4: Wire CDM

Enable `ClusterDeploymentManager` to use `deployment()` facet when available.

### Backward Compatibility

- `EnvironmentIntegration.deployment()` returns `Option.empty()` by default.
- Existing 3-argument factory methods on `EnvironmentIntegration` are retained with deprecation.
- `ComputeProvider` interface is unchanged.
- Cloud-tests continue to work throughout migration (step 3 is a refactor, not a rewrite).

## References

- [RFC-0001: Core Slice Contract](RFC-0001-core-slice-contract.md) -- Factory naming conventions
- [RFC-0012: Resource Provisioning](RFC-0012-resource-provisioning.md) -- SPI discovery pattern
- `aether/environment-integration/src/main/java/org/pragmatica/aether/environment/ComputeProvider.java` -- Existing compute SPI
- `aether/environment-integration/src/main/java/org/pragmatica/aether/environment/EnvironmentIntegration.java` -- Faceted SPI pattern
- `aether/environment-integration/src/main/java/org/pragmatica/aether/environment/EnvironmentError.java` -- Sealed error type pattern
- `aether/environment/hetzner/src/main/java/org/pragmatica/aether/environment/hetzner/HetznerComputeProvider.java` -- Reference implementation
- `aether/cloud-tests/src/test/java/org/pragmatica/aether/cloud/HetznerCloudCluster.java` -- Code to extract
- `aether/cloud-tests/src/test/java/org/pragmatica/aether/cloud/CloudNode.java` -- Code to extract
- `aether/cloud-tests/src/test/java/org/pragmatica/aether/cloud/RemoteCommandRunner.java` -- Code to extract
- `aether/docker/aether-node/Dockerfile` -- Container image definition (env vars, ports, entrypoint)
- `aether/docs/operators/runbooks/deployment.md` -- Manual deployment runbook (to be automated)
- `aether/docs/operators/infrastructure-design.md` -- Infrastructure design context
- `aether/slice/src/main/java/org/pragmatica/aether/slice/kvstore/AetherValue.java` -- `NodeLifecycleState` enum
- `aether/node/src/main/java/org/pragmatica/aether/api/routes/NodeLifecycleRoutes.java` -- Drain/activate API

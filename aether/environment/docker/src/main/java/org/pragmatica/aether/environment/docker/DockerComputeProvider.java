// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment.docker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.pragmatica.aether.environment.ClusterIdentityEnv;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProviderDefaults;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.aether.environment.ReadinessPolicy;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Contract;
import org.pragmatica.utility.IdGenerator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Result.success;


@Contract
public record DockerComputeProvider(DockerCommandRunner runner, DockerConfig config) implements ComputeProvider {
    private static final Logger log = LoggerFactory.getLogger(DockerComputeProvider.class);

    public static Result<DockerComputeProvider> dockerComputeProvider(DockerCommandRunner runner, DockerConfig config) {
        return success(new DockerComputeProvider(runner, config));
    }

    @Override
    public Promise<InstanceInfo> provision(InstanceType instanceType) {
        var ctx = ProvisionContext.provisionContext("default",
                                                    "core",
                                                    SourceName.DEFAULT,
                                                    ProvisionContext.PROVISIONED_BY_BOOTSTRAP);

        return provision(ProvisionSpec.provisionSpec(instanceType, "docker", "default", ctx).unwrap());
    }

    @Override
    public ProviderDefaults providerDefaults() {
        return ProviderDefaults.providerDefaults("docker", "", "", "", Option.empty(), false);
    }

    /// Docker is a single-image, unsized provider: it ignores the resolved instanceSize/image/zone
    /// (there is no sizing or image-selection knob on `docker run` beyond the configured image) and
    /// builds the container from the request context alone. A SPOT request is rejected loud —
    /// Docker has no spot/preemptible concept and must never silently downgrade.
    @Override
    public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
        if (request.market() instanceof InstanceType.Spot) {
            return SPOT_UNSUPPORTED.promise();
        }

        var preflight = preflightCheck(request);

        if (preflight.isPresent()) {
            return preflight.unwrap();
        }

        var identity = resolveIdentity(request);

        return provisionWithIdentity(request, identity).mapError(DockerComputeProvider::toProvisionError);
    }

    private static final Cause SPOT_UNSUPPORTED = EnvironmentError.provisionFailed(new RuntimeException("Docker has no spot/preemptible product; a SPOT request must not silently downgrade to on-demand"));

    /// Resolve the node identity used as both container name and NodeId. Honors a
    /// caller-supplied `ctx.nodeId()` when present (bootstrap supplies it), otherwise
    /// mints `aether-<cluster>-node-<ulid>` via [IdGenerator]. The cluster segment is
    /// sourced from [#clusterOrDefault] (ProvisionContext.clusterName with an
    /// AETHER_CLUSTER_NAME env fallback) so CTM replacements carry the same
    /// `aether-<cluster>-` prefix as their compose-fixed siblings — the orphan sweeper
    /// and `docker kill` prefix-matching keep working.
    private String resolveIdentity(ProvisionRequest request) {
        var cluster = clusterOrDefault(request.context());

        return request.context()
                      .nodeId()
                      .or(() -> IdGenerator.generate(ProvisionContext.coreNodeNamePrefix(cluster)));
    }

    private Promise<InstanceInfo> provisionWithIdentity(ProvisionRequest request, String containerName) {
        var command = buildRunCommand(request, containerName);

        return runner.execute(command)
                     .map(containerId -> toProvisionedInfo(containerId, containerName, request))
                     .flatMap(info -> confirmRunning(info,
                                                     ReadinessPolicy.dockerDefault()))
                     .onFailure(cause -> rollbackOnProvisionFailure(containerName, cause));
    }

    /// bootstrap list (3-part `nodeId:host:port` entries) so the new container can join
    /// the existing consensus group. Without peers the container starts with `PEERS=`
    /// (empty), the JVM cold-boots in isolation (`quorate=false, leaderId=none`), and
    /// nginx upstream resolvers in front of the cluster may route real management
    /// traffic to it — returning empty `/api/nodes/lifecycle` snapshots and corrupting
    /// the test view of cluster state.
    ///
    /// Bootstrap-time provisioning (`provisionedBy=bootstrap`) is intentionally exempt
    /// — the first node legitimately has no peers and is responsible for forming the
    /// cluster. Future enhancement: also fail when `bootstrap` is observed after
    /// cluster formation (would require a "formed" signal threaded through here).
    private Option<Promise<InstanceInfo>> preflightCheck(ProvisionRequest request) {
        var ctx = request.context();

        if (!ProvisionContext.PROVISIONED_BY_CTM.equals(ctx.provisionedBy())) {
            return Option.empty();
        }

        if (!ctx.peers().or("").isEmpty()) {
            return Option.empty();
        }

        var message = "DockerComputeProvider.provision rejected: CTM auto-heal requires a non-empty PEERS bootstrap list, "
                    + "but the provided ProvisionContext.peers is absent or empty. Refusing to spawn an orphan container "
                    + "that would cold-boot in isolation and corrupt cluster management views. "
                    + "Caller (ClusterTopologyManager) should defer provisioning until at least one healthy peer is "
                    + "visible in the observed topology.";

        log.warn(message);

        return Option.some(EnvironmentError.provisionFailed(new IllegalStateException(message)).promise());
    }

    /// Rollback hook for partial provisions. When `docker run -d` fails after the container
    /// shell was created (typical for port-bind collisions: the container is in `Created` state
    /// but `docker run` exits non-zero), the orphaned shell stays in Docker's container table
    /// and leaks SWIM gossip into the cluster. Issue `docker rm -f <containerName>` against the
    /// pre-generated name so any partial leftover is evicted before we surface the original
    /// failure to the caller. If the container never reached Created (run aborted earlier),
    /// the rm fails harmlessly and we log a WARN so the gap is explicit, not silent.
    private void rollbackOnProvisionFailure(String containerName, Cause cause) {
        log.warn("Provision failed for container {} ({}); attempting rollback via docker rm -f",
                 containerName,
                 cause.message());
        runner.execute(buildForceRemoveCommand(containerName))
              .onFailure(rollbackCause -> log.warn("Rollback rm -f {} returned non-zero (likely no partial container existed): {}",
                                                   containerName,
                                                   rollbackCause.message()))
              .onSuccess(ignored -> log.info("Rollback removed partial container {}", containerName));
    }

    @Override
    public void resetProvisionerState(String clusterName) {
        if (!clusterName.isEmpty()) {
            runner.execute(buildCtmPruneCommand(clusterName))
                  .onFailure(cause -> log.warn("CTM sweep failed for cluster {}: {}",
                                               clusterName,
                                               cause.message()))
                  .onSuccess(out -> {
                                 if (!out.isBlank()) {
                                 log.info("CTM sweep for cluster {}: {}",
                                          clusterName,
                                          out.strip());
                             }
                             });
        }
    }

    private static List<String> buildCtmPruneCommand(String clusterName) {
        return List.of("docker",
                       "container",
                       "prune",
                       "--force",
                       "--filter",
                       "label=aether.cluster=" + clusterName,
                       "--filter",
                       "label=aether.provisioned-by=ctm");
    }

    private static List<String> buildForceRemoveCommand(String containerName) {
        return List.of("docker", "rm", "-f", containerName);
    }

    @Override
    public Promise<Unit> terminate(InstanceId instanceId) {
        var stopCommand = buildStopCommand(instanceId);
        var removeCommand = buildRemoveCommand(instanceId);

        return runner.execute(stopCommand)
                     .flatMap(ignored -> runner.execute(removeCommand))
                     .mapToUnit()
                     .mapError(cause -> toTerminateError(instanceId, cause));
    }

    @Override
    public Promise<List<InstanceInfo>> listInstances() {
        var command = buildListCommand();

        return runner.execute(command)
                     .map(DockerComputeProvider::parseContainerList)
                     .mapError(DockerComputeProvider::toListInstancesError);
    }

    @Override
    public Promise<List<InstanceInfo>> listInstances(Map<String, String> tagFilter) {
        var command = buildFilteredListCommand(tagFilter);

        return runner.execute(command)
                     .map(DockerComputeProvider::parseContainerList)
                     .mapError(DockerComputeProvider::toListInstancesError);
    }

    @Override
    public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
        var command = buildInspectCommand(instanceId);

        return runner.execute(command)
                     .map(output -> parseInspectOutput(output, instanceId))
                     .mapError(DockerComputeProvider::toProvisionError);
    }

    @Override
    public Promise<Unit> restart(InstanceId id) {
        var command = buildRestartCommand(id);

        return runner.execute(command)
                     .mapToUnit()
                     .mapError(DockerComputeProvider::toProvisionError);
    }

    @Override
    public Promise<Unit> applyTags(InstanceId id, Map<String, String> tags) {
        return EnvironmentError.operationNotSupported("applyTags (Docker labels are immutable after creation)").promise();
    }

    private List<String> buildRunCommand(ProvisionRequest request, String containerName) {
        var ctx = request.context();
        var role = roleOrDefault(ctx);
        var cluster = clusterOrDefault(ctx);
        // NodeId == container name (the resolved identity: caller-supplied ctx.nodeId()
        // or a freshly-minted ULID id). Keeping them equal means `docker kill <nodeId>`
        // resolves to this exact container — no nodeId→container map needed.
        var nodeId = containerName;
        var peers = ctx.peers().or("");
        var coreMax = String.valueOf(ctx.coreMax());
        var provisionedBy = ctx.provisionedBy();
        var apiKey = config.apiKey();
        var command = new ArrayList<>(List.of("docker",
                                              "run",
                                              "-d",

        // Explicit `--restart no` so a host running with a daemon-level
        // `--default-restart-policy` (Docker 28+) cannot silently auto-restart a
        // CTM-launched replacement — that would resurrect a terminally-removed
        // NodeId and violate the terminal-removal invariant.
        "--restart",
                                              "no",
                                              "--name",
                                              containerName,
                                              "--hostname",
                                              containerName,
                                              "--network",
                                              config.networkName(),
                                              "-v",
                                              config.socketPath() + ":" + config.socketPath(),
                                              "--label",
                                              "aether.cluster=" + cluster,
                                              "--label",
                                              "aether.role=" + role,
                                              "--label",
                                              "aether.node-id=" + nodeId,
                                              "--label",
                                              "aether.provisioned-by=ctm",
                                              "-e",
                                              "NODE_ID=" + nodeId,
                                              "-e",
                                              "AETHER_NODE_ID=" + nodeId,
                                              "-e",

        // Authoritative cluster name: emitted from the SAME source as the
        // `aether.cluster` label above (clusterOrDefault(ctx) →
        // ProvisionContext.clusterName, the KV-bootstrapped name) — NOT
        // forwarded verbatim from the leader's process env. The leader's
        // AETHER_CLUSTER_NAME can legitimately differ from the bootstrapped
        // cluster.name (e.g. compose label `b` vs bootstrap `integration-test`);
        // forwarding it verbatim made the replacement's env disagree with its
        // own label, and the boot guard (Main.verifyClusterLabelConsistency)
        // exited(1) → CTM retried forever → auto-heal storm. Mirrors
        // AETHER_NODE_ID above; the IDENTITY_VARS loop below dedupes via
        // alreadyEmitted().
        "AETHER_CLUSTER_NAME=" + cluster,
                                              "-e",
                                              "CLUSTER_PORT=" + config.clusterPort(),
                                              "-e",
                                              "MANAGEMENT_PORT=8080",
                                              "-e",
                                              "PEERS=" + peers,
                                              "-e",
                                              "CORE_MAX=" + coreMax,
                                              "-e",
                                              "AETHER_API_KEY=" + apiKey));

        if (!provisionedBy.isEmpty()) {
            command.add("-e");
            command.add("AETHER_PROVISIONED_BY=" + provisionedBy);
        }
        // Wave 2 / W4 (cluster-topology-overhaul spec): the provisioned node's AETHER_ROLE is
        // AUTHORITATIVE from the caller's intent (roleOrDefault(ctx) — the SAME source as the
        // `aether.role` label above), NOT inherited from the provisioning host's process env.
        // Emitted BEFORE the IDENTITY_VARS loop so alreadyEmitted() dedupes the host-env
        // AETHER_ROLE out — a leader's own role can never leak onto a node it mints.
        command.add("-e");
        command.add("AETHER_ROLE=" + role);
        // Single source of truth: propagate the full cluster-identity allow-list
        // (AETHER_CLUSTER_NAME/SECRET/PROVISIONED_BY/API_KEY) then the Docker-infra
        // allow-list (AETHER_DOCKER_NETWORK/DOCKER_GID). A provider-minted replacement
        // has no compose env, so without this its identity goes dark one generation deep
        // (--group-add sees an unresolved ${env:DOCKER_GID}; the next replacement it mints
        // loses identity vars). Dedupe (alreadyEmitted) against vars already emitted above:
        // AETHER_CLUSTER_NAME (from clusterOrDefault(ctx) — authoritative, equals the label),
        // AETHER_PROVISIONED_BY (from ctx.provisionedBy()), AETHER_API_KEY (from config.apiKey()).
        // AETHER_CLUSTER_SECRET still rides the loop verbatim (a cluster-wide constant with no
        // per-provision authoritative source, unlike the name).
        ClusterIdentityEnv.IDENTITY_VARS.forEach(name -> propagateEnvVar(command, name));
        ClusterIdentityEnv.DOCKER_INFRA_VARS.forEach(name -> propagateEnvVar(command, name));
        // --- Dev-mode (ISOLATED — never part of IDENTITY_VARS) ---
        // Propagate AETHER_INSECURE_DEV_MODE only when present in env so an auto-healed
        // replacement inherits the dev-mode posture of its siblings (dev-gated routes
        // otherwise 503 on healed nodes). Kept standalone so dev-mode can never silently
        // ride the identity allow-list into a production deploy.
        propagateEnvVar(command, ClusterIdentityEnv.INSECURE_DEV_MODE);
        // Defense-in-depth: never pass an unresolved `${env:...}` literal to `--group-add`
        // (docker rejects it pre-start with exit 125). Treat it as absent.
        if (!config.dockerGid().isEmpty() && !config.dockerGid().startsWith("${env:")) {
            command.add("--group-add");
            command.add(config.dockerGid());
        }

        if (config.exposeHostPorts()) {
            // ULID-minted replacements have no numeric slot, so the old `base + slot`
            // deterministic host-port scheme no longer applies. Publish BOTH in-container
            // ports — management (8080) and app plane (8070) — to ephemeral host ports
            // (`-p 8080`, `-p 8070`); Docker picks free ones. Consumers (the integration
            // harness) discover them via `docker port <name> 8080/tcp` and
            // `docker port <name> 8070/tcp`. The app port MUST be published: once every
            // compose seed has been replaced by a provider-minted node, the app plane is
            // unreachable from the host unless these replacements map their app port.
            command.add("-p");
            command.add("8080");
            command.add("-p");
            command.add("8070");
        }

        addSpecLabels(command, ctx.extraTags());
        addPlacementLabels(command, request.zone());
        command.add(config.imageName());

        return List.copyOf(command);
    }

    private static void propagateEnvVar(ArrayList<String> command, String name) {
        if (alreadyEmitted(command, name)) {
            return;
        }

        var value = System.getenv(name);

        if (value != null && !value.isEmpty()) {
            command.add("-e");
            command.add(name + "=" + value);
        }
    }

    /// True when `-e <name>=...` was already appended (e.g. AETHER_API_KEY from
    /// `config.apiKey()` or AETHER_PROVISIONED_BY from `ctx.provisionedBy()`), so the
    /// allow-list loop never double-emits a var that was set explicitly upstream.
    private static boolean alreadyEmitted(ArrayList<String> command, String name) {
        var prefix = name + "=";

        return command.stream()
                      .anyMatch(arg -> arg.startsWith(prefix));
    }

    private static void addSpecLabels(ArrayList<String> command, Map<String, String> tags) {
        tags.entrySet()
            .stream()
            .filter(DockerComputeProvider::isCustomLabel)
            .forEach(entry -> addLabelArgs(command, entry));
    }

    private static boolean isCustomLabel(Map.Entry<String, String> entry) {
        return ! entry.getKey()
                      .startsWith("aether.");
    }

    private static void addLabelArgs(ArrayList<String> command, Map.Entry<String, String> entry) {
        command.add("--label");
        command.add(entry.getKey() + "=" + entry.getValue());
    }

    private static void addPlacementLabels(ArrayList<String> command, String zone) {
        if (!zone.isBlank()) {
            addPlacementLabel(command, "zone", zone);
        }
    }

    private static void addPlacementLabel(ArrayList<String> command, String key, String value) {
        command.add("--label");
        command.add("aether.placement." + key + "=" + value);
    }

    private static List<String> buildStopCommand(InstanceId instanceId) {
        return List.of("docker", "stop", instanceId.value());
    }

    private static List<String> buildRemoveCommand(InstanceId instanceId) {
        return List.of("docker", "rm", instanceId.value());
    }

    private static List<String> buildListCommand() {
        return List.of("docker",
                       "ps",
                       "-a",
                       "--filter",
                       "label=aether.cluster",
                       "--format",
                       "{{.ID}}\t{{.Names}}\t{{.State}}\t{{.Label \"aether.cluster\"}}\t{{.Label \"aether.role\"}}\t{{.Label \"aether.node-id\"}}");
    }

    private static List<String> buildFilteredListCommand(Map<String, String> tagFilter) {
        var command = new ArrayList<>(List.of("docker", "ps", "-a"));

        tagFilter.forEach((key, value) -> addFilterArgs(command, key, value));
        command.addAll(List.of("--format",
                               "{{.ID}}\t{{.Names}}\t{{.State}}\t{{.Label \"aether.cluster\"}}\t{{.Label \"aether.role\"}}\t{{.Label \"aether.node-id\"}}"));

        return List.copyOf(command);
    }

    private static void addFilterArgs(ArrayList<String> command, String key, String value) {
        command.add("--filter");
        command.add("label=" + key + "=" + value);
    }

    private static List<String> buildInspectCommand(InstanceId instanceId) {
        return List.of("docker",
                       "inspect",
                       "--format",
                       "{{.State.Status}}\t{{.Name}}\t{{.Config.Hostname}}\t{{.Id}}",
                       instanceId.value());
    }

    private static List<String> buildRestartCommand(InstanceId id) {
        return List.of("docker", "restart", id.value());
    }

    private InstanceInfo toProvisionedInfo(String containerId, String containerName, ProvisionRequest request) {
        // Provider-minted replacements are reached on the Docker overlay network at
        // the container's own ports (mgmt 8080, app 8070), addressed by container name
        // == NodeId. Host-mapped per-slot ports were a seed-only convenience; ULID
        // replacements carry no slot, so report the overlay-reachable form.
        var addresses = List.of(containerName + ":8080", containerName + ":8070");
        var tags = buildInstanceTags(request, containerName);
        // Created, not yet confirmed live: report PROVISIONING. confirmRunning() polls
        // `docker inspect` and re-stamps this to RUNNING only after the container actually
        // reaches `running`, or FAILS the provision if it never does (no phantom success).
        return new InstanceInfo(new InstanceId(containerId),
                                InstanceStatus.PROVISIONING,
                                addresses,
                                request.market(),
                                tags,
                                Option.some(containerName));
    }

    private static Map<String, String> buildInstanceTags(ProvisionRequest request, String containerName) {
        var ctx = request.context();
        var role = roleOrDefault(ctx);
        var cluster = clusterOrDefault(ctx);
        // NodeId == container name (see buildRunCommand). Tags expose this to the
        // CTM bookkeeping layer so observed/desired reconciliation aligns with the
        // identity the container actually boots with.
        return Map.of("aether.cluster", cluster, "aether.role", role, "aether.node-id", containerName);
    }

    private static String roleOrDefault(ProvisionContext ctx) {
        return ctx.role()
                  .isEmpty()
               ? "core"
               : ctx.role();
    }

    private static String clusterOrDefault(ProvisionContext ctx) {
        if (!ctx.clusterName().isEmpty()) {
            return ctx.clusterName();
        }
        // Fallback: when ClusterConfigValue isn't yet seeded in KV-Store (e.g., compose-only
        // deployments that never ran `aether cluster bootstrap`), source the cluster name
        // from the AETHER_CLUSTER_NAME env var so CTM-provisioned replacements still get a
        // matching `aether.cluster=<name>` label. The integration test compose YAMLs set
        // this env to `a` / `b` so cluster A/B's CTM replacements carry the same label as
        // their compose-fixed siblings — closes the spec's caveat-c gap.
        var fromEnv = System.getenv("AETHER_CLUSTER_NAME");

        if (fromEnv != null && !fromEnv.isEmpty()) {
            return fromEnv;
        }
        // No cluster name anywhere: mirror the cloud providers' empty fall-through rather
        // than silently mislabeling the node "default". An empty name now reaches the
        // node-side boot gate (Main.verifyClusterNamePresent), which fails loud.
        return "";
    }

    static List<InstanceInfo> parseContainerList(String output) {
        if (output.isEmpty()) {
            return List.of();
        }

        return Arrays.stream(output.split("\n"))
                     .filter(line -> !line.isBlank())
                     .map(DockerComputeProvider::parseContainerLine)
                     .toList();
    }

    static InstanceInfo parseContainerLine(String line) {
        var parts = line.split("\t", -1);
        var id = safePart(parts, 0);
        var name = safePart(parts, 1);
        var state = safePart(parts, 2);
        var cluster = safePart(parts, 3);
        var role = safePart(parts, 4);
        var nodeId = safePart(parts, 5);
        var tags = Map.of("aether.cluster", cluster, "aether.role", role, "aether.node-id", nodeId);

        return new InstanceInfo(new InstanceId(id),
                                mapDockerState(state),
                                List.of(),
                                InstanceType.ON_DEMAND,
                                tags,
                                nodeId.isEmpty()
                                ? Option.none()
                                : Option.some(nodeId));
    }

    private static String safePart(String[] parts, int index) {
        return index < parts.length
               ? parts[index]
               : "";
    }

    static InstanceInfo parseInspectOutput(String output, InstanceId instanceId) {
        var parts = output.split("\t", -1);
        var state = safePart(parts, 0);
        var name = safePart(parts, 1).replaceFirst("^/", "");

        return new InstanceInfo(instanceId, mapDockerState(state), List.of(name), InstanceType.ON_DEMAND, Map.of());
    }

    static InstanceStatus mapDockerState(String dockerState) {
        return switch (dockerState) {
            case "created", "restarting" -> InstanceStatus.PROVISIONING;
            case "running" -> InstanceStatus.RUNNING;
            case "paused", "removing", "exited" -> InstanceStatus.STOPPING;
            case "dead" -> InstanceStatus.TERMINATED;
            default -> InstanceStatus.TERMINATED;
        };
    }

    private static EnvironmentError toProvisionError(Cause cause) {
        return EnvironmentError.provisionFailed(new RuntimeException(cause.message()));
    }

    private static EnvironmentError toTerminateError(InstanceId instanceId, Cause cause) {
        return EnvironmentError.terminateFailed(instanceId, new RuntimeException(cause.message()));
    }

    private static EnvironmentError toListInstancesError(Cause cause) {
        return EnvironmentError.listInstancesFailed(new RuntimeException(cause.message()));
    }
}

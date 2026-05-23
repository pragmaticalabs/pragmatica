// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment.docker;

import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.PlacementHint;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Contract;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Result.success;


@Contract public record DockerComputeProvider(DockerCommandRunner runner,
                                              DockerConfig config,
                                              AtomicInteger nodeCounter) implements ComputeProvider {
    private static final Logger log = LoggerFactory.getLogger(DockerComputeProvider.class);

    public static Result<DockerComputeProvider> dockerComputeProvider(DockerCommandRunner runner, DockerConfig config) {
        return success(new DockerComputeProvider(runner, config, new AtomicInteger(0)));
    }

    @Override public Promise<InstanceInfo> provision(InstanceType instanceType) {
        var ctx = ProvisionContext.provisionContext("default",
                                                    "core",
                                                    "default",
                                                    ProvisionContext.PROVISIONED_BY_BOOTSTRAP);
        return provision(ProvisionSpec.provisionSpec(instanceType, "docker", "default", ctx).unwrap());
    }

    @Override public Promise<InstanceInfo> provision(ProvisionSpec spec) {
        var preflight = preflightCheck(spec);
        if (preflight.isPresent()) {
            return preflight.unwrap();
        }
        // Slot allocation: query existing aether-<cluster>-node-N containers on the host,
        // compute the next free numeric slot N, and use it for BOTH container name and
        // NodeId. Compose-fixed nodes occupy slots 1..5; CTM auto-heal replacements
        // continue the sequence (6, 7, ...) so `NodeId == container_name` everywhere
        // — `docker kill <nodeId>` Just Works in the test harness.
        return allocateSlot(spec).flatMap(slot -> provisionAtSlot(spec, slot))
                                  .mapError(DockerComputeProvider::toProvisionError);
    }

    private Promise<InstanceInfo> provisionAtSlot(ProvisionSpec spec, int slot) {
        var containerName = buildContainerName(spec, slot);
        var command = buildRunCommand(spec, containerName, slot);
        return runner.execute(command).map(containerId -> toProvisionedInfo(containerId, containerName, spec, slot))
                             .onFailure(cause -> rollbackOnProvisionFailure(containerName, cause));
    }

    /// Query the Docker daemon for the next free `aether-<cluster>-node-N` slot.
    /// We list every container (running OR exited) carrying `aether.cluster=<cluster>`,
    /// parse the trailing `-node-N` from each container name, take `max(N) + 1`. When
    /// the host has none yet, slot starts at the post-compose offset (compose uses
    /// 1..5; auto-heal starts at 6 minimum). The `nodeCounter` provides the lower
    /// bound so successive CTM provisions in the same JVM keep monotonic ordering
    /// even if the previous container hasn't been listed yet (e.g. still in `Created`).
    private Promise<Integer> allocateSlot(ProvisionSpec spec) {
        var cluster = clusterOrDefault(spec.context());
        var listCmd = buildSlotProbeCommand(cluster);
        return runner.execute(listCmd)
                     .map(output -> nextSlot(output, cluster))
                     .recover(cause -> recoverSlotFromCounter(cause, cluster));
    }

    private static List<String> buildSlotProbeCommand(String cluster) {
        return List.of("docker",
                       "ps",
                       "-a",
                       "--filter",
                       "label=aether.cluster=" + cluster,
                       "--format",
                       "{{.Names}}");
    }

    private int nextSlot(String output, String cluster) {
        var prefix = "aether-" + cluster + "-node-";
        var observedMax = Arrays.stream(output.split("\n"))
                                .map(String::trim)
                                .filter(name -> name.startsWith(prefix))
                                .map(name -> name.substring(prefix.length()))
                                .mapToInt(DockerComputeProvider::parseLeadingInt)
                                .filter(n -> n > 0)
                                .max()
                                .orElse(0);
        // nodeCounter floor: monotonic per-JVM lower bound so back-to-back provisions
        // before the first container is observable in `docker ps -a` still pick distinct
        // slots. Each provision bumps the counter; we take max(observedMax+1, counter+1).
        var counterFloor = nodeCounter.getAndIncrement() + 1;
        var nextFromObserved = observedMax + 1;
        return Math.max(nextFromObserved, counterFloor);
    }

    private static int parseLeadingInt(String suffix) {
        var i = 0;
        while (i < suffix.length() && Character.isDigit(suffix.charAt(i))) {
            i++;
        }
        if (i == 0) {
            return -1;
        }
        try {
            return Integer.parseInt(suffix.substring(0, i));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private Integer recoverSlotFromCounter(Cause cause, String cluster) {
        // Slot-probe failure must NOT abort provisioning — fall back to the in-memory
        // counter so CTM auto-heal still proceeds (e.g. docker daemon flake). Log the
        // cause at WARN so the gap is explicit; subsequent provisions will retry the
        // probe and reconcile naturally. Floor at 1 so we never hand out slot 0.
        log.warn("Slot probe failed for cluster '{}': {} — falling back to in-memory counter (may collide with stale slot)",
                 cluster,
                 cause.message());
        return Math.max(1, nodeCounter.getAndIncrement() + 1);
    }

    /// Pre-flight validation: CTM auto-heal provisioning REQUIRES a non-empty `peers`
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
    private Option<Promise<InstanceInfo>> preflightCheck(ProvisionSpec spec) {
        var ctx = spec.context();
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

    @Override public void resetProvisionerState(String clusterName) {
        nodeCounter.set(0);
        if (!clusterName.isEmpty()) {
            runner.execute(buildCtmPruneCommand(clusterName))
                  .onFailure(cause -> log.warn("CTM sweep failed for cluster {}: {}", clusterName, cause.message()))
                  .onSuccess(out -> { if (!out.isBlank()) {log.info("CTM sweep for cluster {}: {}", clusterName, out.strip());}});
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

    @Override public Promise<Unit> terminate(InstanceId instanceId) {
        var stopCommand = buildStopCommand(instanceId);
        var removeCommand = buildRemoveCommand(instanceId);
        return runner.execute(stopCommand).flatMap(ignored -> runner.execute(removeCommand))
                             .mapToUnit()
                             .mapError(cause -> toTerminateError(instanceId, cause));
    }

    @Override public Promise<List<InstanceInfo>> listInstances() {
        var command = buildListCommand();
        return runner.execute(command).map(DockerComputeProvider::parseContainerList)
                             .mapError(DockerComputeProvider::toListInstancesError);
    }

    @Override public Promise<List<InstanceInfo>> listInstances(Map<String, String> tagFilter) {
        var command = buildFilteredListCommand(tagFilter);
        return runner.execute(command).map(DockerComputeProvider::parseContainerList)
                             .mapError(DockerComputeProvider::toListInstancesError);
    }

    @Override public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
        var command = buildInspectCommand(instanceId);
        return runner.execute(command).map(output -> parseInspectOutput(output, instanceId))
                             .mapError(DockerComputeProvider::toProvisionError);
    }

    @Override public Promise<Unit> restart(InstanceId id) {
        var command = buildRestartCommand(id);
        return runner.execute(command).mapToUnit()
                             .mapError(DockerComputeProvider::toProvisionError);
    }

    @Override public Promise<Unit> applyTags(InstanceId id, Map<String, String> tags) {
        return EnvironmentError.operationNotSupported("applyTags (Docker labels are immutable after creation)")
                                                     .promise();
    }

    /// Build a cluster-scoped, slot-indexed container name where `NodeId == container name`.
    ///
    /// Format: `aether-<clusterName>-node-<slot>`
    ///
    /// Slot is computed by `allocateSlot` from the host's existing
    /// `aether-<cluster>-node-N` containers (compose-fixed 1..5, CTM extensions 6+).
    /// The cluster segment disambiguates concurrent clusters (e.g., integration-test
    /// clusters A and B sharing a Docker host) so orphan sweepers that filter by
    /// `aether-<clusterName>-` cannot accidentally cross-evict containers from a
    /// sibling cluster. `clusterName` is sourced from [ProvisionContext#clusterName]
    /// (set by CTM from `[cluster].name`), defaulting to `"default"` for callers
    /// that haven't set it explicitly.
    ///
    /// Pool is intentionally dropped from the name: the previous shape
    /// `aether-<cluster>-<pool>-node-<idx>-<hex>` separated the pool to disambiguate
    /// non-core pools (worker, etc.), but pool affinity now flows through labels
    /// (`aether.role`) and the test harness needs `NodeId == container_name` to be
    /// stable enough that `docker kill <nodeid>` Just Works.
    String buildContainerName(ProvisionSpec spec, int slot) {
        var cluster = clusterOrDefault(spec.context());
        return "aether-" + cluster + "-node-" + slot;
    }

    private List<String> buildRunCommand(ProvisionSpec spec, String containerName, int slot) {
        var ctx = spec.context();
        var role = roleOrDefault(ctx);
        var cluster = clusterOrDefault(ctx);
        // NodeId == container name. Any caller-supplied ctx.nodeId() is intentionally
        // ignored — the slot-allocator owns the identity so `docker kill <nodeId>`
        // resolves to the same container the test harness sees in compose YAML.
        var nodeId = containerName;
        var peers = ctx.peers().or("");
        var coreMax = String.valueOf(ctx.coreMax());
        var provisionedBy = ctx.provisionedBy();
        var apiKey = config.apiKey();
        var command = new ArrayList<>(List.of("docker",
                                              "run",
                                              "-d",
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
        propagateEnvVar(command, "AETHER_CLUSTER_SECRET");
        propagateEnvVar(command, "AETHER_DOCKER_NETWORK");
        if (!config.dockerGid().isEmpty()) {
            command.add("--group-add");
            command.add(config.dockerGid());
        }
        if (config.exposeHostPorts()) {
            // Port = base + slot. Compose YAMLs configure
            // AETHER_MGMT_PORT_BASE/AETHER_APP_PORT_BASE so that base+1..base+5 map
            // to compose-fixed nodes, and CTM extensions (slot 6+) extend the
            // sequence without overlapping into the sibling cluster's port range.
            command.add("-p");
            command.add((config.managementPortBase() + slot) + ":8080");
        }
        addSpecLabels(command, ctx.extraTags());
        addPlacementLabels(command, spec.placement());
        command.add(config.imageName());
        return List.copyOf(command);
    }

    private static void propagateEnvVar(ArrayList<String> command, String name) {
        var value = System.getenv(name);
        if (value != null && !value.isEmpty()) {
            command.add("-e");
            command.add(name + "=" + value);
        }
    }

    private static void addSpecLabels(ArrayList<String> command, Map<String, String> tags) {
        tags.entrySet().stream()
                     .filter(DockerComputeProvider::isCustomLabel)
                     .forEach(entry -> addLabelArgs(command, entry));
    }

    private static boolean isCustomLabel(Map.Entry<String, String> entry) {
        return ! entry.getKey().startsWith("aether.");
    }

    private static void addLabelArgs(ArrayList<String> command, Map.Entry<String, String> entry) {
        command.add("--label");
        command.add(entry.getKey() + "=" + entry.getValue());
    }

    private static void addPlacementLabels(ArrayList<String> command, Option<PlacementHint> placement) {
        placement.onPresent(hint -> applyPlacementHint(command, hint));
    }

    private static void applyPlacementHint(ArrayList<String> command, PlacementHint hint) {
        switch (hint){
            case PlacementHint.ZoneHint zone -> addPlacementLabel(command, "zone", zone.zoneName());
            case PlacementHint.HostGroupHint group -> addPlacementLabel(command, "host-group", group.groupId());
            case PlacementHint.AffinityHint ignored -> log.debug("Docker provider ignoring AffinityHint — not supported");
            case PlacementHint.AntiAffinityHint ignored -> log.debug("Docker provider ignoring AntiAffinityHint — not supported");
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

    private InstanceInfo toProvisionedInfo(String containerId,
                                           String containerName,
                                           ProvisionSpec spec,
                                           int slot) {
        var mgmtPort = config.managementPortBase() + slot;
        var appPort = config.appPortBase() + slot;
        var addresses = List.of("localhost:" + mgmtPort, "localhost:" + appPort);
        var tags = buildInstanceTags(spec, containerName);
        return new InstanceInfo(new InstanceId(containerId),
                                InstanceStatus.RUNNING,
                                addresses,
                                spec.instanceType(),
                                tags);
    }

    private static Map<String, String> buildInstanceTags(ProvisionSpec spec, String containerName) {
        var ctx = spec.context();
        var role = roleOrDefault(ctx);
        var cluster = clusterOrDefault(ctx);
        // NodeId == container name (see buildRunCommand). Tags expose this to the
        // CTM bookkeeping layer so observed/desired reconciliation aligns with the
        // identity the container actually boots with.
        return Map.of("aether.cluster", cluster, "aether.role", role, "aether.node-id", containerName);
    }

    private static String roleOrDefault(ProvisionContext ctx) {
        return ctx.role().isEmpty()
              ? "core"
              : ctx.role();
    }

    private static String clusterOrDefault(ProvisionContext ctx) {
        if (!ctx.clusterName().isEmpty()) {return ctx.clusterName();}
        // Fallback: when ClusterConfigValue isn't yet seeded in KV-Store (e.g., compose-only
        // deployments that never ran `aether cluster bootstrap`), source the cluster name
        // from the AETHER_CLUSTER_NAME env var so CTM-provisioned replacements still get a
        // matching `aether.cluster=<name>` label. The integration test compose YAMLs set
        // this env to `a` / `b` so cluster A/B's CTM replacements carry the same label as
        // their compose-fixed siblings — closes the spec's caveat-c gap.
        var fromEnv = System.getenv("AETHER_CLUSTER_NAME");
        if (fromEnv != null && !fromEnv.isEmpty()) {return fromEnv;}
        return "default";
    }

    static List<InstanceInfo> parseContainerList(String output) {
        if (output.isEmpty()) {return List.of();}
        return Arrays.stream(output.split("\n")).filter(line -> !line.isBlank())
                            .map(DockerComputeProvider::parseContainerLine)
                            .toList();
    }

    static InstanceInfo parseContainerLine(String line) {
        var parts = line.split("\t", - 1);
        var id = safePart(parts, 0);
        var name = safePart(parts, 1);
        var state = safePart(parts, 2);
        var cluster = safePart(parts, 3);
        var role = safePart(parts, 4);
        var nodeId = safePart(parts, 5);
        var tags = Map.of("aether.cluster", cluster, "aether.role", role, "aether.node-id", nodeId);
        return new InstanceInfo(new InstanceId(id), mapDockerState(state), List.of(), InstanceType.ON_DEMAND, tags);
    }

    private static String safePart(String[] parts, int index) {
        return index <parts.length
              ? parts[index]
              : "";
    }

    static InstanceInfo parseInspectOutput(String output, InstanceId instanceId) {
        var parts = output.split("\t", - 1);
        var state = safePart(parts, 0);
        var name = safePart(parts, 1).replaceFirst("^/", "");
        return new InstanceInfo(instanceId, mapDockerState(state), List.of(name), InstanceType.ON_DEMAND, Map.of());
    }

    static InstanceStatus mapDockerState(String dockerState) {
        return switch (dockerState){
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

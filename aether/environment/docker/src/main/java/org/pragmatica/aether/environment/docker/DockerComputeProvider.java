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
        var ctx = ProvisionContext.provisionContext("default", "core", "default", ProvisionContext.PROVISIONED_BY_BOOTSTRAP);
        return provision(ProvisionSpec.provisionSpec(instanceType, "docker", "default", ctx).unwrap());
    }

    @Override public Promise<InstanceInfo> provision(ProvisionSpec spec) {
        var nodeIndex = nodeCounter.getAndIncrement();
        var containerName = buildContainerName(spec, nodeIndex);
        var command = buildRunCommand(spec, containerName, nodeIndex);
        return runner.execute(command).map(containerId -> toProvisionedInfo(containerId, containerName, spec, nodeIndex))
                             .mapError(DockerComputeProvider::toProvisionError);
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

    private String buildContainerName(ProvisionSpec spec, int nodeIndex) {
        var pool = spec.pool();
        var suffix = Long.toHexString(System.nanoTime()).substring(4);
        return "aether-" + pool + "-node-" + nodeIndex + "-" + suffix;
    }

    private List<String> buildRunCommand(ProvisionSpec spec, String containerName, int nodeIndex) {
        var ctx = spec.context();
        var role = roleOrDefault(ctx);
        var cluster = clusterOrDefault(ctx);
        var nodeId = ctx.nodeId().or(containerName);
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
                                              "-e",
                                              "NODE_ID=" + nodeId,
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
            command.add("-p");
            command.add((config.managementPortBase() + nodeIndex) + ":8080");
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
                                           int nodeIndex) {
        var mgmtPort = config.managementPortBase() + nodeIndex;
        var appPort = config.appPortBase() + nodeIndex;
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
        var nodeId = ctx.nodeId().or(containerName);
        return Map.of("aether.cluster", cluster, "aether.role", role, "aether.node-id", nodeId);
    }

    private static String roleOrDefault(ProvisionContext ctx) {
        return ctx.role().isEmpty()
              ? "core"
              : ctx.role();
    }

    private static String clusterOrDefault(ProvisionContext ctx) {
        return ctx.clusterName().isEmpty()
              ? "default"
              : ctx.clusterName();
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

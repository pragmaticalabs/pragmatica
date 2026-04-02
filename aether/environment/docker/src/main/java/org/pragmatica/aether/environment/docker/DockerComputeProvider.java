package org.pragmatica.aether.environment.docker;

import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.pragmatica.lang.Result.success;

/// Docker implementation of the ComputeProvider SPI.
/// Creates and manages aether-node containers on a Docker network using the Docker CLI.
/// Designed for integration testing and local development environments.
public record DockerComputeProvider( DockerCommandRunner runner,
                                     DockerConfig config,
                                     AtomicInteger nodeCounter) implements ComputeProvider {
    /// Factory method for creating a DockerComputeProvider.
    public static Result<DockerComputeProvider> dockerComputeProvider(DockerCommandRunner runner,
                                                                      DockerConfig config) {
        return success(new DockerComputeProvider(runner, config, new AtomicInteger(0)));
    }

    @Override public Promise<InstanceInfo> provision(InstanceType instanceType) {
        return provision(ProvisionSpec.provisionSpec(instanceType, "docker", "default", Map.of()).unwrap());
    }

    @Override public Promise<InstanceInfo> provision(ProvisionSpec spec) {
        var nodeIndex = nodeCounter.getAndIncrement();
        var containerName = buildContainerName(spec, nodeIndex);
        var command = buildRunCommand(spec, containerName, nodeIndex);
        return runner.execute(command)
                     .map(containerId -> toProvisionedInfo(containerId, containerName, spec, nodeIndex))
                     .mapError(DockerComputeProvider::toProvisionError);
    }

    @Override public Promise<Unit> terminate(InstanceId instanceId) {
        var stopCommand = buildStopCommand(instanceId);
        var removeCommand = buildRemoveCommand(instanceId);
        return runner.execute(stopCommand)
                     .flatMap(ignored -> runner.execute(removeCommand))
                     .mapToUnit()
                     .mapError(cause -> toTerminateError(instanceId, cause));
    }

    @Override public Promise<List<InstanceInfo>> listInstances() {
        var command = buildListCommand();
        return runner.execute(command)
                     .map(DockerComputeProvider::parseContainerList)
                     .mapError(DockerComputeProvider::toListInstancesError);
    }

    @Override public Promise<List<InstanceInfo>> listInstances(Map<String, String> tagFilter) {
        var command = buildFilteredListCommand(tagFilter);
        return runner.execute(command)
                     .map(DockerComputeProvider::parseContainerList)
                     .mapError(DockerComputeProvider::toListInstancesError);
    }

    @Override public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
        var command = buildInspectCommand(instanceId);
        return runner.execute(command)
                     .map(output -> parseInspectOutput(output, instanceId))
                     .mapError(DockerComputeProvider::toProvisionError);
    }

    @Override public Promise<Unit> restart(InstanceId id) {
        var command = buildRestartCommand(id);
        return runner.execute(command)
                     .mapToUnit()
                     .mapError(DockerComputeProvider::toProvisionError);
    }

    @Override public Promise<Unit> applyTags(InstanceId id, Map<String, String> tags) {
        return EnvironmentError.operationNotSupported("applyTags (Docker labels are immutable after creation)").promise();
    }

    // --- Leaf: build container name from spec and index ---
    private String buildContainerName(ProvisionSpec spec, int nodeIndex) {
        var pool = spec.pool();
        return "aether-" + pool + "-node-" + nodeIndex;
    }

    // --- Leaf: build docker run command ---
    private List<String> buildRunCommand(ProvisionSpec spec, String containerName, int nodeIndex) {
        var mgmtPort = config.managementPortBase() + nodeIndex;
        var appPort = config.appPortBase() + nodeIndex;
        var role = spec.tags().getOrDefault("aether.role", "core");
        var cluster = spec.tags().getOrDefault("aether.cluster", "default");
        var nodeId = spec.tags().getOrDefault("aether.node-id", containerName);
        var peers = spec.tags().getOrDefault("aether.peers", "");
        var coreMax = spec.tags().getOrDefault("aether.core-max", "3");
        var apiKey = spec.tags().getOrDefault("aether.api-key", "");

        var command = new ArrayList<>(List.of(
            "docker", "run", "-d",
            "--name", containerName,
            "--hostname", containerName,
            "--network", config.networkName(),
            "--label", "aether.cluster=" + cluster,
            "--label", "aether.role=" + role,
            "--label", "aether.node-id=" + nodeId,
            "-p", mgmtPort + ":8080",
            "-p", appPort + ":8070",
            "-e", "NODE_ID=" + nodeId,
            "-e", "CLUSTER_PORT=" + config.clusterPort(),
            "-e", "MANAGEMENT_PORT=8080",
            "-e", "PEERS=" + peers,
            "-e", "CORE_MAX=" + coreMax,
            "-e", "AETHER_API_KEY=" + apiKey));

        addSpecLabels(command, spec.tags());
        command.add(config.imageName());
        return List.copyOf(command);
    }

    // --- Leaf: add custom labels from spec tags ---
    private static void addSpecLabels(ArrayList<String> command, Map<String, String> tags) {
        tags.entrySet().stream()
            .filter(DockerComputeProvider::isCustomLabel)
            .forEach(entry -> addLabelArgs(command, entry));
    }

    // --- Leaf: check if a tag is a custom label (not an internal aether tag) ---
    private static boolean isCustomLabel(Map.Entry<String, String> entry) {
        return !entry.getKey().startsWith("aether.");
    }

    // --- Leaf: add a single label argument pair to command list ---
    private static void addLabelArgs(ArrayList<String> command, Map.Entry<String, String> entry) {
        command.add("--label");
        command.add(entry.getKey() + "=" + entry.getValue());
    }

    // --- Leaf: build docker stop command ---
    private static List<String> buildStopCommand(InstanceId instanceId) {
        return List.of("docker", "stop", instanceId.value());
    }

    // --- Leaf: build docker rm command ---
    private static List<String> buildRemoveCommand(InstanceId instanceId) {
        return List.of("docker", "rm", instanceId.value());
    }

    // --- Leaf: build docker ps command for listing all aether containers ---
    private static List<String> buildListCommand() {
        return List.of("docker", "ps", "-a",
                        "--filter", "label=aether.cluster",
                        "--format", "{{.ID}}\t{{.Names}}\t{{.State}}\t{{.Label \"aether.cluster\"}}\t{{.Label \"aether.role\"}}\t{{.Label \"aether.node-id\"}}");
    }

    // --- Leaf: build docker ps command with tag filters ---
    private static List<String> buildFilteredListCommand(Map<String, String> tagFilter) {
        var command = new ArrayList<>(List.of("docker", "ps", "-a"));
        tagFilter.forEach((key, value) -> addFilterArgs(command, key, value));
        command.addAll(List.of("--format", "{{.ID}}\t{{.Names}}\t{{.State}}\t{{.Label \"aether.cluster\"}}\t{{.Label \"aether.role\"}}\t{{.Label \"aether.node-id\"}}"));
        return List.copyOf(command);
    }

    // --- Leaf: add a filter argument pair to command list ---
    private static void addFilterArgs(ArrayList<String> command, String key, String value) {
        command.add("--filter");
        command.add("label=" + key + "=" + value);
    }

    // --- Leaf: build docker inspect command ---
    private static List<String> buildInspectCommand(InstanceId instanceId) {
        return List.of("docker", "inspect",
                        "--format", "{{.State.Status}}\t{{.Name}}\t{{.Config.Hostname}}\t{{.Id}}",
                        instanceId.value());
    }

    // --- Leaf: build docker restart command ---
    private static List<String> buildRestartCommand(InstanceId id) {
        return List.of("docker", "restart", id.value());
    }

    // --- Leaf: create InstanceInfo for a freshly provisioned container ---
    private InstanceInfo toProvisionedInfo(String containerId, String containerName, ProvisionSpec spec, int nodeIndex) {
        var mgmtPort = config.managementPortBase() + nodeIndex;
        var appPort = config.appPortBase() + nodeIndex;
        var addresses = List.of("localhost:" + mgmtPort, "localhost:" + appPort);
        var tags = buildInstanceTags(spec, containerName);
        return new InstanceInfo(new InstanceId(containerId), InstanceStatus.RUNNING, addresses, spec.instanceType(), tags);
    }

    // --- Leaf: build tags map for instance info ---
    private static Map<String, String> buildInstanceTags(ProvisionSpec spec, String containerName) {
        var role = spec.tags().getOrDefault("aether.role", "core");
        var cluster = spec.tags().getOrDefault("aether.cluster", "default");
        var nodeId = spec.tags().getOrDefault("aether.node-id", containerName);
        return Map.of("aether.cluster", cluster, "aether.role", role, "aether.node-id", nodeId);
    }

    // --- Leaf: parse docker ps output into instance info list ---
    static List<InstanceInfo> parseContainerList(String output) {
        if (output.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(output.split("\n"))
                     .filter(line -> !line.isBlank())
                     .map(DockerComputeProvider::parseContainerLine)
                     .toList();
    }

    // --- Leaf: parse a single docker ps output line ---
    static InstanceInfo parseContainerLine(String line) {
        var parts = line.split("\t", -1);
        var id = safePart(parts, 0);
        var name = safePart(parts, 1);
        var state = safePart(parts, 2);
        var cluster = safePart(parts, 3);
        var role = safePart(parts, 4);
        var nodeId = safePart(parts, 5);
        var tags = Map.of("aether.cluster", cluster, "aether.role", role, "aether.node-id", nodeId);
        return new InstanceInfo(new InstanceId(id), mapDockerState(state), List.of(), InstanceType.ON_DEMAND, tags);
    }

    // --- Leaf: safely extract a part from a string array ---
    private static String safePart(String[] parts, int index) {
        return index < parts.length ? parts[index] : "";
    }

    // --- Leaf: parse docker inspect output into instance info ---
    static InstanceInfo parseInspectOutput(String output, InstanceId instanceId) {
        var parts = output.split("\t", -1);
        var state = safePart(parts, 0);
        var name = safePart(parts, 1).replaceFirst("^/", "");
        return new InstanceInfo(instanceId, mapDockerState(state), List.of(name), InstanceType.ON_DEMAND, Map.of());
    }

    // --- Leaf: map Docker container state to InstanceStatus ---
    static InstanceStatus mapDockerState(String dockerState) {
        return switch (dockerState) {case "created", "restarting" -> InstanceStatus.PROVISIONING;case "running" -> InstanceStatus.RUNNING;case "paused", "removing", "exited" -> InstanceStatus.STOPPING;case "dead" -> InstanceStatus.TERMINATED;default -> InstanceStatus.TERMINATED;};
    }

    // --- Leaf: map cause to provision error ---
    private static EnvironmentError toProvisionError(Cause cause) {
        return EnvironmentError.provisionFailed(new RuntimeException(cause.message()));
    }

    // --- Leaf: map cause to terminate error ---
    private static EnvironmentError toTerminateError(InstanceId instanceId, Cause cause) {
        return EnvironmentError.terminateFailed(instanceId, new RuntimeException(cause.message()));
    }

    // --- Leaf: map cause to list instances error ---
    private static EnvironmentError toListInstancesError(Cause cause) {
        return EnvironmentError.listInstancesFailed(new RuntimeException(cause.message()));
    }
}

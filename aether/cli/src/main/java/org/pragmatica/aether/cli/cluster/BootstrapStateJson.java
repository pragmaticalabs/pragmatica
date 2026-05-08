// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.cluster.BootstrapState.PhaseStatus;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;

import static org.pragmatica.lang.Result.success;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"}) sealed interface BootstrapStateJson {
    record unused() implements BootstrapStateJson{}

    JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    static String toJson(BootstrapState state) {
        var sb = new StringBuilder(512);
        sb.append("{\n");
        appendStringField(sb, "clusterName", state.clusterName());
        sb.append(",\n");
        appendStringField(sb, "configHash", state.configHash());
        sb.append(",\n");
        appendStringField(sb, "startedAt", state.startedAt());
        sb.append(",\n");
        appendPhases(sb, state.phases());
        sb.append(",\n");
        appendResources(sb, state.createdResources());
        sb.append(",\n");
        appendStringList(sb, "provisionedNodeIds", state.provisionedNodeIds());
        sb.append(",\n");
        appendStringList(sb, "collectedAddresses", state.collectedAddresses());
        sb.append(",\n");
        appendStringField(sb, "clusterSecret", state.clusterSecret());
        sb.append(",\n");
        appendSources(sb, state.sources());
        sb.append("\n}");
        return sb.toString();
    }

    static Result<BootstrapState> fromJson(String json) {
        return MAPPER.readTree(json).flatMap(BootstrapStateJson::parseTree);
    }

    private static Result<BootstrapState> parseTree(JsonNode root) {
        return Result.lift(ParseError::new, () -> doParse(root));
    }

    @SuppressWarnings("JBCT-EX-01") private static BootstrapState doParse(JsonNode root) {
        var clusterName = root.path("clusterName").asText();
        var configHash = root.path("configHash").asText();
        var startedAt = root.path("startedAt").asText();
        var phases = parsePhases(root.path("phases"));
        var resources = parseResources(root.path("createdResources"));
        var nodeIds = parseStringList(root.path("provisionedNodeIds"));
        var addresses = parseStringList(root.path("collectedAddresses"));
        var clusterSecret = root.path("clusterSecret").asText("");
        var sources = parseSources(root.path("sources"));
        return BootstrapState.bootstrapState(clusterName,
                                             configHash,
                                             startedAt,
                                             phases,
                                             resources,
                                             nodeIds,
                                             addresses,
                                             clusterSecret,
                                             sources);
    }

    private static Map<String, SourceCleanupHandle> parseSources(JsonNode node) {
        if (node.isMissingNode() || node.isNull() || !node.isObject()) {return Map.of();}
        var sources = new LinkedHashMap<String, SourceCleanupHandle>();
        for (var entry : node.properties()) {
            var handle = parseSourceCleanupHandle(entry.getValue());
            if (handle != null) {sources.put(entry.getKey(), handle);}
        }
        return Map.copyOf(sources);
    }

    private static SourceCleanupHandle parseSourceCleanupHandle(JsonNode node) {
        if (node.isMissingNode() || node.isNull() || !node.isObject()) {return null;}
        var provider = node.path("provider").asText("");
        var regionText = node.path("region").asText("");
        var region = regionText.isEmpty() ? Option.<String>none() : Option.some(regionText);
        var envVars = parseStringMap(node.path("credentialEnvVars"));
        return SourceCleanupHandle.sourceCleanupHandle(provider, region, envVars);
    }

    private static Map<String, String> parseStringMap(JsonNode node) {
        if (node.isMissingNode() || node.isNull() || !node.isObject()) {return Map.of();}
        var map = new LinkedHashMap<String, String>();
        for (var entry : node.properties()) {map.put(entry.getKey(), entry.getValue().asText(""));}
        return Map.copyOf(map);
    }

    private static Map<BootstrapPhase, PhaseStatus> parsePhases(JsonNode node) {
        var phases = new EnumMap<BootstrapPhase, PhaseStatus>(BootstrapPhase.class);
        for (var phase : BootstrapPhase.values()) {phases.put(phase, PhaseStatus.PENDING);}
        if (node.isMissingNode() || node.isNull()) {return Map.copyOf(phases);}
        var fieldNames = node.properties();
        for (var entry : fieldNames) {
            var phase = parsePhaseEnum(entry.getKey());
            var status = parseStatusEnum(entry.getValue().asText());
            if (phase != null && status != null) {phases.put(phase, status);}
        }
        return Map.copyOf(phases);
    }

    private static BootstrapPhase parsePhaseEnum(String name) {
        return Result.lift(() -> BootstrapPhase.valueOf(name)).or((BootstrapPhase) null);
    }

    private static PhaseStatus parseStatusEnum(String name) {
        return Result.lift(() -> PhaseStatus.valueOf(name)).or((PhaseStatus) null);
    }

    private static List<CreatedResource> parseResources(JsonNode node) {
        if (node.isMissingNode() || node.isNull() || !node.isArray()) {return List.of();}
        var resources = new ArrayList<CreatedResource>();
        for (var element : node) {
            var resource = parseSingleResourceNode(element);
            if (resource != null) {resources.add(resource);}
        }
        return List.copyOf(resources);
    }

    @SuppressWarnings("JBCT-PAT-01") static CreatedResource parseSingleResourceNode(JsonNode node) {
        var type = node.path("type").asText("");
        return switch (type){
            case "ProvisionedVm" -> CreatedResource.ProvisionedVm.provisionedVm(node.path("provider").asText(),
                                                                                node.path("resourceId").asText(),
                                                                                node.path("sourceName").asText(),
                                                                                node.path("role").asText());
            case "FirewallRule" -> CreatedResource.FirewallRule.firewallRule(node.path("provider").asText(),
                                                                             node.path("resourceId").asText(),
                                                                             node.path("sourceName").asText(),
                                                                             node.path("port").asInt(0),
                                                                             node.path("protocol").asText("tcp"));
            case "FloatingIpAssignment" -> CreatedResource.FloatingIpAssignment.floatingIpAssignment(node.path("provider")
                                                                                                              .asText(),
                                                                                                     node.path("floatingIp")
                                                                                                              .asText(),
                                                                                                     node.path("targetNodeId")
                                                                                                              .asText());
            case "DockerContainer" -> CreatedResource.DockerContainer.dockerContainer(node.path("containerId").asText(),
                                                                                      node.path("sourceName").asText());
            case "SshDeployedConfig" -> CreatedResource.SshDeployedConfig.sshDeployedConfig(node.path("host").asText(),
                                                                                            node.path("remotePath")
                                                                                                     .asText());
            case "SshKeyResource" -> CreatedResource.SshKeyResource.sshKeyResource(node.path("provider").asText(),
                                                                                   node.path("sshKeyId").asLong(),
                                                                                   node.path("name").asText());
            default -> null;
        };
    }

    private static List<String> parseStringList(JsonNode node) {
        if (node.isMissingNode() || node.isNull() || !node.isArray()) {return List.of();}
        var result = new ArrayList<String>();
        for (var element : node) {result.add(element.asText());}
        return List.copyOf(result);
    }

    private static void appendStringField(StringBuilder sb, String key, String value) {
        sb.append("  \"").append(escapeJson(key))
                 .append("\": \"")
                 .append(escapeJson(value))
                 .append('"');
    }

    private static void appendPhases(StringBuilder sb, Map<BootstrapPhase, PhaseStatus> phases) {
        sb.append("  \"phases\": {");
        var first = true;
        for (var phase : BootstrapPhase.values()) {
            var status = phases.getOrDefault(phase, PhaseStatus.PENDING);
            if (!first) {sb.append(',');}
            sb.append("\n    \"").append(phase.name())
                     .append("\": \"")
                     .append(status.name())
                     .append('"');
            first = false;
        }
        sb.append("\n  }");
    }

    private static void appendResources(StringBuilder sb, List<CreatedResource> resources) {
        sb.append("  \"createdResources\": [");
        for (int i = 0;i <resources.size();i++) {
            if (i > 0) {sb.append(',');}
            sb.append("\n    ");
            appendResourceJson(sb, resources.get(i));
        }
        if (!resources.isEmpty()) {sb.append('\n');}
        sb.append("  ]");
    }

    @Contract@SuppressWarnings("JBCT-PAT-01") static void appendResourceJson(StringBuilder sb,
                                                                             CreatedResource resource) {
        switch (resource){
            case CreatedResource.ProvisionedVm vm -> appendVm(sb, vm);
            case CreatedResource.FirewallRule rule -> appendFirewallRule(sb, rule);
            case CreatedResource.FloatingIpAssignment ip -> appendFloatingIp(sb, ip);
            case CreatedResource.DockerContainer container -> appendDockerContainer(sb, container);
            case CreatedResource.SshDeployedConfig config -> appendSshConfig(sb, config);
            case CreatedResource.SshKeyResource key -> appendSshKey(sb, key);
        }
    }

    private static void appendSshKey(StringBuilder sb, CreatedResource.SshKeyResource key) {
        sb.append("{\"type\": \"SshKeyResource\", \"provider\": \"").append(escapeJson(key.provider()))
                 .append("\", \"sshKeyId\": ")
                 .append(key.sshKeyId())
                 .append(", \"name\": \"")
                 .append(escapeJson(key.name()))
                 .append("\"}");
    }

    private static void appendVm(StringBuilder sb, CreatedResource.ProvisionedVm vm) {
        sb.append("{\"type\": \"ProvisionedVm\", \"provider\": \"").append(escapeJson(vm.provider()))
                 .append("\", \"resourceId\": \"")
                 .append(escapeJson(vm.resourceId()))
                 .append("\", \"sourceName\": \"")
                 .append(escapeJson(vm.sourceName()))
                 .append("\", \"role\": \"")
                 .append(escapeJson(vm.role()))
                 .append("\"}");
    }

    private static void appendFirewallRule(StringBuilder sb, CreatedResource.FirewallRule rule) {
        sb.append("{\"type\": \"FirewallRule\", \"provider\": \"").append(escapeJson(rule.provider()))
                 .append("\", \"resourceId\": \"")
                 .append(escapeJson(rule.resourceId()))
                 .append("\", \"sourceName\": \"")
                 .append(escapeJson(rule.sourceName()))
                 .append("\", \"port\": ")
                 .append(rule.port())
                 .append(", \"protocol\": \"")
                 .append(escapeJson(rule.protocol()))
                 .append("\"}");
    }

    private static void appendFloatingIp(StringBuilder sb, CreatedResource.FloatingIpAssignment ip) {
        sb.append("{\"type\": \"FloatingIpAssignment\", \"provider\": \"").append(escapeJson(ip.provider()))
                 .append("\", \"floatingIp\": \"")
                 .append(escapeJson(ip.floatingIp()))
                 .append("\", \"targetNodeId\": \"")
                 .append(escapeJson(ip.targetNodeId()))
                 .append("\"}");
    }

    private static void appendDockerContainer(StringBuilder sb, CreatedResource.DockerContainer container) {
        sb.append("{\"type\": \"DockerContainer\", \"containerId\": \"").append(escapeJson(container.containerId()))
                 .append("\", \"sourceName\": \"")
                 .append(escapeJson(container.sourceName()))
                 .append("\"}");
    }

    private static void appendSshConfig(StringBuilder sb, CreatedResource.SshDeployedConfig config) {
        sb.append("{\"type\": \"SshDeployedConfig\", \"host\": \"").append(escapeJson(config.host()))
                 .append("\", \"remotePath\": \"")
                 .append(escapeJson(config.remotePath()))
                 .append("\"}");
    }

    private static void appendSources(StringBuilder sb, Map<String, SourceCleanupHandle> sources) {
        sb.append("  \"sources\": {");
        var first = true;
        for (var entry : sources.entrySet()) {
            if (!first) {sb.append(',');}
            sb.append("\n    \"").append(escapeJson(entry.getKey()))
                     .append("\": ");
            appendSourceCleanupHandle(sb, entry.getValue());
            first = false;
        }
        if (!sources.isEmpty()) {sb.append("\n  ");}
        sb.append('}');
    }

    private static void appendSourceCleanupHandle(StringBuilder sb, SourceCleanupHandle handle) {
        sb.append("{\"provider\": \"").append(escapeJson(handle.provider()))
                 .append("\", \"region\": \"")
                 .append(escapeJson(handle.region().or("")))
                 .append("\", \"credentialEnvVars\": {");
        var first = true;
        for (var entry : handle.credentialEnvVars().entrySet()) {
            if (!first) {sb.append(", ");}
            sb.append('"').append(escapeJson(entry.getKey()))
                     .append("\": \"")
                     .append(escapeJson(entry.getValue()))
                     .append('"');
            first = false;
        }
        sb.append("}}");
    }

    private static void appendStringList(StringBuilder sb, String key, List<String> values) {
        sb.append("  \"").append(escapeJson(key))
                 .append("\": [");
        for (int i = 0;i <values.size();i++) {
            if (i > 0) {sb.append(", ");}
            sb.append('"').append(escapeJson(values.get(i)))
                     .append('"');
        }
        sb.append(']');
    }

    private static String escapeJson(String value) {
        if (value == null) {return "";}
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                            .replace("\n", "\\n")
                            .replace("\r", "\\r")
                            .replace("\t", "\\t");
    }

    record ParseError(Throwable cause) implements Cause {
        @Override public String message() {
            return "Failed to parse bootstrap state JSON: " + cause.getMessage();
        }
    }
}

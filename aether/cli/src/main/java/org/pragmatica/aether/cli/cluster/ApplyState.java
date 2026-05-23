// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Result.success;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
public record ApplyState(String clusterName,
                         String configHash,
                         String startedAt,
                         int currentWaveIndex,
                         List<WaveStatus> waves,
                         List<CreatedResource> createdResources,
                         List<String> destroyedNodeIds) {
    public ApplyState {
        waves = List.copyOf(waves);
        createdResources = List.copyOf(createdResources);
        destroyedNodeIds = List.copyOf(destroyedNodeIds);
    }

    public static ApplyState applyState(String clusterName,
                                        String configHash,
                                        String startedAt,
                                        int currentWaveIndex,
                                        List<WaveStatus> waves,
                                        List<CreatedResource> createdResources,
                                        List<String> destroyedNodeIds) {
        return new ApplyState(clusterName,
                              configHash,
                              startedAt,
                              currentWaveIndex,
                              waves,
                              createdResources,
                              destroyedNodeIds);
    }

    public static ApplyState initialState(String clusterName, String configHash, String startedAt) {
        var waves = List.of(WaveStatus.PENDING, WaveStatus.PENDING, WaveStatus.PENDING);

        return applyState(clusterName, configHash, startedAt, 0, waves, List.of(), List.of());
    }

    public ApplyState withWaveStatus(int waveIndex, WaveStatus status) {
        var updated = new ArrayList<>(waves);
        updated.set(waveIndex, status);

        return applyState(clusterName, configHash, startedAt, waveIndex, updated, createdResources, destroyedNodeIds);
    }

    public ApplyState withResource(CreatedResource resource) {
        var updated = new ArrayList<>(createdResources);
        updated.add(resource);

        return applyState(clusterName, configHash, startedAt, currentWaveIndex, waves, updated, destroyedNodeIds);
    }

    public ApplyState withDestroyedNode(String nodeId) {
        var updated = new ArrayList<>(destroyedNodeIds);
        updated.add(nodeId);

        return applyState(clusterName, configHash, startedAt, currentWaveIndex, waves, createdResources, updated);
    }

    public enum WaveStatus {
        PENDING,
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }

    static final Path AETHER_DIR = Path.of(System.getProperty("user.home"), ".aether", "clusters");
    static final String STATE_FILE_NAME = "apply-state.json";

    static Result<Unit> save(ApplyState state) {
        return Result.lift(PersistenceError::new, () -> doSave(state));
    }

    static Option<ApplyState> load(String clusterName) {
        var path = stateFilePath(clusterName);

        if (!Files.exists(path)) {return none();}

        return Result.lift(PersistenceError::new,
                           () -> Files.readString(path))
                     .flatMap(ApplyState::fromJson)
                     .onFailure(cause -> System.err.println("Warning: failed to load apply state: " + cause.message()))
                     .option();
    }

    static Result<Unit> delete(String clusterName) {
        return Result.lift(PersistenceError::new, () -> doDelete(clusterName));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Unit doSave(ApplyState state) throws Exception {
        var dir = AETHER_DIR.resolve(state.clusterName());
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(STATE_FILE_NAME), toJson(state));

        return Unit.unit();
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Unit doDelete(String clusterName) throws Exception {
        var path = stateFilePath(clusterName);
        Files.deleteIfExists(path);

        return Unit.unit();
    }

    private static Path stateFilePath(String clusterName) {
        return AETHER_DIR.resolve(clusterName).resolve(STATE_FILE_NAME);
    }

    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    static String toJson(ApplyState state) {
        var sb = new StringBuilder(512);
        sb.append("{\n");
        appendStringField(sb, "clusterName", state.clusterName());
        sb.append(",\n");
        appendStringField(sb, "configHash", state.configHash());
        sb.append(",\n");
        appendStringField(sb, "startedAt", state.startedAt());
        sb.append(",\n");
        sb.append("  \"currentWaveIndex\": ").append(state.currentWaveIndex());
        sb.append(",\n");
        appendWaves(sb, state.waves());
        sb.append(",\n");
        appendResources(sb, state.createdResources());
        sb.append(",\n");
        appendStringList(sb, "destroyedNodeIds", state.destroyedNodeIds());
        sb.append("\n}");

        return sb.toString();
    }

    static Result<ApplyState> fromJson(String json) {
        return MAPPER.readTree(json).flatMap(ApplyState::parseTree);
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<ApplyState> parseTree(JsonNode root) {
        return Result.lift(ParseError::new, () -> doParse(root));
    }

    private static ApplyState doParse(JsonNode root) {
        var clusterName = root.path("clusterName").asText();
        var configHash = root.path("configHash").asText();
        var startedAt = root.path("startedAt").asText();
        var waveIndex = root.path("currentWaveIndex").asInt(0);
        var waves = parseWaves(root.path("waves"));
        var resources = parseResources(root.path("createdResources"));
        var destroyed = parseStringList(root.path("destroyedNodeIds"));

        return applyState(clusterName, configHash, startedAt, waveIndex, waves, resources, destroyed);
    }

    private static List<WaveStatus> parseWaves(JsonNode node) {
        if (node.isMissingNode() || node.isNull() || !node.isArray()) {
            return List.of(WaveStatus.PENDING, WaveStatus.PENDING, WaveStatus.PENDING);
        }

        var statuses = new ArrayList<WaveStatus>();

        for (var element : node) {statuses.add(parseWaveStatus(element.asText()));}

        return List.copyOf(statuses);
    }

    private static WaveStatus parseWaveStatus(String name) {
        return Result.lift(() -> WaveStatus.valueOf(name)).or(WaveStatus.PENDING);
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static List<CreatedResource> parseResources(JsonNode node) {
        if (node.isMissingNode() || node.isNull() || !node.isArray()) {return List.of();}

        var resources = new ArrayList<CreatedResource>();

        for (var element : node) {
            var resource = BootstrapStateJson.parseSingleResourceNode(element);
            if (resource != null) {resources.add(resource);}
        }

        return List.copyOf(resources);
    }

    private static List<String> parseStringList(JsonNode node) {
        if (node.isMissingNode() || node.isNull() || !node.isArray()) {return List.of();}

        var result = new ArrayList<String>();

        for (var element : node) {result.add(element.asText());}

        return List.copyOf(result);
    }

    @Contract
    private static void appendStringField(StringBuilder sb, String key, String value) {
        sb.append("  \"").append(escapeJson(key)).append("\": \"").append(escapeJson(value)).append('"');
    }

    @Contract
    private static void appendWaves(StringBuilder sb, List<WaveStatus> waves) {
        sb.append("  \"waves\": [");

        for (int i = 0;i <waves.size();i++) {
            if (i > 0) {sb.append(", ");}
            sb.append('"').append(waves.get(i).name()).append('"');
        }

        sb.append(']');
    }

    @Contract
    private static void appendResources(StringBuilder sb, List<CreatedResource> resources) {
        sb.append("  \"createdResources\": [");

        for (int i = 0;i <resources.size();i++) {
            if (i > 0) {sb.append(',');}

            sb.append("\n    ");
            BootstrapStateJson.appendResourceJson(sb, resources.get(i));
        }
        if (!resources.isEmpty()) {sb.append('\n');}

        sb.append("  ]");
    }

    @Contract
    private static void appendStringList(StringBuilder sb, String key, List<String> values) {
        sb.append("  \"").append(escapeJson(key)).append("\": [");

        for (int i = 0;i <values.size();i++) {
            if (i > 0) {sb.append(", ");}
            sb.append('"').append(escapeJson(values.get(i))).append('"');
        }

        sb.append(']');
    }

    private static String escapeJson(String value) {
        if (value == null) {return "";}
        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }

    record PersistenceError(Throwable cause) implements Cause {
        @Override
        public String message() {
            return "Apply state persistence error: " + cause.getMessage();
        }
    }

    record ParseError(Throwable cause) implements Cause {
        @Override
        public String message() {
            return "Failed to parse apply state JSON: " + cause.getMessage();
        }
    }
}

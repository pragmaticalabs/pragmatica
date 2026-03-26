package org.pragmatica.aether.cli.cluster;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/// Applies a cluster configuration file to the active cluster.
///
/// Parses the config, computes a diff against the stored config, and either
/// prints planned changes (dry-run) or executes them.
@Command(name = "apply", description = "Apply cluster configuration changes")
@SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01", "JBCT-SEQ-01"})
class ClusterApplyCommand implements Callable<Integer> {
    @Parameters(index = "0", description = "Path to aether-cluster.toml config file")
    private Path configFile;

    @Option(names = "--dry-run", description = "Show planned changes without executing")
    private boolean dryRun;

    @Option(names = "--yes", description = "Skip confirmation prompt")
    private boolean skipConfirmation;

    @Override
    public Integer call() {
        return readConfigFile().flatMap(this::executeApply)
                             .fold(ClusterApplyCommand::onFailure, v -> v);
    }

    private Result<String> readConfigFile() {
        return Result.lift(() -> Files.readString(configFile));
    }

    private Result<Integer> executeApply(String tomlContent) {
        return fetchCurrentVersion().flatMap(version -> sendApplyRequest(tomlContent, version));
    }

    private Result<Long> fetchCurrentVersion() {
        return ClusterHttpClient.fetchFromCluster("/api/cluster/config")
                                .map(ClusterApplyCommand::extractConfigVersion);
    }

    private static long extractConfigVersion(String responseJson) {
        var fields = SimpleJsonReader.parseObject(responseJson);
        var versionStr = fields.getOrDefault("configVersion", "0");
        return parseLong(versionStr);
    }

    private Result<Integer> sendApplyRequest(String tomlContent, long expectedVersion) {
        var jsonBody = buildApplyJson(tomlContent, dryRun
                                                  ? 0
                                                  : expectedVersion);
        return ClusterHttpClient.postToCluster("/api/cluster/config", jsonBody)
                                .map(this::printResult);
    }

    private Integer printResult(String responseJson) {
        var fields = SimpleJsonReader.parseObject(responseJson);
        if (fields.containsKey("plannedChanges")) {
            return printDryRunResult(fields);
        }
        return printApplyResult(fields);
    }

    private static Integer printDryRunResult(Map<String, String> fields) {
        var clusterName = fields.getOrDefault("clusterName", "unknown");
        var fromVersion = fields.getOrDefault("fromVersion", "?");
        var changeCount = fields.getOrDefault("changeCount", "0");
        var rejectedCount = fields.getOrDefault("rejectedCount", "0");
        var changes = fields.getOrDefault("plannedChanges", "[]");
        System.out.printf("Cluster: %s (version %s)%n", clusterName, fromVersion);
        System.out.println("Planned changes:");
        printChanges(changes);
        System.out.printf("%n%s changes, %s rejected.%n", changeCount, rejectedCount);
        System.out.println("Run without --dry-run to apply.");
        return 0;
    }

    private static Integer printApplyResult(Map<String, String> fields) {
        var clusterName = fields.getOrDefault("clusterName", "unknown");
        var configVersion = fields.getOrDefault("configVersion", "?");
        var coreCount = fields.getOrDefault("coreCount", "?");
        System.out.printf("Applied successfully.%n");
        System.out.printf("Cluster: %s%n", clusterName);
        System.out.printf("Config version: %s%n", configVersion);
        System.out.printf("Core count: %s%n", coreCount);
        return 0;
    }

    private static void printChanges(String changesJson) {
        var trimmed = changesJson.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            var inner = trimmed.substring(1, trimmed.length() - 1);
            if (!inner.isBlank()) {
                for (var entry : inner.split(",")) {
                    var desc = entry.trim();
                    if (desc.startsWith("\"") && desc.endsWith("\"")) {
                        desc = desc.substring(1, desc.length() - 1);
                    }
                    System.out.println("  " + desc);
                }
            }
        }
    }

    private static String buildApplyJson(String tomlContent, long expectedVersion) {
        var escapedToml = escapeJsonString(tomlContent);
        return "{\"tomlContent\":\"" + escapedToml + "\",\"expectedVersion\":" + expectedVersion + "}";
    }

    private static String escapeJsonString(String value) {
        return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
    }

    private static long parseLong(String value) {
        try{
            return Long.parseLong(value);
        } catch (NumberFormatException _) {
            return 0;
        }
    }

    private static Integer onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());
        return 1;
    }
}

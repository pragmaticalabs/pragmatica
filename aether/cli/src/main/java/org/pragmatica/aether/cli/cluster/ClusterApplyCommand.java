package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import tools.jackson.databind.JsonNode;

/// Applies a cluster configuration file to the active cluster.
///
/// Parses the config, computes a diff against the stored config, and either
/// prints planned changes (dry-run) or executes them.
@Command(name = "apply", description = "Apply cluster configuration changes")
@SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01", "JBCT-SEQ-01"})
class ClusterApplyCommand implements Callable<Integer> {
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    @Parameters(index = "0", description = "Path to aether-cluster.toml config file")
    private Path configFile;

    @Option(names = "--dry-run", description = "Show planned changes without executing")
    private boolean dryRun;

    @Option(names = "--yes", description = "Skip confirmation prompt")
    private boolean skipConfirmation;

    @CommandLine.ParentCommand
    private ClusterCommand parent;

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
                                .flatMap(MAPPER::readTree)
                                .map(node -> node.path("configVersion").asLong(0));
    }

    private Result<Integer> sendApplyRequest(String tomlContent, long expectedVersion) {
        var jsonBody = buildApplyJson(tomlContent, dryRun ? 0 : expectedVersion);
        return ClusterHttpClient.postToCluster("/api/cluster/config", jsonBody)
                                .map(this::printResult);
    }

    private int printResult(String json) {
        return OutputFormatter.printAction(json, parent.outputOptions(), dryRun ? "Dry-run complete." : "Applied successfully.");
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

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());
        return ExitCode.ERROR;
    }
}

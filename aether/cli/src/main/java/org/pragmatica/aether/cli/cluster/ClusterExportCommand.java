package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.OutputOptions;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;

import java.time.Instant;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import tools.jackson.databind.JsonNode;

/// Exports the cluster configuration as TOML from the management API.
///
/// Default output is the raw TOML content. Use `--with-status` to prepend
/// runtime state as TOML comments.
@Command(name = "export", description = "Export cluster configuration as TOML")
@SuppressWarnings("JBCT-RET-01")
class ClusterExportCommand implements Callable<Integer> {
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    @Option(names = "--with-status", description = "Include runtime state as comments")
    private boolean withStatus;

    @Mixin
    private OutputOptions output;

    @Override
    public Integer call() {
        return ClusterHttpClient.fetchFromCluster("/api/cluster/config")
                                .flatMap(MAPPER::readTree)
                                .fold(ClusterExportCommand::onFailure, this::onSuccess);
    }

    private int onSuccess(JsonNode root) {
        var tomlContent = root.path("tomlContent").asText("");
        if (withStatus) {
            printStatusHeader(root);
        }
        System.out.println(tomlContent);
        return ExitCode.SUCCESS;
    }

    private void printStatusHeader(JsonNode root) {
        var clusterName = root.path("clusterName").asText("unknown");
        var configVersion = root.path("configVersion").asText("?");
        var coreCount = root.path("coreCount").asText("?");
        System.out.printf("# --- Exported from cluster \"%s\" at %s ---%n", clusterName, Instant.now());
        System.out.printf("# Config version: %s%n", configVersion);
        System.out.printf("# Core count: %s%n", coreCount);
        enrichWithLiveStatus();
        System.out.println();
    }

    private void enrichWithLiveStatus() {
        ClusterHttpClient.fetchFromCluster("/api/cluster/status")
                         .flatMap(MAPPER::readTree)
                         .onSuccess(ClusterExportCommand::printLiveStatusComments);
    }

    private static void printLiveStatusComments(JsonNode status) {
        var actualCount = status.path("actualCoreCount").asText("?");
        var desiredCount = status.path("desiredCoreCount").asText("?");
        var leader = status.path("leaderId").asText("none");
        var certExpires = status.path("certificateExpiresAt").asText("N/A");
        System.out.printf("# Actual core nodes: %s/%s%n", actualCount, desiredCount);
        System.out.printf("# Leader: %s%n", leader);
        System.out.printf("# Certificate expires: %s%n", certExpires);
    }

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());
        return ExitCode.ERROR;
    }
}

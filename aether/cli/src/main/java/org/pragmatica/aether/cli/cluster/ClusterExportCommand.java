package org.pragmatica.aether.cli.cluster;

import org.pragmatica.lang.Cause;

import java.time.Instant;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/// Exports the cluster configuration as TOML from the management API.
///
/// Default output is the raw TOML content. Use `--with-status` to prepend
/// runtime state as TOML comments.
@Command(name = "export", description = "Export cluster configuration as TOML")
@SuppressWarnings("JBCT-RET-01")
class ClusterExportCommand implements Callable<Integer> {
    @Option(names = "--with-status", description = "Include runtime state as comments")
    private boolean withStatus;

    @Override
    public Integer call() {
        return ClusterHttpClient.fetchFromCluster("/api/cluster/config")
                                .fold(ClusterExportCommand::onFailure, this::onSuccess);
    }

    private Integer onSuccess(String body) {
        var fields = SimpleJsonReader.parseObject(body);
        var tomlContent = fields.getOrDefault("tomlContent", "");
        if (withStatus) {
            printStatusHeader(fields);
        }
        System.out.println(tomlContent);
        return 0;
    }

    private void printStatusHeader(java.util.Map<String, String> fields) {
        var clusterName = fields.getOrDefault("clusterName", "unknown");
        var configVersion = fields.getOrDefault("configVersion", "?");
        var coreCount = fields.getOrDefault("coreCount", "?");
        System.out.printf("# --- Exported from cluster \"%s\" at %s ---%n", clusterName, Instant.now());
        System.out.printf("# Config version: %s%n", configVersion);
        System.out.printf("# Core count: %s%n", coreCount);
        enrichWithLiveStatus();
        System.out.println();
    }

    private void enrichWithLiveStatus() {
        ClusterHttpClient.fetchFromCluster("/api/cluster/status")
                         .onSuccess(ClusterExportCommand::printLiveStatusComments);
    }

    private static void printLiveStatusComments(String statusJson) {
        var status = SimpleJsonReader.parseObject(statusJson);
        var actualCount = status.getOrDefault("actualCoreCount", "?");
        var desiredCount = status.getOrDefault("desiredCoreCount", "?");
        var leader = status.getOrDefault("leaderId", "none");
        var certExpires = status.getOrDefault("certificateExpiresAt", "N/A");
        System.out.printf("# Actual core nodes: %s/%s%n", actualCount, desiredCount);
        System.out.printf("# Leader: %s%n", leader);
        System.out.printf("# Certificate expires: %s%n", certExpires);
    }

    private static Integer onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());
        return 1;
    }
}

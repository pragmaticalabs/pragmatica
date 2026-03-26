package org.pragmatica.aether.cli.cluster;

import org.pragmatica.lang.Cause;

import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/// Displays aggregated cluster status from the management API.
///
/// Default output is a human-readable table. Use `--json` for raw JSON.
@Command(name = "status", description = "Show cluster status")
@SuppressWarnings("JBCT-RET-01")
class ClusterStatusCommand implements Callable<Integer> {
    @Option(names = "--json", description = "Output raw JSON")
    private boolean jsonOutput;

    @Override
    public Integer call() {
        return ClusterHttpClient.fetchFromCluster("/api/cluster/status")
                                .fold(ClusterStatusCommand::onFailure, this::onSuccess);
    }

    private Integer onSuccess(String body) {
        if (jsonOutput) {
            System.out.println(body);
            return 0;
        }
        return printFormatted(body);
    }

    @SuppressWarnings("JBCT-PAT-01")
    private static Integer printFormatted(String json) {
        var fields = SimpleJsonReader.parseObject(json);
        var clusterName = fields.getOrDefault("clusterName", "unknown");
        var desiredVersion = fields.getOrDefault("desiredVersion", "?");
        var configVersion = fields.getOrDefault("configVersion", "?");
        var state = fields.getOrDefault("state", "UNKNOWN");
        var desiredCoreCount = fields.getOrDefault("desiredCoreCount", "?");
        var actualCoreCount = fields.getOrDefault("actualCoreCount", "?");
        var leaderId = fields.getOrDefault("leaderId", "none");
        var slicesDeployed = fields.getOrDefault("slicesDeployed", "0");
        var sliceInstances = fields.getOrDefault("sliceInstances", "0");
        var certExpires = fields.getOrDefault("certificateExpiresAt", "N/A");
        var certDays = fields.getOrDefault("certificateDaysRemaining", "0");
        var uptimeSeconds = fields.getOrDefault("uptimeSeconds", "0");
        System.out.printf("Cluster: %s%n", clusterName);
        System.out.printf("Version: %s (config version: %s)%n", desiredVersion, configVersion);
        System.out.printf("State: %s%n", state);
        System.out.println();
        System.out.printf("Nodes (%s/%s healthy):%n", actualCoreCount, desiredCoreCount);
        System.out.printf("  Leader: %s%n", leaderId);
        System.out.println();
        System.out.printf("Slices: %s deployed, %s instances%n", slicesDeployed, sliceInstances);
        System.out.printf("Certificate: expires %s (%s seconds remaining)%n", certExpires, certDays);
        System.out.printf("Uptime: %s seconds%n", uptimeSeconds);
        return 0;
    }

    private static Integer onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());
        return 1;
    }
}

package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;


/// Initiates a cross-environment cluster migration via the management API.
///
/// Flow:
/// 1. Validates input parameters (target provider, zone, strategy)
/// 2. Posts migration request to `POST /api/cluster/migrate`
/// 3. Displays migration plan or initiation result
@Command(name = "migrate", description = "Migrate cluster to a different cloud environment") @SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01", "JBCT-SEQ-01"}) class ClusterMigrateCommand implements Callable<Integer> {
    @Option(names = "--target", required = true, description = "Target cloud provider (aws, gcp, azure, hetzner)") private String targetProvider;

    @Option(names = "--zone", required = true, description = "Target availability zone or region (e.g., us-east-1a)") private String targetZone;

    @Option(names = "--strategy", defaultValue = "rolling", description = "Migration strategy: rolling or blue_green (default: rolling)") private String strategy;

    @Option(names = "--dns", description = "DNS hostname to update after migration (e.g., app.example.com)") private String dnsHostname;

    @Option(names = "--dry-run", description = "Show migration plan without executing") private boolean dryRun;

    @CommandLine.ParentCommand private ClusterCommand parent;

    @Override public Integer call() {
        return validateStrategy().flatMap(this::sendMigrateRequest)
                               .fold(ClusterMigrateCommand::onFailure, this::onSuccess);
    }

    private Result<String> validateStrategy() {
        return switch (strategy.toLowerCase()){
            case "rolling", "blue_green" -> Result.success(strategy.toLowerCase());
            default -> new MigrateError.InvalidStrategy(strategy).result();
        };
    }

    private Result<String> sendMigrateRequest(String validStrategy) {
        var jsonBody = buildRequestJson(validStrategy);
        var path = dryRun
                  ? "/api/cluster/migrate/plan"
                  : "/api/cluster/migrate";
        return ClusterHttpClient.postToCluster(path, jsonBody);
    }

    private String buildRequestJson(String validStrategy) {
        var sb = new StringBuilder(128);
        sb.append("{\"targetProvider\":\"").append(targetProvider).append('"');
        sb.append(",\"targetZone\":\"").append(targetZone).append('"');
        sb.append(",\"strategy\":\"").append(validStrategy).append('"');
        if (dnsHostname != null && !dnsHostname.isEmpty()) {
            sb.append(",\"dnsHostname\":\"").append(dnsHostname).append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    private int onSuccess(String json) {
        var label = dryRun
                   ? "Migration plan generated."
                   : "Migration initiated.";
        return OutputFormatter.printAction(json, parent.outputOptions(), label);
    }

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());
        return ExitCode.ERROR;
    }

    sealed interface MigrateError extends Cause {
        record InvalidStrategy(String strategy) implements MigrateError {
            @Override public String message() {
                return "Invalid migration strategy: " + strategy + " (expected: rolling or blue_green)";
            }
        }
    }
}

package org.pragmatica.aether.cli.cluster;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/// Initiates a cluster upgrade to a target version via the management API.
///
/// Flow:
/// 1. Reads current cluster config via `GET /api/cluster/config`
/// 2. If version unchanged, reports "Already at version X.Y.Z"
/// 3. Initiates upgrade via `POST /api/cluster/upgrade`
/// 4. Displays upgrade initiation result
@Command(name = "upgrade", description = "Upgrade cluster to a target version")
@SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01", "JBCT-SEQ-01"})
class ClusterUpgradeCommand implements Callable<Integer> {
    private static final Pattern VERSION_PATTERN = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");

    @Option(names = "--version", required = true, description = "Target version (e.g., 0.26.0)")
    private String targetVersion;

    @Option(names = "--json", description = "Output raw JSON")
    private boolean jsonOutput;

    @Override
    public Integer call() {
        return validateVersion().flatMap(this::fetchCurrentConfig)
                              .flatMap(this::initiateUpgrade)
                              .fold(ClusterUpgradeCommand::onFailure, this::onSuccess);
    }

    private Result<String> validateVersion() {
        if (!VERSION_PATTERN.matcher(targetVersion)
                            .matches()) {
            return new UpgradeError.InvalidVersion(targetVersion).result();
        }
        return Result.success(targetVersion);
    }

    private Result<Map<String, String>> fetchCurrentConfig(String version) {
        return ClusterHttpClient.fetchFromCluster("/api/cluster/config")
                                .map(SimpleJsonReader::parseObject);
    }

    private Result<String> initiateUpgrade(Map<String, String> config) {
        var currentVersion = config.getOrDefault("version", "unknown");
        if (targetVersion.equals(currentVersion)) {
            return new UpgradeError.AlreadyAtVersion(targetVersion).result();
        }
        var jsonBody = "{\"targetVersion\":\"" + targetVersion + "\"}";
        return ClusterHttpClient.postToCluster("/api/cluster/upgrade", jsonBody);
    }

    private Integer onSuccess(String body) {
        if (jsonOutput) {
            System.out.println(body);
            return 0;
        }
        return printFormatted(body);
    }

    private static Integer printFormatted(String json) {
        var fields = SimpleJsonReader.parseObject(json);
        var status = fields.getOrDefault("status", "UNKNOWN");
        var fromVersion = fields.getOrDefault("from", "?");
        var toVersion = fields.getOrDefault("to", "?");
        System.out.printf("Upgrade %s.%n", status.toLowerCase());
        System.out.printf("Version: %s -> %s%n", fromVersion, toVersion);
        return 0;
    }

    private static Integer onFailure(Cause cause) {
        if (cause instanceof UpgradeError.AlreadyAtVersion alreadyAt) {
            System.out.printf("Already at version %s. No upgrade needed.%n", alreadyAt.version());
            return 0;
        }
        System.err.println("Error: " + cause.message());
        return 1;
    }

    sealed interface UpgradeError extends Cause {
        record InvalidVersion(String version) implements UpgradeError {
            @Override
            public String message() {
                return "Invalid version format: " + version + " (expected X.Y.Z)";
            }
        }

        record AlreadyAtVersion(String version) implements UpgradeError {
            @Override
            public String message() {
                return "Already at version " + version;
            }
        }
    }
}

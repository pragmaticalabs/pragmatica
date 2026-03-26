package org.pragmatica.aether.cli.cluster;

import org.pragmatica.lang.Cause;

import java.util.Map;
import java.util.concurrent.Callable;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/// Scales the cluster core node count via the management API.
///
/// Thin wrapper around `POST /api/cluster/scale`. Validates quorum safety
/// (N >= 3, odd) on the CLI side before sending the request.
@Command(name = "scale", description = "Scale cluster core node count")
@SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01", "JBCT-SEQ-01"})
class ClusterScaleCommand implements Callable<Integer> {
    private static final int MINIMUM_CORE_COUNT = 3;

    @Option(names = "--core", required = true, description = "Target core node count (minimum 3, must be odd)")
    private int coreCount;

    @Option(names = "--json", description = "Output raw JSON")
    private boolean jsonOutput;

    @Override
    public Integer call() {
        return validateCoreCount().flatMap(this::fetchConfigVersion)
                                .flatMap(this::sendScaleRequest)
                                .fold(ClusterScaleCommand::onFailure, this::onSuccess);
    }

    private org.pragmatica.lang.Result<Integer> validateCoreCount() {
        if (coreCount < MINIMUM_CORE_COUNT) {
            return new ScaleError.QuorumSafety(coreCount, MINIMUM_CORE_COUNT).result();
        }
        if (coreCount % 2 == 0) {
            return new ScaleError.MustBeOdd(coreCount).result();
        }
        return org.pragmatica.lang.Result.success(coreCount);
    }

    private org.pragmatica.lang.Result<Long> fetchConfigVersion(int count) {
        return ClusterHttpClient.fetchFromCluster("/api/cluster/config")
                                .map(ClusterScaleCommand::extractConfigVersion);
    }

    private org.pragmatica.lang.Result<String> sendScaleRequest(long expectedVersion) {
        var jsonBody = "{\"coreCount\":" + coreCount + ",\"expectedVersion\":" + expectedVersion + "}";
        return ClusterHttpClient.postToCluster("/api/cluster/scale", jsonBody);
    }

    private Integer onSuccess(String body) {
        if (jsonOutput) {
            System.out.println(body);
            return 0;
        }
        return printFormatted(body);
    }

    private Integer printFormatted(String json) {
        var fields = SimpleJsonReader.parseObject(json);
        var previousCount = fields.getOrDefault("previousCount", "?");
        var newCount = fields.getOrDefault("newCount", "?");
        var configVersion = fields.getOrDefault("configVersion", "?");
        System.out.printf("Scale successful.%n");
        System.out.printf("Core nodes: %s -> %s%n", previousCount, newCount);
        System.out.printf("Config version: %s%n", configVersion);
        printScaleWarning(previousCount, newCount);
        return 0;
    }

    private static void printScaleWarning(String previousStr, String newStr) {
        var previous = parseLong(previousStr);
        var target = parseLong(newStr);
        if (target < previous) {
            System.out.printf("%nWarning: scaling down from %d to %d nodes. Excess nodes will be drained.%n",
                              previous,
                              target);
        }
    }

    private static long extractConfigVersion(String responseJson) {
        var fields = SimpleJsonReader.parseObject(responseJson);
        var versionStr = fields.getOrDefault("configVersion", "0");
        return parseLong(versionStr);
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

    sealed interface ScaleError extends Cause {
        record QuorumSafety(int requested, int minimum) implements ScaleError {
            @Override
            public String message() {
                return "Core count " + requested + " is below quorum minimum of " + minimum;
            }
        }

        record MustBeOdd(int requested) implements ScaleError {
            @Override
            public String message() {
                return "Core count must be odd for quorum safety, got " + requested;
            }
        }
    }
}

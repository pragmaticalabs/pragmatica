package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/// Scales the cluster core node count via the management API.
///
/// Thin wrapper around `POST /api/cluster/scale`. Validates quorum safety
/// (N >= 3, odd) on the CLI side before sending the request.
@Command(name = "scale", description = "Scale cluster core node count")
@SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01", "JBCT-SEQ-01"}) class ClusterScaleCommand implements Callable<Integer> {
    private static final int MINIMUM_CORE_COUNT = 3;
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    @Option(names = "--core", required = true, description = "Target core node count (minimum 3, must be odd)")
    private int coreCount;

    @CommandLine.ParentCommand private ClusterCommand parent;

    @Override public Integer call() {
        return validateCoreCount().flatMap(this::fetchConfigVersion)
                                .flatMap(this::sendScaleRequest)
                                .fold(ClusterScaleCommand::onFailure, this::onSuccess);
    }

    private Result<Integer> validateCoreCount() {
        if ( coreCount < MINIMUM_CORE_COUNT) {
        return new ScaleError.QuorumSafety(coreCount, MINIMUM_CORE_COUNT).result();}
        if ( coreCount % 2 == 0) {
        return new ScaleError.MustBeOdd(coreCount).result();}
        return Result.success(coreCount);
    }

    private Result<Long> fetchConfigVersion(int count) {
        return ClusterHttpClient.fetchFromCluster("/api/cluster/config")
        .flatMap(ClusterScaleCommand::extractConfigVersion);
    }

    private Result<String> sendScaleRequest(long expectedVersion) {
        var jsonBody = "{\"coreCount\":" + coreCount + ",\"expectedVersion\":" + expectedVersion + "}";
        return ClusterHttpClient.postToCluster("/api/cluster/scale", jsonBody);
    }

    private int onSuccess(String json) {
        return OutputFormatter.printAction(json, parent.outputOptions(), "Scale successful.");
    }

    private static Result<Long> extractConfigVersion(String responseJson) {
        return MAPPER.readTree(responseJson).map(node -> node.path("configVersion").asLong(0));
    }

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());
        return ExitCode.ERROR;
    }

    sealed interface ScaleError extends Cause {
        record QuorumSafety(int requested, int minimum) implements ScaleError {
            @Override public String message() {
                return "Core count " + requested + " is below quorum minimum of " + minimum;
            }
        }

        record MustBeOdd(int requested) implements ScaleError {
            @Override public String message() {
                return "Core count must be odd for quorum safety, got " + requested;
            }
        }
    }
}

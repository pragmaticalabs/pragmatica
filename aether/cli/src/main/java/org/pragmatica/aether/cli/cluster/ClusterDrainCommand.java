package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;

import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import tools.jackson.databind.JsonNode;

/// Drains a node by transitioning it from ON_DUTY to DRAINING state.
///
/// Delegates to `POST /api/node/drain/{nodeId}`. Optionally waits for
/// the node to reach DECOMMISSIONED state before returning.
@Command(name = "drain", description = "Drain a node (evacuate slices)")
@SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01"}) class ClusterDrainCommand implements Callable<Integer> {
    private static final int POLL_INTERVAL_MS = 2000;
    private static final int DEFAULT_TIMEOUT_SECONDS = 120;
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    @Parameters(index = "0", description = "Node ID to drain")
    private String nodeId;

    @Option(names = "--wait", description = "Wait for drain to complete (DECOMMISSIONED)")
    private boolean waitForCompletion;

    @Option(names = "--timeout", description = "Timeout in seconds when waiting (default: 120)")
    private int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;

    @CommandLine.ParentCommand private ClusterCommand parent;

    @Override public Integer call() {
        return ClusterHttpClient.postToCluster("/api/node/drain/" + nodeId, "{}").flatMap(MAPPER::readTree)
                                              .fold(ClusterDrainCommand::onFailure, this::onDrainInitiated);
    }

    private int onDrainInitiated(JsonNode root) {
        var success = root.path("success").asBoolean(false);
        var state = root.path("state").asText("UNKNOWN");
        var message = root.path("message").asText("");
        if ( !success) {
        return handleDrainRejection(state, message);}
        System.out.printf("Drain initiated for node %s (state: %s)%n", nodeId, state);
        if ( waitForCompletion) {
        return pollUntilDecommissioned();}
        return ExitCode.SUCCESS;
    }

    private static int handleDrainRejection(String state, String message) {
        if ( state.contains("DRAINING") || state.contains("DECOMMISSIONED")) {
            System.out.printf("Node already %s: %s%n", state, message);
            return ExitCode.SUCCESS;
        }
        System.err.printf("Failed to drain: %s%n", message);
        return ExitCode.ERROR;
    }

    @SuppressWarnings("JBCT-SEQ-01")
    private int pollUntilDecommissioned() {
        System.out.printf("Waiting for node %s to reach DECOMMISSIONED (timeout: %ds)...%n", nodeId, timeoutSeconds);
        var deadline = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
        while ( System.currentTimeMillis() < deadline) {
            var stateResult = queryNodeLifecycleState();
            if ( "DECOMMISSIONED".equals(stateResult)) {
                System.out.printf("Node %s is now DECOMMISSIONED.%n", nodeId);
                return ExitCode.SUCCESS;
            }
            System.out.printf("  Current state: %s%n", stateResult);
            sleepQuietly();
        }
        System.err.printf("Timeout: node %s did not reach DECOMMISSIONED within %ds.%n", nodeId, timeoutSeconds);
        return ExitCode.TIMEOUT;
    }

    private String queryNodeLifecycleState() {
        return ClusterHttpClient.fetchFromCluster("/api/node/lifecycle/" + nodeId).flatMap(MAPPER::readTree)
                                                 .map(ClusterDrainCommand::extractState)
                                                 .or("UNKNOWN");
    }

    private static String extractState(JsonNode node) {
        return node.path("state").asText("UNKNOWN");
    }

    @SuppressWarnings("JBCT-EX-01")
    private static void sleepQuietly() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        }























        catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());
        return ExitCode.ERROR;
    }
}

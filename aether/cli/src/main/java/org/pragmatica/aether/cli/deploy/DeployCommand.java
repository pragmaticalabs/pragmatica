package org.pragmatica.aether.cli.deploy;

import org.pragmatica.aether.cli.AetherCli;
import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.aether.cli.OutputOptions;
import org.pragmatica.lang.Contract;

import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;


/// Unified deployment management command.
///
/// Supports immediate, canary, blue-green, and rolling deployment strategies
/// through a single entry point, plus lifecycle subcommands for managing
/// active deployments.
@Command(name = "deploy", description = "Deploy and manage deployments", subcommands = {DeployCommand.ListCommand.class, DeployCommand.StatusCommand.class, DeployCommand.PromoteCommand.class, DeployCommand.RollbackCommand.class, DeployCommand.CompleteCommand.class}) @Contract public class DeployCommand implements Callable<Integer> {
    @CommandLine.ParentCommand private AetherCli parent;

    @Parameters(index = "0", description = "Blueprint coordinates (group:artifact:version)", arity = "0..1") private String coordinates;

    @CommandLine.Option(names = "--canary", description = "Use canary deployment strategy") private boolean canary;

    @CommandLine.Option(names = "--blue-green", description = "Use blue-green deployment strategy") private boolean blueGreen;

    @CommandLine.Option(names = "--rolling", description = "Use rolling deployment strategy") private boolean rolling;

    @CommandLine.Option(names = "--traffic", description = "Initial canary traffic percentage (0-100)", defaultValue = "5") private int trafficPercent;

    @CommandLine.Option(names = {"-n", "--instances"}, description = "Number of instances", defaultValue = "1") private int instances;

    @CommandLine.Option(names = "--error-rate", description = "Max error rate threshold (0.0-1.0)", defaultValue = "0.01") private double errorRate;

    @CommandLine.Option(names = "--latency", description = "Max latency threshold in ms", defaultValue = "500") private long latencyMs;

    @CommandLine.Option(names = "--drain-timeout", description = "Blue-green drain timeout in ms", defaultValue = "30000") private long drainTimeoutMs;

    @CommandLine.Option(names = "--manual-approval", description = "Require manual approval for routing changes") private boolean manualApproval;

    @CommandLine.Option(names = "--wait", description = "Wait for deployment to complete") private boolean waitForCompletion;

    @CommandLine.Option(names = "--timeout", description = "Wait timeout in seconds", defaultValue = "300") private int timeoutSeconds;

    OutputOptions outputOptions() {
        return parent.outputOptions();
    }

    @Override public Integer call() {
        if (coordinates == null) {
            CommandLine.usage(this, System.out);
            return 0;
        }
        if (canary) {return deployWithStrategy("CANARY", buildCanaryBody());}
        if (blueGreen) {return deployWithStrategy("BLUE_GREEN", buildBlueGreenBody());}
        if (rolling) {return deployWithStrategy("ROLLING", buildRollingBody());}
        return deployImmediate();
    }

    @SuppressWarnings("JBCT-SEQ-01") private int deployImmediate() {
        var body = "{\"artifact\":\"" + coordinates + "\"}";
        var response = parent.postToNode("/api/blueprint/deploy", body);
        return OutputFormatter.printQuery(response, parent.outputOptions());
    }

    @SuppressWarnings("JBCT-SEQ-01") private int deployWithStrategy(String strategy, String strategyBody) {
        var body = "{\"blueprint\":\"" + coordinates + "\",\"strategy\":\"" + strategy + "\"," + strategyBody + "}";
        var response = parent.postToNode("/api/deploy", body);
        return OutputFormatter.printQuery(response, parent.outputOptions());
    }

    private String buildCanaryBody() {
        return "\"instances\":" + instances + ",\"canary\":{\"stages\":[{\"trafficPercent\":" + trafficPercent + ",\"observationMinutes\":10}]},\"thresholds\":{\"maxErrorRate\":" + errorRate + ",\"maxLatencyMs\":" + latencyMs + "}";
    }

    private String buildBlueGreenBody() {
        return "\"instances\":" + instances + ",\"blueGreen\":{\"drainTimeoutMs\":" + drainTimeoutMs + "},\"thresholds\":{\"maxErrorRate\":" + errorRate + ",\"maxLatencyMs\":" + latencyMs + "}";
    }

    private String buildRollingBody() {
        return "\"instances\":" + instances + ",\"rolling\":{\"requireManualApproval\":" + manualApproval + "},\"thresholds\":{\"maxErrorRate\":" + errorRate + ",\"maxLatencyMs\":" + latencyMs + "}";
    }

    @Command(name = "list", description = "List active deployments") static class ListCommand implements Callable<Integer> {
        @CommandLine.ParentCommand private DeployCommand deployParent;

        @Override public Integer call() {
            var response = deployParent.parent.fetchFromNode("/api/deploy");
            return OutputFormatter.printQuery(response, deployParent.parent.outputOptions());
        }
    }

    @Command(name = "status", description = "Show deployment status") static class StatusCommand implements Callable<Integer> {
        @CommandLine.ParentCommand private DeployCommand deployParent;

        @Parameters(index = "0", description = "Deployment ID") private String deploymentId;

        @Override public Integer call() {
            var response = deployParent.parent.fetchFromNode("/api/deploy/" + deploymentId);
            return OutputFormatter.printQuery(response, deployParent.parent.outputOptions());
        }
    }

    @Command(name = "promote", description = "Advance deployment (increase traffic or switch environment)") static class PromoteCommand implements Callable<Integer> {
        @CommandLine.ParentCommand private DeployCommand deployParent;

        @Parameters(index = "0", description = "Deployment ID") private String deploymentId;

        @CommandLine.Option(names = "--traffic", description = "New traffic percentage for canary (0-100)") private Integer trafficPercent;

        @Override public Integer call() {
            var body = trafficPercent != null
                      ? "{\"trafficPercent\":" + trafficPercent + "}"
                      : "{}";
            var response = deployParent.parent.postToNode("/api/deploy/" + deploymentId + "/promote", body);
            return OutputFormatter.printAction(response,
                                               deployParent.parent.outputOptions(),
                                               "Deployment " + deploymentId + " promoted");
        }
    }

    @Command(name = "rollback", description = "Rollback deployment") static class RollbackCommand implements Callable<Integer> {
        @CommandLine.ParentCommand private DeployCommand deployParent;

        @Parameters(index = "0", description = "Deployment ID") private String deploymentId;

        @Override public Integer call() {
            var response = deployParent.parent.postToNode("/api/deploy/" + deploymentId + "/rollback", "{}");
            return OutputFormatter.printAction(response,
                                               deployParent.parent.outputOptions(),
                                               "Deployment " + deploymentId + " rolled back");
        }
    }

    @Command(name = "complete", description = "Finalize deployment") static class CompleteCommand implements Callable<Integer> {
        @CommandLine.ParentCommand private DeployCommand deployParent;

        @Parameters(index = "0", description = "Deployment ID") private String deploymentId;

        @Override public Integer call() {
            var response = deployParent.parent.postToNode("/api/deploy/" + deploymentId + "/complete", "{}");
            return OutputFormatter.printAction(response,
                                               deployParent.parent.outputOptions(),
                                               "Deployment " + deploymentId + " completed");
        }
    }
}

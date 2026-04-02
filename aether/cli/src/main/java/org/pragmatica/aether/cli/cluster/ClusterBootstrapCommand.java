package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.config.cluster.ClusterConfigError;
import org.pragmatica.aether.config.cluster.ClusterConfigParser;
import org.pragmatica.aether.config.cluster.ClusterManagementConfig;
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

/// Bootstraps a new Aether cluster from a configuration file.
///
/// Reads `aether-cluster.toml`, validates the configuration, provisions cloud instances,
/// waits for health and quorum, stores the cluster config and API key, and registers
/// the cluster in the local registry.
///
/// Currently supports Hetzner Cloud only (Phase 1).
@Command(name = "bootstrap", description = "Bootstrap a new cluster from config file")
@SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01", "JBCT-SEQ-01"}) class ClusterBootstrapCommand implements Callable<Integer> {
    private static final int POLL_INTERVAL_MS = 2000;
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    @Parameters(index = "0", description = "Path to aether-cluster.toml config file")
    private Path configFile;

    @Option(names = "--yes", description = "Skip confirmation prompt")
    private boolean skipConfirmation;

    @Option(names = "--compose-only", description = "Generate docker-compose.yml and print to stdout, then exit")
    private boolean composeOnly;

    @Option(names = "--wait", description = "Wait for cluster to become healthy after bootstrap")
    private boolean waitForCompletion;

    @Option(names = "--timeout", description = "Timeout in seconds when waiting")
    private int timeoutSeconds = 0;

    @CommandLine.ParentCommand private ClusterCommand parent;

    @Override public Integer call() {
        if ( composeOnly) {
        return parseConfig().map(this::generateCompose)
                          .fold(ClusterBootstrapCommand::onFailure, _ -> ExitCode.SUCCESS);}
        return parseConfig().flatMap(this::confirmAndBootstrap)
                          .fold(ClusterBootstrapCommand::onFailure, this::onSuccess);
    }

    private String generateCompose(ClusterManagementConfig config) {
        var compose = DockerComposeGenerator.generate(config, "");
        System.out.print(compose);
        return compose;
    }

    private Result<ClusterManagementConfig> parseConfig() {
        System.out.printf("Reading config from %s...%n", configFile);
        return readFileContent(configFile).flatMap(ConfigReferenceResolver::resolveAll)
                              .flatMap(ClusterConfigParser::parse);
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<String> readFileContent(Path path) {
        return Result.lift(e -> new ClusterConfigError.ParseFailed("Failed to read file: " + path + " (" + e.getMessage() + ")"),
                           () -> Files.readString(path));
    }

    private Result<BootstrapOrchestrator.BootstrapResult> confirmAndBootstrap(ClusterManagementConfig config) {
        printPlan(config);
        if ( !skipConfirmation && !confirmBootstrap(config.cluster().name())) {
            System.out.println("Aborted.");
            return new AbortedError().result();
        }
        return BootstrapOrchestrator.bootstrap(config);
    }

    private static void printPlan(ClusterManagementConfig config) {
        var cluster = config.cluster();
        var deployment = config.deployment();
        System.out.println();
        System.out.println("Bootstrap plan:");
        System.out.printf("  Cluster:       %s%n", cluster.name());
        System.out.printf("  Version:       %s%n", cluster.version());
        System.out.printf("  Provider:      %s%n",
                          deployment.type().value());
        System.out.printf("  Instance type: %s%n",
                          deployment.instances().getOrDefault("core", "?"));
        System.out.printf("  Core nodes:    %d%n",
                          cluster.core().count());
        System.out.printf("  Runtime:       %s%n",
                          deployment.runtime().type()
                                            .name()
                                            .toLowerCase());
        deployment.runtime().image()
                          .onPresent(img -> System.out.printf("  Image:         %s%n", img));
        System.out.printf("  Ports:         cluster=%d, mgmt=%d, swim=%d%n",
                          deployment.ports().cluster(),
                          deployment.ports().management(),
                          deployment.ports().swim());
        System.out.println();
    }

    private static boolean confirmBootstrap(String clusterName) {
        System.out.printf("This will provision cloud instances for cluster '%s'.%n", clusterName);
        System.out.print("Continue? [y/N] ");
        System.out.flush();
        return readConfirmation();
    }

    @SuppressWarnings("JBCT-EX-01")
    private static boolean readConfirmation() {
        try {
            var bytes = System.in.readNBytes(256);
            var input = new String(bytes).trim()
                                         .toLowerCase();
            return "y".equals(input) || "yes".equals(input);
        }


        catch (Exception _) {
            return false;
        }
    }

    private int onSuccess(BootstrapOrchestrator.BootstrapResult result) {
        System.out.println("Step 12/12: Done.");
        if ( waitForCompletion) {
        return validateTimeoutAndPoll();}
        return ExitCode.SUCCESS;
    }

    private int validateTimeoutAndPoll() {
        if ( timeoutSeconds <= 0) {
            System.err.println("--timeout is required when using --wait");
            return ExitCode.ERROR;
        }
        return pollUntilHealthy();
    }

    @SuppressWarnings("JBCT-SEQ-01")
    private int pollUntilHealthy() {
        System.out.printf("Waiting for cluster to become healthy (timeout: %ds)...%n", timeoutSeconds);
        var deadline = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
        while ( System.currentTimeMillis() < deadline) {
            var status = queryClusterHealthStatus();
            if ( isHealthy(status)) {
                System.out.println("Cluster is healthy.");
                return ExitCode.SUCCESS;
            }
            System.out.printf("  Current status: %s%n", status);
            sleepQuietly();
        }
        System.err.printf("Timeout: cluster did not become healthy within %ds.%n", timeoutSeconds);
        return ExitCode.TIMEOUT;
    }

    private static boolean isHealthy(String status) {
        return "healthy".equalsIgnoreCase(status);
    }

    private static String queryClusterHealthStatus() {
        return ClusterHttpClient.fetchFromCluster("/api/health").flatMap(MAPPER::readTree)
                                                 .map(node -> node.path("status").asText("UNKNOWN"))
                                                 .or("UNKNOWN");
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
        if ( cause instanceof AbortedError) {
        return ExitCode.SUCCESS;}
        System.err.println("Error: " + cause.message());
        return ExitCode.ERROR;
    }

    /// Sentinel error for user-aborted bootstrap.
    private record AbortedError() implements Cause {
        @Override public String message() {
            return "Bootstrap aborted by user.";
        }
    }
}

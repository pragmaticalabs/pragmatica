package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.config.cluster.ClusterConfigParser;
import org.pragmatica.aether.config.cluster.ClusterManagementConfig;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import java.nio.file.Path;
import java.util.concurrent.Callable;

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
@SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01", "JBCT-SEQ-01"})
class ClusterBootstrapCommand implements Callable<Integer> {
    @Parameters(index = "0", description = "Path to aether-cluster.toml config file")
    private Path configFile;

    @Option(names = "--yes", description = "Skip confirmation prompt")
    private boolean skipConfirmation;

    @Override
    public Integer call() {
        return parseConfig().flatMap(this::confirmAndBootstrap)
                          .fold(ClusterBootstrapCommand::onFailure, ClusterBootstrapCommand::onSuccess);
    }

    private Result<ClusterManagementConfig> parseConfig() {
        System.out.printf("Reading config from %s...%n", configFile);
        return ClusterConfigParser.parseFile(configFile);
    }

    private Result<BootstrapOrchestrator.BootstrapResult> confirmAndBootstrap(ClusterManagementConfig config) {
        printPlan(config);
        if (!skipConfirmation && !confirmBootstrap(config.cluster()
                                                         .name())) {
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
                          deployment.type()
                                    .value());
        System.out.printf("  Instance type: %s%n",
                          deployment.instances()
                                    .getOrDefault("core", "?"));
        System.out.printf("  Core nodes:    %d%n",
                          cluster.core()
                                 .count());
        System.out.printf("  Runtime:       %s%n",
                          deployment.runtime()
                                    .type()
                                    .name()
                                    .toLowerCase());
        deployment.runtime()
                  .image()
                  .onPresent(img -> System.out.printf("  Image:         %s%n", img));
        System.out.printf("  Ports:         cluster=%d, mgmt=%d, swim=%d%n",
                          deployment.ports()
                                    .cluster(),
                          deployment.ports()
                                    .management(),
                          deployment.ports()
                                    .swim());
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
        try{
            var bytes = System.in.readNBytes(256);
            var input = new String(bytes).trim()
                                         .toLowerCase();
            return "y".equals(input) || "yes".equals(input);
        } catch (Exception _) {
            return false;
        }
    }

    private static Integer onSuccess(BootstrapOrchestrator.BootstrapResult result) {
        System.out.println("Step 12/12: Done.");
        return 0;
    }

    private static Integer onFailure(Cause cause) {
        if (cause instanceof AbortedError) {
            return 0;
        }
        System.err.println("Error: " + cause.message());
        return 1;
    }

    /// Sentinel error for user-aborted bootstrap.
    private record AbortedError() implements Cause {
        @Override
        public String message() {
            return "Bootstrap aborted by user.";
        }
    }
}

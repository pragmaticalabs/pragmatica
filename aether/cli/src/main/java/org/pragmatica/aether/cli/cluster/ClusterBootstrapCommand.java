// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.ClusterConfigError;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.RoleSubTable;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_HEALTH;


@Command(name = "bootstrap", description = {"Bootstrap a new cluster from config file.", "", "Required when the cluster has no static seed members (cluster.static=0) and the cloud", "minimum is at least the quorum size (cloud.min >= quorum): with no static seeds the", "nodes cannot self-form a quorum, so an explicit operator-driven bootstrap is required."}) @SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01", "JBCT-SEQ-01"}) class ClusterBootstrapCommand implements Callable<Integer> {
    private static final int POLL_INTERVAL_MS = 2000;

    private static final Pattern CLUSTER_NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9-]{0,62}$");

    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    @Parameters(index = "0", description = "Path to aether-cluster.toml config file") private Path configFile;

    @Option(names = "--yes", description = "Skip confirmation prompt") private boolean skipConfirmation;

    @Option(names = "--resume", description = "Resume a failed bootstrap") private boolean resume;

    @Option(names = "--full-check", description = "Run full network pre-flight checks before bootstrap") private boolean fullCheck;

    @Option(names = "--wait", description = "Wait for cluster to become healthy after bootstrap") private boolean waitForCompletion;

    @Option(names = "--timeout", description = "Timeout in seconds when waiting") private int timeoutSeconds = 0;

    @Option(names = "--cluster", description = "Override [cluster].name from the TOML (CLI > TOML > default)") private String clusterNameOverride;

    @Option(names = "--ssh-public-key", description = "Path to operator SSH public key (e.g. ~/.ssh/id_ed25519.pub). " + "Overrides [infrastructure.ssh].public_key_file in TOML and the AETHER_SSH_KEY env fallback. " + "Required for cloud sources to be SSH-reachable.") private String sshPublicKeyPath;

    @CommandLine.ParentCommand private ClusterCommand parent;

    @Override public Integer call() {
        if (!isOverrideAcceptable()) {
            System.err.println("Invalid --cluster value: '" + clusterNameOverride + "': must match ^[a-z][a-z0-9-]{0,62}$");
            return ExitCode.USAGE;
        }
        return parseConfig().map(this::applyClusterNameOverride)
                          .flatMap(this::confirmAndBootstrap)
                          .fold(ClusterBootstrapCommand::onFailure, this::onSuccess);
    }

    private boolean isOverrideAcceptable() {
        if (clusterNameOverride == null || clusterNameOverride.isBlank()) {return true;}
        return CLUSTER_NAME_PATTERN.matcher(clusterNameOverride).matches();
    }

    private ClusterBootstrapConfig applyClusterNameOverride(ClusterBootstrapConfig parsedConfig) {
        if (clusterNameOverride == null || clusterNameOverride.isBlank()) {return parsedConfig;}
        return parsedConfig.withClusterName(clusterNameOverride);
    }

    private Result<ClusterBootstrapConfig> parseConfig() {
        System.out.printf("Reading config from %s...%n", configFile);
        return readFileContent(configFile).flatMap(ConfigReferenceResolver::resolveAll)
                              .flatMap(ClusterBootstrapConfigParser::parse);
    }

    @SuppressWarnings("JBCT-EX-01") private static Result<String> readFileContent(Path path) {
        return Result.lift(e -> new ClusterConfigError.ParseFailed("Failed to read file: " + path + " (" + e.getMessage() + ")"),
                           () -> Files.readString(path));
    }

    private Result<ClusterBootstrapOrchestrator.BootstrapResult> confirmAndBootstrap(ClusterBootstrapConfig config) {
        printPlan(config);
        if (!skipConfirmation && !confirmBootstrap(config.cluster().name())) {
            System.out.println("Aborted.");
            return new AbortedError().result();
        }
        return SshKeyResolver.resolveOrFailIfCloud(config,
                                                   option(sshPublicKeyPath))
        .flatMap(keys -> ClusterBootstrapOrchestrator.bootstrap(config, resume, fullCheck, keys));
    }

    private static void printPlan(ClusterBootstrapConfig config) {
        var cluster = config.cluster();
        var ports = config.operations().ports();
        System.out.println();
        System.out.println("Bootstrap plan:");
        System.out.printf("  Cluster:     %s%n", cluster.name());
        System.out.printf("  Version:     %s%n", cluster.version());
        System.out.printf("  Sources:     %d%n",
                          config.sources().size());
        config.sources().forEach(ClusterBootstrapCommand::printSourceEntry);
        System.out.printf("  Core nodes:  %d (derived)%n", config.derivedCoreCount());
        System.out.printf("  Ports:       cluster=%d, mgmt=%d, app=%d%n",
                          ports.cluster(),
                          ports.management(),
                          ports.appHttp());
        System.out.println();
    }

    private static void printSourceEntry(String name, SourceProfile source) {
        System.out.printf("    %s (%s)%n",
                          name,
                          source.type().value());
        source.roles().forEach(ClusterBootstrapCommand::printRoleEntry);
    }

    private static void printRoleEntry(NodeRole role, RoleSubTable sub) {
        var size = sub.count().or(0) + sub.hosts().map(List::size)
                                                .or(0);
        System.out.printf("      %s: %d nodes%n", role.value(), size);
    }

    private static boolean confirmBootstrap(String clusterName) {
        System.out.printf("This will provision cloud instances for cluster '%s'.%n", clusterName);
        System.out.print("Continue? [y/N] ");
        System.out.flush();
        return readConfirmation();
    }

    @SuppressWarnings("JBCT-EX-01") private static boolean readConfirmation() {
        try {
            var bytes = System.in.readNBytes(256);
            var input = new String(bytes).trim().toLowerCase();
            return "y".equals(input) || "yes".equals(input);
        } catch (Exception _) {
            return false;
        }
    }

    private int onSuccess(ClusterBootstrapOrchestrator.BootstrapResult result) {
        System.out.println("Step 12/12: Done.");
        if (waitForCompletion) {return validateTimeoutAndPoll();}
        return ExitCode.SUCCESS;
    }

    private int validateTimeoutAndPoll() {
        if (timeoutSeconds <= 0) {
            System.err.println("--timeout is required when using --wait");
            return ExitCode.ERROR;
        }
        return pollUntilHealthy();
    }

    @SuppressWarnings("JBCT-SEQ-01") private int pollUntilHealthy() {
        System.out.printf("Waiting for cluster to become healthy (timeout: %ds)...%n", timeoutSeconds);
        var deadline = System.currentTimeMillis() + (long) timeoutSeconds * 1000;
        while (System.currentTimeMillis() <deadline) {
            var status = queryClusterHealthStatus();
            if (isHealthy(status)) {
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
        return ClusterHttpClient.fetch(CLUSTER_HEALTH).flatMap(MAPPER::readTree)
                                      .map(node -> node.path("status").asText("UNKNOWN"))
                                      .or("UNKNOWN");
    }

    @SuppressWarnings("JBCT-EX-01") private static void sleepQuietly() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    private static int onFailure(Cause cause) {
        if (cause instanceof AbortedError) {return ExitCode.SUCCESS;}
        System.err.println("Error: " + cause.message());
        return ExitCode.ERROR;
    }

    private record AbortedError() implements Cause {
        @Override public String message() {
            return "Bootstrap aborted by user.";
        }
    }
}

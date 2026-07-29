// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.ClusterConfigError;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.RoleSubTable;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import static org.pragmatica.lang.Option.option;


@Command(name = "bootstrap", description = {"Bootstrap a new cluster from config file.", "", "Required when the cluster has no static seed members (cluster.static=0) and the cloud", "minimum is at least the quorum size (cloud.min >= quorum): with no static seeds the", "nodes cannot self-form a quorum, so an explicit operator-driven bootstrap is required."})
@SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01", "JBCT-SEQ-01"})
class ClusterBootstrapCommand implements Callable<Integer> {
    private static final int POLL_INTERVAL_MS = 2000;
    private static final Pattern CLUSTER_NAME_PATTERN = Pattern.compile("^[a-z]([a-z0-9-]{0,61}[a-z0-9])?$");
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();
    private static final String READY_PHASE = "CONVERGED";
    private static final String CLUSTER_PHASE_FIELD = "state";
    private static final String UNKNOWN = "UNKNOWN";

    @Parameters(index = "0", description = "Path to aether-cluster.toml config file")
    private Path configFile;

    @Option(names = "--yes", description = "Skip confirmation prompt")
    private boolean skipConfirmation;

    @Option(names = "--resume", description = "Resume a failed bootstrap")
    private boolean resume;

    @Option(names = "--full-check", description = "Run full network pre-flight checks before bootstrap")
    private boolean fullCheck;

    @Option(names = "--wait", description = "Wait for cluster to become healthy after bootstrap")
    private boolean waitForCompletion;

    @Option(names = "--timeout", description = "Timeout in seconds when waiting", defaultValue = "300")
    private int timeoutSeconds;

    @Option(names = "--cluster", description = "Override [cluster].name from the TOML (CLI > TOML > default)")
    private String clusterNameOverride;

    @Option(names = "--ssh-public-key", description = "Path to operator SSH public key (e.g. ~/.ssh/id_ed25519.pub). "
                                                    + "Overrides [infrastructure.ssh].public_key_file in TOML and the AETHER_SSH_KEY env fallback. "
                                                    + "Required for cloud sources to be SSH-reachable.")
    private String sshPublicKeyPath;

    @Option(names = "--keep-on-failure", description = "Skip automatic cleanup of provisioned VMs and SSH keys when bootstrap fails. "
                                                     + "Symmetric with 'aether cluster destroy --keep-resources'. "
                                                     + "Use to preserve resources for SSH-inspection diagnosis; clean up later with "
                                                     + "'aether cluster destroy --cluster <name> --yes'.")
    private boolean keepOnFailure;

    @CommandLine.ParentCommand
    private ClusterCommand parent;

    @Override
    public Integer call() {
        if (!isOverrideAcceptable()) {
            System.err.println("Invalid --cluster value: '" + clusterNameOverride
                              + "': must match ^[a-z]([a-z0-9-]{0,61}[a-z0-9])?$");

            return ExitCode.USAGE;
        }

        return parseConfig().flatMap(this::applyClusterNameOverride)
                          .flatMap(this::confirmAndBootstrap)
                          .fold(ClusterBootstrapCommand::onFailure, this::onSuccess);
    }

    private boolean isOverrideAcceptable() {
        if (clusterNameOverride == null || clusterNameOverride.isBlank()) {
            return true;
        }

        return CLUSTER_NAME_PATTERN.matcher(clusterNameOverride).matches();
    }

    private Result<ParsedConfig> applyClusterNameOverride(ParsedConfig parsed) {
        if (clusterNameOverride == null || clusterNameOverride.isBlank()) {
            return Result.success(parsed);
        }

        return parsed.config()
                     .withClusterName(clusterNameOverride)
                     .map(updated -> new ParsedConfig(updated,
                                                      parsed.rawToml()));
    }

    private Result<ParsedConfig> parseConfig() {
        System.out.printf("Reading config from %s...%n", configFile);

        return readFileContent(configFile).flatMap(raw -> parseFromRaw(raw, configFile));
    }

    private static Result<ParsedConfig> parseFromRaw(String raw, Path source) {
        return ConfigReferenceResolver.resolveAll(raw)
                                      .flatMap(ClusterBootstrapConfigParser::parse)
                                      .map(config -> new ParsedConfig(config, raw));
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<String> readFileContent(Path path) {
        return Result.lift(e -> new ClusterConfigError.ParseFailed("Failed to read file: " + path
                                                                  + " (" + e.getMessage()
                                                                  + ")"),
                           () -> Files.readString(path));
    }

    private Result<ClusterBootstrapOrchestrator.BootstrapResult> confirmAndBootstrap(ParsedConfig parsed) {
        var config = parsed.config();

        printPlan(config);
        if (!skipConfirmation && !confirmBootstrap(config.cluster().name())) {
            System.out.println("Aborted.");

            return AbortedError.INSTANCE.result();
        }

        return SshKeyResolver.resolveOrFailIfCloud(config, option(sshPublicKeyPath)).flatMap(keys -> ClusterBootstrapOrchestrator.bootstrap(config,
                                                                                                                                            resume,
                                                                                                                                            fullCheck,
                                                                                                                                            keys,
                                                                                                                                            keepOnFailure,
                                                                                                                                            parsed.rawToml()));
    }

    record ParsedConfig(ClusterBootstrapConfig config, String rawToml) {}

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
        var size = sub.count().or(0) + sub.hosts().map(List::size).or(0);

        System.out.printf("      %s: %d nodes%n", role.value(), size);
    }

    private static boolean confirmBootstrap(String clusterName) {
        System.out.printf("This will provision cloud instances for cluster '%s'.%n", clusterName);

        return new org.pragmatica.aether.cli.Prompt().confirm("Continue?", false);
    }

    private int onSuccess(ClusterBootstrapOrchestrator.BootstrapResult result) {
        System.out.println("Step 12/12: Done.");
        if (waitForCompletion) {
            return pollUntilHealthy(result);
        }

        return ExitCode.SUCCESS;
    }

    private int pollUntilHealthy(ClusterBootstrapOrchestrator.BootstrapResult result) {
        applyEndpointOverride(result);
        applyApiKeyOverride(result);

        return waitForClusterPhase(ClusterBootstrapCommand::fetchStatusJson,
                                   timeoutSeconds,
                                   POLL_INTERVAL_MS,
                                   System.out,
                                   System.err);
    }

    private static void applyEndpointOverride(ClusterBootstrapOrchestrator.BootstrapResult result) {
        if (result.endpoint() == null || result.endpoint().isBlank()) {
            return;
        }

        ClusterHttpClient.setEndpointOverride(result.endpoint());
    }

    private static void applyApiKeyOverride(ClusterBootstrapOrchestrator.BootstrapResult result) {
        if (result.apiKey() == null || result.apiKey().isBlank()) {
            return;
        }

        ClusterHttpClient.setApiKeyOverride(result.apiKey());
    }

    @SuppressWarnings("JBCT-SEQ-01")
    static int waitForClusterPhase(Function<ManagementRoute, Result<String>> fetcher,
                                   int timeoutSec,
                                   int pollIntervalMs,
                                   PrintStream out,
                                   PrintStream err) {
        out.printf("Waiting for cluster to become healthy (timeout: %ds)...%n", timeoutSec);
        var deadline = System.currentTimeMillis() + (long) timeoutSec * 1000;

        while (System.currentTimeMillis() < deadline) {
            var phase = queryClusterPhase(fetcher);

            if (isReady(phase)) {
                out.println("Cluster is healthy.");

                return ExitCode.SUCCESS;
            }

            out.printf("  Current status: %s%n", phase);
            sleepQuietly(pollIntervalMs);
        }

        err.printf("Timeout: cluster did not become healthy within %ds.%n", timeoutSec);

        return ExitCode.TIMEOUT;
    }

    static boolean isReady(String phase) {
        return READY_PHASE.equalsIgnoreCase(phase);
    }

    static String queryClusterPhase(Function<ManagementRoute, Result<String>> fetcher) {
        return fetcher.apply(ManagementRoute.CLUSTER_STATUS)
                      .flatMap(MAPPER::readTree)
                      .map(node -> node.path(CLUSTER_PHASE_FIELD)
                                       .asText(UNKNOWN))
                      .or(UNKNOWN);
    }

    private static Result<String> fetchStatusJson(ManagementRoute route) {
        return ClusterHttpClient.fetch(route);
    }

    @SuppressWarnings("JBCT-EX-01")
    private static void sleepQuietly(int intervalMs) {
        try {
            Thread.sleep(intervalMs);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    /// #521 — an aborted bootstrap exits NON-zero. Exiting 0 told every wrapper script that a cluster had
    /// been provisioned when nothing had been, and it is indistinguishable from success in CI: the
    /// interactive `Continue? [y/N]` prompt reads EOF in a non-interactive shell, so the abort is exactly
    /// the case a script is most likely to hit and least likely to notice.
    private static int onFailure(Cause cause) {
        if (cause instanceof AbortedError) {
            return ExitCode.ERROR;
        }

        System.err.println("Error: " + cause.message());
        if (cause instanceof ClusterBootstrapOrchestrator.BootstrapError.BootstrapFailedWithOrphans) {
            return ExitCode.CLEANUP_FAILED;
        }

        return ExitCode.ERROR;
    }

    private enum AbortedError implements Cause {
        INSTANCE;
        @Override
        public String message() {
            return "Bootstrap aborted by user.";
        }
    }
}

// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.concurrent.Callable;
import java.util.regex.Pattern;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;

import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_CONFIG_GET;
import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_UPGRADE;
import static org.pragmatica.aether.management.route.ManagementRoute.UPGRADE_STATUS;


@Command(name = "upgrade", description = "Upgrade cluster to a target version: every node is replaced, one at a time, by a node running it (see also upgrade-status, upgrade-pause, upgrade-resume, upgrade-abort)")
@SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01", "JBCT-SEQ-01"})
class ClusterUpgradeCommand implements Callable<Integer> {
    private static final Pattern VERSION_PATTERN = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    @Option(names = "--version", required = true, description = "Target version (e.g., 0.26.0)")
    private String targetVersion;

    @Option(names = "--wait", description = "Wait until the rolling upgrade run ends (exit 0 completed; 1 aborted or paused, 2 timed out)")
    private boolean wait;

    @Option(names = "--wait-timeout-minutes", description = "With --wait: how long to wait (default 180)", defaultValue = "180")
    private long waitTimeoutMinutes;

    @CommandLine.ParentCommand
    private ClusterCommand parent;

    @Mixin
    ClusterTargetMixin clusterTarget = new ClusterTargetMixin();

    @Override
    public Integer call() {
        return clusterTarget.applyOverrides()
                            .flatMap(_ -> validateVersion())
                            .flatMap(this::fetchCurrentConfig)
                            .flatMap(this::initiateUpgrade)
                            .fold(ClusterUpgradeCommand::onFailure, this::onInitiated);
    }

    private Result<String> validateVersion() {
        if (!VERSION_PATTERN.matcher(targetVersion).matches()) {
            return new UpgradeError.InvalidVersion(targetVersion).result();
        }

        return Result.success(targetVersion);
    }

    private Result<JsonNode> fetchCurrentConfig(String version) {
        return ClusterHttpClient.fetch(CLUSTER_CONFIG_GET).flatMap(MAPPER::readTree);
    }

    private Result<String> initiateUpgrade(JsonNode config) {
        var currentVersion = config.path("version").asText("unknown");

        if (targetVersion.equals(currentVersion) && !runIsOwed()) {
            return new UpgradeError.AlreadyAtVersion(targetVersion).result();
        }

        return ClusterHttpClient.post(CLUSTER_UPGRADE,
                                      buildUpgradeJson(targetVersion,
                                                       config.path("configVersion").asLong(0)));
    }

    /// The stored version already is the target, but the run towards it may not have finished (it is live, or a node was left behind):
    /// then the request goes to the server, which attaches to a live run or starts the one that is owed.
    private boolean runIsOwed() {
        return ClusterHttpClient.fetch(UPGRADE_STATUS)
                                .flatMap(UpgradeRunWait.MAPPER::readTree)
                                .map(status -> status.path("present")
                                                     .asBoolean(false) && (status.path("state")
                                                                                 .asText("")
                                                                                 .equals("RUNNING") || status.path("state")
                                                                                                             .asText("")
                                                                                                             .equals("PAUSED")))
                                .or(false);
    }

    /// Field names here MUST match `ManagementApiResponses.UpgradeRequest`; the CLI cannot depend on
    /// `aether/node`, so the contract is spelled twice and `ClusterUpgradeCommandTest` /
    /// `UpgradeRequestContractTest` pin the two spellings to the same names. `expectedVersion` is the
    /// `configVersion` read from the same `GET /cluster/config` that supplied the current version (#1424).
    static String buildUpgradeJson(String targetVersion, long expectedVersion) {
        return "{\"targetVersion\":\"" + targetVersion + "\",\"expectedVersion\":" + expectedVersion + "}";
    }

    private int onInitiated(String json) {
        var printed = OutputFormatter.printAction(json, parent.outputOptions(), "Upgrade initiated.");

        return wait && printed == ExitCode.SUCCESS
               ? awaitRun()
               : printed;
    }

    private int awaitRun() {
        var outcome = UpgradeRunWait.await(() -> ClusterHttpClient.fetch(UPGRADE_STATUS),
                                           System::currentTimeMillis,
                                           ClusterUpgradeCommand::sleep,
                                           waitTimeoutMinutes * 60_000L,
                                           5_000L,
                                           System.out::println);

        return exitFor(outcome);
    }

    static int exitFor(UpgradeRunWait outcome) {
        return switch (outcome) {
            case UpgradeRunWait.Completed completed -> {
                System.out.println("Upgrade to " + completed.targetVersion() + " completed: every node reports it.");
                yield ExitCode.SUCCESS;
            }
            case UpgradeRunWait.Aborted aborted -> {
                System.err.println("Upgrade aborted: " + aborted.reason());
                yield ExitCode.ERROR;
            }
            case UpgradeRunWait.Paused paused -> {
                System.err.println("Upgrade paused, it needs an operator: " + paused.reason() + "\nFix the cause, then `aether cluster upgrade-resume` (or `upgrade-abort`).");
                yield ExitCode.ERROR;
            }
            case UpgradeRunWait.NoRun _ -> {
                System.err.println("Error: the cluster reports no upgrade run.");
                yield ExitCode.ERROR;
            }
            case UpgradeRunWait.TimedOut timedOut -> {
                System.err.println("Timed out waiting for the upgrade (last seen: " + timedOut.lastSeen() + "). The run continues on the cluster: `aether cluster upgrade-status`.");
                yield ExitCode.TIMEOUT;
            }
        };
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int onFailure(Cause cause) {
        if (cause instanceof UpgradeError.AlreadyAtVersion alreadyAt) {
            System.out.printf("Already at version %s. No upgrade needed.%n", alreadyAt.version());

            return ExitCode.SUCCESS;
        }

        System.err.println("Error: " + cause.message());

        return ExitCode.ERROR;
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

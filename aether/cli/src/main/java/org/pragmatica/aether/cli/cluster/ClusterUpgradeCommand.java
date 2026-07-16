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


@Command(name = "upgrade", description = "Upgrade cluster to a target version")
@SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01", "JBCT-SEQ-01"})
class ClusterUpgradeCommand implements Callable<Integer> {
    private static final Pattern VERSION_PATTERN = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    @Option(names = "--version", required = true, description = "Target version (e.g., 0.26.0)")
    private String targetVersion;

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
                            .fold(ClusterUpgradeCommand::onFailure, this::onSuccess);
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

        if (targetVersion.equals(currentVersion)) {
            return new UpgradeError.AlreadyAtVersion(targetVersion).result();
        }

        var jsonBody = "{\"targetVersion\":\"" + targetVersion + "\"}";

        return ClusterHttpClient.post(CLUSTER_UPGRADE, jsonBody);
    }

    private int onSuccess(String json) {
        return OutputFormatter.printAction(json, parent.outputOptions(), "Upgrade initiated.");
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

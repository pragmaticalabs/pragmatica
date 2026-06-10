// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.OutputFormat;
import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.aether.cli.OutputOptions;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;

import java.time.Instant;
import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import tools.jackson.databind.JsonNode;

import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_CONFIG_GET;
import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_STATUS;


@Command(name = "export", description = "Export cluster configuration (default: TOML body; --format json emits the JSON envelope)")
@SuppressWarnings("JBCT-RET-01")
class ClusterExportCommand implements Callable<Integer> {
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    @Option(names = "--with-status", description = "Include runtime state as comments (TOML output only)")
    private boolean withStatus;

    @CommandLine.ParentCommand
    private ClusterCommand parent;

    @Mixin
    ClusterTargetMixin clusterTarget = new ClusterTargetMixin();

    @Override
    public Integer call() {
        return clusterTarget.applyOverrides()
                            .flatMap(_ -> ClusterHttpClient.fetch(CLUSTER_CONFIG_GET))
                            .fold(ClusterExportCommand::onFailure, this::dispatchByFormat);
    }

    /// Format dispatcher — `--format json` (or `--field <path>`) routes the raw response
    /// through `OutputFormatter.printQuery` so the JSON envelope (with `tomlContent`,
    /// `clusterName`, `configVersion`, `coreCount`) is emitted unchanged. Any other format
    /// preserves the legacy behaviour of emitting the embedded TOML body.
    private int dispatchByFormat(String response) {
        return shouldEmitTomlBody(parent.outputOptions())
               ? renderTomlBody(response, withStatus)
               : OutputFormatter.printQuery(response, parent.outputOptions());
    }

    /// Visible for testing — predicate isolating the format routing decision.
    static boolean shouldEmitTomlBody(OutputOptions options) {
        return options.format() == OutputFormat.TABLE;
    }

    /// Visible for testing — renders the embedded TOML body (with optional
    /// status header) without touching network state.
    static int renderTomlBody(String response, boolean withStatus) {
        return MAPPER.readTree(response).fold(ClusterExportCommand::onFailure, root -> onSuccess(root, withStatus));
    }

    private static int onSuccess(JsonNode root, boolean withStatus) {
        var tomlContent = root.path("tomlContent").asText("");

        if (withStatus) {
            printStatusHeader(root);
        }

        System.out.println(tomlContent);

        return ExitCode.SUCCESS;
    }

    private static void printStatusHeader(JsonNode root) {
        var clusterName = root.path("clusterName").asText("unknown");
        var configVersion = root.path("configVersion").asText("?");
        var coreCount = root.path("coreCount").asText("?");

        System.out.printf("# --- Exported from cluster \"%s\" at %s ---%n", clusterName, Instant.now());
        System.out.printf("# Config version: %s%n", configVersion);
        System.out.printf("# Core count: %s%n", coreCount);
        enrichWithLiveStatus();
        System.out.println();
    }

    @Contract
    private static void enrichWithLiveStatus() {
        ClusterHttpClient.fetch(CLUSTER_STATUS).flatMap(MAPPER::readTree).onSuccess(ClusterExportCommand::printLiveStatusComments);
    }

    private static void printLiveStatusComments(JsonNode status) {
        var actualCount = status.path("actualCoreCount").asText("?");
        var desiredCount = status.path("desiredCoreCount").asText("?");
        var leader = status.path("leaderId").asText("none");
        var certExpires = status.path("certificateExpiresAt").asText("N/A");

        System.out.printf("# Actual core nodes: %s/%s%n", actualCount, desiredCount);
        System.out.printf("# Leader: %s%n", leader);
        System.out.printf("# Certificate expires: %s%n", certExpires);
    }

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());

        return ExitCode.ERROR;
    }
}

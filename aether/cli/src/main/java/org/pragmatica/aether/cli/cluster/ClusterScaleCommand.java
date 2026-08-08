// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.concurrent.Callable;

import org.pragmatica.aether.cli.DestructiveAction;
import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_CONFIG_GET;
import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_SCALE;


/// Scale one (source, role) of the cluster topology (RFC-0017 C1).
///
/// `--source` is optional: the server infers it when exactly one source declares the role, and
/// refuses — naming the candidates — when several do. That refusal is the point. The former
/// cluster-wide `--core N` could not say which source absorbed the change and answered the
/// ambiguity by overwriting a single number.
///
/// Quorum arithmetic is deliberately NOT checked here. A per-source count is not the cluster
/// total: scaling one core source to 1 is valid when another source carries 2. Only the server
/// holds the whole topology, so only the server can do that arithmetic honestly.
@Command(name = "scale", description = "Scale one source and role of the cluster topology")
@SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01", "JBCT-SEQ-01"})
class ClusterScaleCommand implements Callable<Integer> {
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();
    private static final String DEFAULT_ROLE = "core";

    @Option(names = "--source", description = "Source name; inferred when exactly one source declares the role")
    private String sourceName = "";

    @Option(names = "--role", description = "Node role: core, worker or spot (default: core)")
    private String roleName = DEFAULT_ROLE;

    @Option(names = "--count", description = "Target node count for this source and role")
    private int count;

    @Option(names = {"--yes", "--force"}, description = "Skip interactive confirmation")
    private boolean skipConfirmation;

    @CommandLine.ParentCommand
    private ClusterCommand parent;

    @Mixin
    ClusterTargetMixin clusterTarget = new ClusterTargetMixin();

    @Override
    public Integer call() {
        return clusterTarget.applyOverrides()
                            .flatMap(_ -> requireCount())
                            .flatMap(this::confirmAndScale)
                            .fold(this::onFailure, this::onSuccess);
    }

    private Result<Integer> requireCount() {
        return count < 1
               ? new ScaleError.MinimumCount(count).result()
               : Result.success(count);
    }

    private Result<String> confirmAndScale(int targetCount) {
        if (!confirmScale(targetCount)) {
            return ScaleError.Aborted.INSTANCE.result();
        }

        return fetchConfigVersion().flatMap(version -> sendScaleRequest(version, targetCount));
    }

    private boolean confirmScale(int targetCount) {
        return DestructiveAction.destructiveAction().confirm(skipConfirmation,
                                                             "This will scale " + describeTarget()
                                                            + " to " + targetCount
                                                            + " (a scale-down terminates nodes).");
    }

    private String describeTarget() {
        return sourceName.isBlank()
               ? roleName + " nodes"
               : roleName + " nodes in source '" + sourceName + "'";
    }

    private Result<Long> fetchConfigVersion() {
        return ClusterHttpClient.fetch(CLUSTER_CONFIG_GET).flatMap(ClusterScaleCommand::extractConfigVersion);
    }

    private Result<String> sendScaleRequest(long expectedVersion, int targetCount) {
        return ClusterHttpClient.post(CLUSTER_SCALE, buildScaleJson(sourceName, roleName, targetCount, expectedVersion));
    }

    /// Field names here MUST match `ManagementApiResponses.ScaleRequest`. They did not: this command
    /// sent `count`/`role`/`source` while the record read a lone `coreCount`, so every scale request
    /// arrived without a usable count and no test crossed the boundary to notice. The CLI cannot
    /// depend on `aether/node`, so the contract is spelled twice; `ClusterScaleCommandTest` and
    /// `ScaleRequestContractTest` pin the two spellings to the same field names.
    static String buildScaleJson(String source, String role, int targetCount, long expectedVersion) {
        return "{\"source\":\"" + source
             + "\",\"role\":\"" + role
             + "\",\"count\":" + targetCount
             + ",\"expectedVersion\":" + expectedVersion
             + "}";
    }

    private int onSuccess(String json) {
        return OutputFormatter.printAction(json, parent.outputOptions(), "Scale successful.");
    }

    private static Result<Long> extractConfigVersion(String responseJson) {
        return MAPPER.readTree(responseJson).map(node -> node.path("configVersion")
                                                             .asLong(0));
    }

    private int onFailure(Cause cause) {
        if (cause instanceof ScaleError.Aborted) {
            System.out.println("Aborted.");

            return ExitCode.SUCCESS;
        }

        return OutputFormatter.printError(cause, parent.outputOptions());
    }

    sealed interface ScaleError extends Cause {
        record MinimumCount(int requested) implements ScaleError {
            @Override
            public String message() {
                return "--count must be at least 1, got " + requested;
            }
        }

        enum Aborted implements ScaleError {
            INSTANCE;
            @Override
            public String message() {
                return "Scale aborted by operator";
            }
        }
    }
}

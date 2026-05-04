// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.cli.ExitCode;
import org.pragmatica.aether.cli.OutputFormatter;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import java.util.concurrent.Callable;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_CONFIG_GET;
import static org.pragmatica.aether.management.route.ManagementRoute.CLUSTER_SCALE;


@Command(name = "scale", description = "Scale cluster node count") @SuppressWarnings({"JBCT-RET-01", "JBCT-PAT-01", "JBCT-SEQ-01"}) class ClusterScaleCommand implements Callable<Integer> {
    private static final int MINIMUM_CORE_COUNT = 3;

    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    @Parameters(index = "0", description = "Source name", defaultValue = "") private String sourceName;

    @Parameters(index = "1", description = "Role (core, worker, spot)", defaultValue = "core") private String roleName;

    @Option(names = "--count", description = "Target node count") private int count;

    @Option(names = "--core", description = "Legacy shortcut: scale core nodes across all sources") private int coreCount;

    @CommandLine.ParentCommand private ClusterCommand parent;

    @Mixin ClusterTargetMixin clusterTarget = new ClusterTargetMixin();

    @Override public Integer call() {
        return clusterTarget.applyOverrides().flatMap(_ -> resolveEffective())
                                           .flatMap(pair -> validateCount(pair.count(),
                                                                          pair.role()).flatMap(this::fetchConfigVersion)
                                                                         .flatMap(version -> sendScaleRequest(version,
                                                                                                              pair.count(),
                                                                                                              pair.role())))
                                           .fold(ClusterScaleCommand::onFailure, this::onSuccess);
    }

    private Result<EffectiveScale> resolveEffective() {
        if (coreCount > 0) {return Result.success(new EffectiveScale(coreCount, "core"));}
        if (count > 0) {return Result.success(new EffectiveScale(count, roleName));}
        return new ScaleError.MissingCount().result();
    }

    private record EffectiveScale(int count, String role){}

    private static Result<Integer> validateCount(int targetCount, String role) {
        if ("core".equals(role)) {return validateCoreCount(targetCount);}
        return validateNonCoreCount(targetCount);
    }

    private static Result<Integer> validateCoreCount(int targetCount) {
        if (targetCount <MINIMUM_CORE_COUNT) {return new ScaleError.QuorumSafety(targetCount, MINIMUM_CORE_COUNT).result();}
        if (targetCount % 2 == 0) {return new ScaleError.MustBeOdd(targetCount).result();}
        return Result.success(targetCount);
    }

    private static Result<Integer> validateNonCoreCount(int targetCount) {
        if (targetCount <1) {return new ScaleError.MinimumCount(targetCount).result();}
        return Result.success(targetCount);
    }

    private Result<Long> fetchConfigVersion(int validatedCount) {
        return ClusterHttpClient.fetch(CLUSTER_CONFIG_GET).flatMap(ClusterScaleCommand::extractConfigVersion);
    }

    private Result<String> sendScaleRequest(long expectedVersion, int targetCount, String role) {
        var jsonBody = buildScaleJson(targetCount, role, sourceName, expectedVersion);
        return ClusterHttpClient.post(CLUSTER_SCALE, jsonBody);
    }

    private static String buildScaleJson(int targetCount, String role, String source, long expectedVersion) {
        return "{\"count\":" + targetCount + ",\"role\":\"" + role + "\",\"source\":\"" + source + "\",\"expectedVersion\":" + expectedVersion + "}";
    }

    private int onSuccess(String json) {
        return OutputFormatter.printAction(json, parent.outputOptions(), "Scale successful.");
    }

    private static Result<Long> extractConfigVersion(String responseJson) {
        return MAPPER.readTree(responseJson).map(node -> node.path("configVersion").asLong(0));
    }

    private static int onFailure(Cause cause) {
        System.err.println("Error: " + cause.message());
        return ExitCode.ERROR;
    }

    sealed interface ScaleError extends Cause {
        record QuorumSafety(int requested, int minimum) implements ScaleError {
            @Override public String message() {
                return "Core count " + requested + " is below quorum minimum of " + minimum;
            }
        }

        record MustBeOdd(int requested) implements ScaleError {
            @Override public String message() {
                return "Core count must be odd for quorum safety, got " + requested;
            }
        }

        record MinimumCount(int requested) implements ScaleError {
            @Override public String message() {
                return "Node count must be at least 1, got " + requested;
            }
        }

        record MissingCount() implements ScaleError {
            @Override public String message() {
                return "Either --count or --core must be specified";
            }
        }
    }
}

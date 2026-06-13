// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.update;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.List;
import java.util.Map;


public record AbTestDeployment(String testId,
                               ArtifactBase artifactBase,
                               Version baselineVersion,
                               Map<String, Version> variantVersions,
                               AbTestState state,
                               SplitRule splitRule,
                               VersionRouting routing,
                               Option<String> blueprintId,
                               List<ArtifactBase> artifacts,
                               long createdAt,
                               long updatedAt) {
    private static final Fn1<Cause, String> INVALID_TRANSITION = Causes.forOneValue("Invalid A/B test state transition: %s");

    public static AbTestDeployment abTestDeployment(String testId,
                                                    ArtifactBase artifactBase,
                                                    Version baselineVersion,
                                                    Map<String, Version> variantVersions,
                                                    SplitRule splitRule) {
        var now = System.currentTimeMillis();

        return new AbTestDeployment(testId,
                                    artifactBase,
                                    baselineVersion,
                                    Map.copyOf(variantVersions),
                                    AbTestState.PENDING,
                                    splitRule,
                                    VersionRouting.ALL_OLD,
                                    Option.none(),
                                    List.of(artifactBase),
                                    now,
                                    now);
    }

    public Result<AbTestDeployment> transitionTo(AbTestState newState) {
        if (!state.validTransitions().contains(newState)) {
            return INVALID_TRANSITION.apply(state + " -> " + newState).result();
        }

        return Result.success(new AbTestDeployment(testId,
                                                   artifactBase,
                                                   baselineVersion,
                                                   variantVersions,
                                                   newState,
                                                   splitRule,
                                                   routing,
                                                   blueprintId,
                                                   artifacts,
                                                   createdAt,
                                                   System.currentTimeMillis()));
    }

    public AbTestDeployment withRouting(VersionRouting newRouting) {
        return new AbTestDeployment(testId,
                                    artifactBase,
                                    baselineVersion,
                                    variantVersions,
                                    state,
                                    splitRule,
                                    newRouting,
                                    blueprintId,
                                    artifacts,
                                    createdAt,
                                    System.currentTimeMillis());
    }

    public AbTestDeployment withBlueprintContext(String newBlueprintId, List<ArtifactBase> newArtifacts) {
        return new AbTestDeployment(testId,
                                    artifactBase,
                                    baselineVersion,
                                    variantVersions,
                                    state,
                                    splitRule,
                                    routing,
                                    Option.some(newBlueprintId),
                                    List.copyOf(newArtifacts),
                                    createdAt,
                                    System.currentTimeMillis());
    }

    public String strategyId() {
        return testId;
    }

    public Version oldVersion() {
        return baselineVersion;
    }

    public Version newVersion() {
        return Option.from(variantVersions.values().stream().findFirst()).or(baselineVersion);
    }

    public boolean isTerminal() {
        return state.isTerminal();
    }

    public boolean isActive() {
        return ! isTerminal();
    }

    public long age() {
        return System.currentTimeMillis() - createdAt;
    }

    public long timeSinceUpdate() {
        return System.currentTimeMillis() - updatedAt;
    }
}

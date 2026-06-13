// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.update;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.List;


public record Deployment(String deploymentId,
                         String blueprintId,
                         Version oldVersion,
                         Version newVersion,
                         DeploymentState state,
                         DeploymentStrategy strategy,
                         StrategyConfig strategyConfig,
                         VersionRouting routing,
                         HealthThresholds thresholds,
                         CleanupPolicy cleanupPolicy,
                         List<ArtifactBase> artifacts,
                         int newInstances,
                         long createdAt,
                         long updatedAt) {
    private static final Fn1<Cause, String> INVALID_TRANSITION = Causes.forOneValue("Invalid deployment state transition: %s");

    public static Deployment deployment(String deploymentId,
                                        String blueprintId,
                                        Version oldVersion,
                                        Version newVersion,
                                        DeploymentStrategy strategy,
                                        StrategyConfig strategyConfig,
                                        HealthThresholds thresholds,
                                        CleanupPolicy cleanupPolicy,
                                        List<ArtifactBase> artifacts,
                                        int newInstances) {
        var now = System.currentTimeMillis();

        return new Deployment(deploymentId,
                              blueprintId,
                              oldVersion,
                              newVersion,
                              DeploymentState.PENDING,
                              strategy,
                              strategyConfig,
                              VersionRouting.ALL_OLD,
                              thresholds,
                              cleanupPolicy,
                              List.copyOf(artifacts),
                              newInstances,
                              now,
                              now);
    }

    public Result<Deployment> deploy() {
        return transitionTo(DeploymentState.DEPLOYING);
    }

    public Result<Deployment> deployed() {
        return transitionTo(DeploymentState.DEPLOYED);
    }

    public Result<Deployment> route(VersionRouting newRouting) {
        return transitionTo(DeploymentState.ROUTING).map(d -> d.withRouting(newRouting));
    }

    public Result<Deployment> promote() {
        return transitionTo(DeploymentState.PROMOTING);
    }

    public Result<Deployment> rollback() {
        return transitionTo(DeploymentState.ROLLING_BACK);
    }

    public Result<Deployment> rolledBack() {
        return transitionTo(DeploymentState.ROLLED_BACK);
    }

    public Result<Deployment> complete() {
        return transitionTo(DeploymentState.COMPLETED);
    }

    public Result<Deployment> fail() {
        return transitionTo(DeploymentState.FAILED);
    }

    public boolean isTerminal() {
        return state.isTerminal();
    }

    public boolean isActive() {
        return state.isActive();
    }

    public long age() {
        return System.currentTimeMillis() - createdAt;
    }

    public long timeSinceUpdate() {
        return System.currentTimeMillis() - updatedAt;
    }

    private Result<Deployment> transitionTo(DeploymentState newState) {
        if (!state.validTransitions().contains(newState)) {
            return INVALID_TRANSITION.apply(state + " -> " + newState).result();
        }

        return Result.success(new Deployment(deploymentId,
                                             blueprintId,
                                             oldVersion,
                                             newVersion,
                                             newState,
                                             strategy,
                                             strategyConfig,
                                             routing,
                                             thresholds,
                                             cleanupPolicy,
                                             artifacts,
                                             newInstances,
                                             createdAt,
                                             System.currentTimeMillis()));
    }

    private Deployment withRouting(VersionRouting newRouting) {
        return new Deployment(deploymentId,
                              blueprintId,
                              oldVersion,
                              newVersion,
                              state,
                              strategy,
                              strategyConfig,
                              newRouting,
                              thresholds,
                              cleanupPolicy,
                              artifacts,
                              newInstances,
                              createdAt,
                              updatedAt);
    }
}

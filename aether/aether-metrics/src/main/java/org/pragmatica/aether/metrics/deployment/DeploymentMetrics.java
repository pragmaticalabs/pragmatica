// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics.deployment;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.cluster.metrics.DeploymentMetricsMessage.DeploymentMetricsEntry;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.LinkedHashMap;
import java.util.Map;


public record DeploymentMetrics(Artifact artifact,
                                NodeId nodeId,
                                long startTime,
                                long loadTime,
                                long loadedTime,
                                long activateTime,
                                long activeTime,
                                DeploymentStatus status) {
    public enum DeploymentStatus {
        IN_PROGRESS,
        SUCCESS,
        FAILED_LOADING,
        FAILED_ACTIVATING;
        public static DeploymentStatus deploymentStatus(String value) {
            return switch (value){
                case "IN_PROGRESS" -> IN_PROGRESS;
                case "SUCCESS" -> SUCCESS;
                case "FAILED_LOADING" -> FAILED_LOADING;
                case "FAILED_ACTIVATING" -> FAILED_ACTIVATING;
                default -> IN_PROGRESS;
            };
        }
    }

    public long fullDeploymentTime() {
        return activeTime > 0
              ? activeTime - startTime
              : - 1;
    }

    public long netDeploymentTime() {
        return activeTime > 0 && loadedTime > 0
              ? activeTime - loadedTime
              : - 1;
    }

    public Map<String, Long> transitionLatencies() {
        var latencies = new LinkedHashMap<String, Long>();
        if (loadTime > 0 && startTime > 0) {latencies.put("START_TO_LOAD", loadTime - startTime);}
        if (loadedTime > 0 && loadTime > 0) {latencies.put("LOAD_TO_LOADED", loadedTime - loadTime);}
        if (activateTime > 0 && loadedTime > 0) {latencies.put("LOADED_TO_ACTIVATE", activateTime - loadedTime);}
        if (activeTime > 0 && activateTime > 0) {latencies.put("ACTIVATE_TO_ACTIVE", activeTime - activateTime);}
        return latencies;
    }

    public DeploymentMetricsEntry toEntry() {
        return new DeploymentMetricsEntry(artifact.asString(),
                                          nodeId.id(),
                                          startTime,
                                          loadTime,
                                          loadedTime,
                                          activateTime,
                                          activeTime,
                                          status.name());
    }

    public static Option<DeploymentMetrics> fromEntry(DeploymentMetricsEntry entry) {
        return Artifact.artifact(entry.artifact()).flatMap(artifact -> buildFromEntry(artifact, entry))
                                .option();
    }

    @SuppressWarnings("JBCT-VO-02") private static Result<DeploymentMetrics> buildFromEntry(Artifact artifact,
                                                                                            DeploymentMetricsEntry entry) {
        return NodeId.nodeId(entry.nodeId())
                            .map(nodeId -> new DeploymentMetrics(artifact,
                                                                 nodeId,
                                                                 entry.startTime(),
                                                                 entry.loadTime(),
                                                                 entry.loadedTime(),
                                                                 entry.activateTime(),
                                                                 entry.activeTime(),
                                                                 DeploymentStatus.deploymentStatus(entry.status())));
    }

    @SuppressWarnings("JBCT-VO-02") public static DeploymentMetrics deploymentMetrics(Artifact artifact,
                                                                                      NodeId nodeId,
                                                                                      long timestamp) {
        return new DeploymentMetrics(artifact, nodeId, timestamp, 0, 0, 0, 0, DeploymentStatus.IN_PROGRESS);
    }

    @SuppressWarnings("JBCT-VO-02") public DeploymentMetrics withLoadTime(long timestamp) {
        return new DeploymentMetrics(artifact,
                                     nodeId,
                                     startTime,
                                     timestamp,
                                     loadedTime,
                                     activateTime,
                                     activeTime,
                                     status);
    }

    @SuppressWarnings("JBCT-VO-02") public DeploymentMetrics withLoadedTime(long timestamp) {
        return new DeploymentMetrics(artifact, nodeId, startTime, loadTime, timestamp, activateTime, activeTime, status);
    }

    @SuppressWarnings("JBCT-VO-02") public DeploymentMetrics withActivateTime(long timestamp) {
        return new DeploymentMetrics(artifact, nodeId, startTime, loadTime, loadedTime, timestamp, activeTime, status);
    }

    @SuppressWarnings("JBCT-VO-02") public DeploymentMetrics completed(long timestamp) {
        return new DeploymentMetrics(artifact,
                                     nodeId,
                                     startTime,
                                     loadTime,
                                     loadedTime,
                                     activateTime,
                                     timestamp,
                                     DeploymentStatus.SUCCESS);
    }

    @SuppressWarnings("JBCT-VO-02") public DeploymentMetrics failedLoading(long timestamp) {
        return new DeploymentMetrics(artifact,
                                     nodeId,
                                     startTime,
                                     loadTime,
                                     timestamp,
                                     0,
                                     0,
                                     DeploymentStatus.FAILED_LOADING);
    }

    @SuppressWarnings("JBCT-VO-02") public DeploymentMetrics failedActivating(long timestamp) {
        return new DeploymentMetrics(artifact,
                                     nodeId,
                                     startTime,
                                     loadTime,
                                     loadedTime,
                                     activateTime,
                                     timestamp,
                                     DeploymentStatus.FAILED_ACTIVATING);
    }
}

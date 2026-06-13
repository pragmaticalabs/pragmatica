// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics.artifact;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.resource.artifact.ArtifactStore;

import java.util.Map;
import java.util.Set;


public interface ArtifactMetricsCollector {
    String ARTIFACT_CHUNKS_TOTAL = "artifact.chunks.total";
    String ARTIFACT_MEMORY_BYTES = "artifact.memory.bytes";
    String ARTIFACT_COUNT = "artifact.count";
    String ARTIFACT_DEPLOYED_COUNT = "artifact.deployed.count";
    Map<String, Double> collectMetrics();
    boolean isDeployed(Artifact artifact);
    Set<Artifact> deployedArtifacts();
    ArtifactStore.Metrics storeMetrics();
    ArtifactDeploymentTracker deploymentTracker();

    static ArtifactMetricsCollector artifactMetricsCollector(ArtifactStore artifactStore) {
        return new ArtifactMetricsCollectorImpl(artifactStore, ArtifactDeploymentTracker.artifactDeploymentTracker());
    }
}

class ArtifactMetricsCollectorImpl implements ArtifactMetricsCollector {
    private final ArtifactStore artifactStore;
    private final ArtifactDeploymentTracker deploymentTracker;

    ArtifactMetricsCollectorImpl(ArtifactStore artifactStore, ArtifactDeploymentTracker deploymentTracker) {
        this.artifactStore = artifactStore;
        this.deploymentTracker = deploymentTracker;
    }

    @Override
    public Map<String, Double> collectMetrics() {
        var storeMetrics = artifactStore.metrics();

        return Map.of(ARTIFACT_CHUNKS_TOTAL,
                      (double) storeMetrics.chunkCount(),
                      ARTIFACT_MEMORY_BYTES,
                      (double) storeMetrics.memoryBytes(),
                      ARTIFACT_COUNT,
                      (double) storeMetrics.artifactCount(),
                      ARTIFACT_DEPLOYED_COUNT,
                      (double) deploymentTracker.deployedCount());
    }

    @Override
    public boolean isDeployed(Artifact artifact) {
        return deploymentTracker.isDeployed(artifact);
    }

    @Override
    public Set<Artifact> deployedArtifacts() {
        return deploymentTracker.deployedArtifacts();
    }

    @Override
    public ArtifactStore.Metrics storeMetrics() {
        return artifactStore.metrics();
    }

    @Override
    public ArtifactDeploymentTracker deploymentTracker() {
        return deploymentTracker;
    }
}

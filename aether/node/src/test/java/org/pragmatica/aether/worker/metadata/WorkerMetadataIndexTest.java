// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
package org.pragmatica.aether.worker.metadata;

import java.util.Set;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.consensus.NodeId;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


class WorkerMetadataIndexTest {
    private static final NodeId WORKER = new NodeId("worker");
    private static final NodeId FOREIGN = new NodeId("foreign");
    private static final NodeId CORE = new NodeId("core");

    @Test
    void consumerAssignmentTravelsWithItsStreamProjection() {
        var index = WorkerMetadataIndex.workerMetadataIndex();
        var key = new AetherKey.ConsumerAssignmentKey("orders", 0, "billing");
        var assignment = AetherValue.ConsumerAssignmentValue.consumerAssignmentValue(WORKER,
            org.pragmatica.aether.slice.generation.Epoch.epoch(1, 1), 1, org.pragmatica.hlc.HlcTimestamp.ZERO);

        index.put(key, assignment);
        assertThat(index.snapshot("stream:orders")).containsEntry(key, assignment);
        assertThat(index.snapshot("stream:unrelated")).doesNotContainKey(key);
        assertThat(index.snapshot(WorkerMetadataIndex.GLOBAL)).doesNotContainKey(key);
    }

    @Test
    void rawProviderConfigurationNeverEntersWorkerProjection() {
        var index = WorkerMetadataIndex.workerMetadataIndex();
        var configuration = new AetherValue.ClusterConfigValue("credentials = 'secret'",
                                                               "cluster",
                                                               "1",
                                                               java.util.List.of(),
                                                               5,
                                                               7,
                                                               "cloud",
                                                               1,
                                                               0);

        index.put(AetherKey.ClusterConfigKey.CURRENT, configuration);
        assertThat(index.snapshot(WorkerMetadataIndex.GLOBAL)).doesNotContainKey(AetherKey.ClusterConfigKey.CURRENT);
        assertThat(index.scopesForWorker(WORKER).stream().flatMap(scope -> index.snapshot(scope)
                                                                                .values()
                                                                                .stream())).doesNotContain(configuration);
    }

    @Test
    void communityMovementReplacesDirectoryWithoutIncludingForeignCommunities() {
        var index = WorkerMetadataIndex.workerMetadataIndex();
        var assignment = new AetherKey.ActivationDirectiveKey(WORKER);

        index.put(assignment, AetherValue.ActivationDirectiveValue.worker("first", ""));
        index.put(new AetherKey.ActivationDirectiveKey(FOREIGN),
                  AetherValue.ActivationDirectiveValue.worker("second", ""));
        assertThat(index.peersForWorker(WORKER, Set.of(CORE))).containsExactlyInAnyOrder(WORKER, CORE);
        index.put(assignment, AetherValue.ActivationDirectiveValue.worker("second", ""));
        assertThat(index.peersForWorker(WORKER, Set.of(CORE))).containsExactlyInAnyOrder(WORKER, CORE, FOREIGN);
        assertThat(index.scopesForWorker(WORKER)).contains("community:second").doesNotContain("community:first");
        assertThat(index.hasScope("community:first")).isFalse();
    }

    @Test
    void unrelatedNodeMutationDoesNotInvalidateOwnScopes() {
        var index = WorkerMetadataIndex.workerMetadataIndex();

        index.put(new AetherKey.ActivationDirectiveKey(WORKER), AetherValue.ActivationDirectiveValue.worker("first", ""));
        var ownRevision = index.revision("node:worker");
        var communityRevision = index.revision("community:first");

        index.put(new AetherKey.ActivationDirectiveKey(FOREIGN),
                  AetherValue.ActivationDirectiveValue.worker("second", ""));
        assertThat(index.revision("node:worker")).isEqualTo(ownRevision);
        assertThat(index.revision("community:first")).isEqualTo(communityRevision);
    }

    @Test
    void endpointDirectoryIncludesRelevantForeignActiveEndpointsWithoutExpandingMembership() {
        var index = WorkerMetadataIndex.workerMetadataIndex();
        var artifact = Artifact.artifact("com.example:service:1.0.0").unwrap();
        var foreignAssignment = new AetherKey.NodeArtifactKey(FOREIGN, artifact);

        index.put(new AetherKey.NodeArtifactKey(WORKER, artifact),
                  AetherValue.NodeArtifactValue.nodeArtifactValue(SliceState.ACTIVE));
        index.put(foreignAssignment, AetherValue.NodeArtifactValue.nodeArtifactValue(SliceState.ACTIVE));
        assertThat(index.endpointPeersForWorker(WORKER)).containsExactlyInAnyOrder(WORKER, FOREIGN);
        assertThat(index.peersForWorker(WORKER, Set.of(CORE))).doesNotContain(FOREIGN);
        index.put(foreignAssignment, AetherValue.NodeArtifactValue.nodeArtifactValue(SliceState.DEACTIVATING));
        assertThat(index.endpointPeersForWorker(WORKER)).containsExactly(WORKER);
    }
}

// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.metadata;

import java.util.Set;

import org.pragmatica.lang.Option;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.RequirementKind;
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
            org.pragmatica.aether.slice.generation.Epoch.epoch(0L, 1, 1), 1, org.pragmatica.hlc.HlcTimestamp.ZERO);

        index.put(key, assignment);
        assertThat(index.snapshot("stream:orders")).containsEntry(key, assignment);
        assertThat(index.snapshot("stream:unrelated")).doesNotContainKey(key);
        assertThat(index.snapshot(WorkerMetadataIndex.GLOBAL)).doesNotContainKey(key);
    }

    /// v-2339 F4 (#1337): a worker receives both topic row kinds: in the global scope and in its artifact's scope. A worker that
    /// hosts a publisher or a subscriber has to see the other side's rows for the activation check, and the delivery path reads them.
    @Test
    void topicSubscriptionAndPublisherRowsAreInTheGlobalAndTheArtifactScopes_andReachAWorker() {
        var index = WorkerMetadataIndex.workerMetadataIndex();
        var artifact = Artifact.artifact("org.example:orders:1.0.0").unwrap();
        var address = org.pragmatica.aether.slice.resource.ResourceAddress.resourceAddress("default", "orders", "1.0.0").unwrap();
        var subscription = AetherKey.TopicSubscriptionKey.topicSubscriptionKey(address, artifact, org.pragmatica.aether.slice.MethodName.methodName("onOrder").unwrap(), CORE);
        var publisher = AetherKey.TopicPublisherKey.topicPublisherKey(address, artifact, CORE);
        var subscriptionValue = AetherValue.TopicSubscriptionValue.topicSubscriptionValue(CORE, AetherValue.TopicTier.DURABLE);
        var publisherValue = AetherValue.TopicPublisherValue.topicPublisherValue(CORE, AetherValue.TopicTier.EPHEMERAL);

        index.put(subscription, subscriptionValue);
        index.put(publisher, publisherValue);

        assertThat(index.snapshot(WorkerMetadataIndex.GLOBAL)).containsEntry(subscription, subscriptionValue).containsEntry(publisher, publisherValue);
        assertThat(index.snapshot("artifact:org.example:orders")).containsEntry(subscription, subscriptionValue).containsEntry(publisher, publisherValue);
        assertThat(index.snapshot("artifact:org.example:other")).doesNotContainKey(publisher).doesNotContainKey(subscription);
        assertThat(index.scopesForWorker(WORKER).stream().flatMap(scope -> index.snapshot(scope).keySet().stream())).contains(subscription, publisher);
    }

    @Test
    void rawProviderConfigurationNeverEntersWorkerProjection() {
        var index = WorkerMetadataIndex.workerMetadataIndex();
        var configuration = new AetherValue.ClusterConfigValue(Option.some("credentials = 'secret'"),
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

    /// rc6 §3.1 (n22): a slice's capacity requirement is a leader-side input of the combiner and enters no worker scope, where the slice's
    /// target travels in its artifact scope. Positive control: the target put moves the artifact scope's revision, so a requirement put that
    /// leaves every revision unmoved is not the index failing to see a put.
    @Test
    void sliceRequirementKey_entersNoWorkerScope_whereTheSliceTargetEntersTheArtifactScope() {
        var index = WorkerMetadataIndex.workerMetadataIndex();
        var base = ArtifactBase.artifactBase("com.example:service").unwrap();
        var scope = "artifact:" + base.asString();

        index.put(AetherKey.SliceTargetKey.sliceTargetKey(base), "opaque value: the arm reads the key only");
        var afterTarget = index.revision();

        assertThat(index.revision(scope)).as("control: the target is in the artifact scope").isPositive();

        index.put(AetherKey.SliceRequirementKey.sliceRequirementKey(base, RequirementKind.OPERATOR), "opaque value: the unscoped arm never reads it");

        assertThat(index.revision()).as("a requirement put moves no revision").isEqualTo(afterTarget);
        assertThat(index.snapshot(scope).keySet()).as("the artifact scope holds the target and no requirement")
                                                 .containsExactly(AetherKey.SliceTargetKey.sliceTargetKey(base));
    }
}

// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.time.Duration;
import java.util.Set;
import org.pragmatica.aether.api.ClusterEvent;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.TlsConfig;

import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// #1109: the leader's committed-target gate reports a LOAD it has refused past the bound to THIS node's operator-warning sink. The
/// unit tests hand the leader context their own sink, so un-binding `clusterDeploymentManager.setOperatorWarningSink(...)` in `AetherNode`
/// left them green while a lost target put stalled the slice with nothing on the event surface. This boots the real assembly, activates the
/// cluster deployment manager, starts it on a store that holds a committed blueprint but no target (a new leader's case, the shape of a lost target put), and waits for
/// the aggregator of that node to hold the `slice-start-awaiting-target` event the leader's own reconcile tick raised.
class AetherNodeLeaderAwaitedTargetBootTest {
    @TempDir
    Path tempDir;

    private static final TimeSpan START_BOUND = timeSpan(30).seconds();
    private static final BlueprintId OWNER = BlueprintId.blueprintId("org.example:wiring-app:1.0.0").unwrap();
    private static final Artifact SLICE = Artifact.artifact("org.example:wiring-slice:1.0.0").unwrap();

    private AetherNode node;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop()
                .await(timeSpan(10).seconds())
                .onFailure(cause -> {});
        }

        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 120, unit = SECONDS)
    void bootedNodeLeader_raisesAwaitingTargetOnItsOwnEventSurface_whenTheTargetPutNeverCommits() throws Exception {
        var self = NodeId.nodeId("awaited-target-boot-" + UUID.randomUUID()).unwrap();

        node = AetherNode.aetherNode(minimalConfig(self, tempDir), () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();
        node.start()
            .await(START_BOUND)
            .onFailure(cause -> fail("start() must succeed: " + cause.message()));

        var cdm = (ClusterDeploymentManager) node.getClass().getMethod("clusterDeploymentManager").invoke(node);
        var ctx = ((ClusterDeploymentManager.ClusterDeploymentManagerAdapter) cdm).context();

        // A new leader's case, which is also the shape of a lost target put: the blueprint is committed, its target is not. The leader
        // restores its mirror from the store and nothing ever puts the target (only the watch under test does, past the bound).
        var slice = ResolvedSlice.resolvedSlice(SLICE, 1, 1, false, Set.of()).unwrap();
        var blueprint = AppBlueprintValue.appBlueprintValue(ExpandedBlueprint.expandedBlueprint(OWNER, List.of(slice), Option.none()));

        node.kvStore().process(node.kvStore().createBatch(List.of(new KVCommand.Put<AetherKey, AetherValue>(AppBlueprintKey.appBlueprintKey(OWNER), blueprint))));
        assertThat(node.kvStore().get(SliceTargetKey.sliceTargetKey(SLICE.base())).isPresent()).as("control: the blueprint is committed, its target is not").isFalse();
        // The booted node's own storage may already have raised its degrade warnings (the synthesized instances cannot open a disk
        // tier under the hermetic root, and say so since A0b); the control is about THIS warning, not about silence.
        assertThat(isAwaitingTargetWarning(node.eventAggregator().lastRaisedOperatorWarning())).as("control: awaiting-target not raised yet").isFalse();

        ctx.setAwaitedTargetBound(timeSpan(1).millis());
        cdm.activate();
        assertThat(ctx.isActive()).as("control: the leader state is active").isTrue();

        await().atMost(Duration.ofSeconds(90)).untilAsserted(() -> {
            var raised = node.eventAggregator().lastRaisedOperatorWarning();

            assertThat(raised.isPresent()).as("the leader's awaiting-target warning reached this node's aggregator").isTrue();
            assertThat(raised.unwrap()).isInstanceOfSatisfying(ClusterEvent.OperatorWarning.class,
                                                              warning -> assertThat(warning.details()).containsEntry("code", "slice-start-awaiting-target"));
        });
    }

    private static boolean isAwaitingTargetWarning(Option<ClusterEvent> raised) {
        return raised.filter(ClusterEvent.OperatorWarning.class::isInstance)
                     .map(ClusterEvent.OperatorWarning.class::cast)
                     .filter(warning -> "slice-start-awaiting-target".equals(warning.details().get("code")))
                     .isPresent();
    }

    private static AetherNodeConfig minimalConfig(NodeId self, Path storageRoot) {
        var selfInfo = NodeInfo.nodeInfo(self, nodeAddress("localhost", ClusterTestPorts.freeClusterPort()).unwrap());

        return AetherNodeConfig.builder()
                               .self(self)
                               .coreNodes(List.of(selfInfo))
                               .managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
                               .sliceConfig(SliceConfig.sliceConfig())
                               .artifactRepo(DHTConfig.FULL)
                               .coreMax(1)
                               .appHttp(AppHttpConfig.appHttpConfig())
                               .tls(Option.none())
                               .quicTls(TlsConfig.selfSignedMutual())
                               .certificateProvider(Option.none())
                               .configProvider(Option.none())
                               .environment(Option.none())
                               .managementHttpProtocol(HttpProtocol.H1)
                               .storageConfig(HermeticStorage.nodeStorageIn(storageRoot, false))
                               .build();
    }
}

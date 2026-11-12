// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;

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

    private static final String AWAITING_TARGET = "slice-start-awaiting-target";
    private static final int NOISE_WARNINGS = 200;

    private AetherNode node;
    private ScheduledExecutorService noise;

    @AfterEach
    void tearDown() {
        if (noise != null) {
            noise.shutdownNow();
        }

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
        assertThat(raisedCodes()).as("control: awaiting-target not raised yet").doesNotContain(AWAITING_TARGET);

        raiseOtherWarningsAround();
        ctx.setAwaitedTargetBound(timeSpan(1).millis());
        cdm.activate();
        assertThat(ctx.isActive()).as("control: the leader state is active").isTrue();

        // The aggregator's single most-recent slot is overwritten by any later warning (storage degrade, the noise above), so the
        // assertion reads the event log: the awaited warning was RAISED, whatever was raised after it.
        await().atMost(Duration.ofSeconds(90))
               .untilAsserted(() -> assertThat(raisedCodes()).as("the leader's awaiting-target warning reached this node's event log")
                                                             .contains(AWAITING_TARGET));
    }

    /// Other producers keep raising warnings while the leader's reconcile tick runs, so the most-recent slot cannot hold the awaited
    /// one for long (#3103); a bounded burst of distinct-subject warnings stands in for the storage degrade warnings of the hermetic node.
    private void raiseOtherWarningsAround() {
        var raised = new AtomicInteger();

        noise = Executors.newSingleThreadScheduledExecutor();
        noise.scheduleAtFixedRate(() -> raiseNoiseWarning(raised.incrementAndGet()), 0, 5, TimeUnit.MILLISECONDS);
    }

    private void raiseNoiseWarning(int sequence) {
        if (sequence > NOISE_WARNINGS) {
            noise.shutdown();
            return;
        }

        var subject = "noise-" + sequence;

        node.eventAggregator().onOperatorWarning(OperatorWarning.operatorWarning(OperatorWarningCode.DEPLOY_WARNING, subject, "deploy-warning " + subject));
    }

    private List<String> raisedCodes() {
        return node.eventAggregator()
                   .events()
                   .await(timeSpan(10).seconds())
                   .or(List.of())
                   .stream()
                   .map(event -> String.valueOf(event.details().get("code")))
                   .toList();
    }

    /// A0b: storage is built before the node's event aggregator exists; the node binds every instance's degrade reporter to the
    /// aggregator's sink once it does. The hermetic storage roots cannot hold a disk tier, so the booted node's instances degrade
    /// while being built, and the aggregator of the booted node must show the storage warning. Red with the bind removed: the
    /// conditions raised during the build then never reach the aggregator.
    @Test
    @Timeout(value = 120, unit = SECONDS)
    void bootedNode_whoseStorageDegradedWhileBuilding_showsTheStorageWarningOnItsAggregator() {
        var self = NodeId.nodeId("storage-bind-" + UUID.randomUUID()).unwrap();
        node = AetherNode.aetherNode(minimalConfig(self, tempDir), () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();
        node.start().await(START_BOUND).onFailure(cause -> fail("start() must succeed: " + cause.message()));

        await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> assertThat(storageWarningCode(node.eventAggregator().lastRaisedOperatorWarning()))
                .startsWith("storage-"));
    }

    private static String storageWarningCode(Option<ClusterEvent> raised) {
        return raised.filter(ClusterEvent.OperatorWarning.class::isInstance)
                     .map(ClusterEvent.OperatorWarning.class::cast)
                     .map(warning -> String.valueOf(warning.details().get("code")))
                     .or("none");
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

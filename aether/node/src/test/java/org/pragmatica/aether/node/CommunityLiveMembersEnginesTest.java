// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.TlsConfig;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// #3159: the community live-member count the management API serves (`ManageableNode#communityLiveMembers`) is the SAME number under both
/// deployment engines. It used to come from the LEGACY `ClusterDeploymentManager` alone, which is never active under FSM2, so FSM2 served
/// "absent" for a community with a committed roster. A real single-node boot per engine: the node leads itself, a committed one-member
/// roster is directed to the community, and the leader's wired liveness view has no observation against that member.
class CommunityLiveMembersEnginesTest {
    private static final String COMMUNITY = "east";
    private static final NodeId WORKER = NodeId.nodeId("community-worker-1").unwrap();

    @TempDir
    Path tempDir;

    private AetherNode node;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop().await(timeSpan(10).seconds()).onFailure(cause -> {});
        }
        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 180, unit = SECONDS)
    void communityLiveMembers_isTheCommittedRosterCount_underLegacy() {
        assertRosterCount(DeploymentEngine.LEGACY);
    }

    @Test
    @Timeout(value = 180, unit = SECONDS)
    void communityLiveMembers_isTheCommittedRosterCount_underFsm2() {
        assertRosterCount(DeploymentEngine.FSM2);
    }

    private void assertRosterCount(DeploymentEngine engine) {
        node = AetherNode.aetherNode(minimalConfig(tempDir).withDeploymentEngine(engine), () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();
        node.start().await(timeSpan(30).seconds()).onFailure(cause -> fail("start() must succeed: " + cause.message()));
        await("the node leads itself").atMost(Duration.ofSeconds(60)).until(node::isLeader);
        commitRoster();

        assertThat(node.communityLiveMembers(COMMUNITY)).as("engine " + engine).isEqualTo(Option.some(1));
    }

    private void commitRoster() {
        var roster = GovernorAnnouncementValue.governorAnnouncementValue(WORKER, List.of(WORKER), "");
        var commands = List.<KVCommand<AetherKey>>of(new KVCommand.Put<AetherKey, AetherValue>(ActivationDirectiveKey.activationDirectiveKey(WORKER),
                                                                                                 ActivationDirectiveValue.worker(COMMUNITY, "")),
                                                     new KVCommand.Put<AetherKey, AetherValue>(GovernorAnnouncementKey.forCommunity(COMMUNITY), roster));

        node.apply(commands).await(timeSpan(30).seconds()).onFailure(cause -> fail("commit must succeed: " + cause.message()));
    }

    private static AetherNodeConfig minimalConfig(Path storageRoot) {
        var self = NodeId.nodeId("community-boot-" + UUID.randomUUID()).unwrap();
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

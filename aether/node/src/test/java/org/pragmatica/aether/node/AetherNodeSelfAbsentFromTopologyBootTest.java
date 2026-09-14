// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.TlsConfig;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.net.tcp.NodeAddress.nodeAddress;

/// #517 (closed: already refused) — tripwire. `AetherNode.findSelfAddress` and `resolveHostname`
/// carry dead placeholder branches for "self absent from its own topology"; they are dead only
/// because `TopologyObserver.topologyObserver` (reached through `RabiaNode.rabiaNode`) refuses
/// such a config BEFORE `assembleNode` runs. This pins that ordering at the node level: a boot with
/// `self ∉ coreNodes` must fail naming the self id, never reach assembly and advertise `("", 0)` /
/// `localhost`. It reddens the moment assembly is reordered ahead of the observer factory.
class AetherNodeSelfAbsentFromTopologyBootTest {
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
    @Timeout(value = 60, unit = SECONDS)
    void aetherNode_refusesBoot_whenSelfIsAbsentFromItsOwnTopology() {
        var self = NodeId.nodeId("self-absent-boot-test").unwrap();
        var other = NodeId.nodeId("some-other-node").unwrap();
        var otherInfo = NodeInfo.nodeInfo(other, nodeAddress("localhost", 39471).unwrap());
        var config = AetherNodeConfig.builder()
                                     .self(self)
                                     .coreNodes(List.of(otherInfo))
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
                                     .build();

        AetherNode.aetherNode(config, () -> {})
                  .onSuccess(booted -> {
                      node = booted;
                      fail("#517: a node absent from its own topology must not boot — it would advertise a placeholder address");
                  })
                  .onFailure(cause -> {
                      assertThat(cause.message()).as("the refusal names the missing self id")
                                                 .contains(self.id());
                      assertThat(cause.message()).as("and it is the observer factory's refusal, not a downstream symptom")
                                                 .contains("must be in coreNodes");
                  });
    }

    /// The refusing producer, named so a rename or removal of the guard reddens here too.
    @Test
    void theGuardIsTheTopologyObserverFactory() {
        assertThat(TopologyObserver.TopologyError.SelfNodeNotInCoreNodes.class.getEnclosingClass())
                .isEqualTo(TopologyObserver.TopologyError.class);
    }
}

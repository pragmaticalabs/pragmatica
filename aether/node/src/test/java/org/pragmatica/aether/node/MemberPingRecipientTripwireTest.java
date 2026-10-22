// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.metrics.DrainCommandRegistry;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// TW-O17, node half, of the worker-replica-feed design (W-OBS): who the leader's ping reaches. The recipient predicate is registered by the
/// node's assembly (`setPingTargetEligibility`: the hierarchy policy's direct peers, a peer with a drain requested, and on a core a peer pending
/// admission), so the test reads it from an ASSEMBLED, unstarted node: the node's scheduler, its context, the predicate, and the registry the
/// predicate captured. The `aether-metrics` half (dispatch is leader-only, no directory slot on the messages) is `ClusterDirectoryTripwireTest`.
///
/// Today a plain member is not a recipient: it is reached only while a drain is requested for it (or, on a core, while it is pending
/// admission). The design needs leader-authored content to reach a member through a responder's pong, not through a leader ping.
class MemberPingRecipientTripwireTest {
    private static final NodeId MEMBER = new NodeId("plain-member");

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path storageRoot;
    private AetherNode node;

    @AfterEach
    void close() {
        if (node != null) {
            node.stop().await(timeSpan(10).seconds());
        }
        ConfigService.clear();
        ResourceProvider.clear();
    }

    @SuppressWarnings("unchecked")
    private Predicate<NodeId> recipientPredicate(String role) throws ReflectiveOperationException {
        var self = new NodeId("ping-recipient-" + role + "-" + UUID.randomUUID());

        node = AetherNode.aetherNode(config(self, role), () -> {}).unwrap();
        var scheduler = component(node, "metricsScheduler");
        var context = field(scheduler, "context");

        return ((AtomicReference<Predicate<NodeId>>) field(context, "pingTargetEligibility")).get();
    }

    private static DrainCommandRegistry capturedDrainRegistry(Predicate<NodeId> predicate) throws ReflectiveOperationException {
        for (Field captured : predicate.getClass().getDeclaredFields()) {
            captured.setAccessible(true);
            if (captured.get(predicate) instanceof DrainCommandRegistry registry) {
                return registry;
            }
        }
        throw new AssertionError("the registered predicate no longer captures the drain registry; reach it another way: " + predicate.getClass());
    }

    @Test
    void aPlainMember_isNotAPingRecipient_onACore_untilADrainIsRequestedForIt_today() throws ReflectiveOperationException {
        var predicate = recipientPredicate("core");

        assertThat(predicate.test(MEMBER)).as("TW-O17: a plain member is not a ping recipient of a core").isFalse();
        capturedDrainRegistry(predicate).requestDrain(MEMBER);
        assertThat(predicate.test(MEMBER)).as("control: the predicate is live, a drain request makes the same member a recipient").isTrue();
    }

    @Test
    void aPlainMember_isNotAPingRecipient_onAWorker_untilADrainIsRequestedForIt_today() throws ReflectiveOperationException {
        var predicate = recipientPredicate("worker");

        assertThat(predicate.test(MEMBER)).as("TW-O17: a plain member is not a ping recipient of a worker").isFalse();
        capturedDrainRegistry(predicate).requestDrain(MEMBER);
        assertThat(predicate.test(MEMBER)).as("control: a drain request makes it one").isTrue();
    }

    @Disabled("W4 (cutover and relay): enable when leader-authored directory content reaches a member through a responder's pong; replace the body with NT-O44 over a governor in Ember")
    @Test
    void aMember_receivesLeaderContent_throughAGovernorsPong_notThroughALeaderPing() {
        throw new AssertionError("NT-O44 end to end is not written yet; the property to check once it is: in an Ember cluster with a governor, a plain member that is not a leader ping recipient installs the leader's ClusterDirectory from its governor's pong");
    }

    private AetherNodeConfig config(NodeId self, String role) {
        return AetherNodeConfig.builder().self(self)
            .coreNodes(List.of(NodeInfo.nodeInfo(self, NodeAddress.nodeAddress("localhost", 6123).unwrap(), Map.of(NodeInfo.LABEL_ROLE, role)),
                              NodeInfo.nodeInfo(new NodeId("core-seed"), NodeAddress.nodeAddress("localhost", 6124).unwrap(), Map.of(NodeInfo.LABEL_ROLE, "core"))))
            .managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
            .sliceConfig(org.pragmatica.aether.config.SliceConfig.sliceConfig())
            .artifactRepo(org.pragmatica.dht.DHTConfig.FULL).coreMax(1)
            .appHttp(AppHttpConfig.appHttpConfig()).tls(Option.none())
            .quicTls(TlsConfig.selfSignedMutual()).certificateProvider(Option.none())
            .configProvider(Option.none())
            .environment(Option.none()).managementHttpProtocol(org.pragmatica.aether.config.HttpProtocol.H1).storageConfig(HermeticStorage.nodeStorageIn(storageRoot, false)).build();
    }

    private static Object component(Object instance, String name) throws ReflectiveOperationException {
        var method = instance.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(instance);
    }

    private static Object field(Object instance, String name) throws ReflectiveOperationException {
        var field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }
}

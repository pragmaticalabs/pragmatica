// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.node.health.CoreSwimHealthDetector;
import org.pragmatica.aether.stream.replication.ReplicationMessage;
import org.pragmatica.aether.stream.replication.ReplicationReceiveHandler;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.net.BootTokens;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.serialization.FrameworkCodecs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1697: the replica boot token the stream replication receive handler stamps on every ack is THIS node's own boot token (the one
/// the node's `BootTokens` registry holds as `self`, drawn once per assembly), handed from `AetherNode` through
/// `StreamForwardingInputs` to `StreamAssembly`. The handler is read from the routing table the ASSEMBLED, unstarted node installed
/// (its `ReplicateEvents` route), and the supplier it acks with is read; the source scan in `OperatorWarningWiringTest` pins only
/// that a supplier is passed, not its value. Mutations: `StreamAssembly` `var bootToken = in.bootToken()` -> `0L`; `AetherNode`
/// `StreamForwardingInputs(..., bootToken)` -> `0L`.
class StreamReplicaBootTokenWiringBootTest {
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

    private AetherNodeConfig config(NodeId self) {
        return AetherNodeConfig.builder().self(self)
            .coreNodes(List.of(NodeInfo.nodeInfo(self, NodeAddress.nodeAddress("localhost", 6123).unwrap(), Map.of(NodeInfo.LABEL_ROLE, "core")),
                              NodeInfo.nodeInfo(new NodeId("core-seed"), NodeAddress.nodeAddress("localhost", 6124).unwrap(), Map.of(NodeInfo.LABEL_ROLE, "core"))))
            .managementPort(AetherNodeConfig.MANAGEMENT_DISABLED)
            .sliceConfig(org.pragmatica.aether.config.SliceConfig.sliceConfig())
            .artifactRepo(org.pragmatica.dht.DHTConfig.FULL).coreMax(1)
            .appHttp(AppHttpConfig.appHttpConfig()).tls(Option.none())
            .quicTls(TlsConfig.selfSignedMutual()).certificateProvider(Option.none())
            .configProvider(Option.none())
            .environment(Option.none()).managementHttpProtocol(org.pragmatica.aether.config.HttpProtocol.H1).storageConfig(HermeticStorage.nodeStorageIn(storageRoot, false)).build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void anAssembledCore_acksWithItsOwnBootToken() throws ReflectiveOperationException {
        var router = MessageRouter.DelegateRouter.delegate();

        node = AetherNode.aetherNode(config(new NodeId("ack-token-" + UUID.randomUUID())),
                                     router,
                                     NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs()),
                                     () -> {}).unwrap();

        var routing = (Map<Class<?>, List<Consumer<?>>>) component(field(router, "delegate"), "routingTable");
        var handler = findHandler(routing.get(ReplicationMessage.ReplicateEvents.class));
        var acksWith = ((LongSupplier) field(handler, "bootToken")).getAsLong();
        var nodeToken = ((CoreSwimHealthDetector) component(node, "swimHealthDetector")).bootTokens()
                                                                                    .map(BootTokens::self)
                                                                                    .or(0L);

        assertThat(nodeToken).as("control: the node drew a real token").isNotEqualTo(0L);
        assertThat(acksWith).as("the replica acks with the node's own boot token").isEqualTo(nodeToken);
    }

    /// The route's consumer is a method reference bound to the handler; the handler is the first `ReplicationReceiveHandler`
    /// reachable from the consumer's captured state.
    private static ReplicationReceiveHandler findHandler(List<Consumer<?>> consumers) throws ReflectiveOperationException {
        assertThat(consumers).as("the ReplicateEvents route is installed").isNotNull().isNotEmpty();

        var pending = new ArrayDeque<Object>(consumers);
        var seen = new java.util.IdentityHashMap<Object, Boolean>();

        while (!pending.isEmpty()) {
            var next = pending.poll();

            if (next instanceof ReplicationReceiveHandler found) {
                return found;
            }
            if (seen.put(next, true) == null) {
                for (var captured : capturedState(next)) {
                    pending.add(captured);
                }
            }
        }
        throw new AssertionError("no ReplicationReceiveHandler behind the ReplicateEvents route");
    }

    private static List<Object> capturedState(Object holder) throws ReflectiveOperationException {
        var state = new java.util.ArrayList<Object>();

        for (var field : holder.getClass().getDeclaredFields()) {
            if (!field.getType().isPrimitive() && !field.getType().getName().startsWith("java.lang") && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                var value = field.get(holder);

                if (value != null) {
                    state.add(value);
                }
            }
        }
        return state;
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

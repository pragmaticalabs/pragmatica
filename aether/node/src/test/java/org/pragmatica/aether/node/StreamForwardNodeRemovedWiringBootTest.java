// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.AppHttpConfig;
import org.pragmatica.aether.config.SliceConfig;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.config.ConfigService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.serialization.FrameworkCodecs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2583 P2: a `MembershipDecision.NodeRemoved` reaches the stream forward handler's `onNodeRemoved`, which closes the removed
/// requester's open unsent-reply episode. The handler is read from the routing table the ASSEMBLED, unstarted node installed (the
/// `NodeRemoved` route `StreamAssembly.streamForwarding` adds), an episode is opened on it, and the route's own consumer is
/// invoked with a real `NodeRemoved`. The handler-level test pins the method; this pins that the event reaches it.
/// Mutation: delete the `MembershipDecision.NodeRemoved.class` route in `StreamAssembly.streamForwarding`.
class StreamForwardNodeRemovedWiringBootTest {
    private static final String HANDLER_CLASS = "DefaultStreamForwardHandler";

    @TempDir java.nio.file.Path storageRoot;
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
            .sliceConfig(SliceConfig.sliceConfig())
            .artifactRepo(DHTConfig.FULL).coreMax(1)
            .appHttp(AppHttpConfig.appHttpConfig()).tls(Option.none())
            .quicTls(TlsConfig.selfSignedMutual()).certificateProvider(Option.none())
            .configProvider(Option.none())
            .environment(Option.none()).managementHttpProtocol(org.pragmatica.aether.config.HttpProtocol.H1).storageConfig(HermeticStorage.nodeStorageIn(storageRoot, false)).build();
    }

    @Test
    @SuppressWarnings("unchecked")
    void nodeRemovedEvent_reachesTheForwardHandler_andEvictsTheRequestersEpisode() throws ReflectiveOperationException {
        var router = MessageRouter.DelegateRouter.delegate();

        node = AetherNode.aetherNode(config(new NodeId("node-removed-" + UUID.randomUUID())),
                                     router,
                                     NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs()),
                                     () -> {}).unwrap();

        var routing = (Map<Class<?>, List<Consumer<?>>>) component(field(router, "delegate"), "routingTable");
        var consumers = routing.get(MembershipDecision.NodeRemoved.class);

        assertThat(consumers).as("the NodeRemoved route is installed").isNotNull().isNotEmpty();

        var requester = new NodeId("departing-requester");
        var routed = routeReachingHandler(consumers);
        var handler = routed.handler();
        var unsent = (Set<NodeId>) field(handler, "unsentRequesters");
        var open = handler.getClass().getDeclaredMethod("countUnsentReply", NodeId.class, String.class);

        open.setAccessible(true);
        open.invoke(handler, requester, "control");
        assertThat(unsent).as("control: the episode is open before the event").contains(requester);

        ((Consumer<MembershipDecision.NodeRemoved>) routed.consumer()).accept(MembershipDecision.nodeRemoved(requester, List.of()));

        assertThat(unsent).as("the NodeRemoved event closed the departed requester's episode").doesNotContain(requester);
    }

    private record Routed(Consumer<?> consumer, Object handler) {}

    /// The route's consumer is a method reference bound to the handler; the handler is the `DefaultStreamForwardHandler`
    /// reachable from the consumer's captured state.
    private static Routed routeReachingHandler(List<Consumer<?>> consumers) throws ReflectiveOperationException {
        for (var consumer : consumers) {
            var handler = findHandler(consumer);

            if (handler != null) {
                return new Routed(consumer, handler);
            }
        }
        throw new AssertionError("no " + HANDLER_CLASS + " behind the NodeRemoved route");
    }

    private static Object findHandler(Object root) throws ReflectiveOperationException {
        var pending = new ArrayDeque<Object>();
        var seen = new IdentityHashMap<Object, Boolean>();

        pending.add(root);
        while (!pending.isEmpty()) {
            var next = pending.poll();

            if (next.getClass().getSimpleName().equals(HANDLER_CLASS)) {
                return next;
            }
            if (!isJdkType(next) && seen.put(next, true) == null) {
                pending.addAll(capturedState(next));
            }
        }
        return null;
    }

    private static boolean isJdkType(Object value) {
        var name = value.getClass().getName();

        return name.startsWith("java.") || name.startsWith("jdk.") || name.startsWith("sun.");
    }

    private static List<Object> capturedState(Object holder) throws ReflectiveOperationException {
        var state = new ArrayList<Object>();

        for (var field : holder.getClass().getDeclaredFields()) {
            if (!field.getType().isPrimitive() && !field.getType().getName().startsWith("java.lang") && !Modifier.isStatic(field.getModifiers())) {
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

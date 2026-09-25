// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.net.tcp.NodeAddress;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class EmberSelfDrainSlotReleaseTest {
    @Test
    @SuppressWarnings("unchecked")
    void selfDrainReclaimsEveryRegistryAndReturnsSlotOnceAfterStop() throws ReflectiveOperationException {
        var cluster = EmberCluster.emberCluster(1, 45100, 45200, "self-drain");
        var id = new NodeId("self-drain-worker");
        var stop = Promise.<Unit>promise();
        var stops = new AtomicInteger();
        var node = (AetherNode) Proxy.newProxyInstance(AetherNode.class.getClassLoader(), new Class<?>[] { AetherNode.class },
            (_, method, _) -> switch (method.getName()) {
                case "self" -> id;
                case "stop" -> { stops.incrementAndGet(); yield stop; }
                case "toString" -> id.id();
                default -> null;
            });
        cluster.adoptNode(NodeInfo.nodeInfo(id, NodeAddress.nodeAddress("localhost", 45100).unwrap()), node);
        var slots = (Map<String, Integer>) field(cluster, "slotsByNodeId");
        var available = (Queue<Integer>) field(cluster, "availableSlots");
        var tags = (Map<String, Map<String, String>>) field(cluster, "instanceTags");
        var infos = (Map<String, NodeInfo>) field(cluster, "nodeInfos");
        available.clear();
        slots.put(id.id(), 0);
        tags.put(id.id(), Map.of("aether-source", "east"));
        var selfDrain = EmberCluster.class.getDeclaredMethod("handleSelfDrain", String.class);
        selfDrain.setAccessible(true);

        selfDrain.invoke(cluster, id.id());
        selfDrain.invoke(cluster, id.id());
        cluster.killNode(id.id()).await(timeSpan(5).seconds());

        assertThat(stops).hasValue(1);
        assertThat(cluster.getNode(id.id()).isEmpty()).isTrue();
        assertThat(infos).doesNotContainKey(id.id());
        assertThat(tags).doesNotContainKey(id.id());
        assertThat(slots).doesNotContainKey(id.id());
        assertThat(available).as("ports cannot be reused while stop is outstanding").isEmpty();
        var released = Promise.<Unit>promise();
        Thread.startVirtualThread(() -> {
            var deadline = System.nanoTime() + timeSpan(5).seconds().nanos();
            while (available.isEmpty() && System.nanoTime() < deadline) {
                java.util.concurrent.locks.LockSupport.parkNanos(timeSpan(1).millis().nanos());
            }
            released.succeed(Unit.unit());
        });
        stop.succeed(Unit.unit());
        assertThat(released.await(timeSpan(6).seconds()).isSuccess()).isTrue();
        assertThat(available).containsExactly(0);
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        var field = EmberCluster.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}

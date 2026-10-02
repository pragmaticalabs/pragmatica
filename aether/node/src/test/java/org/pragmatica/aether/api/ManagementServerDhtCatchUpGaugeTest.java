// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.api;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Modifier;
import java.net.ServerSocket;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.stubbing.Answer;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.TimeoutsConfig.ForwardingTimeouts;
import org.pragmatica.aether.http.security.SecurityValidator;
import org.pragmatica.aether.invoke.InvocationTraceStore;
import org.pragmatica.aether.invoke.ScheduledTaskManager;
import org.pragmatica.aether.invoke.ScheduledTaskRegistry;
import org.pragmatica.aether.invoke.ScheduledTaskStateRegistry;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.resource.entity.EntityCheckpointDriver;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.dht.ConsistentHashRing;
import org.pragmatica.dht.DHTConfig;
import org.pragmatica.dht.DHTNode;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.pragmatica.dht.storage.MemoryStorageEngine.memoryStorageEngine;

/// #1777 (K4): pins the `ManagementServer` registration of `aether.dht.catchup.stuck.partitions`. A started
/// management server must expose the gauge in its meter registry, reading the node's own `DHTNode`. Red with
/// `registerDhtCatchUpMetrics()` removed from `start()`: the gauge is then absent.
class ManagementServerDhtCatchUpGaugeTest {
    private static final String GAUGE = "aether.dht.catchup.stuck.partitions";
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(30).seconds();

    /// Unstubbed collaborators answer `Option.none()` for optional parts and a nested mock for the rest, as in
    /// `ManagementServerStopDuringBindTest`: the constructor wires every route source.
    private static final Answer<Object> NONE_OR_MOCK = invocation -> {
        var type = invocation.getMethod().getReturnType();

        if (type == Option.class) {
            return Option.none();
        }

        var fallback = RETURNS_DEFAULTS.answer(invocation);

        if (fallback != null || type.isPrimitive() || type.isSealed() || Modifier.isFinal(type.getModifiers())) {
            return fallback;
        }

        return mock(type, ManagementServerDhtCatchUpGaugeTest.NONE_OR_MOCK);
    };

    private ManagementServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop().await(START_BOUND);
        }
    }

    @Test
    @Timeout(60)
    void startedServer_exposesTheDhtCatchUpStuckGauge_readingTheNodesDht() {
        var dhtNode = catchingUpDhtNode();

        server = managementServer(dhtNode);
        var started = server.start().await(START_BOUND);

        assertThat(started.isSuccess()).as("control: the server started: %s", started).isTrue();

        var gauge = server.meterRegistry().find(GAUGE).gauge();

        assertThat(gauge).as("the started server registered %s", GAUGE).isNotNull();
        assertThat(gauge.value()).as("it reads the node's DHT").isEqualTo(dhtNode.stuckCatchUpPartitions());
    }

    /// A real DHT node that has just booted, so it holds pending partitions.
    private static DHTNode catchingUpDhtNode() {
        var self = new NodeId("gauge-node");
        var ring = ConsistentHashRing.<NodeId>consistentHashRing();

        ring.addNode(self);

        var node = DHTNode.dhtNode(self, memoryStorageEngine(), ring, DHTConfig.DEFAULT);

        node.beginCatchUp();

        return node;
    }

    private static ManagementServer managementServer(DHTNode dhtNode) {
        var node = mock(ManageableNode.class, NONE_OR_MOCK);

        when(node.dhtNode()).thenReturn(Option.some(dhtNode));
        // Registered just before the DHT gauge; a final type the default answer leaves null.
        when(node.streamPartitionManager()).thenReturn(mock(StreamPartitionManager.class));

        return ManagementServer.managementServer(freePort(),
                                                 () -> node,
                                                 mock(EntityCheckpointDriver.class, NONE_OR_MOCK),
                                                 mock(AlertManager.class, NONE_OR_MOCK),
                                                 mock(ObservabilityConfigRegistry.class, NONE_OR_MOCK),
                                                 mock(InvocationTraceStore.class, NONE_OR_MOCK),
                                                 mock(LogLevelRegistry.class, NONE_OR_MOCK),
                                                 Option.none(),
                                                 mock(ScheduledTaskRegistry.class, NONE_OR_MOCK),
                                                 mock(ScheduledTaskManager.class, NONE_OR_MOCK),
                                                 mock(SliceInvoker.class, NONE_OR_MOCK),
                                                 mock(ScheduledTaskStateRegistry.class, NONE_OR_MOCK),
                                                 Option.none(),
                                                 mock(SecurityValidator.class, NONE_OR_MOCK),
                                                 false,
                                                 Map::of,
                                                 Option.none(),
                                                 Option.none(),
                                                 HttpProtocol.H1,
                                                 ForwardingTimeouts.forwardingTimeouts(),
                                                 Option.none(),
                                                 Option.none(),
                                                 Option.none(),
                                                 _ -> {},
                                                 Set::of);
    }

    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

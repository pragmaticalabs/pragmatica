// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.api;

import io.netty.buffer.ByteBuf;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Modifier;
import java.net.ServerSocket;
import java.util.List;
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
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.DispatcherFailureSink;
import org.pragmatica.cluster.state.kvstore.HeldDelivery;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.cluster.state.kvstore.NotificationDelivery;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// D1 item 10 (KVStore dispatcher design §3.6): pins the `ManagementServer` registration of the node store's delivery metrics. A
/// started management server must expose every `aether.kv.dispatch.*` gauge, reading the node's own store. Red with
/// `registerKvDispatchMetrics()` removed from `start()`: the gauges are then absent.
class ManagementServerKvDispatchGaugeTest {
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(30).seconds();
    private static final List<String> GAUGES = List.of("aether.kv.dispatch.lag.batches",
                                                       "aether.kv.dispatch.lag.oldest.age.ms",
                                                       "aether.kv.dispatch.queue.depth",
                                                       "aether.kv.dispatch.queue.high.water",
                                                       "aether.kv.dispatch.queue.bytes",
                                                       "aether.kv.dispatch.readahead.reads",
                                                       "aether.kv.dispatch.readahead.snapshot.reads",
                                                       "aether.kv.dispatch.slow.handlers",
                                                       "aether.kv.dispatch.slow.handler.max.nanos");

    /// Unstubbed collaborators answer `Option.none()` for optional parts and a nested mock for the rest, as in
    /// `ManagementServerDhtCatchUpGaugeTest`.
    private static final Answer<Object> NONE_OR_MOCK = invocation -> {
        var type = invocation.getMethod().getReturnType();

        if (type == Option.class) {
            return Option.none();
        }

        var fallback = RETURNS_DEFAULTS.answer(invocation);

        if (fallback != null || type.isPrimitive() || type.isSealed() || Modifier.isFinal(type.getModifiers())) {
            return fallback;
        }

        return mock(type, ManagementServerKvDispatchGaugeTest.NONE_OR_MOCK);
    };

    private final HeldDelivery hold = HeldDelivery.heldDelivery();
    private final KVStore<AetherKey, AetherValue> store = new KVStore<>(MessageRouter.mutable(),
                                                                        new Serializer() {
                                                                            @Override
                                                                            public <T> void write(ByteBuf buffer, T value) {}
                                                                        },
                                                                        new Deserializer() {
                                                                            @Override
                                                                            public <T> T read(ByteBuf buffer) {
                                                                                return null;
                                                                            }
                                                                        },
                                                                        NotificationDelivery.dispatched("gauge-node",
                                                                                                        DispatcherFailureSink.loggingOnly(),
                                                                                                        hold));
    private ManagementServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop().await(START_BOUND);
        }

        hold.release();
        store.close();
    }

    @Test
    @Timeout(60)
    void startedServer_exposesTheKvDispatchGauges_readingTheNodesStore() {
        hold.holdFrom(store.enqueuedSeq() + 1);
        put();
        put();
        assertThat(hold.awaitHeld(10)).as("control: the dispatcher is held, so two batches are undelivered").isTrue();

        server = managementServer(store);
        var started = server.start().await(START_BOUND);

        assertThat(started.isSuccess()).as("control: the server started: %s", started).isTrue();
        for (var name : GAUGES) {
            assertThat(server.meterRegistry().find(name).gauge()).as("the started server registered %s", name).isNotNull();
        }

        assertThat(server.meterRegistry().find("aether.kv.dispatch.lag.batches").gauge().value()).as("it reads the node's store")
                                                                                                 .isEqualTo(2.0);
        assertThat(server.meterRegistry().find("aether.kv.dispatch.queue.high.water").tag("phase", "replay").gauge())
            .as("the high-water mark is read per phase").isNotNull();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void put() {
        store.process(store.createBatch(List.of(new KVCommand.Put(LeaderKey.INSTANCE, new LeaderValue(new NodeId("gauge-node"), 1)))));
    }

    private static ManagementServer managementServer(KVStore<AetherKey, AetherValue> store) {
        var node = mock(ManageableNode.class, NONE_OR_MOCK);

        when(node.kvStore()).thenReturn(store);
        // Registered before the KV gauges; a final type the default answer leaves null.
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
                                                 Set::of,
                                                 org.pragmatica.aether.api.routes.NodeLifecycleRoutes.SliceFloor.sliceFloor((_, _) -> java.util.List.of(),
                                                                                                                         org.pragmatica.utility.warning.OperatorWarningSink.logOnly()));
    }

    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

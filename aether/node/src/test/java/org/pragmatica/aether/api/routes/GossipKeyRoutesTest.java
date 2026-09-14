// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.netty.buffer.ByteBuf;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.ManagementRoutePermissions;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GossipKeyRotationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GossipKeyRotationValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.pragmatica.aether.http.handler.security.RoutePermission.ADMIN_ONLY;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #683 (CTO ruling: WIRE). `GossipKeyRotationKey` had a wired consumer on every node
/// (`GossipKeyRotationHandler`, replayed to late joiners) and NO producer: the emergency, in-place
/// gossip-key rotation the design comment calls "the sole delivery path" could not be invoked by
/// anyone. `POST /api/v1/cluster/gossip-key/rotate` is that producer: one consensus Put of a fresh
/// 32-byte key, with the previous record's key carried for the decrypt overlap.
class GossipKeyRoutesTest {
    private static final String ROUTES_LOGGER = "org.pragmatica.aether.api.routes.GossipKeyRoutes";

    private ManageableNode node;
    private KVStore<AetherKey, AetherValue> kvStore;
    private final List<KVCommand<AetherKey>> applied = new ArrayList<>();
    private CapturingAppender appender;
    private LoggerConfig loggerConfig;
    private Level originalLevel;

    @BeforeEach
    void setUp() {
        node = mock(ManageableNode.class);
        kvStore = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        when(node.kvStore()).thenReturn(kvStore);
        when(node.<Object> apply(anyList())).thenAnswer(invocation -> {
            List<KVCommand<AetherKey>> commands = invocation.getArgument(0);

            applied.addAll(commands);
            kvStore.process(kvStore.createBatch(commands));

            return org.pragmatica.lang.Promise.success(List.of());
        });

        appender = CapturingAppender.create("GossipKeyRoutesCapture");
        appender.start();
        var ctx = (LoggerContext) LogManager.getContext(false);
        var configuration = ctx.getConfiguration();
        var existing = configuration.getLoggerConfig(ROUTES_LOGGER);
        loggerConfig = ROUTES_LOGGER.equals(existing.getName())
                       ? existing
                       : new LoggerConfig(ROUTES_LOGGER, Level.DEBUG, false);
        if (loggerConfig != existing) {
            configuration.addLogger(ROUTES_LOGGER, loggerConfig);
        }
        originalLevel = loggerConfig.getLevel();
        loggerConfig.addAppender(appender, Level.TRACE, null);
        loggerConfig.setLevel(Level.TRACE);
        ctx.updateLoggers();
    }

    @AfterEach
    void tearDown() {
        var ctx = (LoggerContext) LogManager.getContext(false);
        loggerConfig.removeAppender(appender.getName());
        loggerConfig.setLevel(originalLevel);
        ctx.updateLoggers();
        appender.stop();
    }

    @Test
    void theRouteExists_andIsAnExactAdminRow() {
        assertThat(ManagementRoute.CLUSTER_GOSSIP_KEY_ROTATE.prefix()).contains("cluster/gossip-key/rotate");
        assertThat(ManagementRoutePermissions.permissionFor(ManagementRoute.CLUSTER_GOSSIP_KEY_ROTATE))
                .as("#683: an exact ADMIN row, never the prefix fallback (#1101)")
                .isEqualTo(ADMIN_ONLY);
    }

    @Test
    void firstRotation_putsAFresh32ByteKey_withNoPrevious_throughConsensus() {
        var response = GossipKeyRoutes.gossipKeyRoutes(() -> node).rotate().await(timeSpan(5).seconds()).unwrap();

        assertThat(applied).as("exactly one consensus write").hasSize(1);
        var value = rotationValueOf(applied.getFirst());
        assertThat(value.currentKeyId()).isEqualTo(1);
        assertThat(Base64.getDecoder().decode(value.currentKey())).hasSize(32);
        assertThat(value.hasPreviousKey()).isFalse();
        assertThat(response.currentKeyId()).isEqualTo(1);
        assertThat(response.previousKeyId()).isEqualTo(0);
    }

    @Test
    void secondRotation_carriesThePreviousKeyForOverlap_andIncrementsTheId() {
        var routes = GossipKeyRoutes.gossipKeyRoutes(() -> node);

        routes.rotate().await(timeSpan(5).seconds()).unwrap();
        var first = rotationValueOf(applied.getFirst());

        routes.rotate().await(timeSpan(5).seconds()).unwrap();

        assertThat(applied).hasSize(2);
        var second = rotationValueOf(applied.get(1));
        assertThat(second.currentKeyId()).isEqualTo(2);
        assertThat(second.previousKeyId()).isEqualTo(1);
        assertThat(second.previousKey()).as("the previous key rides along so peers mid-rotation still decrypt")
                                        .isEqualTo(first.currentKey());
        assertThat(second.currentKey()).isNotEqualTo(first.currentKey());
    }

    /// The key bytes must never reach a log line: count of lines containing the Base64 key = 0,
    /// with the key id as the positive control that the route DID log the rotation.
    @Test
    void rotation_neverLogsTheKeyMaterial() {
        GossipKeyRoutes.gossipKeyRoutes(() -> node).rotate().await(timeSpan(5).seconds()).unwrap();
        var value = rotationValueOf(applied.getFirst());

        assertThat(appender.lines()).as("control: the rotation is logged by key id")
                                    .anyMatch(line -> line.contains("keyId=1"));
        assertThat(appender.lines()).as("#683: 0 lines carry the key material")
                                    .noneMatch(line -> line.contains(value.currentKey()));
    }

    private static GossipKeyRotationValue rotationValueOf(KVCommand<AetherKey> command) {
        assertThat(command).isInstanceOf(KVCommand.Put.class);
        var put = (KVCommand.Put<AetherKey, ?>) command;
        assertThat(put.key()).isInstanceOf(GossipKeyRotationKey.class);

        return (GossipKeyRotationValue) put.value();
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final List<String> lines = new CopyOnWriteArrayList<>();

        private CapturingAppender(String name, Layout<?> layout) {
            super(name, (Filter) null, layout, true, Property.EMPTY_ARRAY);
        }

        static CapturingAppender create(String name) {
            return new CapturingAppender(name, PatternLayout.createDefaultLayout());
        }

        @Override public void append(LogEvent event) {
            lines.add(event.getMessage().getFormattedMessage());
        }

        List<String> lines() {
            return List.copyOf(lines);
        }
    }
}

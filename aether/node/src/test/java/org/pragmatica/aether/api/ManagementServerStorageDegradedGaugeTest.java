// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.api;

import java.lang.reflect.Modifier;
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
import org.pragmatica.aether.node.ClusterTestPorts;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.resource.entity.EntityCheckpointDriver;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.lang.Option;
import org.pragmatica.aether.node.StorageFactory.StorageSetup;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.storage.DemotionManager;
import org.pragmatica.storage.PrefetchManager;
import org.pragmatica.storage.PromotionManager;
import org.pragmatica.storage.SnapshotManager;
import org.pragmatica.storage.StorageCondition;
import org.pragmatica.storage.StorageCondition.Kind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// A0b: the `ManagementServer` registers `aether.storage.degraded` per storage instance, reading how many conditions that
/// need an operator the instance holds now (what boot left standing plus what its managers report). Red with
/// `registerStorageDegradedMetrics()` removed from `onServerStarted`: the gauge is then absent.
class ManagementServerStorageDegradedGaugeTest {
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

        return mock(type, ManagementServerStorageDegradedGaugeTest.NONE_OR_MOCK);
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
    void startedServer_exposesTheDegradedGaugePerInstance_countingBootConditionsAndManagerConditions() {
        var demotion = mock(DemotionManager.class);
        var snapshots = mock(SnapshotManager.class);

        when(demotion.conditions()).thenReturn(List.of(StorageCondition.storageCondition(Kind.DEMOTION_REFUSED, "full")));
        when(snapshots.conditions()).thenReturn(List.of());

        var degraded = new StorageSetup("artifacts", null, snapshots, null, null, demotion, null, Option.none(), Option.empty(),
                                        PromotionManager.NOOP, PrefetchManager.NOOP,
                                        List.of(StorageCondition.storageCondition(Kind.DISK_TIER_UNAVAILABLE, "no disk")));
        var healthy = new StorageSetup("content", null, mock(SnapshotManager.class), null, null, mock(DemotionManager.class), null, Option.none(),
                                       Option.empty(), PromotionManager.NOOP, PrefetchManager.NOOP, List.of());

        server = managementServer(Map.of("artifacts", degraded, "content", healthy));

        var started = server.start().await(START_BOUND);

        assertThat(started.isSuccess()).as("control: the server started: %s", started).isTrue();
        assertThat(gauge("aether.storage.degraded", "artifacts")).as("one boot condition plus one from the demotion manager").isEqualTo(2.0);
        assertThat(gauge("aether.storage.degraded", "content")).isEqualTo(0.0);
    }

    private double gauge(String name, String instance) {
        var gauge = server.meterRegistry().find(name).tag("instance", instance).gauge();

        assertThat(gauge).as("the started server registered %s for %s", name, instance).isNotNull();

        return gauge.value();
    }

    private static ManagementServer managementServer(Map<String, StorageSetup> setups) {
        var node = mock(ManageableNode.class, NONE_OR_MOCK);

        when(node.storageSetups()).thenReturn(setups);
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
        return ClusterTestPorts.freeTcpAndUdpPort();
    }
}

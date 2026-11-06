// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.api;

import java.lang.reflect.Modifier;
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
import org.pragmatica.lang.io.TimeSpan;


import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// #2201 A-14, the author pre-check of PR-2a: the started `ManagementServer` exposes `aether.streams.seams.unbound.safety`, reading
/// the node's partition manager ([StreamPartitionManager#unboundSafetySeamCount]). Red with the gauge registration removed from
/// `registerStreamMemoryMetrics`, or with its supplier reading anything but the manager.
class ManagementServerStreamSeamGaugeTest {
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(30).seconds();

    private static final Answer<Object> NONE_OR_MOCK = invocation -> {
        var type = invocation.getMethod().getReturnType();

        if (type == Option.class) {
            return Option.none();
        }

        var fallback = RETURNS_DEFAULTS.answer(invocation);

        if (fallback != null || type.isPrimitive() || type.isSealed() || Modifier.isFinal(type.getModifiers())) {
            return fallback;
        }

        return mock(type, ManagementServerStreamSeamGaugeTest.NONE_OR_MOCK);
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
    void startedServer_exposesTheUnboundSafetySeamGauge_readingThePartitionManager() {
        var spm = mock(StreamPartitionManager.class);

        when(spm.unboundSafetySeamCount()).thenReturn(3);
        server = managementServer(spm);

        var started = server.start().await(START_BOUND);

        assertThat(started.isSuccess()).as("control: the server started: %s", started).isTrue();

        var gauge = server.meterRegistry().find("aether.streams.seams.unbound.safety").gauge();

        assertThat(gauge).as("the started server registered the gauge").isNotNull();
        assertThat(gauge.value()).as("it reads the manager's count").isEqualTo(3.0);
    }

    private static ManagementServer managementServer(StreamPartitionManager spm) {
        var node = mock(ManageableNode.class, NONE_OR_MOCK);

        when(node.storageSetups()).thenReturn(Map.of());
        when(node.streamPartitionManager()).thenReturn(spm);

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

// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.api;

import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.pragmatica.aether.api.routes.NodeLifecycleRoutes;
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
import org.pragmatica.lang.Option;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #3754: `ForwardReplyResender` is a field of `ManagementServerImpl`, so only the server's own `stop()` can end the
/// lifetime of the reply re-sends. Driven through the real `stop()` of a built (not started) server; the control reads the
/// resender live before the stop. The collaborators are mocked as in `ManagementServerStopDuringBindTest`: none is reached.
class ManagementServerStopEndsReplyResenderTest {
    private static final Answer<Object> NONE_OR_MOCK = new Answer<>() {
        @Override
        public Object answer(InvocationOnMock invocation) throws Throwable {
            var type = invocation.getMethod().getReturnType();

            if (type == Option.class) {
                return Option.none();
            }
            var fallback = RETURNS_DEFAULTS.answer(invocation);

            if (fallback != null || type.isPrimitive() || type.isSealed() || Modifier.isFinal(type.getModifiers())) {
                return fallback;
            }

            return mock(type, this);
        }
    };

    @Test
    @Timeout(60)
    void stop_endsTheReplyResendLifetime() {
        var server = (ManagementServerImpl) managementServer();
        var resender = server.replyResenderForTest();

        assertThat(resender.isStopped()).as("control: the resender is live before stop()").isFalse();

        server.stop().await(timeSpan(30).seconds());

        assertThat(resender.isStopped()).as("stop() must end the reply re-send lifetime").isTrue();
    }

    private static ManagementServer managementServer() {
        var node = mock(ManageableNode.class, NONE_OR_MOCK);

        return ManagementServer.managementServer(0,
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
                                                 NodeLifecycleRoutes.DrainPort.none(),
                                                 NodeLifecycleRoutes.SliceFloor.sliceFloor((_, _) -> List.of(),
                                                                                           OperatorWarningSink.logOnly()));
    }
}

// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.api;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.net.ServerSocket;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;

/// #1456 — the management listener's publish point, pinned the way `AppHttpServerStopDuringBindTest` pins the
/// app listener's. A bind completing after `stop()` has already run must be closed by the publisher that loses
/// the race, never left bound with nothing owning it. Seen in CI as `EmberClusterSwimStartFailureTest` failing on
/// management port 31742, which bound 2 ms after its node's stop and was never stopped.
///
/// **Deterministic by program order.** `beforePublishForTest` runs between the bind completing and the server
/// being published to the slot. The test runs the WHOLE of `stop()` there. `stop()` closes the slot inline, so
/// by the time the gate returns the publish that follows necessarily finds it closed. There is no sleep, latch
/// or second thread to lose a race with, and the verdict is by consequence: the TCP port must be rebindable.
///
/// Two controls keep a green from being vacuous. The gate records that the port is genuinely BOUND while it
/// runs, and it records that `stop()` was called inside the window at all.
///
/// The server is built with its collaborators mocked: none of them is reached on this path, but the
/// constructor wires every route source, so the node supplier answers `Option.none()` for optional parts and
/// a nested mock for the rest.
class ManagementServerStopDuringBindTest {
    /// Its own port, clear of `AppHttpServerStopDuringBindTest`'s 18131 and of every computed range in this module.
    private static final int TEST_PORT = 18133;
    private static final long RECLAIM_WAIT_MS = 10_000;
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(30).seconds();

    private static final Answer<Object> NONE_OR_MOCK = new Answer<>() {
        @Override
        public Object answer(org.mockito.invocation.InvocationOnMock invocation) throws Throwable {
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
    void stop_releasesThePort_whenTheBindLandsAfterStopHasRun() {
        var server = managementServerOnTestPort();
        var boundAtGate = new AtomicBoolean();
        var stopAtGate = new AtomicReference<Promise<Unit>>();

        ((ManagementServerImpl) server).beforePublishForTest(() -> {
            boundAtGate.set(!rebindable(TEST_PORT, 0));
            stopAtGate.set(server.stop());
        });

        server.start().await(START_BOUND);

        assertThat(boundAtGate.get())
            .as("control: TCP %d must already be held when the gate runs, or this test would be asserting the "
                + "release of a port that was never taken", TEST_PORT)
            .isTrue();
        assertThat(stopAtGate.get())
            .as("control: the gate must have run, or no stop() happened inside the bind window")
            .isNotNull();
        assertThat(rebindable(TEST_PORT, RECLAIM_WAIT_MS))
            .as("TCP %d must be reclaimable within %d ms: a bind landing after stop() must be closed by whoever "
                + "publishes it, not left bound with nothing owning it", TEST_PORT, RECLAIM_WAIT_MS)
            .isTrue();
    }

    private static ManagementServer managementServerOnTestPort() {
        var node = mock(ManageableNode.class, NONE_OR_MOCK);

        return ManagementServer.managementServer(TEST_PORT,
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

    /// Closing a channel can trail the stop promise by a few milliseconds, so poll — bounded. A zero budget
    /// makes this a single immediate probe, which is how the gate's control reads the port.
    private static boolean rebindable(int port, long budgetMs) {
        var deadline = System.nanoTime() + budgetMs * 1_000_000L;

        while (true) {
            if (bindable(port)) {
                return true;
            }
            if (System.nanoTime() >= deadline) {
                return false;
            }
            sleepQuietly();
        }
    }

    private static boolean bindable(int port) {
        try (var socket = new ServerSocket(port)) {
            return socket.isBound();
        } catch (IOException taken) {
            return false;
        }
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

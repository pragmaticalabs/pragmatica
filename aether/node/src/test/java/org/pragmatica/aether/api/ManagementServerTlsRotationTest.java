// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.api;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;

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
import org.pragmatica.aether.http.TlsProbe;
import org.pragmatica.http.server.HttpServerError;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.TlsConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;

/// A certificate rotation that cannot build its TLS material is refused BEFORE the running management listener is touched:
/// it keeps serving its current certificate over TLS (never plain HTTP, never nothing), the refusal is typed, and the operator
/// event is raised once and recovered when a later rotation applies. The client side is a real TLS handshake and HTTPS request.
class ManagementServerTlsRotationTest {
    private static final TimeSpan BOUND = TimeSpan.timeSpan(60).seconds();
    private static final long EVENT_BUDGET_MS = 5_000;

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
    @Timeout(120)
    void rotate_unbuildableBundle_keepsServingCurrentCertificateAndRaisesOnceThenRecovers() throws Exception {
        var port = TlsProbe.freeTcpPort();
        var events = TlsProbe.newEventList();
        var server = managementServerWithTls(port);

        server.setOperatorWarningSink(TlsProbe.recordingSink(events));
        try {
            server.start().await(BOUND);
            assertThat(TlsProbe.eventually(() -> reachable(port), EVENT_BUDGET_MS)).as("control: TLS listener is up").isTrue();
            var original = TlsProbe.presentedCertificate(port);

            var refused = server.rotateCertificate(TlsProbe.garbageBundle()).await(BOUND);

            assertThat(refused.isFailure()).as("the rotation is refused").isTrue();
            refused.onFailure(cause -> assertThat(cause).isInstanceOf(HttpServerError.TlsRotationRefused.class));
            assertThat(TlsProbe.sameCertificate(original, TlsProbe.presentedCertificate(port)))
                .as("the listener still presents its CURRENT certificate").isTrue();
            assertThat(TlsProbe.httpsStatus(port)).as("a real HTTPS request is still served").isPositive();
            assertThat(TlsProbe.plainHttpAnswered(port)).as("no plain-HTTP fallback").isFalse();
            assertThat(TlsProbe.eventually(() -> TlsProbe.countOf(events, "http-tls-rotation-refused") == 1, EVENT_BUDGET_MS))
                .as("the refusal is raised as an operator event").isTrue();

            server.rotateCertificate(TlsProbe.garbageBundle()).await(BOUND);
            Thread.sleep(300);
            assertThat(TlsProbe.countOf(events, "http-tls-rotation-refused")).as("a repeated refusal is not re-raised").isEqualTo(1);
            assertThat(TlsProbe.countOf(events, "http-tls-rotation-restored")).as("no recovery while still refused").isZero();

            var applied = server.rotateCertificate(TlsProbe.validBundle("mgmt-rotation-node")).await(BOUND);

            assertThat(applied.isSuccess()).as("a valid bundle rotates").isTrue();
            assertThat(TlsProbe.sameCertificate(original, TlsProbe.presentedCertificate(port)))
                .as("after the valid rotation the certificate changed").isFalse();
            assertThat(TlsProbe.httpsStatus(port)).isPositive();
            assertThat(TlsProbe.eventually(() -> TlsProbe.countOf(events, "http-tls-rotation-restored") == 1, EVENT_BUDGET_MS))
                .as("the recovery event follows the later successful rotation").isTrue();
        } finally {
            server.stop().await(BOUND);
        }
    }

    private static boolean reachable(int port) {
        try {
            TlsProbe.presentedCertificate(port);

            return true;
        } catch (Exception notYet) {
            return false;
        }
    }

    private static ManagementServer managementServerWithTls(int port) {
        var node = mock(ManageableNode.class, NONE_OR_MOCK);

        // The post-bind metrics registration dereferences the stream partition manager.
        org.mockito.Mockito.when(node.streamPartitionManager())
                           .thenReturn(mock(org.pragmatica.aether.stream.StreamPartitionManager.class, NONE_OR_MOCK));

        return ManagementServer.managementServer(port,
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
                                                 Option.some(TlsConfig.selfSignedServer()),
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

}

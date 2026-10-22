// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.stubbing.Answer;
import org.pragmatica.aether.config.HttpProtocol;
import org.pragmatica.aether.config.TimeoutsConfig.ForwardingTimeouts;
import org.pragmatica.aether.http.H3Probe;
import org.pragmatica.aether.http.TlsProbe;
import org.pragmatica.aether.http.security.SecurityValidator;
import org.pragmatica.aether.invoke.InvocationTraceStore;
import org.pragmatica.aether.invoke.ScheduledTaskManager;
import org.pragmatica.aether.invoke.ScheduledTaskRegistry;
import org.pragmatica.aether.invoke.ScheduledTaskStateRegistry;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.resource.entity.EntityCheckpointDriver;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.TlsConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.mock;

/// #2310: the management HTTP/3 listener completes a request from a standard HTTP/3 client, which offers the `h3` ALPN
/// token. Each production path that builds the QUIC context is covered: self-signed (no TLS configured), a configured
/// certificate, and the context rebuilt by certificate rotation. The mirror of `AppHttpServerH3AlpnTest`.
class ManagementServerH3AlpnTest {
    private static final TimeSpan BOUND = TimeSpan.timeSpan(30).seconds();
    private static final String STATUS = "\\d{3}";

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
    void h3Listener_selfSigned_servesAStandardHttp3Client() throws Exception {
        assertThat(statusAfterStart(TlsProbe.freeTcpPort(), Option.none())).as("an HTTP/3 client gets an HTTP status")
                                                                          .matches(STATUS);
    }

    @Test
    @Timeout(120)
    void h3Listener_configuredCertificate_servesAStandardHttp3Client() throws Exception {
        assertThat(statusAfterStart(TlsProbe.freeTcpPort(), Option.some(TlsConfig.selfSignedServer()))).matches(STATUS);
    }

    @Test
    @Timeout(180)
    void h3Listener_afterCertificateRotation_stillServesAStandardHttp3Client() throws Exception {
        var port = TlsProbe.freeTcpPort();
        var server = managementServer(port, Option.some(TlsConfig.selfSignedServer()));

        try {
            assertThat(server.start().await(BOUND).isSuccess()).as("control: the server starts").isTrue();
            assertThat(server.rotateCertificate(TlsProbe.validBundle("h3-alpn-mgmt")).await(TimeSpan.timeSpan(60).seconds())
                             .isSuccess()).as("control: the rotation applies").isTrue();
            assertThat(H3Probe.getStatus(port)).as("the rotated HTTP/3 listener serves").matches(STATUS);
        } finally {
            server.stop().await(BOUND);
        }
    }

    private static String statusAfterStart(int port, Option<TlsConfig> tls) throws Exception {
        var server = managementServer(port, tls);

        try {
            assertThat(server.start().await(BOUND).isSuccess()).as("control: the server starts").isTrue();

            return H3Probe.getStatus(port);
        } finally {
            server.stop().await(BOUND);
        }
    }

    private static ManagementServer managementServer(int port, Option<TlsConfig> tls) {
        var node = mock(ManageableNode.class, NONE_OR_MOCK);

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
                                                 tls,
                                                 mock(SecurityValidator.class, NONE_OR_MOCK),
                                                 false,
                                                 Map::of,
                                                 Option.none(),
                                                 Option.none(),
                                                 HttpProtocol.H3,
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

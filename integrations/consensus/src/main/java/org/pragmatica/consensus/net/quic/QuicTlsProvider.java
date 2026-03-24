/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.consensus.net.quic;

import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.net.tcp.QuicSslContextFactory;
import org.pragmatica.net.tcp.TlsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Provides QUIC TLS context for cluster transport.
///
/// QUIC mandates TLS 1.3 — there is no plaintext mode. When no TLS configuration
/// is provided (development/Forge), ephemeral self-signed certificates are
/// generated automatically for zero-config startup.
///
/// Cluster transport uses a custom ALPN protocol identifier ("aether-cluster/1")
/// to distinguish from HTTP/3 traffic on the same QUIC layer.
public sealed interface QuicTlsProvider {
    Logger log = LoggerFactory.getLogger(QuicTlsProvider.class);

    /// ALPN protocol identifier for Aether cluster transport.
    String CLUSTER_PROTOCOL = "aether-cluster/1";

    /// Obtain a QUIC server SSL context for cluster transport.
    /// Uses the provided TLS config if present, otherwise auto-generates self-signed certs.
    static Result<QuicSslContext> serverContext(Option<TlsConfig> tlsConfig) {
        return tlsConfig.fold(
            QuicTlsProvider::createSelfSignedServer,
            QuicSslContextFactory::createServer
        );
    }

    /// Obtain a QUIC client SSL context for cluster transport.
    /// Uses insecure trust for self-signed certs (development), or configured trust for production.
    static Result<QuicSslContext> clientContext(Option<TlsConfig> tlsConfig) {
        return tlsConfig.fold(
            QuicTlsProvider::createInsecureClient,
            QuicSslContextFactory::createClient
        );
    }

    /// Self-signed server context with cluster ALPN protocol.
    @SuppressWarnings({"deprecation", "JBCT-UTIL-01"}) // SelfSignedCertificate is for dev/testing
    private static Result<QuicSslContext> createSelfSignedServer() {
        try {
            var ssc = new SelfSignedCertificate();
            var context = QuicSslContextBuilder.forServer(ssc.privateKey(), null, ssc.certificate())
                                               .applicationProtocols(CLUSTER_PROTOCOL)
                                               .build();
            return Result.success(context);
        } catch (Exception e) {
            return new QuicTransportError.TlsContextCreationFailed(e).result();
        }
    }

    /// Insecure client context with cluster ALPN protocol (trusts all certificates).
    @SuppressWarnings("JBCT-UTIL-01")
    private static Result<QuicSslContext> createInsecureClient() {
        try {
            log.warn("Creating insecure QUIC client context - FOR DEVELOPMENT ONLY!");
            var context = QuicSslContextBuilder.forClient()
                                               .trustManager(InsecureTrustManagerFactory.INSTANCE)
                                               .applicationProtocols(CLUSTER_PROTOCOL)
                                               .build();
            return Result.success(context);
        } catch (Exception e) {
            return new QuicTransportError.TlsContextCreationFailed(e).result();
        }
    }

    record Unused() implements QuicTlsProvider {}
}

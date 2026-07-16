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

import org.pragmatica.lang.Result;
import org.pragmatica.net.tcp.QuicSslContextFactory;
import org.pragmatica.net.tcp.TlsConfig;

import io.netty.handler.codec.quic.QuicSslContext;


/// Provides QUIC TLS context for cluster transport.
///
/// QUIC mandates TLS 1.3 — there is no plaintext mode. Every node must be started
/// with a resolved [TlsConfig] so cluster transport uses a deterministic, shared
/// certificate authority (typically derived from an `AETHER_CLUSTER_SECRET`).
///
/// Cluster transport uses a custom ALPN protocol identifier ("aether-cluster/1")
/// to distinguish from HTTP/3 traffic on the same QUIC layer.
public sealed interface QuicTlsProvider {
    /// ALPN protocol identifier for Aether cluster transport.
    String CLUSTER_PROTOCOL = "aether-cluster/1";

    /// Obtain a QUIC server SSL context for cluster transport.
    static Result<QuicSslContext> serverContext(TlsConfig tlsConfig) {
        return QuicSslContextFactory.createServer(tlsConfig, CLUSTER_PROTOCOL);
    }

    /// Obtain a QUIC client SSL context for cluster transport.
    static Result<QuicSslContext> clientContext(TlsConfig tlsConfig) {
        return QuicSslContextFactory.createClient(tlsConfig, CLUSTER_PROTOCOL);
    }

    record Unused() implements QuicTlsProvider {}
}

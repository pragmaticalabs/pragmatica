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
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.net.tcp.QuicSslContextFactory;
import org.pragmatica.net.tcp.TlsConfig;

/// Provides QUIC TLS context, auto-generating self-signed certs when no config is present.
///
/// QUIC mandates TLS 1.3 — there is no plaintext mode. When no TLS configuration
/// is provided (development/Forge), ephemeral self-signed certificates are
/// generated automatically for zero-config startup.
public sealed interface QuicTlsProvider {

    /// Obtain a QUIC server SSL context.
    /// Uses the provided TLS config if present, otherwise auto-generates self-signed certs.
    static Result<QuicSslContext> serverContext(Option<TlsConfig> tlsConfig) {
        return tlsConfig.fold(
            QuicSslContextFactory::createSelfSignedServer,
            QuicSslContextFactory::createServer
        );
    }

    record Unused() implements QuicTlsProvider {}
}

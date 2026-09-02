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
package org.pragmatica.net.tcp;

/// Whether a QUIC server demands a certificate from the connecting peer (#715).
///
/// ## Why this is an explicit parameter rather than inferred
///
/// It cannot be derived from the [`TlsConfig`] variant. `TlsConfig.Mutual` would be the natural
/// discriminator — and it is wrong: `Main` hands the SAME `Mutual` instance to the cluster
/// transport, the app HTTP/3 server and the management HTTP/3 server. Browsers, the CLI and the
/// dashboard hold no cluster certificate, so inferring REQUIRED from `Mutual` would reject every
/// operator client at handshake. The variant describes the key material available; it says nothing
/// about which transport is being built.
///
/// So the policy travels as its own argument, and every server-context call site has to state one.
/// That is the point: a wrong choice is a word a reviewer reads in the diff, rather than an
/// omission that looks identical to correct code — which is exactly how #715 survived, since
/// Netty's `QuicSslContextBuilder` silently defaults to `ClientAuth.NONE`.
public enum ClientAuthPolicy {
    /// Demand a peer certificate and verify it against the configured trust anchor. Use for
    /// CLUSTER transport, where every peer holds a certificate from the shared cluster CA.
    REQUIRED,

    /// Do not ask the peer for a certificate. Use for operator-facing HTTP/3 surfaces, whose
    /// clients authenticate by other means (API keys) and have no cluster certificate.
    NOT_REQUESTED
}

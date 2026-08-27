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

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.utils.Causes;


/// Error types for QUIC transport operations.
///
/// Pilot migration of the typed-error construction idiom (`core/docs/typed-error-construction.md`):
/// fixed-text failures are `General` constants; data-carrying failures are records with a trailing
/// `message` component built through their `FACTORY`; variants wrapping an underlying failure
/// implement [Cause.Wrapped], so the cause chain survives into `source()`.
public sealed interface QuicTransportError extends Cause {
    /// Fixed-message transport errors.
    enum General implements QuicTransportError {
        HELLO_TIMEOUT("Hello handshake timed out"),
        UNEXPECTED_MESSAGE("Expected Hello message but received different type"),
        SERVER_NOT_STARTED("QUIC server is not started"),
        NO_TLS_CONFIGURATION("No TLS configuration provided. Set AETHER_INSECURE_DEV_MODE=true for development without TLS verification");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }

    /// Failed to close a QUIC connection.
    record ConnectionCloseFailed(Cause origin, String message) implements QuicTransportError, Cause.Wrapped {
        static final Fn1<ConnectionCloseFailed, Cause> FACTORY = Causes.forOneValue("Failed to close QUIC connection: %s",
                                                                                    ConnectionCloseFailed::new);
    }

    /// Failed to bind the QUIC server to a UDP port.
    record BindFailed(int port, Cause origin, String message) implements QuicTransportError, Cause.Wrapped {
        static final Fn2<BindFailed, Integer, Cause> FACTORY = Causes.forTwoValues("Failed to bind QUIC server to port %s: %s",
                                                                                   BindFailed::new);
    }

    /// Failed to connect to a remote QUIC peer.
    record ConnectFailed(String address, Cause origin, String message) implements QuicTransportError, Cause.Wrapped {
        static final Fn2<ConnectFailed, String, Cause> FACTORY = Causes.forTwoValues("Failed to connect to QUIC peer at %s: %s",
                                                                                     ConnectFailed::new);
    }

    /// Peer address could not be resolved to an IP (e.g. stale/unknown DNS name).
    /// Clean, retryable dial failure — distinct from a Netty-level connect failure.
    record UnresolvedAddress(String address, String message) implements QuicTransportError {
        static final Fn1<UnresolvedAddress, String> FACTORY = Causes.forOneValue("Cannot connect to QUIC peer: address is unresolved or missing: %s",
                                                                                 UnresolvedAddress::new);
    }

    /// Dialer-side Hello identity verification failed (cluster-topology-overhaul spec, Wave 3):
    /// the Hello sender's claimed identity did not match the dialed identity. The connection is
    /// closed un-attached and the dial fails down the normal connect-failure path (backoff and
    /// eviction engage as for any failed dial). A misdirected dial (e.g. DNS re-resolution
    /// landing on whatever answers) can no longer attach under the wrong identity.
    ///
    /// The factory is hand-rolled, not a `forXValues` rung: the rendered form is `NodeId.id()`,
    /// not `NodeId.toString()`, and a `%s` template cannot express that. Custom value rendering
    /// is a second reason to hand-roll, alongside the spec's above-the-ceiling case.
    ///
    /// @param expected the NodeId the dial targeted
    /// @param actual   the NodeId the Hello response claimed
    /// @param address  the remote address the dial actually resolved to
    record IdentityMismatch(NodeId expected, NodeId actual, String address, String message) implements QuicTransportError {
        static IdentityMismatch identityMismatch(NodeId expected, NodeId actual, String address) {
            return new IdentityMismatch(expected,
                                        actual,
                                        address,
                                        "QUIC dialer Hello identity mismatch: dialed=" + expected.id()
                                       + " helloSender=" + actual.id()
                                       + " address=" + address
                                       + " — connection rejected");
        }
    }

    /// Failed to create a QUIC stream.
    record StreamCreationFailed(Cause origin, String message) implements QuicTransportError, Cause.Wrapped {
        static final Fn1<StreamCreationFailed, Cause> FACTORY = Causes.forOneValue("Failed to create QUIC stream: %s",
                                                                                   StreamCreationFailed::new);
    }

    /// Failed to rotate TLS certificates on the QUIC server.
    record CertificateRotationFailed(String detail, String message) implements QuicTransportError {
        static final Fn1<CertificateRotationFailed, String> FACTORY = Causes.forOneValue("Certificate rotation failed: %s",
                                                                                         CertificateRotationFailed::new);
    }
}

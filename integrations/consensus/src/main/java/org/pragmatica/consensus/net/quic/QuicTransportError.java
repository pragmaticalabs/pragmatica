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
import org.pragmatica.lang.utils.Causes;

/// Error types for QUIC transport operations.
public sealed interface QuicTransportError extends Cause {

    /// Fixed-message transport errors.
    enum General implements QuicTransportError {
        HELLO_TIMEOUT("Hello handshake timed out"),
        UNEXPECTED_MESSAGE("Expected Hello message but received different type"),
        SERVER_NOT_STARTED("QUIC server is not started"),
        NO_TLS_CONFIGURATION("No TLS configuration provided. Set AETHER_INSECURE_DEV_MODE=true for development without TLS verification");

        private final String text;

        General(String text) {
            this.text = text;
        }

        @Override
        public String message() {
            return text;
        }
    }

    /// Failed to close a QUIC connection.
    record ConnectionCloseFailed(Throwable cause) implements QuicTransportError {
        @Override
        public String message() {
            return "Failed to close QUIC connection: " + Causes.fromThrowable(cause);
        }
    }

    /// Failed to create QUIC TLS context.
    record TlsContextCreationFailed(Throwable cause) implements QuicTransportError {
        @Override
        public String message() {
            return "Failed to create QUIC TLS context: " + Causes.fromThrowable(cause);
        }
    }

    /// Failed to bind the QUIC server to a UDP port.
    record BindFailed(int port, Throwable cause) implements QuicTransportError {
        @Override
        public String message() {
            return "Failed to bind QUIC server to port " + port + ": " + Causes.fromThrowable(cause);
        }
    }

    /// Failed to connect to a remote QUIC peer.
    record ConnectFailed(String address, Throwable cause) implements QuicTransportError {
        @Override
        public String message() {
            return "Failed to connect to QUIC peer at " + address + ": " + Causes.fromThrowable(cause);
        }
    }

    /// Peer address could not be resolved to an IP (e.g. stale/unknown DNS name).
    /// Clean, retryable dial failure — distinct from a Netty-level connect failure.
    record UnresolvedAddress(String address) implements QuicTransportError {
        @Override
        public String message() {
            return "Cannot connect to QUIC peer: address is unresolved or missing: " + address;
        }
    }

    /// Dialer-side Hello identity verification failed (cluster-topology-overhaul spec, Wave 3):
    /// the Hello sender's claimed identity did not match the dialed identity. The connection is
    /// closed un-attached and the dial fails down the normal connect-failure path (backoff and
    /// eviction engage as for any failed dial). A misdirected dial (e.g. DNS re-resolution
    /// landing on whatever answers) can no longer attach under the wrong identity.
    ///
    /// @param expected the NodeId the dial targeted
    /// @param actual   the NodeId the Hello response claimed
    /// @param address  the remote address the dial actually resolved to
    record IdentityMismatch(NodeId expected, NodeId actual, String address) implements QuicTransportError {
        @Override
        public String message() {
            return "QUIC dialer Hello identity mismatch: dialed=" + expected.id()
                   + " helloSender=" + actual.id()
                   + " address=" + address + " — connection rejected";
        }
    }

    /// Failed to create a QUIC stream.
    record StreamCreationFailed(Throwable cause) implements QuicTransportError {
        @Override
        public String message() {
            return "Failed to create QUIC stream: " + Causes.fromThrowable(cause);
        }
    }

    /// Failed to rotate TLS certificates on the QUIC server.
    record CertificateRotationFailed(String detail) implements QuicTransportError {
        @Override
        public String message() {
            return "Certificate rotation failed: " + detail;
        }
    }
}

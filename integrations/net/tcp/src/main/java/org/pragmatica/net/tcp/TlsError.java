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

import java.nio.file.Path;

import org.pragmatica.lang.Cause;


/// Error types for TLS operations.
public sealed interface TlsError extends Cause {
    /// Failed to load certificate from file.
    record CertificateLoadFailed(Path path, Throwable cause) implements TlsError {
        @Override
        public String message() {
            return "Failed to load certificate from " + path + ": " + cause.getMessage();
        }
    }

    /// Failed to load private key from file.
    record PrivateKeyLoadFailed(Path path, Throwable cause) implements TlsError {
        @Override
        public String message() {
            return "Failed to load private key from " + path + ": " + cause.getMessage();
        }
    }

    /// Failed to load CA certificate (trust store) from file.
    record TrustStoreLoadFailed(Path path, Throwable cause) implements TlsError {
        @Override
        public String message() {
            return "Failed to load CA certificate from " + path + ": " + cause.getMessage();
        }
    }

    /// Failed to generate self-signed certificate.
    record SelfSignedGenerationFailed(Throwable cause) implements TlsError {
        @Override
        public String message() {
            return "Failed to generate self-signed certificate: " + cause.getMessage();
        }
    }

    /// Failed to build SSL context.
    record ContextBuildFailed(Throwable cause) implements TlsError {
        @Override
        public String message() {
            return "Failed to build SSL context: " + cause.getMessage();
        }
    }

    /// Invalid TLS mode for the requested operation.
    record WrongMode(String details) implements TlsError {
        @Override
        public String message() {
            return "Invalid TLS mode: " + details;
        }
    }

    /// Create a WrongMode error with the given details.
    static TlsError wrongMode(String details) {
        return new WrongMode(details);
    }

    /// A server's TLS configuration (`side`: `server` for incoming, `client` for outgoing connections) could not be built,
    /// so the server refuses to start rather than run in plain text.
    record ServerTlsRefused(String serverName, String side, Cause cause) implements TlsError {
        @Override
        public String message() {
            return "TLS (" + side
                 + ") configuration of server '" + serverName
                 + "' failed to build: " + cause.message()
                 + "; refusing to start without TLS";
        }
    }

    /// The private key does not belong to the certificate: each half is valid, so a TLS context would build and then
    /// complete no handshake.
    record KeyDoesNotMatchCertificate() implements TlsError {
        @Override
        public String message() {
            return "the private key does not match the certificate's public key";
        }
    }

    static TlsError keyDoesNotMatchCertificate() {
        return new KeyDoesNotMatchCertificate();
    }
}

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

package org.pragmatica.net.tcp.security;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;

/// Error types for certificate provider operations.
public sealed interface CertificateProviderError extends Cause {

    /// Failed to generate CA certificate and keypair.
    record CaGenerationFailed(Throwable cause) implements CertificateProviderError {
        @Override
        public String message() {
            return "CA generation failed: " + Causes.fromThrowable(cause).message();
        }
    }

    /// Failed to issue a node certificate.
    record CertificateIssueFailed(String nodeId, Throwable cause) implements CertificateProviderError {
        @Override
        public String message() {
            return "Certificate issue failed for node " + nodeId + ": " + Causes.fromThrowable(cause).message();
        }
    }

    /// Failed to derive encryption key.
    record KeyDerivationFailed(Throwable cause) implements CertificateProviderError {
        @Override
        public String message() {
            return "Key derivation failed: " + Causes.fromThrowable(cause).message();
        }
    }
}

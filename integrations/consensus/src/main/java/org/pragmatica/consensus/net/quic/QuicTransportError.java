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

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;

/// Error types for QUIC transport operations.
public sealed interface QuicTransportError extends Cause {

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
}

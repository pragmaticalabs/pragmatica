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

package org.pragmatica.swim;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.utils.Causes;

/// Error types for the SWIM protocol module.
public sealed interface SwimError extends Cause {

    /// Fixed-message errors.
    enum General implements SwimError {
        TRANSPORT_NOT_STARTED("Transport has not been started"),
        PROTOCOL_ALREADY_RUNNING("Protocol is already running"),
        PROTOCOL_NOT_RUNNING("Protocol is not running"),
        NO_MEMBERS_AVAILABLE("No members available for probing");

        private final String message;

        General(String message) {
            this.message = message;
        }

        @Override
        public String message() {
            return message;
        }
    }

    /// Transport-level failure wrapping an underlying exception.
    record TransportFailure(Throwable cause) implements SwimError {
        @Override
        public String message() {
            return "Transport failure: " + Causes.fromThrowable(cause);
        }
    }

    /// Serialization failure.
    record SerializationFailure(Throwable cause) implements SwimError {
        @Override
        public String message() {
            return "Serialization failure: " + Causes.fromThrowable(cause);
        }
    }
}

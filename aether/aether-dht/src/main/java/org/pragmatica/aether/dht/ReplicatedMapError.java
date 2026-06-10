// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.dht;

import org.pragmatica.lang.Cause;


public sealed interface ReplicatedMapError extends Cause {
    record SerializationFailed(String detail) implements ReplicatedMapError {
        @Override
        public String message() {
            return "Serialization failed: " + detail;
        }
    }

    record DhtOperationFailed(Cause underlying) implements ReplicatedMapError {
        @Override
        public String message() {
            return "DHT operation failed: " + underlying.message();
        }
    }
}

// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;


public interface SliceBridge {
    Promise<byte[]> invoke(String methodName, byte[] input);
    Promise<Unit> start();
    Promise<Unit> stop();

    default Promise<byte[]> encode(Object input) {
        return BridgeError.ENCODE_NOT_SUPPORTED.promise();
    }

    default Promise<Object> decode(byte[] bytes) {
        return BridgeError.DECODE_NOT_SUPPORTED.promise();
    }

    ClassLoader classLoader();
    List<String> methodNames();

    enum BridgeError implements Cause {
        ENCODE_NOT_SUPPORTED("Encode not supported by this bridge"),
        DECODE_NOT_SUPPORTED("Decode not supported by this bridge");
        private final String message;
        BridgeError(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }
}

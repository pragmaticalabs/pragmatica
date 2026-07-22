// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ttm.error;

import org.pragmatica.lang.Cause;


public sealed interface TTMError extends Cause {
    record ModelLoadFailed(String path, String reason) implements TTMError {
        @Override
        public String message() {
            return "Failed to load TTM model from " + path + ": " + reason;
        }
    }

    record InferenceFailed(String reason) implements TTMError {
        @Override
        public String message() {
            return "TTM inference failed: " + reason;
        }
    }

    record InsufficientData(int available, int required) implements TTMError {
        @Override
        public String message() {
            return "Insufficient data for TTM prediction: " + available
                 + " minutes available, " + required
                 + " required";
        }
    }

    enum Disabled implements TTMError {
        INSTANCE;
        @Override
        public String message() {
            return "TTM is disabled in configuration";
        }
    }

    record UnexpectedOutputType(String actualType) implements TTMError {
        @Override
        public String message() {
            return "Unexpected output tensor type: " + actualType;
        }
    }

    enum NoProvider implements TTMError {
        INSTANCE;
        @Override
        public String message() {
            return "No TTM predictor implementation available. Add aether-ttm-onnx to classpath.";
        }
    }
}

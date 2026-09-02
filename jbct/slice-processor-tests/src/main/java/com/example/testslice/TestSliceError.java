// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package com.example.testslice;

import org.pragmatica.lang.Cause;


public sealed interface TestSliceError extends Cause {
    record NotFound(Long id) implements TestSliceError {
        @Override
        public String message() {
            return "Item not found: " + id;
        }
    }

    record InvalidInput(String field, String reason) implements TestSliceError {
        @Override
        public String message() {
            return "Invalid " + field + ": " + reason;
        }
    }

    record DuplicateEntry(String key) implements TestSliceError {
        @Override
        public String message() {
            return "Duplicate entry: " + key;
        }
    }
}

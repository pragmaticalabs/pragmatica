// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package com.example.collisionslice;

import org.pragmatica.lang.Cause;

/// Buy-side failures. Its nested `StoreUnavailable` deliberately shares a simple name with
/// [CancelError.StoreUnavailable] to exercise the generator's simple-name collision handling.
public sealed interface BuyError extends Cause {
    record StoreUnavailable(String reason) implements BuyError {
        @Override
        public String message() {
            return "buy store unavailable: " + reason;
        }
    }
}

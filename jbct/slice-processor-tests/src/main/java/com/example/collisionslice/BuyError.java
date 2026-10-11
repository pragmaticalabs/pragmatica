// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
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

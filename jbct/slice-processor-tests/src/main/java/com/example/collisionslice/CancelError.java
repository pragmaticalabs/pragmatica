// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.collisionslice;

import org.pragmatica.lang.Cause;


/// Cancel-side failures. Its nested `StoreUnavailable` deliberately shares a simple name with
/// [BuyError.StoreUnavailable] to exercise the generator's simple-name collision handling.
public sealed interface CancelError extends Cause {
    record StoreUnavailable(String reason) implements CancelError {
        @Override
        public String message() {
            return "cancel store unavailable: " + reason;
        }
    }
}

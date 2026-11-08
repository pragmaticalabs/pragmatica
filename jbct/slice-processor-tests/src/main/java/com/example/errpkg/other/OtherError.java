// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.errpkg.other;

import org.pragmatica.lang.Cause;


public sealed interface OtherError extends Cause {
    record NotAllowed(String action) implements OtherError {
        @Override
        public String message() {
            return "other not allowed: " + action;
        }
    }
}

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.errpkg.shared;

import org.pragmatica.lang.Cause;


public sealed interface SharedError extends Cause {
    record QuotaExceeded(String owner) implements SharedError {
        @Override
        public String message() {
            return "quota exceeded: " + owner;
        }
    }

    record NotAllowed(String action) implements SharedError {
        @Override
        public String message() {
            return "shared not allowed: " + action;
        }
    }
}

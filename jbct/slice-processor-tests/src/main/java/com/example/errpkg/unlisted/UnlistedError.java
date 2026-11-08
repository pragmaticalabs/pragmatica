// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.errpkg.unlisted;

import org.pragmatica.lang.Cause;


/// Deliberately absent from the slice's routes.toml packages list: its `*Gone*` pattern match must not apply.
public sealed interface UnlistedError extends Cause {
    record Gone(String what) implements UnlistedError {
        @Override
        public String message() {
            return "gone: " + what;
        }
    }
}

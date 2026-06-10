// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Cause;


public sealed interface SliceRuntimeError extends Cause {
    enum InvokerNotConfigured implements SliceRuntimeError {
        INSTANCE;
        @Override
        public String message() {
            return "SliceInvoker not configured. "
                 + "This typically means the slice is being used outside of the Aether runtime.";
        }
    }
}

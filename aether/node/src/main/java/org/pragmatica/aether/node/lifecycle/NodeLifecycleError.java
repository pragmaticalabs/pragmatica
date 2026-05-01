// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.lifecycle;

import org.pragmatica.lang.Cause;


public sealed interface NodeLifecycleError extends Cause {
    enum General implements NodeLifecycleError {
        NOT_ACTIVE("drain() requires ACTIVE state"),
        ALREADY_STOPPED("Node lifecycle already STOPPED");
        private final String message;
        General(String message) {
            this.message = message;
        }
        @Override public String message() {
            return message;
        }
    }
}

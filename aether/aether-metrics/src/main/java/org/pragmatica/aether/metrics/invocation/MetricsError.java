// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics.invocation;

import org.pragmatica.lang.Cause;


public sealed interface MetricsError extends Cause {
    enum StrategyChangeNotSupported implements MetricsError {
        INSTANCE;
        @Override public String message() {
            return "Strategy change at runtime requires collector recreation";
        }
    }
}

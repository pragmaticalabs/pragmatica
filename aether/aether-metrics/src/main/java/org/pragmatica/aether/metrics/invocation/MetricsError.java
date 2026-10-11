// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.metrics.invocation;

import org.pragmatica.lang.Cause;


public sealed interface MetricsError extends Cause {
    enum StrategyChangeNotSupported implements MetricsError {
        INSTANCE;
        @Override
        public String message() {
            return "Strategy change at runtime requires collector recreation";
        }
    }
}

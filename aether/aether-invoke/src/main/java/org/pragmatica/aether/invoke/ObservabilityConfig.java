// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

public record ObservabilityConfig(int depthThreshold, int targetTracesPerSec) {
    public static final ObservabilityConfig DEFAULT = new ObservabilityConfig(1, 500);

    public static ObservabilityConfig observabilityConfig(int depthThreshold, int targetTracesPerSec) {
        return new ObservabilityConfig(depthThreshold, targetTracesPerSec);
    }
}

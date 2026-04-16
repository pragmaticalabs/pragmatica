// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.delegation;

import org.pragmatica.serialization.Codec;


/// Identifies a co-location group of control plane components.
/// Each group is assigned to exactly one core node at a time.
@Codec public enum TaskGroup {
    METRICS,
    SCALING,
    STRATEGIES,
    DEPLOYMENT,
    STORAGE,
    STREAMING
}

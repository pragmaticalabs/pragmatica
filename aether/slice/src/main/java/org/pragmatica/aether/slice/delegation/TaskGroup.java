// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.delegation;

import org.pragmatica.serialization.Codec;


@Codec
public enum TaskGroup {
    METRICS,
    SCALING,
    STRATEGIES,
    DEPLOYMENT,
    STORAGE,
    STREAMING,
    /// Wire sentinel (#964): an ordinal this node cannot name decodes here instead of throwing.
    /// Resolves to no owner, so forwarding fails closed as NotAssigned.
    /// Must stay LAST — a new constant appended after it, or inserted before it, is read as UNKNOWN
    /// by an older node either way.
    UNKNOWN
}

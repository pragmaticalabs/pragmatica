// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
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

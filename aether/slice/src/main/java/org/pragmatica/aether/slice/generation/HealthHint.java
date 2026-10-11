// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.generation;

import org.pragmatica.serialization.Codec;


@Codec
public enum HealthHint {
    HEALTHY,
    SUSPECTED,
    FAULTY,
    /// Wire sentinel (#964): an ordinal this node cannot name decodes here instead of throwing.
    /// Counted as SUSPECTED, never HEALTHY: an unreadable hint must not clear a member.
    /// Must stay LAST — a new constant appended after it, or inserted before it, is read as UNKNOWN
    /// by an older node either way.
    UNKNOWN
}

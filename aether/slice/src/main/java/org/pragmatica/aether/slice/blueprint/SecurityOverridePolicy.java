// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.serialization.Codec;


@Codec
public enum SecurityOverridePolicy {
    STRENGTHEN_ONLY,
    FULL,
    NONE,
    /// Wire sentinel (#964): an ordinal this node cannot name decodes here instead of throwing.
    /// Rejects every override -- an unreadable policy must never authorise weakening a route.
    /// Must stay LAST -- a new constant appended after it, or inserted before it, is read as UNKNOWN
    /// by an older node either way.
    UNKNOWN;
    public static SecurityOverridePolicy fromString(String raw) {
        return switch (raw.toLowerCase()
                          .strip()) {
            case "strengthen_only" -> STRENGTHEN_ONLY;
            case "full" -> FULL;
            case "none" -> NONE;
            default -> STRENGTHEN_ONLY;
        };
    }
}

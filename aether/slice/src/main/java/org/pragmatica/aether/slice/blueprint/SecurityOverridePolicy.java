// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.serialization.Codec;


/// Policy controlling how blueprint security overrides are applied to route security.
///
/// - STRENGTHEN_ONLY: override applied only if new security level >= original level
/// - FULL: any override is applied regardless of direction
/// - NONE: all overrides are rejected (logged as warnings)
@Codec public enum SecurityOverridePolicy {
    STRENGTHEN_ONLY,
    FULL,
    NONE;
    public static SecurityOverridePolicy fromString(String raw) {
        return switch (raw.toLowerCase().strip()){
            case "strengthen_only" -> STRENGTHEN_ONLY;
            case "full" -> FULL;
            case "none" -> NONE;
            default -> STRENGTHEN_ONLY;
        };
    }
}

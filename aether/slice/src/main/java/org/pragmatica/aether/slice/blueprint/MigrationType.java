// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.lang.Option;


public enum MigrationType {
    VERSIONED,
    REPEATABLE,
    UNDO,
    BASELINE;
    public static Option<MigrationType> migrationType(String filename) {
        if (filename == null || filename.isEmpty()) {
            return Option.none();
        }

        return switch (filename.charAt(0)) {
            case 'V' -> Option.some(VERSIONED);
            case 'R' -> Option.some(REPEATABLE);
            case 'U' -> Option.some(UNDO);
            case 'B' -> Option.some(BASELINE);
            default -> Option.none();
        };
    }
}

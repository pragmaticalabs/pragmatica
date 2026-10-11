// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.lang.Option;


public enum MigrationType {
    VERSIONED,
    REPEATABLE,
    UNDO,
    BASELINE;
    public static Option<MigrationType> migrationType(String filename) {
        if (Option.option(filename).map(String::isEmpty).or(true)) {
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

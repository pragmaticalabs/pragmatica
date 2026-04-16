// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.schema.model;

import org.pragmatica.lang.Option;


/// A sequence definition.
public record Sequence(String name,
                       String schema,
                       Option<String> dataType,
                       Option<Long> startValue,
                       Option<Long> increment,
                       Option<Long> minValue,
                       Option<Long> maxValue,
                       Option<Long> cache,
                       boolean cycle,
                       Option<String> ownedBy) {
    public static Sequence sequence(String name, String schema) {
        return new Sequence(name,
                            schema,
                            Option.empty(),
                            Option.empty(),
                            Option.empty(),
                            Option.empty(),
                            Option.empty(),
                            Option.empty(),
                            false,
                            Option.empty());
    }
}

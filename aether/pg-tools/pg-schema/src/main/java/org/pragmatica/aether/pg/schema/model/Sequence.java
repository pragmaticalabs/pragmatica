// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.pg.schema.model;

import org.pragmatica.lang.Option;


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

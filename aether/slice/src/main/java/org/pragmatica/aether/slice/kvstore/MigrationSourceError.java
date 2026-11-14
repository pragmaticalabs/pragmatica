// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.utils.Causes;


/// Why a raw migration-source string (`SchemaVersionValue.artifactCoords`) did not read as a [MigrationSource].
public sealed interface MigrationSourceError extends Cause {
    /// The string is null or blank: the record names no source.
    enum Absent implements MigrationSourceError {
        ABSENT;

        @Override
        public String message() {
            return "Migration source is absent";
        }
    }

    /// The string is neither artifact coordinates nor `set:<id>@<version>`. `raw` is kept so a sink can still print it.
    record Malformed(String raw, String message) implements MigrationSourceError {
        static final Fn1<Malformed, String> FACTORY = Causes.forOneValue("Malformed migration source '%s'", Malformed::new);

        public static Malformed malformed(String raw) {
            return FACTORY.apply(raw);
        }
    }
}

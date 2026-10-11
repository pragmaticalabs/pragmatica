// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.pg.codegen.processor;

import org.pragmatica.lang.Cause;


/// Failures raised while discovering and ordering PostgreSQL migration files.
public sealed interface MigrationDiscoveryError extends Cause {
    /// Two distinct files claim the same numeric version.
    record DuplicateVersion(int version, String existing, String duplicate) implements MigrationDiscoveryError {
        @Override
        public String message() {
            return "Duplicate migration version " + version + ": '" + existing + "' and '" + duplicate + "'";
        }
    }

    /// A migration-shaped file name whose version segment is not a valid number.
    record MalformedVersion(String fileName) implements MigrationDiscoveryError {
        @Override
        public String message() {
            return "Malformed migration file name '" + fileName + "': expected V<digits>__<name>.sql";
        }
    }
}

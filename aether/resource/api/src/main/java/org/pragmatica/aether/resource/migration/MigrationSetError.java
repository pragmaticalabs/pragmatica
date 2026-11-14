// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.migration;

import org.pragmatica.lang.Cause;


/// Typed refusals of the migration-set SPI values (#2100 E-B): one per value type, each naming the rule broken.
public sealed interface MigrationSetError extends Cause {
    static MigrationSetError invalidSetId(String value) {
        return new InvalidSetId(value);
    }

    static MigrationSetError invalidTarget(String section) {
        return new InvalidTarget(section);
    }

    static MigrationSetError invalidScript(String filename) {
        return new InvalidScript(filename);
    }

    /// The set id is outside `[a-z][a-z0-9-]{0,62}`. The id is embedded in the source string `set:<id>@<version>`, so
    /// it can hold neither `:` nor `@`.
    record InvalidSetId(String value) implements MigrationSetError {
        @Override
        public String message() {
            return "Migration set id '" + value + "' must match [a-z][a-z0-9-]{0,62}";
        }
    }

    /// The datasource section is blank or contains `@`, which separates the section from the schema in a schema key.
    record InvalidTarget(String section) implements MigrationSetError {
        @Override
        public String message() {
            return "Migration target datasource section '" + section + "' must be non-blank and contain no '@'";
        }
    }

    /// The script has a blank file name, a file name with a path separator, or blank SQL.
    record InvalidScript(String filename) implements MigrationSetError {
        @Override
        public String message() {
            return "Rendered script '" + filename + "' needs a plain, non-blank file name and non-blank SQL";
        }
    }
}

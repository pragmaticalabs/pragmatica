// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.migration;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.utils.Causes;


/// Typed refusals of the migration-set SPI values (#2100 E-B): one per value type, each naming the rule broken.
public sealed interface MigrationSetError extends Cause {
    static MigrationSetError invalidSetId(String value) {
        return InvalidSetId.FACTORY.apply(value);
    }

    static MigrationSetError invalidTarget(String section) {
        return InvalidTarget.FACTORY.apply(section);
    }

    static MigrationSetError invalidScript(String filename) {
        return InvalidScript.FACTORY.apply(filename);
    }

    /// The set id is outside `[a-z][a-z0-9-]{0,62}`. The id is embedded in the source string `set:<id>@<version>`, so
    /// it can hold neither `:` nor `@`.
    record InvalidSetId(String value, String message) implements MigrationSetError {
        static final Fn1<InvalidSetId, String> FACTORY = Causes.forOneValue("Migration set id '%s' must match [a-z][a-z0-9-]{0,62}",
                                                                            InvalidSetId::new);
    }

    /// The datasource section is blank or contains `@`, which separates the section from the schema in a schema key.
    record InvalidTarget(String section, String message) implements MigrationSetError {
        static final Fn1<InvalidTarget, String> FACTORY = Causes.forOneValue("Migration target datasource section '%s' must be non-blank and contain no '@'",
                                                                             InvalidTarget::new);
    }

    /// The script has a blank file name, a file name with a path separator, or blank SQL.
    record InvalidScript(String filename, String message) implements MigrationSetError {
        static final Fn1<InvalidScript, String> FACTORY = Causes.forOneValue("Rendered script '%s' needs a plain, non-blank file name and non-blank SQL",
                                                                             InvalidScript::new);
    }
}

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.migration;

import java.util.regex.Pattern;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;


/// The name of a migration set, e.g. `durable-entity-pg` (#2100 E-B, design §a.8).
///
/// Grammar `[a-z][a-z0-9-]{0,62}` `[guess: the design fixes the example id, not the grammar]`. It is deliberately
/// disjoint from `:` and `@`: the id travels inside the source string `set:<id>@<version>`, and `@` separates a
/// datasource section from its schema in a schema key.
public record MigrationSetId(String value) {
    private static final Pattern GRAMMAR = Pattern.compile("[a-z][a-z0-9-]{0,62}");

    public static Result<MigrationSetId> migrationSetId(String raw) {
        return Verify.ensure(raw,
                             Verify.Is::notNull,
                             MigrationSetError.invalidSetId(raw))
                     .flatMap(MigrationSetId::checkGrammar)
                     .map(MigrationSetId::new);
    }

    private static Result<String> checkGrammar(String raw) {
        return Verify.ensure(raw, Verify.Is::matches, GRAMMAR, MigrationSetError.invalidSetId(raw));
    }
}

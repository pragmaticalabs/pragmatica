// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Set;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.utils.Causes.cause;

/// Refuses the three spellings of `pool_scope` that bind to nothing (#2973, D4.2 = option c): `pool-scope`,
/// `poolScope` in the datasource section, and `pool_scope` under `pool_config`. `@StrictKeys` cannot be used on
/// [DatabaseConnectorConfig] because forge.toml's `[database]` carries Ember's own keys into the same composite, so the
/// three known misspellings are named instead; any other unknown key is ignored as before.
///
/// Reads the file-backed layers only ([ConfigurationProvider#staticKeys()]), the same scope `@StrictKeys` uses: an
/// environment variable or system property never fails a bind the file alone would have accepted.
public final class MisplacedPoolScope {
    private MisplacedPoolScope() {}

    public static Result<Unit> refuseMisplacedPoolScope(ConfigurationProvider provider, String section) {
        return refuseMisplacedPoolScope(provider.staticKeys(), section);
    }

    static Result<Unit> refuseMisplacedPoolScope(Set<String> staticKeys, String section) {
        var misplaced = List.of(section + ".pool-scope", section + ".poolScope", section + ".pool_config.pool_scope")
                            .stream()
                            .filter(staticKeys::contains)
                            .toList();

        return misplaced.isEmpty()
               ? success(Unit.unit())
               : cause("Datasource section [" + section + "] sets " + misplaced + ", which is not a key: `pool_scope` "
                       + "belongs in the datasource section itself (not in pool_config) and is spelled with an underscore")
                     .result();
    }
}

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.migration;

import org.pragmatica.aether.resource.db.PgSchemaName;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;


/// Where a migration set applies for one resource declaration: the datasource section and the PostgreSQL schema inside it
/// (#2100 E-B, design §a.8). The schema key is `<datasourceSection>@<schema>`, so the section holds no `@`.
public record MigrationTarget(String datasourceSection, PgSchemaName schema) {
    public static Result<MigrationTarget> migrationTarget(String datasourceSection, PgSchemaName schema) {
        return Verify.ensure(datasourceSection,
                             Verify.Is::present,
                             MigrationSetError.invalidTarget(datasourceSection))
                     .flatMap(MigrationTarget::checkNoSeparator)
                     .map(section -> new MigrationTarget(section, schema));
    }

    private static Result<String> checkNoSeparator(String section) {
        return Verify.ensure(section, Verify.Is::notContains, "@", MigrationSetError.invalidTarget(section));
    }
}

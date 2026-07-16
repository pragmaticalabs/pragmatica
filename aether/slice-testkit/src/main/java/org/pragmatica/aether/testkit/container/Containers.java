// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.testkit.container;

/// Entry point for testcontainer-backed resources (spec §5.2). Opt into a real backend per resource
/// with `.withContainer(type, section, Containers.postgres()...)`.
public sealed interface Containers {
    /// A PostgreSQL container backing a `@PgSql` `PgSqlConnector`, with optional `schema/` migrations.
    static PostgresContainerResource postgres() {
        return PostgresContainerResource.postgres();
    }

    record unused() implements Containers {}
}

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.migration;

import java.util.List;

import org.pragmatica.aether.resource.db.PgSchemaName;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// A library's append-only schema migrations for one resource type, discovered through `ServiceLoader`
/// (#2100 E-B, design §a.8). The orchestrator renders it per target schema and runs the scripts through the ordinary
/// schema migration path.
public interface ResourceMigrationSet {
    /// The set's name, e.g. `durable-entity-pg`.
    MigrationSetId id();
    /// The highest V this library renders.
    int version();
    /// The resource type the set serves, e.g. `DurableEntity`.
    String resourceType();
    /// Reads the declaration `section` through the node's configuration `view`: `none()` when the section does not ask for
    /// this set (a different backend), a [MigrationTarget] when it does, a failure when the declaration is refused.
    Result<Option<MigrationTarget>> target(ConfigurationProvider view, String section);
    /// Renders the scripts V1..[#version()] for `schema`, every statement qualified by the quoted schema name.
    Result<List<RenderedScript>> render(PgSchemaName schema);
}

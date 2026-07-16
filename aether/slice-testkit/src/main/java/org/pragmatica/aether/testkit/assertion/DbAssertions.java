// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.testkit.assertion;

import org.pragmatica.aether.resource.db.SqlConnector;


/// Row-assertion entry point for one DB section, backed by the real connector registered at that
/// section (typically the container path). Returned from `SliceUnderTest.db(section)`.
public final class DbAssertions {
    private final SqlConnector connector;

    private DbAssertions(SqlConnector connector) {
        this.connector = connector;
    }

    public static DbAssertions dbAssertions(SqlConnector connector) {
        return new DbAssertions(connector);
    }

    /// Run a query and expose its rows for assertions.
    public DbRows query(String sql, Object... params) {
        return DbRows.dbRows(connector, sql, params);
    }
}

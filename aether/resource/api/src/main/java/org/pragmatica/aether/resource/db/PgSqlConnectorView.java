// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

/// One slice's handle on a node-shared [PgSqlConnector] pool (#2973). [PgSqlConnector] adds no method to [SqlConnector],
/// so the release and forwarding rules are [SqlConnectorView]'s.
public final class PgSqlConnectorView extends SqlConnectorView implements PgSqlConnector {
    private PgSqlConnectorView(PgSqlConnector pool, Fn0<Promise<Unit>> release) {
        super(pool, release);
    }

    public static PgSqlConnector pgSqlConnectorView(PgSqlConnector pool, Fn0<Promise<Unit>> release) {
        return new PgSqlConnectorView(pool, release);
    }
}

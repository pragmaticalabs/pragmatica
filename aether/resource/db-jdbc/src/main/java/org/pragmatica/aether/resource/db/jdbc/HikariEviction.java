// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.jdbc;

import javax.sql.DataSource;
import java.sql.Connection;

import org.pragmatica.lang.Unit;

import com.zaxxer.hikari.HikariDataSource;

import static org.pragmatica.lang.Unit.unit;


/// Destroying a checked-out connection through HikariCP. HikariCP is an OPTIONAL dependency of this module, so the
/// connector asks [#owns] by class NAME (which loads nothing) and only then reaches the Hikari-typed [#evict], keeping
/// a classpath without HikariCP free of linkage errors.
final class HikariEviction {
    private static final String HIKARI_DATA_SOURCE = "com.zaxxer.hikari.HikariDataSource";

    private HikariEviction() {}

    static boolean owns(DataSource dataSource) {
        return HIKARI_DATA_SOURCE.equals(dataSource.getClass().getName());
    }

    /// `evictConnection` on a connection that has not been closed is immediate: the physical connection is closed and
    /// the pool entry removed. (`Connection.abort` is NOT enough on Hikari: it only marks the entry, which then stays
    /// checked out.)
    static Unit evict(DataSource dataSource, Connection connection) {
        ((HikariDataSource) dataSource).evictConnection(connection);

        return unit();
    }
}

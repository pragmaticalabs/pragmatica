// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.db.jdbc;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Promise;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;


public final class JdbcSqlConnectorFactory implements ResourceFactory<SqlConnector, DatabaseConnectorConfig> {
    @Override public Class<SqlConnector> resourceType() {
        return SqlConnector.class;
    }

    @Override public Class<DatabaseConnectorConfig> configType() {
        return DatabaseConnectorConfig.class;
    }

    @Override public Promise<SqlConnector> provision(DatabaseConnectorConfig config) {
        return Promise.lift(DatabaseConnectorError::databaseFailure, () -> connector(config));
    }

    @SuppressWarnings("JBCT-EX-01") private static SqlConnector connector(DatabaseConnectorConfig config) {
        var dataSource = hikariDataSource(config);
        try {
            return JdbcSqlConnector.jdbcSqlConnector(config, dataSource);
        } catch (Exception e) {
            dataSource.close();
            throw e;
        }
    }

    private static HikariDataSource hikariDataSource(DatabaseConnectorConfig config) {
        var hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(config.effectiveJdbcUrl());
        config.effectiveUsername().onPresent(hikariConfig::setUsername);
        config.effectivePassword().onPresent(hikariConfig::setPassword);
        hikariConfig.setConnectionTimeout(config.poolConfig().connectionTimeout()
                                                           .toMillis());
        hikariConfig.setIdleTimeout(config.poolConfig().idleTimeout()
                                                     .toMillis());
        hikariConfig.setMaxLifetime(config.poolConfig().maxLifetime()
                                                     .toMillis());
        hikariConfig.setMinimumIdle(config.poolConfig().minConnections());
        hikariConfig.setMaximumPoolSize(config.poolConfig().maxConnections());
        return new HikariDataSource(hikariConfig);
    }
}

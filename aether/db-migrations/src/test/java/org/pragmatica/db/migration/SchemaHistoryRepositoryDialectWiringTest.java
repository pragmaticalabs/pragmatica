// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.db.migration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.aether.resource.db.PoolConfig;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Pins, without Docker, that [SchemaHistoryRepository] renders the meta and owner `CREATE TABLE` for the CONNECTOR's
/// dialect (#3050): a call site that hardcodes one dialect issues that dialect's spelling to every engine. The live
/// SQL Server and Oracle ITs are `heavy-db` and run on a host only, so this is the check that runs in CI.
class SchemaHistoryRepositoryDialectWiringTest {

    @ParameterizedTest
    @EnumSource(DatabaseType.class)
    void bootstrap_issuesTheMetaTableCreateInTheConnectorsDialect(DatabaseType type) {
        var connector = new RecordingConnector(type);

        SchemaHistoryRepository.schemaHistoryRepository().bootstrap(connector).await();

        assertThat(connector.statements).first().isEqualTo(SchemaHistoryEvolution.createMetaTableSql(type));
    }

    @ParameterizedTest
    @EnumSource(DatabaseType.class)
    void claimOwnership_issuesTheOwnerTableCreateInTheConnectorsDialect(DatabaseType type) {
        var connector = new RecordingConnector(type);

        SchemaHistoryRepository.schemaHistoryRepository().claimOwnership(connector, "ds", "owner").await();

        assertThat(connector.statements).first().isEqualTo(SchemaHistoryEvolution.createOwnerTableSql(type));
    }

    /// Records every statement and refuses the first update, so the repository stops right after its create.
    private static final class RecordingConnector implements SqlConnector {
        private final DatabaseConnectorConfig config;
        private final List<String> statements = new ArrayList<>();

        RecordingConnector(DatabaseType type) {
            this.config = new DatabaseConnectorConfig(none(), some(type), some("localhost"), none(), some("test"), none(), none(),
                                                      PoolConfig.DEFAULT, Map.of(), none(), none(), none());
        }

        @Override
        public Promise<Integer> update(String sql, Object... params) {
            statements.add(sql);
            return Causes.cause("recorded " + sql).promise();
        }

        @Override
        public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
            statements.add(sql);
            return Causes.cause("recorded " + sql).promise();
        }

        @Override
        public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
            statements.add(sql);
            return Causes.cause("recorded " + sql).promise();
        }

        @Override
        public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
            statements.add(sql);
            return Causes.cause("recorded " + sql).promise();
        }

        @Override
        public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
            statements.add(sql);
            return Causes.cause("recorded " + sql).promise();
        }

        @Override
        public <T> Promise<T> transactional(TransactionCallback<T> callback) {
            return callback.execute(this);
        }

        @Override
        public DatabaseConnectorConfig config() {
            return config;
        }

        @Override
        public Promise<Boolean> isHealthy() {
            return Promise.success(true);
        }
    }
}

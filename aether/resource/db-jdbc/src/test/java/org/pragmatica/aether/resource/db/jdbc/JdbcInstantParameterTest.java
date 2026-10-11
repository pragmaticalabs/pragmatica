// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.jdbc;

import java.time.Instant;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import static org.assertj.core.api.Assertions.assertThat;

class JdbcInstantParameterTest {
    @Test void transactional_instantBatchesAndQueries_shareParameterConversion() {
        var datasource = new JdbcDataSource();
        datasource.setURL("jdbc:h2:mem:instant_" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var config = DatabaseConnectorConfig.databaseConnectorConfigBuilder().withName("instant-test").withJdbcUrl(datasource.getURL()).build().unwrap();
        var connector = JdbcSqlConnector.jdbcSqlConnector(config, datasource);
        var instant = Instant.parse("2026-01-02T03:04:05.123456789Z");
        connector.update("CREATE TABLE events(event_time TIMESTAMP(9))").await().unwrap();
        try {
            connector.transactional(tx -> tx.batch("INSERT INTO events VALUES (?)", java.util.Collections.singletonList(new Object[]{instant}))
                .flatMap(_ -> tx.queryOne("SELECT COUNT(*) AS n FROM events WHERE event_time = ?", row -> row.getLong("n"), instant))).await()
                .onSuccess(count -> assertThat(count).isEqualTo(1L)).unwrap();
            assertThat(connector.queryOne("SELECT COUNT(*) AS n FROM events WHERE event_time = ?", row -> row.getLong("n"), instant).await().unwrap()).isEqualTo(1L);
        } finally { connector.update("DROP TABLE events").await().unwrap(); connector.stop().await().unwrap(); }
    }
}

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.jooq.r2dbc;

import java.util.UUID;

import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.lang.Promise;

import io.r2dbc.spi.ConnectionFactories;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.resource.db.DatabaseConnectorConfig.databaseConnectorConfigBuilder;

/// #2431: every operation of the jOOQ R2DBC connector runs a real query against a real (in-memory H2) database and
/// returns its row. jOOQ refuses its blocking `fetch` / `execute` when it holds only an R2DBC connection
/// (`DetachedException`), so each of these failed before the connector used jOOQ's reactive API.
class R2dbcJooqConnectorH2QueryTest {
    private R2dbcJooqConnector connector;

    @BeforeEach
    void freshDatabase() {
        var factory = ConnectionFactories.get("r2dbc:h2:mem:///jooq" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var config = databaseConnectorConfigBuilder().withName("db").withHost("localhost").withDatabase("db")
                                                     .withType(DatabaseType.H2)
                                                     .build()
                                                     .unwrap();

        connector = R2dbcJooqConnector.r2dbcJooqConnector(config, factory);
        success(connector.execute(connector.dsl().query("CREATE TABLE items(id INT PRIMARY KEY, name VARCHAR(20))")));
        success(connector.execute(connector.dsl().query("INSERT INTO items(id, name) VALUES (1, 'one'), (2, 'two')")));
    }

    @Test
    void fetchOne_returnsTheRow() {
        var record = success(connector.fetchOne(connector.dsl().resultQuery("SELECT name FROM items WHERE id = 1")));

        assertThat(record.get(0, String.class)).isEqualTo("one");
    }

    @Test
    void fetchOne_noRow_isResultNotFound() {
        var cause = failure(connector.fetchOne(connector.dsl().resultQuery("SELECT name FROM items WHERE id = 99")));

        assertThat(cause).isSameAs(DatabaseConnectorError.ResultNotFound.INSTANCE);
    }

    @Test
    void fetchOptional_returnsTheRowOrNone() {
        var present = success(connector.fetchOptional(connector.dsl().resultQuery("SELECT name FROM items WHERE id = 2")));
        var absent = success(connector.fetchOptional(connector.dsl().resultQuery("SELECT name FROM items WHERE id = 99")));

        assertThat(present.map(r -> r.get(0, String.class)).or("none")).isEqualTo("two");
        assertThat(absent.isPresent()).isFalse();
    }

    @Test
    void fetch_returnsAllRows() {
        var records = success(connector.fetch(connector.dsl().resultQuery("SELECT name FROM items ORDER BY id")));

        assertThat(records.stream().map(r -> r.get(0, String.class))).containsExactly("one", "two");
    }

    @Test
    void execute_returnsAffectedRows() {
        var updated = success(connector.execute(connector.dsl().query("UPDATE items SET name = 'x'")));

        assertThat(updated).isEqualTo(2);
    }

    @Test
    void transactional_commitsOnSuccess_andSeesItsOwnWrites() {
        var count = success(connector.transactional(tx -> tx.execute(tx.dsl().query("INSERT INTO items(id, name) VALUES (3, 'three')"))
                                                          .flatMap(_ -> tx.fetch(tx.dsl().resultQuery("SELECT name FROM items")))
                                                          .map(java.util.List::size)));

        assertThat(count).isEqualTo(3);
        assertThat(success(connector.fetch(connector.dsl().resultQuery("SELECT name FROM items"))).size()).isEqualTo(3);
    }

    @Test
    void transactional_rollsBackOnFailure() {
        var cause = failure(connector.<Integer> transactional(tx -> tx.execute(tx.dsl().query("INSERT INTO items(id, name) VALUES (3, 'three')"))
                                                                    .flatMap(_ -> DatabaseConnectorError.configurationError("stop").promise())));

        assertThat(cause).isInstanceOf(DatabaseConnectorError.class);
        assertThat(success(connector.fetch(connector.dsl().resultQuery("SELECT name FROM items"))).size()).isEqualTo(2);
    }

    @Test
    void fetchOne_manyRows_isMultipleResults() {
        var cause = failure(connector.fetchOne(connector.dsl().resultQuery("SELECT name FROM items")));

        assertThat(cause).isInstanceOf(DatabaseConnectorError.MultipleResults.class);
    }

    @Test
    void fetchOptional_manyRows_takesTheFirst() {
        var name = success(connector.fetchOptional(connector.dsl().resultQuery("SELECT name FROM items ORDER BY id")));

        assertThat(name.map(r -> r.get(0, String.class)).or("none")).isEqualTo("one");
    }

    @Test
    void execute_boundValue_updatesOnlyTheMatchingRow() {
        var updated = success(connector.execute(connector.dsl().query("UPDATE items SET name = 'bound' WHERE id = ?", 2)));

        assertThat(updated).isEqualTo(1);
        assertThat(success(connector.fetchOne(connector.dsl().resultQuery("SELECT name FROM items WHERE id = 1"))).get(0, String.class)).isNotEqualTo("bound");
    }

    @Test
    void fetchOne_fieldDeclaredWiderThanTheColumn_isCoercedToItsDeclaredType() {
        var wide = DSL.field("id", Long.class);
        var record = success(connector.fetchOne(connector.dsl().select(wide).from(DSL.table("items")).where(DSL.field("id", Integer.class).eq(1))));
        Long id = record.value1();

        assertThat(id).isEqualTo(1L);
    }

    /// A query part that renders a `?` the parameter walk cannot see (raw SQL) while reporting a bind value: its parameters
    /// cannot be mapped onto its placeholders, so the query is refused with a typed error rather than run with a raw value.
    @Test
    void fetchOne_parametersThatCannotBeMappedToPlaceholders_areRefusedWithATypedError() {
        var opaque = new org.jooq.impl.CustomField<Integer>("opaque", SQLDataType.INTEGER) {
            @Override
            public void accept(org.jooq.Context<?> ctx) {
                if (ctx instanceof org.jooq.RenderContext render) {
                    render.sql("?");
                } else {
                    ctx.visit(DSL.val(5));
                }
            }
        };

        var cause = failure(connector.fetchOne(connector.dsl().select(opaque)));

        assertThat(cause).isInstanceOf(DatabaseConnectorError.QueryFailed.class);
        assertThat(cause.message()).contains("Cannot map the parameters");
    }

    @Test
    void execute_queryThatIsNotARowCountQuery_failsWithATypedConnectorError() {
        var cause = failure(connector.execute(connector.dsl().resultQuery("SELECT 1")));

        assertThat(cause).isInstanceOf(DatabaseConnectorError.class);
        assertThat(cause.message()).contains("row-count");
    }

    private static <T> T success(Promise<T> promise) {
        return promise.await().fold(cause -> fail("expected success but got " + cause.message()), value -> value);
    }

    private static <T> org.pragmatica.lang.Cause failure(Promise<T> promise) {
        return promise.await().fold(cause -> cause, value -> fail("expected a failure but got " + value));
    }
}

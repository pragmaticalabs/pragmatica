// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.jooq.async;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.lang.Promise;
import org.pragmatica.postgres.net.Connectible;
import org.pragmatica.postgres.net.netty.NettyConnectibleBuilder;
import org.pragmatica.postgres.r2dbc.PgAsyncConnectionFactory;

import org.jooq.Binding;
import org.jooq.BindingGetResultSetContext;
import org.jooq.BindingGetSQLInputContext;
import org.jooq.BindingGetStatementContext;
import org.jooq.BindingRegisterContext;
import org.jooq.BindingSQLContext;
import org.jooq.BindingSetSQLOutputContext;
import org.jooq.BindingSetStatementContext;
import org.jooq.Converter;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.pragmatica.aether.resource.db.DatabaseConnectorConfig.databaseConnectorConfigBuilder;

/// #2431 (v-2475 r2b): a query runs with the converters and bindings of its own parameters. The stored value is read back
/// as the RAW column with plain SQL, because a typed read-back can hide a converter that was skipped. A converter whose user
/// type equals its database type (encrypt, normalise, scale) is the case a re-bind by runtime type cannot tell apart from
/// an already-converted value. Self-skips when no Docker daemon is reachable.
class AsyncJooqConnectorConverterLiveQueryTest {
    record Doc(String json) {}

    /// Wraps on the way to the database and strips on the way back, so "not applied" is visible in the stored value.
    static final Converter<String, String> ENC = Converter.of(String.class, String.class, db -> db.substring(4), user -> "enc:" + user);
    static final Converter<Long, Boolean> LONG_BOOLEAN = Converter.of(Long.class, Boolean.class, db -> db == 100L, flag -> flag ? 100L : 0L);
    static final Converter<JSONB, Doc> DOC_CONVERTER = Converter.of(JSONB.class, Doc.class, db -> new Doc(db.data().replaceAll("^\\{\"v\": ?(.*)}$", "$1")), doc -> JSONB.valueOf("{\"v\": " + doc.json() + "}"));

    static final class DocBinding implements Binding<JSONB, Doc> {
        @Override public Converter<JSONB, Doc> converter() { return DOC_CONVERTER; }
        @Override public void sql(BindingSQLContext<Doc> ctx) { ctx.render().visit(DSL.val(ctx.convert(converter()).value(), SQLDataType.JSONB)).sql("::jsonb"); }
        @Override public void register(BindingRegisterContext<Doc> ctx) throws SQLException { throw new SQLFeatureNotSupportedException("register"); }
        @Override public void set(BindingSetStatementContext<Doc> ctx) throws SQLException { ctx.statement().setString(ctx.index(), ctx.convert(converter()).value().data()); }
        @Override public void set(BindingSetSQLOutputContext<Doc> ctx) throws SQLException { throw new SQLFeatureNotSupportedException("set"); }
        @Override public void get(BindingGetResultSetContext<Doc> ctx) throws SQLException { ctx.convert(converter()).value(JSONB.valueOf(ctx.resultSet().getString(ctx.index()))); }
        @Override public void get(BindingGetStatementContext<Doc> ctx) throws SQLException { throw new SQLFeatureNotSupportedException("get"); }
        @Override public void get(BindingGetSQLInputContext<Doc> ctx) throws SQLException { throw new SQLFeatureNotSupportedException("get"); }
    }

    private static final org.jooq.Table<?> TABLE = DSL.table("conv_items");
    private static final org.jooq.Field<Integer> ID = DSL.field("id", Integer.class);
    private static final org.jooq.Field<String> ENCODED = DSL.field("e", SQLDataType.VARCHAR.asConvertedDataType(ENC));
    private static final org.jooq.Field<Boolean> FLAG = DSL.field("n", SQLDataType.BIGINT.asConvertedDataType(LONG_BOOLEAN));
    private static final org.jooq.Field<Doc> DOC = DSL.field("b", SQLDataType.JSONB.asConvertedDataType(new DocBinding()));
    private static PostgreSQLContainer<?> postgres;
    private static Connectible connectible;
    private static AsyncJooqConnector connector;

    @BeforeAll
    static void startPostgres() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the live-PostgreSQL pin");
        postgres = new PostgreSQLContainer<>("postgres:16");
        postgres.start();
        connectible = new NettyConnectibleBuilder().hostname(postgres.getHost())
                                                   .port(postgres.getMappedPort(5432))
                                                   .database(postgres.getDatabaseName())
                                                   .username(postgres.getUsername())
                                                   .password(postgres.getPassword())
                                                   .maxConnections(2)
                                                   .pool();
        var config = databaseConnectorConfigBuilder().withName("db").withHost("localhost").withDatabase("db").withType(DatabaseType.POSTGRESQL).build().unwrap();

        connector = AsyncJooqConnector.asyncJooqConnector(config, PgAsyncConnectionFactory.pgAsyncConnectionFactory(connectible));
    }

    @AfterAll
    static void stopPostgres() {
        if (connectible != null) {
            connectible.close().await();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void freshTable() {
        success(connector.execute(connector.dsl().query("DROP TABLE IF EXISTS conv_items")));
        success(connector.execute(connector.dsl().query("CREATE TABLE conv_items(id INT PRIMARY KEY, e TEXT, n BIGINT, b JSONB)")));
    }

    @Test
    void insert_throughASameTypeConverter_storesTheConvertedValue() {
        success(connector.execute(connector.dsl().insertInto(TABLE, ID, ENCODED).values(1, "x")));

        assertThat(raw("e", 1)).isEqualTo("enc:x");
        assertThat(success(connector.fetchOne(connector.dsl().select(ENCODED).from(TABLE).where(ID.eq(1)))).get(ENCODED)).isEqualTo("x");
    }

    @Test
    void update_throughASameTypeConverter_storesTheConvertedValue() {
        success(connector.execute(connector.dsl().query("INSERT INTO conv_items(id, e) VALUES (1, 'enc:x')")));

        success(connector.execute(connector.dsl().update(TABLE).set(ENCODED, "y").where(ID.eq(1))));

        assertThat(raw("e", 1)).isEqualTo("enc:y");
    }

    @Test
    void where_throughASameTypeConverter_comparesTheConvertedValue() {
        success(connector.execute(connector.dsl().query("INSERT INTO conv_items(id, e) VALUES (1, 'enc:x'), (2, 'x')")));

        var ids = success(connector.fetch(connector.dsl().select(ID).from(TABLE).where(ENCODED.eq("x"))));

        assertThat(ids.stream().map(r -> r.get(ID))).containsExactly(1);
    }

    @Test
    void insert_throughALongBooleanConverter_storesTheConvertedValue() {
        success(connector.execute(connector.dsl().insertInto(TABLE, ID, FLAG).values(1, true)));

        assertThat(raw("n", 1)).isEqualTo("100");
        assertThat(success(connector.fetchOne(connector.dsl().select(FLAG).from(TABLE).where(ID.eq(1)))).get(FLAG)).isTrue();
    }

    @Test
    void insert_throughAJsonbBinding_storesTheBoundValue() {
        success(connector.execute(connector.dsl().insertInto(TABLE, ID, DOC).values(1, new Doc("2"))));

        assertThat(raw("b", 1)).isEqualTo("{\"v\": 2}");
        assertThat(success(connector.fetchOne(connector.dsl().select(DOC).from(TABLE).where(ID.eq(1)))).get(DOC)).isEqualTo(new Doc("2"));
    }

    @Test
    void namedParameterUsedTwice_bindsAtEveryPosition() {
        success(connector.execute(connector.dsl().query("INSERT INTO conv_items(id) VALUES (1), (2), (3)")));
        var wanted = DSL.param("wanted", 2);

        var ids = success(connector.fetch(connector.dsl().select(ID).from(TABLE).where(ID.eq(wanted).or(ID.add(0).eq(wanted)))));

        assertThat(ids.stream().map(r -> r.get(ID))).containsExactly(2);
    }

    @Test
    void fetch_withAnInlinedValue_runsWithoutAPlaceholderForIt() {
        success(connector.execute(connector.dsl().query("INSERT INTO conv_items(id) VALUES (1), (2), (3)")));
        var ids = success(connector.fetch(connector.dsl().select(ID).from(TABLE).where(ID.eq(DSL.inline(2)).or(ID.eq(3)))));

        assertThat(ids.stream().map(r -> r.get(ID))).containsExactlyInAnyOrder(2, 3);
    }

    @Test
    void fetch_queryBuiltForStaticStatements_runsWithItsValuesInlined() {
        success(connector.execute(connector.dsl().query("INSERT INTO conv_items(id) VALUES (1), (2), (3)")));
        var inlined = DSL.using(connector.dsl().configuration().derive(new org.jooq.conf.Settings().withStatementType(org.jooq.conf.StatementType.STATIC_STATEMENT)));

        var ids = success(connector.fetch(inlined.select(ID).from(TABLE).where(ID.eq(1))));

        assertThat(ids.stream().map(r -> r.get(ID))).containsExactly(1);
    }

    private static String raw(String column, int id) {
        return success(connector.fetchOne(connector.dsl().resultQuery("SELECT " + column + "::text FROM conv_items WHERE id = " + id))).get(0, String.class);
    }

    private static <T> T success(Promise<T> promise) {
        return promise.await().fold(cause -> fail("expected success but got " + cause.message()), value -> value);
    }
}

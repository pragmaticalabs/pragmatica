// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.jooq.jdbc;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.NodeShareable.ServerProbe;
import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.aether.resource.db.SharedPoolSupport;
import org.pragmatica.aether.resource.db.jooq.JooqConnector;
import org.pragmatica.aether.resource.db.jooq.JooqSharedPoolSupport;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// The jOOQ side of the demand guard's probe (#2973 L): it skips servers that are not PostgreSQL, and maps the three
/// columns of the probe row.
class JooqSharedPoolSupportTest {
    private static final TimeSpan TIMEOUT = timeSpan(5).seconds();

    private static JooqConnector connector(DatabaseType type, Record row, AtomicInteger fetches) {
        var dsl = DSL.using(SQLDialect.POSTGRES);

        return (JooqConnector) Proxy.newProxyInstance(JooqConnector.class.getClassLoader(),
                                                      new Class<?>[]{JooqConnector.class},
                                                      (_, method, args) -> answer(method, args, type, dsl, row, fetches));
    }

    private static Object answer(Method method,
                                 Object[] args,
                                 DatabaseType type,
                                 org.jooq.DSLContext dsl,
                                 Record row,
                                 AtomicInteger fetches) {
        return switch (method.getName()) {
            case "databaseType" -> type;
            case "dsl" -> dsl;
            case "fetchOne" -> fetched(args, row, fetches);
            default -> throw new AssertionError("unexpected " + method.getName());
        };
    }

    private static Object fetched(Object[] args, Record row, AtomicInteger fetches) {
        fetches.incrementAndGet();
        assertThat(args[0].toString()).as("the query sent is the probe SQL").contains("max_connections");

        return Promise.success(row);
    }

    private static Record probeRow(int available, String addr, int port) {
        var dsl = DSL.using(SQLDialect.POSTGRES);
        var availableField = DSL.field(DSL.name("available"), Integer.class);
        var addrField = DSL.field(DSL.name("addr"), String.class);
        var portField = DSL.field(DSL.name("port"), Integer.class);
        var record = dsl.newRecord(availableField, addrField, portField);

        record.set(availableField, available);
        record.set(addrField, addr);
        record.set(portField, port);

        return record;
    }

    @Test
    void probe_nonPostgresServer_isSkipped_andNeverQueries() {
        var fetches = new AtomicInteger();
        var result = JooqSharedPoolSupport.probe(connector(DatabaseType.MYSQL, probeRow(1, "x", 1), fetches));

        assertThat(result.isEmpty()).isTrue();
        assertThat(fetches.get()).isZero();
    }

    @Test
    void probe_postgresServer_mapsAvailableAddressAndPort() {
        var fetches = new AtomicInteger();
        var result = JooqSharedPoolSupport.probe(connector(DatabaseType.POSTGRESQL, probeRow(97, "10.0.0.5", 5433), fetches));

        assertThat(result.isPresent()).isTrue();

        var answer = result.unwrap().await(TIMEOUT).unwrap();

        assertThat(answer.available()).isEqualTo(97);
        assertThat(answer.addr()).isEqualTo(Option.some("10.0.0.5"));
        assertThat(answer.port()).isEqualTo(5433);
        assertThat(fetches.get()).isEqualTo(1);
    }

    @Test
    void probe_unixSocketSession_hasNoAddress() {
        var result = JooqSharedPoolSupport.probe(connector(DatabaseType.POSTGRESQL, probeRow(50, "", 0), new AtomicInteger()));

        var answer = result.unwrap().await(TIMEOUT).unwrap();

        assertThat(answer.addr().isEmpty()).isTrue();
        assertThat(answer.available()).isEqualTo(50);
    }
}

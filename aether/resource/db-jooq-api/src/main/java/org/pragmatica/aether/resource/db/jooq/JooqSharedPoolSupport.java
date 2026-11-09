// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.jooq;

import org.pragmatica.aether.resource.NodeShareable.ServerProbe;
import org.pragmatica.aether.resource.db.SharedPoolSupport;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import org.jooq.Record;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// The demand guard's PostgreSQL limit probe through a [JooqConnector] (#2973): [SharedPoolSupport#PROBE_SQL] as a plain
/// jOOQ result query.
public sealed interface JooqSharedPoolSupport {
    static Option<Promise<ServerProbe>> probe(JooqConnector connector) {
        return SharedPoolSupport.isPostgres(connector)
               ? some(connector.fetchOne(connector.dsl()
                                                  .resultQuery(SharedPoolSupport.PROBE_SQL))
                               .map(JooqSharedPoolSupport::serverProbe))
               : none();
    }

    private static ServerProbe serverProbe(Record row) {
        return SharedPoolSupport.serverProbe(row.get("available", Integer.class),
                                             row.get("addr", String.class),
                                             row.get("port", Integer.class));
    }

    record unused() implements JooqSharedPoolSupport {}
}

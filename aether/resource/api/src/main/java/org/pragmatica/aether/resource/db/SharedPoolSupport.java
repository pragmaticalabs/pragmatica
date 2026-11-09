// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import java.util.function.Supplier;

import org.pragmatica.aether.resource.NodeShareable.PoolDemand;
import org.pragmatica.aether.resource.NodeShareable.ServerId;
import org.pragmatica.aether.resource.NodeShareable.ServerProbe;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// What the seven SQL factories share as [org.pragmatica.aether.resource.NodeShareable]s (#2973): the pool's demand on
/// its server and the PostgreSQL limit probe the node's guard runs after a pool opens.
public sealed interface SharedPoolSupport {
    /// The non-superuser connections the server allows, and the address and port it answered on. `reserved_connections`
    /// exists from PostgreSQL 16; `missing_ok` keeps the query valid before it. A unix-socket session has no address, so
    /// it answers an empty string and port 0.
    String PROBE_SQL = "SELECT current_setting('max_connections')::int"
                       + " - current_setting('superuser_reserved_connections')::int"
                       + " - coalesce(nullif(current_setting('reserved_connections', true), '')::int, 0) AS available,"
                       + " coalesce(host(inet_server_addr()), '') AS addr, coalesce(inet_server_port(), 0) AS port";

    /// `warm` is the transport's real warm-up (what it opens at provisioning), `max` its `max_connections`.
    static PoolDemand poolDemand(DatabaseConnectorConfig config, DatabaseConnectorConfig.Transport transport, int warm) {
        return PoolDemand.poolDemand(warm,
                                     config.poolConfig()
                                           .maxConnections(),
                                     ServerId.serverId(config.effectiveType(transport)
                                                             .name(),
                                                       config.effectiveHost(transport),
                                                       config.effectivePort(transport)),
                                     TimeSpan.fromDuration(config.poolConfig()
                                                                 .connectionTimeout()
                                                                 .duration()),
                                     endpointLabel(config, transport));
    }

    /// `<host>:<port>/<db> (<user>)`: never a password, never a URL that could carry one.
    private static String endpointLabel(DatabaseConnectorConfig config, DatabaseConnectorConfig.Transport transport) {
        return config.effectiveHost(transport) + ":" + config.effectivePort(transport) + "/"
               + config.effectiveDatabase(transport) + " (" + config.effectiveUsername(transport)
                                                                    .or("") + ")";
    }

    /// The probe through a [SqlConnector], for PostgreSQL only: other server types have no comparable limit query.
    static Option<Promise<ServerProbe>> probe(SqlConnector connector) {
        return isPostgres(connector)
               ? some(connector.queryOne(PROBE_SQL, SharedPoolSupport::serverProbe))
               : none();
    }

    static boolean isPostgres(DatabaseConnector connector) {
        return connector.databaseType() == DatabaseType.POSTGRESQL;
    }

    private static Result<ServerProbe> serverProbe(RowMapper.RowAccessor row) {
        return Result.all(row.getInt("available"),
                          row.getString("addr"),
                          row.getInt("port"))
                     .map(SharedPoolSupport::serverProbe);
    }

    /// Builds the answer from the probe's three columns; an empty address (a unix socket) is `none()`.
    static ServerProbe serverProbe(int available, String addr, int port) {
        return ServerProbe.serverProbe(available,
                                       Option.option(addr)
                                             .filter(value -> !value.isEmpty()),
                                       port);
    }

    /// Runs `body` with `owner`'s class loader as the thread-context class loader, and restores the previous one (#2973
    /// D2.8). Hikari (`DriverManager`) and R2DBC (`ConnectionFactories.get`) find drivers through the context loader; a
    /// slice loader there would pin into a node-shared pool and impose that slice's driver on every other holder. Drivers
    /// for shared pools therefore come from the node classpath.
    @SuppressWarnings("JBCT-EX-01")
    static <T> T underClassLoaderOf(Class<?> owner, Supplier<T> body) {
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();

        thread.setContextClassLoader(owner.getClassLoader());

        try {
            return body.get();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    record unused() implements SharedPoolSupport {}
}

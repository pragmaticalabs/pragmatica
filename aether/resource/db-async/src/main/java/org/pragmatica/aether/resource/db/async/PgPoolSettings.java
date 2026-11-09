// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.async;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;

import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.PoolConfig;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.postgres.net.Connectible;
import org.pragmatica.postgres.net.Connection;
import org.pragmatica.postgres.net.netty.NettyConnectibleBuilder;

import static org.pragmatica.lang.Option.option;


/// Session identity and pool warm-up shared by the two pg-async connector factories (#2948).
///
/// - `application_name`: the configured value, else `aether-<node id>` when the node publishes its identity in the
///   [#NODE_ID_PROPERTY] system property (`Main` does, right after resolving it), else `aether-<datasource name>`. A
///   derived name longer than [DatabaseConnectorConfig#MAX_APPLICATION_NAME_BYTES] bytes is cut at that length; a configured one
///   was already validated at load.
/// - Warm-up: `pool_config.min_connections` sessions are opened and returned to the pool at provisioning, so the first
///   requests do not pay connection setup. The pg-async pool has no idle eviction, so the sessions stay until the server
///   or the network drops them.
final class PgPoolSettings {
    static final String NODE_ID_PROPERTY = "aether.node.id";

    private PgPoolSettings() {}

    @Contract
    static void configureSession(NettyConnectibleBuilder builder, DatabaseConnectorConfig config) {
        builder.applicationName(applicationName(config));
    }

    static String applicationName(DatabaseConnectorConfig config) {
        return config.applicationName()
                     .or(() -> derivedApplicationName(config));
    }

    private static String derivedApplicationName(DatabaseConnectorConfig config) {
        return truncated("aether-" + option(System.getProperty(NODE_ID_PROPERTY)).filter(id -> !id.isBlank())
                                           .or(config::effectiveName));
    }

    private static String truncated(String value) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);

        return bytes.length <= DatabaseConnectorConfig.MAX_APPLICATION_NAME_BYTES
               ? value
               : new String(bytes, 0, DatabaseConnectorConfig.MAX_APPLICATION_NAME_BYTES, StandardCharsets.UTF_8).replace("�",
                                                                                                                          "");
    }

    /// A built connector together with the pool that serves it, so provisioning can warm the pool before handing the
    /// connector out.
    record Assembled<C>(C connector, Connectible pool) {
        Promise<C> warmed(PoolConfig poolConfig) {
            return warmUp(pool,
                          poolConfig.minConnections(),
                          poolConfig.connectionTimeout().duration()).map(_ -> connector);
        }
    }

    // FER: a pool that could not be warmed (database unreachable at provisioning, or slower than the
    // connection timeout) still serves requests: the pool opens sessions on demand, exactly as it did before the
    // warm-up existed. Guarantee earned: provisioning succeeds as it always has, after at most `bound`, and a first
    // request may pay connection setup; it is NOT a promise that `min_connections` sessions are open. Mechanism: one
    // attempt, no retry. The timeout is armed on the derived promise, not on the pool's own, so sessions that open
    // after the bound are still returned to the pool. [verified: PgPoolWarmUpTest]
    static Promise<Unit> warmUp(Connectible pool, int minConnections, Duration bound) {
        return Promise.allOf(opened(pool, minConnections))
                      .flatMap(PgPoolSettings::released)
                      .timeout(TimeSpan.fromDuration(bound))
                      .recover(_ -> Unit.unit());
    }

    private static List<Promise<Connection>> opened(Connectible pool, int count) {
        return IntStream.range(0, count)
                        .mapToObj(_ -> pool.getConnection())
                        .toList();
    }

    private static Promise<Unit> released(List<Result<Connection>> results) {
        return Promise.allOf(results.stream().flatMap(Result::stream).map(Connection::close).toList()).map(_ -> Unit.unit());
    }
}

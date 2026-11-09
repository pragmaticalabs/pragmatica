// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.async;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.PoolConfig;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.postgres.net.Connectible;
import org.pragmatica.postgres.net.Connection;
import org.pragmatica.postgres.net.netty.NettyConnectibleBuilder;

import static org.pragmatica.lang.Option.option;


/// Session identity and pool warm-up shared by the two pg-async connector factories (#2948).
///
/// - `application_name`: the configured value, else `aether-<node id>` when the node publishes its identity in the
///   [#NODE_ID_PROPERTY] system property (`Main` does, right after resolving it), else `aether-<datasource name>`.
///   Whatever the source, the value is reduced to printable ASCII (other code points become `?`) and cut at
///   [DatabaseConnectorConfig#MAX_APPLICATION_NAME_BYTES] characters, so what `pg_stat_activity` shows is exactly what
///   the connector sent.
/// - Warm-up: `pool_config.min_connections` sessions are opened and returned to the pool at provisioning, so the first
///   requests do not pay connection setup. The pg-async pool has no idle eviction, so the sessions stay until the server
///   or the network drops them.
final class PgPoolSettings {
    static final String NODE_ID_PROPERTY = "aether.node.id";
    private static final int FIRST_PRINTABLE = 0x20;
    private static final int LAST_PRINTABLE = 0x7E;

    private PgPoolSettings() {}

    @Contract
    static void configureSession(NettyConnectibleBuilder builder, DatabaseConnectorConfig config) {
        builder.applicationName(applicationName(config));
    }

    static String applicationName(DatabaseConnectorConfig config) {
        return truncated(printableAscii(config.applicationName().or(() -> derivedApplicationName(config))));
    }

    private static String derivedApplicationName(DatabaseConnectorConfig config) {
        return "aether-" + option(System.getProperty(NODE_ID_PROPERTY)).filter(id -> !id.isBlank())
                                 .or(config::effectiveName);
    }

    /// PostgreSQL rewrites every byte outside printable ASCII in `application_name` (`\xNN`) and applies its 63-byte
    /// limit after the rewrite, so a name that is not printable ASCII would show up in `pg_stat_activity` as something
    /// other than what was configured. The client therefore sends only printable ASCII: each code point outside
    /// `0x20..0x7E` (control characters, non-ASCII letters, emoji) becomes one `?`.
    private static String printableAscii(String value) {
        return value.codePoints()
                    .map(PgPoolSettings::printableOrQuestionMark)
                    .mapToObj(Character::toString)
                    .collect(Collectors.joining());
    }

    private static int printableOrQuestionMark(int codePoint) {
        return codePoint >= FIRST_PRINTABLE && codePoint <= LAST_PRINTABLE
               ? codePoint
               : '?';
    }

    /// Cut at the server limit; the value is printable ASCII here, so one char is one byte.
    private static String truncated(String value) {
        return value.length() <= DatabaseConnectorConfig.MAX_APPLICATION_NAME_BYTES
               ? value
               : value.substring(0, DatabaseConnectorConfig.MAX_APPLICATION_NAME_BYTES);
    }

    /// A built connector together with the pool that serves it, so provisioning can warm the pool before handing the
    /// connector out.
    record Assembled<C>(C connector, Connectible pool) {
        Promise<C> warmed(PoolConfig poolConfig) {
            return warmUp(pool,
                          warmCount(poolConfig),
                          poolConfig.connectionTimeout().duration()).map(_ -> connector);
        }
    }

    /// `min_connections`, bounded by `max_connections` even for a `PoolConfig` built through its canonical constructor
    /// (the factory already refuses min > max): warming past the maximum would check out slots nothing can return.
    static int warmCount(PoolConfig poolConfig) {
        return Math.min(poolConfig.minConnections(), poolConfig.maxConnections());
    }

    // FER: a pool that could not be warmed (database unreachable at provisioning, or slower than the
    // connection timeout) still serves requests: the pool opens sessions on demand, exactly as it did before the
    // warm-up existed. Guarantee earned: provisioning succeeds as it always has, after at most `bound`, and a first
    // request may pay connection setup; it is NOT a promise that `min_connections` sessions are open. Mechanism: one
    // attempt, no retry. Each session is handed back to the pool the moment it opens (per-session release, joined
    // afterwards), never held until every session has opened: the pool hands a new connection to its oldest waiter, and
    // a warm-up waiter whose own session hangs would otherwise keep every later connection for itself after the bound
    // (B1, #2955). A connection that reaches a waiter left over from an expired warm-up is released at once, so it goes
    // to the next waiter or to the idle set.
    static Promise<Unit> warmUp(Connectible pool, int count, Duration bound) {
        return Promise.allOf(warmed(pool, count))
                      .map(_ -> Unit.unit())
                      .timeout(TimeSpan.fromDuration(bound))
                      .recover(_ -> Unit.unit());
    }

    private static List<Promise<Unit>> warmed(Connectible pool, int count) {
        return IntStream.range(0, count)
                        .mapToObj(_ -> openAndRelease(pool))
                        .toList();
    }

    private static Promise<Unit> openAndRelease(Connectible pool) {
        return pool.getConnection()
                   .flatMap(Connection::close);
    }
}

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import java.util.Map;

import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The identity of a shared SQL pool (#2973): the inputs a DB factory's `provision` reads, as EFFECTIVE values.
///
/// - `factoryClass` and `resourceType`: one pool per connector type, so `SqlConnector`, `PgSqlConnector` and jOOQ over one
///   datasource are separate pools.
/// - `endpoint`: the transport's effective endpoint (the effective JDBC or R2DBC URL; effective host, port and database
///   for pg-async), so an absent port and an explicit default port meet.
/// - `type`: the effective database type, which sets the jOOQ SQL dialect and the guard's PostgreSQL gate.
/// - `username` and `password`: the transport's effective credentials, after secret resolution. A rotated or dynamic
///   secret therefore gives each resolution its own pool.
/// - `poolConfig`: every pool number; durations compare by value (`30s` equals `30000ms`).
/// - `properties`: a copy, so mutating the configuration's map later cannot change a key already in use.
/// - `applicationName`: the name the connector sends, derived or explicit.
///
/// The section name, the datasource `name` and `pool_scope` are not part of it. [#toString()] never prints the endpoint
/// or the credentials: a key is never logged.
public record DbShareKey(Class<?> factoryClass,
                         Class<?> resourceType,
                         DatabaseConnectorConfig.Transport transport,
                         String endpoint,
                         DatabaseType type,
                         Option<String> username,
                         Option<String> password,
                         PoolConfig poolConfig,
                         Map<String, String> properties,
                         Option<String> applicationName) {
    /// The key for a factory whose connector does not send an application name: the configured one (absent or explicit)
    /// is part of the key all the same, so two configurations that differ in it never share.
    public static Option<Object> dbShareKey(Class<?> factoryClass,
                                            Class<?> resourceType,
                                            DatabaseConnectorConfig config,
                                            DatabaseConnectorConfig.Transport transport) {
        return dbShareKey(factoryClass, resourceType, config, transport, config.applicationName());
    }

    /// The key with the application name the connector actually sends (pg-async derives one when none is configured), so
    /// a derived name and the same name written explicitly give one key. `none()` for `pool_scope = "slice"`.
    public static Option<Object> dbShareKey(Class<?> factoryClass,
                                            Class<?> resourceType,
                                            DatabaseConnectorConfig config,
                                            DatabaseConnectorConfig.Transport transport,
                                            Option<String> applicationName) {
        return config.poolScope()
                     .filter(PoolScope.SLICE::equals)
                     .fold(() -> some(new DbShareKey(factoryClass,
                                                     resourceType,
                                                     transport,
                                                     endpoint(config, transport),
                                                     config.effectiveType(transport),
                                                     config.effectiveUsername(transport),
                                                     config.effectivePassword(transport),
                                                     config.poolConfig(),
                                                     Map.copyOf(config.properties()),
                                                     applicationName)),
                           _ -> none());
    }

    private static String endpoint(DatabaseConnectorConfig config, DatabaseConnectorConfig.Transport transport) {
        return switch (transport) {
            case JDBC -> config.effectiveJdbcUrl();
            case R2DBC -> config.effectiveR2dbcUrl();
            case ASYNC -> config.effectiveHost(transport) + ":" + config.effectivePort(transport) + "/" + config.effectiveDatabase(transport);
        };
    }

    @Override
    public String toString() {
        return "DbShareKey[" + factoryClass.getSimpleName() + ", " + resourceType.getSimpleName() + ", [REDACTED]]";
    }
}

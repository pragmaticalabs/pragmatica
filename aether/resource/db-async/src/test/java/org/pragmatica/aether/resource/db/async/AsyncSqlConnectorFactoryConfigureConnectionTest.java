// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.resource.db.async;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.postgres.net.ConnectibleBuilder;
import org.pragmatica.postgres.net.netty.NettyConnectibleBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.resource.db.DatabaseConnectorConfig.databaseConnectorConfigBuilder;

/**
 * #769 consumer-level pin: {@code AsyncSqlConnectorFactory.configureConnection} must read the
 * effective (override-resolved) host/port/database from {@code DatabaseConnectorConfig}. Exercised
 * via reflection since the method is private static (established pattern in this codebase for
 * testing private helpers without widening production visibility). Goes red if
 * {@code DatabaseConnectorConfig}'s override-precedence fix (#769) is reverted.
 */
class AsyncSqlConnectorFactoryConfigureConnectionTest {

    @Test
    void configureConnection_withAsyncUrlOverride_appliesTheOverrideHostPortDatabase() {
        var config = databaseConnectorConfigBuilder()
                .withName("db")
                .withHost("discrete-host")
                .withPort(5432)
                .withDatabase("discrete-db")
                .withAsyncUrl("postgresql://override-host:6543/override-db")
                .build()
                .unwrap();

        var builder = new InspectableBuilder();
        invokeConfigureConnection(builder, config);

        var properties = builder.configuration();
        assertThat(properties.hostname()).isEqualTo("override-host");
        assertThat(properties.port()).isEqualTo(6543);
        assertThat(properties.database()).isEqualTo("override-db");
    }

    private static void invokeConfigureConnection(NettyConnectibleBuilder builder, DatabaseConnectorConfig config) {
        try {
            Method method = AsyncSqlConnectorFactory.class.getDeclaredMethod("configureConnection",
                                                                             NettyConnectibleBuilder.class,
                                                                             DatabaseConnectorConfig.class);
            method.setAccessible(true);
            method.invoke(null, builder, config);
        } catch (ReflectiveOperationException e) {
            fail("configureConnection invocation failed: " + e);
        }
    }

    private static final class InspectableBuilder extends NettyConnectibleBuilder {
        ConnectibleBuilder.ConnectibleConfiguration configuration() {
            return properties;
        }
    }
}

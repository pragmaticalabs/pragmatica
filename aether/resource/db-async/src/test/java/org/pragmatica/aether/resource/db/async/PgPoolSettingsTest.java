// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.async;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.PoolConfig;
import org.pragmatica.postgres.net.ConnectibleBuilder;
import org.pragmatica.postgres.net.netty.NettyConnectibleBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.db.DatabaseConnectorConfig.databaseConnectorConfigBuilder;

/// #2948: which `application_name` the pg-async factories hand to the driver.
class PgPoolSettingsTest {
    private String savedNodeId;

    @BeforeEach
    void saveNodeId() {
        savedNodeId = System.getProperty(PgPoolSettings.NODE_ID_PROPERTY);
        System.clearProperty(PgPoolSettings.NODE_ID_PROPERTY);
    }

    @AfterEach
    void restoreNodeId() {
        if (savedNodeId == null) {
            System.clearProperty(PgPoolSettings.NODE_ID_PROPERTY);
        } else {
            System.setProperty(PgPoolSettings.NODE_ID_PROPERTY, savedNodeId);
        }
    }

    private static DatabaseConnectorConfig config(String name, String applicationName) {
        return databaseConnectorConfigBuilder().withName(name)
                                               .withAsyncUrl("postgresql://localhost:5432/db")
                                               .withApplicationName(applicationName)
                                               .build()
                                               .unwrap();
    }

    @Test
    void applicationName_isTheConfiguredValue_whenSet() {
        System.setProperty(PgPoolSettings.NODE_ID_PROPERTY, "node-7");

        assertThat(PgPoolSettings.applicationName(config("orders", "bench-arm-a"))).isEqualTo("bench-arm-a");
    }

    @Test
    void applicationName_isAetherNodeId_whenNodePublishesIdentity() {
        System.setProperty(PgPoolSettings.NODE_ID_PROPERTY, "node-7");

        assertThat(PgPoolSettings.applicationName(config("orders", null))).isEqualTo("aether-node-7");
    }

    @Test
    void applicationName_isAetherDatasourceName_whenNoNodeIdentity() {
        assertThat(PgPoolSettings.applicationName(config("orders", null))).isEqualTo("aether-orders");
    }

    @Test
    void applicationName_derivedValue_isCutAtSixtyThreeBytes() {
        var derived = PgPoolSettings.applicationName(config("d".repeat(100), null));

        assertThat(derived.getBytes(StandardCharsets.UTF_8)).hasSize(63);
        assertThat(derived).startsWith("aether-d");
    }

    @Test
    void applicationName_ignoresABlankNodeId_andFallsBackToTheDatasourceName() {
        System.setProperty(PgPoolSettings.NODE_ID_PROPERTY, "  ");

        assertThat(PgPoolSettings.applicationName(config("orders", null))).isEqualTo("aether-orders");
    }

    @Test
    void applicationName_replacesNonPrintableAscii_withQuestionMarks() {
        assertThat(PgPoolSettings.applicationName(config("orders", "a\tb\u00e9c\u65e5d\uD83D\uDE00e~ \u007f"))).isEqualTo("a?b?c?d?e~ ?");
    }

    @Test
    void applicationName_multibyteConfiguredName_isSentAsExactlyOneQuestionMarkPerCodePoint() {
        var name = PgPoolSettings.applicationName(config("orders", "\u65e5".repeat(21)));

        assertThat(name).isEqualTo("?".repeat(21));
    }

    @Test
    void applicationName_derivedFromANonAsciiDatasourceName_isPrintableAscii() {
        assertThat(PgPoolSettings.applicationName(config("z\u00f3\u0142w", null))).isEqualTo("aether-z??w");
    }

    @Test
    void applicationName_derivedMultibyteName_cutAtSixtyThreeCharacters_hasNoReplacementCharacter() {
        var name = PgPoolSettings.applicationName(config("\u65e5".repeat(100), null));

        assertThat(name).isEqualTo("aether-" + "?".repeat(56));
        assertThat(name).hasSize(63);
    }

    @Test
    void configureSession_handsTheNameToTheDriver() {
        var builder = new InspectableBuilder();

        PgPoolSettings.configureSession(builder, config("orders", "bench-arm-a"));

        assertThat(builder.configuration().applicationName()).isEqualTo("bench-arm-a");
    }

    // FER pin: warm-up against an unreachable database must not fail provisioning (the pool opens sessions on demand).
    @Test
    void provision_succeeds_whenDatabaseUnreachable_andMinConnectionsPositive() {
        var pool = PoolConfig.poolConfigBuilder().withMinConnections(2).withMaxConnections(4).build().unwrap();
        var config = databaseConnectorConfigBuilder().withName("unreachable")
                                                     .withAsyncUrl("postgresql://127.0.0.1:1/db")
                                                     .withUsername("u")
                                                     .withPassword("p")
                                                     .withPoolConfig(pool)
                                                     .build()
                                                     .unwrap();
        var connector = new PgSqlConnectorFactory().provision(config).await();

        assertThat(connector.isSuccess()).isTrue();
        connector.onSuccess(c -> c.stop().await());
    }

    private static final class InspectableBuilder extends NettyConnectibleBuilder {
        ConnectibleBuilder.ConnectibleConfiguration configuration() {
            return properties;
        }
    }
}

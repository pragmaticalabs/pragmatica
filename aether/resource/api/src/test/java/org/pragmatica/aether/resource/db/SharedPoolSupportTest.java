// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import java.net.URL;
import java.net.URLClassLoader;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2973: the helpers the SQL factories share — the driver class loader (D2.8) and the PostgreSQL-only probe (D6.1).
class SharedPoolSupportTest {
    @Test
    void underClassLoaderOf_runsWithTheOwnersLoader_andRestoresTheCallers() {
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        var sliceLoader = new URLClassLoader(new URL[0], previous);

        thread.setContextClassLoader(sliceLoader);

        try {
            var seen = SharedPoolSupport.underClassLoaderOf(SharedPoolSupport.class,
                                                            () -> Thread.currentThread().getContextClassLoader());

            assertThat(seen).isSameAs(SharedPoolSupport.class.getClassLoader());
            assertThat(thread.getContextClassLoader()).isSameAs(sliceLoader);
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @Test
    void probe_runsForPostgresOnly() {
        assertThat(SharedPoolSupport.probe(new SqlConnectorViewTest.PinnedPool()).isPresent()).isTrue();
        assertThat(SharedPoolSupport.probe(new MySqlPool()).isEmpty()).isTrue();
    }

    @Test
    void serverProbe_emptyAddress_isAUnixSocket() {
        assertThat(SharedPoolSupport.serverProbe(3, "", 0).addr().isEmpty()).isTrue();
        assertThat(SharedPoolSupport.serverProbe(3, "10.0.0.1", 5432).addr().isPresent()).isTrue();
    }

    @Test
    void poolDemand_carriesTheConfiguredServer_andNoSecret() {
        var config = DatabaseConnectorConfig.databaseConnectorConfigBuilder()
                                            .withAsyncUrl("postgresql://db.local:6543/app")
                                            .withUsername("u")
                                            .withPassword("secret")
                                            .build()
                                            .unwrap();
        var demand = SharedPoolSupport.poolDemand(config, DatabaseConnectorConfig.Transport.ASYNC, 3);

        assertThat(demand.warm()).isEqualTo(3);
        assertThat(demand.max()).isEqualTo(config.poolConfig().maxConnections());
        assertThat(demand.configured().subject()).isEqualTo("db.local:6543");
        assertThat(demand.endpoint()).isEqualTo("db.local:6543/app (u)").doesNotContain("secret");
        assertThat(demand.probeTimeout().millis()).isEqualTo(config.poolConfig().connectionTimeout().toMillis());
    }

    private static final class MySqlPool extends SqlConnectorViewTest.PinnedPoolBase {
        @Override
        public DatabaseType databaseType() {
            return DatabaseType.MYSQL;
        }
    }
}

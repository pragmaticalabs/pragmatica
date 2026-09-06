// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.AsyncCloseable;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.SpiResourceProvider.spiResourceProvider;
import static org.pragmatica.aether.resource.db.DatabaseConnectorConfig.databaseConnectorConfig;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// The DB family's release verb is `stop()`, which was a THIRD close convention invisible to
/// `ResourceFactory`'s default dispatch — so every connection pool was left open at slice unload
/// while the close reported success (#891).
///
/// [DatabaseConnector] now extends [AsyncCloseable] with `close()` delegating to `stop()`, which
/// fixes all seven DB factories without touching any of them. These tests assert `stop()` actually
/// RAN, not that the close promise succeeded — it succeeded before the fix too.
class DatabaseConnectorCloseTest {
    private static final TimeSpan TIMEOUT = timeSpan(5).seconds();
    private static final String SECTION = "database";

    private static final class StoppingConnector implements DatabaseConnector {
        private final AtomicBoolean stopped = new AtomicBoolean(false);

        @Override
        public DatabaseConnectorConfig config() {
            return connectorConfig();
        }

        @Override
        public Promise<Boolean> isHealthy() {
            return Promise.success(Boolean.TRUE);
        }

        @Override
        public Promise<Unit> stop() {
            stopped.set(true);

            return Promise.unitPromise();
        }

        boolean isStopped() {
            return stopped.get();
        }
    }

    /// No `close` override — the inherited default dispatch is what is under test.
    private static final class StoppingConnectorFactory implements ResourceFactory<DatabaseConnector, DatabaseConnectorConfig> {
        private final StoppingConnector connector = new StoppingConnector();

        @Override
        public Class<DatabaseConnector> resourceType() {
            return DatabaseConnector.class;
        }

        @Override
        public Class<DatabaseConnectorConfig> configType() {
            return DatabaseConnectorConfig.class;
        }

        @Override
        public Promise<DatabaseConnector> provision(DatabaseConnectorConfig config) {
            return Promise.success(connector);
        }
    }

    private static DatabaseConnectorConfig connectorConfig() {
        return databaseConnectorConfig("mydb", DatabaseType.POSTGRESQL, "localhost", "testdb", "user", "pass").unwrap();
    }

    @Test
    void connector_isAsyncCloseable_soReleaseDispatchCanSeeIt() {
        assertThat(new StoppingConnector()).isInstanceOf(AsyncCloseable.class);
    }

    @Test
    void close_delegatesToStop() {
        var connector = new StoppingConnector();

        connector.close()
                 .await(TIMEOUT);

        assertThat(connector.isStopped()).isTrue();
    }

    /// The whole chain: a factory that overrides no close, released through the provider, must stop
    /// the connector. This is the path every one of the seven DB factories takes.
    @Test
    void releaseAll_stopsTheConnector_throughTheInheritedDefaultClose() {
        var factory = new StoppingConnectorFactory();
        var provider = spiResourceProvider(List.of(factory), (_, _) -> Result.success(connectorConfig()));
        var context = ProvisioningContext.provisioningContext()
                                         .withExtension(String.class, "slice-a");

        provider.provide(DatabaseConnector.class, SECTION, context)
                .await(TIMEOUT);

        assertThat(factory.connector.isStopped()).isFalse();

        provider.releaseAll("slice-a")
                .await(TIMEOUT);

        assertThat(factory.connector.isStopped()).isTrue();
    }
}

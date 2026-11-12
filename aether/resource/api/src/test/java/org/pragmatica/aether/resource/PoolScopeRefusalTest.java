// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.resource;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.SharedPoolFixtures.FakeConnector;
import org.pragmatica.aether.resource.SharedPoolFixtures.FakeShareableFactory;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.source.TomlConfigSource;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.SharedPoolFixtures.TIMEOUT;

/// D4.2 = (c): `DatabaseConnectorConfig` is not `@StrictKeys`, so the three spellings of `pool_scope` that bind to
/// nothing are refused by the provider's composite loader (`MisplacedPoolScope`), and nothing else is.
class PoolScopeRefusalTest {
    private static final String DB = "[db]\nname = \"db\"\nasync_url = \"postgresql://u:p@h:5432/d\"\n";

    private final FakeShareableFactory inner = new FakeShareableFactory();

    private final ResourceFactory<FakeConnector, DatabaseConnectorConfig> factory = new ResourceFactory<>() {
        @Override
        public Class<FakeConnector> resourceType() {
            return FakeConnector.class;
        }

        @Override
        public Class<DatabaseConnectorConfig> configType() {
            return DatabaseConnectorConfig.class;
        }

        @Override
        public Promise<FakeConnector> provision(DatabaseConnectorConfig config) {
            return Promise.success(inner.newPool());
        }
    };

    private Result<FakeConnector> provide(String... tomlLayers) {
        var builder = ConfigurationProvider.builder();

        for (var layer : tomlLayers) {
            builder.withSource(TomlConfigSource.tomlConfigSource(layer)
                                               .unwrap());
        }

        var context = ProvisioningContext.provisioningContext()
                                         .withExtension(ConfigurationProvider.class, builder.build())
                                         .withExtension(String.class, "slice-a");

        return SpiResourceProvider.spiResourceProvider(List.of(factory), (_, _) -> Result.success(null))
                                  .provide(FakeConnector.class, "db", context)
                                  .await(TIMEOUT);
    }

    private static String message(Result<?> result) {
        return result.fold(cause -> cause.message(), _ -> "");
    }

    @Test
    void validConfig_provisions() {
        assertThat(provide(DB).isSuccess()).isTrue();
    }

    @Test
    void ticketingStyle_forgeDatabaseEnabledFalse_layeredWithSliceDatabase_provisions() {
        var forge = "[database]\nenabled = false\n";
        var slice = "[database]\nname = \"db\"\nasync_url = \"postgresql://u:p@h:5432/d\"\n[database.pool_config]\nmin_connections = 4\nmax_connections = 20\n";
        var composite = ConfigurationProvider.builder()
                                             .withSource(TomlConfigSource.tomlConfigSource(forge).unwrap())
                                             .withSource(TomlConfigSource.tomlConfigSource(slice).unwrap())
                                             .build();
        var context = ProvisioningContext.provisioningContext()
                                         .withExtension(ConfigurationProvider.class, composite)
                                         .withExtension(String.class, "slice-a");
        var result = SpiResourceProvider.spiResourceProvider(List.of(factory), (_, _) -> Result.success(null))
                                        .provide(FakeConnector.class, "database", context)
                                        .await(TIMEOUT);

        assertThat(result.isSuccess()).as(message(result)).isTrue();
    }

    @Test
    void dashedPoolScope_isRefused_namingPoolScope() {
        var result = provide(DB + "pool-scope = \"slice\"\n");

        assertThat(result.isFailure()).isTrue();
        assertThat(message(result)).contains("pool_scope")
                              .contains("belongs in the datasource section");
    }

    @Test
    void camelCasePoolScope_isRefused_namingPoolScope() {
        var result = provide(DB + "poolScope = \"slice\"\n");

        assertThat(result.isFailure()).isTrue();
        assertThat(message(result)).contains("pool_scope")
                              .contains("belongs in the datasource section");
    }

    @Test
    void poolScopeUnderPoolConfig_isRefused_namingPoolScope() {
        var result = provide(DB + "[db.pool_config]\npool_scope = \"slice\"\n");

        assertThat(result.isFailure()).isTrue();
        assertThat(message(result)).contains("pool_scope")
                              .contains("belongs in the datasource section");
    }
}

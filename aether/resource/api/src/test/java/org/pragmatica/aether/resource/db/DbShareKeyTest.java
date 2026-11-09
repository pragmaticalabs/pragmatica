// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig.Transport;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.ProviderBasedConfigService;
import org.pragmatica.config.source.TomlConfigSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.db.DatabaseConnectorConfig.databaseConnectorConfigBuilder;

/// #2973 §1 and §4: the share key is built from effective values, and `pool_scope` binds strictly.
class DbShareKeyTest {
    private static final String ASYNC = "postgresql://h:5432/db";

    private static DatabaseConnectorConfig.Builder base() {
        return databaseConnectorConfigBuilder().withName("db")
                                               .withAsyncUrl(ASYNC)
                                               .withJdbcUrl("jdbc:postgresql://h:5432/db")
                                               .withR2dbcUrl("r2dbc:postgresql://h:5432/db")
                                               .withUsername("u")
                                               .withPassword("p");
    }

    private static Option<Object> key(DatabaseConnectorConfig config, Transport transport) {
        return DbShareKey.dbShareKey(DbShareKeyTest.class, SqlConnector.class, config, transport);
    }

    /// Binds `[db]` from a TOML source, the file-backed layer production reads.
    private static Result<DatabaseConnectorConfig> bound(String extraLines) {
        var source = TomlConfigSource.tomlConfigSource("[db]\nname = \"db\"\nasync_url = \"" + ASYNC + "\"\n" + extraLines)
                                     .unwrap();
        var provider = ConfigurationProvider.builder().withSource(source).build();

        return ProviderBasedConfigService.providerBasedConfigService(provider).config("db", DatabaseConnectorConfig.class);
    }

    @Nested
    class EffectiveValues {
        @Test
        void absentDefaults_andExplicitDefaults_giveOneKey() { // T3b
            var implicit = databaseConnectorConfigBuilder().withAsyncUrl("postgresql://h/db").build().unwrap();
            var explicit = databaseConnectorConfigBuilder().withAsyncUrl("postgresql://h:5432/db")
                                                           .withPoolScope(PoolScope.NODE)
                                                           .build()
                                                           .unwrap();

            assertThat(key(implicit, Transport.ASYNC)).isEqualTo(key(explicit, Transport.ASYNC));
        }

        @Test
        void equalDurationsInDifferentUnits_giveOneKey() { // T3b (TimeSpan compares by value)
            var seconds = base().withPoolConfig(PoolConfig.DEFAULT).build().unwrap();
            var millis = base().withPoolConfig(new PoolConfig(4,
                                                              20,
                                                              TimeSpan.timeSpan("30000ms").unwrap(),
                                                              TimeSpan.timeSpan("10m").unwrap(),
                                                              TimeSpan.timeSpan("30m").unwrap(),
                                                              Option.none(),
                                                              TimeSpan.timeSpan("0s").unwrap(),
                                                              0))
                               .build()
                               .unwrap();

            assertThat(key(seconds, Transport.ASYNC)).isEqualTo(key(millis, Transport.ASYNC));
        }

        @Test
        void theDatasourceName_isNotPartOfTheKey() {
            var one = base().withName("primary").build().unwrap();
            var two = base().withName("secondary").build().unwrap();

            assertThat(key(one, Transport.JDBC)).isEqualTo(key(two, Transport.JDBC));
        }

        @Test
        void everyInputTheTransportReads_changesTheKey() { // T3c
            var reference = base().build().unwrap();
            var variants = Map.of("async endpoint", base().withAsyncUrl("postgresql://other:5432/db").build().unwrap(),
                                  "jdbc endpoint", base().withJdbcUrl("jdbc:postgresql://h:5433/db").build().unwrap(),
                                  "r2dbc endpoint", base().withR2dbcUrl("r2dbc:postgresql://h:5432/other").build().unwrap(),
                                  "user", base().withUsername("v").build().unwrap(),
                                  "password", base().withPassword("q").build().unwrap(),
                                  "pool max", base().withPoolConfig(new PoolConfig(4, 21, PoolConfig.DEFAULT.connectionTimeout(), PoolConfig.DEFAULT.idleTimeout(), PoolConfig.DEFAULT.maxLifetime(), Option.none(), PoolConfig.DEFAULT.leakDetectionTimeout(), 0)).build().unwrap(),
                                  "properties", base().withProperties(Map.of("ssl", "true")).build().unwrap(),
                                  "application name", base().withApplicationName("app").build().unwrap());
            var transportOf = Map.of("async endpoint", Transport.ASYNC, "jdbc endpoint", Transport.JDBC, "r2dbc endpoint", Transport.R2DBC);

            variants.forEach((input, variant) -> {
                var transports = transportOf.containsKey(input)
                                 ? java.util.List.of(transportOf.get(input))
                                 : java.util.List.of(Transport.values());

                transports.forEach(transport -> assertThat(key(variant, transport)).as("%s over %s", input, transport)
                                                                                   .isNotEqualTo(key(reference, transport)));
            });
        }

        @Test
        void factoryAndResourceType_arePartOfTheKey() {
            var config = base().build().unwrap();

            assertThat(DbShareKey.dbShareKey(String.class, SqlConnector.class, config, Transport.ASYNC))
                .isNotEqualTo(DbShareKey.dbShareKey(Integer.class, SqlConnector.class, config, Transport.ASYNC));
            assertThat(DbShareKey.dbShareKey(String.class, SqlConnector.class, config, Transport.ASYNC))
                .isNotEqualTo(DbShareKey.dbShareKey(String.class, PgSqlConnector.class, config, Transport.ASYNC));
        }

        @Test
        void mutatingThePropertiesAfterwards_keepsTheKey() { // T3d
            var live = new HashMap<String, String>(Map.of("ssl", "true"));
            var config = base().withProperties(live).build().unwrap();
            var before = key(config, Transport.JDBC);

            live.put("ssl", "false");

            assertThat(before).as("the key already in use keeps its inputs")
                              .isEqualTo(key(base().withProperties(Map.of("ssl", "true")).build().unwrap(), Transport.JDBC));
        }

        @Test
        void poolScopeSlice_hasNoKey_andTheKeyNeverPrintsCredentials() {
            var config = base().withPoolScope(PoolScope.SLICE).build().unwrap();

            assertThat(key(config, Transport.ASYNC).isEmpty()).isTrue();
            assertThat(key(base().build().unwrap(), Transport.JDBC).toString()).doesNotContain("p@", "jdbc:", "\"p\"", "h:5432");
        }
    }

    @Nested
    class PoolScopeBinding {
        @Test
        void sliceInAnyCase_binds() { // T6b
            assertThat(bound("pool_scope = \"SLICE\"\n").unwrap().poolScope()).isEqualTo(Option.some(PoolScope.SLICE));
            assertThat(bound("pool_scope = \"Slice\"\n").unwrap().poolScope()).isEqualTo(Option.some(PoolScope.SLICE));
            assertThat(bound("pool_scope = \"node\"\n").unwrap().poolScope()).isEqualTo(Option.some(PoolScope.NODE));
            assertThat(bound("").unwrap().poolScope()).isEqualTo(Option.none());
        }

        /// D4.2 = (c): no `@StrictKeys` on the datasource record (forge.toml's `[database]` carries Ember keys), so the
        /// binder itself ignores unknown keys; the three `pool_scope` misspellings are refused one layer up, see
        /// `PoolScopeRefusalTest`.
        @Test
        void unknownKeys_areIgnoredByTheBinder_theRefusalLivesInTheProviderLoader() { // T6b
            assertThat(bound("pool-scope = \"slice\"\n").isSuccess()).as("pool-scope").isTrue();
            assertThat(bound("enabled = false\n").isSuccess()).as("Ember key").isTrue();
        }

        /// G2: `PoolConfig` keeps `@StrictKeys` (nothing in forge.toml writes under `[database.pool_config]`), so a
        /// misspelled pool key is refused by the binder rather than silently defaulting.
        @Test
        void unknownPoolConfigKey_isRefusedByTheBinder() {
            assertThat(bound("[db.pool_config]\nmax_conections = 5\n").isFailure()).isTrue();
        }

        @Test
        void unknownValue_failsAndNeverDefaults() { // T10
            assertThat(bound("pool_scope = \"per-slice\"\n").isFailure()).isTrue();
            assertThat(bound("pool_scope = \"node \"\n").isFailure()).isTrue();
        }
    }
}

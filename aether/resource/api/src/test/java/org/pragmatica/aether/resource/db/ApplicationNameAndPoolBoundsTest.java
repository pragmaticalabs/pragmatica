// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.

package org.pragmatica.aether.resource.db;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.ProviderBasedConfigService;
import org.pragmatica.config.source.MapConfigSource;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.db.DatabaseConnectorConfig.databaseConnectorConfigBuilder;
import static org.pragmatica.lang.Option.some;

/// #2948: `application_name` and the `0 <= min_connections <= max_connections` bound refuse an invalid configuration
/// at load, both through the builder and through the reflective TOML/config binder that production uses.
class ApplicationNameAndPoolBoundsTest {
    private static final String URL = "postgresql://localhost:5432/db";

    private static Result<DatabaseConnectorConfig> named(String applicationName) {
        return databaseConnectorConfigBuilder().withName("db").withAsyncUrl(URL).withApplicationName(applicationName).build();
    }

    private static Result<DatabaseConnectorConfig> bound(Map<String, String> extra) {
        var values = new HashMap<>(Map.of("db.name", "db", "db.async_url", URL));

        values.putAll(extra);
        var source = MapConfigSource.mapConfigSource("test", values).unwrap();
        var provider = ConfigurationProvider.builder().withSource(source).build();

        return ProviderBasedConfigService.providerBasedConfigService(provider).config("db", DatabaseConnectorConfig.class);
    }

    @Test
    void applicationName_absentByDefault() {
        assertThat(databaseConnectorConfigBuilder().withName("db").withAsyncUrl(URL).build().unwrap().applicationName().isEmpty()).isTrue();
    }

    @Test
    void applicationName_accepted_whenWithinLimit() {
        assertThat(named("bench-arm-a").unwrap().applicationName()).isEqualTo(some("bench-arm-a"));
    }

    @Test
    void applicationName_accepted_atSixtyThreeBytes() {
        assertThat(named("a".repeat(63)).isSuccess()).isTrue();
    }

    @Test
    void applicationName_refused_atSixtyFourBytes() {
        assertThat(named("a".repeat(64)).isFailure()).isTrue();
    }

    @Test
    void applicationName_refused_whenMultibyteExceedsBytesNotChars() {
        assertThat(named("é".repeat(32)).isFailure()).isTrue();
    }

    @Test
    void applicationName_refused_whenBlank() {
        assertThat(named("   ").isFailure()).isTrue();
    }

    @Test
    void applicationName_refused_whenContainsNul() {
        assertThat(named("a\0b").isFailure()).isTrue();
    }

    @Test
    void poolConfig_refused_whenMinAboveMax() {
        assertThat(PoolConfig.poolConfigBuilder().withMinConnections(5).withMaxConnections(4).build().isFailure()).isTrue();
    }

    @Test
    void poolConfig_refused_whenMinNegative() {
        assertThat(PoolConfig.poolConfigBuilder().withMinConnections(-1).build().isFailure()).isTrue();
    }

    @Test
    void poolConfig_refused_whenMaxBelowOne() {
        assertThat(PoolConfig.poolConfigBuilder().withMinConnections(0).withMaxConnections(0).build().isFailure()).isTrue();
    }

    @Test
    void poolConfig_accepted_whenMinEqualsMaxOrZero() {
        assertThat(PoolConfig.poolConfigBuilder().withMinConnections(4).withMaxConnections(4).build().isSuccess()).isTrue();
        assertThat(PoolConfig.poolConfigBuilder().withMinConnections(0).withMaxConnections(1).build().isSuccess()).isTrue();
    }

    @Test
    void binder_bindsApplicationNameAndMinConnections() {
        var config = bound(Map.of("db.application_name", "arm-b", "db.pool_config.min_connections", "2")).unwrap();

        assertThat(config.applicationName()).isEqualTo(some("arm-b"));
        assertThat(config.poolConfig().minConnections()).isEqualTo(2);
    }

    @Test
    void binder_leavesApplicationNameAbsent_andMinAtDefault() {
        var config = bound(Map.of()).unwrap();

        assertThat(config.applicationName().isEmpty()).isTrue();
        assertThat(config.poolConfig().minConnections()).isEqualTo(PoolConfig.DEFAULT.minConnections());
    }

    @Test
    void binder_refusesLoad_whenApplicationNameTooLong() {
        assertThat(bound(Map.of("db.application_name", "x".repeat(64))).isFailure()).isTrue();
    }

    @Test
    void binder_refusesLoad_whenMinAboveMax() {
        assertThat(bound(Map.of("db.pool_config.min_connections", "9", "db.pool_config.max_connections", "3")).isFailure()).isTrue();
    }
}

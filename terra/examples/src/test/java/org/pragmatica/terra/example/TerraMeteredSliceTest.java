// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.example;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.SpiResourceProvider;
import org.pragmatica.aether.slice.ResourceProviderFacade;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.terra.TerraApplication;
import org.pragmatica.terra.TerraBlueprint;
import org.pragmatica.terra.TerraFactory;
import org.pragmatica.terra.TerraMetrics;
import org.pragmatica.terra.TerraMigrations;
import org.pragmatica.terra.example.metered.Metered;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #3650 done-when, metrics half: a Terra app whose generated factory declares a `@Metrics` interceptor starts (it
/// could not before: the provisioning context carried no `MeterRegistry`), and a Prometheus scrape returns the
/// method's metrics.
class TerraMeteredSliceTest {
    private static final String METERED = "org.pragmatica.example:terra-metered:1.0.0-rc5";

    @Test
    void start_meteredSlice_startsAndThePrometheusScrapeReturnsTheMethodMetrics() {
        var config = ConfigurationProvider.builder()
                                          .withDefaults(Map.of("metrics.greet.name", "terra.greet"))
                                          .build();
        var app = TerraApplication.start(new TerraBlueprint(List.of(METERED)),
                                         _ -> config)
                                  .await(timeSpan(10).seconds());

        assertThat(app.<String>fold(cause -> cause.message(), _ -> "started")).isEqualTo("started");
        assertThat(app.unwrap().metrics().prometheusScrape().isEmpty()).as("no Prometheus sink unless configured").isTrue();
        assertThat(app.unwrap().close().await(timeSpan(10).seconds()).isSuccess()).isTrue();

        var exported = TerraApplication.start(new TerraBlueprint(List.of(METERED)),
                                              factories(),
                                              _ -> config,
                                              resources(),
                                              TerraMigrations.noMigrations(),
                                              TerraMetrics.Settings.settings(true, Option.none()))
                                       .await(timeSpan(10).seconds())
                                       .unwrap();

        try {
            assertThat(exported.slice(Metered.class).unwrap().greet("terra").await(timeSpan(5).seconds()).unwrap())
                .isEqualTo("hello terra");
            assertThat(exported.metrics().prometheusScrape().unwrap())
                .contains("terra_greet_success_seconds_count", "terra_greet_success_count_total 1.0");
        } finally {
            assertThat(exported.close().await(timeSpan(10).seconds()).isSuccess()).isTrue();
        }
    }

    private static List<TerraFactory<?>> factories() {
        return ServiceLoader.load(TerraFactory.class)
                            .stream()
                            .<TerraFactory<?>> map(ServiceLoader.Provider::get)
                            .toList();
    }

    private static ResourceProviderFacade resources() {
        return SpiResourceProvider.spiResourceProvider((_, _) -> Causes.cause("unscoped").result())
                                  .facade();
    }
}

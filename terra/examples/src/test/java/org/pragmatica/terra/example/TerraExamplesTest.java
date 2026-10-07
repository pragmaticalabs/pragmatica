// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.terra.example;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.example.catalog.Catalog;
import org.pragmatica.aether.example.catalog.CatalogRoutes;
import org.pragmatica.aether.example.pricing.PricingService;
import org.pragmatica.aether.example.shared.Money;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.terra.TerraApplication;
import org.pragmatica.terra.TerraBlueprint;
import org.pragmatica.terra.example.shop.Shop;
import org.pragmatica.terra.example.sink.Sink;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class TerraExamplesTest {
    @Test void run_demo_closesApplicationAfterDelivery() {
        assertThat(TerraDemo.run().await(timeSpan(10).seconds()).unwrap()).isEqualTo(1);
    }

    @Test void start_existingExamplesAndComposition_deliversCallsAndTopics() throws Exception {
        String toml;
        try (var input = getClass().getResourceAsStream("/blueprint.toml")) {
            toml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        var config = ConfigurationProvider.builder().withDefaults(Map.of(
            "cache.calls.cache_name", "calls", "cache.calls.strategy", "CACHE_ASIDE",
            "cache.calls.ttl_seconds", "60", "cache.calls.max_entries", "100", "cache.calls.mode", "LOCAL")).build();
        var provider = org.pragmatica.aether.resource.SpiResourceProvider.spiResourceProvider();
        var factories = java.util.ServiceLoader.load(org.pragmatica.terra.TerraFactory.class).stream()
            .<org.pragmatica.terra.TerraFactory<?>>map(java.util.ServiceLoader.Provider::get).toList();
        var app = TerraApplication.start(TerraBlueprint.parse(toml).unwrap(), factories, _ -> config, provider.facade())
                                  .await(timeSpan(10).seconds()).unwrap();
        try {
            var catalog = app.slice(Catalog.class).unwrap();
            assertThat(catalog.listV2(new Catalog.ListRequest()).await(timeSpan(5).seconds()).unwrap().items()).isNotEmpty();
            var response = new CatalogRoutes().create(catalog).handle(HttpRequestContext.httpRequestContext(
                "/api/catalog/v2/items", "GET", Map.of(), Map.of(), "terra-test")).await(timeSpan(5).seconds()).unwrap();
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(new String(response.body(), StandardCharsets.UTF_8)).contains("items");
            var pricing = app.slice(PricingService.class).unwrap();
            assertThat(pricing.calculateTax(new PricingService.CalculateTaxRequest(Money.usd("100").unwrap(), org.pragmatica.aether.example.shared.Address.address("1 Main", "Portland", "OR", "97201", "US").unwrap()))
                              .await(timeSpan(5).seconds()).isSuccess()).isTrue();
            var shop = app.slice(Shop.class).unwrap();
            assertThat(shop.visit("one").await(timeSpan(5).seconds()).unwrap()).isEqualTo(1);
            var interceptor = provider.provide(org.pragmatica.aether.resource.interceptor.CacheMethodInterceptor.class, "cache.calls",
                org.pragmatica.aether.slice.ProvisioningContext.provisioningContext()
                    .withExtension(String.class, "org.pragmatica.example:terra-counter:1.0.0-rc4")
                    .withExtension(ConfigurationProvider.class, config)).await(timeSpan(5).seconds()).unwrap();
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(interceptor.cache().get("one").await(timeSpan(5).seconds()).unwrap().unwrap()).isEqualTo(1));
            assertThat(shop.visit("one").await(timeSpan(5).seconds()).unwrap()).isEqualTo(1);
            int value = shop.visit("two").await(timeSpan(5).seconds()).unwrap();
            assertThat(value).isGreaterThan(1);
            assertThat(app.slice(Sink.class).unwrap().count().await(timeSpan(5).seconds()).unwrap()).isEqualTo(3);
        } finally {
            app.close().await(timeSpan(5).seconds()).unwrap();
        }
    }
}

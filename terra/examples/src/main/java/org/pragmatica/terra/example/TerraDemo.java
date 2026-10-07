// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.terra.example;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.terra.TerraApplication;
import org.pragmatica.terra.TerraBlueprint;
import org.pragmatica.terra.example.shop.Shop;
import org.pragmatica.terra.example.sink.Sink;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Runnable composition proof. The production HTTP host is a separate Terra milestone.
public final class TerraDemo {
    private TerraDemo() {}

    @Contract
    public static void main(String[] args) {
        run().await(timeSpan(15).seconds())
           .fold(cause -> {
                     System.err.println(cause.message());
                     System.exit(1);

                     return 1;
                 },
                 deliveries -> {
                     System.out.println("Terra: Catalog + PricingService compiled; Shop publication delivered to " + deliveries
                                       + " subscriber.");

                     return 0;
                 });
    }

    public static Promise<Integer> run() {
        return blueprint().async()
                        .flatMap(blueprint -> TerraApplication.start(blueprint,
                                                                     _ -> configuration()))
                        .flatMap(TerraDemo::exerciseAndClose);
    }

    private static Promise<Integer> exerciseAndClose(TerraApplication app) {
        return app.slice(Shop.class)
                  .async()
                  .flatMap(shop -> shop.visit("demo"))
                  .flatMap(_ -> app.slice(Sink.class)
                                   .async())
                  .flatMap(Sink::count)
                  .fold(result -> app.close()
                                     .flatMap(_ -> result.async()));
    }

    private static Result<TerraBlueprint> blueprint() {
        return Result.lift(Causes::fromThrowable,
                           () -> {
                               try (var input = TerraDemo.class.getResourceAsStream("/blueprint.toml")) {
                               return new String(java.util.Objects.requireNonNull(input, "Missing blueprint.toml")
                                                                  .readAllBytes(),
                                                 StandardCharsets.UTF_8);
                           }
                           })
                     .flatMap(TerraBlueprint::parse);
    }

    private static ConfigurationProvider configuration() {
        return ConfigurationProvider.builder()
                                    .withDefaults(Map.of("cache.calls.cache_name",
                                                         "calls",
                                                         "cache.calls.strategy",
                                                         "CACHE_ASIDE",
                                                         "cache.calls.ttl_seconds",
                                                         "60",
                                                         "cache.calls.max_entries",
                                                         "100",
                                                         "cache.calls.mode",
                                                         "LOCAL"))
                                    .build();
    }
}

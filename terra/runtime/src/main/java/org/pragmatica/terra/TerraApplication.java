// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.terra;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;

import org.pragmatica.aether.resource.SpiResourceProvider;
import org.pragmatica.aether.slice.ResourceProviderFacade;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;


/// One blueprint, one object per slice, direct typed calls. No Aether node is started.
public final class TerraApplication {
    private final Map<Class<?>, Object> slices = new LinkedHashMap<>();
    private final List<TerraContext> contexts = new ArrayList<>();
    private final TerraTopics topics = new TerraTopics();
    private final Promise<Unit> closed = Promise.promise();
    private final List<TerraMigrations.Report> migrations;
    private boolean closing;

    private TerraApplication(List<TerraMigrations.Report> migrations) {
        this.migrations = List.copyOf(migrations);
    }

    public List<TerraMigrations.Report> migrations() {
        return migrations;
    }

    /// Read an exploded blueprint and its sibling schema directory before constructing any slice.
    public static Promise<TerraApplication> start(Path blueprintFile,
                                                  Fn1<ConfigurationProvider, String> configuration,
                                                  ConfigurationProvider migrationConfiguration) {
        var path = blueprintFile.toAbsolutePath();

        return Promise.lift(Causes::fromThrowable,
                            () -> Files.readString(path))
                      .flatMap(toml -> TerraBlueprint.parse(toml).async())
                      .flatMap(blueprint -> TerraMigrations.fromDirectory(blueprint,
                                                                          path.getParent(),
                                                                          migrationConfiguration)
                                                           .flatMap(migrations -> start(blueprint,
                                                                                        configuration,
                                                                                        migrations)));
    }

    public static Promise<TerraApplication> start(TerraBlueprint blueprint,
                                                  Fn1<ConfigurationProvider, String> configuration) {
        return start(blueprint, configuration, TerraMigrations.noMigrations());
    }

    public static Promise<TerraApplication> start(TerraBlueprint blueprint,
                                                  Fn1<ConfigurationProvider, String> configuration,
                                                  TerraMigrations migrations) {
        var factories = ServiceLoader.load(TerraFactory.class)
                                     .stream()
                                     .map(ServiceLoader.Provider::get)
                                     .<TerraFactory<?>> map(factory -> factory)
                                     .toList();
        var resources = SpiResourceProvider.spiResourceProvider((section, type) -> new TerraError.InvalidGraph("Configuration must be scoped to a slice").result());

        return start(blueprint, factories, configuration, resources.facade(), migrations);
    }

    public static Promise<TerraApplication> start(TerraBlueprint blueprint,
                                                  List<TerraFactory<?>> factories,
                                                  Fn1<ConfigurationProvider, String> configuration,
                                                  ResourceProviderFacade resources) {
        return start(blueprint, factories, configuration, resources, TerraMigrations.noMigrations());
    }

    public static Promise<TerraApplication> start(TerraBlueprint blueprint,
                                                  List<TerraFactory<?>> factories,
                                                  Fn1<ConfigurationProvider, String> configuration,
                                                  ResourceProviderFacade resources,
                                                  TerraMigrations migrations) {
        return ordered(blueprint, factories).async()
                      .flatMap(order -> migrations.apply()
                                                  .flatMap(reports -> constructApplication(order,
                                                                                           configuration,
                                                                                           resources,
                                                                                           reports)));
    }

    private static Promise<TerraApplication> constructApplication(List<TerraFactory<?>> order,
                                                                  Fn1<ConfigurationProvider, String> configuration,
                                                                  ResourceProviderFacade resources,
                                                                  List<TerraMigrations.Report> reports) {
        var application = new TerraApplication(reports);

        return application.construct(order, configuration, resources)
                          .fold(result -> result.fold(application::cleanupAfterFailure,
                                                      _ -> Promise.success(application)));
    }

    private Promise<TerraApplication> cleanupAfterFailure(Cause cause) {
        return close().fold(cleanup -> cleanup.fold(cleanupCause -> Causes.composite(cause.result(),
                                                                                     cleanupCause.result())
                                                                          .promise(),
                                                    _ -> cause.promise()));
    }

    private Promise<Unit> construct(List<TerraFactory<?>> order,
                                    Fn1<ConfigurationProvider, String> configuration,
                                    ResourceProviderFacade resources) {
        var chain = Promise.unitPromise();

        for (var factory : order) {
            chain = chain.flatMap(_ -> constructOne(factory, configuration, resources));
        }

        return chain.map(_ -> topics.start());
    }

    private <T> Promise<Unit> constructOne(TerraFactory<T> factory,
                                           Fn1<ConfigurationProvider, String> configuration,
                                           ResourceProviderFacade resources) {
        return Result.lift(Causes::fromThrowable,
                           () -> contextFor(factory, configuration, resources))
                     .async()
                     .flatMap(context -> invokeFactory(factory, context).flatMap(instance -> bind(factory,
                                                                                                  context,
                                                                                                  instance).async()));
    }

    private static <T> Promise<T> invokeFactory(TerraFactory<T> factory, TerraContext context) {
        return Result.lift(Causes::fromThrowable,
                           () -> Objects.requireNonNull(factory.create(context),
                                                        "Factory returned null factory promise: " + factory.artifact()))
                     .<Promise<T>> fold(Promise::failure, promise -> promise)
                     .filter(new TerraError.InvalidGraph("Factory returned null slice: " + factory.artifact()),
                             Objects::nonNull);
    }

    private TerraContext contextFor(TerraFactory<?> factory,
                                    Fn1<ConfigurationProvider, String> configuration,
                                    ResourceProviderFacade resources) {
        var context = new TerraContext(factory.artifact(),
                                       slices,
                                       configuration.apply(factory.artifact()),
                                       resources,
                                       topics);

        contexts.add(context);

        return context;
    }

    private <T> Result<Unit> bind(TerraFactory<T> factory, TerraContext context, T instance) {
        slices.put(factory.sliceType(), instance);

        return Result.lift(Causes::fromThrowable,
                           () -> Objects.requireNonNull(factory.bind(instance, context),
                                                        "Factory returned null binding result: " + factory.artifact()))
                     .flatMap(result -> result);
    }

    public <T> Result<T> slice(Class<T> type) {
        return Option.option(slices.get(type))
                     .map(type::cast)
                     .toResult(new TerraError.InvalidGraph("Slice not in application: " + type.getName()));
    }

    public synchronized Promise<Unit> close() {
        if (!closing) {
            closing = true;
            topics.close().flatMap(_ -> releaseScopes()).withResult(closed::resolve);
        }

        return closed;
    }

    private Promise<Unit> releaseScopes() {
        var reverse = new ArrayList<>(contexts);

        Collections.reverse(reverse);
        // Each scope drains its acquisitions. Continue cleanup even when a custom provider fails.
        var chain = Promise.success(new ArrayList<Result<Unit>>());

        for (var context : reverse) {
            chain = chain.flatMap(results -> context.close()
                                                    .fold(result -> {
                                                              results.add(result);

                                                              return Promise.success(results);
                                                          }));
        }

        return chain.flatMap(results -> Result.allOf(results)
                                              .async()
                                              .mapToUnit());
    }

    /// Resolve the selected dependency graph without constructing slices or provisioning resources.
    public static Result<List<TerraFactory<?>>> plan(TerraBlueprint blueprint, List<TerraFactory<?>> available) {
        return Result.lift(Causes::fromThrowable, () -> ordered(blueprint, available)).flatMap(result -> result);
    }

    static Result<List<TerraFactory<?>>> ordered(TerraBlueprint blueprint, List<TerraFactory<?>> available) {
        if (blueprint.artifacts().isEmpty() || blueprint.artifacts().stream().distinct().count() != blueprint.artifacts()
                                                                                                             .size()) {
            return new TerraError.InvalidGraph("Blueprint selection must be nonempty and contain unique artifacts").result();
        }

        var byArtifact = new LinkedHashMap<String, TerraFactory<?>>();

        for (var factory : available) {
            if (byArtifact.putIfAbsent(factory.artifact(), factory) != null) {
                return new TerraError.InvalidGraph("Duplicate Terra artifact: " + factory.artifact()).result();
            }
        }

        var pending = new LinkedHashMap<Class<?>, TerraFactory<?>>();

        for (var artifact : blueprint.artifacts()) {
            var factory = byArtifact.get(artifact);

            if (factory == null || pending.putIfAbsent(factory.sliceType(), factory) != null) {
                return new TerraError.InvalidGraph("Missing artifact or duplicate slice type: " + artifact).result();
            }
        }

        var ordered = new ArrayList<TerraFactory<?>>();

        while (!pending.isEmpty()) {
            var ready = pending.values()
                               .stream()
                               .filter(factory -> factory.dependencies()
                                                         .stream()
                                                         .allMatch(type -> ordered.stream()
                                                                                  .anyMatch(existing -> existing.sliceType()
                                                                                                                .equals(type))))
                               .toList();

            if (ready.isEmpty()) {
                return new TerraError.InvalidGraph("Missing dependency or cycle among: " + pending.keySet()).result();
            }

            ordered.addAll(ready);
            ready.forEach(factory -> pending.remove(factory.sliceType()));
        }

        return Result.success(List.copyOf(ordered));
    }
}

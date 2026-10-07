// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.terra.launcher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import org.pragmatica.aether.resource.SpiResourceProvider;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.terra.TerraApplication;
import org.pragmatica.terra.TerraBlueprint;
import org.pragmatica.terra.TerraFactory;
import org.pragmatica.terra.TerraMigrations;
import org.pragmatica.terra.http.TerraHttpServer;


/// Immutable, checked selection and startup configuration. Loading does not construct slices,
/// acquire resources, open a listener, or instantiate authentication clients.
public final class TerraLaunchPlan {
    private final Path directory;
    private final TerraBlueprint blueprint;
    private final List<TerraFactory<?>> factories;
    private final Map<String, ConfigurationProvider> configuration;
    private final ConfigurationProvider deployment;
    private final LaunchHttp http;

    private TerraLaunchPlan(Path directory,
                            TerraBlueprint blueprint,
                            List<TerraFactory<?>> factories,
                            Map<String, ConfigurationProvider> configuration,
                            ConfigurationProvider deployment,
                            LaunchHttp http) {
        this.directory = directory;
        this.blueprint = blueprint;
        this.factories = List.copyOf(factories);
        this.configuration = Map.copyOf(configuration);
        this.deployment = deployment;
        this.http = http;
    }

    public static Promise<TerraLaunchPlan> load(Path directory) {
        return Promise.lift(Causes::fromThrowable,
                            () -> read(directory.toAbsolutePath()))
                      .flatMap(Result::async)
                      .flatMap(TerraLaunchPlan::checkMigrations);
    }

    @org.pragmatica.lang.Contract
    private static Result<TerraLaunchPlan> read(Path directory) throws Exception {
        var blueprintText = Files.readString(directory.resolve("blueprint.toml"));
        var available = ServiceLoader.load(TerraFactory.class)
                                     .stream()
                                     .<TerraFactory<?>> map(ServiceLoader.Provider::get)
                                     .toList();

        return TerraBlueprint.parse(blueprintText).flatMap(blueprint -> TerraApplication.plan(blueprint, available).flatMap(factories -> configure(directory,
                                                                                                                                                   blueprint,
                                                                                                                                                   factories)));
    }

    private static Result<TerraLaunchPlan> configure(Path directory,
                                                     TerraBlueprint blueprint,
                                                     List<TerraFactory<?>> factories) {
        return TerraConfiguration.deployment(directory).flatMap(deployment -> TerraConfiguration.host(directory,
                                                                                                      deployment)
                                                                                                .flatMap(settings -> LaunchHttp.launchHttp(directory,
                                                                                                                                           settings))
                                                                                                .flatMap(http -> configureSlices(directory,
                                                                                                                                 blueprint,
                                                                                                                                 factories,
                                                                                                                                 deployment,
                                                                                                                                 http)));
    }

    private static Result<TerraLaunchPlan> configureSlices(Path directory,
                                                           TerraBlueprint blueprint,
                                                           List<TerraFactory<?>> factories,
                                                           TerraConfiguration.Deployment deployment,
                                                           LaunchHttp http) {
        var configurations = new LinkedHashMap<String, ConfigurationProvider>();

        for (var factory : factories) {
            var config = TerraConfiguration.slice(directory, factory, deployment);

            if (config instanceof Result.Failure<ConfigurationProvider>(var cause)) {
                return cause.result();
            }

            configurations.put(factory.artifact(), config.unwrap());
        }

        return Result.success(new TerraLaunchPlan(directory,
                                                  blueprint,
                                                  factories,
                                                  configurations,
                                                  deployment.provider(),
                                                  http));
    }

    private Promise<TerraLaunchPlan> checkMigrations() {
        return TerraMigrations.fromDirectory(blueprint, directory, deployment).map(_ -> this);
    }

    public List<String> artifacts() {
        return factories.stream()
                        .map(TerraFactory::artifact)
                        .toList();
    }

    public Promise<TerraHttpServer> start() {
        var resources = SpiResourceProvider.spiResourceProvider((_, _) -> new TerraLaunchError("Resource configuration must be slice-scoped").result());

        return TerraMigrations.fromDirectory(blueprint, directory, deployment)
                              .flatMap(migrations -> TerraApplication.start(blueprint,
                                                                            factories,
                                                                            configuration::get,
                                                                            resources.facade(),
                                                                            migrations))
                              .flatMap(this::host);
    }

    private Promise<TerraHttpServer> host(TerraApplication application) {
        return Result.lift(Causes::fromThrowable,
                           http.authenticator()::apply)
                     .fold(cause -> application.close()
                                               .fold(cleanup -> Result.all(cause.<TerraHttpServer> result(),
                                                                           cleanup)
                                                                      .map((server, _) -> server)
                                                                      .async()),
                           authenticator -> TerraHttpServer.start(application,
                                                                  http.config(),
                                                                  authenticator));
    }
}

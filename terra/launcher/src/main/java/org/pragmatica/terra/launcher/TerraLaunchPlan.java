// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
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
import org.pragmatica.terra.TerraMetrics;
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
    private final TerraMetrics.Settings metrics;

    private TerraLaunchPlan(Path directory,
                            TerraBlueprint blueprint,
                            List<TerraFactory<?>> factories,
                            Map<String, ConfigurationProvider> configuration,
                            ConfigurationProvider deployment,
                            LaunchHttp http,
                            TerraMetrics.Settings metrics) {
        this.directory = directory;
        this.blueprint = blueprint;
        this.factories = List.copyOf(factories);
        this.configuration = Map.copyOf(configuration);
        this.deployment = deployment;
        this.http = http;
        this.metrics = metrics;
    }

    public static Promise<TerraLaunchPlan> load(Path directory) {
        return load(directory, LaunchHttp.RuntimeInputs.REQUIRED);
    }

    /// The plan `--check` validates (#3026): everything known at build time, with deployment secrets that the build
    /// environment does not carry reported by [#unsuppliedInputs] instead of refused. Such a plan cannot [#start].
    public static Promise<TerraLaunchPlan> check(Path directory) {
        return load(directory, LaunchHttp.RuntimeInputs.DEFERRED);
    }

    private static Promise<TerraLaunchPlan> load(Path directory, LaunchHttp.RuntimeInputs inputs) {
        return Promise.lift(Causes::fromThrowable,
                            () -> read(directory.toAbsolutePath(),
                                       inputs))
                      .flatMap(Result::async)
                      .flatMap(TerraLaunchPlan::checkMigrations);
    }

    @org.pragmatica.lang.Contract
    private static Result<TerraLaunchPlan> read(Path directory, LaunchHttp.RuntimeInputs inputs) throws Exception {
        var blueprintText = Files.readString(directory.resolve("blueprint.toml"));
        var available = ServiceLoader.load(TerraFactory.class)
                                     .stream()
                                     .<TerraFactory<?>> map(ServiceLoader.Provider::get)
                                     .toList();

        return TerraBlueprint.parse(blueprintText).flatMap(blueprint -> TerraApplication.plan(blueprint, available).flatMap(factories -> configure(directory,
                                                                                                                                                   blueprint,
                                                                                                                                                   factories,
                                                                                                                                                   inputs)));
    }

    private static Result<TerraLaunchPlan> configure(Path directory,
                                                     TerraBlueprint blueprint,
                                                     List<TerraFactory<?>> factories,
                                                     LaunchHttp.RuntimeInputs inputs) {
        return TerraConfiguration.deployment(directory).flatMap(deployment -> TerraConfiguration.host(directory,
                                                                                                      deployment).flatMap(settings -> Result.all(LaunchHttp.launchHttp(directory,
                                                                                                                                                                       settings,
                                                                                                                                                                       inputs),
                                                                                                                                                 TerraMetrics.Settings.settings(settings))
                                                                                                                                            .flatMap((http, metrics) -> configureSlices(directory,
                                                                                                                                                                                        blueprint,
                                                                                                                                                                                        factories,
                                                                                                                                                                                        deployment,
                                                                                                                                                                                        http,
                                                                                                                                                                                        metrics))));
    }

    private static Result<TerraLaunchPlan> configureSlices(Path directory,
                                                           TerraBlueprint blueprint,
                                                           List<TerraFactory<?>> factories,
                                                           TerraConfiguration.Deployment deployment,
                                                           LaunchHttp http,
                                                           TerraMetrics.Settings metrics) {
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
                                                  http,
                                                  metrics));
    }

    private Promise<TerraLaunchPlan> checkMigrations() {
        return TerraMigrations.fromDirectory(blueprint, directory, deployment).map(_ -> this);
    }

    public List<String> artifacts() {
        return factories.stream()
                        .map(TerraFactory::artifact)
                        .toList();
    }

    /// Runtime inputs (deployment secrets) the settings declare and the environment did not supply; empty for a plan
    /// from [#load], which refuses a missing one.
    public List<String> unsuppliedInputs() {
        return http.unsupplied();
    }

    public Promise<TerraHttpServer> start() {
        return unsuppliedInputs().isEmpty()
               ? startApplication().flatMap(this::host)
               : new TerraLaunchError("Runtime inputs not supplied: " + String.join(", ", unsuppliedInputs())).promise();
    }

    /// Start the selected application without an HTTP entry point; the caller owns close().
    public Promise<TerraApplication> startApplication() {
        var resources = SpiResourceProvider.spiResourceProvider((_, _) -> new TerraLaunchError("Resource configuration must be slice-scoped").result());

        return TerraMigrations.fromDirectory(blueprint, directory, deployment).flatMap(migrations -> TerraApplication.start(blueprint,
                                                                                                                            factories,
                                                                                                                            configuration::get,
                                                                                                                            resources.facade(),
                                                                                                                            migrations,
                                                                                                                            metrics));
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

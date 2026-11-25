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
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.terra.TerraApplication;
import org.pragmatica.terra.TerraBlueprint;
import org.pragmatica.terra.TerraFactory;
import org.pragmatica.terra.TerraMetrics;
import org.pragmatica.terra.TerraMigrations;
import org.pragmatica.terra.http.TerraHttpMount;
import org.pragmatica.terra.http.TerraHttpServer;
import org.pragmatica.terra.management.ManagementConfig;
import org.pragmatica.terra.management.TerraManagement;


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
    private final List<TerraHttpMount> mounts;

    private TerraLaunchPlan(Path directory,
                            TerraBlueprint blueprint,
                            List<TerraFactory<?>> factories,
                            Map<String, ConfigurationProvider> configuration,
                            ConfigurationProvider deployment,
                            LaunchHttp http,
                            TerraMetrics.Settings metrics,
                            List<TerraHttpMount> mounts) {
        this.directory = directory;
        this.blueprint = blueprint;
        this.factories = List.copyOf(factories);
        this.configuration = Map.copyOf(configuration);
        this.deployment = deployment;
        this.http = http;
        this.metrics = metrics;
        this.mounts = List.copyOf(mounts);
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
                                                                                                      deployment).flatMap(settings -> configureHost(directory,
                                                                                                                                                    blueprint,
                                                                                                                                                    factories,
                                                                                                                                                    deployment,
                                                                                                                                                    settings,
                                                                                                                                                    inputs)));
    }

    private static Result<TerraLaunchPlan> configureHost(Path directory,
                                                         TerraBlueprint blueprint,
                                                         List<TerraFactory<?>> factories,
                                                         TerraConfiguration.Deployment deployment,
                                                         ConfigurationProvider settings,
                                                         LaunchHttp.RuntimeInputs inputs) {
        return Result.all(LaunchHttp.launchHttp(directory, settings, inputs),
                          TerraMetrics.Settings.settings(settings),
                          managementSettings(settings))
                     .flatMap((http, metrics, management) -> management(http, management).flatMap(mounts -> configureSlices(directory,
                                                                                                                             blueprint,
                                                                                                                             factories,
                                                                                                                             deployment,
                                                                                                                             http,
                                                                                                                             metrics,
                                                                                                                             mounts)));
    }

    private static Result<TerraLaunchPlan> configureSlices(Path directory,
                                                           TerraBlueprint blueprint,
                                                           List<TerraFactory<?>> factories,
                                                           TerraConfiguration.Deployment deployment,
                                                           LaunchHttp http,
                                                           TerraMetrics.Settings metrics,
                                                           List<TerraHttpMount> mounts) {
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
                                                  metrics,
                                                  mounts));
    }

    // #3651: the management.* and info.* settings of terra.toml, read and checked at load like the HTTP settings.
    // Spring's `management.*` keys carry dashes (`base-path`), which an environment name cannot (`_` is its only
    // separator inside a segment), so `_` reads as `-` in a management key. A setting given in both forms with
    // different values is refused: neither would be the one the operator meant.
    private static Result<ManagementConfig> managementSettings(ConfigurationProvider settings) {
        return managementKeys(settings).flatMap(ManagementConfig::managementConfig);
    }

    static Result<Map<String, String>> managementKeys(ConfigurationProvider settings) {
        var keys = new LinkedHashMap<String, String>();

        for (var key : settings.keys()) {
            var value = settings.getString(key);

            if (value.isPresent() && (key.startsWith("management.") || key.startsWith("info."))) {
                var name = key.startsWith("management.")
                           ? key.replace('_', '-')
                           : key;
                var previous = Option.option(keys.put(name, value.unwrap()));

                if (previous.filter(earlier -> !earlier.equals(value.unwrap())).isPresent()) {
                    return new TerraLaunchError("Management setting " + name
                                               + " is given in two forms with different values").result();
                }
            }
        }

        return Result.success(keys);
    }

    // The management endpoints mount beside the slice routes; a default configuration exposes only health at
    // /actuator/health on the application listener. Build info comes from the launcher's class path.
    private static Result<List<TerraHttpMount>> management(LaunchHttp http, ManagementConfig config) {
        return TerraManagement.buildInfo(TerraLaunchPlan.class.getClassLoader())
                              .flatMap(build -> TerraManagement.terraManagement(config,
                                                                                Map.of(),
                                                                                build,
                                                                                Option.empty()))
                              .flatMap(management -> management.mount(http.config().transport()))
                              .map(mount -> mount.stream()
                                                 .toList());
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
                                                                  authenticator,
                                                                  mounts));
    }
}

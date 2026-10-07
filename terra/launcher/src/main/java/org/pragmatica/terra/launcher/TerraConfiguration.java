// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.terra.launcher;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarFile;

import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.IntrinsicConfigProvider;
import org.pragmatica.config.LayeredConfigProvider;
import org.pragmatica.config.source.MapConfigSource;
import org.pragmatica.config.source.TomlConfigSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.terra.TerraFactory;


/// Frozen startup layers. Double underscores in environment names separate path segments;
/// single underscores are preserved in resource property names (e.g. DATABASE__JDBC_URL).
final class TerraConfiguration {
    private TerraConfiguration() {}

    record Deployment(ConfigurationProvider dynamic, ConfigurationProvider resources) {
        ConfigurationProvider provider() {
            return LayeredConfigProvider.layered(List.of(dynamic, resources));
        }
    }

    static Result<Deployment> deployment(Path directory) {
        return environment(System.getenv()).flatMap(environment -> file(directory.resolve("resources.toml")).map(resources -> new Deployment(ConfigurationProvider.builder()
                                                                                                                                                                  .withSystemProperties("terra.")
                                                                                                                                                                  .withSource(environment)
                                                                                                                                                                  .build(),
                                                                                                                                             resources)));
    }

    static Result<ConfigurationProvider> host(Path directory, Deployment deployment) {
        return file(directory.resolve("terra.toml")).flatMap(TerraConfiguration::hostKeys)
                   .map(host -> LayeredConfigProvider.layered(List.of(deployment.dynamic(),
                                                                      host)))
                   .flatMap(ConfigurationProvider::withoutSecretResolution);
    }

    private static Result<ConfigurationProvider> hostKeys(ConfigurationProvider host) {
        return host.keys()
                   .stream()
                   .allMatch(key -> key.startsWith("http.") || key.startsWith("api_keys.") || key.startsWith("jwt."))
               ? Result.success(host)
               : new TerraLaunchError("terra.toml accepts only http, api_keys, and jwt sections; put resource settings in resources.toml").result();
    }

    static Result<MapConfigSource> environment(Map<String, String> values) {
        var normalized = new LinkedHashMap<String, String>();

        for (var entry : values.entrySet()) {
            if (entry.getKey().startsWith("TERRA_")) {
                var key = entry.getKey().substring(6).toLowerCase(Locale.ROOT).replace("__", ".");

                if (normalized.putIfAbsent(key, entry.getValue()) != null) {
                    return new TerraLaunchError("Ambiguous Terra environment key: " + key).result();
                }
            }
        }

        return MapConfigSource.mapConfigSource("Terra environment", normalized, 100);
    }

    static Result<ConfigurationProvider> slice(Path directory, TerraFactory<?> factory, Deployment deployment) {
        var encoded = URLEncoder.encode(factory.artifact(), StandardCharsets.UTF_8);

        return Result.all(file(directory.resolve("slices").resolve(encoded + ".toml")),
                          intrinsic(factory))
                     .map((override, intrinsic) -> LayeredConfigProvider.layered(List.of(deployment.dynamic(),
                                                                                         override,
                                                                                         deployment.resources(),
                                                                                         intrinsic)))
                     .flatMap(ConfigurationProvider::withoutSecretResolution);
    }

    static Result<ConfigurationProvider> file(Path path) {
        return Files.exists(path)
               ? TomlConfigSource.tomlConfigSource(path).map(ConfigurationProvider::configurationProvider)
               : Result.success(ConfigurationProvider.builder().build());
    }

    static Result<ConfigurationProvider> intrinsic(TerraFactory<?> factory) {
        return Result.lift(Causes::fromThrowable,
                           () -> readOwnResources(factory.getClass()))
                     .flatMap(content -> TomlConfigSource.tomlConfigSource(content.or("")))
                     .map(source -> IntrinsicConfigProvider.intrinsicConfigProvider(factory.artifact(),
                                                                                    source.asMap()));
    }

    // Read exactly the factory's code source, never a parent/classpath-first resources.toml.
    @org.pragmatica.lang.Contract
    private static Option<String> readOwnResources(Class<?> factory) throws Exception {
        var location = Path.of(factory.getProtectionDomain().getCodeSource().getLocation().toURI());

        if (Files.isDirectory(location)) {
            var metadata = location.resolve("META-INF/resources.toml");
            var root = location.resolve("resources.toml");

            return readFileChoice(metadata, root);
        }

        try (var jar = new JarFile(location.toFile())) {
            var entry = Option.option(jar.getJarEntry("META-INF/resources.toml")).orElse(() -> Option.option(jar.getJarEntry("resources.toml")));

            if (entry.isEmpty()) {
                return Option.empty();
            }

            try (var input = jar.getInputStream(entry.unwrap())) {
                return Option.some(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    @org.pragmatica.lang.Contract
    private static Option<String> readFileChoice(Path metadata, Path root) throws Exception {
        if (Files.exists(metadata)) {
            return Option.some(Files.readString(metadata));
        }

        return Files.exists(root)
               ? Option.some(Files.readString(root))
               : Option.empty();
    }
}

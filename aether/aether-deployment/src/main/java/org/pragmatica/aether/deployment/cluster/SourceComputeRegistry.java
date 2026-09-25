// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import org.pragmatica.lang.Unit;
import org.pragmatica.aether.config.cluster.SourceCloudBindings;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterBootstrapConfigParser;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.aether.environment.CloudConfig;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.EnvironmentIntegrationFactory;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// Resolves the named committed source to its own provider/account/region. Unknown sources never
/// fall back to the boot node's provider. Constructing integrations does not provision resources.
public interface SourceComputeRegistry {
    Result<ComputeProvider> resolve(SourceName source);

    default Result<ComputeProvider> resolve(SourceName source, String expectedBinding) {
        return EnvironmentError.operationNotSupported("Bound source resolution unavailable").result();
    }

    default boolean isAvailable() {
        return true;
    }

    default Result<String> binding(SourceName source) {
        return EnvironmentError.operationNotSupported("Source identity binding unavailable").result();
    }

    default Result<List<SourceName>> sources(Map<String, String> filter) {
        return EnvironmentError.operationNotSupported("Fleet source inventory unavailable").result();
    }

    default Unit resetProvisionerState(Option<ClusterName> clusterName) {
        return Unit.unit();
    }

    static SourceComputeRegistry sourceComputeRegistry(Supplier<Option<ClusterConfigValue>> configuration) {
        return sourceComputeRegistry(configuration, Option.none(), EnvironmentIntegrationFactory::createFromConfig);
    }

    static SourceComputeRegistry sourceComputeRegistry(Supplier<Option<ClusterConfigValue>> configuration,
                                                       Option<ComputeProvider> localProvider) {
        return sourceComputeRegistry(configuration, localProvider, EnvironmentIntegrationFactory::createFromConfig);
    }

    static SourceComputeRegistry sourceComputeRegistry(Supplier<Option<ClusterConfigValue>> configuration,
                                                       Function<CloudConfig, Result<EnvironmentIntegration>> factory) {
        return sourceComputeRegistry(configuration, Option.none(), factory);
    }

    static SourceComputeRegistry sourceComputeRegistry(Supplier<Option<ClusterConfigValue>> configuration,
                                                       Option<ComputeProvider> localProvider,
                                                       Option<ConfigurationProvider> protectedConfig) {
        return sourceComputeRegistry(configuration,
                                     localProvider,
                                     protectedConfig,
                                     EnvironmentIntegrationFactory::createFromConfig);
    }

    static SourceComputeRegistry sourceComputeRegistry(Supplier<Option<ClusterConfigValue>> configuration,
                                                       Option<ComputeProvider> localProvider,
                                                       Function<CloudConfig, Result<EnvironmentIntegration>> factory) {
        return sourceComputeRegistry(configuration, localProvider, Option.none(), factory);
    }

    static SourceComputeRegistry sourceComputeRegistry(Supplier<Option<ClusterConfigValue>> configuration,
                                                       Option<ComputeProvider> localProvider,
                                                       Option<ConfigurationProvider> protectedConfig,
                                                       Function<CloudConfig, Result<EnvironmentIntegration>> factory) {
        record CachedProvider(CloudConfig configuration, ComputeProvider provider) {}
        record Registry(Supplier<Option<ClusterConfigValue>> configuration,
                        Option<ComputeProvider> localProvider,
                        Option<ConfigurationProvider> protectedConfig,
                        Function<CloudConfig, Result<EnvironmentIntegration>> factory,
                        Map<SourceName, CachedProvider> providers) implements SourceComputeRegistry {
            @Override
            public boolean isAvailable() {
                return localProvider.isPresent() || configuration.get()
                                                                 .filter(value -> !value.tomlContent()
                                                                                        .isBlank())
                                                                 .isPresent();
            }

            @Override
            public synchronized Result<ComputeProvider> resolve(SourceName source) {
                return configuration.get()
                                    .filter(value -> !value.tomlContent()
                                                           .isBlank())
                                    .fold(() -> localProvider.toResult(EnvironmentError.operationNotSupported("Source registry: committed cluster configuration absent")),
                                          value -> ClusterBootstrapConfigParser.parse(value.tomlContent()).flatMap(config -> resolveConfigured(config,
                                                                                                                                               source)));
            }

            @Override
            public synchronized Result<ComputeProvider> resolve(SourceName source, String expectedBinding) {
                return configuration.get()
                                    .toResult(EnvironmentError.operationNotSupported("Committed source configuration unavailable"))
                                    .flatMap(value -> ClusterBootstrapConfigParser.parse(value.tomlContent()))
                                    .flatMap(config -> Option.option(config.sources().get(source.value()))
                                                             .toResult(EnvironmentError.operationNotSupported("Unknown compute source: " + source.value()))
                                                             .flatMap(profile -> resolveBound(profile,
                                                                                              config.cluster()
                                                                                                    .name()
                                                                                                    .value(),
                                                                                              expectedBinding)));
            }

            private Result<ComputeProvider> resolveBound(SourceProfile profile, String clusterName, String expected) {
                if (profile.type() == SourceType.FORGE || profile.type() == SourceType.DOCKER) {
                    return sourceBinding(profile).flatMap(actual -> actual.equals(expected)
                                                                    ? cachedOrCreate(profile, clusterName)
                                                                    : bindingChanged(profile.name()));
                }

                return resolvedCloudConfig(profile, clusterName).flatMap(config -> sourceBinding(profile).flatMap(raw -> SourceComputeRegistry.resolvedBinding(raw,
                                                                                                                                                               config))
                                                                                                .flatMap(actual -> actual.equals(expected)
                                                                                                                   ? cachedOrCreate(profile.name(),
                                                                                                                                    config)
                                                                                                                   : bindingChanged(profile.name())));
            }

            private Result<ComputeProvider> bindingChanged(SourceName source) {
                return EnvironmentError.operationNotSupported("Provider account binding changed for source " + source.value()).result();
            }

            private Result<ComputeProvider> resolveConfigured(ClusterBootstrapConfig config, SourceName name) {
                return Option.option(config.sources().get(name.value()))
                             .toResult(EnvironmentError.operationNotSupported("Unknown compute source: " + name.value()))
                             .flatMap(profile -> cachedOrCreate(profile,
                                                                config.cluster().name().value()));
            }

            private Result<ComputeProvider> cachedOrCreate(SourceProfile profile, String clusterName) {
                if (profile.type() == SourceType.FORGE || profile.type() == SourceType.DOCKER) {
                    return localProvider.fold(() -> sourceCloudConfig(profile, clusterName).flatMap(config -> cachedOrCreate(profile.name(),
                                                                                                                             config)),
                                              Result::success);
                }

                return resolvedCloudConfig(profile, clusterName).flatMap(config -> cachedOrCreate(profile.name(), config));
            }

            private Result<CloudConfig> resolvedCloudConfig(SourceProfile profile, String clusterName) {
                return protectedConfig.flatMap(provider -> SourceCloudBindings.read(provider.asMap(),
                                                                                    profile.name()))
                                      .fold(() -> sourceCloudConfig(profile, clusterName),
                                            binding -> verifyBinding(profile, binding));
            }

            private Result<CloudConfig> verifyBinding(SourceProfile profile, CloudConfig binding) {
                return profile.provider()
                              .filter(provider -> provider.value()
                                                          .equals(binding.provider()))
                              .isPresent()
                       ? SourceCloudBindings.resolve(binding)
                       : EnvironmentError.operationNotSupported("Protected provider binding disagrees with committed source " + profile.name()
                                                                                                                                       .value()).result();
            }

            private Result<ComputeProvider> cachedOrCreate(SourceName name, CloudConfig config) {
                return Option.option(providers.get(name))
                             .filter(cached -> cached.configuration()
                                                     .equals(config))
                             .fold(() -> create(name, config),
                                   cached -> Result.success(cached.provider()));
            }

            private Result<ComputeProvider> create(SourceName name, CloudConfig config) {
                return factory.apply(config)
                              .flatMap(integration -> integration.compute()
                                                                 .toResult(EnvironmentError.operationNotSupported("Source has no compute capability: " + name.value())))
                              .onSuccess(provider -> providers.put(name,
                                                                   new CachedProvider(config, provider)));
            }

            @Override
            public Result<String> binding(SourceName source) {
                return configuration.get()
                                    .toResult(EnvironmentError.operationNotSupported("Source registry: committed cluster configuration absent"))
                                    .flatMap(value -> ClusterBootstrapConfigParser.parse(value.tomlContent()))
                                    .flatMap(config -> bindingFor(config, source));
            }

            private Result<String> bindingFor(ClusterBootstrapConfig config, SourceName source) {
                return Option.option(config.sources().get(source.value()))
                             .toResult(EnvironmentError.operationNotSupported("Unknown compute source: " + source.value()))
                             .flatMap(profile -> resolvedBinding(profile,
                                                                 config.cluster().name().value()));
            }

            private Result<String> resolvedBinding(SourceProfile profile, String clusterName) {
                if (profile.type() == SourceType.FORGE || profile.type() == SourceType.DOCKER) {
                    return sourceBinding(profile);
                }

                return Result.all(sourceBinding(profile), resolvedCloudConfig(profile, clusterName)).flatMap(SourceComputeRegistry::resolvedBinding);
            }

            @Override
            public Result<List<SourceName>> sources(Map<String, String> filter) {
                return configuration.get()
                                    .filter(value -> !value.tomlContent()
                                                           .isBlank())
                                    .fold(() -> localProvider.map(_ -> List.of(SourceName.DEFAULT))
                                                             .toResult(EnvironmentError.operationNotSupported("Source registry: committed cluster configuration absent")),
                                          value -> ClusterBootstrapConfigParser.parse(value.tomlContent()).map(config -> matchingSources(config,
                                                                                                                                         filter)));
            }

            @Override
            public synchronized Unit resetProvisionerState(Option<ClusterName> clusterName) {
                providers.values()
                         .stream()
                         .map(CachedProvider::provider)
                         .distinct()
                         .forEach(provider -> provider.resetProvisionerState(clusterName));
                localProvider.onPresent(provider -> provider.resetProvisionerState(clusterName));

                return Unit.unit();
            }
        }

        return new Registry(configuration, localProvider, protectedConfig, factory, new HashMap<>());
    }

    static Result<CloudConfig> sourceCloudConfig(SourceProfile source, String clusterName) {
        if (source.type() != SourceType.CLOUD && source.type() != SourceType.DOCKER) {
            return EnvironmentError.operationNotSupported("Source is not provisionable: " + source.name().value()).result();
        }

        return SourceCloudBindings.resolve(SourceCloudBindings.cloudConfig(source, clusterName));
    }

    private static List<SourceName> matchingSources(ClusterBootstrapConfig config, Map<String, String> filter) {
        var role = Option.option(filter.get("aether.role")).orElse(() -> Option.option(filter.get("aether-role")));

        return config.sources()
                     .values()
                     .stream()
                     .filter(source -> source.type() == SourceType.CLOUD || source.type() == SourceType.DOCKER || source.type() == SourceType.FORGE)
                     .filter(source -> role.fold(() -> true,
                                                 name -> source.roles()
                                                               .keySet()
                                                               .stream()
                                                               .anyMatch(key -> key.value()
                                                                                   .equals(name))))
                     .map(SourceProfile::name)
                     .sorted(java.util.Comparator.comparing(SourceName::value))
                     .toList();
    }

    /// Stable opaque identity for persisted operations; contains no plaintext credential. Role
    /// counts and instance sizes do not change account routing and are deliberately excluded.
    static Result<String> sourceBinding(SourceProfile source) {
        var canonical = new StringBuilder();

        appendBinding(canonical,
                      "name",
                      source.name().value());
        appendBinding(canonical,
                      "type",
                      source.type().value());
        appendBinding(canonical,
                      "provider",
                      source.provider().map(provider -> provider.value()).or(""));
        appendBinding(canonical,
                      "credentials",
                      source.credentials().or(""));
        appendBinding(canonical,
                      "region",
                      source.region().or(""));
        appendBinding(canonical,
                      "zone",
                      source.zone().or(""));
        source.zones().forEach(zone -> appendBinding(canonical, "zones", zone));
        appendSection(canonical, "cloud.credentials", section(source, "cloud.credentials"));
        appendSection(canonical, "cloud.compute", identityCompute(source));
        appendSection(canonical, "cloud.security", section(source, "cloud.security"));

        return Result.lift(() -> java.security.MessageDigest.getInstance("SHA-256")).map(digest -> java.util.HexFormat.of()
                                                                                                                      .formatHex(digest.digest(canonical.toString()
                                                                                                                                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    private static Result<String> resolvedBinding(String profileBinding, CloudConfig resolved) {
        var canonical = new StringBuilder();

        appendBinding(canonical, "profile", profileBinding);
        appendBinding(canonical, "provider", resolved.provider());
        appendSection(canonical, "credentials", resolved.credentials());
        appendSection(canonical, "compute", resolved.compute());
        appendSection(canonical, "security", resolved.security());

        return Result.lift(() -> java.security.MessageDigest.getInstance("SHA-256")).map(digest -> java.util.HexFormat.of()
                                                                                                                      .formatHex(digest.digest(canonical.toString()
                                                                                                                                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    private static Map<String, String> identityCompute(SourceProfile source) {
        var compute = section(source, "cloud.compute");

        java.util.Set.of("server_type", "instance_type", "machine_type", "image", "ami_id", "source_image", "user_data")
                     .forEach(compute::remove);

        return compute;
    }

    private static void appendSection(StringBuilder target, String section, Map<String, String> values) {
        new java.util.TreeMap<>(values).forEach((key, value) -> appendBinding(target, section + "." + key, value));
    }

    private static void appendBinding(StringBuilder target, String name, String value) {
        target.append(name.length()).append(':').append(name).append(value.length()).append(':').append(value);
    }

    private static Map<String, String> section(SourceProfile source, String name) {
        return new HashMap<>(source.nodeConfig().map(config -> config.getSection(name)).or(Map.of()));
    }
}

// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.pragmatica.aether.config.ConfigReferenceValues;
import org.pragmatica.aether.environment.CloudConfig;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.FirewallId;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// Protected per-source provider bindings carried to every core. Source names, not the leader's
/// own account, select credentials and provider resources. Values never enter shared cluster state.
public interface SourceCloudBindings {
    String PREFIX = "cloud.sources.";

    static CloudConfig cloudConfig(SourceProfile source, String clusterName) {
        var provider = source.type() == SourceType.DOCKER
                       ? "docker"
                       : source.provider().map(CloudProviderName::value).or("");
        var credentials = section(source, "cloud.credentials");
        var compute = section(source, "cloud.compute");

        source.credentials().onPresent(value -> scalarCredential(credentials, value));
        source.region().onPresent(value -> compute.put("region", value));
        source.zone().onPresent(value -> compute.put("zone", value));
        applyLocation(credentials, source, provider);
        var discovery = section(source, "cloud.discovery");

        discovery.put("cluster_name", clusterName);

        return new CloudConfig(provider,
                               Map.copyOf(credentials),
                               Map.copyOf(compute),
                               Map.of(),
                               Map.copyOf(discovery),
                               Map.of(),
                               Map.copyOf(section(source, "cloud.security")));
    }

    static Result<CloudConfig> resolve(CloudConfig config) {
        return Result.all(ConfigReferenceValues.resolve(config.credentials()),
                          ConfigReferenceValues.resolve(config.compute()),
                          ConfigReferenceValues.resolve(config.discovery()),
                          ConfigReferenceValues.resolve(config.security()))
                     .map((credentials, compute, discovery, security) -> new CloudConfig(config.provider(),
                                                                                         credentials,
                                                                                         compute,
                                                                                         Map.of(),
                                                                                         discovery,
                                                                                         Map.of(),
                                                                                         security));
    }

    static Option<CloudConfig> read(Map<String, String> provider, SourceName source) {
        var prefix = PREFIX + source.value();

        return Option.option(provider.get(prefix + ".provider")).map(name -> new CloudConfig(name,
                                                                                             configSection(provider,
                                                                                                           prefix
                                                                                                          + ".credentials"),
                                                                                             configSection(provider,
                                                                                                           prefix
                                                                                                          + ".compute"),
                                                                                             Map.of(),
                                                                                             configSection(provider,
                                                                                                           prefix
                                                                                                          + ".discovery"),
                                                                                             Map.of(),
                                                                                             configSection(provider,
                                                                                                           prefix
                                                                                                          + ".security")));
    }

    static Option<CloudConfig> read(TomlDocument document, SourceName source) {
        var prefix = PREFIX + source.value();

        return document.getString(prefix, "provider")
                       .map(name -> new CloudConfig(name,
                                                    document.getSection(prefix + ".credentials"),
                                                    document.getSection(prefix + ".compute"),
                                                    Map.of(),
                                                    document.getSection(prefix + ".discovery"),
                                                    Map.of(),
                                                    document.getSection(prefix + ".security")));
    }

    static TomlDocument augment(TomlDocument overlay,
                                ClusterBootstrapConfig config,
                                NodeRole role,
                                Map<String, List<Long>> sshKeys,
                                Map<SourceName, List<FirewallId>> firewalls) {
        if (role != NodeRole.CORE) {
            return overlay;
        }

        var sections = new LinkedHashMap<>(overlay.sections());

        config.sources()
              .values()
              .stream()
              .filter(source -> source.type() == SourceType.CLOUD)
              .forEach(source -> putSource(sections,
                                           source,
                                           config.cluster().name().value(),
                                           sshKeys,
                                           firewalls));

        return new TomlDocument(Map.copyOf(sections), overlay.tableArrays());
    }

    static Result<TomlDocument> resolveOverlay(TomlDocument composed,
                                               TomlDocument protectedConfig,
                                               SourceName target,
                                               NodeRole role) {
        return read(protectedConfig, target).toResult(EnvironmentError.operationNotSupported("Protected provider binding unavailable for source " + target.value()))
                   .flatMap(SourceCloudBindings::resolve)
                   .flatMap(binding -> PlaceholderConfigResolver.resolve(composed, protectedConfig).map(resolved -> applyBinding(resolved,
                                                                                                                                 protectedConfig,
                                                                                                                                 binding,
                                                                                                                                 role)));
    }

    static Result<TomlDocument> resolveOverlayFromConfig(TomlDocument composed,
                                                         ClusterBootstrapConfig config,
                                                         SourceName target,
                                                         NodeRole role) {
        return Result.allOf(config.sources()
                                  .values()
                                  .stream()
                                  .filter(source -> source.type() == SourceType.CLOUD && (role == NodeRole.CORE || source.name()
                                                                                                                         .equals(target)))
                                  .map(source -> resolve(cloudConfig(source,
                                                                     config.cluster().name().value())).map(binding -> Map.entry(source.name(),
                                                                                                                                binding))))
                     .map(SourceCloudBindings::bindingDocument)
                     .flatMap(bindings -> resolveOverlay(composed, bindings, target, role));
    }

    private static TomlDocument bindingDocument(List<Map.Entry<SourceName, CloudConfig>> bindings) {
        var sections = new LinkedHashMap<String, Map<String, Object>>();

        bindings.forEach(entry -> putBinding(sections, entry.getKey(), entry.getValue()));

        return new TomlDocument(Map.copyOf(sections), Map.of());
    }

    private static void putBinding(Map<String, Map<String, Object>> sections, SourceName source, CloudConfig binding) {
        var prefix = PREFIX + source.value();

        sections.put(prefix,
                     Map.of("provider", binding.provider()));
        sections.put(prefix + ".credentials", objects(binding.credentials()));
        sections.put(prefix + ".compute", objects(binding.compute()));
        sections.put(prefix + ".discovery", objects(binding.discovery()));
        sections.put(prefix + ".security", objects(binding.security()));
    }

    private static TomlDocument applyBinding(TomlDocument composed,
                                             TomlDocument protectedConfig,
                                             CloudConfig binding,
                                             NodeRole role) {
        var sections = new LinkedHashMap<>(composed.sections());

        sections.put("cloud",
                     Map.of("provider", binding.provider()));
        sections.put("cloud.credentials", objects(binding.credentials()));
        var compute = new HashMap<>(composed.getSection("cloud.compute"));

        binding.compute().forEach((key, value) -> copyBindingCompute(compute, key, value));
        sections.put("cloud.compute", objects(compute));
        sections.put("cloud.discovery", objects(binding.discovery()));
        sections.put("cloud.security", objects(binding.security()));
        if (role == NodeRole.CORE) {
            protectedConfig.sections().forEach((name, values) -> copyProtectedSource(sections, name, values));
        }

        return new TomlDocument(Map.copyOf(sections), composed.tableArrays());
    }

    private static void copyBindingCompute(Map<String, String> target, String key, String value) {
        if (!java.util.Set.of("image",
                              "server_type",
                              "instance_type",
                              "machine_type",
                              "ami_id",
                              "source_image",
                              "user_data")
                          .contains(key)) {
            target.put(key, value);
        }
    }

    private static void copyProtectedSource(Map<String, Map<String, Object>> target,
                                            String name,
                                            Map<String, Object> values) {
        if (name.startsWith(PREFIX)) {
            target.put(name, values);
        }
    }

    private static void putSource(Map<String, Map<String, Object>> sections,
                                  SourceProfile source,
                                  String cluster,
                                  Map<String, List<Long>> sshKeys,
                                  Map<SourceName, List<FirewallId>> firewalls) {
        var binding = cloudConfig(source, cluster);
        var compute = new HashMap<>(binding.compute());

        putIds(compute,
               "ssh_key_ids",
               sshKeys.getOrDefault(source.name().value(),
                                    List.of()).stream().map(String::valueOf).toList());
        putIds(compute,
               "firewall_ids",
               firewalls.getOrDefault(source.name(), List.of()).stream().map(FirewallId::value).toList());
        var prefix = PREFIX + source.name().value();

        sections.put(prefix,
                     Map.of("provider", binding.provider()));
        sections.put(prefix + ".credentials", objects(binding.credentials()));
        sections.put(prefix + ".compute", objects(compute));
        sections.put(prefix + ".discovery", objects(binding.discovery()));
        sections.put(prefix + ".security", objects(binding.security()));
    }

    private static void putIds(Map<String, String> values, String name, List<String> ids) {
        if (!ids.isEmpty()) {
            values.put(name, String.join(",", ids));
        }
    }

    private static Map<String, Object> objects(Map<String, String> values) {
        return values.entrySet()
                     .stream()
                     .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                                                           entry -> (Object) entry.getValue()));
    }

    private static Map<String, String> configSection(Map<String, String> provider, String section) {
        var prefix = section + ".";

        return provider.entrySet()
                       .stream()
                       .filter(entry -> entry.getKey()
                                             .startsWith(prefix))
                       .collect(Collectors.toUnmodifiableMap(entry -> entry.getKey()
                                                                           .substring(prefix.length()),
                                                             Map.Entry::getValue));
    }

    private static Map<String, String> section(SourceProfile source, String name) {
        return new HashMap<>(source.nodeConfig().map(config -> config.getSection(name)).or(Map.of()));
    }

    private static void scalarCredential(Map<String, String> credentials, String value) {
        credentials.put("api_token", value);
        credentials.put("access_key", value);
        credentials.put("credentials_file", value);
    }

    private static void applyLocation(Map<String, String> credentials, SourceProfile source, String provider) {
        switch (provider) {
            case "aws" -> source.region().onPresent(value -> credentials.put("region", value));
            case "gcp" -> source.zone().onPresent(value -> credentials.put("zone", value));
            case "azure" -> source.region().onPresent(value -> credentials.put("location", value));
            default -> {}
        }
    }
}

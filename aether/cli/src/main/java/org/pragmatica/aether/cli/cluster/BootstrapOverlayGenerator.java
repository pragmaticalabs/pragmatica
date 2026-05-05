// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.RoleSubTable;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.lang.Option;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;


public interface BootstrapOverlayGenerator {
    record Section(String name, Map<String, Object> values) {
        static Section section(String name, Map<String, Object> values) {
            return new Section(name, Map.copyOf(values));
        }
    }

    static TomlDocument overlay(ClusterBootstrapConfig config,
                                SourceProfile source,
                                int nodeIndex,
                                Option<String> apiKey,
                                Option<String> dockerGid,
                                Option<String> clusterSecret) {
        var fixed = Stream.of(Option.some(clusterSection(config)),
                              Option.some(clusterPortsSection(config)),
                              cloudSection(source),
                              cloudCredentialsSection(source),
                              sourceSpecificSection(config, source, nodeIndex, apiKey, dockerGid),
                              tlsSection(source, clusterSecret))
        .flatMap(Option::stream);
        var databases = databaseSections(source).stream();
        var sections = Stream.concat(fixed, databases).toList();
        return new TomlDocument(toOrderedMap(sections), Map.of());
    }

    private static Map<String, Map<String, Object>> toOrderedMap(List<Section> sections) {
        var ordered = new LinkedHashMap<String, Map<String, Object>>();
        sections.forEach(section -> ordered.put(section.name(), section.values()));
        return Map.copyOf(ordered);
    }

    private static Section clusterSection(ClusterBootstrapConfig config) {
        var values = new LinkedHashMap<String, Object>();
        values.put("name",
                   config.cluster().name());
        values.put("tls",
                   config.operations().tls()
                                    .autoGenerate());
        return Section.section("cluster", values);
    }

    private static Section clusterPortsSection(ClusterBootstrapConfig config) {
        var ports = config.operations().ports();
        var values = new LinkedHashMap<String, Object>();
        values.put("management", ports.management());
        values.put("cluster", ports.cluster());
        return Section.section("cluster.ports", values);
    }

    private static Option<Section> sourceSpecificSection(ClusterBootstrapConfig config,
                                                         SourceProfile source,
                                                         int nodeIndex,
                                                         Option<String> apiKey,
                                                         Option<String> dockerGid) {
        return switch (source.type()){
            case DOCKER -> Option.some(dockerComputeSection(config, nodeIndex, apiKey, dockerGid));
            case CLOUD -> Option.some(cloudComputeSection(source));
            case SSH, FORGE -> Option.empty();
        };
    }

    private static Section dockerComputeSection(ClusterBootstrapConfig config,
                                                int nodeIndex,
                                                Option<String> apiKey,
                                                Option<String> dockerGid) {
        var ports = config.operations().ports();
        var values = new LinkedHashMap<String, Object>();
        values.put("management_port_base", ports.management());
        values.put("app_port_base", ports.appHttp());
        values.put("cluster_port", ports.cluster() + nodeIndex);
        values.put("socket_path", "/var/run/docker.sock");
        apiKey.onPresent(key -> values.put("api_key", key));
        dockerGid.onPresent(gid -> values.put("docker_gid", gid));
        return Section.section("cloud.compute", values);
    }

    private static Section cloudComputeSection(SourceProfile source) {
        var values = new LinkedHashMap<String, Object>();
        source.region().onPresent(region -> values.put("region", region));
        coreInstanceType(source).onPresent(serverType -> values.put("server_type", serverType));
        return Section.section("cloud.compute", values);
    }

    /// Top-level `[cloud]` section. Required for `ConfigLoader.populateCloudConfig` to
    /// build a `CloudConfig` at runtime — without it, `lifecycleManager.isCloudManaged()`
    /// returns false and CTM cannot auto-provision new nodes during scale-up.
    private static Option<Section> cloudSection(SourceProfile source) {
        if (source.type() != SourceType.CLOUD) {return Option.empty();}
        return source.provider().map(provider -> Section.section("cloud", Map.of("provider", provider.value())));
    }

    /// `[cloud.credentials]` section carrying the API token from the bootstrap config.
    /// Required so the runtime ComputeProvider can authenticate with the cloud API
    /// when it provisions new VMs after a `/api/cluster/scale` request.
    private static Option<Section> cloudCredentialsSection(SourceProfile source) {
        if (source.type() != SourceType.CLOUD) {return Option.empty();}
        return source.credentials().filter(token -> !token.isBlank())
                                 .map(token -> Section.section("cloud.credentials", Map.of("api_token", token)));
    }

    private static Option<String> coreInstanceType(SourceProfile source) {
        return Option.option(source.roles().get(NodeRole.CORE)).flatMap(RoleSubTable::instanceType);
    }

    private static Option<Section> tlsSection(SourceProfile source, Option<String> clusterSecret) {
        if (source.type() != SourceType.CLOUD) {return Option.empty();}
        return clusterSecret.map(secret -> Section.section("tls", Map.of("cluster_secret", secret)));
    }

    private static List<Section> databaseSections(SourceProfile source) {
        return source.databases().entrySet()
                               .stream()
                               .map(entry -> Section.section("database." + entry.getKey(),
                                                             Map.of(urlFieldName(entry.getValue()),
                                                                    entry.getValue())))
                               .toList();
    }

    private static String urlFieldName(String url) {
        return url.startsWith("jdbc:")
              ? "jdbc_url"
              : "async_url";
    }
}

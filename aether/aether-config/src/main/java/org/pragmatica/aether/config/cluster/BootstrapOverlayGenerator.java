// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.lang.Option;


/// Builds the CLI/CTM overlay section of a node's composed `aether.toml`: the cluster name +
/// ports, cloud provider/credentials/discovery, the source-specific compute section, the TLS
/// cluster-secret and any database URLs. Lives in `aether-config` (moved from `cli`) so BOTH the
/// CLI bootstrap and the CTM auto-heal replacement path compose node config from the SAME overlay
/// generator and cannot drift — the CTM module (`aether-deployment`) cannot import `cli`.
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
                                Option<String> clusterSecret,
                                NodeRole role) {
        return overlay(config, source, nodeIndex, apiKey, dockerGid, clusterSecret, role, List.of());
    }

    /// #442 — `sshKeyIds` carries the operator SSH key ids resolved for the source's cloud provider
    /// (bootstrap: `BootstrapContext.sshKeyIdsFor`; CTM auto-heal replacement: the leader's own
    /// resolved `[cloud.compute] ssh_key_ids`). Rendered into `[cloud.compute] ssh_key_ids` so every
    /// node's runtime config carries them and a leader provisioning a replacement resolves keys from
    /// config (no API lookup) — closing the PAM wall on keyless replacements. Empty for non-cloud
    /// sources and when no keys were resolved: the field is then omitted.
    ///
    /// RFC-0016 W2 — `role` selects which role's `image` is rendered into `[cloud.compute] image`
    /// (via [#roleImage]): a worker node's overlay carries the worker role's image, a core node's the
    /// core role's — no cross-role fallback.
    static TomlDocument overlay(ClusterBootstrapConfig config,
                                SourceProfile source,
                                int nodeIndex,
                                Option<String> apiKey,
                                Option<String> dockerGid,
                                Option<String> clusterSecret,
                                NodeRole role,
                                List<Long> sshKeyIds) {
        var fixed = Stream.of(Option.some(clusterSection(config)),
                              Option.some(clusterPortsSection(config)),
                              cloudSection(source),
                              cloudCredentialsSection(source),
                              cloudDiscoverySection(config, source),
                              sourceSpecificSection(config, source, nodeIndex, apiKey, dockerGid, sshKeyIds, role),
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
                   config.operations().tls().autoGenerate());

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
                                                         Option<String> dockerGid,
                                                         List<Long> sshKeyIds,
                                                         NodeRole role) {
        return switch (source.type()) {
            case DOCKER -> Option.some(dockerComputeSection(config, nodeIndex, apiKey, dockerGid));
            case CLOUD -> Option.some(cloudComputeSection(source, sshKeyIds, role));
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

    private static Section cloudComputeSection(SourceProfile source, List<Long> sshKeyIds, NodeRole role) {
        var values = new LinkedHashMap<String, Object>();

        source.region().onPresent(region -> values.put("region", region));
        coreInstanceType(source).onPresent(serverType -> values.put("server_type", serverType));
        roleImage(source, role).onPresent(image -> values.put("image", image));
        if (!sshKeyIds.isEmpty()) {
            values.put("ssh_key_ids", joinLongs(sshKeyIds));
        }

        return Section.section("cloud.compute", values);
    }

    /// Comma-joined form of the resolved SSH key ids — mirrors `ProviderResolver.joinLongs` so the
    /// value the node-side `HetznerEnvironmentIntegrationFactory` parses (via `split(",")`) matches
    /// exactly. Rendered as a scalar string (not a TOML array) because the node reads `[cloud.compute]`
    /// through `TomlDocument.getSection`, which stringifies every value.
    private static String joinLongs(List<Long> ids) {
        return ids.stream()
                  .map(String::valueOf)
                  .collect(Collectors.joining(","));
    }

    private static Option<Section> cloudSection(SourceProfile source) {
        if (source.type() != SourceType.CLOUD) {
            return Option.empty();
        }

        return source.provider()
                     .map(provider -> Section.section("cloud",
                                                      Map.of("provider",
                                                             provider.value())));
    }

    private static Option<Section> cloudCredentialsSection(SourceProfile source) {
        if (source.type() != SourceType.CLOUD) {
            return Option.empty();
        }

        return source.credentials()
                     .filter(token -> !token.isBlank())
                     .map(token -> Section.section("cloud.credentials",
                                                   Map.of("api_token", token)));
    }

    private static Option<Section> cloudDiscoverySection(ClusterBootstrapConfig config, SourceProfile source) {
        if (source.type() != SourceType.CLOUD) {
            return Option.empty();
        }

        return Option.some(Section.section("cloud.discovery",
                                           Map.of("cluster_name",
                                                  config.cluster().name())));
    }

    private static Option<String> coreInstanceType(SourceProfile source) {
        return Option.option(source.roles().get(NodeRole.CORE)).flatMap(RoleSubTable::instanceType);
    }

    /// #459 / RFC-0016 W2 — the node's OWN role `image` (VM boot image / snapshot id), rendered into
    /// each node's runtime `[cloud.compute] image` so a running leader's `config.image()` carries the
    /// image for ITS role and both bootstrap seeds AND CTM auto-heal replacements boot from the
    /// operator's prepared snapshot for that role. The source profile is persisted in the KV cluster
    /// config, so a replacement re-parses the same image and inherits it via this overlay — no
    /// per-generation threading needed (the image is spec-level, unlike the runtime-resolved
    /// `ssh_key_ids`). NO cross-role fallback: a role with no `image` renders no `[cloud.compute]
    /// image` (the field is omitted and the provider's loud default applies), never a sibling's image.
    private static Option<String> roleImage(SourceProfile source, NodeRole role) {
        return Option.option(source.roles().get(role)).flatMap(RoleSubTable::image);
    }

    private static Option<Section> tlsSection(SourceProfile source, Option<String> clusterSecret) {
        if (source.type() != SourceType.CLOUD) {
            return Option.empty();
        }

        return clusterSecret.map(secret -> Section.section("tls", Map.of("cluster_secret", secret)));
    }

    private static List<Section> databaseSections(SourceProfile source) {
        return source.databases()
                     .entrySet()
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

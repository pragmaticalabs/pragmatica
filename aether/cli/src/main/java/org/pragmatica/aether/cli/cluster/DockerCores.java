// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.RoleSubTable;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.config.cluster.SourceType;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.ProvisionContext;
import org.pragmatica.lang.Option;
import org.pragmatica.utility.IdGenerator;

import static org.pragmatica.lang.Option.option;


/// The core node ids of a bootstrap's DOCKER sources, minted before any container exists, and the ONE `PEERS` list every docker node
/// is created with (#2089).
///
/// A docker node boots from its `PEERS` list and aborts when its own id is not in it, and the provider can only echo the list it is
/// given. The list must name EVERY core of the cluster, across all sources: a list per source would give two docker sources two
/// separate clusters, silently. A container's host is its name, which is its node id (same Docker network); the port is
/// [#CLUSTER_PORT].
record DockerCores(Map<String, List<String>> idsBySource, String peers) {
    /// The cluster port every Docker node listens on: `DockerConfig`'s default. `ProviderResolver` passes it to the provider
    /// explicitly, so the two agree by construction rather than by two defaults.
    static final int CLUSTER_PORT = 6000;

    static DockerCores mint(Map<String, SourceProfile> sources, ClusterName clusterName) {
        var ids = new LinkedHashMap<String, List<String>>();

        sources.forEach((name, source) -> {
            if (source.type() == SourceType.DOCKER) {
                ids.put(name, mintFor(source, clusterName));
            }
        });
        var all = ids.values().stream().flatMap(List::stream).toList();

        return new DockerCores(Map.copyOf(ids), peersOf(all));
    }

    List<String> idsFor(String sourceName) {
        return idsBySource.getOrDefault(sourceName, List.of());
    }

    static List<String> mintFor(SourceProfile source, ClusterName clusterName) {
        var prefix = ProvisionContext.coreNodeNamePrefix(Option.some(clusterName));
        var count = option(source.roles().get(NodeRole.CORE)).flatMap(RoleSubTable::count).or(0);

        return IntStream.range(0, count)
                        .mapToObj(_ -> IdGenerator.generate(prefix))
                        .toList();
    }

    static String peersOf(List<String> coreIds) {
        return coreIds.stream()
                      .map(id -> id + ":" + id + ":" + CLUSTER_PORT)
                      .collect(Collectors.joining(","));
    }
}

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

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
record DockerCores(Map<String, List<String>> idsBySource,
                   String peers,
                   Map<String, Map<NodeRole, List<String>>> idsBySourceAndRole) {
    /// The cluster port every Docker node listens on: `DockerConfig`'s default. `ProviderResolver` passes it to the provider
    /// explicitly, so the two agree by construction rather than by two defaults.
    static final int CLUSTER_PORT = 6000;

    static DockerCores mint(Map<String, SourceProfile> sources, ClusterName clusterName) {
        return mint(sources, clusterName, DockerNodeLedger.none());
    }

    /// The ids of every docker node of the bootstrap. A slot the ledger already holds a name for keeps that name (#2124: a re-run
    /// resumes the nodes of the earlier run, it does not mint a second set); only the slots beyond it get a fresh one.
    static DockerCores mint(Map<String, SourceProfile> sources, ClusterName clusterName, DockerNodeLedger ledger) {
        var byRole = new LinkedHashMap<String, Map<NodeRole, List<String>>>();

        sources.forEach((name, source) -> {
            if (source.type() == SourceType.DOCKER) {
                byRole.put(name, mintRoles(name, source, clusterName, ledger));
            }
        });
        var cores = new LinkedHashMap<String, List<String>>();

        byRole.forEach((name, roles) -> cores.put(name, roles.get(NodeRole.CORE)));
        var all = cores.values().stream().flatMap(List::stream).toList();

        return new DockerCores(Map.copyOf(cores), peersOf(all), Map.copyOf(byRole));
    }

    private static Map<NodeRole, List<String>> mintRoles(String name,
                                                         SourceProfile source,
                                                         ClusterName clusterName,
                                                         DockerNodeLedger ledger) {
        var roles = new EnumMap<NodeRole, List<String>>(NodeRole.class);

        for (var role : ROLE_ORDER) {
            roles.put(role, mintRole(source, role, clusterName, ledger.priorNames(name, role)));
        }

        return Map.copyOf(roles);
    }

    List<String> idsFor(String sourceName) {
        return idsBySource.getOrDefault(sourceName, List.of());
    }

    List<String> idsFor(String sourceName, NodeRole role) {
        return idsBySourceAndRole.getOrDefault(sourceName,
                                               Map.of())
                                 .getOrDefault(role,
                                               List.of());
    }

    static final List<NodeRole> ROLE_ORDER = List.of(NodeRole.CORE, NodeRole.WORKER, NodeRole.SPOT);

    static List<String> mintFor(SourceProfile source, ClusterName clusterName) {
        return mintRole(source, NodeRole.CORE, clusterName, List.of());
    }

    private static List<String> mintRole(SourceProfile source,
                                         NodeRole role,
                                         ClusterName clusterName,
                                         List<String> recorded) {
        var prefix = ProvisionContext.coreNodeNamePrefix(Option.some(clusterName));
        var count = option(source.roles().get(role)).flatMap(RoleSubTable::count).or(0);
        var kept = recorded.stream().limit(count).toList();
        var fresh = IntStream.range(kept.size(), count).mapToObj(_ -> IdGenerator.generate(prefix));

        return Stream.concat(kept.stream(),
                             fresh)
                     .toList();
    }

    static String peersOf(List<String> coreIds) {
        return coreIds.stream()
                      .map(id -> id + ":" + id + ":" + CLUSTER_PORT)
                      .collect(Collectors.joining(","));
    }
}

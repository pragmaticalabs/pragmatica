// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import java.util.LinkedHashMap;
import java.util.Map;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Option;


public interface CommunityPlacementParser {
    static Result<Map<String, CommunityPlacement>> parse(TomlDocument document, Map<String, SourceProfile> sources) {
        var ids = document.sectionNames()
                          .stream()
                          .filter(section -> section.startsWith("community."))
                          .map(section -> section.substring("community.".length())
                                                 .split("\\.") [0])
                          .distinct()
                          .sorted()
                          .toList();
        var policies = new LinkedHashMap<String, CommunityPlacement>();

        for (var id : ids) {
            var parsed = parseCommunity(document, id, sources);

            if (parsed.isFailure()) {
                return parsed.map(_ -> Map.copyOf(policies));
            }

            parsed.onSuccess(policy -> policies.put(id, policy));
        }

        return Result.success(Map.copyOf(policies));
    }

    private static Result<CommunityPlacement> parseCommunity(TomlDocument document,
                                                             String id,
                                                             Map<String, SourceProfile> sources) {
        var prefix = "community." + id + ".placement.";
        var locations = document.sectionNames()
                                .stream()
                                .filter(section -> section.startsWith(prefix))
                                .sorted()
                                .map(section -> new CommunityPlacement.Location(document.getString(section, "source")
                                                                                        .or(""),
                                                                                document.getString(section, "zone"),
                                                                                document.getInt(section, "minimum")
                                                                                        .or(0),
                                                                                document.getInt(section, "weight").or(1)))
                                .toList();

        for (var location : locations) {
            var source = Option.option(sources.get(location.source())).filter(profile -> profile.roles()
                                                                                                .containsKey(NodeRole.WORKER));

            if (source.isEmpty()) {
                return new ClusterConfigError.ParseFailed("Community " + id
                                                         + " references a source without worker capacity: " + location.source()).result();
            }

            if (location.zone().filter(zone -> !source.unwrap()
                                                      .effectiveZones()
                                                      .contains(zone)).isPresent()) {
                return new ClusterConfigError.ParseFailed("Community " + id + " references an unconfigured source zone").result();
            }
        }

        return document.getInt("community." + id, "target_size")
                       .fold(() -> new ClusterConfigError.ParseFailed("Community target_size must be explicit: " + id).result(),
                             target -> CommunityPlacement.communityPlacement(id, target, locations));
    }
}

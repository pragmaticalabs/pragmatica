// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import java.util.List;

import org.pragmatica.aether.config.CommunitySizing;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Result.success;


/// Parses the optional `[community_policy]` table. An absent table is `none`: the node-level
/// [CommunitySizing] keeps applying and growth stays off. A mistyped value, an out-of-range number or an unknown key refuses
/// the whole apply, like the neighbouring `[rollback]` and `[replication]` tables.
public interface CommunityPolicyParser {
    String SECTION = "community_policy";

    List<String> KEYS = List.of("max_size",
                                "viability_floor",
                                "max_communities",
                                "dissolve_grace_seconds",
                                "growth",
                                "allow_below_rf");

    static Result<Option<CommunityPolicy>> parse(TomlDocument document) {
        return document.hasSection(SECTION)
               ? refuseUnknownKeys(document).flatMap(_ -> fromSection(document))
                                  .map(Option::some)
               : success(none());
    }

    private static Result<CommunityPolicy> fromSection(TomlDocument document) {
        return CommunityPolicy.communityPolicy(document.getInt(SECTION, "max_size")
                                                       .or(CommunitySizing.DEFAULT_TARGET_SIZE),
                                               document.getInt(SECTION, "viability_floor")
                                                       .or(CommunitySizing.DEFAULT_VIABILITY_FLOOR),
                                               document.getInt(SECTION, "max_communities")
                                                       .or(CommunityPolicy.DEFAULT_MAX_COMMUNITIES),
                                               document.getInt(SECTION, "dissolve_grace_seconds")
                                                       .or(CommunityPolicy.DEFAULT_DISSOLVE_GRACE_SECONDS),
                                               document.getBoolean(SECTION, "growth").or(false),
                                               document.getBoolean(SECTION, "allow_below_rf").or(false));
    }

    private static Result<Unit> refuseUnknownKeys(TomlDocument document) {
        var unknown = document.keys(SECTION).stream().filter(key -> !KEYS.contains(key)).sorted().toList();

        return unknown.isEmpty()
               ? Result.unitResult()
               : new ClusterConfigError.ParseFailed("[" + SECTION
                                                   + "] unknown key(s) " + String.join(", ", unknown)
                                                   + "; valid keys: " + String.join(", ", KEYS)).result();
    }
}

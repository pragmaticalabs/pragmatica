// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import java.util.List;
import java.util.Set;

import org.pragmatica.aether.config.ReplicationDefaultsConfig;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Result.success;


/// #1564: the cluster-wide replication defaults, the `[replication]` section of the COMMITTED cluster TOML
/// (`ClusterConfigKey.CURRENT`; owner ruling, know 596bdfd07(2)). Cluster-scoped, so every node resolves an
/// undeclared value to the same default; a per-node file would let two nodes resolve one resource differently.
///
/// ```toml
/// [replication]
/// replication_factor = 3       # default 3; at least 3 — a lower factor must be declared on the resource itself
/// confirmation_factor = 2      # default 2; 1 <= confirmation_factor <= replication_factor
///
/// [replication.cluster_events]
/// confirmation_factor = 1      # default 1; the system:cluster-events stream's CF (its RF is the cluster size)
///
/// [cache]
/// replication_factor = 1       # default 1; the DHT cache namespace's own declaration (#1777 track 1)
/// confirmation_factor = 1      # default 1; 1 <= confirmation_factor <= replication_factor
/// ```
///
/// The DHT resolves its own replication from `[replication]` too (#1777 track 1): writes are acked by
/// `confirmation_factor` replicas and reads ask `replication_factor - confirmation_factor + 1`. The cache is a
/// namespace that declares its own, lower factors — it is recomputable, so a single copy is its default.
///
/// An absent section, and a blank or seed cluster TOML, mean [ReplicationDefaultsConfig#BUILT_IN]. Every key is
/// typed and validated when the TOML is applied: a mistyped value, an out-of-range factor or an unknown key
/// refuses the whole apply, naming the key, rather than falling back to a default. Whether a factor fits the
/// cluster's desired core count is checked when each resource resolves, against the committed core count.
public sealed interface ReplicationDefaultsParser {
    String SECTION = "replication";
    String CLUSTER_EVENTS_SECTION = "replication.cluster_events";
    String CACHE_SECTION = "cache";
    String REPLICATION_FACTOR = "replication_factor";
    String CONFIRMATION_FACTOR = "confirmation_factor";
    Set<String> KEYS = Set.of(REPLICATION_FACTOR, CONFIRMATION_FACTOR);
    Set<String> CLUSTER_EVENTS_KEYS = Set.of(CONFIRMATION_FACTOR);
    Set<String> CACHE_KEYS = Set.of(REPLICATION_FACTOR, CONFIRMATION_FACTOR);
    int MINIMUM_DEFAULT_FACTOR = 3;

    /// The defaults committed in `tomlContent`. Blank content (a self-bootstrapped cluster's seed) is the
    /// built-in default.
    static Result<ReplicationDefaultsConfig> fromClusterToml(String tomlContent) {
        return tomlContent.isBlank()
               ? success(ReplicationDefaultsConfig.BUILT_IN)
               : TomlParser.parse(tomlContent)
                           .mapError(cause -> new ClusterConfigError.ParseFailed(cause.message()))
                           .flatMap(ReplicationDefaultsParser::parse);
    }

    /// The `[replication]` sections of an already parsed document; absent sections are the built-in defaults.
    static Result<ReplicationDefaultsConfig> parse(TomlDocument doc) {
        var builtIn = ReplicationDefaultsConfig.BUILT_IN;

        return refuseUnknownKeys(doc, SECTION, KEYS).flatMap(_ -> refuseUnknownKeys(doc,
                                                                                    CLUSTER_EVENTS_SECTION,
                                                                                    CLUSTER_EVENTS_KEYS))
                                .flatMap(_ -> refuseUnknownKeys(doc, CACHE_SECTION, CACHE_KEYS))
                                .flatMap(_ -> Result.all(factor(doc,
                                                                SECTION,
                                                                REPLICATION_FACTOR,
                                                                builtIn.replicationFactor()),
                                                         factor(doc,
                                                                SECTION,
                                                                CONFIRMATION_FACTOR,
                                                                builtIn.confirmationFactor()),
                                                         factor(doc,
                                                                CLUSTER_EVENTS_SECTION,
                                                                CONFIRMATION_FACTOR,
                                                                builtIn.clusterEventsConfirmationFactor()),
                                                         factor(doc,
                                                                CACHE_SECTION,
                                                                REPLICATION_FACTOR,
                                                                builtIn.cacheReplicationFactor()),
                                                         factor(doc,
                                                                CACHE_SECTION,
                                                                CONFIRMATION_FACTOR,
                                                                builtIn.cacheConfirmationFactor()))
                                                    .flatMap(ReplicationDefaultsParser::validated));
    }

    private static Result<ReplicationDefaultsConfig> validated(int rf, int cf, int eventsCf, int cacheRf, int cacheCf) {
        if (rf < MINIMUM_DEFAULT_FACTOR) {
            return failed("[replication] replication_factor = " + rf
                         + " is below 3; a default must be at least 3 (a lower factor is declared on the resource itself)");
        }

        if (cf < 1 || cf > rf) {
            return failed("[replication] confirmation_factor = " + cf
                         + " is invalid for replication_factor = " + rf
                         + ": 1 <= confirmation_factor <= replication_factor must hold");
        }

        if (eventsCf < 1) {
            return failed("[replication.cluster_events] confirmation_factor = " + eventsCf
                         + " is invalid: must be at least 1");
        }

        if (cacheRf < 1) {
            return failed("[cache] replication_factor = " + cacheRf + " is invalid: must be at least 1");
        }

        if (cacheCf < 1 || cacheCf > cacheRf) {
            return failed("[cache] confirmation_factor = " + cacheCf
                         + " is invalid for replication_factor = " + cacheRf
                         + ": 1 <= confirmation_factor <= replication_factor must hold");
        }

        return success(new ReplicationDefaultsConfig(rf, cf, eventsCf, cacheRf, cacheCf));
    }

    private static Result<Unit> refuseUnknownKeys(TomlDocument doc, String section, Set<String> known) {
        var unknown = doc.hasSection(section)
                      ? doc.keys(section).stream().filter(key -> !known.contains(key)).sorted().toList()
                      : List.<String> of();

        return unknown.isEmpty()
               ? Result.unitResult()
               : failed("[" + section
                       + "] unknown key(s) " + String.join(", ", unknown)
                       + "; valid keys: " + String.join(", ",
                                                        known.stream().sorted().toList()));
    }

    private static Result<Integer> factor(TomlDocument doc, String section, String key, int fallback) {
        return doc.hasSection(section) && doc.keys(section)
                                             .contains(key)
               ? doc.getInt(section, key)
                    .toResult(new ClusterConfigError.ParseFailed("[" + section + "] " + key + " must be an integer"))
               : success(fallback);
    }

    private static <T> Result<T> failed(String message) {
        return new ClusterConfigError.ParseFailed(message).result();
    }

    record unused() implements ReplicationDefaultsParser {}
}

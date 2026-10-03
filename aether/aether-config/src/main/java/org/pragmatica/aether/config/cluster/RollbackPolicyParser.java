// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import java.util.List;
import java.util.Set;

import org.pragmatica.aether.config.RollbackConfig;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.parse.TimeSpan.timeSpan;


/// #1573: the cluster-wide automatic-rollback policy, the `[rollback]` section of the COMMITTED cluster TOML
/// (`ClusterConfigKey.CURRENT`). Rollback is a leader decision, so its policy is cluster-scoped: a per-node
/// file would let nodes disagree about what the leader may do.
///
/// ```toml
/// [rollback]
/// enabled = true                          # default true — automatic rollback is ON unless turned off
/// trigger_on_all_instances_failed = true  # default true
/// cooldown = "5m"                         # minimum time between two automatic rollbacks of one slice
/// max_rollbacks = 2                       # automatic rollbacks per slice before a human must act
/// bake_window = "15m"                     # only a version younger than this is rolled back
/// ```
///
/// An absent section, and a blank or seed cluster TOML, mean the built-in defaults
/// ([RollbackConfig#rollbackConfig()]). Every key is typed and validated when the TOML is applied: a
/// mistyped value, a negative count, a non-positive bake window or an unknown key refuses the whole apply,
/// naming the key, rather than falling back silently to a default.
public sealed interface RollbackPolicyParser {
    String SECTION = "rollback";
    String ENABLED = "enabled";
    String TRIGGER = "trigger_on_all_instances_failed";
    String COOLDOWN = "cooldown";
    String MAX_ROLLBACKS = "max_rollbacks";
    String BAKE_WINDOW = "bake_window";
    Set<String> KEYS = Set.of(ENABLED, TRIGGER, COOLDOWN, MAX_ROLLBACKS, BAKE_WINDOW);

    /// The policy committed in `tomlContent`. The self-bootstrap seed carries no source configuration
    /// ([Option#none()]) and is the built-in default.
    static Result<RollbackConfig> fromClusterToml(Option<String> tomlContent) {
        return tomlContent.fold(() -> success(RollbackConfig.rollbackConfig()),
                                toml -> TomlParser.parse(toml)
                                                  .mapError(cause -> new ClusterConfigError.ParseFailed(cause.message()))
                                                  .flatMap(RollbackPolicyParser::parse));
    }

    /// The `[rollback]` section of an already parsed document; an absent section is the built-in default.
    static Result<RollbackConfig> parse(TomlDocument doc) {
        var defaults = RollbackConfig.rollbackConfig();

        return doc.hasSection(SECTION)
               ? refuseUnknownKeys(doc).flatMap(_ -> Result.all(flag(doc,
                                                                     ENABLED,
                                                                     defaults.enabled()),
                                                                flag(doc,
                                                                     TRIGGER,
                                                                     defaults.triggerOnAllInstancesFailed()),
                                                                duration(doc,
                                                                         COOLDOWN,
                                                                         defaults.cooldown(),
                                                                         false),
                                                                count(doc,
                                                                      MAX_ROLLBACKS,
                                                                      defaults.maxRollbacks()),
                                                                duration(doc,
                                                                         BAKE_WINDOW,
                                                                         defaults.bakeWindow(),
                                                                         true))
                                                           .flatMap(RollbackConfig::rollbackConfig))
               : success(defaults);
    }

    private static Result<Unit> refuseUnknownKeys(TomlDocument doc) {
        var unknown = doc.keys(SECTION).stream().filter(key -> !KEYS.contains(key)).sorted().toList();

        return unknown.isEmpty()
               ? Result.unitResult()
               : failed("[rollback] unknown key(s) " + String.join(", ", unknown)
                       + "; valid keys: " + String.join(", ",
                                                        List.of(ENABLED, TRIGGER, COOLDOWN, MAX_ROLLBACKS, BAKE_WINDOW)));
    }

    private static Result<Boolean> flag(TomlDocument doc, String key, boolean fallback) {
        return present(doc, key)
               ? doc.getBoolean(SECTION, key)
                    .toResult(new ClusterConfigError.ParseFailed("[rollback] " + key + " must be true or false"))
               : success(fallback);
    }

    private static Result<Integer> count(TomlDocument doc, String key, int fallback) {
        return present(doc, key)
               ? doc.getInt(SECTION, key)
                    .filter(value -> value >= 0)
                    .toResult(new ClusterConfigError.ParseFailed("[rollback] " + key + " must be a non-negative integer"))
               : success(fallback);
    }

    private static Result<TimeSpan> duration(TomlDocument doc, String key, TimeSpan fallback, boolean positive) {
        return present(doc, key)
               ? doc.getString(SECTION, key)
                    .toResult(new ClusterConfigError.ParseFailed("[rollback] " + key
                                                                + " must be a duration string, e.g. \"5m\""))
                    .flatMap(raw -> parsedDuration(key, raw, positive))
               : success(fallback);
    }

    private static Result<TimeSpan> parsedDuration(String key, String raw, boolean positive) {
        return timeSpan(raw).mapError(cause -> new ClusterConfigError.ParseFailed("[rollback] " + key
                                                                                 + ": " + cause.message()
                                                                                 + " (was '" + raw
                                                                                 + "')"))
                       .map(parsed -> TimeSpan.fromDuration(parsed.duration()))
                       .flatMap(span -> checkSign(key, raw, span, positive));
    }

    private static Result<TimeSpan> checkSign(String key, String raw, TimeSpan span, boolean positive) {
        var valid = positive
                    ? span.nanos() > 0
                    : span.nanos() >= 0;

        return valid
               ? success(span)
               : failed("[rollback] " + key
                       + " must be " + (positive
                                        ? "a positive"
                                        : "a non-negative")
                       + " duration (was '" + raw
                       + "')");
    }

    private static boolean present(TomlDocument doc, String key) {
        return doc.keys(SECTION)
                  .contains(key);
    }

    private static <T> Result<T> failed(String detail) {
        return new ClusterConfigError.ParseFailed(detail).result();
    }

    record unused() implements RollbackPolicyParser {}
}

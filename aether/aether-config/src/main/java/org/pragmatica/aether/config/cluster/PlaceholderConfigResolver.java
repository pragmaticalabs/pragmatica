// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Result;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Result.success;


/// #336 — position-based placeholder resolution for the CTM auto-heal / scale-up render path.
///
/// At bootstrap the cluster TOML is persisted to KV UNRESOLVED (spec REQ-KV-03 — the raw
/// `${env:...}` / `${secrets:...}` references are deliberately kept, never the operator-laptop
/// literals). When the leader's [ClusterTopologyManager] later provisions a replacement node it
/// re-parses that unresolved TOML and composes the new node's overlay WITHOUT any reference
/// resolution (the CLI-only `ConfigReferenceResolver` is structurally unreachable from this
/// module), so the overlay carries literal placeholders → the node crashes on config load and
/// never joins (the #336 over-provision leak).
///
/// The leader, however, already runs with its OWN per-node overlay RESOLVED to literals (same
/// compose pipeline, but rendered by the CLI from the resolved config). Both documents share the
/// SAME TOML structure — only the leaf values differ (placeholder vs literal). So for every leaf
/// in the composed overlay that still holds a placeholder, this resolver substitutes the resolved
/// source's value AT THE SAME TOML PATH. It is GENERIC (covers every reference, not a hardcoded
/// section list) and POSITION-BASED (the leader cannot resolve by variable name — names are lost
/// once the raw TOML is persisted). It writes NOTHING back to KV — it produces a fresh in-memory
/// overlay used only to render that one replacement's cloud-init user-data, restoring spec
/// REQ-4.2.7 ("the CLI-resolved credentials MUST be written into each node's per-node overlay")
/// for the runtime provisioning path.
///
/// Reuses the SAME placeholder regex the CLI `ConfigReferenceResolver` uses
/// (`\$\{(env|secrets):([^}]+)}`) so the two paths recognise references identically. A placeholder
/// whose path is absent — or itself a placeholder — in the resolved source is LEFT IN PLACE and
/// logged at WARN (this cannot happen for a well-formed cloud source, whose leader overlay is fully
/// resolved). Never throws; returns the resolved [TomlDocument] in a success [Result].
public sealed interface PlaceholderConfigResolver {
    record unused() implements PlaceholderConfigResolver {}

    Logger log = LoggerFactory.getLogger(PlaceholderConfigResolver.class);

    /// Same reference grammar as the CLI `ConfigReferenceResolver.REFERENCE_PATTERN`: an `env:` or
    /// `secrets:` reference wrapped in `${...}`.
    Pattern REFERENCE_PATTERN = Pattern.compile("\\$\\{(env|secrets):([^}]+)}");

    /// Resolve every placeholder leaf in `composed` from `resolvedSource` at the same TOML path.
    ///
    /// @param composed       the new node's overlay (placeholders intact), as produced by
    ///                       [ReplacementNodeConfigComposer#compose]
    /// @param resolvedSource the leader's OWN resolved overlay (literals), same TOML structure
    /// @return a new [TomlDocument] with placeholder leaves substituted by the source's literals;
    ///         leaves with no resolved counterpart are preserved unchanged
    static Result<TomlDocument> resolve(TomlDocument composed, TomlDocument resolvedSource) {
        return success(new TomlDocument(resolveSections(composed.sections(), resolvedSource),
                                        resolveTableArrays(composed.tableArrays(), resolvedSource)));
    }

    private static Map<String, Map<String, Object>> resolveSections(Map<String, Map<String, Object>> sections,
                                                                    TomlDocument resolvedSource) {
        var resolved = new LinkedHashMap<String, Map<String, Object>>();

        sections.forEach((section, keys) -> resolved.put(section, resolveSectionKeys(section, keys, resolvedSource)));

        return Map.copyOf(resolved);
    }

    private static Map<String, Object> resolveSectionKeys(String section,
                                                          Map<String, Object> keys,
                                                          TomlDocument resolvedSource) {
        var resolved = new LinkedHashMap<String, Object>();

        keys.forEach((key, value) -> resolved.put(key, resolveValue(section, key, value, resolvedSource)));

        return Map.copyOf(resolved);
    }

    private static Object resolveValue(String section, String key, Object value, TomlDocument resolvedSource) {
        return value instanceof String text && containsPlaceholder(text)
               ? resolveString(section, key, text, resolvedSource)
               : value;
    }

    private static boolean containsPlaceholder(String text) {
        return REFERENCE_PATTERN.matcher(text).find();
    }

    private static Object resolveString(String section, String key, String text, TomlDocument resolvedSource) {
        return resolvedSource.getString(section, key)
                             .filter(candidate -> !containsPlaceholder(candidate))
                             .onEmpty(() -> warnUnresolved(section, key, text))
                             .or(text);
    }

    @Contract
    private static void warnUnresolved(String section, String key, String text) {
        log.warn("#336: placeholder at [{}].{} ('{}') has no resolved counterpart in the leader's config — left unresolved",
                 section,
                 key,
                 text);
    }

    private static Map<String, List<Map<String, Object>>> resolveTableArrays(Map<String, List<Map<String, Object>>> tableArrays,
                                                                             TomlDocument resolvedSource) {
        var resolved = new LinkedHashMap<String, List<Map<String, Object>>>();

        tableArrays.forEach((name, tables) -> resolved.put(name, resolveTables(name, tables, resolvedSource)));

        return Map.copyOf(resolved);
    }

    private static List<Map<String, Object>> resolveTables(String name,
                                                           List<Map<String, Object>> tables,
                                                           TomlDocument resolvedSource) {
        return tables.stream()
                     .map(table -> resolveSectionKeys(name, table, resolvedSource))
                     .toList();
    }
}

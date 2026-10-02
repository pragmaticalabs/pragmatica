// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.artifact;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;


/// A grow-only set of names whose entries carry an archived flag (#1778). The only operations are the
/// ones that move an entry UP the state order `absent < present < archived`: [#add] (absent -> present,
/// an existing entry keeps its flag), [#archive] (present or absent -> archived) and [#merge], the
/// per-entry maximum. Nothing removes an entry or clears a flag, so merging two copies of the same set
/// is commutative, associative and idempotent, and a stale copy can never downgrade a newer one.
///
/// Serialized as a comma-separated list; an archived entry carries a trailing `!`.
record GrowOnlySet(Map<String, Boolean> entries) {
    private static final String ARCHIVED_SUFFIX = "!";
    private static final GrowOnlySet EMPTY = new GrowOnlySet(Map.of());

    GrowOnlySet {
        entries = Map.copyOf(entries);
    }

    static GrowOnlySet empty() {
        return EMPTY;
    }

    static GrowOnlySet growOnlySet(byte[] data) {
        return Stream.of(new String(data, StandardCharsets.UTF_8).split(","))
                     .filter(GrowOnlySet::isToken)
                     .reduce(EMPTY, GrowOnlySet::withToken, GrowOnlySet::merge);
    }

    GrowOnlySet add(String name) {
        return with(name, entries.getOrDefault(name, false));
    }

    GrowOnlySet archive(String name) {
        return with(name, true);
    }

    GrowOnlySet merge(GrowOnlySet other) {
        var merged = new LinkedHashMap<>(entries);

        other.entries.forEach((name, archived) -> merged.merge(name, archived, Boolean::logicalOr));

        return new GrowOnlySet(merged);
    }

    boolean contains(String name) {
        return entries.containsKey(name);
    }

    boolean isArchived(String name) {
        return entries.getOrDefault(name, false);
    }

    /// The entries that are present and not archived, in name order.
    List<String> live() {
        return entries.keySet()
                      .stream()
                      .filter(name -> !entries.get(name))
                      .sorted()
                      .toList();
    }

    byte[] toBytes() {
        return String.join(",", sortedTokens()).getBytes(StandardCharsets.UTF_8);
    }

    private List<String> sortedTokens() {
        return entries.entrySet()
                      .stream()
                      .sorted(Map.Entry.comparingByKey())
                      .map(GrowOnlySet::token)
                      .toList();
    }

    private GrowOnlySet with(String name, boolean archived) {
        var copy = new LinkedHashMap<>(entries);

        copy.put(name, archived);

        return new GrowOnlySet(copy);
    }

    private GrowOnlySet withToken(String token) {
        return token.endsWith(ARCHIVED_SUFFIX)
               ? archive(token.substring(0,
                                         token.length() - ARCHIVED_SUFFIX.length()))
               : add(token);
    }

    private static boolean isToken(String token) {
        return ! token.isEmpty();
    }

    private static String token(Map.Entry<String, Boolean> entry) {
        return entry.getValue()
               ? entry.getKey() + ARCHIVED_SUFFIX
               : entry.getKey();
    }
}

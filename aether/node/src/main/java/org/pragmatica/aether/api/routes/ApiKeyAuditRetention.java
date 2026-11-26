// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ApiKeyAuditKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ApiKeyAuditValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;


/// #3528: the API-key audit keeps the NEWEST [#MAX_ENTRIES_PER_KEY] entries PER KEY (the entry's own `keyId`, never
/// a prefix of its id, so `key-a` and `key-a-b` are two keys). The remover is the cluster leader's key sweep
/// ([ApiKeyRoutes#sweepExpiredKeys]): it submits the `Remove`s as a consensus batch, so every node applies the same
/// removal and no node prunes its own store.
///
/// A leader sweep, not a remove in the writing batch, because the writer's "oldest entry" comes from ITS local view:
/// two concurrent writers both remove the same oldest entry while each adds one, so the count would drift above the
/// bound and never come back. The sweep recomputes from state every period, covers every writer (route, expiry sweep,
/// bootstrap leg) and a restored backup with one rule, and survives a leader change (the next leader's sweep
/// continues). A deposed leader's stale view cannot over-remove: entries only ever gain NEWER timestamps, so any
/// entry a stale view ranks beyond the newest 1000 is beyond the newest 1000 of the true state too, and a `Remove` of
/// an entry already gone is a no-op.
///
/// The bound is a count enforced once per sweep: between two sweeps a key holds up to its bound plus the writes of
/// one sweep period. One sweep removes at most [#MAX_REMOVALS_PER_SWEEP] entries so that a restored, unbounded backup
/// does not become one enormous consensus batch; it drains over successive sweeps.
sealed interface ApiKeyAuditRetention {
    int MAX_ENTRIES_PER_KEY = 1000;
    int MAX_REMOVALS_PER_SWEEP = 1000;

    Comparator<Entry> NEWEST_FIRST = Comparator.comparingLong(Entry::timestamp)
                                               .thenComparing(entry -> entry.key()
                                                                            .entryId())
                                               .reversed();

    Comparator<Entry> OLDEST_FIRST = NEWEST_FIRST.reversed();

    /// `Remove` commands for the oldest entries beyond the bound of their key, oldest first, at most
    /// [#MAX_REMOVALS_PER_SWEEP]. Empty when every key is within its bound.
    static List<KVCommand<AetherKey>> excessRemovals(KVStore<AetherKey, AetherValue> store) {
        var byKey = new HashMap<String, List<Entry>>();

        store.forEach(ApiKeyAuditKey.class,
                      ApiKeyAuditValue.class,
                      (key, value) -> byKey.computeIfAbsent(value.keyId(),
                                                            _ -> new ArrayList<>())
                                           .add(Entry.entry(key,
                                                            value.timestamp())));

        return byKey.values()
                    .stream()
                    .flatMap(ApiKeyAuditRetention::beyondBound)
                    .sorted(OLDEST_FIRST)
                    .limit(MAX_REMOVALS_PER_SWEEP)
                    .map(ApiKeyAuditRetention::remove)
                    .toList();
    }

    private static Stream<Entry> beyondBound(List<Entry> entriesOfKey) {
        return entriesOfKey.stream()
                           .sorted(NEWEST_FIRST)
                           .skip(MAX_ENTRIES_PER_KEY);
    }

    @SuppressWarnings("unchecked")
    private static KVCommand<AetherKey> remove(Entry entry) {
        return (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Remove<>(entry.key());
    }

    record Entry(ApiKeyAuditKey key, long timestamp) {
        static Entry entry(ApiKeyAuditKey key, long timestamp) {
            return new Entry(key, timestamp);
        }
    }

    record unused() implements ApiKeyAuditRetention {}
}

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.replica;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

import org.pragmatica.cluster.state.kvstore.IncarnationTag;
import org.pragmatica.cluster.state.kvstore.ReplicaSnapshot;
import org.pragmatica.lang.Option;


/// The encoded filtered snapshots a core reuses for the same `(tag, revision)` for a few seconds, so a join storm costs one capture per
/// revision, not one per joiner. Bounded in entries (I-W11): a `put` beyond the bound drops the oldest, and an entry past its age is never served.
final class SnapshotCache {
    /// A captured snapshot with the hex SHA-256 of its whole encoding.
    record Cached(ReplicaSnapshot snapshot, String hash, long storedAtNanos) {}

    private final int maxEntries;
    private final long ttlNanos;
    private final LongSupplier nanos;
    private final List<Cached> entries = new ArrayList<>();

    SnapshotCache(int maxEntries, long ttlNanos, LongSupplier nanos) {
        this.maxEntries = maxEntries;
        this.ttlNanos = ttlNanos;
        this.nanos = nanos;
    }

    synchronized Option<Cached> get(IncarnationTag tag, long revision) {
        dropExpired();

        return Option.from(entries.stream()
                                  .filter(cached -> cached.snapshot()
                                                          .tag()
                                                          .equals(tag) && cached.snapshot()
                                                                                .revision() == revision)
                                  .findFirst());
    }

    /// Stores `cached` and drops the oldest entries beyond the bound; answers the entry it stored.
    synchronized Cached put(Cached cached) {
        dropExpired();
        entries.add(cached);
        while (entries.size() > maxEntries) {
            entries.removeFirst();
        }

        return cached;
    }

    synchronized int size() {
        dropExpired();

        return entries.size();
    }

    private void dropExpired() {
        var now = nanos.getAsLong();

        entries.removeIf(cached -> now - cached.storedAtNanos() > ttlNanos);
    }
}

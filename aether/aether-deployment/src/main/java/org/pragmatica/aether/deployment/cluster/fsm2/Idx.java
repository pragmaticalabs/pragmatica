// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

/// Helpers for the model's secondary indexes, which are maps from a key to a persistent set.
public sealed interface Idx {
    static <K, V> PMap<K, PSet<V>> add(PMap<K, PSet<V>> index, K key, V value) {
        return index.put(key,
                         index.get(key).or(PSet.<V> empty()).add(value));
    }

    static <K, V> PMap<K, PSet<V>> remove(PMap<K, PSet<V>> index, K key, V value) {
        var shrunk = index.get(key).or(PSet.<V> empty()).remove(value);

        return shrunk.isEmpty()
               ? index.remove(key)
               : index.put(key, shrunk);
    }

    static <K, V> PSet<V> members(PMap<K, PSet<V>> index, K key) {
        return index.get(key)
                    .or(PSet.<V> empty());
    }

    record unused() implements Idx {}
}

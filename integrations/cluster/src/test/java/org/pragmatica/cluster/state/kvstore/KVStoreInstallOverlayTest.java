// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;

/// #1068 (review N1/N3): a mid-life snapshot install must never let a concurrent reader observe an
/// EMPTY store. `installSilently` used to `clear()` then `putAll()`; a gate deciding whether a committed
/// record exists, reading between the two, saw nothing and refused. The install now overlays first and
/// drops the vanished keys afterwards, so at every instant the store holds at least the old view or the
/// new one.
///
/// The intermediate state is observed from INSIDE the install: `putAll` iterates the restored map's
/// entry set, and this map's iterator reads the store back on each step. With clear-then-put the first
/// probe finds the pre-install key gone; with the overlay it is still there.
class KVStoreInstallOverlayTest {
    record InstallKey(String id) implements StructuredKey {}

    @Test
    void installSilently_neverExposesAnEmptyStore_midInstall() {
        var seenDuringInstall = new ArrayList<Boolean>();
        var probe = new ProbingMap<InstallKey, String>();
        var store = new KVStore<InstallKey, String>(MessageRouter.mutable(), stubSerializer(), deserializerReturning(probe));
        var existing = new InstallKey("existing");

        store.process(store.createBatch(List.of(new Put<>(existing, "old"))));
        probe.onStep = () -> seenDuringInstall.add(store.get(existing).isPresent());
        probe.put(new InstallKey("restored-1"), "new-1");
        probe.put(new InstallKey("restored-2"), "new-2");

        store.restoreSnapshot(new byte[0]).unwrap();

        assertThat(seenDuringInstall)
                .as("the pre-install key is readable on every step of the install — the store is never empty")
                .isNotEmpty()
                .allMatch(present -> present);
        assertThat(store.get(existing).isPresent())
                .as("and it is gone once the install completes: the snapshot did not carry it")
                .isFalse();
        assertThat(store.get(new InstallKey("restored-1")).isPresent()).isTrue();
        assertThat(store.get(new InstallKey("restored-2")).isPresent()).isTrue();
    }

    /// A map whose entry-set iteration runs `onStep` before yielding each entry, so a `putAll` over it
    /// can be observed mid-way.
    private static final class ProbingMap<K, V> extends HashMap<K, V> {
        Runnable onStep = () -> {};

        @Override
        public Set<Map.Entry<K, V>> entrySet() {
            var delegate = super.entrySet();

            return new java.util.AbstractSet<>() {
                @Override
                public Iterator<Map.Entry<K, V>> iterator() {
                    var inner = delegate.iterator();

                    return new Iterator<>() {
                        @Override
                        public boolean hasNext() {
                            return inner.hasNext();
                        }

                        @Override
                        public Map.Entry<K, V> next() {
                            onStep.run();

                            return inner.next();
                        }
                    };
                }

                @Override
                public int size() {
                    return delegate.size();
                }
            };
        }
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    @SuppressWarnings("unchecked")
    private static Deserializer deserializerReturning(Map<?, ?> restored) {
        return new Deserializer() {
            @Override public <T> T read(ByteBuf byteBuf) {
                return (T) restored;
            }
        };
    }
}

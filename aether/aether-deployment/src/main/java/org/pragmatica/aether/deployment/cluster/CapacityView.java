// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityLedgerValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityReservationValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// What the rest of the system may read of capacity (O3-3, q4 7.1.3): the per-node reservation and the fleet counter, over the live store or over
/// one snapshot of it. A reader states its reading once, with the three names [#isLive], [#isFreed] and [#isAbsent], instead of re-deriving it
/// from a phase or from the presence of a key at each site; under impl A (the reservation key) nothing is ever freed, so the three reduce to
/// presence and absence. O3-4 changes what the names mean for a `FREED` tombstone, not the sites.
///
/// The view speaks today's values ([CapacityReservationValue], [CapacityLedgerValue]). A snapshot view reads exactly the map it was given, so a caller
/// that decided on one snapshot keeps deciding on it.
public interface CapacityView {
    /// The reservation of `node`, when one is committed and is a reservation.
    Option<CapacityReservationValue> reservation(NodeId node);
    /// Whether ANY value is committed under the reservation of `node` (presence, not type).
    boolean holds(NodeId node);
    /// Every committed reservation, by node.
    Map<NodeId, CapacityReservationValue> reservations();
    /// Every node that has a reservation key, whatever sits under it.
    Set<NodeId> reservedNodes();
    /// The fleet counter, when committed and a counter.
    Option<CapacityLedgerValue> ledger();
    /// Whether anything is committed under the fleet counter's key.
    boolean hasLedger();
    /// A read witness that the reservation of `node` is, at commit, what this view holds for it (absent, or the exact value).
    KVCommand.ReadWitness<AetherKey> witness(NodeId node);

    /// A read witness that the reservation of `node` is `expected` (absent, or an exact value) at commit: the guard of a decision taken on a value read earlier.
    default KVCommand.ReadWitness<AetherKey> witness(NodeId node, Option<CapacityReservationValue> expected) {
        return new KVCommand.ReadWitness<>(new AetherKey.CapacityReservationKey(node),
                                           expected.map(value -> (Object) value));
    }

    /// The reservation of `node` was freed (kept as a tombstone). Never under impl A.
    boolean isFreed(NodeId node);

    /// Nothing is committed for `node`.
    default boolean isAbsent(NodeId node) {
        return ! holds(node);
    }

    /// A reservation is committed for `node` and has not been freed.
    default boolean isLive(NodeId node) {
        return holds(node) && !isFreed(node);
    }

    /// The view over the live store: every call reads it afresh.
    static CapacityView overStore(KVStore<AetherKey, AetherValue> store) {
        return reading(key -> store.get(key)
                                   .or((AetherValue) null),
                       store::getTyped,
                       store::snapshot);
    }

    /// The view over one snapshot: every call reads the same map.
    static CapacityView overSnapshot(Map<AetherKey, AetherValue> snapshot) {
        return reading(snapshot::get,
                       new Typed() {
                           @Override
                           public <T> Option<T> read(AetherKey key, Class<T> type) {
                               return Option.option(snapshot.get(key))
                                            .filter(type::isInstance)
                                            .map(type::cast);
                           }
                       },
                       () -> snapshot);
    }

    /// A typed read of one key, as `KVStore#getTyped` does it: the value when it is there and is of the type.
    interface Typed {
        <T> Option<T> read(AetherKey key, Class<T> type);
    }

    private static CapacityView reading(Function<AetherKey, Object> read, Typed typed, Supplier<Map<?, ?>> all) {
        record reading(Function<AetherKey, Object> read, Typed typed, Supplier<Map<?, ?>> all) implements CapacityView {
            @Override
            public Option<CapacityReservationValue> reservation(NodeId node) {
                return typed.read(new AetherKey.CapacityReservationKey(node), CapacityReservationValue.class);
            }

            @Override
            public boolean holds(NodeId node) {
                return Option.option(read.apply(new AetherKey.CapacityReservationKey(node))).isPresent();
            }

            @Override
            public Map<NodeId, CapacityReservationValue> reservations() {
                var result = new HashMap<NodeId, CapacityReservationValue>();

                all.get()
                   .forEach((key, value) -> {
                                if (key instanceof AetherKey.CapacityReservationKey reservation && value instanceof CapacityReservationValue state) {
                                    result.put(reservation.nodeId(),
                                               state);
                                }
                            });

                return Map.copyOf(result);
            }

            @Override
            public Set<NodeId> reservedNodes() {
                var result = new HashSet<NodeId>();

                all.get()
                   .keySet()
                   .forEach(key -> {
                                if (key instanceof AetherKey.CapacityReservationKey reservation) {
                                    result.add(reservation.nodeId());
                                }
                            });

                return Set.copyOf(result);
            }

            @Override
            public Option<CapacityLedgerValue> ledger() {
                return typed.read(AetherKey.CapacityLedgerKey.INSTANCE, CapacityLedgerValue.class);
            }

            @Override
            public boolean hasLedger() {
                return Option.option(read.apply(AetherKey.CapacityLedgerKey.INSTANCE)).isPresent();
            }

            @Override
            public KVCommand.ReadWitness<AetherKey> witness(NodeId node) {
                var key = new AetherKey.CapacityReservationKey(node);

                return new KVCommand.ReadWitness<>(key,
                                                   Option.option(read.apply(key)).map(value -> (Object) value));
            }

            @Override
            public boolean isFreed(NodeId node) {
                return false;
            }
        }

        return new reading(read, typed, all);
    }
}

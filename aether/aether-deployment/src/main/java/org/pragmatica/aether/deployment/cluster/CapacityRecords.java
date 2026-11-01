// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.HashMap;
import java.util.Map;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityLedgerValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityReservationValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// Where `CapacityControlledLifecycle` keeps what it knows of capacity (O3-2, q4 7.1.3): the per-node reservation and the fleet counter. The
/// lifecycle reads and writes them ONLY through this port, so that O3-4 can move the reservation into the per-node lifecycle record by
/// replacing the implementation, with the lifecycle's own logic and the golden table untouched.
///
/// The port is a seam, not an abstraction of behaviour: it speaks today's values ([CapacityReservationValue], [CapacityLedgerValue]) and
/// hands back the exact [KVCommand.Mutation]s and [KVCommand.ReadWitness]es the lifecycle used to build inline. [#overReservations] is
/// impl A, over `CapacityReservationKey` and `CapacityLedgerKey`, and reproduces every old access one for one.
interface CapacityRecords {
    /// The reservation of `node`, when one is committed and is a reservation.
    Option<CapacityReservationValue> reservation(NodeId node);
    /// Whether ANY value is committed under the reservation of `node` (presence, not type).
    boolean holds(NodeId node);
    /// Every committed reservation, by node.
    Map<NodeId, CapacityReservationValue> reservations();
    /// The fleet counter, when committed.
    Option<CapacityLedgerValue> ledger();
    /// The key that names a transaction about the reservation of `node`.
    AetherKey recordKey(NodeId node);
    /// The key that names a transaction about the fleet counter.
    AetherKey ledgerKey();

    /// Put the reservation of `node`, expecting `expected` to be what is committed (absent, or an exact value).
    KVCommand.Mutation<AetherKey, AetherValue> put(NodeId node,
                                                   Option<CapacityReservationValue> expected,
                                                   CapacityReservationValue next);

    /// Create the reservation of `node` (expects it absent).
    KVCommand.Mutation<AetherKey, AetherValue> create(NodeId node, CapacityReservationValue value);

    /// Replace the reservation of `node` `before` with `after`.
    KVCommand.Mutation<AetherKey, AetherValue> replace(NodeId node,
                                                       CapacityReservationValue before,
                                                       CapacityReservationValue after);

    /// Remove the reservation of `node` that is `before`.
    KVCommand.Mutation<AetherKey, AetherValue> remove(NodeId node, CapacityReservationValue before);
    /// A read witness that the reservation of `node` is still `expected` (absent, or an exact value).
    KVCommand.ReadWitness<AetherKey> witness(NodeId node, Option<CapacityReservationValue> expected);

    /// Move the fleet counter from `before` to `after`.
    KVCommand.Mutation<AetherKey, AetherValue> ledgerChange(Option<CapacityLedgerValue> before,
                                                            CapacityLedgerValue after);

    /// Impl A: the reservations are `CapacityReservationKey` entries of `store` and the counter is `CapacityLedgerKey`.
    static CapacityRecords overReservations(KVStore<AetherKey, AetherValue> store) {
        record overReservations(KVStore<AetherKey, AetherValue> store) implements CapacityRecords {
            @Override
            public Option<CapacityReservationValue> reservation(NodeId node) {
                return store.getTyped(new AetherKey.CapacityReservationKey(node), CapacityReservationValue.class);
            }

            @Override
            public boolean holds(NodeId node) {
                return store.get(new AetherKey.CapacityReservationKey(node))
                            .isPresent();
            }

            @Override
            public Map<NodeId, CapacityReservationValue> reservations() {
                var result = new HashMap<NodeId, CapacityReservationValue>();
                Map<?, ?> snapshot = store.snapshot();

                snapshot.forEach((key, value) -> {
                    if (key instanceof AetherKey.CapacityReservationKey reservation && value instanceof CapacityReservationValue state) {
                        result.put(reservation.nodeId(), state);
                    }
                });

                return Map.copyOf(result);
            }

            @Override
            public Option<CapacityLedgerValue> ledger() {
                return store.getTyped(AetherKey.CapacityLedgerKey.INSTANCE, CapacityLedgerValue.class);
            }

            @Override
            public AetherKey recordKey(NodeId node) {
                return new AetherKey.CapacityReservationKey(node);
            }

            @Override
            public AetherKey ledgerKey() {
                return AetherKey.CapacityLedgerKey.INSTANCE;
            }

            @Override
            public KVCommand.Mutation<AetherKey, AetherValue> put(NodeId node,
                                                                  Option<CapacityReservationValue> expected,
                                                                  CapacityReservationValue next) {
                return new KVCommand.Mutation<>(recordKey(node),
                                                expected.map(value -> (AetherValue) value),
                                                Option.some(next));
            }

            @Override
            public KVCommand.Mutation<AetherKey, AetherValue> create(NodeId node, CapacityReservationValue value) {
                return new KVCommand.Mutation<>(recordKey(node), Option.none(), Option.some(value));
            }

            @Override
            public KVCommand.Mutation<AetherKey, AetherValue> replace(NodeId node,
                                                                      CapacityReservationValue before,
                                                                      CapacityReservationValue after) {
                return new KVCommand.Mutation<>(recordKey(node), Option.some(before), Option.some(after));
            }

            @Override
            public KVCommand.Mutation<AetherKey, AetherValue> remove(NodeId node, CapacityReservationValue before) {
                return new KVCommand.Mutation<>(recordKey(node), Option.some(before), Option.none());
            }

            @Override
            public KVCommand.ReadWitness<AetherKey> witness(NodeId node, Option<CapacityReservationValue> expected) {
                return new KVCommand.ReadWitness<>(recordKey(node), expected.map(value -> (Object) value));
            }

            @Override
            public KVCommand.Mutation<AetherKey, AetherValue> ledgerChange(Option<CapacityLedgerValue> before,
                                                                           CapacityLedgerValue after) {
                return new KVCommand.Mutation<>(ledgerKey(),
                                                before.map(value -> (AetherValue) value),
                                                Option.some(after));
            }
        }

        return new overReservations(store);
    }
}

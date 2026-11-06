// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityLedgerValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityReservationValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// Where the capacity of the system is kept (O3-2/O3-3, q4 7.1.3): the per-node reservation and the fleet counter. `CapacityControlledLifecycle` and
/// the one writer outside it (`NodeReplacementWiring`) read and write them ONLY through this port (the readers use its read half, [CapacityView]), so that O3-4 can move the reservation into the per-node lifecycle record by
/// replacing the implementation, with the lifecycle's own logic and the golden table untouched.
///
/// The port is a seam, not an abstraction of behaviour: it speaks today's values ([CapacityReservationValue], [CapacityLedgerValue]) and
/// hands back the exact [KVCommand.Mutation]s and [KVCommand.ReadWitness]es the lifecycle used to build inline. [#overReservations] is
/// impl A, over `CapacityReservationKey` and `CapacityLedgerKey`, and reproduces every old access one for one.
public interface CapacityRecords extends CapacityView {
    /// The key that names a transaction about the reservation of `node`.
    AetherKey recordKey(NodeId node);
    /// The key that names a transaction about the fleet counter.
    AetherKey ledgerKey();

    /// The guard of a decision taken on `expected`, on the key of the reservation of `node` as THIS implementation keeps it ([#recordKey]).
    @Override
    default KVCommand.ReadWitness<AetherKey> witness(NodeId node, Option<CapacityReservationValue> expected) {
        return new KVCommand.ReadWitness<>(recordKey(node), expected.map(value -> (Object) value));
    }

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

    /// Release the reservation of `node` that is `before`: impl A deletes it (O3-4's impl B writes the `FREED` tombstone instead).
    KVCommand.Mutation<AetherKey, AetherValue> remove(NodeId node, CapacityReservationValue before);

    /// Move the fleet counter from `before` to `after`.
    KVCommand.Mutation<AetherKey, AetherValue> ledgerChange(Option<CapacityLedgerValue> before,
                                                            CapacityLedgerValue after);

    /// The mutation builders of impl A alone, for a caller that builds a write without holding a store (a pure function of its inputs). Its
    /// reads see an empty store and mean nothing.
    static CapacityRecords forBuilding() {
        return overReservations(CapacityView.overSnapshot(Map.of()));
    }

    /// Impl A: the reservations are `CapacityReservationKey` entries of `store` and the counter is `CapacityLedgerKey`.
    static CapacityRecords overReservations(KVStore<AetherKey, AetherValue> store) {
        return overReservations(CapacityView.overStore(store));
    }

    private static CapacityRecords overReservations(CapacityView view) {
        record overReservations(CapacityView view) implements CapacityRecords {
            @Override
            public Option<CapacityReservationValue> reservation(NodeId node) {
                return view.reservation(node);
            }

            @Override
            public boolean holds(NodeId node) {
                return view.holds(node);
            }

            @Override
            public Map<NodeId, CapacityReservationValue> reservations() {
                return view.reservations();
            }

            @Override
            public Set<NodeId> reservedNodes() {
                return view.reservedNodes();
            }

            @Override
            public Option<CapacityLedgerValue> ledger() {
                return view.ledger();
            }

            @Override
            public boolean hasLedger() {
                return view.hasLedger();
            }

            @Override
            public KVCommand.ReadWitness<AetherKey> witness(NodeId node) {
                return view.witness(node);
            }

            @Override
            public boolean isFreed(NodeId node) {
                return view.isFreed(node);
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
            public KVCommand.Mutation<AetherKey, AetherValue> ledgerChange(Option<CapacityLedgerValue> before,
                                                                           CapacityLedgerValue after) {
                return new KVCommand.Mutation<>(ledgerKey(),
                                                before.map(value -> (AetherValue) value),
                                                Option.some(after));
            }
        }

        return new overReservations(view);
    }
}

// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.entityforward;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.EntityKeyspaceRegistrationKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.EntityKeyspaceRegistrationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;


/// Whether COMMITTED cluster state still makes `self` answerable for an entity keyspace — the test that
/// separates "this node has just stopped serving a keyspace it was handed" (a handoff, transient) from
/// "nobody ever told this node it serves that keyspace" (unknown, terminal).
///
/// Two committed facts count, and both are read from the replicated KV rather than from anything local:
///   - a per-node HOSTING registration `(keyspace, self)` — the record the leader mints ownership over
///     (`EntityOwnershipReconciler.scanRegistrations`), still present until this node's reconcile prunes
///     it after the slice unloads; and
///   - an OWNERSHIP record of any `entity:<keyspace>` arc naming `self` — which outlives the registration,
///     because it is the LEADER that re-mints it, one reconcile later. Reading the registration alone would
///     answer terminally in exactly the gap between the node's prune and the leader's mint.
///
/// Local state is deliberately not consulted: the local target is already known to be absent here, and the
/// local declared set was emptied by the same unload that created the window.
public final class CommittedEntityStake {
    private CommittedEntityStake() {}

    public static Predicate<String> committedEntityStake(KVStore<AetherKey, AetherValue> kvStore, NodeId self) {
        return keyspace -> isRegisteredHost(kvStore, self, keyspace) || ownsAnyArc(kvStore, self, keyspace);
    }

    private static boolean isRegisteredHost(KVStore<AetherKey, AetherValue> kvStore, NodeId self, String keyspace) {
        return kvStore.getTyped(EntityKeyspaceRegistrationKey.entityKeyspaceRegistrationKey(keyspace, self),
                                EntityKeyspaceRegistrationValue.class)
                      .isPresent();
    }

    private static boolean ownsAnyArc(KVStore<AetherKey, AetherValue> kvStore, NodeId self, String keyspace) {
        var arcName = EntityPartitionArc.arcName(keyspace);
        var owned = new AtomicBoolean(false);

        kvStore.forEach(StreamPartitionOwnershipKey.class,
                        StreamPartitionOwnershipValue.class,
                        (key, value) -> {
                            if (key.stream()
                                   .equals(arcName) && value.owner()
                                                            .equals(self)) {
                            owned.set(true);
                        }
                        });

        return owned.get();
    }
}

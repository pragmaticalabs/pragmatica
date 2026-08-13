// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import org.pragmatica.aether.resource.entity.DurableEntityProvisioningError.InvalidPartitionCount;
import org.pragmatica.aether.resource.entity.DurableEntityProvisioningError.InvalidReplicationFactor;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.lang.Result.all;


/// Configuration for a [DurableEntity] resource.
///
/// Bound from an `[entities.*]` section of the blueprint's `resources.toml` by the record binder, which
/// prefers this type's `durableEntityConfig(String, int, int)` factory over the canonical constructor —
/// so every rule below runs at BIND time and a rejected declaration fails slice loading with a named
/// cause rather than producing a config object nobody can honour.
///
/// @param keyspace          logical name of the entity family (e.g. `"orders"`); also the name of the
///                          `(keyspace, partition)` ownership arcs the write fence and the linearizable
///                          read pipeline both key on
/// @param partitionCount    number of ownership arcs the keyspace's keys are spread across via
///                          [org.pragmatica.aether.dht.EntityPartitionArc]; the fence granularity —
///                          a reshuffle of one arc never fences a key that hashes to another
/// @param replicationFactor total copies of each partition INCLUDING the owner, honoured as the backing
///                          stream's `replicas`. See [#minSyncReplicas()] for what each value actually
///                          buys — this is the knob that decides whether entity state survives losing a
///                          node, so it is honoured rather than refused (#345 I3)
public record DurableEntityConfig(String keyspace, int partitionCount, int replicationFactor) {
    /// Enough copies to survive one node loss with a quorum left over. The default is deliberately NOT
    /// `1`: a durable entity that silently evaporates when its owner dies would not earn the name, and a
    /// default has to be the safe reading of the word.
    public static final int DEFAULT_REPLICATION_FACTOR = 3;

    /// The in-sync acks a write waits for, INCLUDING the owner — so `2` means the owner plus one peer,
    /// i.e. `awaitReplication(..., minAcks)` blocks on ONE distinct non-self ack.
    ///
    /// ## Why this is derived and not configured
    /// It is the difference between the two guarantees this resource can offer, and both are honest:
    ///
    ///   - `replicationFactor == 1` ⇒ `1`. No peer holds the record. State survives a RESTART of the
    ///     owner (the local WAL is fsync-durable before the write acks) but NOT the owner's death: a new
    ///     owner recovers only to the last sealed segment plus checkpoint, and everything after it is on
    ///     a disk nobody is reading. This is a legitimate single-node/dev choice, and it is the caller's
    ///     to make EXPLICITLY.
    ///   - `replicationFactor >= 2` ⇒ `2`. A peer holds the record before the write acks, so a new owner
    ///     replays a log that already contains it.
    ///
    /// Deriving rather than configuring is what keeps the runtime from silently degrading: a cluster too
    /// small to satisfy the barrier FAILS the write with a typed cause instead of quietly serving the
    /// weaker guarantee under the stronger name. Whoever wants the weaker one writes `1` and can be held
    /// to it.
    public int minSyncReplicas() {
        return Math.min(2, replicationFactor);
    }

    private static final int DEFAULT_PARTITION_COUNT = 64;

    /// Build a config with the default partition count and replication factor.
    ///
    /// @param keyspace logical name of the entity family
    ///
    /// @return the config
    public static Result<DurableEntityConfig> durableEntityConfig(String keyspace) {
        return durableEntityConfig(keyspace, DEFAULT_PARTITION_COUNT, DEFAULT_REPLICATION_FACTOR);
    }

    /// Build a config with an explicit partition count and replication factor.
    ///
    /// @param keyspace          logical name of the entity family
    /// @param partitionCount    number of ownership arcs for the keyspace
    /// @param replicationFactor total copies including the owner; must be at least 1
    ///
    /// @return the config, or a failure naming the rule the declaration broke
    public static Result<DurableEntityConfig> durableEntityConfig(String keyspace,
                                                                  int partitionCount,
                                                                  int replicationFactor) {
        return all(Verify.ensure(keyspace, Verify.Is::present),
                   Verify.ensure(partitionCount,
                                 Verify.Is::greaterThanOrEqualTo,
                                 1,
                                 new InvalidPartitionCount(partitionCount)),
                   Verify.ensure(replicationFactor,
                                 Verify.Is::greaterThanOrEqualTo,
                                 1,
                                 new InvalidReplicationFactor(replicationFactor))).map(DurableEntityConfig::new);
    }
}

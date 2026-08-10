// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import org.pragmatica.aether.resource.entity.DurableEntityProvisioningError.InvalidPartitionCount;
import org.pragmatica.aether.resource.entity.DurableEntityProvisioningError.ReplicationNotSupported;
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
/// @param replicationFactor replica count; only [#SUPPORTED_REPLICATION_FACTOR] is accepted today and
///                          anything else is REFUSED ([ReplicationNotSupported]) rather than recorded
///                          and ignored, which is what it was before #345 I1
public record DurableEntityConfig(String keyspace, int partitionCount, int replicationFactor) {
    /// The one replica this cut actually keeps. The entity commits to a single local `StorageEngine`;
    /// nothing spans nodes until the restart-durable fenced-log backing (plan Phase 3 / #349).
    public static final int SUPPORTED_REPLICATION_FACTOR = 1;

    private static final int DEFAULT_PARTITION_COUNT = 64;

    /// Build a config with the default partition count and the only supported replication factor.
    ///
    /// @param keyspace logical name of the entity family
    ///
    /// @return the config
    public static Result<DurableEntityConfig> durableEntityConfig(String keyspace) {
        return durableEntityConfig(keyspace, DEFAULT_PARTITION_COUNT, SUPPORTED_REPLICATION_FACTOR);
    }

    /// Build a config with an explicit partition count and replication factor, refusing a replication
    /// factor this cut cannot honour.
    ///
    /// @param keyspace          logical name of the entity family
    /// @param partitionCount    number of ownership arcs for the keyspace
    /// @param replicationFactor replica count; must be [#SUPPORTED_REPLICATION_FACTOR]
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
                                 Verify.Is::equalTo,
                                 SUPPORTED_REPLICATION_FACTOR,
                                 new ReplicationNotSupported(replicationFactor, SUPPORTED_REPLICATION_FACTOR))).map(DurableEntityConfig::new);
    }
}

// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import org.pragmatica.aether.resource.entity.EntityProvisioningError.InvalidKeyspace;
import org.pragmatica.aether.resource.entity.EntityProvisioningError.InvalidPartitionCount;
import org.pragmatica.aether.slice.ReplicationDeclaration;
import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.config.StrictKeys;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Result.all;


/// Configuration for a [DurableEntity] resource.
///
/// Bound from an `[entities.*]` section of the blueprint's `resources.toml` by the record binder, which
/// prefers this type's `durableEntityConfig(String, int, Option, Option)` factory over the canonical
/// constructor — so every rule below runs at BIND time and a rejected declaration fails slice loading with a
/// named cause rather than producing a config object nobody can honour. [StrictKeys]: a key the section does
/// not declare (a mistyped key, or the pre-#1564 derived `min_sync_replicas`) fails the bind instead of being
/// silently ignored.
///
/// @param keyspace           logical name of the entity family (e.g. `"orders"`); also the name of the
///                           `(keyspace, partition)` ownership arcs the write fence and the linearizable
///                           read pipeline both key on
/// @param partitionCount     number of ownership arcs the keyspace's keys are spread across via
///                           [org.pragmatica.aether.dht.EntityPartitionArc]; the fence granularity —
///                           a reshuffle of one arc never fences a key that hashes to another
/// @param replicationFactor  `replication_factor`: copies of each partition INCLUDING the owner; absent takes
///                           the committed cluster default (#1564)
/// @param confirmationFactor `confirmation_factor`: copies, the owner included, that hold a write before it is
///                           acknowledged; absent takes `min(cluster default, replication_factor)` (#1564)
@StrictKeys
public record DurableEntityConfig(String keyspace,
                                  int partitionCount,
                                  Option<Integer> replicationFactor,
                                  Option<Integer> confirmationFactor) {
    private static final int DEFAULT_PARTITION_COUNT = 64;

    /// Build a config with the default partition count and the cluster's default replication factors.
    ///
    /// @param keyspace logical name of the entity family
    ///
    /// @return the config
    public static Result<DurableEntityConfig> durableEntityConfig(String keyspace) {
        return durableEntityConfig(keyspace, DEFAULT_PARTITION_COUNT, none(), none());
    }

    /// Build a config with an explicit partition count and declared replication factors.
    ///
    /// @param keyspace           logical name of the entity family; non-blank, no `/` (see
    ///                           [EntityProvisioningError.InvalidKeyspace] for why the character is reserved)
    /// @param partitionCount     number of ownership arcs for the keyspace
    /// @param replicationFactor  declared `replication_factor`, if any
    /// @param confirmationFactor declared `confirmation_factor`, if any
    ///
    /// @return the config, or a failure naming the rule the declaration broke. The factors are checked here
    ///         only where no default is involved (a declared factor below 1, a declared CF above a declared
    ///         RF); the rest waits for the cluster defaults at provisioning ([DurableEntityFactory]).
    public static Result<DurableEntityConfig> durableEntityConfig(String keyspace,
                                                                  int partitionCount,
                                                                  Option<Integer> replicationFactor,
                                                                  Option<Integer> confirmationFactor) {
        return all(validKeyspace(keyspace),
                   Verify.ensure(partitionCount,
                                 Verify.Is::greaterThanOrEqualTo,
                                 1,
                                 new InvalidPartitionCount(partitionCount)),
                   declaredFactorsInRange(replicationFactor, confirmationFactor)).map((name, partitions, _) -> new DurableEntityConfig(name,
                                                                                                                                       partitions,
                                                                                                                                       replicationFactor,
                                                                                                                                       confirmationFactor));
    }

    /// The declared replication factors, before any default applies.
    public ReplicationDeclaration replication() {
        return ReplicationDeclaration.replicationDeclaration(replicationFactor, confirmationFactor);
    }

    private static Result<ReplicationFactors> declaredFactorsInRange(Option<Integer> replicationFactor,
                                                                     Option<Integer> confirmationFactor) {
        var confirmation = confirmationFactor.or(1);
        var factor = replicationFactor.or(Math.max(1, confirmation));

        return ReplicationFactors.replicationFactors(factor, confirmation).mapError(EntityProvisioningError.ReplicationRefused::new);
    }

    /// Parse-don't-validate for the keyspace name: this factory is the ONE entry point every keyspace
    /// passes through, so the `/`-free invariant both downstream parsers rely on
    /// (`EntityPartitionArc.arcOf`, `EntityKeyspaceRegistrationKey.fromIdentity`) is established here,
    /// once, and everything after can rely on it instead of re-checking.
    private static Result<String> validKeyspace(String keyspace) {
        return Option.option(keyspace)
                     .filter(name -> !name.isBlank() && !name.contains("/"))
                     .toResult(new InvalidKeyspace(keyspace));
    }
}

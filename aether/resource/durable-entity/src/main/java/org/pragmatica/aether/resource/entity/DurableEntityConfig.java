// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


/// Configuration for a [DurableEntity] resource.
///
/// Minimal for the HA-only in-memory cut (plan Phase 2b): a `keyspace` naming the entity family
/// and a `replicationFactor` recording the intended durability. `replicationFactor` is carried but
/// not yet enforced — replication rides the ownership fence (#345) and durable backing (#349) in
/// later slices; it is recorded here so the config surface is stable across those slices.
///
/// @param keyspace          logical name of the entity family (e.g. `"orders"`)
/// @param replicationFactor intended replica count (recorded; enforced in a later slice)
public record DurableEntityConfig(String keyspace, int replicationFactor) {
    private static final int DEFAULT_REPLICATION_FACTOR = 3;

    /// Build a config with the default replication factor.
    ///
    /// @param keyspace logical name of the entity family
    ///
    /// @return the config
    public static Result<DurableEntityConfig> durableEntityConfig(String keyspace) {
        return durableEntityConfig(keyspace, DEFAULT_REPLICATION_FACTOR);
    }

    /// Build a config with an explicit replication factor.
    ///
    /// @param keyspace          logical name of the entity family
    /// @param replicationFactor intended replica count
    ///
    /// @return the config
    public static Result<DurableEntityConfig> durableEntityConfig(String keyspace, int replicationFactor) {
        return success(new DurableEntityConfig(keyspace, replicationFactor));
    }
}

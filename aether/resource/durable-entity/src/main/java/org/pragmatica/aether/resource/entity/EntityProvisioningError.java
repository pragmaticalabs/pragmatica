// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import org.pragmatica.lang.Cause;


/// Reasons a [DurableEntity] resource cannot be provisioned as declared — the refusal vocabulary of the
/// durable-entity module, distinct from [EntityError] because none of these is scoped to an
/// entity key: they describe a resource that never came into existence.
///
/// Every variant exists to make a previously SILENT wrong behaviour loud (#345 I1). A cause raised here
/// reaches the operator as `SliceLoadingFailure.Fatal` — `ResourceCreationFailed` for a provisioning
/// refusal, `ConfigurationFailed` for a config refusal — and lands verbatim in the cluster-event feed's
/// `DEPLOYMENT_FAILED` record, so a slice that cannot get the guarantees it declared fails to start
/// instead of starting wrong.
public sealed interface EntityProvisioningError extends Cause {
    /// The entity's `replication_factor`/`confirmation_factor` refused (#1564); `cause` is the typed
    /// [org.pragmatica.aether.slice.ReplicationFactorsError] — out of range, an RF below 3 taken from a default,
    /// an RF above the desired core count, or a policy different from the keyspace's committed one.
    record ReplicationRefused(Cause cause) implements EntityProvisioningError {
        @Override
        public String message() {
            return "Durable entity replication refused: " + cause.message();
        }
    }

    /// The node supplied no [org.pragmatica.aether.slice.ReplicationContext.Source], so the declared factors cannot be
    /// resolved against the committed cluster defaults. Refused rather than resolved against a guess (#1564).
    record ReplicationContextUnavailable(String keyspace) implements EntityProvisioningError {
        @Override
        public String message() {
            return "Durable entity '" + keyspace
                 + "' cannot resolve its replication factors: the node supplies no replication context";
        }
    }

    /// The keyspace's durable log could not be materialized, so the entity would have had nowhere to
    /// persist anything.
    ///
    /// Refused rather than degraded, on the same reasoning as [FenceUnavailable]: an entity with no log is
    /// an entity with no durability, and starting one would mean serving a resource that answers to the
    /// name "durable entity" while holding state no restart survives. The realistic cause is a cluster
    /// whose stream partition budget or ring pool is exhausted, which is an operator-actionable condition
    /// rather than a code fault — so the underlying cause is carried through verbatim.
    record LogUnavailable(String keyspace, Cause reason) implements EntityProvisioningError {
        @Override
        public String message() {
            return "Durable entity keyspace '" + keyspace
                 + "' cannot be provisioned: its durable log could not be created — " + reason.message()
                 + "; refusing rather than serving an entity that persists nothing";
        }
    }

    /// `partition_count` must be at least one — it is the divisor of the key→`(keyspace, partition)`
    /// ownership-arc mapping ([org.pragmatica.aether.dht.EntityPartitionArc]), so a non-positive value
    /// has no meaning and would fail later, deep inside a modulo.
    record InvalidPartitionCount(int requested) implements EntityProvisioningError {
        @Override
        public String message() {
            return "Durable entity partition_count = " + requested + " is invalid: must be at least 1";
        }
    }

    /// The keyspace name must be present and must not contain `/` — it is embedded in TWO parsed
    /// coordinates whose grammar reserves that character: the DHT key / ownership-arc format
    /// (`entity:<keyspace>/<partition>/<key>`, parsed back by `EntityPartitionArc.arcOf`) and the
    /// per-node registration snapshot identity (`<keyspace>/<nodeId>`, parsed back by
    /// `EntityKeyspaceRegistrationKey.fromIdentity`). A `/` in the name would not fail here-and-now: it
    /// would silently shift both parses — writes fenced against the FLOOR arc as if unowned, and a
    /// snapshot restore reassembling a different keyspace with a phantom host — which is why the rule is
    /// enforced at the one entry point every keyspace passes through, instead of trusted at each parser.
    record InvalidKeyspace(String requested) implements EntityProvisioningError {
        @Override
        public String message() {
            return "Durable entity keyspace '" + requested
                 + "' is invalid: must be non-blank and must not contain '/'"
                 + " (reserved by the entity DHT-key and registration-identity formats)";
        }
    }

    /// A collaborator the WRITE FENCE depends on was absent from the provisioning context, so the entity
    /// could only have been built unfenced.
    ///
    /// Provisioning refuses rather than falling back, per the #345 I1 owner ruling: an absent fence costs
    /// SAFETY, not freshness — it accepts writes from a deposed owner, the five-writers-for-one-key shape
    /// I0 measured. A silent fallback would reintroduce that defect behind a green build, and a future
    /// refactor that dropped a single `registerExtension` call would do it invisibly. Contrast
    /// [EntityError.LinearizableUnavailable], where the missing collaborator (the barrier) costs
    /// only freshness and is therefore refused per-READ rather than per-RESOURCE.
    record FenceUnavailable(String keyspace, String collaborator) implements EntityProvisioningError {
        @Override
        public String message() {
            return "Durable entity keyspace '" + keyspace
                 + "' cannot be provisioned: the write fence requires " + collaborator
                 + ", which this node did not register — refusing rather than serving an unfenced entity";
        }
    }
}

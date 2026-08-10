// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import org.pragmatica.lang.Cause;


/// Reasons a [DurableEntity] resource cannot be provisioned as declared — the refusal vocabulary of the
/// durable-entity module, distinct from [DurableEntityError] because none of these is scoped to an
/// entity key: they describe a resource that never came into existence.
///
/// Both variants exist to make a previously SILENT wrong behaviour loud (#345 I1). A cause raised here
/// reaches the operator as `SliceLoadingFailure.Fatal` — `ResourceCreationFailed` for a provisioning
/// refusal, `ConfigurationFailed` for a config refusal — and lands verbatim in the cluster-event feed's
/// `DEPLOYMENT_FAILED` record, so a slice that cannot get the guarantees it declared fails to start
/// instead of starting wrong.
public sealed interface DurableEntityProvisioningError extends Cause {
    /// The blueprint declared `replication_factor` greater than the one replica this cut actually keeps.
    ///
    /// Before I1 the field was accepted and silently ignored: the I0 fixture declared 3 and got a single
    /// process-local copy that died with its node. Refusing is the honest reading of the field — the
    /// entity cannot replicate anything until the fenced-log slice (plan Phase 3 / #349) gives it a
    /// backing that spans nodes, and a declaration that asks for durability it will not get is exactly
    /// the claim-vs-reality gap the arc exists to close. The field is KEPT rather than deleted so the
    /// config surface stays stable across that slice.
    record ReplicationNotSupported(int requested, int supported) implements DurableEntityProvisioningError {
        @Override
        public String message() {
            return "Durable entity replication_factor = " + requested
                 + " is not supported: this cut keeps " + supported
                 + " replica (single-replica, local-owner, HA-only) — declare " + supported
                 + ", or wait for the restart-durable fenced-log backing (#349) to honour a higher factor";
        }
    }

    /// `partition_count` must be at least one — it is the divisor of the key→`(keyspace, partition)`
    /// ownership-arc mapping ([org.pragmatica.aether.dht.EntityPartitionArc]), so a non-positive value
    /// has no meaning and would fail later, deep inside a modulo.
    record InvalidPartitionCount(int requested) implements DurableEntityProvisioningError {
        @Override
        public String message() {
            return "Durable entity partition_count = " + requested + " is invalid: must be at least 1";
        }
    }

    /// A collaborator the WRITE FENCE depends on was absent from the provisioning context, so the entity
    /// could only have been built unfenced.
    ///
    /// Provisioning refuses rather than falling back, per the #345 I1 owner ruling: an absent fence costs
    /// SAFETY, not freshness — it accepts writes from a deposed owner, the five-writers-for-one-key shape
    /// I0 measured. A silent fallback would reintroduce that defect behind a green build, and a future
    /// refactor that dropped a single `registerExtension` call would do it invisibly. Contrast
    /// [DurableEntityError.LinearizableUnavailable], where the missing collaborator (the barrier) costs
    /// only freshness and is therefore refused per-READ rather than per-RESOURCE.
    record FenceUnavailable(String keyspace, String collaborator) implements DurableEntityProvisioningError {
        @Override
        public String message() {
            return "Durable entity keyspace '" + keyspace
                 + "' cannot be provisioned: the write fence requires " + collaborator
                 + ", which this node did not register — refusing rather than serving an unfenced entity";
        }
    }
}

// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.List;


/// Narrow read-only view of the locally-known streams that the {@link ReplicaSetController}
/// needs in order to recompute desired replica sets. Deliberately decoupled from the concrete
/// `StreamPartitionManager` so the controller is unit-testable against a trivial fake.
///
/// `StreamPartitionManager` adapts to this interface (see its `replicaCatalog()` accessor); the
/// controller never depends on the manager directly.
public interface StreamCatalog {
    /// One spec per stream currently materialized on this node. `partitions` is the partition
    /// count; `replicas` is the configured app-stream replication factor (total copies incl. owner)
    /// that drives placement — ignored for system streams, whose RF is derived from cluster size.
    /// `minSyncReplicas` is the write-ack requirement (in-sync count incl. owner); it is carried here
    /// for consumers of the in-sync gate and is NOT used for placement.
    record StreamSpec(String name, int partitions, int replicas, int minSyncReplicas) {}

    List<StreamSpec> streams();
}

// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

/// #1564: the cluster-wide replication defaults of the committed cluster TOML's `[replication]` section
/// (owner ruling, know 596bdfd07(2)). `replicationFactor`/`confirmationFactor` are the defaults every stream,
/// durable topic and durable entity resolves an undeclared value from; `clusterEventsConfirmationFactor` is the
/// confirmation factor of the `system:cluster-events` stream, whose replication factor is the cluster size.
/// Plain values: `aether-config` does not depend on `slice-api`, so the consumers convert them to
/// `ReplicationFactors` at their boundary.
public record ReplicationDefaultsConfig(int replicationFactor,
                                        int confirmationFactor,
                                        int clusterEventsConfirmationFactor) {
    /// The owner's built-in defaults: RF 3, CF 2. Cluster events keep CF 1 (acked on the owner's append) until
    /// the owner decides the acked-but-lost question (#1564; guarantees.md row 14a).
    public static final ReplicationDefaultsConfig BUILT_IN = new ReplicationDefaultsConfig(3, 2, 1);
}

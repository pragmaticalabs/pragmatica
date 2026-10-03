// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import org.pragmatica.lang.io.TimeSpan;

/// #1564: the cluster-wide replication defaults of the committed cluster TOML's `[replication]` section
/// (owner ruling, know 596bdfd07(2)). `replicationFactor`/`confirmationFactor` are the defaults every stream,
/// durable topic and durable entity resolves an undeclared value from; `clusterEventsConfirmationFactor` is the
/// confirmation factor of the `system:cluster-events` stream, whose replication factor is the cluster size.
/// Plain values: `aether-config` does not depend on `slice-api`, so the consumers convert them to
/// `ReplicationFactors` at their boundary.
///
/// #1777 track 1: the DHT resolves its replication from the same `replicationFactor`/`confirmationFactor`, and the
/// cache namespace from its own `[cache]` declaration (`cacheReplicationFactor`/`cacheConfirmationFactor`).
///
/// #1777 track 3: `tombstoneRetention` is how long the DHT keeps a removed key's tombstone. Cluster-wide, because
/// every replica must agree which tombstones have expired: the anti-entropy digest leaves expired ones out.
public record ReplicationDefaultsConfig(int replicationFactor,
                                        int confirmationFactor,
                                        int clusterEventsConfirmationFactor,
                                        int cacheReplicationFactor,
                                        int cacheConfirmationFactor,
                                        TimeSpan tombstoneRetention) {
    /// The owner's built-in defaults: RF 3, CF 2. Cluster events keep CF 1 (acked on the owner's append) until
    /// the owner decides the acked-but-lost question (#1564; guarantees.md row 14a). The cache is RF 1, CF 1 (owner
    /// ruling 2026-10-03, #1777): it is recomputable.
    /// The owner's default tombstone retention (2026-10-03, #1777): one hour.
    public static final TimeSpan DEFAULT_TOMBSTONE_RETENTION = TimeSpan.timeSpan(1).hours();

    public static final ReplicationDefaultsConfig BUILT_IN = new ReplicationDefaultsConfig(3,
                                                                                           2,
                                                                                           1,
                                                                                           1,
                                                                                           1,
                                                                                           DEFAULT_TOMBSTONE_RETENTION);
}

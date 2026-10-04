// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import org.pragmatica.lang.io.TimeSpan;


/// The node-local `[dht.replication]` replication-cooldown knobs. The DHT's replication factor is NOT here: it
/// comes from the cluster's committed `[replication]` section (#1777 track 1).
public record DhtReplicationConfig(TimeSpan cooldownDelay, int cooldownRate) {
    public static final TimeSpan DEFAULT_COOLDOWN_DELAY = TimeSpan.timeSpan(10).seconds();
    public static final int DEFAULT_COOLDOWN_RATE = 10_000;

    public static DhtReplicationConfig dhtReplicationConfig(TimeSpan cooldownDelay, int cooldownRate) {
        return new DhtReplicationConfig(cooldownDelay, cooldownRate);
    }

    public static DhtReplicationConfig dhtReplicationConfig() {
        return new DhtReplicationConfig(DEFAULT_COOLDOWN_DELAY, DEFAULT_COOLDOWN_RATE);
    }
}

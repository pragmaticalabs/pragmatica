// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.health;

import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.messaging.Message;

import java.util.concurrent.atomic.AtomicLong;


public record ClusterPhaseChanged(ClusterPhase previous, ClusterPhase current, long sequence) implements Message.Local {
    private static final AtomicLong SEQUENCE = new AtomicLong();

    public static ClusterPhaseChanged clusterPhaseChanged(ClusterPhase previous, ClusterPhase current) {
        return new ClusterPhaseChanged(previous, current, SEQUENCE.incrementAndGet());
    }
}

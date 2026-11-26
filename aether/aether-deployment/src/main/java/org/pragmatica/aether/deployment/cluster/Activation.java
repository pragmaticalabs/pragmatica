// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.cluster.state.kvstore.LeaderValue;


/// What one read of [CapacityControlledLifecycle#activation] answers: the stored leader this node holds, and the leadership
/// generation (the count of lose-to-gain edges this instance has observed) that read paired with it. A leader-only external
/// effect (C1) carries one of these from submit to its continuation, and runs only while a fresh read still pairs "leader
/// present" with an equal generation.
public record Activation(LeaderValue value, long generation) {
    public static Activation activation(LeaderValue value, long generation) {
        return new Activation(value, generation);
    }
}

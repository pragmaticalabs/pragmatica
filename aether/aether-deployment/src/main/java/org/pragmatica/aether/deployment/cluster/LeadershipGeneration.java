// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;


/// The count of lose-to-gain leadership edges one [CapacityControlledLifecycle] has observed (#3754 C1), with NO accessor for the count.
/// The compiler enforces that [CapacityControlledLifecycle#activation] is the one reader: the type has no method that returns the
/// value, so `get()`, `incrementAndGet()` used as a value, and a pass-through that reads its parameter do not compile, inside the
/// lifecycle and outside the module (the methods are package-private, and the lifecycle's public component accessor hands out this type,
/// not a number). The count leaves only inside an [Activation], paired with the leader value it was taken with, by [#pairedWith].
public final class LeadershipGeneration {
    private final AtomicLong count = new AtomicLong();

    private LeadershipGeneration() {}

    public static LeadershipGeneration leadershipGeneration() {
        return new LeadershipGeneration();
    }

    /// The gain edge: the count moves by one. Answers nothing, so the new value cannot be used as a read.
    @Contract
    void gain() {
        count.incrementAndGet();
    }

    /// The one pairing: a present leader with the count taken now, or none.
    Option<Activation> pairedWith(Option<LeaderValue> current) {
        return current.map(value -> Activation.activation(value, count.get()));
    }
}

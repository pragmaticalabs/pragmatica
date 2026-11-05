// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;


/// A start refused for want of a committed target (N2, B-155): parked, never dropped, nothing written. `since` is the
/// wall time of the first refusal and survives an identical re-delivery (B-159 changed). `warned` records that
/// `slice-start-awaiting-target` was raised, so its recovery is raised only then.
public record Parked(long since, SliceGoal goal, Generation generation, boolean warned) {
    public static Parked parked(long since, SliceGoal goal, Generation generation) {
        return new Parked(since, goal, generation, false);
    }

    /// `desired` is the goal this park is for.
    public boolean matches(SliceDesiredValue desired) {
        return goal == desired.goal() && generation.equals(desired.generation());
    }

    public Parked asWarned() {
        return new Parked(since, goal, generation, true);
    }
}

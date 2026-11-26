// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.List;
import java.util.function.Supplier;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Promise;


/// The one port through which a leader-only external effect (C1) is issued. The port runs the effect itself, so no caller can
/// skip the re-read of the activation: the effect runs only in the continuation of an accepted leader transaction this node
/// committed for it.
///
/// The port always FAILS with a typed cause ([FenceFailure], or the consensus cause of the commit, for example `ApplyTimeout`);
/// it never answers `false`. The effect's own failure is passed through.
public interface FencePort {
    /// Commits a leader transaction that carries the stored leader and `guards` as its read witnesses and has an empty mutation list,
    /// then runs `effect` if the commit was accepted in time and the activation is unchanged.
    <T> Promise<T> fenced(List<KVCommand.ReadWitness<AetherKey>> guards, Supplier<Promise<T>> effect);
}

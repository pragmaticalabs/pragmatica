// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;


/// The outer state of the cluster FSM (spec 3.2).
///
/// - [Dormant]: not the leader. Every input but `Activate` is dropped (B-001).
/// - [Rebuilding]: activated, and waiting for `Rebuild`, the first mailbox item (R-3.1.6). Inputs that arrive in the
///   meantime are held and replayed, in order, after the rebuild (NT-01).
/// - [Active]: the model is built. `Active / WitnessStale` is not a separate case: it is an Active model whose
///   committed leader does not name this node ([Active#suspended]), which is how the exit on CI-16 (3.2) is decided.
public sealed interface ClusterState {
    /// The number of the last incarnation, so a fresh Active entry gets a fresh number (R-3.1.3).
    record Dormant(long lastIncarnation) implements ClusterState {}

    record Rebuilding(long incarnation, List<ClusterInput> held) implements ClusterState {
        public Rebuilding {
            held = List.copyOf(held);
        }
    }

    record Active(ClusterModel model) implements ClusterState {
        /// R-5.7.1 (a): the FSM submits only while the committed leader names itself.
        public boolean suspended(Settings settings) {
            return ! model.leader()
                          .map(value -> value.leader()
                                             .equals(settings.self()))
                          .or(false);
        }
    }

    static ClusterState initial() {
        return new Dormant(0L);
    }
}

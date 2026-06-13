// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.governor;

import org.pragmatica.consensus.NodeId;


@SuppressWarnings("JBCT-STY-04")
public sealed interface GovernorState {
    record Governor(NodeId self) implements GovernorState {
        public static Governor governor(NodeId self) {
            return new Governor(self);
        }
    }

    record Follower(NodeId governorId) implements GovernorState {
        public static Follower follower(NodeId governorId) {
            return new Follower(governorId);
        }
    }
}

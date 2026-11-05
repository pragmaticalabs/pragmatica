// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.consensus.NodeId;


/// What the node FSM knows about itself and its bounds. `process` is this process's boot identity (spec C6): the same
/// one SWIM announces, with `bootToken` taken from `BootTokens.self()` (0 = a node assembled without a token, L-17).
/// `instanceNumber` is the ACTIVE row's instance number (B-152).
public record NodeFsmConfig(NodeId self, ProcessIncarnation process, int instanceNumber, NodeFsmTimings timings) {
    public static NodeFsmConfig nodeFsmConfig(NodeId self, ProcessIncarnation process) {
        return new NodeFsmConfig(self,
                                 process,
                                 self.id().hashCode() & Integer.MAX_VALUE,
                                 NodeFsmTimings.nodeFsmTimings());
    }

    public NodeFsmConfig withTimings(NodeFsmTimings timings) {
        return new NodeFsmConfig(self, process, instanceNumber, timings);
    }

    public long selfToken() {
        return process.bootToken();
    }

    /// A row is stale on positive evidence only: both tokens are known (non-zero) and differ (spec R-1.6.2, C8).
    public boolean isStale(long rowToken) {
        var self = selfToken();

        return self != 0L
               && rowToken != 0L
               && rowToken != self;
    }
}

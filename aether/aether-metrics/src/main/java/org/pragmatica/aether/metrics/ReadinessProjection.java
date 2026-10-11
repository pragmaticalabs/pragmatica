// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.metrics;

import java.util.HashMap;
import java.util.Map;

import org.pragmatica.consensus.NodeId;


/// Read-time evidence composition. Direct node states take precedence, including DRAINING/SYNCING.
/// Neither source is cached here: their original expiry and authority checks remain authoritative.
public interface ReadinessProjection {
    static Map<NodeId, NodeReportedState> merge(Map<NodeId, NodeReportedState> community,
                                                Map<NodeId, NodeReportedState> direct) {
        var result = new HashMap<>(community);

        result.putAll(direct);

        return Map.copyOf(result);
    }
}

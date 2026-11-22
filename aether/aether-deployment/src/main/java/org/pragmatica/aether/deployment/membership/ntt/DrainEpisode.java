// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.ntt;

import org.pragmatica.consensus.NodeId;


/// One drain episode of one node. `processToken` 0 means unknown; it is compared only through [TokenCompare].
public record DrainEpisode(NodeId node, long processToken) {
    public static DrainEpisode drainEpisode(NodeId node, long processToken) {
        return new DrainEpisode(node, processToken);
    }
}

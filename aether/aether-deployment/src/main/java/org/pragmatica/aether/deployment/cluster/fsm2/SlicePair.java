// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.consensus.NodeId;


/// One instance key (spec R-2.8): the pair `SliceDesiredKey(node, artifact)` + `SliceObservedKey(node, artifact)`.
public record SlicePair(NodeId node, Artifact artifact) {
    public static SlicePair slicePair(NodeId node, Artifact artifact) {
        return new SlicePair(node, artifact);
    }

    public SliceDesiredKey desiredKey() {
        return new SliceDesiredKey(node, artifact);
    }

    public SliceObservedKey observedKey() {
        return new SliceObservedKey(node, artifact);
    }
}

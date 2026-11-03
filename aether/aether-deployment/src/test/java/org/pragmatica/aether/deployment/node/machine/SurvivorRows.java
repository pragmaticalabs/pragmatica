// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import java.util.List;

import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.lang.Option;

/// Row fixtures for the survivor pins: a committed row of this node's own process.
final class SurvivorRows {
    private SurvivorRows() {}

    static SliceObservedValue row(NodeHarness h, SliceObservedState state, long version, Generation generation) {
        return new SliceObservedValue(state,
                                      version,
                                      generation,
                                      new ProcessIncarnation(NodeHarness.BOOT_INCARNATION, NodeHarness.SELF_TOKEN),
                                      Option.none(),
                                      false,
                                      0,
                                      List.of(),
                                      h.wall);
    }
}

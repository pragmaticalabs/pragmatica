// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #3345: a drain refused because the node is already winding down treats `HELD` like `DRAINING` (the node is draining, its
/// departure push has not resolved). The cli does not depend on the metrics module that owns the enum, so it matches the wire names.
/// Mutation: drop the `HELD` match from `isDrainingState`.
class ClusterDrainCommandHeldTest {
    @Test
    void heldIsADrainingState() {
        assertThat(ClusterDrainCommand.isDrainingState("HELD")).isTrue();
    }

    @Test
    void drainingIsADrainingState() {
        assertThat(ClusterDrainCommand.isDrainingState("DRAINING")).isTrue();
    }

    @Test
    void readyAndSyncingAreNotDrainingStates() {
        assertThat(ClusterDrainCommand.isDrainingState("READY")).isFalse();
        assertThat(ClusterDrainCommand.isDrainingState("SYNCING")).isFalse();
    }
}

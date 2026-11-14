// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.metrics.artifact;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// FSM2 (deployment state machine spec 1.6.1, NT-95): the tracker counts each shown `(node, artifact)` pair once, and a pair gone is the
/// removal. Red if the tracker reads only `NodeArtifactKey` rows.
class ArtifactDeploymentTrackerObservedRowsTest {
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:orders:1.0.0").unwrap();
    private static final SliceObservedKey ON_ONE = SliceObservedKey.sliceObservedKey(NodeId.nodeId("core-1").unwrap(), ARTIFACT);
    private static final SliceObservedKey ON_TWO = SliceObservedKey.sliceObservedKey(NodeId.nodeId("core-2").unwrap(), ARTIFACT);

    private final ArtifactDeploymentTracker tracker = ArtifactDeploymentTracker.artifactDeploymentTracker();

    @Test
    void shownPairs_countOnceEach_however_oftenWritten() {
        tracker.onObservedSlice(ON_ONE, row(SliceObservedState.LOADING));
        tracker.onObservedSlice(ON_ONE, row(SliceObservedState.ACTIVE));
        tracker.onObservedSlice(ON_TWO, row(SliceObservedState.ACTIVE));

        assertThat(tracker.isDeployed(ARTIFACT)).isTrue();
        assertThat(tracker.deployedCount()).isEqualTo(1);

        tracker.onObservedSliceGone(ON_ONE);

        assertThat(tracker.isDeployed(ARTIFACT)).as("still on core-2").isTrue();

        tracker.onObservedSliceGone(ON_TWO);

        assertThat(tracker.isDeployed(ARTIFACT)).isFalse();
    }

    @Test
    void gonePairNeverShown_changesNothing() {
        tracker.onObservedSlice(ON_ONE, row(SliceObservedState.ACTIVE));
        tracker.onObservedSliceGone(ON_TWO);
        tracker.onObservedSliceGone(ON_TWO);

        assertThat(tracker.isDeployed(ARTIFACT)).isTrue();
    }

    private static SliceObservedValue row(SliceObservedState state) {
        return new SliceObservedValue(state, 1L, new Generation(1L, 1L), new ProcessIncarnation(1L, 0L), Option.none(), false, 0, List.of(), 0L);
    }
}

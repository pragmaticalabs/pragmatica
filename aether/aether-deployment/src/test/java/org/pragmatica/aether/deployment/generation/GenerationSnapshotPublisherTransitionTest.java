// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.generation.PublisherEvent.ApplyDone;
import org.pragmatica.aether.deployment.generation.PublisherEvent.LeaderGained;
import org.pragmatica.aether.deployment.generation.PublisherEvent.LeaderLost;
import org.pragmatica.aether.deployment.generation.PublisherEvent.Mark;
import org.pragmatica.aether.deployment.generation.PublisherState.Disabled;
import org.pragmatica.aether.deployment.generation.PublisherState.Idle;
import org.pragmatica.aether.deployment.generation.PublisherState.Publishing;
import org.pragmatica.aether.deployment.generation.PublisherState.PublishingDirty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.generation.GenerationSnapshotPublisher.transition;


/// Pure-function tests for the package-private static `GenerationSnapshotPublisher#transition`.
/// 16 cells: 4 states (Disabled / Idle / Publishing / PublishingDirty) × 4 events
/// (LeaderGained / LeaderLost / Mark / ApplyDone). Each test asserts one expected next state.
class GenerationSnapshotPublisherTransitionTest {

    // ---- Disabled row ----

    @Test
    void disabled_leaderGained_idle() {
        assertThat(transition(Disabled.INSTANCE, LeaderGained.INSTANCE)).isSameAs(Idle.INSTANCE);
    }

    @Test
    void disabled_leaderLost_unchanged() {
        assertThat(transition(Disabled.INSTANCE, LeaderLost.INSTANCE)).isSameAs(Disabled.INSTANCE);
    }

    @Test
    void disabled_mark_unchanged() {
        assertThat(transition(Disabled.INSTANCE, Mark.INSTANCE)).isSameAs(Disabled.INSTANCE);
    }

    @Test
    void disabled_applyDone_unchanged() {
        assertThat(transition(Disabled.INSTANCE, ApplyDone.INSTANCE)).isSameAs(Disabled.INSTANCE);
    }

    // ---- Idle row ----

    @Test
    void idle_mark_publishing() {
        assertThat(transition(Idle.INSTANCE, Mark.INSTANCE)).isSameAs(Publishing.INSTANCE);
    }

    @Test
    void idle_leaderLost_disabled() {
        assertThat(transition(Idle.INSTANCE, LeaderLost.INSTANCE)).isSameAs(Disabled.INSTANCE);
    }

    @Test
    void idle_leaderGained_unchanged() {
        assertThat(transition(Idle.INSTANCE, LeaderGained.INSTANCE)).isSameAs(Idle.INSTANCE);
    }

    @Test
    void idle_applyDone_unchanged() {
        assertThat(transition(Idle.INSTANCE, ApplyDone.INSTANCE)).isSameAs(Idle.INSTANCE);
    }

    // ---- Publishing row ----

    @Test
    void publishing_mark_publishingDirty() {
        assertThat(transition(Publishing.INSTANCE, Mark.INSTANCE)).isSameAs(PublishingDirty.INSTANCE);
    }

    @Test
    void publishing_applyDone_idle() {
        assertThat(transition(Publishing.INSTANCE, ApplyDone.INSTANCE)).isSameAs(Idle.INSTANCE);
    }

    @Test
    void publishing_leaderLost_disabled() {
        assertThat(transition(Publishing.INSTANCE, LeaderLost.INSTANCE)).isSameAs(Disabled.INSTANCE);
    }

    @Test
    void publishing_leaderGained_unchanged() {
        assertThat(transition(Publishing.INSTANCE, LeaderGained.INSTANCE)).isSameAs(Publishing.INSTANCE);
    }

    // ---- PublishingDirty row ----

    @Test
    void publishingDirty_applyDone_publishing() {
        assertThat(transition(PublishingDirty.INSTANCE, ApplyDone.INSTANCE)).isSameAs(Publishing.INSTANCE);
    }

    @Test
    void publishingDirty_leaderLost_disabled() {
        assertThat(transition(PublishingDirty.INSTANCE, LeaderLost.INSTANCE)).isSameAs(Disabled.INSTANCE);
    }

    @Test
    void publishingDirty_mark_unchanged() {
        assertThat(transition(PublishingDirty.INSTANCE, Mark.INSTANCE)).isSameAs(PublishingDirty.INSTANCE);
    }

    @Test
    void publishingDirty_leaderGained_unchanged() {
        assertThat(transition(PublishingDirty.INSTANCE, LeaderGained.INSTANCE)).isSameAs(PublishingDirty.INSTANCE);
    }
}

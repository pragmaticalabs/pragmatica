// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.state.kvstore.ReplicaFeedModel.Core;
import org.pragmatica.cluster.state.kvstore.ReplicaFeedModel.Replica;

import static org.assertj.core.api.Assertions.assertThat;

/// TW-O16 of the worker-replica-feed design (W-OBS): the interleave that made draft 2's replica rule unsafe.
///
/// A heartbeat carries the core's `nextSlot`. Draft 2 let a replica advance its `applied` position on a contiguous
/// heartbeat. If a heartbeat is enqueued between a batch's `nextSlot` bump and the offer of that batch's entry, the
/// replica receives the heartbeat first, moves `applied` past the entry's slot, and then skips the entry as already
/// applied (`slot < applied`): the batch's effects are lost with no gap signal. The rule was withdrawn, and the design
/// fixes the source (offer inside the append critical section) and the replica (a heartbeat never moves `applied`).
/// Neither exists as code at this commit, so the enabled halves run the stub-level model in `ReplicaFeedModel`: they pin
/// that the hazard is real under the withdrawn rule, and that the interleave and the rule are BOTH needed for it (the
/// controls), which is what makes the model discriminating. The real ring half is asserted by NT-O41 in `CommitRingTest` (W1); the real applier half stays here, fail-closed, until W3.
class ReplicaFeedInterleaveModelTest {
    private static final long START = 5L;
    private static final List<String> EFFECTS = List.of("put deployment");

    private static Core coreWithHeartbeatInTheWindow() {
        var core = new Core(START);

        core.bump(EFFECTS);
        core.heartbeat();
        core.offerPending();

        return core;
    }

    @Test
    void withdrawnRule_losesTheEffect_whenAHeartbeatIsEnqueuedBetweenBumpAndOffer() {
        var replica = new Replica(START, true);

        replica.receive(coreWithHeartbeatInTheWindow().delivered());

        assertThat(replica.applied()).as("TW-O16: the heartbeat moved applied past the entry's slot").isEqualTo(START + 1);
        assertThat(replica.effects()).as("TW-O16: and the entry was then skipped as already applied: the batch's effect is lost").isEmpty();
        assertThat(replica.gapSeen()).as("with no gap signal, so nothing triggers a resubscribe").isFalse();
    }

    @Test
    void withdrawnRule_losesNothing_whenTheOfferIsInsideTheAppend() {
        var core = new Core(START);
        var replica = new Replica(START, true);

        core.appendAtomically(EFFECTS);
        core.heartbeat();
        replica.receive(core.delivered());

        assertThat(replica.effects()).as("control: with the offer in the append's critical section the heartbeat follows the entry, and even the withdrawn rule is safe").containsExactlyElementsOf(EFFECTS);
    }

    @Test
    void designRule_losesNothing_evenWhenAHeartbeatIsInTheWindow() {
        var replica = new Replica(START, false);

        replica.receive(coreWithHeartbeatInTheWindow().delivered());

        assertThat(replica.effects()).as("control: if only entries move applied, the same interleave loses nothing").containsExactlyElementsOf(EFFECTS);
        assertThat(replica.applied()).isEqualTo(START + 1);
    }
}

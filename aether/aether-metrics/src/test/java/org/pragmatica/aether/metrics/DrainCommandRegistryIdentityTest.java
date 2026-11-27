// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.metrics;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;

/// #2315: a drain entry names the drain that set it, and the registry refuses a clear that names another. Every case drives the
/// registry boundary; a refused clear is a no-op, visible as the entry still standing.
class DrainCommandRegistryIdentityTest {
    private static final NodeId NODE = NodeId.nodeId("drained").unwrap();
    private static final NodeId OTHER = NodeId.nodeId("other").unwrap();

    private final AtomicLong process = new AtomicLong(0);
    private final AtomicLong incarnation = new AtomicLong(0);
    private final DrainCommandRegistry registry = boundRegistry();

    private DrainCommandRegistry boundRegistry() {
        var created = DrainCommandRegistry.drainCommandRegistry();

        created.bindSubject(DrainSubject.drainSubject(_ -> process.get(), _ -> incarnation.get()));
        return created;
    }

    private DrainId drain(NodeId node) {
        return registry.requestDrain(node).unwrap();
    }

    /// Failure input (c): the completion of an earlier drain arrives after a newer drain of the same node began.
    @Test
    void completionOfAnOlderDrain_afterANewerDrainStarted_leavesTheNewerEntry() {
        process.set(1);
        var older = drain(NODE);

        process.set(2);
        var newer = drain(NODE);
        assertThat(newer).as("a request for another process is a new drain").isNotEqualTo(older);

        registry.clearDrain(older);

        assertThat(registry.isDrainRequested(NODE)).as("the older id is refused at the boundary").isTrue();
        assertThat(registry.pinOf(NODE).unwrap().id()).isEqualTo(newer);
    }

    @Test
    void clearByTheCurrentId_endsTheDrain_andOnlyThatNodes() {
        var held = drain(NODE);

        drain(OTHER);
        registry.clearDrain(held);

        assertThat(registry.drainTargets()).containsExactly(OTHER);
    }

    @Test
    void clearByAnIdAlreadyCleared_isANoOp_onALaterDrainOfTheSameProcess() {
        var first = drain(NODE);

        registry.clearDrain(first);
        var second = drain(NODE);
        registry.clearDrain(first);

        assertThat(second).as("a drain set after a clear is a new drain, even for the same process").isNotEqualTo(first);
        assertThat(registry.isDrainRequested(NODE)).isTrue();
    }

    @Test
    void repeatedRequest_forTheSameProcess_isTheSameDrain() {
        process.set(7);

        assertThat(drain(NODE)).isEqualTo(drain(NODE));
        assertThat(registry.drainTargets()).containsExactly(NODE);
    }

    @Test
    void endedLife_clearsAnEntryPinnedAtOrBeforeIt() {
        incarnation.set(5);
        drain(NODE);

        registry.clearEndedLife(NODE, 5);

        assertThat(registry.isDrainRequested(NODE)).isFalse();
    }

    @Test
    void endedLife_refusesAnEntryPinnedAtALaterIncarnation() {
        incarnation.set(9);
        drain(NODE);

        registry.clearEndedLife(NODE, 5);

        assertThat(registry.isDrainRequested(NODE)).as("a late DEAD edge of an old life does not end a newer life's drain").isTrue();
    }

    @Test
    void priorLives_clearsAnEntryPinnedBeforeTheReadmittedIncarnation() {
        incarnation.set(5);
        drain(NODE);

        registry.clearPriorLives(NODE, 9);

        assertThat(registry.isDrainRequested(NODE)).isFalse();
    }

    @Test
    void priorLives_refusesAnEntryPinnedAtTheReadmittedIncarnation() {
        incarnation.set(9);
        drain(NODE);

        registry.clearPriorLives(NODE, 9);

        assertThat(registry.isDrainRequested(NODE)).as("a drain pinned at the new life's incarnation is the new life's").isTrue();
    }

    @Test
    void supersededProcess_clearsAnEntryPinnedToAnotherProcess() {
        process.set(1);
        drain(NODE);

        process.set(2);
        registry.clearSupersededProcess(NODE);

        assertThat(registry.isDrainRequested(NODE)).isFalse();
    }

    @Test
    void supersededProcess_keepsAnEntryOfTheSameProcess() {
        process.set(1);
        drain(NODE);

        registry.clearSupersededProcess(NODE);

        assertThat(registry.isDrainRequested(NODE)).as("a refuted or withdrawn drain of the same process is still in flight").isTrue();
    }

    @Test
    void repeatedRequest_withAnUnknownTokenOnEitherSide_isTheSameDrain() {
        process.set(0);
        var bootWindow = drain(NODE);
        process.set(7);
        assertThat(drain(NODE)).as("pinned before the token was known").isEqualTo(bootWindow);

        process.set(0);
        assertThat(drain(NODE)).as("token lost after the pin").isEqualTo(bootWindow);
    }

    @Test
    void supersededProcess_takesAnUnknownTokenAsNoEvidence() {
        process.set(0);
        drain(OTHER);
        process.set(1);
        drain(NODE);

        process.set(0);
        registry.clearSupersededProcess(NODE);
        process.set(2);
        registry.clearSupersededProcess(OTHER);

        assertThat(registry.drainTargets()).as("0 on either side is no evidence of another process").containsExactlyInAnyOrder(NODE, OTHER);
    }

    @Test
    void nullNode_isIgnored() {
        assertThat(registry.requestDrain(null).isPresent()).isFalse();
        assertThat(registry.isDrainRequested(null)).isFalse();
    }
}

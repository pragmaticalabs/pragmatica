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

    /// A released drain is a command its attempt left to the DEAD edge: the next request is a new drain, and the clear of that
    /// drain leaves the released command standing.
    @Test
    void releasedDrain_isNeverJoined_andSurvivesTheClearOfTheNextDrain() {
        var released = drain(NODE);
        registry.release(released);

        var next = drain(NODE);
        assertThat(next).as("a released command is not joined").isNotEqualTo(released);

        registry.clearDrain(next);

        assertThat(registry.isDrainRequested(NODE)).as("the released command stands until the DEAD edge").isTrue();
        assertThat(registry.drainTargets()).containsExactly(NODE);
    }

    @Test
    void releasedDrain_endsAtTheEdgeClears() {
        incarnation.set(5);
        registry.release(drain(NODE));

        registry.clearPriorLives(NODE, 5);
        assertThat(registry.isDrainRequested(NODE)).as("a drain of the new life's incarnation is not a prior life's").isTrue();

        registry.clearEndedLife(NODE, 5);

        assertThat(registry.isDrainRequested(NODE)).as("the DEAD edge of its life ends it").isFalse();
    }

    @Test
    void release_byAnIdThatIsNotLive_isANoOp() {
        var live = drain(NODE);

        registry.release(DrainId.drainId(NODE, 999L));

        assertThat(drain(NODE)).as("the live drain is still joined").isEqualTo(live);
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
    void repeatedRequest_withAnUnknownTokenOnEitherSide_isTheSameDrain() {
        process.set(0);
        var bootWindow = drain(NODE);
        process.set(7);
        assertThat(drain(NODE)).as("pinned before the token was known").isEqualTo(bootWindow);

        process.set(0);
        assertThat(drain(NODE)).as("token lost after the pin").isEqualTo(bootWindow);
    }

    /// The boot window: before a subject is bound every pin carries process `0` and incarnation `0`, which no edge takes as
    /// evidence of another life. Kills `DrainSubject.unknown()` answering `1` for either.
    @Test
    void unboundRegistry_pinsProcessAndIncarnationZero() {
        var unbound = DrainCommandRegistry.drainCommandRegistry();

        unbound.requestDrain(NODE);

        var pin = unbound.pinOf(NODE).unwrap();
        assertThat(pin.process()).as("an unknown process is 0, never evidence").isZero();
        assertThat(pin.incarnation()).as("an unknown incarnation is 0").isZero();
    }

    /// A drain pinned in the boot window is of incarnation `0`, so the end of any life (incarnation `0` or later) clears it. An
    /// unknown incarnation pinned as `1` would be refused by an edge naming `0`, and the entry would leak.
    @Test
    void unboundRegistry_entryIsClearedByTheEndOfALifeAtIncarnationZero() {
        var unbound = DrainCommandRegistry.drainCommandRegistry();

        unbound.requestDrain(NODE);
        unbound.clearEndedLife(NODE, 0);

        assertThat(unbound.isDrainRequested(NODE)).isFalse();
    }

    @Test
    void nullNode_isIgnored() {
        assertThat(registry.requestDrain(null).isPresent()).isFalse();
        assertThat(registry.isDrainRequested(null)).isFalse();
    }
}

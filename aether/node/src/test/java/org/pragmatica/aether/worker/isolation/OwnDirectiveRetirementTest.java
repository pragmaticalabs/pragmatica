// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.isolation;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.pragmatica.aether.worker.isolation.OwnDirectiveRetirement.ownDirectiveRetirement;

/// #3193 ruling item 2, the mechanism in isolation: every condition of the class header, both arms. The draining
/// probe and the halt are fakes, so each arm names the one input that decides it. The store is not an input (#3330):
/// `OwnDirectiveWiringTest.ownDirectiveRemove_storeAlreadyHoldsALaterDirective_stillHalts` pins that over a real store.
class OwnDirectiveRetirementTest {
    private static final NodeId SELF = NodeId.nodeId("worker-self").expect("valid id");
    private static final NodeId OTHER = NodeId.nodeId("worker-other").expect("valid id");
    private static final ActivationDirectiveValue WORKER_DIRECTIVE = new ActivationDirectiveValue(ActivationDirectiveValue.WORKER, "", "");
    private static final ActivationDirectiveValue CORE_DIRECTIVE = new ActivationDirectiveValue(ActivationDirectiveValue.CORE, "", "");

    private static final String COMMUNITY_A = "community-a";
    private static final String COMMUNITY_B = "community-b";
    private static final String COMMUNITY_C = "community-c";

    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicInteger halts = new AtomicInteger();
    private final List<OperatorWarning> warnings = new CopyOnWriteArrayList<>();
    private final OperatorWarningSink sink = OperatorWarningSink.handingOffTo(warnings::add);

    private OwnDirectiveRetirement observer(boolean configuredWorker) {
        return ownDirectiveRetirement(SELF, configuredWorker, draining::get, halts::incrementAndGet, sink);
    }

    @Test
    void onRemove_directedWorkerOwnKeyNoReplacement_haltsOnceAndWarnsOnceNamingTheNode() {
        var observer = observer(true);

        observer.onPut(put(SELF, WORKER_DIRECTIVE));
        observer.onRemove(remove(SELF));

        assertEquals(1, halts.get());
        assertTrue(observer.halted());
        assertEquals(List.of(OperatorWarningCode.OWN_DIRECTIVE_RETIRED), ownWarningCodes());
        assertEquals(SELF.id(), ownWarnings().getFirst().subject());
    }

    /// #3327, restated on the #3330 contract: a removal followed by a directive committed in a LATER transaction is a
    /// retirement then a fresh assignment, never a move. The worker halts on the removal, and a directive delivered right
    /// after neither cancels the halt nor re-arms the observer.
    @Test
    void onRemove_directiveDeliveredRightAfter_stillHaltsOnce() {
        var observer = observer(true);

        observer.onPut(put(SELF, WORKER_DIRECTIVE));
        observer.onRemove(remove(SELF));
        observer.onPut(put(SELF, new ActivationDirectiveValue(ActivationDirectiveValue.WORKER, "community-2", "")));
        observer.onRemove(remove(SELF));

        assertEquals(1, halts.get());
        assertTrue(observer.halted());
        assertEquals(List.of(OperatorWarningCode.OWN_DIRECTIVE_RETIRED), ownWarningCodes());
    }

    /// A put naming the SAME community is a repeat even when another field changed: "same" compares `communityId` only.
    @Test
    void onPut_sameCommunityChangedValue_isARepeat_andTheNextRemovalStillHalts() {
        var observer = observer(true);

        observer.onPut(put(SELF, directive(COMMUNITY_A)));
        observer.onPut(put(SELF, new ActivationDirectiveValue(ActivationDirectiveValue.WORKER, COMMUNITY_A, "governor-hint")));

        assertEquals(0, halts.get());
        assertFalse(observer.halted());

        observer.onRemove(remove(SELF));

        assertEquals(1, halts.get());
    }

    @Test
    void onPut_directedCommunityAgain_doesNotHalt() {
        var observer = observer(true);

        observer.onPut(put(SELF, directive(COMMUNITY_A)));
        observer.onPut(put(SELF, directive(COMMUNITY_A)));

        assertEquals(0, halts.get());
        assertFalse(observer.halted());
        assertEquals(List.of(), ownWarningCodes());
    }

    /// #3330 put edge: a removal and a re-create committed between two deliveries arrive as ONE put naming another
    /// community. That is a move, which a worker never makes in place, so it halts like the removal, exactly once.
    @Test
    void onPut_anotherCommunityAfterDirected_haltsOnce_andAThirdCommunityStillOnce() {
        var observer = observer(true);

        observer.onPut(put(SELF, directive(COMMUNITY_A)));
        observer.onPut(put(SELF, directive(COMMUNITY_B)));

        assertEquals(1, halts.get());
        assertTrue(observer.halted());

        observer.onPut(put(SELF, directive(COMMUNITY_C)));

        assertEquals(1, halts.get());
        assertEquals(List.of(OperatorWarningCode.OWN_DIRECTIVE_RETIRED), ownWarningCodes());
        assertEquals(SELF.id(), ownWarnings().getFirst().subject());
    }

    /// The recorded community is the FIRST directed one and is never overwritten: a put for B suppressed by a running
    /// drain does not make B the reference, so a later put for B (drain no longer running) is still a move from A.
    @Test
    void onPut_recordedCommunityIsNeverOverwritten_soALaterPutForTheSameOtherCommunityStillHalts() {
        var observer = observer(true);

        observer.onPut(put(SELF, directive(COMMUNITY_A)));
        draining.set(true);
        observer.onPut(put(SELF, directive(COMMUNITY_B)));

        assertEquals(0, halts.get());

        draining.set(false);
        observer.onPut(put(SELF, directive(COMMUNITY_B)));

        assertEquals(1, halts.get());
    }

    @Test
    void onPut_anotherCommunityWhileDraining_startsNoSecondDrainAndRaisesNothing() {
        var observer = observer(true);

        observer.onPut(put(SELF, directive(COMMUNITY_A)));
        draining.set(true);
        observer.onPut(put(SELF, directive(COMMUNITY_B)));

        assertEquals(0, halts.get());
        assertFalse(observer.halted());
        assertEquals(List.of(), ownWarningCodes());
    }

    @Test
    void onPut_anotherNodesDirectiveForAnotherCommunity_doesNotHalt() {
        var observer = observer(true);

        observer.onPut(put(SELF, directive(COMMUNITY_A)));
        observer.onPut(put(OTHER, directive(COMMUNITY_B)));

        assertEquals(0, halts.get());
        assertFalse(observer.halted());
    }

    @Test
    void onRemove_anotherNodesDirective_doesNotHalt() {
        var observer = observer(true);

        observer.onPut(put(SELF, WORKER_DIRECTIVE));
        observer.onRemove(remove(OTHER));

        assertEquals(0, halts.get());
        assertEquals(List.of(), ownWarningCodes());
    }

    @Test
    void onRemove_anotherNodesPutDoesNotArm_soOwnRemovalDoesNothing() {
        var observer = observer(true);

        observer.onPut(put(OTHER, WORKER_DIRECTIVE));
        observer.onRemove(remove(SELF));

        assertEquals(0, halts.get());
    }

    @Test
    void onRemove_neverDirectedWorker_doesNotHalt() {
        var observer = observer(true);

        observer.onRemove(remove(SELF));

        assertEquals(0, halts.get());
        assertEquals(List.of(), ownWarningCodes());
    }

    @Test
    void onRemove_ownDirectiveWithCoreRole_doesNotArm() {
        var observer = observer(true);

        observer.onPut(put(SELF, CORE_DIRECTIVE));
        observer.onRemove(remove(SELF));

        assertEquals(0, halts.get());
    }

    @Test
    void onRemove_alreadyDraining_startsNoSecondDrainAndRaisesNothing() {
        var observer = observer(true);

        observer.onPut(put(SELF, WORKER_DIRECTIVE));
        draining.set(true);
        observer.onRemove(remove(SELF));

        assertEquals(0, halts.get());
        assertFalse(observer.halted());
        assertEquals(List.of(), ownWarningCodes());
    }

    @Test
    void onRemove_repeatedRemoval_haltsAtMostOnce() {
        var observer = observer(true);

        observer.onPut(put(SELF, WORKER_DIRECTIVE));
        observer.onRemove(remove(SELF));
        observer.onRemove(remove(SELF));
        observer.onRemove(remove(SELF));

        assertEquals(1, halts.get());
        assertEquals(1, ownWarnings().size());
    }

    /// A CORE node's directive is never retired by C17b (`WorkerDepartureRetirementTest.coreDeparture_keepsTheDirective`),
    /// and `configuredWorker(config)` is false for it, so the observer is never armed.
    @Test
    void onRemove_nodeNotConfiguredAsWorker_neverHalts() {
        var observer = observer(false);

        observer.onPut(put(SELF, WORKER_DIRECTIVE));
        observer.onRemove(remove(SELF));

        assertEquals(0, halts.get());
        assertEquals(List.of(), ownWarningCodes());
    }

    private static ValuePut<ActivationDirectiveKey, ActivationDirectiveValue> put(NodeId node, ActivationDirectiveValue value) {
        return new ValuePut<>(new KVCommand.Put<>(ActivationDirectiveKey.activationDirectiveKey(node), value), Option.none());
    }

    private static ActivationDirectiveValue directive(String communityId) {
        return new ActivationDirectiveValue(ActivationDirectiveValue.WORKER, communityId, "");
    }

    private static ValueRemove<ActivationDirectiveKey, ActivationDirectiveValue> remove(NodeId node) {
        return new ValueRemove<>(new KVCommand.Remove<>(ActivationDirectiveKey.activationDirectiveKey(node)), Option.none());
    }

    private List<OperatorWarningCode> ownWarningCodes() {
        return ownWarnings().stream()
                            .map(OperatorWarning::code)
                            .toList();
    }

    /// The sink hands off on its own single thread, in order: a sentinel accepted after the scenario arrives after every
    /// warning the scenario raised, so a "nothing raised" arm reads a complete list, not a racing one.
    private List<OperatorWarning> ownWarnings() {
        sink.accept(OperatorWarning.operatorWarning(OperatorWarningCode.CORE_ABSENCE_FENCE, "sentinel", "sentinel"));

        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while (warnings.stream()
                       .noneMatch(w -> "sentinel".equals(w.subject())) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        return warnings.stream()
                       .filter(w -> w.code() == OperatorWarningCode.OWN_DIRECTIVE_RETIRED)
                       .toList();
    }
}

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
import java.util.concurrent.atomic.AtomicReference;

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

/// #3193 ruling item 2, the mechanism in isolation: every condition of the class header, both arms. The store read,
/// the draining probe and the halt are fakes, so each arm names the one input that decides it.
class OwnDirectiveRetirementTest {
    private static final NodeId SELF = NodeId.nodeId("worker-self").expect("valid id");
    private static final NodeId OTHER = NodeId.nodeId("worker-other").expect("valid id");
    private static final ActivationDirectiveValue WORKER_DIRECTIVE = new ActivationDirectiveValue(ActivationDirectiveValue.WORKER, "", "");
    private static final ActivationDirectiveValue CORE_DIRECTIVE = new ActivationDirectiveValue(ActivationDirectiveValue.CORE, "", "");

    private final AtomicReference<Option<ActivationDirectiveValue>> store = new AtomicReference<>(Option.none());
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicInteger halts = new AtomicInteger();
    private final List<OperatorWarning> warnings = new CopyOnWriteArrayList<>();
    private final OperatorWarningSink sink = OperatorWarningSink.handingOffTo(warnings::add);

    private OwnDirectiveRetirement observer(boolean configuredWorker) {
        return ownDirectiveRetirement(SELF, configuredWorker, store::get, draining::get, halts::incrementAndGet, sink);
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

    @Test
    void onRemove_replacementPresentInStore_doesNotHalt() {
        var observer = observer(true);

        observer.onPut(put(SELF, WORKER_DIRECTIVE));
        store.set(Option.some(WORKER_DIRECTIVE));
        observer.onRemove(remove(SELF));

        assertEquals(0, halts.get());
        assertFalse(observer.halted());
        assertEquals(List.of(), ownWarningCodes());
    }

    @Test
    void onPut_rewriteOfOwnDirective_neverHalts_andTheNextRemovalStillDoes() {
        var observer = observer(true);

        observer.onPut(put(SELF, WORKER_DIRECTIVE));
        observer.onPut(put(SELF, new ActivationDirectiveValue(ActivationDirectiveValue.WORKER, "community-2", "")));

        assertEquals(0, halts.get());

        observer.onRemove(remove(SELF));

        assertEquals(1, halts.get());
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

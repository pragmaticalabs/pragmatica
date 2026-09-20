// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deadsurface;

import java.util.List;
import java.util.function.Consumer;

import org.pragmatica.aether.node.stream.StreamConsumerManager;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;


/// #1271: pins the PRODUCTION WIRING of the consumer abandon on quorum loss, with the same bytecode
/// reachability scanner [InvokerAccessibilityFilterWiringTest] uses for #275.
///
/// `AetherNode` re-sets the quorum-loss listener AFTER the consumer manager exists, replacing the drain chain
/// with `StreamConsumerManager.abandoningOnQuorumLoss(chain, manager)` — the chain plus the abandon. That
/// factory has exactly one production caller, that line. Delete it and every `aether/node` test stays green:
/// none of them boots a node, and the manager-level pin (`abandoningOnQuorumLoss_abandonsConsumersBeforeTheDrainChain`)
/// calls the factory itself. Then a node that loses quorum keeps delivering until the drain's halt, seconds
/// after the majority was free to reassign its partitions.
///
/// What this asserts and what it does NOT: that production code CALLS the factory. It cannot see that the
/// result reaches `setQuorumLossListener` — the scanner sees call edges, not arguments. Adopted from the
/// s24 rev1335 review probe.
class QuorumLossAbandonWiringTest {
    private static final List<java.nio.file.Path> PRODUCTION_ROOTS = ReactorRoots.productionRoots();

    /// Without this, a red here is ambiguous between "the production wiring was deleted" (the defect this
    /// test exists to catch) and "the node module was simply not compiled in this working copy". Mirrors
    /// [InvokerAccessibilityFilterWiringTest]'s precondition, and is fail-safe the same way: an uncompiled
    /// module can only make a method look LESS reachable.
    private static void assertCorpusIsComplete() {
        var missing = ReactorRoots.missingProductionOutput();

        assertTrue(missing.isEmpty(),
                   "Corpus incomplete: these module(s) have src/main/java but no target/classes, so a "
                  + "call site living there would read as unreachable and fail this assertion for the "
                  + "wrong reason: " + missing
                  + ". Run a full reactor build "
                  + "(`mvn -pl aether install -DskipTests`) before trusting this gate's result.");
    }

    /// Pinned call site: `AetherNode.assembleNode` ->
    /// `quorumLossDetector.setQuorumLossListener(StreamConsumerManager.abandoningOnQuorumLoss(quorumLossChain, streamConsumerManager))`.
    @Test
    void abandoningOnQuorumLoss_isCalledByProductionCode() {
        assertCorpusIsComplete();
        var reachability = BytecodeReachability.scan(PRODUCTION_ROOTS);
        var factory = Result.lift(() -> StreamConsumerManager.class.getDeclaredMethod("abandoningOnQuorumLoss",
                                                                                      Consumer.class,
                                                                                      StreamConsumerManager.class));

        assertTrue(factory.isSuccess(), "the pinned factory no longer exists with this signature: " + factory);
        assertTrue(factory.map(MethodRef::of).map(reachability::isReachable).or(false),
                   "#1271: StreamConsumerManager.abandoningOnQuorumLoss(Consumer, StreamConsumerManager) must be "
                  + "called by production code (AetherNode, bound to quorumLossDetector.setQuorumLossListener). "
                  + "Unreachable here means a node that loses quorum keeps delivering until the drain's halt -- "
                  + "while every aether/node test stays green, because none of them boots a node");
    }
}

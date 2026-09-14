// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deadsurface;

import java.util.List;

import org.pragmatica.aether.http.forward.AccessibilityFilter;
import org.pragmatica.aether.invoke.SliceInvoker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;


/// #275: pins the PRODUCTION WIRING of the slice invoker's membership-liveness filter, with the same
/// bytecode reachability scanner [AlertingWiringLivenessTest] uses for #957.
///
/// The defect class is identical to #957's and is the reason this test is structural rather than
/// behavioural. `SliceInvoker.setAccessibilityFilter` is a `default` NO-OP on the interface, overridden by
/// the production invoker, and the invoker's own field defaults to `AccessibilityFilter.IDENTITY` — accept
/// everything. So an invoker that was never wired and one that was wired are **observationally identical
/// until a node actually dies**, which no unit test in `aether/node` induces. Deleting the single call site
/// left all 1449 `aether/node` tests green when this was measured on 2026-09-14; the four invoker-level pins
/// in `SliceInvokerLivenessFilterTest` and `SliceInvokerRoutingLivenessFilterTest` set the filter on their
/// own fixture, so none of them can see the production line either.
///
/// What this asserts and what it does NOT: it asserts that production code CALLS
/// `setAccessibilityFilter(AccessibilityFilter)`. It does not and cannot assert that the filter handed over
/// is the same instance the HTTP forward path consults, nor that its body is correct — the scanner sees call
/// edges, not arguments. Those remain unpinned.
///
/// The assertion names the single production call site it pins, so a reader can check the instrument by
/// deleting that line and watching this redden — which is how it was validated rather than by reading.
class InvokerAccessibilityFilterWiringTest {
    private static final List<java.nio.file.Path> PRODUCTION_ROOTS = ReactorRoots.productionRoots();

    /// Without this, a red here is ambiguous between "the production wiring was deleted" (the defect this
    /// test exists to catch) and "the node module was simply not compiled in this working copy". Mirrors
    /// [AlertingWiringLivenessTest]'s precondition, and is fail-safe the same way: an uncompiled module can
    /// only make a method look LESS reachable.
    private static void assertCorpusIsComplete() {
        var missing = ReactorRoots.missingProductionOutput();

        assertTrue(missing.isEmpty(),
                   "Corpus incomplete: these module(s) have src/main/java but no target/classes, so a "
                  + "call site living there would read as unreachable and fail this assertion for the "
                  + "wrong reason: " + missing
                  + ". Run a full reactor build "
                  + "(`mvn -pl aether install -DskipTests`) before trusting this gate's result.");
    }

    /// Pinned call site: `AetherNode.assembleNode` -> `sliceInvoker.setAccessibilityFilter(accessibilityFilter)`.
    ///
    /// The target is the method as declared on the `SliceInvoker` INTERFACE, which is also the static owner
    /// at the call site (`AetherNode` holds the factory's `SliceInvoker` return type). That keeps this clear
    /// of the scanner's documented polymorphic-dispatch limitation, which would bite if the implementation's
    /// override were targeted instead.
    @Test
    void sliceInvokerAccessibilityFilterIsWiredByProductionCode() throws Exception {
        assertCorpusIsComplete();
        var reachability = BytecodeReachability.scan(PRODUCTION_ROOTS);

        assertTrue(reachability.isReachable(MethodRef.of(SliceInvoker.class.getDeclaredMethod("setAccessibilityFilter",
                                                                                              AccessibilityFilter.class))),
                   "#275: SliceInvoker.setAccessibilityFilter(AccessibilityFilter) must be called by production "
                  + "code (AetherNode.assembleNode). Unreachable here means the invoker keeps its default "
                  + "AccessibilityFilter.IDENTITY, every slice-to-slice call is round-robined onto co-confirmed-"
                  + "DEAD nodes again, and it hangs for the invoker timeout -- while every aether/node test and "
                  + "every invoker-level liveness test stays green, because they set the filter themselves");
    }
}

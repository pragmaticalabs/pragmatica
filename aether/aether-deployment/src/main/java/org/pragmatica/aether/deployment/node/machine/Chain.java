// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The live chain of one instance (spec R-3.4.2: one chain per key): the steps still to run, the generation the chain
/// executes, the local step now running with its token, and what finished steps contributed to the next batch.
/// `id` names the chain for its overall bound; `stepToken` (the spec's `opToken`) names the running step, so a step
/// that ends after the chain failed carries a dead token and is dropped (B-142).
public record Chain(long id,
                    ChainKind kind,
                    Generation generation,
                    List<Step> steps,
                    long stepToken,
                    Option<LocalStep> running,
                    List<LegCommand> collected,
                    List<String> methods,
                    List<LocalStep> built) {
    public Chain {
        steps = List.copyOf(steps);
        collected = List.copyOf(collected);
        methods = List.copyOf(methods);
        built = List.copyOf(built);
    }

    /// What the chain is for. Decides the overall bound and the ACQUIRE filter.
    public enum ChainKind {
        /// N1: load.
        LOAD,
        /// N4: activate a loaded slice.
        ACTIVATE,
        /// N4b, N11: load, then activate.
        REDEPLOY,
        /// R-3.4.7: re-register and write ACTIVE after a quorum cycle, with no ACQUIRE.
        REACTIVATE,
        /// R-6.0 step 5: rebuild the lost ACTIVE write from the current mirror.
        REDRIVE_ACTIVE,
        /// N12, N13: back to LOADED.
        DEACTIVATE,
        /// N16: unload.
        UNLOAD,
        /// A single write: N0's overwrite, N12's LOADED, a lost write rebuilt.
        SINGLE_WRITE;
        /// Chains that activate bound themselves at the chain bound (B-169).
        public boolean isBounded() {
            return this == ACTIVATE || this == REDEPLOY || this == REACTIVATE || this == REDRIVE_ACTIVE;
        }
        /// Chains that register things that a failure must unregister (N6).
        public boolean registers() {
            return isBounded();
        }
    }

    /// One step: a version-fenced observed write, or a local step.
    public sealed interface Step {
        /// "W s" (spec R-3.4.3): the observed Put of `state`, with whatever earlier steps collected.
        record Write(SliceObservedState state) implements Step {}

        /// A local step.
        record Local(LocalStep step) implements Step {}
    }

    public static Step write(SliceObservedState state) {
        return new Step.Write(state);
    }

    public static Step local(LocalStep step) {
        return new Step.Local(step);
    }

    public static Chain chain(long id, ChainKind kind, Generation generation, List<Step> steps) {
        return new Chain(id, kind, generation, steps, 0L, none(), List.of(), List.of(), List.of());
    }

    /// `step` is the local step running now.
    public boolean isRunning(LocalStep step) {
        return running.filter(current -> current == step)
                      .isPresent();
    }

    public boolean hasSteps() {
        return ! steps.isEmpty();
    }

    public Step head() {
        return steps.getFirst();
    }

    /// The chain with its head taken off and no step running.
    public Chain withoutHead() {
        return new Chain(id,
                         kind,
                         generation,
                         steps.subList(1, steps.size()),
                         0L,
                         none(),
                         collected,
                         methods,
                         built);
    }

    /// The chain with its head taken off and `step` running under `token`.
    public Chain started(LocalStep step, long token) {
        return new Chain(id,
                         kind,
                         generation,
                         steps.subList(1, steps.size()),
                         token,
                         some(step),
                         collected,
                         methods,
                         built);
    }

    /// What `step` produced is added to what the next write carries. A builder step is remembered, so a write probe can run
    /// it again (spec R-3.4.4, O1).
    public Chain collecting(LocalStep step, List<LegCommand> commands, List<String> described) {
        var more = new ArrayList<>(built);

        if (isBuilder(step)) {
            more.add(step);
        }

        return collecting(commands, described).withBuilt(more);
    }

    private static boolean isBuilder(LocalStep step) {
        return step == LocalStep.BUILD_LEGS || step == LocalStep.DESCRIBE_ACTIVE || step == LocalStep.PREPARE_TEARDOWN;
    }

    private Chain withBuilt(List<LocalStep> next) {
        return new Chain(id, kind, generation, steps, stepToken, running, collected, methods, next);
    }

    public Chain collecting(List<LegCommand> commands, List<String> described) {
        var all = new ArrayList<>(collected);

        all.addAll(commands);

        return new Chain(id,
                         kind,
                         generation,
                         steps,
                         0L,
                         none(),
                         all,
                         described.isEmpty()
                         ? methods
                         : described,
                         built);
    }

    public Chain flushed() {
        return new Chain(id, kind, generation, steps, stepToken, running, List.of(), methods, List.of());
    }
}

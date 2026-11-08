// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.ARTIFACT;
import static org.pragmatica.aether.deployment.node.machine.NodeHarness.G1;

/// FSM2 F2b, node carry-forward rows of spec §8.5 pinned on the pure node FSM over the harness's real `KVStore` applier.
class NodeFsmCarryForwardTest {
    private static SliceObservedValue row(NodeHarness h) {
        return h.observed(ARTIFACT).or((SliceObservedValue) null);
    }

    /// A chain bound shorter than the ACTIVATING check, so the bound fires while the ACTIVE write is still in flight.
    private static NodeFsmTimings shortChain() {
        var d = NodeFsmTimings.nodeFsmTimings();

        return new NodeFsmTimings(d.loadMs(),
                                  d.activateMs(),
                                  d.deactivateMs(),
                                  d.unloadMs(),
                                  40_000L,
                                  d.activatingCheckMs() * 10L,
                                  d.tombstoneGraceMs(),
                                  d.parkedBoundMs(),
                                  d.writeAttemptMs(),
                                  d.writeAttempts(),
                                  d.failedRetryMs(),
                                  d.failedCycles());
    }

    // -- R-CF.7 (D7, NT-145) -------------------------------------------------------------------------------------------------------
    @Nested
    class ChainTimeout {
        /// Red when: the late ACTIVE batch of a chain that already failed lands over its FAILED row (legacy D7: the orphaned chain's ACTIVE
        /// committed after FAILED). Mechanism: the FAILED row waits for the in-flight write and takes its version, so the late batch's
        /// version-fenced observed Put expects a version the key no longer has and is refused.
        @Test
        @DisplayName("NT-145 R-CF.7: the late ACTIVE batch of a timed-out chain is refused by the version fence")
        void nt145_lateActiveBatchOfATimedOutChain_isRefused_theRowStaysFailed() {
            var h = new NodeHarness(NodeHarness.SELF_TOKEN, shortChain());

            h.loseWritesOf(SliceObservedState.ACTIVE).establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.ACTIVE, G1);
            h.advance(h.timings.chainBoundMs());
            h.advance(h.timings.writeAttempts() * h.timings.writeAttemptMs());

            assertThat(row(h).state()).as("control: the chain failed and FAILED committed").isEqualTo(SliceObservedState.FAILED);

            var failedAt = row(h).version();
            var late = h.submitsOf(SliceObservedState.ACTIVE).getFirst();

            assertThat(NodeHarness.putOf(late).map(SliceObservedValue::version).or(0L)).as("the late batch names the version FAILED took")
                                                                                        .isEqualTo(failedAt);
            h.clearEffects();
            h.applyLate(late);

            assertThat(row(h).state()).isEqualTo(SliceObservedState.FAILED);
            assertThat(row(h).version()).isEqualTo(failedAt);
            assertThat(h.effectsOf(NodeFsmEffect.Submit.class)).as("the late settlement writes nothing").isEmpty();
        }
    }

    // -- R-CF.20 (#2163, NT-153) ---------------------------------------------------------------------------------------------------
    /// The LOADING arm is `NodeFsmWriteTest.Retry.nt68_threeAttempts_sameVersion_typedFinalCause`; these are the LOADED arm, after the load
    /// itself succeeded, by exhaustion and by consensus-reported failures.
    @Nested
    class LoadChainWriteFailure {
        /// Red when: an exhausted LOADED write is classified PERMANENT (a fatal FAILED condemns a healthy artifact, #2163).
        @Test
        @DisplayName("NT-153 R-CF.20: an exhausted LOADED write is retryable, not a verdict on the artifact")
        void nt153_exhaustedLoadedWrite_isRetryable_notFatal() {
            var h = new NodeHarness();

            h.loseWritesOf(SliceObservedState.LOADED).establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.LOADED, G1);
            h.advance(h.timings.writeAttempts() * h.timings.writeAttemptMs());

            assertThat(h.localSteps()).as("control: the load itself ran and succeeded").contains(LocalStep.LOAD);
            assertThat(row(h).state()).isEqualTo(SliceObservedState.FAILED);
            assertThat(row(h).fatal()).isFalse();
            assertThat(row(h).failureReason().or("")).contains("was not confirmed after");
        }

        /// Red when: a LOADED write that consensus refused on every attempt is classified PERMANENT.
        @Test
        @DisplayName("NT-153 R-CF.20: a LOADED write refused by consensus is retryable, not a verdict on the artifact")
        void nt153_loadedWriteRefusedByConsensus_isRetryable_notFatal() {
            var h = new NodeHarness();

            h.writeScript = submitted -> NodeHarness.putOf(submitted.submit())
                                                    .map(put -> put.state() == SliceObservedState.LOADED
                                                                ? NodeHarness.WriteMode.FAIL
                                                                : NodeHarness.WriteMode.APPLY)
                                                    .or(NodeHarness.WriteMode.APPLY);
            h.establish().permit(ARTIFACT).goal(ARTIFACT, SliceGoal.LOADED, G1);
            h.advance(h.timings.writeAttempts() * h.timings.writeAttemptMs());

            assertThat(row(h).state()).isEqualTo(SliceObservedState.FAILED);
            assertThat(row(h).fatal()).isFalse();
        }

        /// The control: a load that fails on the artifact itself is still PERMANENT (B-121), so the arms above are not vacuous.
        @Test
        @DisplayName("NT-153 control: a failed LOAD step is fatal")
        void nt153_control_aFailedLoadStep_isFatal() {
            var h = new NodeHarness();

            h.script(LocalStep.LOAD, LocalOutcome.failed(Causes.cause("corrupt jar")))
             .establish()
             .permit(ARTIFACT)
             .goal(ARTIFACT, SliceGoal.LOADED, G1);

            assertThat(row(h).state()).isEqualTo(SliceObservedState.FAILED);
            assertThat(row(h).fatal()).isTrue();
        }
    }
}

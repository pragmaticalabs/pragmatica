// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster.init;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #1019 — this replaces `TopologyDeriverTest`, and the replacement is not a rename.
///
/// The deriver took ONE number and invented a split; its tests asserted the inventions, and two of
/// them asserted the DEFECT: `derive_five_returnsThreeCoreTwoWorker` pinned "asking for 5 nodes
/// yields a three-member consensus tier", and the 7/10/11/100 cases pinned the core cap at 5 — the
/// cap that made a 7- or 9-member consensus cluster inexpressible. Those assertions were wrong about
/// what the code should do, not merely stale. With both tiers stated explicitly there is nothing to
/// derive, so the type is gone and what remains to pin is the authoring POLICY enforced here.
///
/// This is the ONLY place the supported minimum of 5 is enforced. `ConfigValidator` and
/// `ClusterSizeGate` keep a STRUCTURAL floor of 3 because they run on every node boot.
class CoreWorkerSplitTest {

    @Nested
    class CoreBelowMinimum {

        /// Three was the supported minimum before the 2026-09-12 ruling. It is the discriminating
        /// value: a case at 2 would be refused by the old floor and the new one alike.
        @Test
        void coreWorkerSplit_three_isRefused() {
            CoreWorkerSplit.coreWorkerSplit(3, 0)
                           .onSuccess(s -> fail("Expected failure but got " + s))
                           .onFailure(cause -> assertThat(cause).isInstanceOf(ClusterInitError.TooFewCoreNodes.class));
        }

        @Test
        void coreWorkerSplit_four_isRefused() {
            CoreWorkerSplit.coreWorkerSplit(4, 0)
                           .onSuccess(s -> fail("Expected failure but got " + s))
                           .onFailure(cause -> assertThat(cause).isInstanceOf(ClusterInitError.TooFewCoreNodes.class));
        }

        @Test
        void coreWorkerSplit_zero_isRefused() {
            CoreWorkerSplit.coreWorkerSplit(0, 0)
                           .onSuccess(s -> fail("Expected failure but got " + s))
                           .onFailure(cause -> assertThat(cause).isInstanceOf(ClusterInitError.TooFewCoreNodes.class));
        }

        /// The message is the operator's whole signal at a refused `init`, so it is pinned rather than
        /// left to drift: it must name the minimum, the reason, and that workers are the way to add
        /// capacity without touching the consensus tier.
        @Test
        void message_namesMinimumReasonAndTheWorkerRemedy() {
            CoreWorkerSplit.coreWorkerSplit(3, 0)
                           .onSuccess(s -> fail("Expected failure but got " + s))
                           .onFailure(cause -> assertThat(cause.message()).contains("at least 5 core nodes")
                                                                          .contains("(got 3)")
                                                                          .contains("fault budget")
                                                                          .contains("--worker-nodes"));
        }
    }

    @Nested
    class CoreShape {

        @Test
        void coreWorkerSplit_evenCore_isRefused() {
            CoreWorkerSplit.coreWorkerSplit(6, 0)
                           .onSuccess(s -> fail("Expected failure but got " + s))
                           .onFailure(cause -> assertThat(cause.message()).contains("core must be odd"));
        }

        @Test
        void coreWorkerSplit_aboveConsensusCap_isRefused() {
            CoreWorkerSplit.coreWorkerSplit(11, 0)
                           .onSuccess(s -> fail("Expected failure but got " + s))
                           .onFailure(cause -> assertThat(cause.message()).contains("core must be at most 9")
                                                                          .contains("add capacity as workers"));
        }

        @Test
        void coreWorkerSplit_negativeWorker_isRefused() {
            CoreWorkerSplit.coreWorkerSplit(5, -1)
                           .onSuccess(s -> fail("Expected failure but got " + s))
                           .onFailure(cause -> assertThat(cause.message()).contains("worker must be >= 0"));
        }
    }

    @Nested
    class Accepted {

        @Test
        void coreWorkerSplit_five_isTheMinimum() {
            assertSplit(5, 0);
        }

        @Test
        void coreWorkerSplit_seven_isTheRecommendedDefault() {
            assertSplit(7, 0);
        }

        @Test
        void coreWorkerSplit_nine_isTheMaximumCore() {
            assertSplit(9, 0);
        }

        /// The fleet is NOT bounded by the consensus cap — `ClusterConfig#maxNodes` is deliberately
        /// unbounded (#298), so a large worker tier alongside a 9-node core is legal.
        @Test
        void coreWorkerSplit_manyWorkersBesideMaximumCore_isAccepted() {
            assertSplit(9, 91);
        }

        private static void assertSplit(int core, int worker) {
            CoreWorkerSplit.coreWorkerSplit(core, worker)
                           .onFailure(c -> fail("Expected success but got " + c.message()))
                           .onSuccess(split -> {
                               assertThat(split.core()).as("core").isEqualTo(core);
                               assertThat(split.worker()).as("worker").isEqualTo(worker);
                               assertThat(split.total()).as("total").isEqualTo(core + worker);
                           });
        }
    }
}

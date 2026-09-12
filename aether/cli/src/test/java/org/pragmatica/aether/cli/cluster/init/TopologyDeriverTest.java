// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster.init;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #1019 — the previous `CanonicalSplits` cases did not merely encode an older policy, they encoded
/// the DEFECT: `derive_five_returnsThreeCoreTwoWorker` asserted that asking for 5 nodes yields a
/// THREE-member consensus tier, and `derive_seven_returnsFiveCoreTwoWorker` plus the 10/11/100 cases
/// pinned the core cap at 5 — the very cap that made a 7- or 9-member consensus cluster inexpressible.
/// Those assertions were wrong about what the code should do, not merely stale.
///
/// The 3-node cases are a different matter: they pinned the old minimum correctly, and it is the
/// owner ruling of 2026-09-12 (minimum 5) that superseded them.
class TopologyDeriverTest {

    @Nested
    class FailureCases {

        @Test
        void derive_belowMinimum_returnsTooFewNodes() {
            TopologyDeriver.derive(4)
                           .onSuccess(s -> fail("Expected failure but got " + s))
                           .onFailure(cause -> assertThat(cause).isInstanceOf(ClusterInitError.TooFewNodes.class));
        }

        /// Three was the supported minimum until the 2026-09-12 ruling; it now has no fault budget
        /// during maintenance and is refused.
        @Test
        void derive_three_returnsTooFewNodes() {
            TopologyDeriver.derive(3)
                           .onSuccess(s -> fail("Expected failure but got " + s))
                           .onFailure(cause -> assertThat(cause).isInstanceOf(ClusterInitError.TooFewNodes.class));
        }

        @Test
        void derive_zero_returnsTooFewNodes() {
            TopologyDeriver.derive(0)
                           .onSuccess(s -> fail("Expected failure but got " + s))
                           .onFailure(cause -> assertThat(cause).isInstanceOf(ClusterInitError.TooFewNodes.class));
        }

        @Test
        void derive_negative_returnsTooFewNodes() {
            TopologyDeriver.derive(-3)
                           .onSuccess(s -> fail("Expected failure but got " + s))
                           .onFailure(cause -> assertThat(cause).isInstanceOf(ClusterInitError.TooFewNodes.class));
        }

        @Test
        void derive_tooFewNodes_messageNamesMinimumAndReason() {
            TopologyDeriver.derive(3)
                           .onSuccess(s -> fail("Expected failure but got " + s))
                           .onFailure(cause -> assertThat(cause.message()).contains("at least 5 nodes")
                                                                          .contains("(got 3)")
                                                                          .contains("fault budget"));
        }
    }

    /// The whole point of #1019: `--nodes N` must produce an N-member CONSENSUS cluster up to the
    /// cap, because cloud bootstrap provisions the CORE tier only.
    @Nested
    class CanonicalSplits {

        @Test
        void derive_five_returnsFiveCoreZeroWorker() {
            assertSplit(5, 5, 0);
        }

        @Test
        void derive_six_returnsFiveCoreOneWorker() {
            assertSplit(6, 5, 1);
        }

        @Test
        void derive_seven_returnsSevenCoreZeroWorker() {
            assertSplit(7, 7, 0);
        }

        @Test
        void derive_eight_returnsSevenCoreOneWorker() {
            assertSplit(8, 7, 1);
        }

        @Test
        void derive_nine_returnsNineCoreZeroWorker() {
            assertSplit(9, 9, 0);
        }

        @Test
        void derive_ten_returnsNineCoreOneWorker() {
            assertSplit(10, 9, 1);
        }

        @Test
        void derive_eleven_returnsNineCoreTwoWorker() {
            assertSplit(11, 9, 2);
        }

        /// Past the cap the fleet grows as WORKERS and is never refused — `ClusterConfig#UNBOUNDED`
        /// (#298) is the fleet policy; the cap bounds only the tier consensus broadcasts across.
        @Test
        void derive_hundred_returnsNineCoreNinetyOneWorker() {
            assertSplit(100, 9, 91);
        }

        @Test
        void derive_everyLegalTotal_keepsCoreAndWorkerSummingToTotal() {
            for (int total = 5; total <= 40; total++) {
                assertSplitSumsTo(total);
            }
        }

        private static void assertSplitSumsTo(int totalNodes) {
            TopologyDeriver.derive(totalNodes)
                           .onFailure(c -> fail("Expected success for N=" + totalNodes + " but got " + c.message()))
                           .onSuccess(split -> {
                               assertThat(split.core() + split.worker()).as("core+worker for N=" + totalNodes)
                                                                        .isEqualTo(totalNodes);
                               assertThat(split.core()).as("core is odd and within bounds for N=" + totalNodes)
                                                       .isBetween(TopologyDeriver.MINIMUM_TOTAL_NODES,
                                                                  TopologyDeriver.MAXIMUM_CORE_NODES)
                                                       .matches(c -> c % 2 == 1, "odd");
                           });
        }

        private static void assertSplit(int totalNodes, int expectedCore, int expectedWorker) {
            TopologyDeriver.derive(totalNodes)
                           .onFailure(c -> fail("Expected success but got " + c.message()))
                           .onSuccess(split -> {
                               assertThat(split.core()).as("core for N=" + totalNodes).isEqualTo(expectedCore);
                               assertThat(split.worker()).as("worker for N=" + totalNodes).isEqualTo(expectedWorker);
                           });
        }
    }
}

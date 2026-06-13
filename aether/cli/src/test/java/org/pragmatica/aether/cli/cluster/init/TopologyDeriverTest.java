// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster.init;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class TopologyDeriverTest {

    @Nested
    class FailureCases {

        @Test
        void derive_belowQuorum_returnsTooFewNodes() {
            TopologyDeriver.derive(2)
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
    }

    @Nested
    class CanonicalSplits {

        @Test
        void derive_three_returnsThreeCoreZeroWorker() {
            assertSplit(3, 3, 0);
        }

        @Test
        void derive_five_returnsThreeCoreTwoWorker() {
            assertSplit(5, 3, 2);
        }

        @Test
        void derive_seven_returnsFiveCoreTwoWorker() {
            assertSplit(7, 5, 2);
        }

        @Test
        void derive_ten_returnsFiveCoreFiveWorker() {
            assertSplit(10, 5, 5);
        }

        @Test
        void derive_eleven_returnsFiveCoreSixWorker() {
            assertSplit(11, 5, 6);
        }

        @Test
        void derive_hundred_returnsFiveCoreNinetyFiveWorker() {
            assertSplit(100, 5, 95);
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

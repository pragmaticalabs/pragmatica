// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Regression coverage for the `aether scale --wait` sibling found by the #522 sweep.
///
/// `ClusterSlicesResponse` names each artifact exactly once and its instance entries carry
/// only `nodeId`/`state`, so the previous "count occurrences of the coordinates in the raw
/// response" reading was pinned at 1 for any number of running instances.
class ScaleWaitTest {
    private static final String ARTIFACT = "org.example:hello-hello-world:1.0.0-SNAPSHOT";

    private static final String THREE_ACTIVE = """
            {"slices":[{"artifact":"org.example:hello-hello-world:1.0.0-SNAPSHOT",
                        "targetInstances":3,
                        "minInstances":2,
                        "version":"1.0.0-SNAPSHOT",
                        "instances":[{"nodeId":"core-0","state":"ACTIVE","failureReason":""},
                                     {"nodeId":"core-1","state":"ACTIVE","failureReason":""},
                                     {"nodeId":"core-4","state":"ACTIVE","failureReason":""}]}]}""";

    private static final String PARTIALLY_UP = """
            {"slices":[{"artifact":"org.example:hello-hello-world:1.0.0-SNAPSHOT",
                        "targetInstances":3,
                        "minInstances":2,
                        "version":"1.0.0-SNAPSHOT",
                        "instances":[{"nodeId":"core-0","state":"ACTIVE","failureReason":""},
                                     {"nodeId":"core-1","state":"LOADING","failureReason":""},
                                     {"nodeId":"core-4","state":"FAILED","failureReason":"boom"}]}]}""";

    private static final String TWO_VERSIONS = """
            {"slices":[{"artifact":"org.example:hello-hello-world:1.0.0-SNAPSHOT",
                        "targetInstances":1,
                        "minInstances":1,
                        "version":"1.0.0-SNAPSHOT",
                        "instances":[{"nodeId":"core-0","state":"ACTIVE","failureReason":""}]},
                       {"artifact":"org.example:hello-hello-world:2.0.0",
                        "targetInstances":2,
                        "minInstances":1,
                        "version":"2.0.0",
                        "instances":[{"nodeId":"core-1","state":"ACTIVE","failureReason":""},
                                     {"nodeId":"core-4","state":"ACTIVE","failureReason":""}]}]}""";

    @Nested
    class Counting {
        @Test
        void activeInstances_threeActiveInstances_returnsThree() {
            assertEquals(3, ScaleWait.activeInstances(THREE_ACTIVE, ARTIFACT));
        }

        @Test
        void activeInstances_onlyOneInstanceActive_countsOnlyTheActiveOne() {
            assertEquals(1, ScaleWait.activeInstances(PARTIALLY_UP, ARTIFACT));
        }

        @Test
        void activeInstances_artifactAbsentFromList_returnsZero() {
            assertEquals(0, ScaleWait.activeInstances(THREE_ACTIVE, "org.example:other:1.0.0"));
        }

        @Test
        void activeInstances_severalVersionsDeployed_countsOnlyTheRequestedOne() {
            assertEquals(1, ScaleWait.activeInstances(TWO_VERSIONS, ARTIFACT));
            assertEquals(2, ScaleWait.activeInstances(TWO_VERSIONS, "org.example:hello-hello-world:2.0.0"));
        }

        @Test
        void activeInstances_emptySliceList_returnsZero() {
            assertEquals(0, ScaleWait.activeInstances("{\"slices\":[]}", ARTIFACT));
        }
    }

    @Nested
    class UnreadableResponses {
        @Test
        void activeInstances_errorEnvelope_returnsUnreadable() {
            assertEquals(ScaleWait.UNREADABLE,
                         ScaleWait.activeInstances("{\"error\":\"no leader\"}", ARTIFACT));
        }

        @Test
        void activeInstances_problemDetail_returnsUnreadable() {
            assertEquals(ScaleWait.UNREADABLE,
                         ScaleWait.activeInstances("{\"status\":503,\"title\":\"No leader\"}", ARTIFACT));
        }

        @Test
        void activeInstances_malformedJson_returnsUnreadable() {
            assertEquals(ScaleWait.UNREADABLE, ScaleWait.activeInstances("connection refused", ARTIFACT));
        }

        @Test
        void activeInstances_unreadableResponse_neverSatisfiesAnyTarget() {
            // The wait loop's completion test is `current >= target`; UNREADABLE must fall below
            // every legal target (>= 1) so an unreadable cluster keeps waiting and then fails.
            var current = ScaleWait.activeInstances("connection refused", ARTIFACT);

            assertTrue(current < 1, "unreadable must not satisfy the smallest legal target");
        }
    }

    @Nested
    class Display {
        @Test
        void describe_realCount_rendersTheNumber() {
            assertEquals("0", ScaleWait.describe(0));
            assertEquals("3", ScaleWait.describe(3));
        }

        @Test
        void describe_unreadable_saysUnknownRatherThanShowingTheSentinel() {
            // `Current instances: -1 / 3` presents a sentinel as if it were a measurement.
            assertEquals("unknown", ScaleWait.describe(ScaleWait.UNREADABLE));
        }

        @Test
        void describe_unreadableResponseEndToEnd_saysUnknown() {
            assertEquals("unknown",
                         ScaleWait.describe(ScaleWait.activeInstances("connection refused", ARTIFACT)));
        }

        @Test
        void describe_zeroActiveInstances_saysZeroNotUnknown() {
            // Zero is a real reading — the cluster answered and nothing is running. It must stay
            // distinguishable from "we could not tell".
            var noneUp = """
                    {"slices":[{"artifact":"org.example:hello-hello-world:1.0.0-SNAPSHOT",
                                "targetInstances":3,
                                "minInstances":1,
                                "version":"1.0.0-SNAPSHOT",
                                "instances":[]}]}""";

            assertEquals("0", ScaleWait.describe(ScaleWait.activeInstances(noneUp, ARTIFACT)));
        }
    }

    @Nested
    class TheDefect {
        @Test
        void activeInstances_threeRunningInstances_exceedsTheSubstringCountTheOldGateUsed() {
            // The old reading: occurrences of the coordinates in the raw response text.
            assertEquals(1, occurrences(THREE_ACTIVE, ARTIFACT),
                         "coordinates appear exactly once regardless of instance count");
            assertEquals(3, ScaleWait.activeInstances(THREE_ACTIVE, ARTIFACT),
                         "so `--wait -n 3` timed out on a scale that had already succeeded");
        }

        @Test
        void activeInstances_scaleDownToOne_noLongerSucceedsWithoutReadingInstanceState() {
            // The old reading returned 1 for a slice with zero healthy instances, so
            // `--wait -n 1` reported success on a slice that was not up at all.
            var noneUp = """
                    {"slices":[{"artifact":"org.example:hello-hello-world:1.0.0-SNAPSHOT",
                                "targetInstances":1,
                                "minInstances":1,
                                "version":"1.0.0-SNAPSHOT",
                                "instances":[{"nodeId":"core-0","state":"FAILED","failureReason":"boom"}]}]}""";

            assertEquals(1, occurrences(noneUp, ARTIFACT));
            assertEquals(0, ScaleWait.activeInstances(noneUp, ARTIFACT),
                         "a failed instance must not count towards the target");
        }

        private static int occurrences(String response, String token) {
            var index = 0;
            var count = 0;

            while ((index = response.indexOf(token, index)) >= 0) {
                count++;
                index += token.length();
            }

            return count;
        }
    }
}

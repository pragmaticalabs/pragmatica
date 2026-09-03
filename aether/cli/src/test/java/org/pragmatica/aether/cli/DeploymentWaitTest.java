// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Regression coverage for #522 — `aether blueprints deploy --wait` reported a 300s
/// timeout on a deployment that had actually completed.
///
/// The payloads below are the ones observed on the live 5-node cluster: the deployed
/// blueprint is `org.example:hello:1.0.0-SNAPSHOT`, while the slice it expands to is
/// `org.example:hello-hello-world:1.0.0-SNAPSHOT`. The old gate substring-matched the
/// blueprint coordinates against the slice list, which cannot match a derived slice
/// artifact, so it reported PENDING forever.
class DeploymentWaitTest {
    private static final String BLUEPRINT_COORDS = "org.example:hello:1.0.0-SNAPSHOT";

    /// #759 review (m) — matches the live `BlueprintResponse` shape
    /// (`ManagementApiResponses.java`): `targetInstances`/`activeInstances`/`failedInstances`
    /// plus `statusUrl`, not the retired `slices` count.
    private static final String DEPLOY_RESPONSE = """
            {"status":"pending","blueprint":"org.example:hello:1.0.0-SNAPSHOT","targetInstances":3,\
            "activeInstances":0,"failedInstances":0,"statusUrl":"/api/blueprints/status/org.example%3Ahello%3A1.0.0-SNAPSHOT"}""";

    private static final String COMPLETED_BLUEPRINT_STATUS = """
            {"id":"org.example:hello:1.0.0-SNAPSHOT",
             "overallStatus":"DEPLOYED",
             "slices":[{"artifact":"org.example:hello-hello-world:1.0.0-SNAPSHOT",
                        "targetInstances":3,
                        "activeInstances":3,
                        "status":"DEPLOYED"}]}""";

    private static final String STUCK_BLUEPRINT_STATUS = """
            {"id":"org.example:hello:1.0.0-SNAPSHOT",
             "overallStatus":"PENDING",
             "slices":[{"artifact":"org.example:hello-hello-world:1.0.0-SNAPSHOT",
                        "targetInstances":3,
                        "activeInstances":0,
                        "status":"PENDING"}]}""";

    /// What `aether slices status` reported at the very moment `--wait` was claiming PENDING.
    private static final String COMPLETED_SLICES_STATUS = """
            {"slices":[{"artifact":"org.example:hello-hello-world:1.0.0-SNAPSHOT",
                        "state":"ACTIVE",
                        "instances":[{"nodeId":"hetzner-eu-core-4","state":"ACTIVE","health":"HEALTHY"},
                                     {"nodeId":"hetzner-eu-core-1","state":"ACTIVE","health":"HEALTHY"},
                                     {"nodeId":"hetzner-eu-core-0","state":"ACTIVE","health":"HEALTHY"}]}]}""";

    private static final long POLL_INTERVAL_MS = 1;

    @Nested
    class BlueprintIdExtraction {
        @Test
        void blueprintId_deployResponse_returnsDeclaredBlueprintId() {
            assertEquals(BLUEPRINT_COORDS,
                         DeploymentWait.blueprintId(DEPLOY_RESPONSE).or("<absent>"));
        }

        @Test
        void blueprintId_responseWithoutBlueprintField_returnsEmpty() {
            assertTrue(DeploymentWait.blueprintId("{\"status\":\"deployed\",\"targetInstances\":1,\"activeInstances\":1,\"failedInstances\":0}")
                                     .isEmpty());
        }

        @Test
        void blueprintId_malformedJson_returnsEmpty() {
            assertTrue(DeploymentWait.blueprintId("not json at all").isEmpty());
        }
    }

    @Nested
    class OverallStatusExtraction {
        @Test
        void overallStatus_allSlicesDeployed_returnsDeployed() {
            assertEquals("DEPLOYED", DeploymentWait.overallStatus(COMPLETED_BLUEPRINT_STATUS));
        }

        @Test
        void overallStatus_noInstancesActive_returnsPending() {
            assertEquals("PENDING", DeploymentWait.overallStatus(STUCK_BLUEPRINT_STATUS));
        }

        @Test
        void overallStatus_errorEnvelope_returnsUnknown() {
            assertEquals(DeploymentWait.UNKNOWN,
                         DeploymentWait.overallStatus("{\"error\":\"Blueprint not found\"}"));
        }

        @Test
        void overallStatus_problemDetail_returnsUnknown() {
            assertEquals(DeploymentWait.UNKNOWN,
                         DeploymentWait.overallStatus("{\"status\":503,\"title\":\"No leader\"}"));
        }

        @Test
        void overallStatus_malformedResponse_returnsUnknown() {
            assertEquals(DeploymentWait.UNKNOWN, DeploymentWait.overallStatus("connection refused"));
        }

        @Test
        void overallStatus_fieldMissing_returnsUnknown() {
            assertEquals(DeploymentWait.UNKNOWN, DeploymentWait.overallStatus("{\"id\":\"x\",\"slices\":[]}"));
        }
    }

    @Nested
    class CompletionPredicate {
        @Test
        void isComplete_deployedStatus_returnsTrue() {
            assertTrue(DeploymentWait.isComplete("DEPLOYED"));
        }

        @Test
        void isComplete_nonTerminalStatuses_returnsFalse() {
            List.of("PENDING", "IN_PROGRESS", "PARTIAL", DeploymentWait.UNKNOWN)
                .forEach(status -> assertFalse(DeploymentWait.isComplete(status),
                                               status + " must not end the wait"));
        }

        @Test
        void isComplete_sliceLevelActiveState_returnsFalse() {
            // ACTIVE belongs to the per-instance SliceState vocabulary, not to the blueprint
            // overallStatus vocabulary (DEPLOYED/PENDING/IN_PROGRESS/PARTIAL). Accepting it
            // would re-open the false-success half of the old gate.
            assertFalse(DeploymentWait.isComplete("ACTIVE"));
        }
    }

    @Nested
    class AwaitCompletion {
        @Test
        void awaitCompletion_statusReachesDeployed_returnsSuccessWithoutWaitingForDeadline() {
            var remaining = scripted("PENDING", "IN_PROGRESS", "DEPLOYED");
            var polls = new AtomicInteger();
            var startedAt = System.currentTimeMillis();
            var deadline = startedAt + 300_000;

            var exitCode = DeploymentWait.awaitCompletion(() -> countedStatus(polls, remaining),
                                                          deadline,
                                                          POLL_INTERVAL_MS);

            assertEquals(ExitCode.SUCCESS, exitCode);
            assertEquals(3, polls.get(), "should stop polling on the first DEPLOYED reading");
            assertTrue(System.currentTimeMillis() - startedAt < 5_000,
                       "must return as soon as the deployment completes, not at the deadline");
        }

        @Test
        void awaitCompletion_statusNeverLeavesPending_returnsTimeout() {
            var remaining = scripted("PENDING");
            var polls = new AtomicInteger();

            var exitCode = DeploymentWait.awaitCompletion(() -> countedStatus(polls, remaining),
                                                          System.currentTimeMillis() + 100,
                                                          POLL_INTERVAL_MS);

            assertEquals(ExitCode.TIMEOUT, exitCode);
            assertTrue(exitCode != ExitCode.SUCCESS, "a stuck deployment must exit non-zero");
            assertTrue(polls.get() > 1, "should keep polling until the deadline");
        }

        @Test
        void awaitCompletion_statusUnreadable_returnsTimeout() {
            var remaining = scripted(DeploymentWait.UNKNOWN);

            assertEquals(ExitCode.TIMEOUT,
                         DeploymentWait.awaitCompletion(() -> nextStatus(remaining),
                                                        System.currentTimeMillis() + 50,
                                                        POLL_INTERVAL_MS));
        }

        @Test
        void awaitCompletion_deadlineAlreadyPassedButDeployed_returnsSuccess() {
            var remaining = scripted("DEPLOYED");

            assertEquals(ExitCode.SUCCESS,
                         DeploymentWait.awaitCompletion(() -> nextStatus(remaining),
                                                        System.currentTimeMillis() - 1,
                                                        POLL_INTERVAL_MS));
        }

        @Test
        void awaitCompletion_deadlineAlreadyPassedAndPending_returnsTimeout() {
            var remaining = scripted("PENDING");

            assertEquals(ExitCode.TIMEOUT,
                         DeploymentWait.awaitCompletion(() -> nextStatus(remaining),
                                                        System.currentTimeMillis() - 1,
                                                        POLL_INTERVAL_MS));
        }
    }

    @Nested
    class SurfaceAgreement {
        @Test
        void overallStatus_completedDeployment_reportsDeployedWhereSliceListSubstringCannotMatch() {
            // The old gate's input: the blueprint coordinates never occur in the slice list,
            // because the list carries the derived slice artifact. This is the whole of #522.
            assertFalse(COMPLETED_SLICES_STATUS.contains(BLUEPRINT_COORDS),
                        "blueprint coords cannot appear in the slice list — the old gate was unmatchable");
            assertTrue(COMPLETED_SLICES_STATUS.contains("org.example:hello-hello-world:1.0.0-SNAPSHOT"));

            assertEquals("DEPLOYED",
                         DeploymentWait.overallStatus(COMPLETED_BLUEPRINT_STATUS),
                         "the blueprint status surface agrees with slices status that the deploy finished");
        }

        @Test
        void awaitCompletion_completedDeploymentPayload_returnsSuccess() {
            var remaining = scripted(COMPLETED_BLUEPRINT_STATUS);

            assertEquals(ExitCode.SUCCESS,
                         DeploymentWait.awaitCompletion(() -> DeploymentWait.overallStatus(nextStatus(remaining)),
                                                        System.currentTimeMillis() + 5_000,
                                                        POLL_INTERVAL_MS));
        }

        @Test
        void awaitCompletion_stuckDeploymentPayload_returnsTimeout() {
            var remaining = scripted(STUCK_BLUEPRINT_STATUS);

            assertEquals(ExitCode.TIMEOUT,
                         DeploymentWait.awaitCompletion(() -> DeploymentWait.overallStatus(nextStatus(remaining)),
                                                        System.currentTimeMillis() + 100,
                                                        POLL_INTERVAL_MS));
        }

        @Test
        void awaitCompletion_deploymentThatCompletesMidWait_returnsSuccess() {
            var remaining = scripted(STUCK_BLUEPRINT_STATUS, STUCK_BLUEPRINT_STATUS, COMPLETED_BLUEPRINT_STATUS);

            assertEquals(ExitCode.SUCCESS,
                         DeploymentWait.awaitCompletion(() -> DeploymentWait.overallStatus(nextStatus(remaining)),
                                                        System.currentTimeMillis() + 5_000,
                                                        POLL_INTERVAL_MS));
        }
    }

    private static Deque<String> scripted(String... statuses) {
        return new ArrayDeque<>(List.of(statuses));
    }

    /// Yields each scripted reading once, then repeats the last one indefinitely.
    private static String nextStatus(Deque<String> remaining) {
        return remaining.size() > 1
               ? remaining.poll()
               : remaining.peek();
    }

    private static String countedStatus(AtomicInteger polls, Deque<String> remaining) {
        polls.incrementAndGet();

        return nextStatus(remaining);
    }
}

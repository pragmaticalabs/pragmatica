// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;


/// Poll-until-terminal gate behind `aether blueprints deploy --wait` (#522).
///
/// The gate reads `overallStatus` from the blueprint deployment status endpoint
/// (`BLUEPRINT_STATUS`). The node derives that value from the same replicated
/// `DeploymentMap` that backs `aether slices status` and the `DEPLOYMENT_COMPLETED`
/// event, so the three surfaces cannot report different answers about whether a
/// deployment finished. Matching the requested coordinates against the slice list
/// instead — what this gate used to do — compares a blueprint artifact against the
/// derived slice artifacts and never observes completion.
///
/// Only `DEPLOYED` ends the wait successfully. `PENDING`, `IN_PROGRESS`, `PARTIAL`
/// and an unreadable response all keep polling until the deadline, so a stuck or
/// failed deployment still exits with [ExitCode#TIMEOUT].
public sealed interface DeploymentWait {
    String DEPLOYED = "DEPLOYED";
    String UNKNOWN = "UNKNOWN";

    /// Authoritative blueprint id, read from the deploy response instead of being
    /// re-derived from the requested coordinates — a blueprint declares its own id,
    /// so deriving it is a guess.
    static Option<String> blueprintId(String deployResponse) {
        return OutputFormatter.MAPPER.extractField(deployResponse, "blueprint").option();
    }

    static String overallStatus(String statusResponse) {
        return OutputFormatter.isErrorResponse(statusResponse)
               ? UNKNOWN
               : OutputFormatter.MAPPER.extractField(statusResponse, "overallStatus").or(UNKNOWN);
    }

    static boolean isComplete(String overallStatus) {
        return DEPLOYED.equalsIgnoreCase(overallStatus);
    }

    /// Polls until the status source reports completion or `deadlineMillis` passes.
    /// The status is sampled before the deadline is examined, so an already-finished
    /// deployment is observed even when no time is left.
    // PAT-01: a poll-sleep-until-deadline gate is inherently a loop over wall-clock time,
    // not over a collection — there is nothing to iterate functionally.
    @SuppressWarnings("JBCT-PAT-01")
    static int awaitCompletion(Fn0<String> statusSource, long deadlineMillis, long pollIntervalMillis) {
        while (true) {
            var status = statusSource.apply();

            if (isComplete(status)) {
                return ExitCode.SUCCESS;
            }

            if (System.currentTimeMillis() >= deadlineMillis) {
                return ExitCode.TIMEOUT;
            }

            reportProgress(status);
            sleepQuietly(pollIntervalMillis);
        }
    }

    @Contract
    private static void reportProgress(String status) {
        System.out.printf("  Deployment status: %s%n", status);
    }

    @Contract
    @SuppressWarnings("JBCT-EX-01")
    private static void sleepQuietly(long pollIntervalMillis) {
        try {
            Thread.sleep(pollIntervalMillis);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    record unused() implements DeploymentWait {}
}

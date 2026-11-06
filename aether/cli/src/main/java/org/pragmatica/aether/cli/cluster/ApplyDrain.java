// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.List;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.aether.management.route.ManagementRoute.NODE_DRAIN;
import static org.pragmatica.aether.management.route.ManagementRoute.NODE_LIFECYCLE_GET;


/// The cluster-side drain `--rollback` runs for a node that had joined, bounded by [WaveExecutor#DRAIN_TIMEOUT_MS]
/// (cluster-apply spec 4.5; owner ruling Q10: after the bound, terminate anyway, loudly).
@SuppressWarnings("JBCT-UTIL-02")
final class ApplyDrain {
    private static final long POLL_MILLIS = 2_000;

    private ApplyDrain() {}

    static Result<Unit> drainThroughTheCluster(String nodeId) {
        return ClusterHttpClient.post(NODE_DRAIN,
                                      List.of(nodeId),
                                      ClusterHttpClient.forceQuery(false),
                                      "{}")
                                .flatMap(_ -> awaitDeparture(nodeId));
    }

    private static Result<Unit> awaitDeparture(String nodeId) {
        var deadline = System.currentTimeMillis() + WaveExecutor.DRAIN_TIMEOUT_MS;

        while (System.currentTimeMillis() < deadline) {
            if (DrainCompletion.isDeparted(ClusterHttpClient.fetch(NODE_LIFECYCLE_GET, List.of(nodeId)))) {
                return Result.unitResult();
            }

            pause();
        }

        return Causes.cause("node " + nodeId
                           + " did not finish draining within " + WaveExecutor.DRAIN_TIMEOUT_MS / 1000
                           + " s").result();
    }

    @Contract
    private static void pause() {
        try {
            Thread.sleep(POLL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

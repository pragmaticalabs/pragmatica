// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.audit;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.stream.StreamPartitionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// Regression for the audit-stream over-provisioning bug: the lifecycle-audit stream was
/// configured to reserve ~347.55 MiB off-heap (4 partitions x (64 + 24 x 1,000,000 + 64 MiB))
/// while the default `StreamPartitionManager` budget is 128 MiB, so EVERY node failed audit
/// provisioning with "Total off-heap memory limit exceeded". The retention bounds were shrunk
/// (maxCount 1,000,000 -> 50,000, maxBytes 64 MiB -> 8 MiB) bringing the reservation to ~36.6 MiB.
class AuditLifecycleStreamsTest {
    /// Mirror of `StreamPartitionManager.calculateStreamBytes`: perPartition = 64 + 24*maxCount
    /// + maxBytes; total = perPartition * partitions.
    private static final long EXPECTED_TOTAL_BYTES =
        (64L + (24L * AuditLifecycleStreams.AUDIT_RETENTION_MAX_COUNT) + AuditLifecycleStreams.AUDIT_RETENTION_MAX_BYTES)
        * AuditLifecycleStreams.AUDIT_PARTITIONS;
    private static final long DEFAULT_BUDGET_BYTES = 128L * 1024L * 1024L;

    @Test
    void auditStreamReservation_fitsWithinDefaultBudget() {
        // The audit stream alone must sit comfortably under the 128 MiB default budget so the
        // node still has headroom for application streams.
        assertThat(EXPECTED_TOTAL_BYTES)
            .as("audit-stream off-heap reservation must stay well under the 128 MiB default budget")
            .isLessThan(DEFAULT_BUDGET_BYTES / 2);
    }

    @Test
    void createStream_auditLifecycle_succeedsAgainstDefaultBudgetManager() {
        // End-to-end: provisioning the real AUDIT_LIFECYCLE_COMMANDS config against a manager
        // with the production default budget must succeed (this is exactly what AetherNode.start
        // does at boot). Before the fix this returned an off-heap-limit failure.
        var manager = StreamPartitionManager.streamPartitionManager();

        manager.createStream(AuditLifecycleStreams.AUDIT_LIFECYCLE_COMMANDS)
               .onFailure(cause -> fail("audit-stream provisioning must succeed within budget: " + cause.message()));
    }
}

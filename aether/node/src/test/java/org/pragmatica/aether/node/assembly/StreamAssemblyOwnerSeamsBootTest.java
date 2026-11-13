// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.AssemblySource;
import org.pragmatica.aether.stream.OwnerActivation;
import org.pragmatica.aether.stream.OwnerLease;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// #2981 step 2 (S7, design §2.2 "Production cannot run unwired"): node startup fails when the partition manager's ownership-record
/// seam or lease seam is still its `UNWIRED` sentinel, or the lease is the test-scope "valid" object, each compared BY IDENTITY.
/// Mutations: the check made log-only -> the three failure tests RED; any one of the three identity comparisons dropped -> its own
/// test RED; the call removed from `streamOwnership` -> the wiring pin RED.
class StreamAssemblyOwnerSeamsBootTest {
    private static final OwnerActivation.OwnershipRecordSource WIRED_RECORDS = (_, _) -> Option.none();
    private static final OwnerLease WIRED_LEASE = () -> true;

    @Test
    void unwiredRecordSeam_failsStartup_namingTheSeam() {
        assertThatThrownBy(() -> StreamAssembly.verifyOwnerSeamsWired(OwnerActivation.OwnershipRecordSource.UNWIRED, WIRED_LEASE))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("OWNERSHIP_RECORDS");
    }

    @Test
    void unwiredLeaseSeam_failsStartup_namingTheSeam() {
        assertThatThrownBy(() -> StreamAssembly.verifyOwnerSeamsWired(WIRED_RECORDS, OwnerLease.UNWIRED))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("OWNER_LEASE")
            .hasMessageContaining("UNWIRED");
    }

    @Test
    void testScopeValidLease_failsStartup_namingTheSeam() {
        assertThatThrownBy(() -> StreamAssembly.verifyOwnerSeamsWired(WIRED_RECORDS, OwnerLease.TEST_VALID))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("OWNER_LEASE")
            .hasMessageContaining("test-scope");
    }

    @Test
    void bothSeamsWired_startupProceeds() {
        assertThatCode(() -> StreamAssembly.verifyOwnerSeamsWired(WIRED_RECORDS, WIRED_LEASE)).doesNotThrowAnyException();
    }

    /// The assembly runs the check on the manager's bound seams, after the existing boot report. Pinned by source text
    /// [unverified: wiring pinned by text only]. Mutation: the call removed or its arguments swapped -> RED.
    @Test
    void streamOwnership_runsTheCheck_onTheManagersBoundSeams_afterTheBootReport() {
        var source = AssemblySource.stripped();
        var report = source.indexOf("streamPartitionManager.reportUnboundSafetySeams();");
        var check = source.indexOf("verifyOwnerSeamsWired(streamPartitionManager.ownershipRecordSeam(),streamPartitionManager.ownerLeaseSeam());");

        assertThat(report).isPositive();
        assertThat(check).isGreaterThan(report);
        assertThat(source).containsOnlyOnce("verifyOwnerSeamsWired(streamPartitionManager.ownershipRecordSeam(),streamPartitionManager.ownerLeaseSeam());");
    }
}

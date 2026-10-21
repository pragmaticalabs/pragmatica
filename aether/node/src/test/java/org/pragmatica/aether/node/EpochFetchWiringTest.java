// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1873 (KIP-320): the epoch-validated fetch works only if the node binds its committed ownership records into the stream
/// partition manager (the serving side checks every consumer read against them), hands the owner activation the ring
/// incarnation and the commit that records where an epoch begins, gives the consumer runtime the validating reader, and
/// binds the operator-warning sink a consumer's re-seek is reported through. Each piece is pinned by its own unit test;
/// this pins the production wiring that connects them, which none of those can reach.
class EpochFetchWiringTest {
    @Test
    void theNodeWiresTheEpochValidatedFetch() {
        var code = assemblyCode();

        assertThat(code).containsOnlyOnce("streamPartitionManager.ownershipRecords((stream,partition)->kvStore.getTyped(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(stream,partition),StreamPartitionOwnershipValue.class));");
        assertThat(code).containsOnlyOnce("streamPartitionManager.ringIncarnation(stream,partition).or(-1L),streamLineageCommit(()->kvStore.getTyped(LeaderKey.INSTANCE,LeaderValue.class),clusterCommandApplier,hlcClock));");
        assertThat(code).containsOnlyOnce("StreamConsumerRuntime.validatingReader(");
        assertThat(code).containsOnlyOnce("streamReadRouter::readValidated)");
        assertThat(code).containsOnlyOnce("streamConsumerRuntime.operatorWarnings(operatorWarningSink);");
    }

    private static String assemblyCode() {
        return AssemblySource.stripped();
    }
}

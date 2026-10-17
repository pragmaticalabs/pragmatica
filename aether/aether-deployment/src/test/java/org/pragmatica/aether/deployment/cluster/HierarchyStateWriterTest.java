// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

class HierarchyStateWriterTest {
    private static final LeaderValue LEADER = new LeaderValue(new NodeId("core"), 4);
    private static final AetherValue VALUE = AetherValue.AutoHealStateValue.autoHealStateValue(false, "test");

    @Test
    void deactivationBeforeSubmissionRejectsLateCallbackWithoutSubmitting() {
        var active = new AtomicBoolean(true);
        var calls = new AtomicInteger();
        var writer = HierarchyStateWriter.hierarchyStateWriter(() -> Option.some(LEADER), _ -> Option.none(), commands -> {
            calls.incrementAndGet();
            return Promise.success(List.of());
        }).whileActive(active::get);
        active.set(false);
        assertThat(writer.put(AetherKey.AutoHealStateKey.SINGLETON, Option.none(), VALUE).await().isFailure()).isTrue();
        assertThat(calls.get()).isZero();
    }

    @Test
    void rejectedConditionalCommitCannotReportSuccess() {
        var writer = HierarchyStateWriter.hierarchyStateWriter(() -> Option.some(LEADER), _ -> Option.none(), commands -> {
            var transaction = (KVCommand.LeaderTransaction<?, ?>) commands.getFirst();
            assertThat(transaction.leader()).isEqualTo(LEADER);
            return Promise.success(List.of(new KVCommand.TransactionResult(transaction.transactionId(), false)));
        });
        assertThat(writer.put(AetherKey.AutoHealStateKey.SINGLETON, Option.none(), VALUE).await().isFailure()).isTrue();
    }

    @Test
    void anotherSubmittersAcceptedResultCannotAcknowledgeThisWrite() {
        var writer = HierarchyStateWriter.hierarchyStateWriter(() -> Option.some(LEADER), _ -> Option.none(),
            _ -> Promise.success(List.of(new KVCommand.TransactionResult("another-write", true))));
        assertThat(writer.put(AetherKey.AutoHealStateKey.SINGLETON, Option.none(), VALUE).await().isFailure()).isTrue();
    }
}

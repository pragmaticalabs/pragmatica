// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.pragmatica.lang.Promise;


public interface FailoverRecovery {
    Promise<RecoveryResult> recover(String streamName, int partitionCount);

    record RecoveryResult(int partitionsRecovered, long eventsReplayed, long recoveryMs) {
        public static RecoveryResult recoveryResult(int partitionsRecovered, long eventsReplayed, long recoveryMs) {
            return new RecoveryResult(partitionsRecovered, eventsReplayed, recoveryMs);
        }

        public static RecoveryResult recoveryResult(long recoveryMs) {
            return new RecoveryResult(0, 0L, recoveryMs);
        }
    }

    /// `durability` is the replica WAL barrier each recovered partition commits through once its last event
    /// is applied (#1244 × #1235); production wires `StreamPartitionManager::syncReplicated`, WAL-less
    /// callers pass [ReplicationReceiveHandler#NO_DURABILITY_BARRIER].
    static FailoverRecovery failoverRecovery(ReplicaRegistry registry,
                                             AlignedRecovery partitionRecovery,
                                             CatchupTransport transport,
                                             ReplicationReceiveHandler.ReplicaDurability durability) {
        return new DefaultFailoverRecovery(registry, partitionRecovery, transport, durability);
    }
}

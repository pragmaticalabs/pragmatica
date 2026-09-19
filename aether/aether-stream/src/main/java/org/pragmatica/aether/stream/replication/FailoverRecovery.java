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

    static FailoverRecovery failoverRecovery(ReplicaRegistry registry,
                                             StreamPartitionRecovery partitionRecovery,
                                             CatchupTransport transport) {
        return failoverRecovery(registry, partitionRecovery, transport, ReplicationReceiveHandler.NO_DURABILITY_BARRIER);
    }

    /// #1244: `durability` is the replica WAL barrier each recovered partition's fetched range is committed
    /// through before the partition counts as recovered.
    static FailoverRecovery failoverRecovery(ReplicaRegistry registry,
                                             StreamPartitionRecovery partitionRecovery,
                                             CatchupTransport transport,
                                             ReplicationReceiveHandler.ReplicaDurability durability) {
        return new DefaultFailoverRecovery(registry, partitionRecovery, transport, durability);
    }
}

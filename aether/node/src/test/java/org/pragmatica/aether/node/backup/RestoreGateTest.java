// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import java.util.List;

import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.BackupRestoreKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GossipKeyRotationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.BackupRestoreOutcome;
import org.pragmatica.aether.slice.kvstore.AetherValue.BackupRestoreValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GossipKeyRotationValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1533 — the restore gate's admission rule on its own. The end-to-end pin (a node's submit path refuses
/// before the decision) is `EmberKvBackupRestoreTest`.
class RestoreGateTest {
    private static final SliceCodec NODE_CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    private static final ConfigKey STATE_KEY = ConfigKey.forKey("state");
    private static final ConfigValue STATE_VALUE = ConfigValue.configValue("state", "v");

    private KVStore<AetherKey, AetherValue> kvStore;
    private long slot;

    @BeforeEach
    void setUp() {
        kvStore = new KVStore<>(MessageRouter.mutable(), NODE_CODEC, NODE_CODEC);
    }

    @Test
    void admit_refusesClusterStateWrites_beforeTheDecision() {
        Cause refusal = RestoreGate.admit(kvStore, List.of(new KVCommand.Put<>(STATE_KEY, STATE_VALUE)))
                                   .fold(cause -> cause, _ -> Causes.cause("admitted"));

        assertThat(refusal).isInstanceOf(RestoreGate.RestorePending.class);
        assertThat(RestoreGate.admit(kvStore, List.of(new KVCommand.Remove<>(STATE_KEY))).isFailure()).isTrue();
        assertThat(RestoreGate.admit(kvStore, List.of(transaction("seeder:1"))).isFailure()).isTrue();
    }

    @Test
    void admit_letsRuntimeWritesAndTheRestoreItselfThrough() {
        assertThat(RestoreGate.admit(kvStore,
                                     List.of(new KVCommand.Put<>(GossipKeyRotationKey.gossipKeyRotationKey(),
                                                                 GossipKeyRotationValue.gossipKeyRotationValue(1, "k1")))).isSuccess()).isTrue();
        assertThat(RestoreGate.admit(kvStore, List.of(transaction(RestoreGate.RESTORE_TRANSACTION_PREFIX + "chunk:1"))).isSuccess()).isTrue();
    }

    /// The election writes `LeaderKey` through the same log under the `AetherKey` parameter; the gate must
    /// read it without a typed cast, or leader election would throw inside the submit path.
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void admit_passesTheLeaderAtom_withoutACast() {
        List commands = List.of(new KVCommand.Put<>(LeaderKey.INSTANCE,
                                                    LeaderValue.leaderValue(NodeId.nodeId("node-1")
                                                                                  .unwrap(),
                                                                            1L)));

        assertThat(RestoreGate.admit(kvStore, commands)
                              .isSuccess()).isTrue();
    }

    @Test
    void theGate_staysClosedWhileARestoreIsInProgress_andOpensOnATerminalDecision() {
        decide(BackupRestoreOutcome.IN_PROGRESS);
        assertThat(RestoreGate.admit(kvStore, List.of(new KVCommand.Put<>(STATE_KEY, STATE_VALUE))).isFailure()).isTrue();

        decide(BackupRestoreOutcome.UNKNOWN);
        assertThat(RestoreGate.isOpen(kvStore)).as("an outcome this node cannot name keeps it closed").isFalse();

        for (var terminal : List.of(BackupRestoreOutcome.RESTORED,
                                    BackupRestoreOutcome.FRESH,
                                    BackupRestoreOutcome.SKIPPED_EXISTING_STATE,
                                    BackupRestoreOutcome.DISABLED)) {
            decide(terminal);
            assertThat(RestoreGate.admit(kvStore, List.of(new KVCommand.Put<>(STATE_KEY, STATE_VALUE))).isSuccess()).as("%s opens the gate",
                                                                                                                       terminal)
                                                                                                                   .isTrue();
        }
    }

    private void decide(BackupRestoreOutcome outcome) {
        kvStore.processCommitted(kvStore.createBatch(List.of(new KVCommand.Put<>(BackupRestoreKey.backupRestoreKey(),
                                                                                 BackupRestoreValue.decided(outcome)))),
                                 ++slot);
    }

    private static KVCommand<AetherKey> transaction(String id) {
        return new KVCommand.LeaderTransaction<AetherKey, AetherValue>(STATE_KEY,
                                                                       id,
                                                                       LeaderValue.leaderValue(NodeId.nodeId("node-1")
                                                                                                     .unwrap(),
                                                                                               1L),
                                                                       List.of(),
                                                                       List.of(new KVCommand.Mutation<>(STATE_KEY,
                                                                                                        Option.none(),
                                                                                                        Option.some(STATE_VALUE))));
    }
}

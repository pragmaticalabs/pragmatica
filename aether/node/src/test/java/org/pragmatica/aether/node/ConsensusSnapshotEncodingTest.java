// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.StateMachine;
import org.pragmatica.consensus.rabia.ClusterConfig;
import org.pragmatica.consensus.rabia.Phase;
import org.pragmatica.consensus.rabia.RabiaPersistence;
import org.pragmatica.consensus.rabia.VoterConfiguration;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

class ConsensusSnapshotEncodingTest {
    private static final byte[] SNAPSHOT = new byte[]{0, 1, 2, -1, 42};

    @TempDir
    Path backupDir;

    @Test
    void persistedPhaseHeaderDoesNotPreventSnapshotRestoration() {
        var payload = Base64.getEncoder().encodeToString(SNAPSHOT);
        assertThat(AetherNode.base64ToSnapshot("# Phase: 123\n" + payload).unwrap()).containsExactly(SNAPSHOT);
        assertThat(AetherNode.base64ToSnapshot(payload).unwrap()).containsExactly(SNAPSHOT);
    }

    @Test
    void malformedHeaderAndPayloadRemainFailures() {
        assertThat(AetherNode.base64ToSnapshot("# Phase: unknown\nAA==").isFailure()).isTrue();
        assertThat(AetherNode.base64ToSnapshot("# Phase: 123\nnot base64!").isFailure()).isTrue();
        assertThat(AetherNode.base64ToSnapshot("# Voter: 0|x\nnot base64!").isFailure()).isTrue();
    }

    /// The configuration-carrying save (the only save the engine issues once voters are installed) writes
    /// a `# Voter:` header line BEFORE `# Phase:`. A `[backup]` node must read its own snapshot back
    /// through the real git-backed round trip, or every restore from backup fails to decode.
    @Test
    void voterHeaderedSnapshot_roundTripsThroughGitBackedBackup() {
        var persistence = RabiaPersistence.<KVCommand<AetherKey>>gitBacked(backupDir,
                                                                           Option.none(),
                                                                           AetherNode::snapshotToBase64,
                                                                           AetherNode::base64ToSnapshot);
        var voters = new VoterConfiguration(0, new ClusterConfig(List.of(new NodeId("node-1"),
                                                                         new NodeId("node-2"),
                                                                         new NodeId("node-3"))));

        assertThat(persistence.save(new FixedSnapshot(), Phase.phase(7), List.of(), voters)
                              .isSuccess()).isTrue();

        var loaded = persistence.load();

        assertThat(loaded.isPresent()).as("the voter-headered snapshot must decode").isTrue();
        assertThat(loaded.unwrap().snapshot()).containsExactly(SNAPSHOT);
        assertThat(loaded.unwrap().lastCommittedPhase()).isEqualTo(Phase.phase(7));
        assertThat(loaded.unwrap().configuration()).isEqualTo(Option.some(voters));
    }

    private static final class FixedSnapshot implements StateMachine<KVCommand<AetherKey>> {
        @Override
        public <R> List<R> process(Batch<KVCommand<AetherKey>> batch) {
            return List.of();
        }

        @Override
        public Result<byte[]> makeSnapshot() {
            return Result.success(SNAPSHOT.clone());
        }

        @Override
        public Result<Unit> restoreSnapshot(byte[] snapshot) {
            return Result.unitResult();
        }

        @Override
        public Unit reset() {
            return Unit.unit();
        }

        @Override
        public Serializer serializer() {
            return NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
        }
    }
}

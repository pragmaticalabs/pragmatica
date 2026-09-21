package org.pragmatica.consensus.rabia;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestCommand;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestStateMachine;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.SyncResponse;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.ConfigurationTransfer;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HandoffPreparationTest {
    private final List<NodeId> members = List.of(new NodeId("a"), new NodeId("b"), new NodeId("c"));
    private final VoterAuthority<TestCommand> authority = new VoterAuthority<>(new VoterConfiguration(0, new ClusterConfig(members)), Option.none());
    private final ClusterConfig target = new ClusterConfig(List.of(new NodeId("b"), new NodeId("c"), new NodeId("d")));

    @Test
    void refusesCheckpointWhenPostInstallSyncEnvelopeExceedsBound() {
        var result = HandoffPreparation.prepare(authority, target, new Phase(1), new byte[100], List.of(),
            message -> message instanceof SyncResponse<?> ? ReconfigurationError.STATE_TRANSFER_TOO_LARGE.result() : Result.success(Unit.unit()));
        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).isEqualTo(ReconfigurationError.STATE_TRANSFER_TOO_LARGE));
    }

    @Test
    void dropsOnlyUncommittedRecoveryHintsWhenEnvelopeWouldOverflow() {
        var pending = new ArrayList<Batch<TestCommand>>();
        pending.add(Batch.create(new TestStateMachine().serializer(), List.of(new TestCommand("pending"))));
        var result = HandoffPreparation.prepare(authority, target, new Phase(1), new byte[100], pending,
            message -> message instanceof ConfigurationTransfer<?> transfer && !transfer.handoff().pendingBatches().isEmpty()
                ? ReconfigurationError.STATE_TRANSFER_TOO_LARGE.result() : Result.success(Unit.unit()));
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.unwrap().pendingBatches()).isEmpty();
        assertThat(pending).hasSize(1);
    }

    @Test
    void capturesPendingHintsAtPreparedPrefix() {
        var pending = new ArrayList<Batch<TestCommand>>();
        pending.add(Batch.create(new TestStateMachine().serializer(), List.of(new TestCommand("first"))));
        var prepared = HandoffPreparation.prepare(authority, target, new Phase(1), new byte[100], pending,
            _ -> Result.success(Unit.unit())).unwrap();
        pending.add(Batch.create(new TestStateMachine().serializer(), List.of(new TestCommand("later"))));
        assertThat(prepared.pendingBatches()).hasSize(1);
        assertThat(prepared.nextSlot()).isEqualTo(new Phase(1));
    }
}

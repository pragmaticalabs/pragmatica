package org.pragmatica.consensus.rabia;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.StateMachine;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestCommand;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestStateMachine;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class BackupRabiaPersistenceTest {
    @Test void aBlockedOrFailedBackupDoesNotBlockDurableVotesAndPendingSnapshotsCoalesce() {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var completed = new CountDownLatch(2);
        var phases = new CopyOnWriteArrayList<Phase>();
        RabiaPersistence<TestCommand> backup = new RabiaPersistence<>() {
            @Override public Result<Unit> save(StateMachine<TestCommand> machine, Phase frontier,
                                               Collection<Batch<TestCommand>> pending) { return VotingJournalError.UNSUPPORTED.result(); }
            @Override public Option<SavedState<TestCommand>> load() { return Option.none(); }
            @Override public Result<Unit> saveSnapshot(SavedState<TestCommand> snapshot) {
                phases.add(snapshot.lastCommittedPhase());
                entered.countDown();
                var waited = Result.lift(Causes::fromThrowable, () -> release.await(5, TimeUnit.SECONDS));
                completed.countDown();
                return waited.flatMap(_ -> VotingJournalError.UNSUPPORTED.result());
            }
        };
        var persistence = RabiaPersistence.withBackup(RabiaPersistence.inMemory(), backup);
        var machine = new TestStateMachine();
        assertThat(persistence.save(machine, Phase.phase(1), List.of()).isSuccess()).isTrue();
        assertThat(Result.lift(Causes::fromThrowable, () -> entered.await(5, TimeUnit.SECONDS)).unwrap()).isTrue();
        assertThat(persistence.save(machine, Phase.phase(2), List.of()).isSuccess()).isTrue();
        assertThat(persistence.save(machine, Phase.phase(3), List.of()).isSuccess()).isTrue();
        var vote = new RabiaProtocolMessage.Synchronous.VoteRound1(new NodeId("core-1"), Phase.phase(3), StateValue.V0);
        assertThat(persistence.append(vote).isSuccess()).isTrue();
        assertThat(persistence.loadJournal().unwrap()).containsExactly(vote);
        release.countDown();
        assertThat(Result.lift(Causes::fromThrowable, () -> completed.await(5, TimeUnit.SECONDS)).unwrap()).isTrue();
        assertThat(phases).containsExactly(Phase.phase(1), Phase.phase(3));
        assertThat(persistence.lastBackupFailure().isPresent()).isTrue();
        assertThat(persistence.close().isSuccess()).isTrue();
    }
}

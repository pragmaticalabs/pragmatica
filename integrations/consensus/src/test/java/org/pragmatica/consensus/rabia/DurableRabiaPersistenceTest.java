package org.pragmatica.consensus.rabia;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestCommand;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestStateMachine;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.*;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.SliceCodec;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class DurableRabiaPersistenceTest {
    private static final NodeId SELF = new NodeId("node-1");
    private static final SliceCodec CODEC = SliceCodec.sliceCodec(
        TestSerializers.stringCommandSerializer(TestCommand.class, TestCommand::value, TestCommand::new),
        Stream.concat(ConsensusCodecs.CODECS.stream(), RabiaCodecs.CODECS.stream()).toList());
    @TempDir Path directory;
    private final List<RabiaPersistence<TestCommand>> opened = new ArrayList<>();

    @AfterEach void close() { opened.forEach(RabiaPersistence::close); }

    private RabiaPersistence<TestCommand> open() {
        var persistence = RabiaPersistence.<TestCommand>durable(directory, CODEC, CODEC).unwrap();
        opened.add(persistence);
        return persistence;
    }

    @Test void durablePromisesSurviveReopenAndConflictingBallotsAreRejected() {
        var persistence = open();
        var batch = Batch.create(CODEC, List.of(new TestCommand("value")));
        var proposal = new Propose<>(SELF, 0, Phase.ZERO, batch);
        var first = new VoteRound1(SELF, 0, Phase.ZERO, 0, StateValue.V1);
        var second = new VoteRound2(SELF, 0, Phase.ZERO, 0, StateValue.V1);
        for (var message : List.of(proposal, first, second)) { assertThat(persistence.append(message).isSuccess()).isTrue(); }
        var bytesBefore = Result.lift(Causes::fromThrowable, () -> Files.size(directory.resolve("voting.wal"))).unwrap();
        assertThat(persistence.append(first).isSuccess()).isTrue();
        assertThat(Result.lift(Causes::fromThrowable, () -> Files.size(directory.resolve("voting.wal"))).unwrap()).isEqualTo(bytesBefore);
        persistence.close();
        var restored = open();
        assertThat(restored.loadJournal().unwrap()).containsExactly(proposal, first, second);
        assertThat(restored.append(new VoteRound1(SELF, 0, Phase.ZERO, 0, StateValue.V0)).isFailure()).isTrue();
        assertThat(restored.loadJournal().unwrap()).containsExactly(proposal, first, second);
    }

    @Test void checkpointCompactsAppliedPrefixButRetainsTheOpenSlotsBallots() {
        var persistence = open();
        persistence.append(new Decision<>(SELF, 0, Phase.ZERO, StateValue.V0, Batch.emptyBatch()));
        var openVote = new VoteRound1(SELF, 0, Phase.phase(1), 0, StateValue.V0);
        persistence.append(openVote);
        var authority = new VoterAuthority<TestCommand>(new VoterConfiguration(0, new ClusterConfig(List.of(SELF))), Option.none());
        var saved = persistence.save(new TestStateMachine(), Phase.phase(1), List.of(), authority);
        assertThat(saved.isSuccess()).describedAs("%s", saved).isTrue();
        assertThat(Result.lift(Causes::fromThrowable, () -> Files.size(directory.resolve("voting.wal"))).unwrap()).isZero();
        persistence.close();
        var recovered = open();
        assertThat(recovered.load().unwrap().lastCommittedPhase()).isEqualTo(Phase.phase(1));
        assertThat(recovered.loadJournal().unwrap()).containsExactly(openVote);
        assertThat(recovered.append(new VoteRound2(SELF, 0, Phase.phase(1), 0, StateValue.VQUESTION)).isSuccess()).isTrue();
    }

    @Test void exclusiveDirectoryOwnershipPreventsTwoWriters() {
        var first = open();
        var duplicate = RabiaPersistence.<TestCommand>durable(directory, CODEC, CODEC);
        assertThat(duplicate.isFailure()).isTrue();
        duplicate.onFailure(cause -> assertThat(cause).isEqualTo(VotingJournalError.IN_USE));
        assertThat(first.append(new VoteRound1(SELF, Phase.ZERO, StateValue.V0)).isSuccess()).isTrue();
    }

    @Test void tornTailFailsWithoutTruncatingTheEvidence() {
        var persistence = open();
        persistence.append(new VoteRound1(SELF, Phase.ZERO, StateValue.V0));
        persistence.close();
        var path = directory.resolve("voting.wal");
        var bytes = Result.lift(Causes::fromThrowable, () -> Files.readAllBytes(path)).unwrap();
        var torn = Arrays.copyOf(bytes, bytes.length - 1);
        Result.lift(Causes::fromThrowable, () -> Files.write(path, torn)).unwrap();
        var reopened = RabiaPersistence.<TestCommand>durable(directory, CODEC, CODEC);
        assertThat(reopened.isFailure()).isTrue();
        reopened.onFailure(cause -> assertThat(cause).isEqualTo(VotingJournalError.TORN_TAIL));
        assertThat(Result.lift(Causes::fromThrowable, () -> Files.readAllBytes(path)).unwrap()).isEqualTo(torn);
    }

    @Test void checksumCorruptionFailsClosed() {
        var persistence = open();
        persistence.append(new VoteRound1(SELF, Phase.ZERO, StateValue.V0));
        persistence.close();
        var path = directory.resolve("voting.wal");
        var bytes = Result.lift(Causes::fromThrowable, () -> Files.readAllBytes(path)).unwrap();
        bytes[bytes.length - 1] ^= 1;
        Result.lift(Causes::fromThrowable, () -> Files.write(path, bytes)).unwrap();
        var reopened = RabiaPersistence.<TestCommand>durable(directory, CODEC, CODEC);
        assertThat(reopened.isFailure()).isTrue();
        reopened.onFailure(cause -> assertThat(cause).isEqualTo(VotingJournalError.CORRUPT));
    }

    @Test void checkpointPublishedBeforeWalReplacementCanRecoverCoveredOldFrames() {
        var persistence = open();
        assertThat(persistence.append(new Decision<>(SELF, 0, Phase.ZERO, StateValue.V0, Batch.emptyBatch())).isSuccess()).isTrue();
        var vote = new VoteRound1(SELF, 0, Phase.phase(1), 0, StateValue.V0);
        assertThat(persistence.append(vote).isSuccess()).isTrue();
        var path = directory.resolve("voting.wal");
        var oldWal = Result.lift(Causes::fromThrowable, () -> Files.readAllBytes(path)).unwrap();
        assertThat(persistence.save(new TestStateMachine(), Phase.phase(1), List.of()).isSuccess()).isTrue();
        persistence.close();
        // Represents a power loss after the new checkpoint rename but before WAL replacement.
        Result.lift(Causes::fromThrowable, () -> Files.write(path, oldWal)).unwrap();
        var recovered = open();
        assertThat(recovered.loadJournal().unwrap()).containsExactly(vote);
        var second = new VoteRound2(SELF, 0, Phase.phase(1), 0, StateValue.V0);
        assertThat(recovered.append(second).isSuccess()).isTrue();
        recovered.close();
        assertThat(open().loadJournal().unwrap()).containsExactly(vote, second);
    }

    @Test void removingAWholeValidFrameStillFailsTheSequenceCheck() {
        var persistence = open();
        assertThat(persistence.append(new VoteRound1(SELF, Phase.ZERO, StateValue.V0)).isSuccess()).isTrue();
        var path = directory.resolve("voting.wal");
        var firstLength = Result.lift(Causes::fromThrowable, () -> Files.size(path)).unwrap().intValue();
        assertThat(persistence.append(new VoteRound2(SELF, Phase.ZERO, StateValue.V0)).isSuccess()).isTrue();
        persistence.close();
        var bytes = Result.lift(Causes::fromThrowable, () -> Files.readAllBytes(path)).unwrap();
        Result.lift(Causes::fromThrowable, () -> Files.write(path, Arrays.copyOfRange(bytes, firstLength, bytes.length))).unwrap();
        var reopened = RabiaPersistence.<TestCommand>durable(directory, CODEC, CODEC);
        assertThat(reopened.isFailure()).isTrue();
        reopened.onFailure(cause -> assertThat(cause).isEqualTo(VotingJournalError.GAP));
    }

    @Test void reportLocalAppendFsyncLatencyWithoutPretendingItIsAClusterBenchmark() {
        var persistence = open();
        var samples = new long[64];
        for (int index = 0; index < samples.length; index++) {
            var started = System.nanoTime();
            assertThat(persistence.append(new Decision<>(SELF, 0, Phase.phase(index), StateValue.V0, Batch.emptyBatch())).isSuccess()).isTrue();
            samples[index] = System.nanoTime() - started;
        }
        Arrays.sort(samples);
        System.out.printf("Local WAL append+fsync, temp directory %s: n=64 median=%.3f ms p95=%.3f ms%n",
            directory, samples[32] / 1_000_000.0, samples[60] / 1_000_000.0);
    }
    @Test void everyDurabilityFailurePoisonsWriterAndLeavesRecoverableFileCombination() throws Exception {
        for (var step : DurableRabiaPersistence.DurabilityStep.values()) {
            var location = directory.resolve(step.name());
            var armed = new java.util.concurrent.atomic.AtomicBoolean(false);
            var persistence = DurableRabiaPersistence.<TestCommand>open(location, CODEC, CODEC,
                observed -> armed.get() && observed == step ? VotingJournalError.CAPACITY.result()
                                                          : Result.success(org.pragmatica.lang.Unit.unit())).unwrap();
            opened.add(persistence);
            var vote = new VoteRound1(SELF, 0, Phase.ZERO, 0, StateValue.V0);
            var appendBoundary = step == DurableRabiaPersistence.DurabilityStep.APPEND_FORCE
                                 || step == DurableRabiaPersistence.DurabilityStep.APPEND_DIRECTORY_FORCE;
            if (!appendBoundary) {
                assertThat(persistence.append(vote).isSuccess()).isTrue();
            }
            armed.set(true);
            var result = appendBoundary ? persistence.append(vote)
                                        : persistence.save(new TestStateMachine(), Phase.ZERO, List.of());
            assertThat(result.isFailure()).as("failure at %s must not report success", step).isTrue();
            assertThat(persistence.append(new VoteRound2(SELF, 0, Phase.ZERO, 0, StateValue.V0)).isFailure())
                .as("writer poisoned at %s", step).isTrue();
            persistence.close();
            var recovered = DurableRabiaPersistence.<TestCommand>open(location, CODEC, CODEC).unwrap();
            opened.add(recovered);
            assertThat(recovered.loadJournal().unwrap()).as("durable vote survives %s", step).containsExactly(vote);
            recovered.close();
        }
    }

    @Test void appendAcknowledgmentWaitsForForceBoundary() throws Exception {
        var reached = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var persistence = DurableRabiaPersistence.<TestCommand>open(directory, CODEC, CODEC, step -> {
            if (step != DurableRabiaPersistence.DurabilityStep.APPEND_FORCE) {
                return Result.success(org.pragmatica.lang.Unit.unit());
            }
            reached.countDown();
            return Result.lift(Causes::fromThrowable, () -> release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                         .mapToUnit();
        }).unwrap();
        opened.add(persistence);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var append = executor.submit(() -> persistence.append(new VoteRound1(SELF, Phase.ZERO, StateValue.V0)));
            try {
                assertThat(reached.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(append.isDone()).isFalse();
            } finally {
                release.countDown();
            }
            assertThat(append.get(2, java.util.concurrent.TimeUnit.SECONDS).isSuccess()).isTrue();
        }
    }

    @Test void versionOneHeaderAndCrc32cHaveFixedGoldenBytes() throws Exception {
        var serializer = new org.pragmatica.serialization.Serializer() {
            @Override public <T> void write(io.netty.buffer.ByteBuf buffer, T value) {
                buffer.writeBytes("123456789".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }
        };
        var persistence = DurableRabiaPersistence.<TestCommand>open(directory, serializer, CODEC).unwrap();
        opened.add(persistence);
        assertThat(persistence.append(new VoteRound1(SELF, Phase.ZERO, StateValue.V0)).isSuccess()).isTrue();
        assertThat(java.util.HexFormat.of().formatHex(Files.readAllBytes(directory.resolve("voting.wal"))))
            .isEqualTo("524142310000000100000009e3069283313233343536373839");
    }

    @Test void unknownFormatVersionFailsWithoutRewritingEvidence() throws Exception {
        var persistence = open();
        persistence.append(new VoteRound1(SELF, Phase.ZERO, StateValue.V0)).unwrap();
        persistence.close();
        var file = directory.resolve("voting.wal");
        var bytes = Files.readAllBytes(file);
        java.nio.ByteBuffer.wrap(bytes).putInt(4, 2);
        Files.write(file, bytes);
        assertThat(DurableRabiaPersistence.<TestCommand>open(directory, CODEC, CODEC).isFailure()).isTrue();
        assertThat(Files.readAllBytes(file)).isEqualTo(bytes);
    }

}

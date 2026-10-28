// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// F4: the ledger write is atomic. `ApplyLedger.tempWriter` is handed the TEMP path; a writer that writes half the
/// bytes and dies there stands for a full disk or a kill mid-write. The previous ledger must survive it.
class ApplyStateDurabilityTest {
    private static final ClusterName CLUSTER = ClusterName.clusterName("durability-test").unwrap();

    @TempDir
    Path stateRoot;

    private Function<ClusterName, Path> originalResolver;
    private ApplyLedger.TempWriter originalWriter;

    @BeforeEach
    void redirect() {
        originalResolver = ApplyState.stateDirResolver;
        originalWriter = ApplyLedger.tempWriter;
        ApplyState.stateDirResolver = name -> stateRoot.resolve(name.value());
    }

    @AfterEach
    void restore() {
        ApplyState.stateDirResolver = originalResolver;
        ApplyLedger.tempWriter = originalWriter;
        ApplyLedger.trace = _ -> {};
    }

    private Path ledgerFile() {
        return stateRoot.resolve(CLUSTER.value()).resolve("apply-state.json");
    }

    /// Writes the first half of the content to the path it is handed, then dies.
    private static Unit halfWriteThenDie(Path target, byte[] content) throws Exception {
        Files.write(target, java.util.Arrays.copyOf(content, content.length / 2), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        throw new java.io.IOException("No space left on device (test cause)");
    }

    @Test
    void save_failureMidWrite_leavesThePreviousFileReadable() throws Exception {
        var first = ApplyStateFixtures.state(CLUSTER);

        ApplyState.save(first).onFailure(cause -> fail(cause.message()));

        var before = Files.readString(ledgerFile());

        ApplyLedger.tempWriter = ApplyStateDurabilityTest::halfWriteThenDie;

        ApplyState.save(first.withPhase(ApplyPhase.COMMITTING))
                  .onSuccess(_ -> fail("a write that died mid-way must be reported as a failure"));

        assertThat(Files.readString(ledgerFile())).as("the previous ledger is byte-identical").isEqualTo(before);
        ApplyState.read(CLUSTER)
                  .onFailure(cause -> fail("the previous ledger must stay readable: " + cause.message()))
                  .onSuccess(read -> assertThat(read.unwrap()).isEqualTo(first));
    }

    @Test
    void save_failure_removesItsOwnTempFile_andKeepsThePreviousLedger() throws Exception {
        ApplyState.save(ApplyStateFixtures.state(CLUSTER)).onFailure(cause -> fail(cause.message()));
        ApplyLedger.tempWriter = ApplyStateDurabilityTest::halfWriteThenDie;

        ApplyState.save(ApplyStateFixtures.state(CLUSTER).withPhase(ApplyPhase.COMMITTING)).onSuccess(_ -> fail("must fail"));

        assertThat(Files.list(ledgerFile().getParent()).map(p -> p.getFileName().toString()).toList()).containsExactly("apply-state.json");
    }

    @Test
    void save_forcesTheFileBeforeTheAtomicMove_andTheDirectoryAfterIt() {
        var events = new java.util.ArrayList<String>();

        ApplyLedger.trace = events::add;
        ApplyState.save(ApplyStateFixtures.state(CLUSTER)).onFailure(cause -> fail(cause.message()));

        var expected = System.getProperty("os.name", "").toLowerCase().contains("win")
                       ? List.of("force-file:metadata=true", "move:[ATOMIC_MOVE]")
                       : List.of("force-file:metadata=true", "move:[ATOMIC_MOVE]", "force-directory:metadata=true");

        assertThat(events).containsExactlyElementsOf(expected);
    }

    @Test
    void writeFully_keepsWritingUntilTheBufferIsEmpty_whenTheChannelTakesOneByteAtATime() throws Exception {
        var sink = new java.io.ByteArrayOutputStream();
        var content = "0123456789".getBytes(StandardCharsets.UTF_8);
        java.nio.channels.WritableByteChannel oneByte = new java.nio.channels.WritableByteChannel() {
            public int write(java.nio.ByteBuffer src) {
                sink.write(src.get());
                return 1;
            }

            public boolean isOpen() {
                return true;
            }

            public void close() {}
        };

        ApplyLedger.writeFully(oneByte, java.nio.ByteBuffer.wrap(content));

        assertThat(sink.toByteArray()).isEqualTo(content);
    }

    @Test
    void writeFully_propagatesAFailureOfALaterWrite_soAShortWriteIsNeverRenamedIntoPlace() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        java.nio.channels.WritableByteChannel failsOnSecond = new java.nio.channels.WritableByteChannel() {
            public int write(java.nio.ByteBuffer src) throws java.io.IOException {
                if (calls.incrementAndGet() > 1) {
                    throw new java.io.IOException("No space left on device (test cause)");
                }
                src.get();
                return 1;
            }

            public boolean isOpen() {
                return true;
            }

            public void close() {}
        };

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ApplyLedger.writeFully(failsOnSecond, java.nio.ByteBuffer.wrap(new byte[] {1, 2, 3})))
                                       .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void delete_absentLedger_isSuccess_presentLedger_isRemoved() {
        ApplyState.delete(CLUSTER).onFailure(cause -> fail("absent is not a failure: " + cause.message()));

        ApplyState.save(ApplyStateFixtures.state(CLUSTER)).onFailure(cause -> fail(cause.message()));
        ApplyState.delete(CLUSTER).onFailure(cause -> fail(cause.message()));

        assertThat(ledgerFile()).doesNotExist();
    }

    /// B1: concurrent writers (two CLI processes, a future beat thread) must neither fail nor tear the ledger.
    @Test
    void save_concurrentWriters_neverFail_andAReaderNeverSeesAnUnreadableLedger() throws Exception {
        var one = ApplyStateFixtures.state(CLUSTER);
        var two = one.withPhase(ApplyPhase.COMMITTING);
        var failedWrites = new java.util.concurrent.atomic.AtomicInteger();
        var unreadable = new java.util.concurrent.atomic.AtomicInteger();
        var writersDone = new java.util.concurrent.atomic.AtomicBoolean();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(5);

        ApplyState.save(one).onFailure(cause -> fail(cause.message()));

        var reader = pool.submit(() -> {
            while (!writersDone.get()) {
                ApplyState.read(CLUSTER).onFailure(_ -> unreadable.incrementAndGet());
            }
            return Unit.unit();
        });
        var writers = new java.util.ArrayList<java.util.concurrent.Future<Unit>>();

        for (int w = 0; w < 4; w++) {
            var state = w % 2 == 0 ? one : two;

            writers.add(pool.submit(() -> {
                for (int i = 0; i < 150; i++) {
                    ApplyState.save(state).onFailure(_ -> failedWrites.incrementAndGet());
                }
                return Unit.unit();
            }));
        }

        for (var writer : writers) {
            writer.get();
        }

        writersDone.set(true);
        reader.get();
        pool.shutdown();

        assertThat(failedWrites.get()).as("failed writes").isZero();
        assertThat(unreadable.get()).as("unreadable observations").isZero();
        ApplyState.read(CLUSTER).onFailure(cause -> fail(cause.message()));
        assertThat(Files.list(ledgerFile().getParent()).count()).as("no temp file left behind").isEqualTo(1);
    }

    @Test
    void save_failureOnTheVeryFirstWrite_leavesNoReadableLedgerAndNoLedgerFile() {
        ApplyLedger.tempWriter = ApplyStateDurabilityTest::halfWriteThenDie;

        ApplyState.save(ApplyStateFixtures.state(CLUSTER)).onSuccess(_ -> fail("must fail"));

        assertThat(ledgerFile()).as("a torn first write is never promoted to the ledger name").doesNotExist();
        ApplyState.read(CLUSTER).onFailure(cause -> fail("absent, not unreadable: " + cause.message()));
    }

    @Test
    void save_success_replacesTheFileAndLeavesNoTempFile() throws Exception {
        var first = ApplyStateFixtures.state(CLUSTER);
        var second = first.withPhase(ApplyPhase.COMMITTING);

        ApplyState.save(first).onFailure(cause -> fail(cause.message()));
        ApplyState.save(second).onFailure(cause -> fail(cause.message()));

        assertThat(ApplyState.read(CLUSTER).unwrap().unwrap()).isEqualTo(second);
        assertThat(Files.list(ledgerFile().getParent()).map(p -> p.getFileName().toString()).toList())
                .containsExactly("apply-state.json");
        assertThat(new String(Files.readAllBytes(ledgerFile()), StandardCharsets.UTF_8)).isEqualTo(ApplyState.toJson(second));
    }

    @Test
    void save_underAPathThatCannotBeCreated_isAFailureNotAnException() throws Exception {
        var blocker = Files.writeString(stateRoot.resolve("blocker"), "i am a file");

        ApplyState.stateDirResolver = name -> blocker.resolve(name.value());

        ApplyState.save(ApplyStateFixtures.state(CLUSTER)).onSuccess(_ -> fail("must fail"));
    }
}

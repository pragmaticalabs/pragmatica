/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.consensus.net;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.IdentityLedger.DurableWriter;
import org.pragmatica.consensus.net.IdentityLedger.Origin;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// The identity ledger (#2429): write-ahead order, loss states, coalescing, and a writer that fails or stalls.
class IdentityLedgerTest {
    private static final TimeSpan BOUND = TimeSpan.timeSpan(10).seconds();
    private static final Cause DISK_FULL = Causes.cause("disk full");

    @TempDir
    Path directory;

    private static NodeId node(String id) {
        return new NodeId(id);
    }

    private Path ledgerFile() {
        return directory.resolve("identity").resolve("n1").resolve("ledger");
    }

    /// Records the three durable steps in call order and can fail or hold any of them.
    private static final class RecordingWriter implements DurableWriter {
        final List<String> steps = Collections.synchronizedList(new ArrayList<>());
        final DurableWriter real = DurableWriter.fileOps();
        volatile CountDownLatch holdDirectoryForce = new CountDownLatch(0);
        final AtomicBoolean failWrites = new AtomicBoolean();
        final AtomicInteger writes = new AtomicInteger();

        @Override
        public Result<Unit> writeForced(Path file, byte[] content) {
            steps.add("F1");
            writes.incrementAndGet();

            return failWrites.get()
                   ? DISK_FULL.result()
                   : real.writeForced(file, content);
        }

        @Override
        public Result<Unit> moveAtomic(Path source, Path target) {
            steps.add("F2");

            return real.moveAtomic(source, target);
        }

        @Override
        public Result<Unit> forceDirectory(Path dir) {
            steps.add("F3");
            await(holdDirectoryForce);

            return real.forceDirectory(dir);
        }

        private static void await(CountDownLatch latch) {
            try {
                latch.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    void open_loadsWhatWasRecorded_afterANewInstanceReadsTheFile() {
        var first = IdentityLedger.identityLedger(ledgerFile(), DurableWriter.fileOps());

        first.open(true);
        first.record(IdentityFacts.terminated(node("a"), "TOKEN_CONFLICT").merge(IdentityFacts.terminated(node("b"), "TOKEN_CONFLICT"))).await(BOUND);

        var second = IdentityLedger.identityLedger(ledgerFile(), DurableWriter.fileOps()).open(false);

        assertThat(second.origin()).isEqualTo(Origin.LOADED);
        assertThat(second.facts().isTerminated(node("a"))).isTrue();
        assertThat(second.facts().isTerminated(node("b"))).isTrue();
    }

    @Test
    void open_aMissingFileOnANewNode_isCreated_andWritesAnEmptyLedgerAtOnce() {
        var loading = IdentityLedger.identityLedger(ledgerFile(), DurableWriter.fileOps()).open(true);

        assertThat(loading.origin()).isEqualTo(Origin.CREATED);
        assertThat(Files.isRegularFile(ledgerFile())).as("a later restart must find a file").isTrue();
        assertThat(IdentityLedger.identityLedger(ledgerFile(), DurableWriter.fileOps()).open(false).origin()).isEqualTo(Origin.LOADED);
    }

    @Test
    void open_aMissingFileOnANodeThatIsNotNew_isWiped_andStaysUnwrittenAcrossRestarts() {
        var loading = IdentityLedger.identityLedger(ledgerFile(), DurableWriter.fileOps()).open(false);

        assertThat(loading.origin()).isEqualTo(Origin.WIPED);
        assertThat(loading.unwritable().isEmpty()).as("the directory is writable").isTrue();
        assertThat(Files.exists(ledgerFile())).as("a wiped ledger is not rewritten empty").isFalse();
        assertThat(IdentityLedger.identityLedger(ledgerFile(), DurableWriter.fileOps()).open(false).origin()).isEqualTo(Origin.WIPED);
    }

    @Test
    void open_anUnparseableFile_isWipedNotEmpty() throws Exception {
        Files.createDirectories(ledgerFile().getParent());
        Files.writeString(ledgerFile(), "garbage that is not a ledger", StandardCharsets.UTF_8);

        assertThat(IdentityLedger.identityLedger(ledgerFile(), DurableWriter.fileOps()).open(false).origin()).isEqualTo(Origin.WIPED);
    }

    @Test
    void open_aTornTempFileBesideTheLedger_isIgnored() throws Exception {
        var ledger = IdentityLedger.identityLedger(ledgerFile(), DurableWriter.fileOps());

        ledger.open(true);
        ledger.record(IdentityFacts.terminated(node("a"), "TOKEN_CONFLICT")).await(BOUND);
        Files.writeString(ledgerFile().resolveSibling(".identity-ledger-1.tmp"), "torn", StandardCharsets.UTF_8);

        var reopened = IdentityLedger.identityLedger(ledgerFile(), DurableWriter.fileOps()).open(false);

        assertThat(reopened.origin()).isEqualTo(Origin.LOADED);
        assertThat(reopened.facts().isTerminated(node("a"))).isTrue();
    }

    @Test
    void record_forcesTheFileThenRenamesThenForcesTheDirectory_andCompletesOnlyAfterTheLast() throws Exception {
        var writer = new RecordingWriter();
        var ledger = IdentityLedger.identityLedger(ledgerFile(), writer);

        ledger.open(true);
        writer.steps.clear();
        writer.holdDirectoryForce = new CountDownLatch(1);

        var pending = ledger.record(IdentityFacts.terminated(node("a"), "TOKEN_CONFLICT"));

        awaitStep(writer, "F3");
        assertThat(writer.steps).as("F1 then F2 then F3").containsExactly("F1", "F2", "F3");
        assertThat(pending.isResolved()).as("not durable before the directory force returns").isFalse();

        writer.holdDirectoryForce.countDown();

        assertThat(pending.await(BOUND).isSuccess()).isTrue();
    }

    @Test
    void record_neverBlocksItsCaller_whileTheWriterIsStalled() {
        var writer = new RecordingWriter();
        var ledger = IdentityLedger.identityLedger(ledgerFile(), writer);

        ledger.open(true);
        writer.holdDirectoryForce = new CountDownLatch(1);

        var started = System.nanoTime();
        var pending = ledger.record(IdentityFacts.terminated(node("a"), "TOKEN_CONFLICT"));
        var elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(elapsedMs).as("record returned without waiting for the disk").isLessThan(2_000L);
        assertThat(pending.isResolved()).isFalse();

        writer.holdDirectoryForce.countDown();
        pending.await(BOUND);
    }

    @Test
    void record_ofAFactTheFileAlreadyHolds_completesAtOnce_withoutAWrite() {
        var writer = new RecordingWriter();
        var ledger = IdentityLedger.identityLedger(ledgerFile(), writer);

        ledger.open(true);
        ledger.record(IdentityFacts.terminated(node("a"), "TOKEN_CONFLICT")).await(BOUND);
        var writesBefore = writer.writes.get();

        var again = ledger.record(IdentityFacts.terminated(node("a"), "TOKEN_CONFLICT"));

        assertThat(again.isResolved()).isTrue();
        assertThat(writer.writes.get()).isEqualTo(writesBefore);
    }

    @Test
    void record_coalescesRequestsQueuedBehindAStalledWrite_intoOneFollowingWrite() throws Exception {
        var writer = new RecordingWriter();
        var ledger = IdentityLedger.identityLedger(ledgerFile(), writer);

        ledger.open(true);
        writer.holdDirectoryForce = new CountDownLatch(1);
        writer.writes.set(0);

        var first = ledger.record(IdentityFacts.terminated(node("a"), "TOKEN_CONFLICT"));

        awaitStep(writer, "F3");
        var queued = List.of(ledger.record(IdentityFacts.terminated(node("b"), "TOKEN_CONFLICT")),
                             ledger.record(IdentityFacts.terminated(node("c"), "TOKEN_CONFLICT")),
                             ledger.record(IdentityFacts.terminated(node("d"), "TOKEN_CONFLICT")));

        writer.holdDirectoryForce.countDown();
        first.await(BOUND);
        queued.forEach(pending -> assertThat(pending.await(BOUND).isSuccess()).isTrue());

        assertThat(writer.writes.get()).as("one write for the first, one for the three queued behind it").isEqualTo(2);
        assertThat(IdentityLedger.identityLedger(ledgerFile(), DurableWriter.fileOps()).open(false).facts().retired())
            .containsExactlyInAnyOrder(node("a"), node("b"), node("c"), node("d"));
    }

    @Test
    void record_afterAFailedWrite_failsThePromise_reportsNotDurable_andReportsRecoveryOnTheNextSuccess() {
        var writer = new RecordingWriter();
        var ledger = IdentityLedger.identityLedger(ledgerFile(), writer);
        var changes = Collections.synchronizedList(new ArrayList<Option<Cause>>());

        ledger.open(true);
        ledger.onDurabilityChange(changes::add);
        writer.failWrites.set(true);

        var failed = ledger.record(IdentityFacts.terminated(node("a"), "TOKEN_CONFLICT")).await(BOUND);

        assertThat(failed.isFailure()).isTrue();
        assertThat(ledger.durable()).isFalse();

        writer.failWrites.set(false);
        var healed = ledger.record(IdentityFacts.terminated(node("b"), "TOKEN_CONFLICT")).await(BOUND);

        assertThat(healed.isSuccess()).isTrue();
        assertThat(ledger.durable()).isTrue();
        assertThat(changes).hasSize(2);
        assertThat(changes.get(0).isPresent()).isTrue();
        assertThat(changes.get(1).isEmpty()).isTrue();
    }

    @Test
    void open_onADirectoryThatCannotBeWritten_isCreatedButUnwritable_andNotDurable() throws Exception {
        var blocker = directory.resolve("blocker");

        Files.writeString(blocker, "a file where a directory is needed", StandardCharsets.UTF_8);

        var ledger = IdentityLedger.identityLedger(blocker.resolve("identity").resolve("ledger"), DurableWriter.fileOps());
        var loading = ledger.open(true);

        assertThat(loading.unwritable().isPresent()).isTrue();
        assertThat(ledger.durable()).isFalse();
    }

    private static void awaitStep(RecordingWriter writer, String step) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (!writer.steps.contains(step) && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }

        assertThat(writer.steps).contains(step);
    }
}

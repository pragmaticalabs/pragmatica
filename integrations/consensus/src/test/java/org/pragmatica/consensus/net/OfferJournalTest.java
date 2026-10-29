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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.IdentityLedger.DurableWriter;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// The offers a wiped core keeps beside its ledger (#2429, ID-1c): durable like the ledger (F1-F3), read whole or not at all, and
/// LOUD when it cannot be used.
class OfferJournalTest {
    private static final TimeSpan BOUND = TimeSpan.timeSpan(10).seconds();
    private static final NodeId SEAT_A = new NodeId("seat-a");
    private static final NodeId SEAT_C = new NodeId("seat-c");
    private static final IdentityFacts RETIRES_X = IdentityFacts.terminated(new NodeId("x"), "TOKEN_CONFLICT");
    private static final IdentityFacts RETIRES_Y = IdentityFacts.terminated(new NodeId("y"), "TOKEN_CONFLICT");

    @TempDir
    Path directory;

    private final List<Option<Cause>> health = Collections.synchronizedList(new ArrayList<>());

    private Path file() {
        return directory.resolve("ledger.offers");
    }

    private OfferJournal journal(DurableWriter writer) {
        return OfferJournal.offerJournal(file(), writer, health::add);
    }

    /// Records the order of the three durable steps; the directory force can be held.
    private static final class RecordingWriter implements DurableWriter {
        final DurableWriter real = DurableWriter.fileOps();
        final List<String> steps = Collections.synchronizedList(new ArrayList<>());
        final AtomicBoolean failing = new AtomicBoolean();
        volatile CountDownLatch gate = new CountDownLatch(0);

        @Override
        public Result<Unit> writeForced(Path file, byte[] content) {
            steps.add("F1");

            return failing.get()
                   ? Causes.cause("disk full").result()
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
            hold();

            return real.forceDirectory(dir);
        }

        private void hold() {
            try {
                gate.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    void record_thenLoad_roundTripsEveryOffer_andARepeatedSeatIsUnioned() {
        var written = journal(DurableWriter.fileOps());

        assertThat(written.record(SEAT_A, RETIRES_X).await(BOUND).isSuccess()).isTrue();
        assertThat(written.record(SEAT_C, IdentityFacts.identityFacts()).await(BOUND).isSuccess()).isTrue();
        assertThat(written.record(SEAT_A, RETIRES_Y).await(BOUND).isSuccess()).isTrue();

        var loaded = journal(DurableWriter.fileOps()).load();

        assertThat(loaded.keySet()).containsExactlyInAnyOrder(SEAT_A, SEAT_C);
        assertThat(loaded.get(SEAT_A).retired()).as("repeated offers of one seat are unioned, not replaced").containsExactlyInAnyOrder(new NodeId("x"), new NodeId("y"));
        assertThat(health).as("a journal that works says nothing").isEmpty();
    }

    @Test
    void load_aMissingFile_isNoOffers_andSilent() {
        assertThat(journal(DurableWriter.fileOps()).load()).isEmpty();
        assertThat(health).isEmpty();
    }

    @Test
    void load_aGarbageFile_isNoOffers_butLoud() throws Exception {
        Files.writeString(file(), "this is not a journal");

        assertThat(journal(DurableWriter.fileOps()).load()).isEmpty();
        assertThat(health).singleElement().satisfies(change -> assertThat(change.isPresent()).isTrue());
    }

    @Test
    void load_aTamperedBody_isRefusedByTheDigest_andLoud() throws Exception {
        journal(DurableWriter.fileOps()).record(SEAT_A, RETIRES_X).await(BOUND);
        Files.writeString(file(), Files.readString(file()).replace("seat-a", "seat-b"));

        assertThat(journal(DurableWriter.fileOps()).load()).isEmpty();
        assertThat(health).hasSize(1);
    }

    @Test
    void load_aWrongHeaderWithAValidDigest_isRefusedByTheHeader_andLoud() throws Exception {
        var body = "aether-identity-offers-v0\nO\tseat-a\nT\tx\tTOKEN_CONFLICT\n";

        Files.writeString(file(), body + "D\t" + IdentityFacts.digestOf(body) + "\n");

        assertThat(journal(DurableWriter.fileOps()).load()).isEmpty();
        assertThat(health).hasSize(1);
    }

    @Test
    void record_runsTheThreeDurableStepsInOrder_andCompletesOnlyAfterTheDirectoryForce() throws Exception {
        var writer = new RecordingWriter();
        var journal = journal(writer);

        writer.gate = new CountDownLatch(1);
        var pending = journal.record(SEAT_A, RETIRES_X);

        for (var attempt = 0; attempt < 200 && !writer.steps.contains("F3"); attempt++) {
            Thread.sleep(25);
        }

        assertThat(writer.steps).containsExactly("F1", "F2", "F3");
        assertThat(pending.isResolved()).as("held at F3: not durable yet").isFalse();

        writer.gate.countDown();

        assertThat(pending.await(BOUND).isSuccess()).isTrue();
    }

    @Test
    void aFailingWrite_failsTheRecord_andIsReportedOnce_thenRecoveryIsReported() {
        var writer = new RecordingWriter();
        var journal = journal(writer);

        writer.failing.set(true);

        assertThat(journal.record(SEAT_A, RETIRES_X).await(BOUND).isFailure()).isTrue();
        assertThat(journal.record(SEAT_C, RETIRES_X).await(BOUND).isFailure()).isTrue();
        assertThat(health).as("one report for the failure, not one per write").hasSize(1);
        assertThat(health.get(0).isPresent()).isTrue();

        writer.failing.set(false);

        assertThat(journal.record(SEAT_C, RETIRES_X).await(BOUND).isSuccess()).isTrue();
        assertThat(health).hasSize(2);
        assertThat(health.get(1).isEmpty()).as("and the recovery").isTrue();
    }

    @Test
    void aJournalOfALedger_reportsAFailedWriteThroughTheLedgersDurability() {
        var writer = new RecordingWriter();
        var ledger = IdentityLedger.identityLedger(directory.resolve("ledger"), writer);
        var told = Collections.synchronizedList(new ArrayList<Option<Cause>>());

        ledger.onDurabilityChange(told::add);
        writer.failing.set(true);
        ledger.offers().record(SEAT_A, RETIRES_X).await(BOUND);

        assertThat(ledger.durable()).as("the operator-facing flag").isFalse();
        assertThat(told).singleElement().satisfies(change -> assertThat(change.isPresent()).isTrue());
    }
}

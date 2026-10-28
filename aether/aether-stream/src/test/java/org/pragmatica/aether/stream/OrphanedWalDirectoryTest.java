// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.lang.Option;
import org.pragmatica.storage.AppendLog;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManagerOverLogs;

/// Know 3b01e624f1: a WAL directory `<stream>@<incarnation>` whose incarnation the restored cluster state does not know is never silent. The
/// stream adopts the committed life; the directory of another life stays untouched and is raised once, naming the stream, the orphaned
/// incarnation and the adopted one.
class OrphanedWalDirectoryTest {
    private static final String STREAM = "orders";
    private static final long ORPHANED_LIFE = 111L;
    private static final long ADOPTED_LIFE = 222L;

    @TempDir
    Path walDir;

    private final List<OperatorWarning> warnings = new CopyOnWriteArrayList<>();

    @Test
    void adoptingALife_raisesTheOrphanedDirectoryOfAnotherLife_namingBothIncarnations_andKeepsTheDirectory() throws IOException {
        var orphan = walDirectory(STREAM + "@" + ORPHANED_LIFE);
        Files.createFile(orphan.resolve("1.wal"));
        var node = manager();

        node.onStreamConfigPut(committed(config().withIncarnation(ADOPTED_LIFE)));
        awaitWarnings(1);
        node.close();

        quiesce();
        assertThat(warnings).as("one event for the directory, however many partitions it holds").hasSize(1);
        assertThat(warnings.getFirst().code()).isEqualTo(OperatorWarningCode.STORAGE_STREAM_WAL_ORPHANED);
        assertThat(warnings.getFirst().subject()).isEqualTo(STREAM + "@" + ORPHANED_LIFE);
        assertThat(warnings.getFirst().message()).contains("Stream " + STREAM, "of incarnation " + ORPHANED_LIFE, "runs as incarnation " + ADOPTED_LIFE,
                                                                "0 sealed segment(s)", "WAL directory present: true", "Keeping it is the safe default", "There is no CLI or API verb to release it",
                                                                "<walPath of the streams storage instance>/" + STREAM + "@" + ORPHANED_LIFE,
                                                                "NEVER touch the directory of the committed incarnation " + ADOPTED_LIFE,
                                                                "repeats on every boot while the life has sealed segments");
        assertThat(orphan).as("the orphaned directory is never deleted").exists();
        assertThat(orphan.resolve("0.wal")).exists();
    }

    @Test
    void adoptingTheLifeTheDirectoryBelongsTo_raisesNothing() throws IOException {
        walDirectory(STREAM + "@" + ADOPTED_LIFE);
        var node = manager();

        node.onStreamConfigPut(committed(config().withIncarnation(ADOPTED_LIFE)));
        quiesce();
        node.close();

        assertThat(warnings).isEmpty();
    }

    /// The pre-incarnation layout `<stream>/<partition>.wal` is not a `<stream>@<incarnation>` life directory.
    @Test
    void aPlainStreamDirectory_isNotReported() throws IOException {
        walDirectory(STREAM);
        var node = manager();

        node.onStreamConfigPut(committed(config().withIncarnation(ADOPTED_LIFE)));
        quiesce();
        node.close();

        assertThat(warnings).isEmpty();
    }

    /// A log root that cannot be listed leaves adoption working and reports nothing: the unknown is logged, never a failure.
    @Test
    void anOpenerThatCannotList_doesNotBreakAdoption_andReportsNothing() {
        var directory = AppendLog.Opener.directory(walDir);
        AppendLog.Opener openOnly = directory::open;
        var node = streamPartitionManagerOverLogs(Long.MAX_VALUE, Option.some(openOnly), LastSealedOffsetSource.none());

        node.onStreamConfigPut(committed(config().withIncarnation(ADOPTED_LIFE)));
        quiesce();

        assertThat(node.partitionInfo(STREAM, 0).isSuccess()).as("the stream is adopted and served").isTrue();
        assertThat(warnings).isEmpty();
        node.close();
    }

    /// No WAL is configured (the no-WAL path): adoption has nothing to scan and nothing to report.
    @Test
    void aManagerWithoutLogs_adoptsWithoutScanning_andReportsNothing() {
        var node = StreamPartitionManager.streamPartitionManager();

        node.operatorWarnings(sink());
        node.onStreamConfigPut(committed(config().withIncarnation(ADOPTED_LIFE)));
        quiesce();

        assertThat(node.partitionInfo(STREAM, 0).isSuccess()).as("the stream is adopted and served").isTrue();
        assertThat(warnings).isEmpty();
        node.close();
    }

    @Test
    void aDirectoryOfAnotherStream_isNotThisStreamsOrphan() throws IOException {
        walDirectory("payments@" + ORPHANED_LIFE);
        var node = manager();

        node.onStreamConfigPut(committed(config().withIncarnation(ADOPTED_LIFE)));
        quiesce();
        node.close();

        assertThat(warnings).isEmpty();
    }

    @Test
    void anOrphanedDirectory_isRaisedOncePerDirectory_howManyLivesAdoptAfterIt() throws IOException {
        walDirectory(STREAM + "@" + ORPHANED_LIFE);
        var node = manager();

        node.onStreamConfigPut(committed(config().withIncarnation(ADOPTED_LIFE)));
        awaitWarnings(1);
        node.onStreamConfigRemove(new ValueRemove<>(new KVCommand.Remove<StreamConfigKey>(StreamConfigKey.streamConfigKey(STREAM)),
                                                                                                               Option.empty()));
        node.onStreamConfigPut(committed(config().withIncarnation(ADOPTED_LIFE + 1)));
        quiesce();
        node.close();

        assertThat(warnings).as("the second life adopted after the first raises nothing more for the same directory").hasSize(1);
    }

    /// The orphan is closed when a later adoption of the stream finds the life gone (an operator removed it): one recovery, same subject,
    /// and the startup snapshot, which still lists the directory, does not announce it again.
    @Test
    void aLifeRemovedByAnOperator_isReleased_atTheNextAdoption_andNotAnnouncedAgain() throws IOException {
        var orphan = walDirectory(STREAM + "@" + ORPHANED_LIFE);
        var node = manager();

        node.onStreamConfigPut(committed(config().withIncarnation(ADOPTED_LIFE)));
        awaitWarnings(1);
        Files.delete(orphan.resolve("0.wal"));
        Files.delete(orphan);
        node.onStreamConfigRemove(new ValueRemove<>(new KVCommand.Remove<StreamConfigKey>(StreamConfigKey.streamConfigKey(STREAM)), Option.empty()));
        node.onStreamConfigPut(committed(config().withIncarnation(ADOPTED_LIFE + 1)));
        awaitWarnings(2);
        node.onStreamConfigRemove(new ValueRemove<>(new KVCommand.Remove<StreamConfigKey>(StreamConfigKey.streamConfigKey(STREAM)), Option.empty()));
        node.onStreamConfigPut(committed(config().withIncarnation(ADOPTED_LIFE + 2)));
        quiesce();
        node.close();

        assertThat(warnings).extracting(OperatorWarning::code)
                            .containsExactly(OperatorWarningCode.STORAGE_STREAM_WAL_ORPHANED, OperatorWarningCode.STORAGE_STREAM_WAL_RELEASED);
        assertThat(warnings.get(1).subject()).isEqualTo(STREAM + "@" + ORPHANED_LIFE);
    }

    /// Storage-identity adoption (#1569) makes the orphaned life the committed one: that closes the warning too.
    @Test
    void aLifeThatBecomesTheCommittedLife_isReleased() throws IOException {
        walDirectory(STREAM + "@" + ORPHANED_LIFE);
        var node = manager();

        node.onStreamConfigPut(committed(config().withIncarnation(ADOPTED_LIFE)));
        awaitWarnings(1);
        node.onStreamConfigRemove(new ValueRemove<>(new KVCommand.Remove<StreamConfigKey>(StreamConfigKey.streamConfigKey(STREAM)), Option.empty()));
        node.onStreamConfigPut(committed(config().withIncarnation(ORPHANED_LIFE)));
        awaitWarnings(2);
        node.close();

        assertThat(warnings).extracting(OperatorWarning::code)
                            .containsExactly(OperatorWarningCode.STORAGE_STREAM_WAL_ORPHANED, OperatorWarningCode.STORAGE_STREAM_WAL_RELEASED);
    }

    /// A log root that cannot be listed proves nothing about the directory: no recovery by absence.
    @Test
    void anOrphan_isNotReleased_whenTheLogRootCannotBeListedAtTheNextAdoption() throws IOException {
        walDirectory(STREAM + "@" + ORPHANED_LIFE);
        var listable = new java.util.concurrent.atomic.AtomicBoolean(true);
        var directory = AppendLog.Opener.directory(walDir);
        var opener = AppendLog.Opener.opener(directory::open,
                                             directory::inspect,
                                             () -> listable.get() ? directory.listLogs() : org.pragmatica.lang.Result.failure(() -> "cannot list"));
        var node = streamPartitionManagerOverLogs(Long.MAX_VALUE, Option.some(opener), LastSealedOffsetSource.none());

        node.operatorWarnings(sink());
        node.onStreamConfigPut(committed(config().withIncarnation(ADOPTED_LIFE)));
        awaitWarnings(1);
        listable.set(false);
        node.onStreamConfigRemove(new ValueRemove<>(new KVCommand.Remove<StreamConfigKey>(StreamConfigKey.streamConfigKey(STREAM)), Option.empty()));
        node.onStreamConfigPut(committed(config().withIncarnation(ADOPTED_LIFE + 1)));
        quiesce();
        node.close();

        assertThat(warnings).extracting(OperatorWarning::code).containsExactly(OperatorWarningCode.STORAGE_STREAM_WAL_ORPHANED);
    }

    /// A boot adopts its first lives before the node wires the operator-warning sink; the orphan found then must still reach it.
    @Test
    void anOrphanFoundBeforeTheSinkIsBound_isRaisedWhenItIsBound() throws IOException {
        walDirectory(STREAM + "@" + ORPHANED_LIFE);
        var node = managerWithoutSink();

        node.onStreamConfigPut(committed(config().withIncarnation(ADOPTED_LIFE)));
        quiesce();

        assertThat(warnings).as("control: nothing is delivered before the sink is bound").isEmpty();

        node.operatorWarnings(sink());
        awaitWarnings(1);
        node.close();

        assertThat(warnings.getFirst().subject()).isEqualTo(STREAM + "@" + ORPHANED_LIFE);
    }

    private Path walDirectory(String name) throws IOException {
        var directory = Files.createDirectories(walDir.resolve(name));

        Files.createFile(directory.resolve("0.wal"));

        return directory;
    }

    private StreamPartitionManager manager() {
        var node = managerWithoutSink();

        node.operatorWarnings(sink());

        return node;
    }

    private OperatorWarningSink sink() {
        return OperatorWarningSink.handingOffTo(warnings::add);
    }

    private StreamPartitionManager managerWithoutSink() {
        return streamPartitionManagerOverLogs(Long.MAX_VALUE, Option.some(AppendLog.Opener.directory(walDir)), LastSealedOffsetSource.none());
    }

    private void awaitWarnings(int expected) {
        await(() -> warnings.size() >= expected);
    }

    /// The hand-off is asynchronous: wait for what must arrive, then give anything that must NOT arrive time to.
    private static void await(BooleanSupplier condition) {
        var deadline = System.nanoTime() + 5_000_000_000L;

        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            pause(20);
        }
    }

    private static void quiesce() {
        pause(400);
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static StreamConfig config() {
        return StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 600_000), "earliest");
    }

    private static ValuePut<StreamConfigKey, StreamConfigValue> committed(StreamConfig config) {
        return new ValuePut<>(new KVCommand.Put<>(StreamConfigKey.streamConfigKey(STREAM), StreamConfigValue.streamConfigValue(config)),
                              Option.empty());
    }
}

// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.LongStream;

import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.PartitionRecoveryReason;
import org.pragmatica.aether.slice.kvstore.AetherValue.PartitionRecoveryReasonKind;
import org.pragmatica.aether.stream.provenance.PartitionFlags;
import org.pragmatica.aether.stream.provenance.PartitionFlags.PartitionFlag;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #2320: the `toOffset >= start` boundary of `installUnattributed` (#2302 reviewed it). The copy holds offsets 0..2,
/// so the next expected offset (`start`) is 3. Records up to `toOffset` that reach `start` are new to this copy and
/// get an `UNKNOWN` history entry at `start` plus `HISTORY_INCOMPLETE`; records that stop below `start` are already
/// held and record nothing. Red under "`toOffset >= start` -> `true`" (the start - 1 case) and under
/// "`toOffset >= start` -> `toOffset > start`" (the start case).
class InstallUnattributedBoundaryTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final byte[] PAYLOAD = "e".getBytes(UTF_8);
    private static final long HEAD = 2L;
    private static final long START = HEAD + 1;

    @TempDir
    Path walDir;

    private final List<PartitionRecoveryReasonKind> raised = new CopyOnWriteArrayList<>();
    private StreamPartitionManager manager;

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager(Long.MAX_VALUE, some(walDir));
        manager.partitionFlags(recordingFlags());
        manager.ensureStreamMaterialized(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
        LongStream.rangeClosed(0, HEAD).forEach(this::appendHeld);
    }

    private void appendHeld(long offset) {
        manager.appendRecovered(STREAM, PARTITION, offset, PAYLOAD, 1L).onFailure(cause -> fail(cause.message()));
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    @Test
    void toOffsetBelowStart_recordsNothing() {
        var installed = manager.installUnattributed(STREAM, PARTITION, START - 1).unwrap();

        assertThat(installed.claimed()).as("toOffset == start - 1 is already held: no UNKNOWN entry claimed").isEmpty();
        assertThat(manager.epochHistory(STREAM, PARTITION).unwrap()).as("no history entry at start - 1").isEmpty();
        assertThat(raised).as("no HISTORY_INCOMPLETE for records already held").isEmpty();
    }

    @Test
    void toOffsetEqualToStart_recordsUnknownAtStart() {
        var installed = manager.installUnattributed(STREAM, PARTITION, START).unwrap();

        assertThat(installed.claimed()).as("toOffset == start reaches past the head: UNKNOWN claimed at start")
                  .hasSize(1);
        assertThat(manager.epochHistory(STREAM, PARTITION).unwrap()).as("one entry, at start").hasSize(1);
        assertThat(raised).containsExactly(PartitionRecoveryReasonKind.HISTORY_INCOMPLETE);
    }

    @Test
    void toOffsetAboveStart_recordsUnknownAtStart() {
        var installed = manager.installUnattributed(STREAM, PARTITION, START + 1).unwrap();

        assertThat(installed.claimed()).as("toOffset == start + 1: UNKNOWN claimed at start").hasSize(1);
        assertThat(manager.epochHistory(STREAM, PARTITION).unwrap()).as("one entry, at start").hasSize(1);
        assertThat(raised).containsExactly(PartitionRecoveryReasonKind.HISTORY_INCOMPLETE);
    }

    private PartitionFlags recordingFlags() {
        return new PartitionFlags() {
            @Override
            public Promise<PartitionFlag> raise(String stream, int partition, PartitionRecoveryReason reason) {
                raised.add(reason.kind());

                return Promise.success(new PartitionFlag(AetherValue.StreamPartitionRecoveryValue.raised(none(), reason),
                                                         "d"));
            }

            @Override
            public Option<PartitionFlag> status(String stream, int partition) {
                return none();
            }

            @Override
            public PartitionRecoveryReason local(PartitionRecoveryReasonKind kind, String evidence) {
                return PartitionRecoveryReason.partitionRecoveryReason(kind, some("self"), evidence);
            }
        };
    }
}

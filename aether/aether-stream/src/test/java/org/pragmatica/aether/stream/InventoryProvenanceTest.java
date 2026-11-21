// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.RetentionDimensions;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.provenance.ProvenanceEntry;
import org.pragmatica.lang.Option;
import org.pragmatica.storage.AppendLog;
import org.pragmatica.storage.AppendLog.EpochStart;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #1569 A4 / T27 (stream side): the inventory a node reports from the read-only look at its log
/// (`storage.inspectLog`) equals what the provenance comparison sees of the same log (`localProvenance`), so the
/// inventory and the comparison can never rank one copy two ways. `low` is compared here only for an unsealed log
/// with no eviction: the provenance `low` is still the in-memory ring tail (AD0a-P moves it to the WAL low), the
/// inventory `low` the WAL's on-disk low, and the two agree only then.
class InventoryProvenanceTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final Epoch E1 = Epoch.epoch(0, 1, 0);
    private static final Epoch E2 = Epoch.epoch(0, 2, 0);
    private static final Epoch E3 = Epoch.epoch(0, 3, 0);
    private static final long HOUR_MS = 3_600_000L;
    private static final byte[] PAYLOAD = "e".getBytes(UTF_8);

    @TempDir
    Path walDir;

    private StreamPartitionManager manager;

    @BeforeEach
    void setUp() {
        manager = open();
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    @Test
    void inspectLog_underThreeEpochs_equalsWhatTheProvenanceComparisonSees() {
        publish(E1, 3);
        publish(E2, 2);
        publish(E3, 2);
        manager.close();
        manager = open();

        var local = manager.localProvenance(STREAM, PARTITION).unwrap().unwrap();
        var extent = AppendLog.Opener.directory(walDir).inspect(logName()).unwrap();
        var reached = local.history().stream().filter(entry -> entry.startOffset() <= local.head()).reduce((_, later) -> later).orElseThrow();

        assertThat(local.history()).hasSize(3);
        assertThat(extent.lowOffset()).isEqualTo(local.low());
        assertThat(extent.headOffset()).isEqualTo(local.head());
        assertThat(extent.firstStart()).isEqualTo(local.history().getFirst().startOffset());
        assertThat(extent.lastEpoch()).isEqualTo(Option.some(reached.key().unwrap()));
        assertThat(extent.historyDigest()).isEqualTo(AppendLog.historyDigestOf(asLogEntries(local.history())));
    }

    /// Ruling on T27's `low`: the inventory's `low` is the WAL's on-disk lowOffset, never the in-memory ring tail
    /// (`earliestRetainedOffset`), which an age eviction moves while the WAL keeps every record until a seal-truncate.
    /// Red under "the inventory reads SPM's earliestRetainedOffset" (3 vs 0).
    @Test
    void inspectLog_afterRingEviction_reportsTheWalLow_notTheRingTail() {
        manager.close();
        manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));
        manager.ensureStreamMaterialized(aged()).onFailure(cause -> fail(cause.message()));
        for (var i = 0; i < 3; i++) {
            manager.publishLocal(STREAM, PARTITION, PAYLOAD, System.currentTimeMillis() - 2 * HOUR_MS, E1).onFailure(cause -> fail(cause.message()));
        }
        for (var i = 0; i < 4; i++) {
            manager.publishLocal(STREAM, PARTITION, PAYLOAD, System.currentTimeMillis(), E2).onFailure(cause -> fail(cause.message()));
        }
        manager.evictRingsByAge();

        var local = manager.localProvenance(STREAM, PARTITION).unwrap().unwrap();
        var extent = AppendLog.Opener.directory(walDir).inspect(logName()).unwrap();

        // ENABLED tripwire: pins today's behaviour (provenance low = the ring tail). It goes red when AD0a-P fixes that.
        assertThat(local.low()).as("AD0a-P: ProvenanceComparison's low must read the WAL low (know 744c149fe4); replace this tripwire with the divergent-case pins")
                               .isEqualTo(3L);
        assertThat(extent.lowOffset()).as("the WAL still holds them").isZero();
        assertThat(extent.headOffset()).isEqualTo(local.head());
    }

    private static StreamConfig aged() {
        return StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(1_000L, 64 * 1024L, HOUR_MS).withDeclared(RetentionDimensions.AGE), "latest");
    }

    private static List<EpochStart> asLogEntries(List<ProvenanceEntry> history) {
        return history.stream().map(entry -> new EpochStart(entry.key().unwrap(), entry.startOffset())).toList();
    }

    private static String logName() {
        return StreamPartitionManager.StreamEntry.logName(StreamConfig.streamConfig(STREAM), PARTITION);
    }

    private StreamPartitionManager open() {
        var opened = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));

        opened.ensureStreamMaterialized(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));

        return opened;
    }

    private void publish(Epoch epoch, int count) {
        for (var i = 0; i < count; i++) {
            manager.publishLocal(STREAM, PARTITION, PAYLOAD, 1L, epoch).onFailure(cause -> fail(cause.message()));
        }
    }
}

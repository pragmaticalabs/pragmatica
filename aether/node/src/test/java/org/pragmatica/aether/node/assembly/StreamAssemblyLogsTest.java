// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.storage.AppendLog;
import org.pragmatica.storage.StorageError;
import org.pragmatica.storage.StorageInstance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/// Know 3b01e624f1: the opener the stream manager scans for orphaned WAL directories is the `streams` storage instance's own listing.
/// The manager's detection is pinned in `OrphanedWalDirectoryTest` over a directory opener; this reads the assembly's output, so it is
/// red when the assembly stops handing the instance's listing to the opener.
class StreamAssemblyLogsTest {
    @Test
    void streamLogs_listsThroughTheStreamStorageInstance() {
        var storage = mock(StorageInstance.class);

        when(storage.logRoot()).thenReturn(Option.some(Path.of("/streams")));
        when(storage.listLogs()).thenReturn(Result.success(List.of("orders@7/0")));

        var opener = StreamAssembly.streamLogs(storage).unwrap();

        assertThat(opener.listLogs().unwrap()).containsExactly("orders@7/0");
    }

    /// #1759: the replica WAL catch-up reads go through the instance (the opener's own default refuses them), so reverting the
    /// assembly to an opener built from open/inspect/list alone silently turns the feature off. Red on that revert.
    @Test
    void streamLogs_readsRangesAndEpochHistoryThroughTheStreamStorageInstance() {
        var storage = mock(StorageInstance.class);
        var page = new AppendLog.RangePage(List.of(new AppendLog.WalRecord(3, 7L, new byte[]{1})), 40L);
        var history = new ArrayList<AppendLog.EpochStart>();

        when(storage.logRoot()).thenReturn(Option.some(Path.of("/streams")));
        when(storage.readLogRange("orders@7/0", 3L, 16, 0L)).thenReturn(Result.success(page));
        when(storage.readLogEpochHistory("orders@7/0")).thenReturn(Result.success(history));

        var opener = StreamAssembly.streamLogs(storage).unwrap();

        assertThat(opener.readRange("orders@7/0", 3L, 16, 0L).unwrap()).isSameAs(page);
        assertThat(opener.readEpochHistory("orders@7/0").unwrap()).isSameAs(history);
    }

    /// The remaining delegates of the instance's opener: open and inspect.
    @Test
    void streamLogs_opensAndInspectsThroughTheStreamStorageInstance() {
        var storage = mock(StorageInstance.class);
        var extent = new AppendLog.LogExtent(0L, 2L, 10L, 10L);

        when(storage.logRoot()).thenReturn(Option.some(Path.of("/streams")));
        when(storage.inspectLog("orders@7/0")).thenReturn(Result.success(extent));
        when(storage.openLog("orders@7/0")).thenReturn(new StorageError.InspectUnsupported("orders@7/0").result());

        var opener = StreamAssembly.streamLogs(storage).unwrap();

        assertThat(opener.inspect("orders@7/0").unwrap()).isSameAs(extent);
        assertThat(opener.open("orders@7/0").isFailure()).as("open reached the instance's refusal").isTrue();
        verify(storage).openLog("orders@7/0");
    }

    @Test
    void streamLogs_isAbsent_whenTheInstanceHasNoLogRoot() {
        var storage = mock(StorageInstance.class);

        when(storage.logRoot()).thenReturn(Option.none());

        assertThat(StreamAssembly.streamLogs(storage).isEmpty()).isTrue();
    }
}

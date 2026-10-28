// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.nio.file.Path;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.storage.StorageInstance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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

    @Test
    void streamLogs_isAbsent_whenTheInstanceHasNoLogRoot() {
        var storage = mock(StorageInstance.class);

        when(storage.logRoot()).thenReturn(Option.none());

        assertThat(StreamAssembly.streamLogs(storage).isEmpty()).isTrue();
    }
}

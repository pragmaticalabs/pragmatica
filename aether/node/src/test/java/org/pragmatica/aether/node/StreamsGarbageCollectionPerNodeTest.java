// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;
import org.pragmatica.storage.StorageGarbageCollector;

import static org.assertj.core.api.Assertions.assertThat;

/// #1604: storage GC is leader-pinned, but a node's `streams` instance is its own, over its own disk. Since
/// retention only drops refs and leaves the blocks to GC, a leader-pinned streams collector would leave every
/// non-leader's disk to fill. So the streams collector runs on every node, and leader activation leaves it be.
class StreamsGarbageCollectionPerNodeTest {
    @TempDir
    Path dir;

    @Test
    void streamsCollector_isActiveWithoutLeaderActivation_andLeaderDeactivationLeavesItActive() {
        var streams = StorageFactory.defaultStreamStorage(Option.none(), dir.resolve("stream-data"), "node-1").unwrap();

        assertThat(streams.garbageCollector().isActive()).as("active on a node that never became leader").isTrue();

        var composite = StorageFactory.compositeGarbageCollector(Map.of(StorageFactory.STREAMS_NAME, streams));

        composite.activate();
        composite.deactivate();

        assertThat(streams.garbageCollector().isActive()).as("losing leadership does not stop this node's streams GC")
                                                         .isTrue();
        assertThat(composite).isInstanceOf(StorageGarbageCollector.class);
    }
}

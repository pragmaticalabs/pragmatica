// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2201 A-05, the author pre-check of PR-2a: the per-stream state accessors of [StreamEntries]. A lookup of a name never
/// creates state; the writer's accessor creates it once and hands back the same object; a purge drops exactly the named
/// stream, by name, so a stream whose name extends the purged one (the prefix match the pre-extraction purge of
/// `raisedLocally`'s `stream#partition#...` keys had to get right) keeps its state.
class StreamEntriesTest {
    @Test
    void existingPartitionState_neverCreatesState() {
        var entries = StreamEntries.streamEntries();

        assertThat(entries.existingPartitionState("orders").isEmpty()).as("first lookup").isTrue();
        assertThat(entries.existingPartitionState("orders").isEmpty()).as("a lookup left nothing behind").isTrue();
    }

    @Test
    void partitionState_createsOnce_andTheReaderSeesTheSameObject() {
        var entries = StreamEntries.streamEntries();
        var written = entries.partitionState("orders");

        assertThat(entries.partitionState("orders")).as("a second write reaches the same state").isSameAs(written);
        assertThat(entries.existingPartitionState("orders").map(state -> state == written).or(false)).as("the reader sees it").isTrue();
    }

    @Test
    void purgePartitionState_dropsTheNamedStreamOnly_notOneWhoseNameExtendsIt() {
        var entries = StreamEntries.streamEntries();
        var sibling = entries.partitionState("orders#1");
        var longer = entries.partitionState("orders2");

        entries.partitionState("orders");
        entries.purgePartitionState("orders");

        assertThat(entries.existingPartitionState("orders").isEmpty()).as("the purged stream").isTrue();
        assertThat(entries.existingPartitionState("orders#1").map(state -> state == sibling).or(false)).as("a name with a '#' suffix").isTrue();
        assertThat(entries.existingPartitionState("orders2").map(state -> state == longer).or(false)).as("a longer name").isTrue();
        assertThat(entries.partitionState("orders")).as("a write after the purge starts fresh").isNotNull();
    }
}

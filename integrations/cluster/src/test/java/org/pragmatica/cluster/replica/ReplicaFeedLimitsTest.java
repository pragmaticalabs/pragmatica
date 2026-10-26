// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.replica;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// The defaults the PR documents, pinned by literal (not read back from the constants under test). Each is a marked guess; changing one
/// should be a visible edit of this test, not a silent one.
class ReplicaFeedLimitsTest {
    /// Mutation: change any default in `ReplicaFeedLimits.DEFAULT`.
    @Test
    void defaults_areTheDocumentedValues() {
        var limits = ReplicaFeedLimits.DEFAULT;

        assertThat(limits.chunkBytes()).as("64 KiB chunks").isEqualTo(65_536);
        assertThat(limits.maxServes()).as("two snapshot transfers at once").isEqualTo(2);
        assertThat(limits.cacheMillis()).as("an encoded snapshot is reused for 5 s").isEqualTo(5_000L);
        assertThat(limits.cacheEntries()).as("two cached snapshots").isEqualTo(2);
        assertThat(limits.queueBytes()).as("8 MiB per subscriber queue").isEqualTo(8L * 1024 * 1024);
        assertThat(limits.maxSubscribers()).as("512 subscribers").isEqualTo(512);
        assertThat(limits.transferIdleMillis()).as("30 s transfer idle").isEqualTo(30_000L);
        assertThat(limits.drainMessagesPerTick()).as("256 messages per subscriber per tick").isEqualTo(256);
        assertThat(limits.catchUpWeight()).as("256 effects per catch-up message").isEqualTo(256);
    }
}

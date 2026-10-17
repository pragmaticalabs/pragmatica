// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.projection;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.projection.ProjectionStore.RewindToken;
import org.pragmatica.aether.slice.generation.RewindEpoch;

import static org.assertj.core.api.Assertions.assertThat;

/// #1529: the rewind token a rebuild mints is strictly newer than the newest committed rewind epoch, and
/// a token minted in a newer cluster incarnation starts that incarnation's own sequence.
class NodeReplayCursorMintTest {
    private static final RewindEpoch RESTORED = RewindEpoch.rewindEpoch(1L, 3L, 4L);

    @Test
    void nextToken_sameIncarnation_advancesPastTheNewestCommittedRewind() {
        var token = NodeReplayCursor.nextToken(RESTORED, 1L, 2L);

        assertThat(token).isEqualTo(new RewindToken(1L, 3L, 5L));
        assertThat(NodeReplayCursor.epochOf(token).isStrictlyAfter(RESTORED)).isTrue();
    }

    @Test
    void nextToken_afterAColdRestart_startsTheNewIncarnationsSequence() {
        var token = NodeReplayCursor.nextToken(RESTORED, 2L, 1L);

        assertThat(token).isEqualTo(new RewindToken(2L, 1L, 1L));
        assertThat(NodeReplayCursor.epochOf(token).isStrictlyAfter(RESTORED))
            .as("the new run's first rewind outranks the previous run's newest, whatever its generation")
            .isTrue();
    }

    @Test
    void nextToken_readerBehindTheCommittedIncarnation_stillMintsStrictlyNewer() {
        var token = NodeReplayCursor.nextToken(RESTORED, 0L, 1L);

        assertThat(NodeReplayCursor.epochOf(token).isStrictlyAfter(RESTORED)).isTrue();
    }
}

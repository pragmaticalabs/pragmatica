// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.projection;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.stream.ConsumerAssignmentWriter.CommittedAssignments;
import org.pragmatica.aether.resource.projection.ProjectionStore.RewindToken;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.EpochMintError;
import org.pragmatica.aether.slice.generation.RewindEpoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamCursorCheckpointValue;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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

    /// #2654: while the life's incarnation is not committed, a rebuild mints no rewind (it would carry the restored
    /// life's incarnation into the new life); once the commit is observed it mints the new life's first rewind.
    @Test
    void mintRewindToken_incarnationNone_isRefusedAsNotCommitted() {
        var incarnation = new AtomicLong(Epoch.NO_INCARNATION);

        var refused = cursor(incarnation).mintRewindToken(1L).await();

        assertThat(refused.isFailure()).as("a NONE-incarnation rewind mint is refused").isTrue();
        refused.onFailure(cause -> assertThat(cause).isEqualTo(EpochMintError.General.INCARNATION_NOT_COMMITTED));
    }

    @Test
    void mintRewindToken_afterTheIncarnationCommits_mintsTheNewLifesFirstRewind() {
        var incarnation = new AtomicLong(Epoch.NO_INCARNATION);
        var cursor = cursor(incarnation);

        incarnation.set(2L);

        assertThat(cursor.mintRewindToken(1L).await()).isEqualTo(Result.success(new RewindToken(2L, 1L, 1L)));
    }

    private static NodeReplayCursor cursor(AtomicLong incarnation) {
        return new NodeReplayCursor("topic-stream",
                                    () -> Result.success("group"),
                                    () -> Option.some(1),
                                    mock(PartitionBounds.class),
                                    _ -> Promise.success(Unit.unit()),
                                    _ -> Option.<StreamCursorCheckpointValue>none(),
                                    mock(CommittedAssignments.class),
                                    incarnation::get,
                                    mock(CommitWitness.class));
    }
}

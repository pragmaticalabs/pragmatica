// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.EpochStart;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// #2981 step 3 (design §2.3 B1, §7 step 3): every epoch start names the owner whose lineage write recorded it, because a
/// later owner's move fence probes that owner. Each of the three construction sites carries it, and no constructor or factory
/// can leave it out.
class EpochStartOwnerTest {
    private static final NodeId OWNER = NodeId.nodeId("core-1").unwrap();
    private static final NodeId EARLIER = NodeId.nodeId("core-2").unwrap();
    private static final Epoch EPOCH = Epoch.epoch(1L, 2L, 5L);

    private static StreamPartitionOwnershipValue record(List<EpochStart> starts) {
        return StreamPartitionOwnershipValue.streamPartitionOwnershipValue(OWNER,
                                                                           EPOCH,
                                                                           5L,
                                                                           HlcTimestamp.ZERO,
                                                                           List.of(OWNER),
                                                                           1L,
                                                                           List.of(),
                                                                           starts);
    }

    @Test
    void withEpochStart_namesTheRecordsOwner() {
        var written = record(List.of()).withEpochStart(40L);

        assertThat(written.lastEpochStart()
                          .map(EpochStart::owner)
                          .flatMap(owner -> owner))
            .as("the lineage write names the activating owner, the record's own")
            .isEqualTo(Option.some(OWNER));
    }

    @Test
    void restarted_namesTheRecordsOwner() {
        var restarted = record(List.of()).restarted(70L, HlcTimestamp.ZERO);

        assertThat(restarted.lastEpochStart()
                            .map(EpochStart::owner)
                            .flatMap(owner -> owner))
            .as("a restart's new epoch was started by the same owner")
            .isEqualTo(Option.some(OWNER));
    }

    @Test
    void capped_foldKeepsTheOldestKeptEntrysOwner() {
        var starts = new ArrayList<EpochStart>();

        for (long counter = 1; counter <= StreamPartitionOwnershipValue.EPOCH_STARTS_MAX + 1; counter++) {
            var owner = counter == 2
                        ? EARLIER
                        : OWNER;
            starts.add(EpochStart.epochStart(Epoch.epoch(1L, 2L, counter), counter * 10, Option.some(owner)));
        }

        var folded = record(starts).epochStarts()
                                   .getFirst();

        assertThat(folded.epoch()).isEqualTo(Epoch.epoch(1L, 2L, 2L));
        assertThat(folded.coversFrom()).as("the fold happened").isEqualTo(Epoch.epoch(1L, 2L, 1L));
        assertThat(folded.owner()).as("the folded entry stands for the kept epoch, so it keeps that epoch's owner")
                                  .isEqualTo(Option.some(EARLIER));
    }

    /// v3186 p8, decided (#2981 step 3b): the owner joins the idempotence check of [StreamPartitionOwnershipValue#withEpochStart]
    /// (record equality). A newest start of the same epoch and offset that names no owner, or another one, is replaced by the start
    /// naming this record's owner: the fence target a later owner reads must be the node that really wrote, never an unknown
    /// owner kept only because epoch and offset matched.
    @Test
    void withEpochStart_sameEpochAndOffsetButAnotherOrNoOwner_recordsTheOwner() {
        for (var recorded : List.of(Option.<NodeId>none(), Option.some(EARLIER))) {
            var before = record(List.of(EpochStart.epochStart(EPOCH, 40L, recorded)));
            var written = before.withEpochStart(40L);

            assertThat(written).as("a start owned by %s is not the same start", recorded).isNotSameAs(before);
            assertThat(written.epochStarts()).as("replaced, not appended beside it")
                                             .containsExactly(EpochStart.epochStart(EPOCH, 40L, Option.some(OWNER)));
        }
    }

    /// The other half of the decision: the same start, owner included, changes nothing (the re-activation of an epoch that already
    /// has its start).
    @Test
    void withEpochStart_theSameStartIncludingItsOwner_changesNothing() {
        var before = record(List.of(EpochStart.epochStart(EPOCH, 40L, Option.some(OWNER))));

        assertThat(before.withEpochStart(40L)).isSameAs(before);
    }

    @Test
    void epochStart_hasNoConstructionWithoutAnOwner() {
        var constructors = EpochStart.class.getDeclaredConstructors();

        assertThat(constructors).as("only the canonical constructor; the two-argument convenience constructor is removed")
                                .hasSize(1);
        assertThat(Arrays.asList(constructors[0].getParameterTypes())).contains(Option.class);
        assertThat(Arrays.stream(EpochStart.class.getDeclaredMethods())
                         .filter(method -> method.getReturnType() == EpochStart.class)
                         .allMatch(method -> Arrays.asList(method.getParameterTypes())
                                                   .contains(Option.class)))
            .as("every factory takes the owner")
            .isTrue();
    }
}

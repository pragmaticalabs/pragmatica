// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.StreamPartitionManager.StreamEntry;

import static org.assertj.core.api.Assertions.assertThat;

/// #2201 A-05, the author pre-check of PR-2a, and the incarnation stamp of know `f7bc44d170` (v2559): the per-stream state
/// accessors of [StreamEntries]. A lookup of a name never creates state; the writer's accessor creates it once and hands back
/// the same object; a purge drops exactly the named stream's life, by name, so a stream whose name extends the purged one (the
/// prefix match the pre-extraction purge of `raisedLocally`'s `stream#partition#...` keys had to get right) keeps its state.
/// A write for a name with no life here lands in a dead bucket; a reader sees only the live life's state; a writer of an ended
/// life never replaces a re-created life's state; a purge of one life never drops another's.
class StreamEntriesTest {
    private static final long OLD_LIFE = 11L;
    private static final long NEW_LIFE = 22L;

    @Test
    void existingPartitionState_neverCreatesState() {
        var entries = withAppliedLife("orders", OLD_LIFE);

        assertThat(entries.existingPartitionState("orders").isEmpty()).as("first lookup").isTrue();
        assertThat(entries.existingPartitionState("orders").isEmpty()).as("a lookup left nothing behind").isTrue();
    }

    @Test
    void partitionState_createsOnce_andTheReaderSeesTheSameObject() {
        var entries = withAppliedLife("orders", OLD_LIFE);
        var written = entries.livePartitionState("orders");

        assertThat(entries.livePartitionState("orders")).as("a second write reaches the same state").isSameAs(written);
        assertThat(entries.partitionState("orders", OLD_LIFE)).as("the life's own writer reaches it too").isSameAs(written);
        assertThat(entries.existingPartitionState("orders").map(state -> state == written).or(false)).as("the reader sees it").isTrue();
    }

    @Test
    void purgePartitionState_dropsTheNamedStreamOnly_notOneWhoseNameExtendsIt() {
        var entries = withAppliedLife("orders", OLD_LIFE);

        entries.appliedConfigs().put("orders#1", config("orders#1", OLD_LIFE));
        entries.appliedConfigs().put("orders2", config("orders2", OLD_LIFE));
        var sibling = entries.livePartitionState("orders#1");
        var longer = entries.livePartitionState("orders2");

        entries.livePartitionState("orders");
        entries.purgePartitionState("orders", OLD_LIFE);

        assertThat(entries.existingPartitionState("orders").isEmpty()).as("the purged stream").isTrue();
        assertThat(entries.existingPartitionState("orders#1").map(state -> state == sibling).or(false)).as("a name with a '#' suffix").isTrue();
        assertThat(entries.existingPartitionState("orders2").map(state -> state == longer).or(false)).as("a longer name").isTrue();
        assertThat(entries.livePartitionState("orders")).as("a write after the purge starts fresh").isNotNull();
    }

    /// Item 1 (dead bucket): a name with no life on this node gets a throwaway, never stored.
    @Test
    void livePartitionState_nameWithNoLife_isADeadBucket_neverStored() {
        var entries = StreamEntries.streamEntries();
        var bucket = entries.livePartitionState("orders");

        bucket.standing().replicaCommitted().put(0, new StreamPartitionManager.CommittedMark(Epoch.ZERO, 7L));
        entries.appliedConfigs().put("orders", config("orders", OLD_LIFE));

        assertThat(entries.livePartitionState("orders")).as("a later life gets its own state").isNotSameAs(bucket);
        assertThat(entries.existingPartitionState("orders").isPresent()).as("the life's own state, created by its writer").isTrue();
        assertThat(entries.existingPartitionState("orders").map(state -> state.heldRows(0)).or(Set.of()))
            .as("the dead bucket's write is not in it").isEmpty();
    }

    /// Item 1 (stamp): a writer of an ended life gets a dead bucket and cannot replace the live life's state.
    @Test
    void partitionState_ofAnEndedLife_isADeadBucket_andKeepsTheLiveLifesState() {
        var entries = withAppliedLife("orders", NEW_LIFE);
        var live = entries.livePartitionState("orders");

        assertThat(entries.partitionState("orders", OLD_LIFE)).as("the ended life's writer").isNotSameAs(live);
        assertThat(entries.existingPartitionState("orders").map(state -> state == live).or(false)).as("the live life's state stays").isTrue();
    }

    /// Item 1 (stamp, nothing stored yet): a writer naming a life that is not the live one gets a dead bucket and stores
    /// nothing, even when no state is stored for the name.
    @Test
    void partitionState_ofALifeThatIsNotLive_storesNothing() {
        var entries = withAppliedLife("orders", NEW_LIFE);
        var bucket = entries.partitionState("orders", OLD_LIFE);

        assertThat(entries.lifePartitionState("orders", OLD_LIFE).isEmpty()).as("the not-live life's state is not stored").isTrue();
        assertThat(entries.livePartitionState("orders")).as("the live life's writer").isNotSameAs(bucket);
    }

    /// Item 1 (reader filter): a stored state of another incarnation reads as absent; the live life's writer replaces it.
    @Test
    void existingPartitionState_ofAnotherIncarnation_readsAsAbsent_andTheLiveWriterReplacesIt() {
        var entries = withAppliedLife("orders", OLD_LIFE);
        var ended = entries.livePartitionState("orders");

        entries.appliedConfigs().put("orders", config("orders", NEW_LIFE));

        assertThat(entries.existingPartitionState("orders").isEmpty()).as("the ended life's state is not the live life's").isTrue();
        var replaced = entries.livePartitionState("orders");
        assertThat(replaced).as("the live life's writer replaces it").isNotSameAs(ended);
        assertThat(replaced.incarnation()).isEqualTo(NEW_LIFE);
    }

    /// While the name has no life (a removal between taking the entry out and its purge), the ending life's own readers still
    /// reach its state; a writer naming no life does not, and the purge drops it.
    @Test
    void existingPartitionState_noLiveLife_readsTheEndingLifesStateUntilItsPurge() {
        var entries = withAppliedLife("orders", OLD_LIFE);
        var ending = entries.livePartitionState("orders");

        entries.appliedConfigs().remove("orders");

        assertThat(entries.existingPartitionState("orders").map(state -> state == ending).or(false)).as("before the purge").isTrue();
        assertThat(entries.livePartitionState("orders")).as("a writer naming no life").isNotSameAs(ending);
        entries.purgePartitionState("orders", OLD_LIFE);
        assertThat(entries.existingPartitionState("orders").isEmpty()).as("after the purge").isTrue();
    }

    /// Item 1 (replace only for a state no life here holds): while the committed life is applied and the losing local entry is
    /// still in place, the losing life's writer cannot replace the committed life's state.
    @Test
    void partitionState_ofTheLocalLife_doesNotReplaceTheAppliedLifesState() {
        var entries = withAppliedLife("orders", NEW_LIFE);
        var committed = entries.hydratingPartitionState("orders", NEW_LIFE);

        entries.streams().put("orders", StreamEntry.metadataOnly(config("orders", OLD_LIFE)));

        assertThat(entries.livePartitionState("orders")).as("the local losing life's writer").isNotSameAs(committed);
        assertThat(entries.lifePartitionState("orders", NEW_LIFE).map(state -> state == committed).or(false)).as("kept").isTrue();
    }

    /// Item 4 (the reverse race): a removal's purge that runs after the name was created again drops nothing of the new life.
    @Test
    void purgePartitionState_ofAnEndedLife_keepsTheRecreatedLifesState() {
        var entries = withAppliedLife("orders", NEW_LIFE);
        var recreated = entries.livePartitionState("orders");

        entries.purgePartitionState("orders", OLD_LIFE);

        assertThat(entries.existingPartitionState("orders").map(state -> state == recreated).or(false)).as("the new life's state").isTrue();
    }

    /// Item 3: a hydrate installs the applied life's state over another life's; a hydrate of a life that is not the applied one
    /// gets a dead bucket.
    @Test
    void hydratingPartitionState_installsOnlyTheAppliedLife() {
        var entries = withAppliedLife("orders", NEW_LIFE);

        entries.streams().put("orders", StreamEntry.metadataOnly(config("orders", OLD_LIFE)));
        var losing = entries.livePartitionState("orders");
        var committed = entries.hydratingPartitionState("orders", NEW_LIFE);

        assertThat(committed).as("the applied life's hydrate replaces the losing life's state").isNotSameAs(losing);
        assertThat(entries.lifePartitionState("orders", NEW_LIFE).isPresent()).as("stored").isTrue();
        assertThat(entries.hydratingPartitionState("orders", 33L)).as("a hydrate of a life not applied here").isNotSameAs(committed);
        assertThat(entries.lifePartitionState("orders", NEW_LIFE).map(state -> state == committed).or(false)).as("kept").isTrue();
    }

    /// Item 3, no applied config (a fresh create before its put): the hydrate installs, unless the stored state is the local
    /// entry's (a concurrent create that won the put).
    @Test
    void hydratingPartitionState_freshCreate_installsUnlessAnotherCreateWonThePut() {
        var entries = StreamEntries.streamEntries();
        var fresh = entries.hydratingPartitionState("orders", OLD_LIFE);

        assertThat(entries.lifePartitionState("orders", OLD_LIFE).map(state -> state == fresh).or(false)).as("installed").isTrue();

        entries.streams().put("orders", StreamEntry.metadataOnly(config("orders", OLD_LIFE)));

        assertThat(entries.hydratingPartitionState("orders", NEW_LIFE)).as("the losing create's hydrate").isNotSameAs(fresh);
        assertThat(entries.existingPartitionState("orders").map(state -> state == fresh).or(false)).as("the winner's state stays").isTrue();
    }

    private static StreamEntries withAppliedLife(String name, long incarnation) {
        var entries = StreamEntries.streamEntries();

        entries.appliedConfigs().put(name, config(name, incarnation));

        return entries;
    }

    private static StreamConfig config(String name, long incarnation) {
        return StreamConfig.streamConfig(name, 1, RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 3_600_000L), "earliest")
                           .withIncarnation(incarnation);
    }
}

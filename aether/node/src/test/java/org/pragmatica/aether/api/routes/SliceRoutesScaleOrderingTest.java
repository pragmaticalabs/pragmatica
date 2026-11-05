// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.api.routes.SliceRoutesScaleFloorTest.BLUEPRINT_ID;
import static org.pragmatica.aether.api.routes.SliceRoutesScaleFloorTest.SLICE;

/// #2662: the scale route's read-modify-write of a slice target is correct whatever order consensus commits it in.
///
/// The route reads the target and writes the scaled value. The write is held here while a racing change commits into the real
/// `KVStore`, then the held write commits after it — the reverse of the order the two were decided in. A plain put lands either way:
/// after a blueprint delete it re-creates the deleted blueprint's target (an orphan), after a republish it reverts the target to the
/// version it read (the stale value wins), and over a target the blueprint wrote meanwhile its creation drops the owner. The fenced
/// write is refused in each case, and its rebuild reads what committed.
class SliceRoutesScaleOrderingTest {
    private static final Artifact V2 = Artifact.artifact("org.example:svc-a:2.0.0").unwrap();
    private static final SliceTargetKey TARGET_KEY = SliceTargetKey.sliceTargetKey(SLICE.base());
    private static final AppBlueprintKey BLUEPRINT_KEY = AppBlueprintKey.appBlueprintKey(BLUEPRINT_ID);

    private KVStore<AetherKey, AetherValue> store;
    private final List<Held> held = new ArrayList<>();

    private record Held(List<KVCommand<AetherKey>> batch, Promise<List<Object>> promise) {}

    @BeforeEach
    void leaderAndBlueprint() {
        store = SliceRoutesScaleFloorTest.fencedStore();
    }

    /// Control of the instrument: with nothing racing, the scale commits.
    @Test
    void scale_nothingRaces_scaledTargetCommits() {
        commit(new KVCommand.Put<>(TARGET_KEY, ownedTarget(SLICE, 2)));

        var response = scaleTo(4);

        commitAllAsTheyArrive();

        assertThat(status(response)).isEqualTo(HttpStatus.OK);
        assertThat(committedTarget().map(SliceTargetValue::targetInstances)).isEqualTo(Option.some(4));
    }

    /// The blueprint is deleted and its target removed after the read; the scale commits last. It must not re-create the target.
    @Test
    void scale_deleteCommitsFirst_doesNotRecreateTheTarget() {
        commit(new KVCommand.Put<>(TARGET_KEY, ownedTarget(SLICE, 2)));

        var response = scaleTo(4);

        commit(new KVCommand.Remove<>(BLUEPRINT_KEY));
        commit(new KVCommand.Remove<>(TARGET_KEY));
        commitAllAsTheyArrive();

        assertThat(committedTarget()).as("no target is re-created for a deleted blueprint").isEqualTo(Option.none());
        assertThat(status(response)).as("the request lost to the delete").isEqualTo(HttpStatus.CONFLICT);
    }

    /// A republish moves the target to v2 after the read; the scale commits last. The v1 value it read must not win.
    @Test
    void scale_republishCommitsFirst_doesNotRevertTheVersion() {
        commit(new KVCommand.Put<>(TARGET_KEY, ownedTarget(SLICE, 2)));

        var response = scaleTo(4);

        commit(new KVCommand.Put<>(BLUEPRINT_KEY, blueprintOf(V2)));
        commit(new KVCommand.Put<>(TARGET_KEY, ownedTarget(V2, 2)));
        commitAllAsTheyArrive();

        var committed = committedTarget().unwrap();

        assertThat(committed.currentVersion()).as("the republished version is not reverted").isEqualTo(V2.version());
        assertThat(committed.targetInstances()).as("the rebuilt write scales the moved target").isEqualTo(4);
        assertThat(status(response)).isEqualTo(HttpStatus.OK);
    }

    /// The blueprint is deleted after the read while its target is still committed (its removal has not committed yet). The scale is
    /// refused by the owner witness, and its rebuild refuses to write the orphan: the request answers 409 and the target is unchanged.
    @Test
    void scale_blueprintDeleteCommitsFirst_targetStillCommitted_refusedWithConflict() {
        commit(new KVCommand.Put<>(TARGET_KEY, ownedTarget(SLICE, 2)));

        var response = scaleTo(4);

        commit(new KVCommand.Remove<>(BLUEPRINT_KEY));
        commitAllAsTheyArrive();

        assertThat(committedTarget().map(SliceTargetValue::targetInstances)).as("the orphan is not scaled").isEqualTo(Option.some(2));
        assertThat(status(response)).as("the request lost to the delete").isEqualTo(HttpStatus.CONFLICT);
    }

    /// No target yet, so the route creates one (a register-only blueprint's slice). The blueprint is deleted before the creation commits:
    /// the creation must not leave an unowned target that no delete will ever sweep.
    @Test
    void scaleCreate_deleteCommitsFirst_createsNoTarget() {
        var response = scaleTo(4);

        commit(new KVCommand.Remove<>(BLUEPRINT_KEY));
        commitAllAsTheyArrive();

        assertThat(committedTarget()).as("no unowned target outlives the deleted blueprint").isEqualTo(Option.none());
        assertThat(status(response)).isEqualTo(HttpStatus.CONFLICT);
    }

    /// No target yet; the blueprint's own target put commits before the creation. The creation must not replace it (dropping its owner):
    /// its rebuild scales the committed target instead.
    @Test
    void scaleCreate_blueprintTargetCommitsFirst_scalesItAndKeepsTheOwner() {
        var response = scaleTo(4);

        commit(new KVCommand.Put<>(TARGET_KEY, ownedTarget(SLICE, 3)));
        commitAllAsTheyArrive();

        var committed = committedTarget().unwrap();

        assertThat(committed.owningBlueprint()).as("the blueprint's ownership survives").isEqualTo(Option.some(BLUEPRINT_ID));
        assertThat(committed.targetInstances()).isEqualTo(4);
        assertThat(status(response)).isEqualTo(HttpStatus.OK);
    }

    /// Control of the creation: with nothing racing, the slice's first target is created, unowned, as before.
    @Test
    void scaleCreate_nothingRaces_createsTheUnownedTarget() {
        var response = scaleTo(4);

        commitAllAsTheyArrive();

        assertThat(status(response)).isEqualTo(HttpStatus.OK);
        assertThat(committedTarget().map(SliceTargetValue::owningBlueprint)).isEqualTo(Option.some(Option.none()));
    }

    private CompletableFuture<SliceRoutesScaleFloorTest.RecordingResponseWriter> scaleTo(int instances) {
        var node = new SliceRoutesScaleFloorTest.ScaleManageableNode(SliceRoutesScaleFloorTest.listingBlueprintService(), store, this::hold);
        var response = CompletableFuture.supplyAsync(() -> SliceRoutesScaleFloorTest.scale(node, instances));

        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> heldCount() > 0);

        return response;
    }

    private synchronized Promise<List<Object>> hold(List<KVCommand<AetherKey>> batch) {
        var promise = Promise.<List<Object>> promise();

        held.add(new Held(List.copyOf(batch), promise));

        return promise;
    }

    private synchronized int heldCount() {
        return held.size();
    }

    private synchronized void commitOldest() {
        var entry = held.removeFirst();

        entry.promise().succeed(store.<Object> process(store.createBatch(entry.batch())));
    }

    /// Commits the held write, then each rebuild the refusal submits, until none arrives for a while.
    private void commitAllAsTheyArrive() {
        var quietUntil = System.currentTimeMillis() + 300;

        while (System.currentTimeMillis() < quietUntil) {
            if (heldCount() > 0) {
                commitOldest();
                quietUntil = System.currentTimeMillis() + 300;
            } else {
                Thread.onSpinWait();
            }
        }
    }

    private static HttpStatus status(CompletableFuture<SliceRoutesScaleFloorTest.RecordingResponseWriter> response) {
        return response.join().status.get();
    }

    private Option<SliceTargetValue> committedTarget() {
        return store.get(TARGET_KEY).filter(SliceTargetValue.class::isInstance).map(SliceTargetValue.class::cast);
    }

    private void commit(KVCommand<AetherKey> command) {
        store.process(store.createBatch(List.of(command)));
    }

    private static SliceTargetValue ownedTarget(Artifact slice, int instances) {
        return SliceTargetValue.sliceTargetValue(slice.version(), instances, 1, Option.some(BLUEPRINT_ID), Option.none(), Option.none(), Option.none());
    }

    private static AppBlueprintValue blueprintOf(Artifact slice) {
        var resolved = ResolvedSlice.resolvedSlice(slice, 3, false).unwrap();

        return AppBlueprintValue.appBlueprintValue(ExpandedBlueprint.expandedBlueprint(BLUEPRINT_ID, List.of(resolved)));
    }
}

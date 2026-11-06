// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.util.List;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceRequirementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.RequirementKind;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceRequirementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVCommand.ReadWitness;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// The fences of a requirement source's write, pinned in the slice module: the witness on the target at the value the write was decided
/// against, and the witness on the owning blueprint. `SliceRoutesScaleOrderingTest` (node) pins the same races end to end, but only a node
/// run reaches them; these pin the transaction's guard list directly, so removing either witness goes red here.
class SliceRequirementFenceTest {
    private static final long NOW = 1_000_000L;
    private static final Version V1 = Version.version("1.0.0").unwrap();
    private static final ArtifactBase BASE = ArtifactBase.artifactBase("org.example:orders-slice").unwrap();
    private static final SliceTargetKey TARGET_KEY = SliceTargetKey.sliceTargetKey(BASE);
    private static final BlueprintId OWNER = BlueprintId.blueprintId("org.example:orders-app:1.0.0").unwrap();
    private static final AppBlueprintKey OWNER_KEY = AppBlueprintKey.appBlueprintKey(OWNER);
    private static final SliceRequirementKey OPERATOR_KEY = SliceRequirementKey.sliceRequirementKey(BASE, RequirementKind.OPERATOR);

    private static KVStore<AetherKey, AetherValue> store() {
        var store = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), new Serializer() {
            @Override public <T> void write(ByteBuf buffer, T value) {}
        }, new Deserializer() {
            @Override public <T> T read(ByteBuf buffer) {return null;}
        });

        put(store, LeaderKey.INSTANCE, new LeaderValue(NodeId.nodeId("leader").unwrap(), 1));

        return store;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void put(KVStore<AetherKey, AetherValue> store, StructuredKey key, Object value) {
        store.process(store.createBatch((List) List.of(new KVCommand.Put(key, value))));
    }

    private static AppBlueprintValue ownerBlueprint() {
        var slice = ResolvedSlice.resolvedSlice(Artifact.artifact("org.example:orders-slice:1.0.0").unwrap(), 1, false).unwrap();

        return AppBlueprintValue.appBlueprintValue(ExpandedBlueprint.expandedBlueprint(OWNER, List.of(slice)));
    }

    private static SliceTargetValue ownedTarget() {
        return SliceTargetValue.sliceTargetValue(V1, 5, Option.some(OWNER));
    }

    private static SliceTargetValue unownedTarget() {
        return SliceTargetValue.sliceTargetValue(V1, 5, Option.<BlueprintId>none());
    }

    private static Option<SliceRequirementValue> operatorRequirement() {
        return Option.some(SliceRequirementValue.sliceRequirementValue(8, "test", Option.none(), 0L));
    }

    /// s18, s19: an owned target's requirement write witnesses the target at the observed value AND the owner's blueprint at its
    /// committed value, in that order.
    @Test
    void requirementTransaction_ownedTarget_witnessesTheTargetThenTheOwnerBlueprint() {
        var store = store();
        var observed = ownedTarget();
        var blueprint = ownerBlueprint();

        put(store, TARGET_KEY, observed);
        put(store, OWNER_KEY, blueprint);

        var transaction = SliceRequirements.requirementTransaction(store, observed, OPERATOR_KEY, operatorRequirement()).unwrap();

        assertThat(transaction.guards()).containsExactly(new ReadWitness<AetherKey>(TARGET_KEY, Option.some(observed)),
                                                         new ReadWitness<AetherKey>(OWNER_KEY, Option.some(blueprint)));
    }

    /// s18, s20: a target with no owner is witnessed on the target alone; there is no blueprint to witness.
    @Test
    void requirementTransaction_unownedTarget_witnessesTheTargetOnly() {
        var store = store();
        var observed = unownedTarget();

        put(store, TARGET_KEY, observed);

        var transaction = SliceRequirements.requirementTransaction(store, observed, OPERATOR_KEY, operatorRequirement()).unwrap();

        assertThat(transaction.guards()).containsExactly(new ReadWitness<AetherKey>(TARGET_KEY, Option.some(observed)));
    }

    /// s20: an owner that is not committed refuses the write as an orphan; nothing is built.
    @Test
    void requirementTransaction_ownerNotCommitted_failsAsOrphanTarget() {
        var store = store();
        var observed = ownedTarget();

        put(store, TARGET_KEY, observed);

        var built = SliceRequirements.requirementTransaction(store, observed, OPERATOR_KEY, operatorRequirement());

        assertThat(built.isFailure()).isTrue();
        built.onFailure(cause -> assertThat(cause).isEqualTo(new SliceTargetFence.SliceTargetFenceError.OrphanTarget(BASE, OWNER)));
    }

    /// s18, s19: an expired requirement removed while the combined count stays at the target is a held-target write; it carries the same
    /// two witnesses.
    @Test
    void combinerTransaction_expiredRequirementCountUnchanged_witnessesTheTargetThenTheOwnerBlueprint() {
        var store = store();
        var target = ownedTarget();
        var blueprint = ownerBlueprint();

        put(store, TARGET_KEY, target);
        put(store, OWNER_KEY, blueprint);
        put(store, OPERATOR_KEY, SliceRequirementValue.sliceRequirementValue(8, "test", Option.some(NOW - 1), 0L));

        var transaction = SliceRequirements.combinerTransaction(store, BASE, NOW, 100).unwrap();

        assertThat(transaction.mutations().stream().map(KVCommand.Mutation::key).toList()).contains(OPERATOR_KEY);
        assertThat(transaction.guards()).containsExactly(new ReadWitness<AetherKey>(TARGET_KEY, Option.some(target)),
                                                         new ReadWitness<AetherKey>(OWNER_KEY, Option.some(blueprint)));
    }

    /// s20: the held-target write of an orphaned target (owner not committed) is refused.
    @Test
    void combinerTransaction_expiredRequirementOwnerNotCommitted_failsAsOrphanTarget() {
        var store = store();

        put(store, TARGET_KEY, ownedTarget());
        put(store, OPERATOR_KEY, SliceRequirementValue.sliceRequirementValue(8, "test", Option.some(NOW - 1), 0L));

        var built = SliceRequirements.combinerTransaction(store, BASE, NOW, 100);

        assertThat(built.isFailure()).isTrue();
        built.onFailure(cause -> assertThat(cause).isEqualTo(new SliceTargetFence.SliceTargetFenceError.OrphanTarget(BASE, OWNER)));
    }
}

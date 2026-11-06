// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceRequirementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.RequirementKind;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceRequirementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// rc6 §3.1 `POST /api/v1/scale` response and write contents, through the real router over a fenced store (v2713 survivors
/// n01-n03, n07-n09, n11, n12, n14-n16, n19): `release:false` is a scale, release refuses a placement and a slice outside the blueprint,
/// the response names status/instances/target, the written requirement carries "aether scale", the created count and the placement,
/// and a release with nothing committed answers success and writes nothing.
class SliceRoutesScaleResponseTest {
    private static final Artifact SLICE = SliceRoutesScaleFloorTest.SLICE;
    private static final Artifact OTHER = Artifact.artifact("org.example:not-declared:1.0.0").unwrap();
    private static final SliceTargetKey TARGET_KEY = SliceTargetKey.sliceTargetKey(SLICE.base());
    private static final SliceRequirementKey OPERATOR_KEY = SliceRequirementKey.sliceRequirementKey(SLICE.base(), RequirementKind.OPERATOR);

    private final List<KVCommand<AetherKey>> applied = new CopyOnWriteArrayList<>();
    private final KVStore<AetherKey, AetherValue> store = SliceRoutesScaleFloorTest.fencedStore();

    @Test
    void releaseFalse_withInstances_isAScale_notARelease() {
        commitTarget(4);

        var response = post("{\"artifact\":\"" + SLICE.asString() + "\",\"release\":false,\"instances\":3}");

        assertThat(response.status.get()).as(response.body()).isEqualTo(HttpStatus.OK);
        assertThat(compact(response)).contains("\"status\":\"scaled\"").contains("\"instances\":3");
        assertThat(requirementValues()).extracting(SliceRequirementValue::instances).containsExactly(3);
    }

    @Test
    void release_withPlacement_isRefused_andWritesNothing() {
        commitTarget(4);
        commitOperator(7);

        var response = post("{\"artifact\":\"" + SLICE.asString() + "\",\"release\":true,\"placement\":\"WORKER_ONLY\"}");

        assertThat(response.status.get()).as(response.body()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(applied).isEmpty();
    }

    @Test
    void release_ofASliceOutsideTheBlueprint_isRefused_andWritesNothing() {
        var response = post("{\"artifact\":\"" + OTHER.asString() + "\",\"release\":true}");

        assertThat(response.status.get()).as(response.body()).isNotEqualTo(HttpStatus.OK);
        assertThat(applied).isEmpty();
    }

    @Test
    void release_withCommittedRequirement_answersReleasedWithZeroInstances() {
        commitTarget(4);
        commitOperator(7);

        var response = post("{\"artifact\":\"" + SLICE.asString() + "\",\"release\":true}");

        assertThat(response.status.get()).as(response.body()).isEqualTo(HttpStatus.OK);
        assertThat(compact(response)).contains("\"status\":\"released\"").contains("\"instances\":0").contains("\"target\":4");
        assertThat(store.get(OPERATOR_KEY).isEmpty()).as("the operator requirement is gone").isTrue();
    }

    @Test
    void release_withNothingCommitted_answersSuccess_andWritesNothing() {
        commitTarget(4);

        var response = post("{\"artifact\":\"" + SLICE.asString() + "\",\"release\":true}");

        assertThat(response.status.get()).as("a release whose postcondition already holds is not a 409").isEqualTo(HttpStatus.OK);
        assertThat(compact(response)).contains("\"status\":\"released\"");
        assertThat(applied).isEmpty();
    }

    @Test
    void scale_aboveThree_reportsTheUnclampedTarget_andWritesTheOperatorReason() {
        commitTarget(4);

        var response = post("{\"artifact\":\"" + SLICE.asString() + "\",\"instances\":7}");

        assertThat(response.status.get()).as(response.body()).isEqualTo(HttpStatus.OK);
        assertThat(compact(response)).contains("\"status\":\"scaled\"").contains("\"instances\":7").contains("\"target\":7");
        assertThat(requirementValues()).extracting(SliceRequirementValue::reason).containsExactly("aether scale");
    }

    @Test
    void scale_withPlacement_writesThePlacementToTheTarget() {
        commitTarget(4);

        var response = post("{\"artifact\":\"" + SLICE.asString() + "\",\"instances\":5,\"placement\":\"WORKER_ONLY\"}");

        assertThat(response.status.get()).as(response.body()).isEqualTo(HttpStatus.OK);
        assertThat(targetValues()).extracting(SliceTargetValue::placement).containsExactly("WORKER_ONLY");
    }

    @Test
    void scale_ofASliceWithoutTarget_createsItAtTheRequestedCount() {
        var response = post("{\"artifact\":\"" + SLICE.asString() + "\",\"instances\":5}");

        assertThat(response.status.get()).as(response.body()).isEqualTo(HttpStatus.OK);
        assertThat(targetValues()).extracting(SliceTargetValue::targetInstances).containsExactly(5);
        assertThat(requirementValues()).extracting(SliceRequirementValue::instances).containsExactly(5);
    }

    @Test
    void guardMaxInstances_aboveMax_namesRequestedThenMax() {
        commitTarget(4, 6);

        var message = SliceRoutes.guardMaxInstances(store, SLICE, 9).await().fold(Cause::message, _ -> "");

        assertThat(message).isEqualTo("Requested 9 instances but the slice's maxInstances is 6");
    }

    private SliceRoutesScaleFloorTest.RecordingResponseWriter post(String body) {
        var node = new SliceRoutesScaleFloorTest.ScaleManageableNode(SliceRoutesScaleFloorTest.listingBlueprintService(),
                                                                     store,
                                                                     commands -> record(commands));
        var router = ManagementRouter.managementRouter(SliceRoutes.sliceRoutes(() -> node));
        var recorder = new SliceRoutesScaleFloorTest.RecordingResponseWriter();

        assertThat(router.handle(SliceRoutesScaleFloorTest.postRequest(ManagementRoute.SLICE_SCALE.prefix(), body), recorder)).isTrue();
        recorder.awaitStatus();

        return recorder;
    }

    private Promise<List<Object>> record(List<KVCommand<AetherKey>> commands) {
        applied.addAll(commands);

        return Promise.success(store.process(store.createBatch(commands)));
    }

    private void commitTarget(int instances) {
        commit(TARGET_KEY, SliceTargetValue.sliceTargetValue(SLICE.version(), instances));
    }

    private void commitTarget(int instances, int max) {
        commit(TARGET_KEY, new SliceTargetValue(SLICE.version(), instances, 2, Option.none(), "CORE_ONLY", 0L, Option.some(max), Option.none(), Option.none()));
    }

    private void commitOperator(int instances) {
        commit(OPERATOR_KEY, SliceRequirementValue.sliceRequirementValue(instances, "aether scale", Option.none(), 0L));
    }

    private void commit(AetherKey key, AetherValue value) {
        store.process(store.createBatch(List.<KVCommand<AetherKey>> of(new KVCommand.Put<AetherKey, AetherValue>(key, value))));
    }

    private List<SliceTargetValue> targetValues() {
        return applied.stream().flatMap(SliceRoutesScaleFloorTest::targetValues).toList();
    }

    private List<SliceRequirementValue> requirementValues() {
        return applied.stream().flatMap(SliceRoutesScaleResponseTest::requirementsOf).toList();
    }

    private static Stream<SliceRequirementValue> requirementsOf(KVCommand<AetherKey> command) {
        return switch (command) {
            case KVCommand.LeaderTransaction<AetherKey, ?> transaction -> transaction.mutations()
                                                                                       .stream()
                                                                                       .filter(m -> m.key() instanceof SliceRequirementKey key
                                                                                                    && key.equals(OPERATOR_KEY))
                                                                                       .flatMap(m -> m.replacement().stream())
                                                                                       .filter(SliceRequirementValue.class::isInstance)
                                                                                       .map(SliceRequirementValue.class::cast);
            default -> Stream.empty();
        };
    }

    private static String compact(SliceRoutesScaleFloorTest.RecordingResponseWriter response) {
        return response.body().replace(" ", "");
    }
}

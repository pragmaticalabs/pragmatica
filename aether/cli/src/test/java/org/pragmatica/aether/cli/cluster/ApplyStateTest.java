// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.cluster.DiffAction;
import org.pragmatica.aether.config.cluster.DiffPlan;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.environment.SourceName.sourceNameOrDefault;

class ApplyStateTest {
    private static final ClusterName CLUSTER = ClusterName.clusterName("state-test").unwrap();

    private static ApplyState fullState() {
        return ApplyState.applyState(ApplyStateFixtures.APPLY_ID,
                                     CLUSTER,
                                     "hash-1",
                                     5_000_000_000L,
                                     "2026-10-28T10:00:00Z",
                                     ApplyPhase.COMMITTING,
                                     List.of(ApplyAction.applyAction("AddRole|eu|worker|->2", 2, ApplyAction.Status.IN_PROGRESS),
                                             ApplyAction.applyAction("RemoveSource|old||", 0, ApplyAction.Status.PENDING)),
                                     List.of(ApplyResource.applyResource("AddRole|eu|worker|->2",
                                                                         "eu-worker-1",
                                                                         "hetzner",
                                                                         Option.some("srv-1"),
                                                                         ApplyResource.State.JOINED),
                                             ApplyResource.applyResource("AddRole|eu|worker|->2",
                                                                         "eu-worker-2",
                                                                         "hetzner",
                                                                         Option.none(),
                                                                         ApplyResource.State.INTENDED)),
                                     List.of(ApplyReservation.applyReservation("eu", "worker", 4_000_000_001L, 4_000_000_003L)),
                                     List.of(CreatedResource.DockerNetwork.dockerNetwork("aether-net")),
                                     List.of("old-1"));
    }

    @Nested
    class RoundTrip {
        @Test
        void roundTrip_preservesActionsReservationsResourceStates() {
            var state = fullState();

            ApplyState.fromJson(ApplyState.toJson(state))
                      .onFailure(cause -> fail(cause.message()))
                      .onSuccess(restored -> assertThat(restored).isEqualTo(state));
        }

        @Test
        void roundTrip_preservesEveryPhaseAndEveryResourceState() {
            for (var phase : ApplyPhase.values()) {
                assertThat(ApplyState.fromJson(ApplyState.toJson(fullState().withPhase(phase))).unwrap().phase()).isEqualTo(phase);
            }

            for (var resourceState : ApplyResource.State.values()) {
                var row = ApplyResource.applyResource("k", "n-1", "fake", Option.some("srv"), resourceState);

                assertThat(ApplyState.fromJson(ApplyState.toJson(fullState().withNode(row))).unwrap().resources()).contains(row);
            }
        }

        @Test
        void roundTrip_escapesQuotesInStrings() {
            var row = ApplyResource.applyResource("a\"b\\c", "n-1", "fake", Option.some("s\"1"), ApplyResource.State.CREATED);

            assertThat(ApplyState.fromJson(ApplyState.toJson(fullState().withNode(row))).unwrap().resources()).contains(row);
        }
    }

    /// B2: a ledger that `save` accepted must be readable. Every control code point is written escaped.
    @Test
    void roundTrip_survivesEveryControlCodePoint_quotesBackslashesAndSurrogatePairs() {
        var specials = new java.util.ArrayList<String>();

        for (int c = 0; c < 0x20; c++) {
            specials.add("a" + (char) c + "b");
        }

        specials.add("q\"b\\s\n\r\t");
        specials.add("emoji \uD83D\uDE00 and \u2028");

        for (var text : specials) {
            var row = ApplyResource.applyResource(text, "n-1", text, Option.some(text), ApplyResource.State.CREATED);
            var state = ApplyStateFixtures.state(CLUSTER).withNode(row);

            ApplyState.fromJson(ApplyState.toJson(state))
                      .onFailure(cause -> fail("unreadable for " + java.util.HexFormat.of().formatHex(text.getBytes(java.nio.charset.StandardCharsets.UTF_16BE)) + ": " + cause.message()))
                      .onSuccess(restored -> assertThat(restored).isEqualTo(state));
        }
    }

    /// T3: only the integer 2 is schema 2; 2.0, 2.9 and 2e0 are other documents.
    @Test
    void schemaVersion_fractionalOrExponentOrOutOfRangeForms_areRefused_namingTheSchema() {
        var good = ApplyState.toJson(fullState());

        for (var form : java.util.List.of("2.0", "2.9", "2.0000001", "2e0", "20e-1", "4294967298", "0", "1", "-2")) {
            ApplyState.fromJson(good.replace("\"schemaVersion\": 2", "\"schemaVersion\": " + form))
                      .onSuccess(state -> fail("schema " + form + " must be refused"))
                      .onFailure(cause -> assertThat(cause.message()).as(form).contains("schema " + java.util.Map.of("2.0", "2.0", "2.9", "2.9", "2.0000001", "2.0000001", "2e0", "2.0", "20e-1", "2.0", "4294967298", "4294967298", "0", "0", "1", "1", "-2", "-2").get(form)).contains("not supported").contains("reads and writes schema 2"));
        }
    }

    @Test
    void toJson_writesTheStatesOwnApplyId_notAConstant() {
        var id = ApplyState.newApplyId();
        var state = ApplyState.applyState(id, CLUSTER, "h", 0L, "2026-10-28T10:00:00Z", ApplyPhase.RUNNING, List.of(), List.of(), List.of(), List.of(), List.of());

        assertThat(ApplyState.toJson(state)).contains("\"applyId\": \"" + id + "\"");
        assertThat(ApplyState.fromJson(ApplyState.toJson(state)).unwrap().applyId()).isEqualTo(id);
    }

    @Test
    void constructor_copiesEveryList_soLaterMutationOfTheCallersListsCannotChangeTheState() {
        var actions = new java.util.ArrayList<ApplyAction>();
        var resources = new java.util.ArrayList<ApplyResource>();
        var reservations = new java.util.ArrayList<ApplyReservation>();
        var created = new java.util.ArrayList<CreatedResource>();
        var destroyed = new java.util.ArrayList<String>();
        var state = ApplyState.applyState(ApplyStateFixtures.APPLY_ID, CLUSTER, "h", 0L, "t", ApplyPhase.RUNNING, actions, resources, reservations, created, destroyed);

        actions.add(ApplyAction.applyAction("k", 0, ApplyAction.Status.PENDING));
        resources.add(ApplyResource.applyResource("k", "n", "p", Option.none(), ApplyResource.State.INTENDED));
        reservations.add(ApplyReservation.applyReservation("s", "r", 1L, 2L));
        created.add(CreatedResource.DockerNetwork.dockerNetwork("net"));
        destroyed.add("x");

        assertThat(state.actions()).isEmpty();
        assertThat(state.resources()).isEmpty();
        assertThat(state.reservations()).isEmpty();
        assertThat(state.createdResources()).isEmpty();
        assertThat(state.destroyedNodeIds()).isEmpty();
    }

    @Nested
    class Refusals {
        private String mutated(String from, String to) {
            var json = ApplyState.toJson(fullState());

            assertThat(json).as("fixture must contain the text it mutates").contains(from);

            return json.replace(from, to);
        }

        private void assertRefused(String json, String... mentions) {
            ApplyState.fromJson(json)
                      .onSuccess(state -> fail("must be refused, was read as " + state))
                      .onFailure(cause -> assertThat(cause.message()).contains(mentions));
        }

        @Test
        void schemaVersionHigherThanKnown_isRefused_namingTheVersion() {
            assertRefused(mutated("\"schemaVersion\": 2", "\"schemaVersion\": 3"), "schema 3", "not supported");
        }

        @Test
        void schemaVersionMissing_isRefused_asAPreSchema2File() {
            assertRefused(mutated("\"schemaVersion\": 2,", ""), "pre-2");
        }

        @Test
        void unknownPhase_isRefused_notDefaulted() {
            assertRefused(mutated("\"COMMITTING\"", "\"FUTURE_PHASE\""), "FUTURE_PHASE");
        }

        @Test
        void unknownResourceState_isRefused_notDefaulted() {
            assertRefused(mutated("\"JOINED\"", "\"VAPORISED\""), "VAPORISED");
        }

        @Test
        void unknownActionStatus_isRefused_notDefaulted() {
            assertRefused(mutated("\"IN_PROGRESS\"", "\"HALF_DONE\""), "HALF_DONE");
        }

        @Test
        void applyId_wrongLengthOrCaseOrAlphabet_isRefused_byTheGrammarAndByTheParser() {
            var id = ApplyStateFixtures.APPLY_ID;

            for (var bad : List.of(id.substring(1), id + "q", id.toUpperCase(java.util.Locale.ROOT), id.substring(1) + "-", "")) {
                assertThat(ApplyState.isApplyId(bad)).as(bad).isFalse();
                assertRefused(mutated(id, bad), "applyId");
            }

            assertThat(ApplyState.isApplyId(id)).isTrue();
        }

        @Test
        void configHashThatIsANumber_isRefused_notReadAsText() {
            assertRefused(mutated("\"hash-1\"", "123"), "configHash");
        }

        @Test
        void applyIdThatIsNotALowercaseUlid_isRefused() {
            assertRefused(mutated(ApplyStateFixtures.APPLY_ID, "NOT-A-ULID"), "NOT-A-ULID");
        }

        @Test
        void schemaVersionWrittenAsAString_isRefused() {
            assertRefused(mutated("\"schemaVersion\": 2", "\"schemaVersion\": \"2\""), "not supported");
        }

        @Test
        void blankConfigHash_isRefused() {
            assertRefused(mutated("\"hash-1\"", "\"  \""), "configHash");
        }

        @Test
        void missingStoredVersion_isRefused_notReadAsZero() {
            assertRefused(mutated("\"storedVersion\": 5000000000,", ""), "storedVersion");
        }

        @Test
        void missingArray_isRefused_notReadAsEmpty() {
            assertRefused(mutated("\"reservations\"", "\"renamed\""), "reservations");
        }
    }

    @Test
    void holdsVm_isTrueExactlyForTheStatesThatMayStandAtTheProvider() {
        var expected = java.util.EnumSet.of(ApplyResource.State.CREATED,
                                            ApplyResource.State.JOINED,
                                            ApplyResource.State.ADOPTED,
                                            ApplyResource.State.TERMINATING,
                                            ApplyResource.State.TERMINATE_FAILED);

        for (var state : ApplyResource.State.values()) {
            assertThat(state.holdsVm()).as(state.name()).isEqualTo(expected.contains(state));
        }
    }

    @Nested
    class ApplyIds {
        @Test
        void newApplyId_isALowercaseUlid_insideEveryProvidersLabelGrammar() {
            var id = ApplyState.newApplyId();

            assertThat(id).hasSize(26).matches("[0-9a-z]{26}");
            assertThat(ApplyState.isApplyId(id)).isTrue();
        }

        @Test
        void newApplyId_isFreshEachTime() {
            assertThat(ApplyState.newApplyId()).isNotEqualTo(ApplyState.newApplyId());
        }
    }

    @Nested
    class Actions {
        @Test
        void fromPlan_listsAdditionsModificationsRemovals_inExecutionOrder_allPending() {
            var eu = sourceNameOrDefault("eu");
            var plan = DiffPlan.diffPlan(List.of(new DiffAction.AddRole(eu, NodeRole.WORKER, 2)),
                                         List.of(new DiffAction.ScaleUp(eu, NodeRole.CORE, 3, 5)),
                                         List.of(new DiffAction.RemoveSource(sourceNameOrDefault("old"))),
                                         List.of());

            var actions = ApplyAction.fromPlan(plan);

            assertThat(actions).extracting(ApplyAction::key)
                               .containsExactly("AddRole|eu|worker|->2", "ScaleUp|eu|core|3->5", "RemoveSource|old||");
            assertThat(actions).extracting(ApplyAction::planned).containsExactly(2, 2, 0);
            assertThat(actions).extracting(ApplyAction::status).containsOnly(ApplyAction.Status.PENDING);
        }

        @Test
        void actionKey_namesKindSourceRoleAndChange_forEveryKindOfAction() {
            var eu = sourceNameOrDefault("eu");
            var expected = new java.util.LinkedHashMap<DiffAction, String>();

            expected.put(new DiffAction.AddSource(eu), "AddSource|eu||");
            expected.put(new DiffAction.RemoveSource(eu), "RemoveSource|eu||");
            expected.put(new DiffAction.AddRole(eu, NodeRole.WORKER, 2), "AddRole|eu|worker|->2");
            expected.put(new DiffAction.RemoveRole(eu, NodeRole.WORKER, 2), "RemoveRole|eu|worker|2->");
            expected.put(new DiffAction.ScaleUp(eu, NodeRole.CORE, 3, 5), "ScaleUp|eu|core|3->5");
            expected.put(new DiffAction.ScaleDown(eu, NodeRole.CORE, 5, 3), "ScaleDown|eu|core|5->3");
            expected.put(new DiffAction.RuntimeChange(eu, NodeRole.CORE, "jvm", "native"), "RuntimeChange|eu|core|jvm->native");
            expected.put(new DiffAction.RuntimeProfileChange("default", "heap"), "RuntimeProfileChange|default||heap");
            expected.put(new DiffAction.SourceFieldChange(eu, "zone"), "SourceFieldChange|eu||zone");
            expected.put(new DiffAction.ClusterLevelChange("version", "1", "2"), "ClusterLevelChange|||version:1->2");
            expected.put(new DiffAction.CommunityPlacementChange(), "CommunityPlacementChange|||");
            expected.put(new DiffAction.ImmutableFieldChange("cluster.name"), "ImmutableFieldChange|||cluster.name");

            expected.forEach((action, key) -> assertThat(ApplyAction.actionKey(action)).isEqualTo(key));
        }

        @Test
        void withActionStatus_changesOnlyTheNamedAction_andIgnoresAnUnknownKey() {
            var state = ApplyState.applyState(ApplyStateFixtures.APPLY_ID,
                                              CLUSTER,
                                              "h",
                                              0L,
                                              "t",
                                              ApplyPhase.RUNNING,
                                              List.of(ApplyAction.applyAction("a", 1, ApplyAction.Status.PENDING), ApplyAction.applyAction("b", 2, ApplyAction.Status.PENDING)),
                                              List.of(),
                                              List.of(),
                                              List.of(),
                                              List.of());

            assertThat(state.withActionStatus("b", ApplyAction.Status.DONE).actions()).containsExactly(ApplyAction.applyAction("a", 1, ApplyAction.Status.PENDING),
                                                                                                         ApplyAction.applyAction("b", 2, ApplyAction.Status.DONE));
            assertThat(state.withActionStatus("zzz", ApplyAction.Status.DONE)).isEqualTo(state);
        }

        @Test
        void withNode_keepsCreationOrder_whenARowChangesState() {
            var first = ApplyResource.applyResource("k", "n-1", "fake", Option.none(), ApplyResource.State.INTENDED);
            var second = ApplyResource.applyResource("k", "n-2", "fake", Option.none(), ApplyResource.State.INTENDED);
            var state = ApplyStateFixtures.state(CLUSTER).withNode(first).withNode(second).withNode(ApplyResource.applyResource("k", "n-1", "fake", Option.some("srv"), ApplyResource.State.CREATED));

            assertThat(state.resources()).extracting(ApplyResource::nodeId).containsExactly("n-1", "n-2");
            assertThat(state.resources().getFirst().state()).isEqualTo(ApplyResource.State.CREATED);
        }

        @Test
        void withDestroyedNode_appendsInOrder() {
            var state = ApplyStateFixtures.state(CLUSTER).withDestroyedNode("a").withDestroyedNode("b");

            assertThat(state.destroyedNodeIds()).containsExactly("a", "b");
        }

        @Test
        void withNode_replacesTheRowOfTheSameNodeId_appendsOtherwise() {
            var intended = ApplyResource.applyResource("k", "n-1", "fake", Option.none(), ApplyResource.State.INTENDED);
            var created = ApplyResource.applyResource("k", "n-1", "fake", Option.some("srv-1"), ApplyResource.State.CREATED);
            var other = ApplyResource.applyResource("k", "n-2", "fake", Option.none(), ApplyResource.State.INTENDED);

            var state = ApplyStateFixtures.state(CLUSTER).withNode(intended).withNode(other).withNode(created);

            assertThat(state.resources()).containsExactlyInAnyOrder(created, other);
        }
    }
}

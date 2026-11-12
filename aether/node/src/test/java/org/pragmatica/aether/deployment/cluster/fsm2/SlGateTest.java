// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Set;

import org.pragmatica.aether.slice.kvstore.AetherKey.FsmWarningKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.SlFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// The activation gate (R-3.3.5): the dependency half and the schema half. NT-18, 19, 20, 21, 22.
class SlGateTest {
    private static final String APP = "org.example:app:1.0.0";

    private FsmHarness deployedWithDependency() {
        var h = threeCores();

        h.activate();
        publish(h,
                blueprint(APP, false, ATTEMPT, slice(ORDERS, 1, 1, Set.of(BILLING)), slice(BILLING, 1, 1, Set.of())),
                Option.none());

        return h;
    }

    @Nested
    class Dependencies {
        /// NT-18. Red when: a dependent gets goal ACTIVE while its dependency has no ACTIVE instance.
        @Test
        void dependent_staysLoaded_untilItsDependencyIsActive() {
            var h = deployedWithDependency();
            var ordersHost = hosts(h, ORDERS).getFirst();
            var billingHost = hosts(h, BILLING).getFirst();

            node(h, ordersHost, 1L).follows(ORDERS);
            h.drain();
            node(h, billingHost, 2L).follows(BILLING);
            h.drain();
            assertThat(stateOf(h, ordersHost, ORDERS)).isEqualTo(SliceObservedState.LOADED);
            assertThat(goalOf(h, ordersHost, ORDERS)).as("BILLING is LOADED, not ACTIVE: ORDERS waits")
                      .isEqualTo(Option.some(SliceGoal.LOADED));
            assertThat(goalOf(h, billingHost, BILLING)).as("the dependency itself has none to wait for")
                      .isEqualTo(Option.some(SliceGoal.ACTIVE));
        }

        /// NT-19. Red when: the dependency's ACTIVE does not activate the dependent in the same drain.
        @Test
        void dependencyActive_activatesTheDependent_inTheSameDrain() {
            var h = deployedWithDependency();
            var ordersHost = hosts(h, ORDERS).getFirst();
            var billingHost = hosts(h, BILLING).getFirst();

            node(h, ordersHost, 1L).follows(ORDERS);
            node(h, billingHost, 2L).follows(BILLING);
            h.drain();
            node(h, billingHost, 2L).follows(BILLING);
            h.drain();
            assertThat(stateOf(h, billingHost, BILLING)).isEqualTo(SliceObservedState.ACTIVE);
            assertThat(goalOf(h, ordersHost, ORDERS)).isEqualTo(Option.some(SliceGoal.ACTIVE));
        }
    }

    @Nested
    class Schema {
        private SchemaVersionValue record(SchemaStatus status) {
            return new SchemaVersionValue("orders-db",
                                          1,
                                          "V1",
                                          status,
                                          "org.example:migrations:1.0.0",
                                          blueprintId(APP),
                                          0,
                                          FsmHarness.START_WALL);
        }

        private FsmHarness heldByPendingSchema() {
            var h = threeCores();

            h.activate();
            publish(h, blueprint(APP, false, ATTEMPT, slice(ORDERS, 1, 1, Set.of())), Option.none());
            h.seed(new SchemaVersionKey("orders-db"), record(SchemaStatus.PENDING));
            h.drain();
            node(h, hosts(h, ORDERS).getFirst(), 1L).follows(ORDERS);
            h.drain();

            return h;
        }

        /// NT-20. Red when: COMPLETED does not activate the held instance.
        @Test
        void completed_activatesTheHeldInstance() {
            var h = heldByPendingSchema();
            var host = hosts(h, ORDERS).getFirst();

            assertThat(goalOf(h, host, ORDERS)).as("held while PENDING").isEqualTo(Option.some(SliceGoal.LOADED));
            assertThat(h.warns("schema-hold")).hasSize(1);
            h.seed(new SchemaVersionKey("orders-db"), record(SchemaStatus.COMPLETED));
            h.drain();
            assertThat(goalOf(h, host, ORDERS)).isEqualTo(Option.some(SliceGoal.ACTIVE));
            assertThat(h.warns("schema-hold-cleared")).hasSize(1);
            assertThat(h.warns("schema-status-readable")).as("never reported unknown: nothing to close").isEmpty();
        }

        /// D.6 rows 15/16 (know 7cc9e79f61), the subject-gone close. Red when: the instance of a held slice goes and `schema-hold` stays
        /// open (no `schema-hold-cleared`, same subject, reason subject-gone).
        @Test
        void heldInstanceGone_closesTheHold_subjectGone() {
            var h = heldByPendingSchema();
            var host = hosts(h, ORDERS).getFirst();

            assertThat(h.warns("schema-hold-cleared")).isEmpty();
            h.seedRemove(new SliceObservedKey(host, ORDERS));
            h.drain();
            var cleared = h.warns("schema-hold-cleared");

            assertThat(cleared).hasSize(1);
            assertThat(cleared.getFirst().subject()).isEqualTo(ORDERS.asString());
            assertThat(cleared.getFirst().message()).contains(Recoveries.SUBJECT_GONE);
        }

        private FsmHarness heldTwiceByPendingSchema() {
            var h = threeCores();

            h.activate();
            publish(h, blueprint(APP, false, ATTEMPT, slice(ORDERS, 2, 1, Set.of())), Option.none());
            h.seed(new SchemaVersionKey("orders-db"), record(SchemaStatus.PENDING));
            h.drain();
            var token = 1L;

            for (var host : hosts(h, ORDERS)) {
                node(h, host, token++).follows(ORDERS);
                h.drain();
            }

            assertThat(h.model().holdSignatures.size()).as("precondition: two instances held").isEqualTo(2);

            return h;
        }

        /// D.6 rows 15/16, CTO ruling on #2831 (v2831a probe p4). `schema-hold` has the artifact as its subject, so it closes only when the
        /// hold set of the artifact is empty. Red when: one of two held instances going raises `schema-hold-cleared` while the other is still
        /// held, or the last one going does not raise it exactly once.
        @Test
        void twoHeldInstances_oneGone_keepsTheHoldOpen_untilTheLastGoes() {
            var h = heldTwiceByPendingSchema();
            var hosts = hosts(h, ORDERS);

            h.seedRemove(new SliceObservedKey(hosts.getFirst(), ORDERS));
            h.drain();
            assertThat(h.model().holdSignatures.size()).as("the other instance is still held").isEqualTo(1);
            assertThat(h.warns("schema-hold-cleared")).as("not while an instance of the artifact is held").isEmpty();
            h.seedRemove(new SliceObservedKey(hosts.get(1), ORDERS));
            h.drain();
            var cleared = h.warns("schema-hold-cleared");

            assertThat(cleared).as("once, when the last held instance goes").hasSize(1);
            assertThat(cleared.getFirst().subject()).isEqualTo(ORDERS.asString());
            assertThat(cleared.getFirst().message()).contains(Recoveries.SUBJECT_GONE);
        }

        /// D.6 rows 15/16, CTO ruling on #2831. Red when: the release of two held instances raises `schema-hold-cleared` per instance (the
        /// first while the other is still held) instead of once.
        @Test
        void twoHeldInstances_released_closesTheHoldOnce() {
            var h = heldTwiceByPendingSchema();

            h.seed(new SchemaVersionKey("orders-db"), record(SchemaStatus.COMPLETED));
            h.drain();
            assertThat(hosts(h, ORDERS)).allMatch(host -> goalOf(h, host, ORDERS).equals(Option.some(SliceGoal.ACTIVE)));
            assertThat(h.warns("schema-hold-cleared")).hasSize(1);
        }

        /// D.6 rows 15/16, CTO ruling on #2831. Red when: one of two held instances failing closes the hold while the other is still held.
        @Test
        void twoHeldInstances_oneFails_keepsTheHoldOpen() {
            var h = heldTwiceByPendingSchema();

            node(h, hosts(h, ORDERS).getFirst(), 1L).fails(ORDERS, true);
            h.drain();
            assertThat(h.warns("schema-hold-cleared")).isEmpty();
        }

        /// D.6 rows 15/16. Red when: a held slice that fails drops its hold silently (the hold signature is cleared with no close).
        @Test
        void heldInstanceFails_closesTheHold() {
            var h = heldByPendingSchema();
            var host = hosts(h, ORDERS).getFirst();

            node(h, host, 1L).fails(ORDERS, true);
            h.drain();
            var cleared = h.warns("schema-hold-cleared");

            assertThat(cleared).hasSize(1);
            assertThat(cleared.getFirst().subject()).isEqualTo(ORDERS.asString());
            assertThat(cleared.getFirst().message()).contains("failed");
        }

        /// D.6 row 17 (know 7cc9e79f61). Red when: a datasource reported `schema-status-unknown` whose status decodes again is not closed
        /// by `schema-status-readable`, same subject, once.
        @Test
        void unknownStatus_closedWhenTheStatusDecodes() {
            var h = heldByPendingSchema();

            h.seed(new SchemaVersionKey("orders-db"), record(SchemaStatus.UNKNOWN));
            h.drain();
            assertThat(h.warns("schema-status-readable")).isEmpty();
            h.seed(new SchemaVersionKey("orders-db"), record(SchemaStatus.COMPLETED));
            h.drain();
            h.seed(new SchemaVersionKey("orders-db"), record(SchemaStatus.COMPLETED));
            h.drain();
            var readable = h.warns("schema-status-readable");

            assertThat(readable).hasSize(1);
            assertThat(readable.getFirst().subject()).isEqualTo("orders-db");
            assertThat(readable.getFirst().message()).doesNotContain(Recoveries.SUBJECT_GONE);
        }

        /// D.6 row 17, the subject-gone close. Red when: removing the schema record of a reported datasource leaves the warning open.
        @Test
        void unknownStatus_recordRemoved_closesTheWarning_subjectGone() {
            var h = heldByPendingSchema();

            h.seed(new SchemaVersionKey("orders-db"), record(SchemaStatus.UNKNOWN));
            h.drain();
            h.seedRemove(new SchemaVersionKey("orders-db"));
            h.drain();
            var readable = h.warns("schema-status-readable");

            assertThat(readable).hasSize(1);
            assertThat(readable.getFirst().subject()).isEqualTo("orders-db");
            assertThat(readable.getFirst().message()).contains(Recoveries.SUBJECT_GONE);
            assertThat(h.model().schemaUnknownWarned.contains("orders-db")).isFalse();
        }

        /// NT-21. Red when: FAILED does not route `ActivationBlocked` and an audit line.
        @Test
        void failed_routesActivationBlocked_andAudits() {
            var h = heldByPendingSchema();

            h.seed(new SchemaVersionKey("orders-db"), record(SchemaStatus.FAILED));
            h.drain();
            assertThat(h.effects(ClusterEffect.Route.class).stream().map(ClusterEffect.Route::event)).anyMatch(event -> event instanceof RoutedEvent.ActivationBlocked blocked
                                                                                                                        && blocked.datasource()
                                                                                                                                  .equals("orders-db")
                                                                                                                        && blocked.slices()
                                                                                                                                  .contains(ORDERS.asString()));
            assertThat(h.effects(ClusterEffect.Audit.class)).anyMatch(audit -> audit.action()
                                                                                    .equals("schema-activation-blocked"));
            assertThat(goalOf(h,
                              hosts(h, ORDERS).getFirst(),
                              ORDERS)).as("still held")
                      .isEqualTo(Option.some(SliceGoal.LOADED));
        }

        /// NT-22. Red when: an UNKNOWN status submits anything, or does not WARN.
        @Test
        void unknownStatus_submitsNothing_andWarns() {
            var h = heldByPendingSchema();
            var before = h.submitsBesides(ClusterModel.SubmitKind.WARNINGS).size();
            var warningsBefore = h.submits(ClusterModel.SubmitKind.WARNINGS).size();

            h.seed(new SchemaVersionKey("orders-db"), record(SchemaStatus.UNKNOWN));
            h.drain();
            assertThat(h.submitsBesides(ClusterModel.SubmitKind.WARNINGS)).hasSize(before);
            assertThat(h.submits(ClusterModel.SubmitKind.WARNINGS)).as("exactly one WARNINGS transaction: the opened warning")
                      .hasSize(warningsBefore + 1);
            assertThat(h.warningPuts()).as("the open schema-status-unknown warning is persisted")
                      .last()
                      .isEqualTo(new FsmWarningKey("schema-status-unknown", "orders-db"));
            assertThat(h.warns("schema-status-unknown")).hasSize(1);
        }

        /// #2834: once per episode. Red when: a second UNKNOWN status for the same datasource raises `schema-status-unknown` again.
        @Test
        void unknownStatus_repeated_warnsOncePerEpisode() {
            var h = heldByPendingSchema();

            h.seed(new SchemaVersionKey("orders-db"), record(SchemaStatus.UNKNOWN));
            h.drain();
            h.seed(new SchemaVersionKey("orders-db"), record(SchemaStatus.UNKNOWN));
            h.drain();
            assertThat(h.warns("schema-status-unknown")).hasSize(1);
        }

        /// C12 PENDING. Red when: a PENDING record is not handed to the orchestrator.
        @Test
        void pending_dispatchesTheMigration() {
            var h = heldByPendingSchema();

            assertThat(h.effects(ClusterEffect.DispatchMigration.class)).anyMatch(dispatch -> dispatch.datasource()
                                                                                                      .equals("orders-db"));
        }

        /// Not a schema hold: an owner without a blocking record is never held (control of NT-20). Red when: the gate holds a slice with no record.
        @Test
        void noRecord_doesNotHold() {
            var h = threeCores();

            h.activate();
            publish(h, blueprint(APP, false, ATTEMPT, slice(ORDERS, 1, 1, Set.of())), Option.none());
            node(h, hosts(h, ORDERS).getFirst(), 1L).follows(ORDERS);
            h.drain();
            assertThat(goalOf(h, hosts(h, ORDERS).getFirst(), ORDERS)).isEqualTo(Option.some(SliceGoal.ACTIVE));
        }
    }

    @Nested
    class Permits {
        /// #1068. Red when: a LOADED slice whose version no committed target names is activated instead of unloaded.
        @Test
        void loadedRow_ofAVersionNoTargetNames_isUnloaded() {
            var h = threeCores();

            seedTarget(h, ORDERS_V2, 1, 1);
            seedGoal(h, CORE_1, ORDERS, SliceGoal.LOADED, G1);
            h.activate();
            node(h, CORE_1, 1L).follows(ORDERS);
            h.drain();
            assertThat(goalOf(h, CORE_1, ORDERS)).isEqualTo(Option.some(SliceGoal.UNLOADED));
            assertThat(h.store.get(new SliceObservedKey(CORE_1, ORDERS)).isPresent()).isTrue();
        }
    }
}

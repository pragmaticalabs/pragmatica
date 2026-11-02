// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Set;

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
            var before = h.submits().size();

            h.seed(new SchemaVersionKey("orders-db"), record(SchemaStatus.UNKNOWN));
            h.drain();
            assertThat(h.submits()).hasSize(before);
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

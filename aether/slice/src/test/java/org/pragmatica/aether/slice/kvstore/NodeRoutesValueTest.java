// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue.RouteEntry;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NodeRoutesValueTest {
    @Nested
    class BackwardCompatibleFactories {
        @Test
        void empty_hasZeroEpoch() {
            var v = NodeRoutesValue.empty();

            assertThat(v.routes()).isEmpty();
            assertThat(v.observedCoreEpoch()).isEqualTo(Epoch.ZERO);
        }

        @Test
        void nodeRoutesValue_listOnly_hasZeroEpoch() {
            var v = NodeRoutesValue.nodeRoutesValue(List.of(
                    RouteEntry.activeRoute("GET", "/users/", "list")));

            assertThat(v.routes()).hasSize(1);
            assertThat(v.observedCoreEpoch()).isEqualTo(Epoch.ZERO);
        }
    }

    @Nested
    class NewFactory {
        @Test
        void nodeRoutesValue_withEpoch_preservesEpoch() {
            var epoch = Epoch.epoch(7L, 0L);

            var v = NodeRoutesValue.nodeRoutesValue(List.of(
                    RouteEntry.activeRoute("POST", "/orders/", "create")), epoch);

            assertThat(v.observedCoreEpoch()).isEqualTo(epoch);
            assertThat(v.routes()).hasSize(1);
        }

        @Test
        void withObservedCoreEpoch_replacesEpoch_preservesRoutes() {
            var original = NodeRoutesValue.nodeRoutesValue(List.of(
                    RouteEntry.activeRoute("GET", "/api/", "fetch")));

            var updated = original.withObservedCoreEpoch(Epoch.epoch(42L, 3L));

            assertThat(updated.routes()).isEqualTo(original.routes());
            assertThat(updated.observedCoreEpoch()).isEqualTo(Epoch.epoch(42L, 3L));
        }
    }

    @Nested
    class CanonicalConstructorNormalization {
        @Test
        void construct_nullEpoch_normalizesToZero() {
            var v = new NodeRoutesValue(List.of(), null);

            assertThat(v.observedCoreEpoch()).isEqualTo(Epoch.ZERO);
        }

        @Test
        void construct_nullRoutes_normalizesToEmptyList() {
            var v = new NodeRoutesValue(null, Epoch.epoch(1L, 0L));

            assertThat(v.routes()).isEmpty();
            assertThat(v.observedCoreEpoch()).isEqualTo(Epoch.epoch(1L, 0L));
        }

        @Test
        void construct_validInput_storesBothFields() {
            var routes = List.of(RouteEntry.activeRoute("DELETE", "/items/", "remove"));
            var epoch = Epoch.epoch(100L, 0L);

            var v = new NodeRoutesValue(routes, epoch);

            assertThat(v.routes()).isEqualTo(routes);
            assertThat(v.observedCoreEpoch()).isEqualTo(epoch);
        }
    }

    @Nested
    class Equality {
        @Test
        void sameRoutesAndEpoch_areEqual() {
            var route = new RouteEntry("GET", "/x/", "m", "ACTIVE", 100, 1710000000000L, "PUBLIC");
            var a = NodeRoutesValue.nodeRoutesValue(List.of(route), Epoch.epoch(5L, 0L));
            var b = NodeRoutesValue.nodeRoutesValue(List.of(route), Epoch.epoch(5L, 0L));

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        void differentEpoch_notEqual() {
            var route = new RouteEntry("GET", "/x/", "m", "ACTIVE", 100, 1710000000000L, "PUBLIC");
            var routes = List.of(route);
            var a = NodeRoutesValue.nodeRoutesValue(routes, Epoch.epoch(5L, 0L));
            var b = NodeRoutesValue.nodeRoutesValue(routes, Epoch.epoch(6L, 0L));

            assertThat(a).isNotEqualTo(b);
        }
    }
}

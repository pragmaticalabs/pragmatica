// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


class ProvisionContextTest {

    @Nested
    class FactoryTests {

        @Test
        void provisionContext_short_setsDefaults() {
            var ctx = ProvisionContext.provisionContext("prod", "core", "eu-1",
                                                         ProvisionContext.PROVISIONED_BY_BOOTSTRAP);

            assertThat(ctx.clusterName()).isEqualTo("prod");
            assertThat(ctx.role()).isEqualTo("core");
            assertThat(ctx.sourceName()).isEqualTo("eu-1");
            assertThat(ctx.provisionedBy()).isEqualTo("bootstrap");
            assertThat(ctx.nodeId().isEmpty()).isTrue();
            assertThat(ctx.peers().isEmpty()).isTrue();
            assertThat(ctx.coreMax()).isEqualTo(ProvisionContext.DEFAULT_CORE_MAX);
            assertThat(ctx.extraTags()).isEmpty();
        }

        @Test
        void provisionContext_full_storesAllFields() {
            var ctx = ProvisionContext.provisionContext("prod", "worker", "ctm-default",
                                                         Option.some("eu-1-core-0"),
                                                         Option.some("n1:h1:6000,n2:h2:6000"),
                                                         5,
                                                         ProvisionContext.PROVISIONED_BY_CTM,
                                                         Map.of("custom-tag", "custom-value"));

            assertThat(ctx.nodeId().or("")).isEqualTo("eu-1-core-0");
            assertThat(ctx.peers().or("")).isEqualTo("n1:h1:6000,n2:h2:6000");
            assertThat(ctx.coreMax()).isEqualTo(5);
            assertThat(ctx.extraTags()).containsEntry("custom-tag", "custom-value");
        }
    }

    @Nested
    class SharedPreparationTests {

        @Test
        void forBootstrap_validInputs_emptyPeersAndDefaultCoreMax() {
            var ctx = ProvisionContext.forBootstrap("prod", "worker", "eu-1", "eu-1-worker-0");

            assertThat(ctx.clusterName()).isEqualTo("prod");
            assertThat(ctx.role()).isEqualTo("worker");
            assertThat(ctx.sourceName()).isEqualTo("eu-1");
            assertThat(ctx.nodeId().or("")).isEqualTo("eu-1-worker-0");
            assertThat(ctx.peers().isEmpty()).isTrue();
            assertThat(ctx.coreMax()).isEqualTo(ProvisionContext.DEFAULT_CORE_MAX);
            assertThat(ctx.provisionedBy()).isEqualTo(ProvisionContext.PROVISIONED_BY_BOOTSTRAP);
            assertThat(ctx.extraTags()).isEmpty();
        }

        @Test
        void forReplacement_validInputs_givenPeersAndGivenCoreMax() {
            var ctx = ProvisionContext.forReplacement("prod", "node-abc", "n1:h1:6000,n2:h2:6000", 5);

            assertThat(ctx.clusterName()).isEqualTo("prod");
            assertThat(ctx.role()).isEqualTo("core");
            assertThat(ctx.sourceName()).isEqualTo("default");
            assertThat(ctx.nodeId().or("")).isEqualTo("node-abc");
            assertThat(ctx.peers().or("")).isEqualTo("n1:h1:6000,n2:h2:6000");
            assertThat(ctx.coreMax()).isEqualTo(5);
            assertThat(ctx.provisionedBy()).isEqualTo(ProvisionContext.PROVISIONED_BY_CTM);
            assertThat(ctx.extraTags()).isEmpty();
        }
    }

    @Nested
    class ImmutabilityTests {

        @Test
        void extraTags_defensiveCopy_protectsAgainstCallerMutation() {
            var mutable = new HashMap<String, String>();
            mutable.put("key", "before");
            var ctx = ProvisionContext.provisionContext("c", "core", "s", Option.empty(), Option.empty(), 3,
                                                         ProvisionContext.PROVISIONED_BY_BOOTSTRAP, mutable);

            mutable.put("key", "after");

            assertThat(ctx.extraTags().get("key")).isEqualTo("before");
        }

        @Test
        void extraTags_returnedMapIsImmutable() {
            var ctx = ProvisionContext.provisionContext("c", "core", "s", Option.empty(), Option.empty(), 3,
                                                         ProvisionContext.PROVISIONED_BY_BOOTSTRAP,
                                                         Map.of("k", "v"));

            assertThatThrownBy(() -> ctx.extraTags().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    class WithersTests {

        @Test
        void withNodeId_returnsContextWithNewNodeId_othersUnchanged() {
            var base = ProvisionContext.provisionContext("prod", "core", "eu-1",
                                                          ProvisionContext.PROVISIONED_BY_BOOTSTRAP);

            var updated = base.withNodeId("eu-1-core-0");

            assertThat(updated.nodeId().or("")).isEqualTo("eu-1-core-0");
            assertThat(updated.clusterName()).isEqualTo("prod");
            assertThat(updated.role()).isEqualTo("core");
        }

        @Test
        void withPeers_setsPeersValue() {
            var base = ProvisionContext.provisionContext("prod", "core", "eu-1",
                                                          ProvisionContext.PROVISIONED_BY_CTM);

            var updated = base.withPeers("n1:h1:6000");

            assertThat(updated.peers().or("")).isEqualTo("n1:h1:6000");
        }

        @Test
        void withCoreMax_overridesDefault() {
            var base = ProvisionContext.provisionContext("prod", "core", "eu-1",
                                                          ProvisionContext.PROVISIONED_BY_BOOTSTRAP);

            var updated = base.withCoreMax(7);

            assertThat(updated.coreMax()).isEqualTo(7);
        }
    }

    @Nested
    class EqualityTests {

        @Test
        void equals_sameFields_returnsTrue() {
            var a = ProvisionContext.provisionContext("c", "core", "s", ProvisionContext.PROVISIONED_BY_BOOTSTRAP);
            var b = ProvisionContext.provisionContext("c", "core", "s", ProvisionContext.PROVISIONED_BY_BOOTSTRAP);

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        void equals_differentClusterName_returnsFalse() {
            var a = ProvisionContext.provisionContext("c1", "core", "s", ProvisionContext.PROVISIONED_BY_BOOTSTRAP);
            var b = ProvisionContext.provisionContext("c2", "core", "s", ProvisionContext.PROVISIONED_BY_BOOTSTRAP);

            assertThat(a).isNotEqualTo(b);
        }
    }
}

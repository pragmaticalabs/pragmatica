// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.update;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AbTestKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AbTestValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.messaging.MessageRouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.update.DeploymentVersionRoundTripTest.BASE;
import static org.pragmatica.aether.update.DeploymentVersionRoundTripTest.NEW_VERSION;
import static org.pragmatica.aether.update.DeploymentVersionRoundTripTest.OLD_VERSION;
import static org.pragmatica.aether.update.DeploymentVersionRoundTripTest.SELF;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.CapturingRabiaNode;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.seed;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.stubDeserializer;
import static org.pragmatica.aether.update.SliceTargetOverridePreservationTest.stubSerializer;

/// #1533 — the A/B test manager activates at leader election, BEFORE a fresh cluster's KV restore lands,
/// so activation sees no tests. When the restore decision commits, the node's restore hook calls
/// [AbTestManager#reloadRestoredState]; the restored test is then known to the manager again.
class AbTestRestoreReloadTest {
    private static final String TEST_ID = "restored-ab";

    private KVStore<AetherKey, AetherValue> kvStore;
    private AbTestManager manager;

    @BeforeEach
    void setUp() {
        kvStore = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        manager = AbTestManager.abTestManager(new CapturingRabiaNode(SELF),
                                              kvStore,
                                              InvocationMetricsCollector.invocationMetricsCollector());
        manager.activate()
               .await()
               .onFailure(cause -> Assertions.fail(cause.message()));
    }

    @Test
    void reloadRestoredState_restoresATestRestoredAfterActivation() {
        seed(kvStore, new AbTestKey(TEST_ID), activeTest());

        assertThat(manager.getTest(TEST_ID)
                          .isEmpty()).as("activation ran before the restore").isTrue();

        manager.reloadRestoredState();

        assertThat(manager.getTest(TEST_ID)
                          .isPresent()).as("the restored A/B test is known again").isTrue();
    }

    @Test
    void reloadRestoredState_isANoOp_whileInactive() {
        manager.deactivate()
               .await();
        seed(kvStore, new AbTestKey(TEST_ID), activeTest());

        manager.reloadRestoredState();

        assertThat(manager.getTest(TEST_ID)
                          .isEmpty()).isTrue();
    }

    private static AbTestValue activeTest() {
        var now = System.currentTimeMillis();

        return AbTestValue.abTestValue(TEST_ID,
                                       BASE,
                                       OLD_VERSION,
                                       "b=" + NEW_VERSION,
                                       AbTestState.ACTIVE.name(),
                                       "header-hash:X-Request-Id:2",
                                       50,
                                       50,
                                       "",
                                       now,
                                       now);
    }
}

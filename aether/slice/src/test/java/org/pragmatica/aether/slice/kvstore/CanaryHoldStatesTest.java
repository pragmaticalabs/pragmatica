// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import java.util.List;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.kvstore.AetherKey.AbTestKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.AbTestValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// v2713c F2 (CTO ruling 2026-11-06), survivors x10 and v03: one test per A/B test state pins whether the canary hold applies.
/// Held: `DEPLOYING_VARIANTS`, `ACTIVE`, `ROLLING_BACK` (scaling up a variant that is being rolled back is the wrong direction).
/// Not held: `PENDING`, `CONCLUDING` (a refused conclude can sit there indefinitely and must not freeze scaling; known limit: the
/// count may flap once while the conclude write lands), and the terminal `COMPLETED`, `ROLLED_BACK`, `FAILED`.
class CanaryHoldStatesTest {
    private static final ArtifactBase BASE = ArtifactBase.artifactBase("org.example:orders-slice").unwrap();
    private static final ArtifactBase OTHER = ArtifactBase.artifactBase("org.example:other-slice").unwrap();
    private static final Version V1 = Version.version("1.0.0").unwrap();

    @Test
    void pending_isNotHeld() {
        assertHold("PENDING", false);
    }

    @Test
    void deployingVariants_isHeld() {
        assertHold("DEPLOYING_VARIANTS", true);
    }

    @Test
    void active_isHeld() {
        assertHold("ACTIVE", true);
    }

    @Test
    void concluding_isNotHeld() {
        assertHold("CONCLUDING", false);
    }

    @Test
    void rollingBack_isHeld() {
        assertHold("ROLLING_BACK", true);
    }

    @Test
    void completed_isNotHeld() {
        assertHold("COMPLETED", false);
    }

    @Test
    void rolledBack_isNotHeld() {
        assertHold("ROLLED_BACK", false);
    }

    @Test
    void failed_isNotHeld() {
        assertHold("FAILED", false);
    }

    @Test
    void noTest_isNotHeld() {
        assertThat(SliceRequirements.canaryRunning(emptyStore(), BASE)).isFalse();
    }

    @Test
    void aRunningTestOnAnotherSlice_doesNotHoldThisOne() {
        var store = emptyStore();

        put(store, new AbTestKey("t-other"), abTest("t-other", OTHER, "ACTIVE"));

        assertThat(SliceRequirements.canaryRunning(store, BASE)).isFalse();
        assertThat(SliceRequirements.canaryRunning(store, OTHER)).isTrue();
    }

    @Test
    void oneRunningTestAmongEndedOnes_holdsTheSlice() {
        var store = emptyStore();

        put(store, new AbTestKey("t-done"), abTest("t-done", BASE, "COMPLETED"));
        put(store, new AbTestKey("t-live"), abTest("t-live", BASE, "ACTIVE"));
        put(store, new AbTestKey("t-failed"), abTest("t-failed", BASE, "FAILED"));

        assertThat(SliceRequirements.canaryRunning(store, BASE)).as("the hold is an OR over every test of the slice").isTrue();
    }

    private static void assertHold(String state, boolean held) {
        var store = emptyStore();

        put(store, new AbTestKey("t1"), abTest("t1", BASE, state));

        assertThat(abTest("t1", BASE, state).runsCanary()).as("runsCanary in %s", state).isEqualTo(held);
        assertThat(SliceRequirements.canaryRunning(store, BASE)).as("canaryRunning in %s", state).isEqualTo(held);
    }

    private static AbTestValue abTest(String id, ArtifactBase base, String state) {
        return AbTestValue.abTestValue(id, base, V1, "{}", state, "{}", 50, 50, "owner", 0L, 0L);
    }

    private static KVStore<AetherKey, AetherValue> emptyStore() {
        return new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), new Serializer() {
            @Override public <T> void write(ByteBuf buffer, T value) {}
        }, new Deserializer() {
            @Override public <T> T read(ByteBuf buffer) {return null;}
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void put(KVStore<AetherKey, AetherValue> store, StructuredKey key, Object value) {
        store.process(store.createBatch((List) List.of(new KVCommand.Put(key, value))));
    }
}

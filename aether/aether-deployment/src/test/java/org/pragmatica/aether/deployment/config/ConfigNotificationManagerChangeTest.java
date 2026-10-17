// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.config;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.node.NodeDeploymentManager;
import org.pragmatica.aether.slice.ConfigFacade;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;


/// #381: `notifyChange` takes the changed KV key and a per-artifact facade. Each registered slice is
/// notified once per declared section the key falls under, through the generated factory's static
/// `notifyConfigUpdate(instance, section, facade)`, with the facade built for THAT artifact; a key
/// outside every declared section reaches nobody.
class ConfigNotificationManagerChangeTest {
    private static final Artifact SLICE_A = Artifact.artifact("org.example:slice-a:1.0.0").unwrap();
    private static final Artifact SLICE_B = Artifact.artifact("org.example:slice-b:1.0.0").unwrap();
    private static final Object INSTANCE_A = new Object();
    private static final Object INSTANCE_B = new Object();

    record Call(Object instance, String section, ConfigFacade facade) {}

    /// Stands in for a generated `<Slice>Factory`: the manager looks the method up by name and shape.
    public static final class RecordingFactory {
        static final List<Call> calls = new CopyOnWriteArrayList<>();

        public static void notifyConfigUpdate(Object sliceInstance, String section, ConfigFacade config) {
            calls.add(new Call(sliceInstance, section, config));
        }
    }

    @Test
    void change_reachesEverySliceWhoseSectionPrefixesTheKey_withItsOwnFacade() {
        RecordingFactory.calls.clear();
        var manager = ConfigNotificationManager.configNotificationManager();
        var facadeA = NodeDeploymentManager.NO_OP_CONFIG;
        var facadeB = new DistinctFacade();

        manager.register(SLICE_A, INSTANCE_A, getClass().getClassLoader(), RecordingFactory.class.getName(), List.of("database", "cache"));
        manager.register(SLICE_B, INSTANCE_B, getClass().getClassLoader(), RecordingFactory.class.getName(), List.of("database.pool"));
        manager.notifyChange("database.pool.size", artifact -> artifact.equals(SLICE_A) ? facadeA : facadeB);

        await().untilAsserted(() -> assertThat(RecordingFactory.calls).hasSize(2));
        assertThat(RecordingFactory.calls).containsExactlyInAnyOrder(new Call(INSTANCE_A, "database", facadeA),
                                                                     new Call(INSTANCE_B, "database.pool", facadeB));
        manager.shutdown();
    }

    @Test
    void change_outsideEveryDeclaredSection_reachesNobody() {
        RecordingFactory.calls.clear();
        var manager = ConfigNotificationManager.configNotificationManager();

        manager.register(SLICE_A, INSTANCE_A, getClass().getClassLoader(), RecordingFactory.class.getName(), List.of("database"));
        manager.notifyChange("databases.other", _ -> NodeDeploymentManager.NO_OP_CONFIG);
        manager.notifyChange("cache.ttl", _ -> NodeDeploymentManager.NO_OP_CONFIG);
        manager.notifyChange("database.url", _ -> NodeDeploymentManager.NO_OP_CONFIG);

        await().untilAsserted(() -> assertThat(RecordingFactory.calls).hasSize(1));
        assertThat(RecordingFactory.calls.getFirst().section()).isEqualTo("database");
        manager.shutdown();
    }

    /// Any facade that is not the shared no-op one — identity is what the assertion needs.
    private static final class DistinctFacade implements ConfigFacade {
        @Override
        public org.pragmatica.lang.Result<String> requireString(String section, String key) { return NodeDeploymentManager.NO_OP_CONFIG.requireString(section, key); }
        @Override
        public org.pragmatica.lang.Result<Integer> requireInt(String section, String key) { return NodeDeploymentManager.NO_OP_CONFIG.requireInt(section, key); }
        @Override
        public org.pragmatica.lang.Result<Long> requireLong(String section, String key) { return NodeDeploymentManager.NO_OP_CONFIG.requireLong(section, key); }
        @Override
        public org.pragmatica.lang.Result<Double> requireDouble(String section, String key) { return NodeDeploymentManager.NO_OP_CONFIG.requireDouble(section, key); }
        @Override
        public org.pragmatica.lang.Result<Boolean> requireBoolean(String section, String key) { return NodeDeploymentManager.NO_OP_CONFIG.requireBoolean(section, key); }
        @Override
        public org.pragmatica.lang.Result<List<String>> requireStringList(String section, String key) { return NodeDeploymentManager.NO_OP_CONFIG.requireStringList(section, key); }
        @Override
        public org.pragmatica.lang.Option<String> getString(String section, String key) { return NodeDeploymentManager.NO_OP_CONFIG.getString(section, key); }
        @Override
        public org.pragmatica.lang.Result<org.pragmatica.lang.Option<Integer>> getInt(String section, String key) { return NodeDeploymentManager.NO_OP_CONFIG.getInt(section, key); }
        @Override
        public org.pragmatica.lang.Result<org.pragmatica.lang.Option<Long>> getLong(String section, String key) { return NodeDeploymentManager.NO_OP_CONFIG.getLong(section, key); }
        @Override
        public org.pragmatica.lang.Result<org.pragmatica.lang.Option<Double>> getDouble(String section, String key) { return NodeDeploymentManager.NO_OP_CONFIG.getDouble(section, key); }
        @Override
        public org.pragmatica.lang.Result<org.pragmatica.lang.Option<Boolean>> getBoolean(String section, String key) { return NodeDeploymentManager.NO_OP_CONFIG.getBoolean(section, key); }
    }
}

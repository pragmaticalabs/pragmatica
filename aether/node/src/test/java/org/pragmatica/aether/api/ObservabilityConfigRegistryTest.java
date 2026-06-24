// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.slice.AspectObservabilityConfig;
import org.pragmatica.aether.slice.ObservabilityAspect;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ObservabilityConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ObservabilityConfigValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// #277 PR1: proves the write-side registry pushes KV-sourced AspectObservabilityConfig snapshots onto
/// the live per-slice ObservabilityAspect instances (push-on-event), and that deregister drops the live
/// reference so a later KV-update cannot touch an unloaded slice's aspect.
class ObservabilityConfigRegistryTest {
    private static final String SLICE = "com.example:my-slice";

    private KVStore<AetherKey, AetherValue> kvStore;
    private ObservabilityConfigRegistry registry;

    @BeforeEach
    void setUp() {
        var router = MessageRouter.mutable();

        kvStore = new KVStore<>(router, stubSerializer(), stubDeserializer());
        registry = ObservabilityConfigRegistry.observabilityConfigRegistry(null, kvStore);
    }

    @Test
    void createAndRegister_returnsAspect_atOffSnapshot() {
        var aspect = (ObservabilityAspect<?>) registry.createAndRegister(base(SLICE));

        assertThat(aspect.observabilityConfig()).isEqualTo(AspectObservabilityConfig.OFF);
        assertThat(aspect.observabilityConfig().allOff()).isTrue();
    }

    @Test
    void onObservabilityConfigPut_swapsLiveInstanceVolatile() {
        var aspect = (ObservabilityAspect<?>) registry.createAndRegister(base(SLICE));

        registry.onObservabilityConfigPut(put(SLICE, true, true, false, false, 3));
        var snapshot = aspect.observabilityConfig();

        assertThat(snapshot.logging()).isTrue();
        assertThat(snapshot.metrics()).isTrue();
        assertThat(snapshot.spans()).isFalse();
        assertThat(snapshot.tracing()).isFalse();
        assertThat(snapshot.depth()).isEqualTo(3);
    }

    @Test
    void createAndRegister_afterPut_seedsFromLastKnownSnapshot() {
        registry.onObservabilityConfigPut(put(SLICE, true, false, true, false, 5));
        var aspect = (ObservabilityAspect<?>) registry.createAndRegister(base(SLICE));
        var snapshot = aspect.observabilityConfig();

        assertThat(snapshot.logging()).isTrue();
        assertThat(snapshot.spans()).isTrue();
        assertThat(snapshot.depth()).isEqualTo(5);
    }

    @Test
    void onObservabilityConfigPut_forUnregisteredBase_updatesConfigsWithoutThrowing() {
        registry.onObservabilityConfigPut(put(SLICE, true, false, false, true, 2));
        var stored = registry.getConfig(SLICE);

        assertThat(stored.logging()).isTrue();
        assertThat(stored.tracing()).isTrue();
        assertThat(stored.depth()).isEqualTo(2);
        assertThat(registry.allConfigs()).containsKey(SLICE);
    }

    @Test
    void onObservabilityConfigRemove_swapsLiveInstanceToOff() {
        var aspect = (ObservabilityAspect<?>) registry.createAndRegister(base(SLICE));

        registry.onObservabilityConfigPut(put(SLICE, true, true, true, true, 4));
        registry.onObservabilityConfigRemove(remove(SLICE));
        assertThat(aspect.observabilityConfig()).isEqualTo(AspectObservabilityConfig.OFF);
        assertThat(registry.allConfigs()).doesNotContainKey(SLICE);
    }

    @Test
    void deregister_dropsLiveInstance_soLaterPutDoesNotTouchIt() {
        var aspect = (ObservabilityAspect<?>) registry.createAndRegister(base(SLICE));

        registry.deregister(base(SLICE));
        registry.onObservabilityConfigPut(put(SLICE, true, true, true, true, 8));
        // The deregistered aspect must keep its last (OFF) snapshot: the put updates configs only.
        assertThat(aspect.observabilityConfig()).isEqualTo(AspectObservabilityConfig.OFF);
        assertThat(registry.getConfig(SLICE).logging()).isTrue();
    }

    private static ArtifactBase base(String value) {
        return ArtifactBase.artifactBase(value).unwrap();
    }

    private static ValuePut<ObservabilityConfigKey, ObservabilityConfigValue> put(String artifactBase,
                                                                                  boolean logging,
                                                                                  boolean metrics,
                                                                                  boolean spans,
                                                                                  boolean tracing,
                                                                                  int depth) {
        var key = ObservabilityConfigKey.forArtifactBase(artifactBase);
        var value = ObservabilityConfigValue.observabilityConfigValue(artifactBase,
                                                                      logging,
                                                                      metrics,
                                                                      spans,
                                                                      tracing,
                                                                      depth);

        return new ValuePut<>(new KVCommand.Put<>(key, value), Option.none());
    }

    private static ValueRemove<ObservabilityConfigKey, ObservabilityConfigValue> remove(String artifactBase) {
        var key = ObservabilityConfigKey.forArtifactBase(artifactBase);

        return new ValueRemove<>(new KVCommand.Remove<>(key), Option.none());
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}

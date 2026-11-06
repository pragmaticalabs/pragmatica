// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.deployment.config.DefaultConfigChangeFeed;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConfigValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.DynamicConfigurationProvider;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// The node's wiring of the config change feed, with the real `DynamicConfigManager`: the applier writes the
/// overlay and THEN publishes, the feed is registered before the legacy (FSM) listener, and a throwing legacy
/// listener cannot keep a `Watched` from seeing the change.
class ConfigWatchWiringTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final String OWNER = "org.example:orders:1.0.0";
    private static final String SECTION = "app.orders";
    private static final String KEY = SECTION + ".limit";

    private final DynamicConfigurationProvider provider = DynamicConfigurationProvider.dynamicConfigurationProvider(ConfigurationProvider.builder()
                                                                                                                                       .build());
    private final DynamicConfigManager manager = DynamicConfigManager.dynamicConfigManager(null, emptyStore(), provider, SELF);
    private final DefaultConfigChangeFeed feed = DefaultConfigChangeFeed.defaultConfigChangeFeed(() -> 0L,
                                                                                                 provider::atomically,
                                                                                                 TimeSpan.timeSpan(2).seconds());

    private Result<String> bindLimit() {
        return Result.success(provider.getString(KEY).or("none"));
    }

    @Test
    void watched_seesACommittedPut_evenWhenALaterLegacyListenerThrows() {
        var seen = new CopyOnWriteArrayList<String>();
        manager.onApplied(feed::publish);
        manager.onApplied(_ -> {
            throw new IllegalStateException("legacy push path failed");
        });
        feed.subscribe(OWNER, SECTION, this::bindLimit, value -> record(seen, value));

        manager.onConfigPut(put("42"));

        await().atMost(Duration.ofSeconds(5)).until(() -> seen.contains("42"));
    }

    @Test
    void watched_neverMissesTheFinalValue_whenRegistrationRacesTheApplier() {
        manager.onApplied(feed::publish);
        var deadline = System.nanoTime() + 60_000_000_000L;
        var rounds = 0;

        for (int i = 1; i <= 1000 && System.nanoTime() < deadline; i++) {
            race(i);
            rounds++;
        }

        assertThat(rounds).isGreaterThan(500);
    }

    private void race(int round) {
        var owner = OWNER + "-" + round;
        var value = Integer.toString(round);
        var last = new AtomicReference<String>("none");
        var gate = new AtomicInteger(2);
        var applier = Thread.ofPlatform().start(() -> spinThen(gate, () -> manager.onConfigPut(put(value))));

        spinThen(gate, () -> feed.subscribe(owner, SECTION, this::bindLimit, seen -> record(last, seen)));
        join(applier);

        await().pollInterval(Duration.ofMillis(1)).atMost(Duration.ofSeconds(5)).until(() -> value.equals(last.get()));
        feed.releaseAll(owner).await();
    }

    private static Promise<Unit> record(List<String> into, String value) {
        into.add(value);
        return Promise.unitPromise();
    }

    private static Promise<Unit> record(AtomicReference<String> into, String value) {
        into.set(value);
        return Promise.unitPromise();
    }

    private static void spinThen(AtomicInteger gate, Runnable action) {
        gate.decrementAndGet();
        while (gate.get() > 0) {
            Thread.onSpinWait();
        }
        action.run();
    }

    private static void join(Thread thread) {
        try {
            thread.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static ValuePut<ConfigKey, ConfigValue> put(String value) {
        var key = ConfigKey.forKey(KEY);

        return new ValuePut<>(new KVCommand.Put<>(key, ConfigValue.configValue(KEY, value)), Option.none());
    }

    private static KVStore<AetherKey, AetherValue> emptyStore() {
        return new KVStore<>(MessageRouter.mutable(), new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        }, new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        });
    }
}

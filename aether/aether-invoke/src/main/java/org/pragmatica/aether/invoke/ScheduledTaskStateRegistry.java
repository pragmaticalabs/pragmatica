// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskStateKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskStateValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageReceiver;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


@SuppressWarnings("JBCT-RET-01")
public interface ScheduledTaskStateRegistry {
    @MessageReceiver
    void onStatePut(ValuePut<ScheduledTaskStateKey, ScheduledTaskStateValue> valuePut);

    @MessageReceiver
    void onStateRemove(ValueRemove<ScheduledTaskStateKey, ScheduledTaskStateValue> valueRemove);

    Option<ScheduledTaskStateValue> stateFor(ScheduledTaskStateKey key);
    Map<ScheduledTaskStateKey, ScheduledTaskStateValue> allStates();

    static ScheduledTaskStateRegistry scheduledTaskStateRegistry() {
        record scheduledTaskStateRegistry(Map<ScheduledTaskStateKey, ScheduledTaskStateValue> states) implements ScheduledTaskStateRegistry {
            private static final Logger log = LoggerFactory.getLogger(ScheduledTaskStateRegistry.class);

            @Override
            public void onStatePut(ValuePut<ScheduledTaskStateKey, ScheduledTaskStateValue> valuePut) {
                var key = valuePut.cause().key();
                var value = valuePut.cause().value();

                states.put(key, value);
                log.debug("Updated execution state for task: {}", key);
            }

            @Override
            public void onStateRemove(ValueRemove<ScheduledTaskStateKey, ScheduledTaskStateValue> valueRemove) {
                var key = valueRemove.cause().key();

                states.remove(key);
                log.debug("Removed execution state for task: {}", key);
            }

            @Override
            public Option<ScheduledTaskStateValue> stateFor(ScheduledTaskStateKey key) {
                return Option.option(states.get(key));
            }

            @Override
            public Map<ScheduledTaskStateKey, ScheduledTaskStateValue> allStates() {
                return Map.copyOf(states);
            }
        }

        return new scheduledTaskStateRegistry(new ConcurrentHashMap<>());
    }
}

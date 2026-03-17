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

/// Passive KV-Store watcher maintaining a local cache of scheduled task execution state.
///
/// Tracks execution metrics (last run time, next fire time, failure counts) for
/// scheduled tasks. Read-only from registry perspective; ScheduledTaskManager
/// writes state via consensus.
@SuppressWarnings("JBCT-RET-01") // MessageReceiver callbacks — void required by messaging framework
public interface ScheduledTaskStateRegistry {
    @MessageReceiver
    void onStatePut(ValuePut<ScheduledTaskStateKey, ScheduledTaskStateValue> valuePut);

    @MessageReceiver
    void onStateRemove(ValueRemove<ScheduledTaskStateKey, ScheduledTaskStateValue> valueRemove);

    /// Get execution state for a specific task.
    Option<ScheduledTaskStateValue> stateFor(ScheduledTaskStateKey key);

    /// All tracked execution states.
    Map<ScheduledTaskStateKey, ScheduledTaskStateValue> allStates();

    /// Create a new scheduled task state registry.
    static ScheduledTaskStateRegistry scheduledTaskStateRegistry() {
        record scheduledTaskStateRegistry(Map<ScheduledTaskStateKey, ScheduledTaskStateValue> states)
        implements ScheduledTaskStateRegistry {
            private static final Logger log = LoggerFactory.getLogger(ScheduledTaskStateRegistry.class);

            @Override
            public void onStatePut(ValuePut<ScheduledTaskStateKey, ScheduledTaskStateValue> valuePut) {
                var key = valuePut.cause()
                                  .key();
                var value = valuePut.cause()
                                    .value();
                states.put(key, value);
                log.debug("Updated execution state for task: {}", key);
            }

            @Override
            public void onStateRemove(ValueRemove<ScheduledTaskStateKey, ScheduledTaskStateValue> valueRemove) {
                var key = valueRemove.cause()
                                     .key();
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

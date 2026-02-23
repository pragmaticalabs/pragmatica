package org.pragmatica.aether.invoke;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageReceiver;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Passive KV-Store watcher maintaining a local cache of scheduled task registrations.
///
/// Tracks which slice methods are registered for periodic invocation. Used by
/// ScheduledTaskManager to start/stop timers when tasks are added or removed.
public interface ScheduledTaskRegistry {
    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01") // MessageReceiver callback — void required by messaging framework
    void onScheduledTaskPut(ValuePut<ScheduledTaskKey, ScheduledTaskValue> valuePut);

    @MessageReceiver
    @SuppressWarnings("JBCT-RET-01") // MessageReceiver callback — void required by messaging framework
    void onScheduledTaskRemove(ValueRemove<ScheduledTaskKey, ScheduledTaskValue> valueRemove);

    /// All registered scheduled tasks.
    List<ScheduledTask> allTasks();

    /// Tasks that should run only on the leader node.
    List<ScheduledTask> leaderOnlyTasks();

    /// Tasks registered by a specific node (for local-only execution).
    List<ScheduledTask> localTasks(NodeId self);

    /// Set a listener for task changes (add/remove/update).
    /// The BiConsumer receives (key, Optional task) — present for add/update, empty for remove.
    @SuppressWarnings("JBCT-RET-01") // Lifecycle wiring — called once during initialization
    void setChangeListener(BiConsumer<ScheduledTaskKey, Option<ScheduledTask>> listener);

    /// A scheduled task entry in the registry.
    record ScheduledTask(String configSection,
                         Artifact artifact,
                         MethodName methodName,
                         NodeId registeredBy,
                         String interval,
                         String cron,
                         boolean leaderOnly) {
        public boolean isInterval() {
            return ! interval.isEmpty();
        }

        public boolean isCron() {
            return ! cron.isEmpty();
        }
    }

    /// Create a new scheduled task registry.
    static ScheduledTaskRegistry scheduledTaskRegistry() {
        record scheduledTaskRegistry(Map<ScheduledTaskKey, ScheduledTask> tasks,
                                     AtomicReference<BiConsumer<ScheduledTaskKey, Option<ScheduledTask>>> changeListener)
        implements ScheduledTaskRegistry {
            private static final Logger log = LoggerFactory.getLogger(ScheduledTaskRegistry.class);

            @Override
            @SuppressWarnings("JBCT-RET-01")
            public void onScheduledTaskPut(ValuePut<ScheduledTaskKey, ScheduledTaskValue> valuePut) {
                var key = valuePut.cause()
                                  .key();
                var value = valuePut.cause()
                                    .value();
                var task = new ScheduledTask(key.configSection(),
                                             key.artifact(),
                                             key.methodName(),
                                             value.registeredBy(),
                                             value.interval(),
                                             value.cron(),
                                             value.leaderOnly());
                var previous = tasks.put(key, task);
                log.debug("Registered scheduled task: {}", task);
                notifyIfChanged(key, previous, task);
            }

            @Override
            @SuppressWarnings("JBCT-RET-01")
            public void onScheduledTaskRemove(ValueRemove<ScheduledTaskKey, ScheduledTaskValue> valueRemove) {
                var key = valueRemove.cause()
                                     .key();
                Option.option(tasks.remove(key))
                      .onPresent(removed -> handleTaskRemoved(key, removed));
            }

            private void handleTaskRemoved(ScheduledTaskKey key, ScheduledTask removed) {
                log.debug("Unregistered scheduled task: {}", removed);
                notifyListener(key, Option.none());
            }

            @Override
            public List<ScheduledTask> allTasks() {
                return List.copyOf(tasks.values());
            }

            @Override
            public List<ScheduledTask> leaderOnlyTasks() {
                return tasks.values()
                            .stream()
                            .filter(ScheduledTask::leaderOnly)
                            .toList();
            }

            @Override
            public List<ScheduledTask> localTasks(NodeId self) {
                return tasks.values()
                            .stream()
                            .filter(task -> task.registeredBy()
                                                .equals(self))
                            .toList();
            }

            @Override
            @SuppressWarnings("JBCT-RET-01")
            public void setChangeListener(BiConsumer<ScheduledTaskKey, Option<ScheduledTask>> listener) {
                changeListener.set(listener);
            }

            private void notifyIfChanged(ScheduledTaskKey key, ScheduledTask previous, ScheduledTask current) {
                if (previous == null || scheduleChanged(previous, current)) {
                    notifyListener(key, Option.some(current));
                }
            }

            private boolean scheduleChanged(ScheduledTask previous, ScheduledTask current) {
                return ! Objects.equals(previous.interval(), current.interval()) || !Objects.equals(previous.cron(),
                                                                                                    current.cron()) || previous.leaderOnly() != current.leaderOnly();
            }

            private void notifyListener(ScheduledTaskKey key, Option<ScheduledTask> task) {
                var listener = changeListener.get();
                if (listener != null) {
                    listener.accept(key, task);
                }
            }
        }
        return new scheduledTaskRegistry(new ConcurrentHashMap<>(), new AtomicReference<>());
    }
}

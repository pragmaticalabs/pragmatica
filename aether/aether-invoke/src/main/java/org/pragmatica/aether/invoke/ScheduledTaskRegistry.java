package org.pragmatica.aether.invoke;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.ExecutionMode;
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
    @MessageReceiver@SuppressWarnings("JBCT-RET-01") void onScheduledTaskPut(ValuePut<ScheduledTaskKey, ScheduledTaskValue> valuePut);
    @MessageReceiver@SuppressWarnings("JBCT-RET-01") void onScheduledTaskRemove(ValueRemove<ScheduledTaskKey, ScheduledTaskValue> valueRemove);
    List<ScheduledTask> allTasks();
    List<ScheduledTask> singleModeTasks();
    List<ScheduledTask> localTasks(NodeId self);
    @SuppressWarnings("JBCT-RET-01") void setChangeListener(BiConsumer<ScheduledTaskKey, Option<ScheduledTask>> listener);

    record ScheduledTask(String configSection,
                         Artifact artifact,
                         MethodName methodName,
                         NodeId registeredBy,
                         String interval,
                         String cron,
                         ExecutionMode executionMode,
                         boolean paused) {
        public boolean isInterval() {
            return ! interval.isEmpty();
        }

        public boolean isCron() {
            return ! cron.isEmpty();
        }
    }

    static ScheduledTaskRegistry scheduledTaskRegistry() {
        record scheduledTaskRegistry(Map<ScheduledTaskKey, ScheduledTask> tasks,
                                     AtomicReference<BiConsumer<ScheduledTaskKey, Option<ScheduledTask>>> changeListener) implements ScheduledTaskRegistry {
            private static final Logger log = LoggerFactory.getLogger(ScheduledTaskRegistry.class);

            @Override@SuppressWarnings("JBCT-RET-01") public void onScheduledTaskPut(ValuePut<ScheduledTaskKey, ScheduledTaskValue> valuePut) {
                var key = valuePut.cause().key();
                var value = valuePut.cause().value();
                var task = new ScheduledTask(key.configSection(),
                                             key.artifact(),
                                             key.methodName(),
                                             value.registeredBy(),
                                             value.interval(),
                                             value.cron(),
                                             value.executionMode(),
                                             value.paused());
                var previous = tasks.put(key, task);
                log.debug("Registered scheduled task: {}", task);
                notifyIfChanged(key, previous, task);
            }

            @Override@SuppressWarnings("JBCT-RET-01") public void onScheduledTaskRemove(ValueRemove<ScheduledTaskKey, ScheduledTaskValue> valueRemove) {
                var key = valueRemove.cause().key();
                Option.option(tasks.remove(key)).onPresent(removed -> handleTaskRemoved(key, removed));
            }

            private void handleTaskRemoved(ScheduledTaskKey key, ScheduledTask removed) {
                log.debug("Unregistered scheduled task: {}", removed);
                notifyListener(key, Option.none());
            }

            @Override public List<ScheduledTask> allTasks() {
                return List.copyOf(tasks.values());
            }

            @Override public List<ScheduledTask> singleModeTasks() {
                return tasks.values().stream()
                                   .filter(task -> task.executionMode() == ExecutionMode.SINGLE)
                                   .toList();
            }

            @Override public List<ScheduledTask> localTasks(NodeId self) {
                return tasks.values().stream()
                                   .filter(task -> task.registeredBy().equals(self))
                                   .toList();
            }

            @Override@SuppressWarnings("JBCT-RET-01") public void setChangeListener(BiConsumer<ScheduledTaskKey, Option<ScheduledTask>> listener) {
                changeListener.set(listener);
            }

            private void notifyIfChanged(ScheduledTaskKey key, ScheduledTask previous, ScheduledTask current) {
                if (previous == null || scheduleChanged(previous, current)) {notifyListener(key, Option.some(current));}
            }

            private boolean scheduleChanged(ScheduledTask previous, ScheduledTask current) {
                return ! Objects.equals(previous.interval(), current.interval()) || !Objects.equals(previous.cron(),
                                                                                                    current.cron()) || previous.executionMode() != current.executionMode() || previous.paused() != current.paused();
            }

            private void notifyListener(ScheduledTaskKey key, Option<ScheduledTask> task) {
                var listener = changeListener.get();
                if (listener != null) {listener.accept(key, task);}
            }
        }
        return new scheduledTaskRegistry(new ConcurrentHashMap<>(), new AtomicReference<>());
    }
}

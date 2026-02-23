package org.pragmatica.aether.invoke;

import org.pragmatica.aether.invoke.ScheduledTaskRegistry.ScheduledTask;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.consensus.topology.QuorumStateNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.messaging.MessageReceiver;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Timer lifecycle and execution manager for scheduled tasks.
///
/// Watches the ScheduledTaskRegistry for changes, manages timer creation
/// and cancellation, and invokes slice methods at configured intervals.
/// Respects leader-only semantics and quorum requirements.
@SuppressWarnings({"JBCT-RET-01", "JBCT-RET-03"}) // MessageReceiver callbacks + lifecycle methods
public interface ScheduledTaskManager {
    @MessageReceiver
    void onLeaderChange(LeaderChange leaderChange);

    @MessageReceiver
    void onQuorumStateChange(QuorumStateNotification notification);

    /// Number of currently active timers (for management API).
    int activeTimerCount();

    /// Stop all timers and clean up.
    void stop();

    /// Create a new scheduled task manager.
    static ScheduledTaskManager scheduledTaskManager(ScheduledTaskRegistry registry,
                                                     SliceInvoker invoker,
                                                     NodeId self) {
        record scheduledTaskManager(ScheduledTaskRegistry registry,
                                    SliceInvoker invoker,
                                    NodeId self,
                                    AtomicBoolean isLeader,
                                    AtomicBoolean hasQuorum,
                                    Map<ScheduledTaskKey, ScheduledFuture<?>> activeTimers,
                                    AtomicLong quorumSequence) implements ScheduledTaskManager {
            private static final Logger log = LoggerFactory.getLogger(ScheduledTaskManager.class);

            @Override
            public void onLeaderChange(LeaderChange leaderChange) {
                if (leaderChange.localNodeIsLeader()) {
                    log.info("Node {} became leader, starting leader-only scheduled tasks", self);
                    isLeader.set(true);
                    startLeaderOnlyTasks();
                } else {
                    log.info("Node {} lost leadership, cancelling leader-only scheduled tasks", self);
                    isLeader.set(false);
                    cancelLeaderOnlyTimers();
                }
            }

            @Override
            public void onQuorumStateChange(QuorumStateNotification notification) {
                if (!notification.advanceSequence(quorumSequence)) {
                    log.debug("Ignoring stale QuorumStateNotification: {}", notification);
                    return;
                }
                if (notification.state() == QuorumStateNotification.State.ESTABLISHED) {
                    log.info("Quorum established, enabling scheduled task execution");
                    hasQuorum.set(true);
                    startAllEligibleTasks();
                } else {
                    log.info("Quorum disappeared, cancelling all scheduled tasks");
                    hasQuorum.set(false);
                    cancelAllTimers();
                }
            }

            @Override
            public int activeTimerCount() {
                return activeTimers.size();
            }

            @Override
            public void stop() {
                cancelAllTimers();
            }

            private void onRegistryChange(ScheduledTaskKey key, Option<ScheduledTask> taskOption) {
                taskOption.onPresent(task -> handleTaskAdded(key, task))
                          .onEmpty(() -> handleTaskRemoved(key));
            }

            private void handleTaskAdded(ScheduledTaskKey key, ScheduledTask task) {
                cancelTimer(key);
                if (shouldStartTask(task)) {
                    startTimer(key, task);
                }
            }

            private void handleTaskRemoved(ScheduledTaskKey key) {
                cancelTimer(key);
            }

            private boolean shouldStartTask(ScheduledTask task) {
                if (!hasQuorum.get()) {
                    return false;
                }
                return ! task.leaderOnly() || isLeader.get();
            }

            private void startTimer(ScheduledTaskKey key, ScheduledTask task) {
                if (task.isInterval()) {
                    startIntervalTimer(key, task);
                } else if (task.isCron()) {
                    log.debug("Cron scheduling not yet supported, skipping task: {}", key);
                }
            }

            private void startIntervalTimer(ScheduledTaskKey key, ScheduledTask task) {
                parseInterval(task.interval()).onSuccess(interval -> scheduleAtFixedRate(key, task, interval))
                             .onFailure(cause -> log.warn("Failed to parse interval '{}' for task {}: {}",
                                                          task.interval(),
                                                          key,
                                                          cause.message()));
            }

            private void scheduleAtFixedRate(ScheduledTaskKey key, ScheduledTask task, TimeSpan interval) {
                var future = SharedScheduler.scheduleAtFixedRate(() -> executeTask(task), interval);
                activeTimers.put(key, future);
                log.info("Started scheduled task {} with interval {}", key, task.interval());
            }

            private void executeTask(ScheduledTask task) {
                // Scheduler boundary — generic catch prevents scheduler thread death
                try{
                    invoker.invoke(task.artifact(),
                                   task.methodName(),
                                   Unit.unit())
                           .onFailure(cause -> log.warn("Scheduled task {}.{} failed: {}",
                                                        task.configSection(),
                                                        task.methodName()
                                                            .name(),
                                                        cause.message()));
                } catch (Exception e) {
                    // Scheduler boundary — generic catch prevents scheduler thread death
                    log.error("Error executing scheduled task {}.{}: {}",
                              task.configSection(),
                              task.methodName()
                                  .name(),
                              e.getMessage());
                }
            }

            private void cancelTimer(ScheduledTaskKey key) {
                Option.option(activeTimers.remove(key))
                      .onPresent(future -> cancelFuture(key, future));
            }

            private void cancelFuture(ScheduledTaskKey key, ScheduledFuture<?> future) {
                future.cancel(false);
                log.debug("Cancelled scheduled task timer: {}", key);
            }

            private void startLeaderOnlyTasks() {
                if (!hasQuorum.get()) {
                    return;
                }
                registry.leaderOnlyTasks()
                        .forEach(this::startTaskIfNotRunning);
            }

            private void startAllEligibleTasks() {
                registry.allTasks()
                        .stream()
                        .filter(this::shouldStartTask)
                        .forEach(this::startTaskIfNotRunning);
            }

            private void startTaskIfNotRunning(ScheduledTask task) {
                var key = ScheduledTaskKey.scheduledTaskKey(task.configSection(), task.artifact(), task.methodName());
                if (!activeTimers.containsKey(key)) {
                    startTimer(key, task);
                }
            }

            private void cancelLeaderOnlyTimers() {
                registry.leaderOnlyTasks()
                        .forEach(task -> cancelTimer(ScheduledTaskKey.scheduledTaskKey(task.configSection(),
                                                                                       task.artifact(),
                                                                                       task.methodName())));
            }

            private void cancelAllTimers() {
                activeTimers.forEach((key, future) -> future.cancel(false));
                var count = activeTimers.size();
                activeTimers.clear();
                if (count > 0) {
                    log.info("Cancelled {} scheduled task timers", count);
                }
            }

            private static org.pragmatica.lang.Result<TimeSpan> parseInterval(String interval) {
                return IntervalParser.parse(interval);
            }
        }
        var manager = new scheduledTaskManager(registry,
                                               invoker,
                                               self,
                                               new AtomicBoolean(false),
                                               new AtomicBoolean(false),
                                               new ConcurrentHashMap<>(),
                                               new AtomicLong(0));
        registry.setChangeListener(manager::onRegistryChange);
        return manager;
    }

    /// Parses human-readable interval strings like "30s", "5m", "1h" into TimeSpan.
    sealed interface IntervalParser {
        static org.pragmatica.lang.Result<TimeSpan> parse(String interval) {
            if (interval == null || interval.isEmpty()) {
                return EMPTY_INTERVAL.result();
            }
            var trimmed = interval.trim();
            if (trimmed.length() < 2) {
                return INVALID_INTERVAL.apply(interval)
                                       .result();
            }
            var suffix = trimmed.charAt(trimmed.length() - 1);
            var numberPart = trimmed.substring(0, trimmed.length() - 1);
            return parseNumber(numberPart, interval).flatMap(value -> applyUnit(value, suffix, interval));
        }

        private static org.pragmatica.lang.Result<Long> parseNumber(String numberPart, String original) {
            try{
                return org.pragmatica.lang.Result.success(Long.parseLong(numberPart));
            } catch (NumberFormatException _) {
                return INVALID_INTERVAL.apply(original)
                                       .result();
            }
        }

        private static org.pragmatica.lang.Result<TimeSpan> applyUnit(long value, char suffix, String original) {
            return switch (suffix) {
                case 's' -> org.pragmatica.lang.Result.success(TimeSpan.timeSpan(value)
                                                                       .seconds());
                case 'm' -> org.pragmatica.lang.Result.success(TimeSpan.timeSpan(value)
                                                                       .minutes());
                case 'h' -> org.pragmatica.lang.Result.success(TimeSpan.timeSpan(value)
                                                                       .hours());
                case 'd' -> org.pragmatica.lang.Result.success(TimeSpan.timeSpan(value)
                                                                       .days());
                default -> INVALID_INTERVAL.apply(original)
                                           .result();
            };
        }

        org.pragmatica.lang.Cause EMPTY_INTERVAL = () -> "Interval string is empty";

        org.pragmatica.lang.Functions.Fn1<org.pragmatica.lang.Cause, String> INVALID_INTERVAL = org.pragmatica.lang.utils.Causes.forOneValue("Invalid interval format: %s (expected e.g. '30s', '5m', '1h')");

        record unused() implements IntervalParser {}
    }
}

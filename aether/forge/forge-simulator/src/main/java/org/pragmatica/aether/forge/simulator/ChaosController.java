package org.pragmatica.aether.forge.simulator;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Result.unitResult;
import static org.pragmatica.lang.Unit.unit;

/// Controller for chaos engineering experiments.
/// Injects failures and disruptions to test system resilience.
public final class ChaosController {
    private static final Logger log = LoggerFactory.getLogger(ChaosController.class);
    private static final int EVENT_ID_LENGTH = 8;

    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final Map<String, ActiveChaosEvent> activeEvents = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final Consumer<ChaosEvent> eventExecutor;

    private ChaosController(Consumer<ChaosEvent> eventExecutor) {
        this.eventExecutor = eventExecutor;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(ChaosController::createDaemonThread);
    }

    private static Thread createDaemonThread(Runnable r) {
        var t = new Thread(r, "chaos-controller");
        t.setDaemon(true);
        return t;
    }

    public static ChaosController chaosController(Consumer<ChaosEvent> eventExecutor) {
        return new ChaosController(eventExecutor);
    }

    /// Enable or disable chaos injection.
    public Result<Unit> setEnabled(boolean enabled) {
        this.enabled.set(enabled);
        logEnabledState(enabled);
        if (!enabled) {
            stopAllChaos();
        }
        return unitResult();
    }

    private static void logEnabledState(boolean state) {
        var action = state
                     ? "enabled"
                     : "disabled";
        log.info("Chaos controller {}", action);
    }

    /// Check if chaos is enabled.
    public boolean isEnabled() {
        return enabled.get();
    }

    /// Inject a chaos event immediately.
    public Promise<String> injectChaos(ChaosEvent event) {
        if (!enabled.get()) {
            log.warn("Chaos injection attempted but controller is disabled");
            return Promise.success("disabled");
        }
        var eventId = generateEventId();
        var activeEvent = ActiveChaosEvent.activeChaosEvent(eventId,
                                                            event,
                                                            Instant.now())
                                          .unwrap();
        if (activeEvents.putIfAbsent(eventId, activeEvent) != null) {
            return Promise.success("disabled");
        }
        if (!enabled.get()) {
            activeEvents.remove(eventId);
            return Promise.success("disabled");
        }
        return chaosEventOutcome(eventId, event);
    }

    private static String generateEventId() {
        return UUID.randomUUID()
                   .toString()
                   .substring(0, EVENT_ID_LENGTH);
    }

    private Promise<String> chaosEventOutcome(String eventId, ChaosEvent event) {
        log.info("Injecting chaos event {}: {}", eventId, event.description());
        return liftEventExecution(eventId, event).onSuccess(_ -> scheduleEventRemoval(eventId, event))
                                 .map(_ -> eventId)
                                 .async();
    }

    private Result<Unit> liftEventExecution(String eventId, ChaosEvent event) {
        return Result.lift(_ -> ChaosError.ExecutionFailed.INSTANCE,
                           () -> eventExecutor.accept(event))
                     .onFailure(c -> onEventFailure(eventId, c));
    }

    private void onEventFailure(String eventId, Cause cause) {
        log.error("Failed to execute chaos event {}: {}", eventId, cause.message());
        activeEvents.remove(eventId);
    }

    private void scheduleEventRemoval(String eventId, ChaosEvent event) {
        event.duration()
             .filter(d -> !d.isZero() && !d.isNegative())
             .onPresent(d -> scheduleRemoval(eventId, d));
    }

    private void scheduleRemoval(String eventId, Duration duration) {
        var future = scheduler.schedule(() -> stopChaos(eventId), duration.toMillis(), TimeUnit.MILLISECONDS);
        scheduledTasks.put(eventId, future);
    }

    /// Stop a specific chaos event.
    public Promise<Unit> stopChaos(String eventId) {
        var event = activeEvents.remove(eventId);
        cancelScheduledTask(eventId);
        logStoppedEvent(eventId, event);
        return Promise.success(unit());
    }

    private void cancelScheduledTask(String eventId) {
        var future = scheduledTasks.remove(eventId);
        option(future).onPresent(f -> f.cancel(false));
    }

    private static void logStoppedEvent(String eventId, ActiveChaosEvent event) {
        option(event).onPresent(e -> log.info("Stopping chaos event {}: {}",
                                              eventId,
                                              e.event()
                                               .description()));
    }

    /// Stop all active chaos events.
    public Result<Unit> stopAllChaos() {
        log.info("Stopping all {} active chaos events", activeEvents.size());
        List.copyOf(activeEvents.keySet())
            .forEach(this::stopChaos);
        return unitResult();
    }

    /// Schedule a chaos event for later.
    public String scheduleChaos(ChaosEvent event, Duration delay) {
        if (!enabled.get()) {
            log.warn("Chaos scheduling attempted but controller is disabled");
            return "disabled";
        }
        var scheduleId = "SCH-" + generateEventId();
        log.info("Scheduling chaos event {} in {}: {}", scheduleId, delay, event.description());
        scheduler.schedule(() -> injectChaos(event), delay.toMillis(), TimeUnit.MILLISECONDS);
        return scheduleId;
    }

    /// Get list of active chaos events.
    public List<ActiveChaosEvent> activeEvents() {
        return List.copyOf(activeEvents.values());
    }

    /// Get chaos controller status.
    public ChaosStatus status() {
        var eventsCopy = List.copyOf(activeEvents.values());
        return ChaosStatus.chaosStatus(enabled.get(),
                                       activeEvents.size(),
                                       eventsCopy)
                          .unwrap();
    }

    /// Shutdown the chaos controller.
    public Result<Unit> shutdown() {
        stopAllChaos();
        scheduler.shutdown();
        try{
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread()
                  .interrupt();
        }
        return unitResult();
    }

    /// Active chaos event with metadata.
    public record ActiveChaosEvent(String eventId,
                                   ChaosEvent event,
                                   Instant startedAt) {
        public static Result<ActiveChaosEvent> activeChaosEvent(String eventId,
                                                                ChaosEvent event,
                                                                Instant startedAt) {
            return success(new ActiveChaosEvent(eventId, event, startedAt));
        }

        public String toJson() {
            var durationStr = event.duration()
                                   .map(ChaosController::durationText)
                                   .or("indefinite");
            return String.format("{\"eventId\":\"%s\",\"type\":\"%s\",\"description\":\"%s\",\"startedAt\":\"%s\",\"duration\":\"%s\"}",
                                 eventId,
                                 event.type(),
                                 event.description(),
                                 startedAt,
                                 durationStr);
        }
    }

    private static String durationText(Duration d) {
        return d.toSeconds() + "s";
    }

    /// Chaos controller status.
    public record ChaosStatus(boolean enabled,
                              int activeEventCount,
                              List<ActiveChaosEvent> activeEvents) {
        public static Result<ChaosStatus> chaosStatus(boolean enabled,
                                                      int activeEventCount,
                                                      List<ActiveChaosEvent> activeEvents) {
            return success(new ChaosStatus(enabled, activeEventCount, activeEvents));
        }

        public String toJson() {
            var eventsJson = buildEventsJson();
            return String.format("{\"enabled\":%b,\"activeEventCount\":%d,\"activeEvents\":%s}",
                                 enabled,
                                 activeEventCount,
                                 eventsJson);
        }

        private String buildEventsJson() {
            var sb = new StringBuilder("[");
            var first = true;
            for (var event : activeEvents) {
                if (!first) {
                    sb.append(",");
                }
                first = false;
                sb.append(event.toJson());
            }
            sb.append("]");
            return sb.toString();
        }
    }

    /// Chaos-specific errors.
    public sealed interface ChaosError extends Cause {
        record ExecutionFailed() implements ChaosError {
            private static final ExecutionFailed INSTANCE = executionFailed().unwrap();

            public static Result<ExecutionFailed> executionFailed() {
                return success(new ExecutionFailed());
            }

            @Override
            public String message() {
                return "Failed to execute chaos event";
            }
        }

        record unused() implements ChaosError {
            @Override
            public String message() {
                return "";
            }
        }
    }
}

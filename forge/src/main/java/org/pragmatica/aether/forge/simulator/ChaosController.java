package org.pragmatica.aether.forge.simulator;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Controller for chaos engineering experiments.
 * Injects failures and disruptions to test system resilience.
 */
public final class ChaosController {
    private static final Logger log = LoggerFactory.getLogger(ChaosController.class);
    private static final int EVENT_ID_LENGTH = 8;

    private final AtomicBoolean enabled = new AtomicBoolean(false);
    private final Map<String, ActiveChaosEvent> activeEvents = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture< ? >> scheduledTasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final Consumer<ChaosEvent> eventExecutor;

    private ChaosController(Consumer<ChaosEvent> eventExecutor) {
        this.eventExecutor = eventExecutor;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                                                                        var t = new Thread(r, "chaos-controller");
                                                                        t.setDaemon(true);
                                                                        return t;
                                                                    });
    }

    public static ChaosController chaosController(Consumer<ChaosEvent> eventExecutor) {
        return new ChaosController(eventExecutor);
    }

    /**
     * Enable or disable chaos injection.
     */
    public void setEnabled(boolean enabled) {
        this.enabled.set(enabled);
        log.info("Chaos controller {}", enabled
                                       ? "enabled"
                                       : "disabled");
        if (!enabled) {
            stopAllChaos();
        }
    }

    /**
     * Check if chaos is enabled.
     */
    public boolean isEnabled() {
        return enabled.get();
    }

    /**
     * Inject a chaos event immediately.
     */
    public Promise<String> injectChaos(ChaosEvent event) {
        if (!enabled.get()) {
            log.warn("Chaos injection attempted but controller is disabled");
            return Promise.success("disabled");
        }
        var eventId = UUID.randomUUID()
                          .toString()
                          .substring(0, EVENT_ID_LENGTH);
        var activeEvent = new ActiveChaosEvent(eventId, event, Instant.now());
        // Use putIfAbsent and verify still enabled after insertion (race condition fix)
        if (activeEvents.putIfAbsent(eventId, activeEvent) != null) {
            return Promise.success("disabled");
        }
        // Double-check enabled after map insertion
        if (!enabled.get()) {
            activeEvents.remove(eventId);
            return Promise.success("disabled");
        }
        log.info("Injecting chaos event {}: {}", eventId, event.description());
        try{
            eventExecutor.accept(event);
        } catch (Exception e) {
            log.error("Failed to execute chaos event {}: {}", eventId, e.getMessage());
            activeEvents.remove(eventId);
            return ChaosError.ExecutionFailed.INSTANCE.promise();
        }
        // Schedule removal after duration, track the future for cancellation
        if (event.duration() != null && !event.duration()
                                              .isZero() && !event.duration()
                                                                 .isNegative()) {
            var future = scheduler.schedule(() -> stopChaos(eventId),
                                            event.duration()
                                                 .toMillis(),
                                            TimeUnit.MILLISECONDS);
            scheduledTasks.put(eventId, future);
        }
        return Promise.success(eventId);
    }

    /**
     * Stop a specific chaos event.
     */
    public Promise<Unit> stopChaos(String eventId) {
        var event = activeEvents.remove(eventId);
        var future = scheduledTasks.remove(eventId);
        if (future != null) {
            future.cancel(false);
        }
        if (event != null) {
            log.info("Stopping chaos event {}: {}",
                     eventId,
                     event.event()
                          .description());
        }
        return Promise.success(Unit.unit());
    }

    /**
     * Stop all active chaos events.
     */
    public void stopAllChaos() {
        log.info("Stopping all {} active chaos events", activeEvents.size());
        for (var eventId : List.copyOf(activeEvents.keySet())) {
            stopChaos(eventId);
        }
    }

    /**
     * Schedule a chaos event for later.
     */
    public String scheduleChaos(ChaosEvent event, Duration delay) {
        if (!enabled.get()) {
            log.warn("Chaos scheduling attempted but controller is disabled");
            return "disabled";
        }
        var scheduleId = "SCH-" + UUID.randomUUID()
                                     .toString()
                                     .substring(0, EVENT_ID_LENGTH);
        log.info("Scheduling chaos event {} in {}: {}", scheduleId, delay, event.description());
        scheduler.schedule(() -> injectChaos(event), delay.toMillis(), TimeUnit.MILLISECONDS);
        return scheduleId;
    }

    /**
     * Get list of active chaos events.
     */
    public List<ActiveChaosEvent> activeEvents() {
        return List.copyOf(activeEvents.values());
    }

    /**
     * Get chaos controller status.
     */
    public ChaosStatus status() {
        return new ChaosStatus(enabled.get(),
                               activeEvents.size(),
                               List.copyOf(activeEvents.values()));
    }

    /**
     * Shutdown the chaos controller.
     */
    public void shutdown() {
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
    }

    /**
     * Active chaos event with metadata.
     */
    public record ActiveChaosEvent(
    String eventId,
    ChaosEvent event,
    Instant startedAt) {
        public String toJson() {
            return String.format(
            "{\"eventId\":\"%s\",\"type\":\"%s\",\"description\":\"%s\",\"startedAt\":\"%s\",\"duration\":\"%s\"}",
            eventId,
            event.type(),
            event.description(),
            startedAt,
            event.duration() != null
            ? event.duration()
                   .toSeconds() + "s"
            : "indefinite");
        }
    }

    /**
     * Chaos controller status.
     */
    public record ChaosStatus(
    boolean enabled,
    int activeEventCount,
    List<ActiveChaosEvent> activeEvents) {
        public String toJson() {
            var eventsJson = new StringBuilder("[");
            var first = true;
            for (var event : activeEvents) {
                if (!first) eventsJson.append(",");
                first = false;
                eventsJson.append(event.toJson());
            }
            eventsJson.append("]");
            return String.format(
            "{\"enabled\":%b,\"activeEventCount\":%d,\"activeEvents\":%s}", enabled, activeEventCount, eventsJson);
        }
    }

    /**
     * Chaos-specific errors.
     */
    public sealed interface ChaosError extends org.pragmatica.lang.Cause {
        record ExecutionFailed() implements ChaosError {
            public static final ExecutionFailed INSTANCE = new ExecutionFailed();

            @Override
            public String message() {
                return "Failed to execute chaos event";
            }
        }
    }
}

package org.pragmatica.aether.forge.api;

import org.pragmatica.aether.forge.ForgeCluster;
import org.pragmatica.aether.forge.api.ForgeApiResponses.*;
import org.pragmatica.aether.forge.simulator.ChaosController;
import org.pragmatica.aether.forge.simulator.ChaosController.ActiveChaosEvent;
import org.pragmatica.aether.forge.simulator.ChaosController.ChaosStatus;
import org.pragmatica.aether.forge.simulator.ChaosEvent;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import static org.pragmatica.http.routing.PathParameter.aString;
import static org.pragmatica.http.routing.Route.get;
import static org.pragmatica.http.routing.Route.in;
import static org.pragmatica.http.routing.Route.post;

/**
 * REST API routes for chaos engineering operations.
 * Provides endpoints for enabling/disabling chaos, injecting events,
 * and managing cluster nodes (add, kill, rolling restart).
 */
public final class ChaosRoutes {
    private ChaosRoutes() {}

    private static final Fn1<Cause, String> UNKNOWN_CHAOS_TYPE = Causes.forOneValue("Unknown chaos type: %s");

    /**
     * Creates chaos routes for the Forge API.
     *
     * @param cluster         the ForgeCluster for node management operations
     * @param chaosController the ChaosController for chaos injection
     * @param eventLogger     callback to log events for the dashboard
     * @return RouteSource containing all chaos-related routes
     */
    public static RouteSource chaosRoutes(ForgeCluster cluster,
                                          ChaosController chaosController,
                                          Consumer<EventLogEntry> eventLogger) {
        return in("/api/chaos")
                 .serve(statusRoute(chaosController),
                        enableRoute(chaosController, eventLogger),
                        injectRoute(chaosController, eventLogger),
                        stopRoute(chaosController, eventLogger),
                        stopAllRoute(chaosController, eventLogger),
                        addNodeRoute(cluster, eventLogger),
                        killNodeRoute(cluster, eventLogger),
                        rollingRestartRoute(cluster, eventLogger));
    }

    // ========== Route Definitions ==========
    private static Route<ChaosStatusResponse> statusRoute(ChaosController chaosController) {
        return Route.<ChaosStatusResponse> get("/status")
                    .toJson(() -> chaosStatus(chaosController));
    }

    private static Route<ChaosEnabledResponse> enableRoute(ChaosController chaosController,
                                                           Consumer<EventLogEntry> eventLogger) {
        return Route.<ChaosEnabledResponse> post("/enable")
                    .withBody(EnableRequest.class)
                    .toJson(req -> enableChaos(chaosController, eventLogger, req));
    }

    private static Route<ChaosInjectResponse> injectRoute(ChaosController chaosController,
                                                          Consumer<EventLogEntry> eventLogger) {
        return Route.<ChaosInjectResponse> post("/inject")
                    .withBody(InjectRequest.class)
                    .toJson(req -> injectChaos(chaosController, eventLogger, req));
    }

    private static Route<ChaosStoppedResponse> stopRoute(ChaosController chaosController,
                                                         Consumer<EventLogEntry> eventLogger) {
        return Route.<ChaosStoppedResponse> post("/stop")
                    .withPath(aString())
                    .to(eventId -> stopChaos(chaosController, eventLogger, eventId))
                    .asJson();
    }

    private static Route<SuccessResponse> stopAllRoute(ChaosController chaosController,
                                                       Consumer<EventLogEntry> eventLogger) {
        return Route.<SuccessResponse> post("/stop-all")
                    .toJson(_ -> stopAllChaos(chaosController, eventLogger));
    }

    private static Route<NodeAddedResponse> addNodeRoute(ForgeCluster cluster,
                                                         Consumer<EventLogEntry> eventLogger) {
        return Route.<NodeAddedResponse> post("/add-node")
                    .toJson(_ -> addNode(cluster, eventLogger));
    }

    private static Route<NodeActionResponse> killNodeRoute(ForgeCluster cluster,
                                                           Consumer<EventLogEntry> eventLogger) {
        return Route.<NodeActionResponse> post("/kill")
                    .withPath(aString())
                    .to(nodeId -> killNode(cluster, eventLogger, nodeId))
                    .asJson();
    }

    private static Route<SuccessResponse> rollingRestartRoute(ForgeCluster cluster,
                                                              Consumer<EventLogEntry> eventLogger) {
        return Route.<SuccessResponse> post("/rolling-restart")
                    .toJson(_ -> rollingRestart(cluster, eventLogger));
    }

    // ========== Request Records ==========
    /**
     * Request to enable or disable chaos injection.
     */
    public record EnableRequest(boolean enabled) {}

    /**
     * Request to inject a chaos event.
     */
    public record InjectRequest(String type,
                                String nodeId,
                                String artifact,
                                Long latencyMs,
                                Double level,
                                Double failureRate,
                                Long durationSeconds) {}

    /**
     * Event log entry for dashboard notifications.
     */
    public record EventLogEntry(String type, String message) {}

    // ========== Handler Methods ==========
    private static ChaosStatusResponse chaosStatus(ChaosController controller) {
        ChaosStatus status = controller.status();
        List<ActiveChaosEventInfo> activeEventInfos = status.activeEvents()
                                                            .stream()
                                                            .map(ChaosRoutes::toEventInfo)
                                                            .toList();
        return new ChaosStatusResponse(status.enabled(), status.activeEventCount(), activeEventInfos);
    }

    private static ActiveChaosEventInfo toEventInfo(ActiveChaosEvent event) {
        ChaosEvent chaosEvent = event.event();
        String durationStr = chaosEvent.duration() != null
                             ? chaosEvent.duration()
                                         .toSeconds() + "s"
                             : "indefinite";
        return new ActiveChaosEventInfo(event.eventId(),
                                        chaosEvent.type(),
                                        chaosEvent.description(),
                                        event.startedAt()
                                             .toString(),
                                        durationStr);
    }

    private static Promise<ChaosEnabledResponse> enableChaos(ChaosController controller,
                                                             Consumer<EventLogEntry> eventLogger,
                                                             EnableRequest request) {
        controller.setEnabled(request.enabled());
        String eventType = request.enabled()
                           ? "CHAOS_ENABLED"
                           : "CHAOS_DISABLED";
        String message = "Chaos controller " + (request.enabled()
                                                ? "enabled"
                                                : "disabled");
        eventLogger.accept(new EventLogEntry(eventType, message));
        return Promise.success(new ChaosEnabledResponse(true, request.enabled()));
    }

    private static Promise<ChaosInjectResponse> injectChaos(ChaosController controller,
                                                            Consumer<EventLogEntry> eventLogger,
                                                            InjectRequest request) {
        Duration duration = Duration.ofSeconds(request.durationSeconds() != null
                                               ? request.durationSeconds()
                                               : 60);
        return parseChaosEvent(request, duration)
                              .async()
                              .flatMap(controller::injectChaos)
                              .map(eventId -> logAndBuildInjectResponse(eventLogger, request, eventId));
    }

    private static ChaosInjectResponse logAndBuildInjectResponse(Consumer<EventLogEntry> eventLogger,
                                                                 InjectRequest request,
                                                                 String eventId) {
        eventLogger.accept(new EventLogEntry("CHAOS_INJECTED", "Injected " + request.type() + " event: " + eventId));
        return new ChaosInjectResponse(true, eventId, request.type());
    }

    private static Result<ChaosEvent> parseChaosEvent(InjectRequest request, Duration duration) {
        String type = request.type() != null
                      ? request.type()
                               .toUpperCase()
                      : "";
        return switch (type) {
            case "NODE_KILL" -> ChaosEvent.NodeKill.kill(request.nodeId(),
                                                         duration)
                                          .map(e -> e);
            case "LATENCY_SPIKE" -> ChaosEvent.LatencySpike.addLatency(request.nodeId(),
                                                                       request.latencyMs() != null
                                                                       ? request.latencyMs()
                                                                       : 500,
                                                                       duration)
                                              .map(e -> e);
            case "SLICE_CRASH" -> ChaosEvent.SliceCrash.crashSlice(request.artifact(),
                                                                   request.nodeId(),
                                                                   duration)
                                            .map(e -> e);
            case "INVOCATION_FAILURE" -> ChaosEvent.InvocationFailure.forSlice(request.artifact(),
                                                                               request.failureRate() != null
                                                                               ? request.failureRate()
                                                                               : 0.5,
                                                                               duration)
                                                   .map(e -> e);
            case "CPU_SPIKE" -> ChaosEvent.CpuSpike.onNode(request.nodeId(),
                                                           request.level() != null
                                                           ? request.level()
                                                           : 0.8,
                                                           duration)
                                          .map(e -> e);
            case "MEMORY_PRESSURE" -> ChaosEvent.MemoryPressure.onNode(request.nodeId(),
                                                                       request.level() != null
                                                                       ? request.level()
                                                                       : 0.9,
                                                                       duration)
                                                .map(e -> e);
            default -> UNKNOWN_CHAOS_TYPE.apply(type)
                                         .result();
        };
    }

    private static Promise<ChaosStoppedResponse> stopChaos(ChaosController controller,
                                                           Consumer<EventLogEntry> eventLogger,
                                                           String eventId) {
        return controller.stopChaos(eventId)
                         .map(_ -> logAndBuildStopResponse(eventLogger, eventId));
    }

    private static ChaosStoppedResponse logAndBuildStopResponse(Consumer<EventLogEntry> eventLogger,
                                                                String eventId) {
        eventLogger.accept(new EventLogEntry("CHAOS_STOPPED", "Stopped chaos event " + eventId));
        return new ChaosStoppedResponse(true, eventId);
    }

    private static Promise<SuccessResponse> stopAllChaos(ChaosController controller,
                                                         Consumer<EventLogEntry> eventLogger) {
        controller.stopAllChaos();
        eventLogger.accept(new EventLogEntry("CHAOS_STOPPED_ALL", "Stopped all chaos events"));
        return Promise.success(SuccessResponse.OK);
    }

    private static Promise<NodeAddedResponse> addNode(ForgeCluster cluster,
                                                      Consumer<EventLogEntry> eventLogger) {
        eventLogger.accept(new EventLogEntry("ADD_NODE", "Adding new node to cluster"));
        return cluster.addNode()
                      .map(nodeId -> logAndBuildNodeAddedResponse(eventLogger, nodeId))
                      .onFailure(cause -> eventLogger.accept(new EventLogEntry("ADD_NODE_FAILED",
                                                                               "Failed to add node: " + cause.message())));
    }

    private static NodeAddedResponse logAndBuildNodeAddedResponse(Consumer<EventLogEntry> eventLogger,
                                                                  org.pragmatica.consensus.NodeId nodeId) {
        eventLogger.accept(new EventLogEntry("NODE_JOINED", "Node " + nodeId.id() + " joined the cluster"));
        return new NodeAddedResponse(true, nodeId.id(), "joining");
    }

    private static Promise<NodeActionResponse> killNode(ForgeCluster cluster,
                                                        Consumer<EventLogEntry> eventLogger,
                                                        String nodeId) {
        boolean wasLeader = cluster.currentLeader()
                                   .map(l -> l.equals(nodeId))
                                   .or(false);
        eventLogger.accept(new EventLogEntry("KILL_NODE", "Killing node " + nodeId + (wasLeader
                                                                                      ? " (leader)"
                                                                                      : "")));
        return cluster.killNode(nodeId)
                      .map(_ -> logAndBuildNodeKilledResponse(cluster, eventLogger, nodeId, wasLeader))
                      .onFailure(cause -> eventLogger.accept(new EventLogEntry("KILL_FAILED",
                                                                               "Failed to kill node " + nodeId)));
    }

    private static NodeActionResponse logAndBuildNodeKilledResponse(ForgeCluster cluster,
                                                                    Consumer<EventLogEntry> eventLogger,
                                                                    String nodeId,
                                                                    boolean wasLeader) {
        String newLeader = cluster.currentLeader()
                                  .or("none");
        eventLogger.accept(new EventLogEntry("NODE_KILLED",
                                             "Node " + nodeId + " killed" + (wasLeader
                                                                             ? ", new leader: " + newLeader
                                                                             : "")));
        return new NodeActionResponse(true, newLeader);
    }

    private static Promise<SuccessResponse> rollingRestart(ForgeCluster cluster,
                                                           Consumer<EventLogEntry> eventLogger) {
        eventLogger.accept(new EventLogEntry("ROLLING_RESTART", "Starting rolling restart of all nodes"));
        return cluster.rollingRestart()
                      .map(_ -> logAndBuildRollingRestartResponse(eventLogger))
                      .onFailure(cause -> eventLogger.accept(new EventLogEntry("ROLLING_RESTART_FAILED",
                                                                               "Rolling restart failed: " + cause.message())));
    }

    private static SuccessResponse logAndBuildRollingRestartResponse(Consumer<EventLogEntry> eventLogger) {
        eventLogger.accept(new EventLogEntry("ROLLING_RESTART_COMPLETE", "Rolling restart completed successfully"));
        return SuccessResponse.OK;
    }
}

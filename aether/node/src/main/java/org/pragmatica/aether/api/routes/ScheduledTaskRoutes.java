package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.invoke.ScheduledTaskManager;
import org.pragmatica.aether.invoke.ScheduledTaskRegistry;
import org.pragmatica.aether.invoke.ScheduledTaskRegistry.ScheduledTask;
import org.pragmatica.aether.invoke.ScheduledTaskStateRegistry;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.slice.ExecutionMode;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskStateKey;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.pragmatica.http.routing.PathParameter.aString;

/// Routes for scheduled task management: listing, pause/resume, and manual trigger.
public final class ScheduledTaskRoutes implements RouteSource {
    private static final Cause TASK_NOT_FOUND = Causes.cause("Scheduled task not found");
    private static final Cause UNKNOWN_ACTION = Causes.cause("Unknown action (expected: pause, resume, trigger)");

    private final ScheduledTaskRegistry registry;
    private final ScheduledTaskManager manager;
    private final Supplier<AetherNode> nodeSupplier;
    private final SliceInvoker invoker;
    private final ScheduledTaskStateRegistry stateRegistry;

    private ScheduledTaskRoutes(ScheduledTaskRegistry registry,
                                ScheduledTaskManager manager,
                                Supplier<AetherNode> nodeSupplier,
                                SliceInvoker invoker,
                                ScheduledTaskStateRegistry stateRegistry) {
        this.registry = registry;
        this.manager = manager;
        this.nodeSupplier = nodeSupplier;
        this.invoker = invoker;
        this.stateRegistry = stateRegistry;
    }

    public static ScheduledTaskRoutes scheduledTaskRoutes(ScheduledTaskRegistry registry,
                                                          ScheduledTaskManager manager,
                                                          Supplier<AetherNode> nodeSupplier,
                                                          SliceInvoker invoker,
                                                          ScheduledTaskStateRegistry stateRegistry) {
        return new ScheduledTaskRoutes(registry, manager, nodeSupplier, invoker, stateRegistry);
    }

    record TaskSummary(String configSection,
                       String artifact,
                       String method,
                       String interval,
                       String cron,
                       ExecutionMode executionMode,
                       boolean paused,
                       String registeredBy,
                       long lastExecutionAt,
                       long nextFireAt,
                       int consecutiveFailures,
                       int totalExecutions){}

    record ScheduledTasksResponse(List<TaskSummary> tasks, int activeTimers){}

    record FilteredTasksResponse(List<TaskSummary> tasks, String configSection){}

    record TaskActionResult(boolean success, String configSection, String artifact, String method, String action){}

    record TaskStateResponse(String configSection,
                             String artifact,
                             String method,
                             long lastExecutionAt,
                             long nextFireAt,
                             int consecutiveFailures,
                             int totalExecutions,
                             String lastFailureMessage,
                             long updatedAt){}

    @Override public Stream<Route<?>> routes() {
        return Stream.of(Route.<ScheduledTasksResponse>get("/api/scheduled-tasks")
                              .toJson(this::buildTasksResponse),
                         Route.<FilteredTasksResponse>get("/api/scheduled-tasks")
                              .withPath(aString())
                              .to(this::buildFilteredResponse)
                              .asJson(),
                         Route.<TaskStateResponse>get("/api/scheduled-tasks")
                              .withPath(aString(),
                                        aString(),
                                        aString(),
                                        aString())
                              .to(this::getTaskState)
                              .asJson(),
                         Route.<TaskActionResult>post("/api/scheduled-tasks")
                              .withPath(aString(),
                                        aString(),
                                        aString(),
                                        aString())
                              .to(this::handleTaskAction)
                              .asJson());
    }

    private ScheduledTasksResponse buildTasksResponse() {
        var tasks = registry.allTasks().stream()
                                     .map(this::toSummary)
                                     .toList();
        return new ScheduledTasksResponse(tasks, manager.activeTimerCount());
    }

    private Promise<FilteredTasksResponse> buildFilteredResponse(String configSection) {
        var tasks = registry.allTasks().stream()
                                     .filter(task -> task.configSection().equals(configSection))
                                     .map(this::toSummary)
                                     .toList();
        return Promise.success(new FilteredTasksResponse(tasks, configSection));
    }

    @SuppressWarnings("JBCT-PAT-01") // Condition: routes action to appropriate handler
    private Promise<TaskActionResult> handleTaskAction(String configSection,
                                                       String artifactStr,
                                                       String methodStr,
                                                       String action) {
        return switch (action) {case "pause" -> setPaused(configSection, artifactStr, methodStr, true);case "resume" -> setPaused(configSection,
                                                                                                                                  artifactStr,
                                                                                                                                  methodStr,
                                                                                                                                  false);case "trigger" -> triggerTask(configSection,
                                                                                                                                                                       artifactStr,
                                                                                                                                                                       methodStr);default -> UNKNOWN_ACTION.promise();};
    }

    private Promise<TaskActionResult> setPaused(String configSection,
                                                String artifactStr,
                                                String methodStr,
                                                boolean paused) {
        return findTask(configSection, artifactStr, methodStr)
        .flatMap(task -> submitPausedUpdate(task, configSection, artifactStr, methodStr, paused));
    }

    private Promise<TaskActionResult> submitPausedUpdate(ScheduledTask task,
                                                         String configSection,
                                                         String artifactStr,
                                                         String methodStr,
                                                         boolean paused) {
        var key = ScheduledTaskKey.scheduledTaskKey(task.configSection(), task.artifact(), task.methodName());
        var value = new ScheduledTaskValue(task.registeredBy(),
                                           task.interval(),
                                           task.cron(),
                                           task.executionMode(),
                                           paused);
        KVCommand<AetherKey> command = new KVCommand.Put<>(key, value);
        var action = paused
                     ? "paused"
                     : "resumed";
        return nodeSupplier.get().apply(List.of(command))
                               .map(_ -> new TaskActionResult(true, configSection, artifactStr, methodStr, action));
    }

    private Promise<TaskActionResult> triggerTask(String configSection,
                                                  String artifactStr,
                                                  String methodStr) {
        return findTask(configSection, artifactStr, methodStr)
        .flatMap(task -> invokeAndBuildResult(task, configSection, artifactStr, methodStr));
    }

    private Promise<TaskActionResult> invokeAndBuildResult(ScheduledTask task,
                                                           String configSection,
                                                           String artifactStr,
                                                           String methodStr) {
        return invoker.invoke(task.artifact(), task.methodName(), Unit.unit())
        .map(_ -> new TaskActionResult(true, configSection, artifactStr, methodStr, "triggered"));
    }

    private Promise<ScheduledTask> findTask(String configSection,
                                            String artifactStr,
                                            String methodStr) {
        return registry.allTasks().stream()
                                .filter(t -> matchesTask(t, configSection, artifactStr, methodStr))
                                .findFirst()
                                .map(Promise::success)
                                .orElse(TASK_NOT_FOUND.promise());
    }

    private static boolean matchesTask(ScheduledTask task, String configSection, String artifactStr, String methodStr) {
        return task.configSection().equals(configSection) && task.artifact().asString()
                                                                          .equals(artifactStr) && task.methodName().name()
                                                                                                                 .equals(methodStr);
    }

    private TaskSummary toSummary(ScheduledTask task) {
        var stateKey = ScheduledTaskStateKey.scheduledTaskStateKey(task.configSection(),
                                                                   task.artifact(),
                                                                   task.methodName());
        return stateRegistry.stateFor(stateKey)
        .fold(() -> buildSummary(task, 0, 0, 0, 0),
              state -> buildSummary(task,
                                    state.lastExecutionAt(),
                                    state.nextFireAt(),
                                    state.consecutiveFailures(),
                                    state.totalExecutions()));
    }

    private static TaskSummary buildSummary(ScheduledTask task,
                                            long lastExecutionAt,
                                            long nextFireAt,
                                            int consecutiveFailures,
                                            int totalExecutions) {
        return new TaskSummary(task.configSection(),
                               task.artifact().asString(),
                               task.methodName().name(),
                               task.interval(),
                               task.cron(),
                               task.executionMode(),
                               task.paused(),
                               task.registeredBy().id(),
                               lastExecutionAt,
                               nextFireAt,
                               consecutiveFailures,
                               totalExecutions);
    }

    @SuppressWarnings("JBCT-PAT-01") // Condition: routes action to state handler
    private Promise<TaskStateResponse> getTaskState(String configSection,
                                                    String artifactStr,
                                                    String methodStr,
                                                    String stateAction) {
        if ( !"state".equals(stateAction)) {
        return Causes.cause("Unknown GET action: " + stateAction).promise();}
        return findTask(configSection, artifactStr, methodStr)
        .flatMap(task -> buildStateResponse(task, configSection, artifactStr, methodStr));
    }

    private Promise<TaskStateResponse> buildStateResponse(ScheduledTask task,
                                                          String configSection,
                                                          String artifactStr,
                                                          String methodStr) {
        var stateKey = ScheduledTaskStateKey.scheduledTaskStateKey(task.configSection(),
                                                                   task.artifact(),
                                                                   task.methodName());
        return stateRegistry.stateFor(stateKey)
        .fold(() -> Promise.success(emptyStateResponse(configSection, artifactStr, methodStr)),
              state -> Promise.success(new TaskStateResponse(configSection,
                                                             artifactStr,
                                                             methodStr,
                                                             state.lastExecutionAt(),
                                                             state.nextFireAt(),
                                                             state.consecutiveFailures(),
                                                             state.totalExecutions(),
                                                             state.lastFailureMessage(),
                                                             state.updatedAt())));
    }

    private static TaskStateResponse emptyStateResponse(String configSection, String artifactStr, String methodStr) {
        return new TaskStateResponse(configSection, artifactStr, methodStr, 0, 0, 0, 0, "", 0);
    }
}

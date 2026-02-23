package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.invoke.ScheduledTaskManager;
import org.pragmatica.aether.invoke.ScheduledTaskRegistry;
import org.pragmatica.aether.invoke.ScheduledTaskRegistry.ScheduledTask;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Promise;

import java.util.List;
import java.util.stream.Stream;

import static org.pragmatica.http.routing.PathParameter.aString;

/// Routes for scheduled task management: listing and inspection.
public final class ScheduledTaskRoutes implements RouteSource {
    private final ScheduledTaskRegistry registry;
    private final ScheduledTaskManager manager;

    private ScheduledTaskRoutes(ScheduledTaskRegistry registry, ScheduledTaskManager manager) {
        this.registry = registry;
        this.manager = manager;
    }

    public static ScheduledTaskRoutes scheduledTaskRoutes(ScheduledTaskRegistry registry,
                                                          ScheduledTaskManager manager) {
        return new ScheduledTaskRoutes(registry, manager);
    }

    record TaskSummary(String configSection,
                       String artifact,
                       String method,
                       String interval,
                       String cron,
                       boolean leaderOnly,
                       String registeredBy) {}

    record ScheduledTasksResponse(List<TaskSummary> tasks, int activeTimers) {}

    record FilteredTasksResponse(List<TaskSummary> tasks, String configSection) {}

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(Route.<ScheduledTasksResponse> get("/api/scheduled-tasks")
                              .toJson(this::buildTasksResponse),
                         Route.<FilteredTasksResponse> get("/api/scheduled-tasks")
                              .withPath(aString())
                              .to(this::buildFilteredResponse)
                              .asJson());
    }

    private ScheduledTasksResponse buildTasksResponse() {
        var tasks = registry.allTasks()
                            .stream()
                            .map(ScheduledTaskRoutes::toSummary)
                            .toList();
        return new ScheduledTasksResponse(tasks, manager.activeTimerCount());
    }

    private Promise<FilteredTasksResponse> buildFilteredResponse(String configSection) {
        var tasks = registry.allTasks()
                            .stream()
                            .filter(task -> task.configSection()
                                                .equals(configSection))
                            .map(ScheduledTaskRoutes::toSummary)
                            .toList();
        return Promise.success(new FilteredTasksResponse(tasks, configSection));
    }

    private static TaskSummary toSummary(ScheduledTask task) {
        return new TaskSummary(task.configSection(),
                               task.artifact()
                                   .asString(),
                               task.methodName()
                                   .name(),
                               task.interval(),
                               task.cron(),
                               task.leaderOnly(),
                               task.registeredBy()
                                   .id());
    }
}

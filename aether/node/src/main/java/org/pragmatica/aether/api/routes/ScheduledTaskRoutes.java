// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.aether.api.ManagementApiResponses.ScheduledTaskExecutionsByNodeResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ScheduledTaskInjectRequest;
import org.pragmatica.aether.api.ManagementApiResponses.ScheduledTaskInjectResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ScheduledTaskNodeExecution;
import org.pragmatica.aether.invoke.ScheduledTaskManager;
import org.pragmatica.aether.invoke.ScheduledTaskRegistry;
import org.pragmatica.aether.invoke.ScheduledTaskRegistry.ScheduledTask;
import org.pragmatica.aether.invoke.ScheduledTaskStateRegistry;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.slice.ExecutionMode;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskStateKey;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskStateValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.http.routing.PathParameter.aString;


@SuppressWarnings("JBCT-RET-01")
public final class ScheduledTaskRoutes implements RouteSource {
    private static final Cause TASK_NOT_FOUND = Causes.cause("Scheduled task not found");
    private static final String DEV_MODE_ENV = "AETHER_INSECURE_DEV_MODE";

    private final ScheduledTaskRegistry registry;
    private final ScheduledTaskManager manager;
    private final Supplier<ManageableNode> nodeSupplier;
    private final SliceInvoker invoker;
    private final ScheduledTaskStateRegistry stateRegistry;
    private final BooleanSupplier devModeEnabled;

    private ScheduledTaskRoutes(ScheduledTaskRegistry registry,
                                ScheduledTaskManager manager,
                                Supplier<ManageableNode> nodeSupplier,
                                SliceInvoker invoker,
                                ScheduledTaskStateRegistry stateRegistry,
                                BooleanSupplier devModeEnabled) {
        this.registry = registry;
        this.manager = manager;
        this.nodeSupplier = nodeSupplier;
        this.invoker = invoker;
        this.stateRegistry = stateRegistry;
        this.devModeEnabled = devModeEnabled;
    }

    public static ScheduledTaskRoutes scheduledTaskRoutes(ScheduledTaskRegistry registry,
                                                          ScheduledTaskManager manager,
                                                          Supplier<ManageableNode> nodeSupplier,
                                                          SliceInvoker invoker,
                                                          ScheduledTaskStateRegistry stateRegistry) {
        return new ScheduledTaskRoutes(registry,
                                       manager,
                                       nodeSupplier,
                                       invoker,
                                       stateRegistry,
                                       ScheduledTaskRoutes::devModeFromEnv);
    }

    /// Test-friendly factory: callers (unit tests) inject the dev-mode flag directly
    /// rather than mutating the JVM-wide environment. Production callers use the
    /// no-supplier overload which defers to `AETHER_INSECURE_DEV_MODE`.
    public static ScheduledTaskRoutes scheduledTaskRoutes(ScheduledTaskRegistry registry,
                                                          ScheduledTaskManager manager,
                                                          Supplier<ManageableNode> nodeSupplier,
                                                          SliceInvoker invoker,
                                                          ScheduledTaskStateRegistry stateRegistry,
                                                          BooleanSupplier devModeEnabled) {
        return new ScheduledTaskRoutes(registry, manager, nodeSupplier, invoker, stateRegistry, devModeEnabled);
    }

    private static boolean devModeFromEnv() {
        return "true".equalsIgnoreCase(System.getenv(DEV_MODE_ENV));
    }

    /// Package-private accessor for unit tests that exercise the inject handler
    /// without standing up the full HTTP layer. Production callers go through the
    /// `routes()` stream registered with the management server.
    Promise<ScheduledTaskInjectResponse> handleInjectForTest(ScheduledTaskInjectRequest req) {
        return handleInject(req);
    }

    /// Package-private read-only accessors mirroring `handleInjectForTest`: let unit tests
    /// assert the deterministic ordering of the list / by-section responses without standing
    /// up the HTTP routing layer. No write path is touched.
    ScheduledTasksResponse buildTasksResponseForTest() {
        return buildTasksResponse();
    }

    Promise<FilteredTasksResponse> buildFilteredResponseForTest(String configSection) {
        return buildFilteredResponse(configSection);
    }

    /// Package-private accessor mirroring `handleInjectForTest`: lets unit tests drive the
    /// manual-trigger path (including the `tryClaim` conflict guard, #273 review item 1)
    /// without standing up the HTTP routing layer.
    Promise<TaskActionResult> triggerForTest(String configSection, String artifactStr, String methodStr) {
        return triggerTask(configSection, artifactStr, methodStr);
    }

    /// Package-private accessor mirroring `triggerForTest`: lets unit tests drive the single-task
    /// `/state` handler (including the #680/#841 ALL-mode per-node aggregation in
    /// [#buildStateResponse]) without standing up the HTTP routing layer.
    Promise<TaskStateResponse> getTaskStateForTest(String configSection, String artifactStr, String methodStr) {
        return getTaskState(configSection, artifactStr, methodStr);
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
                       int totalExecutions) {}

    record ScheduledTasksResponse(List<TaskSummary> tasks, int activeTimers) {}

    record FilteredTasksResponse(List<TaskSummary> tasks, String configSection) {}

    record TaskActionResult(boolean success, String configSection, String artifact, String method, String action) {}

    record TaskStateResponse(String configSection,
                             String artifact,
                             String method,
                             long lastExecutionAt,
                             long nextFireAt,
                             int consecutiveFailures,
                             int totalExecutions,
                             String lastFailureMessage,
                             long updatedAt,
                             int skippedOverlaps) {}

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(ManagementRoutes.<ScheduledTasksResponse> route(ManagementRoute.SCHEDULED_TASKS_LIST).toJson(this::buildTasksResponse),
                         ManagementRoutes.<FilteredTasksResponse> route(ManagementRoute.SCHEDULED_TASKS_BY_SECTION)
                                         .withPath(aString())
                                         .to(this::buildFilteredResponse)
                                         .asJson(),
                         ManagementRoutes.<TaskStateResponse> route(ManagementRoute.SCHEDULED_TASK_STATE)
                                         .withPath(aString(),
                                                   aString(),
                                                   aString())
                                         .to(this::getTaskState)
                                         .asJson(),
                         ManagementRoutes.<TaskActionResult> route(ManagementRoute.SCHEDULED_TASK_PAUSE)
                                         .withPath(aString(),
                                                   aString(),
                                                   aString())
                                         .to((section, artifact, method) -> setPaused(section, artifact, method, true))
                                         .asJson(),
                         ManagementRoutes.<TaskActionResult> route(ManagementRoute.SCHEDULED_TASK_RESUME)
                                         .withPath(aString(),
                                                   aString(),
                                                   aString())
                                         .to((section, artifact, method) -> setPaused(section, artifact, method, false))
                                         .asJson(),
                         ManagementRoutes.<TaskActionResult> route(ManagementRoute.SCHEDULED_TASK_TRIGGER)
                                         .withPath(aString(),
                                                   aString(),
                                                   aString())
                                         .to(this::triggerTask)
                                         .asJson(),
                         ManagementRoutes.<ScheduledTaskInjectResponse> route(ManagementRoute.SCHEDULED_TASK_INJECT)
                                         .withBody(ScheduledTaskInjectRequest.class)
                                         .toJson(this::handleInject),
                         ManagementRoutes.<ScheduledTaskExecutionsByNodeResponse> route(ManagementRoute.SCHEDULED_TASK_EXECUTIONS_BY_NODE)
                                         .withPath(aString(),
                                                   aString(),
                                                   aString())
                                         .to(this::getExecutionsByNode)
                                         .asJson());
    }

    /// Synchronous test-only fire path. Gated by `AETHER_INSECURE_DEV_MODE` (or the
    /// supplier injected at construction time for unit tests). On success: invokes the
    /// task body, persists a fresh `successState` (or `failureState` on invocation
    /// failure) via consensus apply, and returns the prior + current `lastExecutionAt`
    /// so the caller can assert strict monotonic advancement.
    ///
    /// Differs from `triggerTask`:
    ///   - dev-mode gated (production clusters reject with 403-equivalent failure)
    ///   - synchronously writes state (trigger relies on `ScheduledTaskManager`'s
    ///     timer-driven success/failure write, which is asynchronous and may not
    ///     have landed by the time the caller reads `/api/scheduled-tasks/state`)
    ///   - returns previousExecutionMs in the response (enables strict-advancement
    ///     assertions in integration tests; see RC1-blocker #16)
    private Promise<ScheduledTaskInjectResponse> handleInject(ScheduledTaskInjectRequest req) {
        if (!devModeEnabled.getAsBoolean()) {
            return InjectError.DEV_MODE_DISABLED.promise();
        }

        return validateInjectRequest(req).flatMap(this::executeInject);
    }

    // RET-06: `req` is the deserialized request body (null when absent); the null check IS the
    // parse-don't-validate entry validation.
    @SuppressWarnings("JBCT-RET-06")
    private Promise<ScheduledTaskInjectRequest> validateInjectRequest(ScheduledTaskInjectRequest req) {
        if (req == null) {
            return InjectError.MISSING_BODY.promise();
        }

        if (req.section() == null || req.section().isBlank()) {
            return InjectError.MISSING_SECTION.promise();
        }

        if (req.artifact() == null || req.artifact().isBlank()) {
            return InjectError.MISSING_ARTIFACT.promise();
        }

        if (req.method() == null || req.method().isBlank()) {
            return InjectError.MISSING_METHOD.promise();
        }

        return Promise.success(req);
    }

    private Promise<ScheduledTaskInjectResponse> executeInject(ScheduledTaskInjectRequest req) {
        return findTask(req.section(), req.artifact(), req.method()).flatMap(task -> ensureLocalThenInvoke(task, req));
    }

    /// Gate on `SliceInvoker.hasLocalSlice` before invoking — the inject path encodes the
    /// `Unit.unit()` request via the slice's local bridge, which only exists on nodes that
    /// host the slice. When invoked through the cluster's load balancer the request can
    /// land on a non-hosting node; rather than 500 with the historic
    /// "Option is empty" NPE from `findSenderBridge`, surface a structured error pointing
    /// the caller at one of the nodes that owns the task so they can retry directly.
    private Promise<ScheduledTaskInjectResponse> ensureLocalThenInvoke(ScheduledTask task,
                                                                       ScheduledTaskInjectRequest req) {
        if (invoker.hasLocalSlice(task.artifact())) {
            return invokeAndAdvanceState(task, req);
        }

        var node = nodeSupplier.get();

        return new InjectError.SliceNotLocal(selectHostingHint(node.kvStore(),
                                                               node.self(),
                                                               task.artifact(),
                                                               task.registeredBy()),
                                             task.artifact().asString()).promise();
    }

    /// Pick a node ID to point the rejected caller at. Reads CURRENT slice placement from the
    /// authoritative KV-Store (`NodeArtifactKey(nodeId, artifact) → NodeArtifactValue(state)`),
    /// the same source of truth as `SliceOwnershipQuery`. Prefers a node whose entry for THIS
    /// artifact is `ACTIVE` (a usable local bridge exists there), excluding the receiving node.
    /// Falls back to `registeredBy` (registration history) only when no ACTIVE placement is found —
    /// the hint is never empty. The earlier `registeredBy`-only hint was circular: it could name
    /// the very node that just refused the request once the slice had moved off the registrant.
    static String selectHostingHint(KVStore<AetherKey, AetherValue> kvStore,
                                    NodeId self,
                                    Artifact artifact,
                                    NodeId registeredBy) {
        var active = new AtomicReference<NodeId>();

        kvStore.forEach(NodeArtifactKey.class,
                        NodeArtifactValue.class,
                        (key, value) -> recordActiveHost(active, self, artifact, key, value));

        return Option.option(active.get())
                     .or(registeredBy)
                     .id();
    }

    private static void recordActiveHost(AtomicReference<NodeId> active,
                                         NodeId self,
                                         Artifact artifact,
                                         NodeArtifactKey key,
                                         NodeArtifactValue value) {
        if (!key.nodeId().equals(self) && key.artifact().equals(artifact) && value.state() == SliceState.ACTIVE) {
            active.compareAndSet(null, key.nodeId());
        }
    }

    private Promise<ScheduledTaskInjectResponse> invokeAndAdvanceState(ScheduledTask task,
                                                                       ScheduledTaskInjectRequest req) {
        var stateKey = injectStateKeyFor(task);
        var priorState = stateRegistry.stateFor(stateKey);
        var previousExecutionMs = priorState.map(ScheduledTaskStateValue::lastExecutionAt).or(0L);

        return invoker.invoke(task.artifact(),
                              task.methodName(),
                              Unit.unit())
                      .onFailure(cause -> writeFailureBestEffort(stateKey, priorState, cause))
                      .flatMap(_ -> writeSuccessAndRespond(stateKey, priorState, req, previousExecutionMs));
    }

    /// Mirrors `ScheduledTaskManager.TaskOps#stateKeyFor` so `/inject` writes land on the same
    /// row the automatic path reads and the Management API aggregates: an ALL-mode task is
    /// scoped by this node, same as an automatic fire, so the write is visible on the per-node
    /// aggregation (#841) instead of landing on the unscoped key every ALL-mode read surface
    /// now filters out. SINGLE-mode keeps the pre-#841 unscoped key unchanged — it still races
    /// the leader's automatic fire on that same key (no `tryClaim`/`release` guard here; `/trigger`
    /// writes no state entry at all, so it is not a racer on this key), a documented TOCTOU caveat,
    /// not fixed by this change.
    private ScheduledTaskStateKey injectStateKeyFor(ScheduledTask task) {
        return task.executionMode() == ExecutionMode.ALL
               ? ScheduledTaskStateKey.scheduledTaskStateKey(task.configSection(),
                                                             task.artifact(),
                                                             task.methodName(),
                                                             nodeSupplier.get().self())
               : ScheduledTaskStateKey.scheduledTaskStateKey(task.configSection(), task.artifact(), task.methodName());
    }

    private Promise<ScheduledTaskInjectResponse> writeSuccessAndRespond(ScheduledTaskStateKey stateKey,
                                                                        Option<ScheduledTaskStateValue> priorState,
                                                                        ScheduledTaskInjectRequest req,
                                                                        long previousExecutionMs) {
        var priorTotal = priorState.map(ScheduledTaskStateValue::totalExecutions).or(0);
        var priorSkipped = priorState.map(ScheduledTaskStateValue::skippedOverlaps).or(0);
        var value = ScheduledTaskStateValue.successState(0, priorTotal + 1, priorSkipped);
        KVCommand<AetherKey> command = new KVCommand.Put<>(stateKey, value);

        return nodeSupplier.get()
                           .apply(List.of(command))
                           .map(_ -> new ScheduledTaskInjectResponse(req.section(),
                                                                     req.artifact(),
                                                                     req.method(),
                                                                     previousExecutionMs,
                                                                     value.lastExecutionAt()));
    }

    private void writeFailureBestEffort(ScheduledTaskStateKey stateKey,
                                        Option<ScheduledTaskStateValue> priorState,
                                        Cause cause) {
        var priorTotal = priorState.map(ScheduledTaskStateValue::totalExecutions).or(0);
        var priorFailures = priorState.map(ScheduledTaskStateValue::consecutiveFailures).or(0);
        var priorSkipped = priorState.map(ScheduledTaskStateValue::skippedOverlaps).or(0);
        var value = ScheduledTaskStateValue.failureState(0, priorFailures + 1, priorTotal, priorSkipped, cause.message());
        KVCommand<AetherKey> command = new KVCommand.Put<>(stateKey, value);

        nodeSupplier.get().apply(List.of(command));
    }

    /// Stable total-order over scheduled tasks so positional access into the response
    /// (`tasks.0`) is deterministic. `registry.allTasks()` is a `ConcurrentHashMap`-backed
    /// `List.copyOf(values())` whose iteration order is unspecified and shifts across calls;
    /// two tasks differing only by artifact (e.g. same `scheduling.heartbeat/heartbeat`
    /// section+method) flipped between a pause-write and a readback, producing a false
    /// `paused=false`. Ordering by `(configSection, artifact, method)` removes that flake.
    private static final Comparator<ScheduledTask> TASK_ORDER = Comparator.comparing(ScheduledTask::configSection)
                                                                          .thenComparing(task -> task.artifact()
                                                                                                     .asString())
                                                                          .thenComparing(task -> task.methodName()
                                                                                                     .name());

    private ScheduledTasksResponse buildTasksResponse() {
        var tasks = registry.allTasks().stream().sorted(TASK_ORDER).map(this::toSummary).toList();

        return new ScheduledTasksResponse(tasks, manager.activeTimerCount());
    }

    private Promise<FilteredTasksResponse> buildFilteredResponse(String configSection) {
        var tasks = registry.allTasks()
                            .stream()
                            .filter(task -> task.configSection()
                                                .equals(configSection))
                            .sorted(TASK_ORDER)
                            .map(this::toSummary)
                            .toList();

        return Promise.success(new FilteredTasksResponse(tasks, configSection));
    }

    private Promise<TaskActionResult> setPaused(String configSection,
                                                String artifactStr,
                                                String methodStr,
                                                boolean paused) {
        return findTask(configSection, artifactStr, methodStr).flatMap(task -> submitPausedUpdate(task,
                                                                                                  configSection,
                                                                                                  artifactStr,
                                                                                                  methodStr,
                                                                                                  paused));
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

        return nodeSupplier.get()
                           .apply(List.of(command))
                           .map(_ -> new TaskActionResult(true, configSection, artifactStr, methodStr, action));
    }

    private Promise<TaskActionResult> triggerTask(String configSection, String artifactStr, String methodStr) {
        return findTask(configSection, artifactStr, methodStr).flatMap(task -> invokeAndBuildResult(task,
                                                                                                    configSection,
                                                                                                    artifactStr,
                                                                                                    methodStr));
    }

    private Promise<TaskActionResult> invokeAndBuildResult(ScheduledTask task,
                                                           String configSection,
                                                           String artifactStr,
                                                           String methodStr) {
        var key = ScheduledTaskKey.scheduledTaskKey(task.configSection(), task.artifact(), task.methodName());

        if (!manager.tryClaim(key)) {
            return new TriggerConflict(configSection, artifactStr, methodStr).promise();
        }

        return invoker.invoke(task.artifact(),
                              task.methodName(),
                              Unit.unit())
                      .onResultRun(() -> manager.release(key))
                      .map(_ -> new TaskActionResult(true, configSection, artifactStr, methodStr, "triggered"));
    }

    private Promise<ScheduledTask> findTask(String configSection, String artifactStr, String methodStr) {
        return registry.allTasks()
                       .stream()
                       .filter(t -> matchesTask(t, configSection, artifactStr, methodStr))
                       .findFirst()
                       .map(Promise::success)
                       .orElse(TASK_NOT_FOUND.promise());
    }

    private static boolean matchesTask(ScheduledTask task, String configSection, String artifactStr, String methodStr) {
        return task.configSection()
                   .equals(configSection)
               && task.artifact()
                      .asString()
                      .equals(artifactStr)
               && task.methodName()
                      .name()
                      .equals(methodStr);
    }

    /// #680/#841: an ALL-mode task's automatic fires write per-node `ScheduledTaskStateKey` rows
    /// (one per node running its own independent timer), so `stateRegistry` — a single-row,
    /// per-JVM mirror synced only for the unscoped key — reports zero or stale totals for such a
    /// task. Reading it unconditionally here was the #680 defect surviving on this endpoint after
    /// the per-node key fix (which only corrected the WRITE side, in `ScheduledTaskManager`). ALL-
    /// mode tasks aggregate across the cluster's per-node rows instead, via
    /// [#aggregateAllModeState] (same `KVStore.forEach` idiom as [#buildExecutionsByNode]);
    /// SINGLE-mode tasks keep the cheap `stateRegistry` read (one writer, already correct).
    private TaskSummary toSummary(ScheduledTask task) {
        if (task.executionMode() == ExecutionMode.ALL) {
            return aggregateAllModeState(task).fold(() -> buildSummary(task, 0, 0, 0, 0),
                                                    state -> buildSummary(task,
                                                                          state.lastExecutionAt(),
                                                                          state.nextFireAt(),
                                                                          state.consecutiveFailures(),
                                                                          state.totalExecutions()));
        }

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

    private Promise<TaskStateResponse> getTaskState(String configSection, String artifactStr, String methodStr) {
        return findTask(configSection, artifactStr, methodStr).flatMap(task -> buildStateResponse(task,
                                                                                                  configSection,
                                                                                                  artifactStr,
                                                                                                  methodStr));
    }

    /// Same #680/#841 gap as [#toSummary], on the single-task `/state` surface: an ALL-mode task
    /// aggregates its per-node rows via [#aggregateAllModeState] instead of the single-row
    /// `stateRegistry` mirror. `lastFailureMessage`/`updatedAt` are not naturally summable across
    /// nodes — [#combineNodeStates] takes them from whichever per-node row is freshest by
    /// `updatedAt`, the same tie-break the combine uses for every non-additive field.
    private Promise<TaskStateResponse> buildStateResponse(ScheduledTask task,
                                                          String configSection,
                                                          String artifactStr,
                                                          String methodStr) {
        if (task.executionMode() == ExecutionMode.ALL) {
            return Promise.success(aggregateAllModeState(task).fold(() -> emptyStateResponse(configSection,
                                                                                             artifactStr,
                                                                                             methodStr),
                                                                    state -> toStateResponse(configSection,
                                                                                             artifactStr,
                                                                                             methodStr,
                                                                                             state)));
        }

        var stateKey = ScheduledTaskStateKey.scheduledTaskStateKey(task.configSection(),
                                                                   task.artifact(),
                                                                   task.methodName());

        return stateRegistry.stateFor(stateKey)
                            .fold(() -> Promise.success(emptyStateResponse(configSection, artifactStr, methodStr)),
                                  state -> Promise.success(toStateResponse(configSection, artifactStr, methodStr, state)));
    }

    private static TaskStateResponse toStateResponse(String configSection,
                                                     String artifactStr,
                                                     String methodStr,
                                                     ScheduledTaskStateValue state) {
        return new TaskStateResponse(configSection,
                                     artifactStr,
                                     methodStr,
                                     state.lastExecutionAt(),
                                     state.nextFireAt(),
                                     state.consecutiveFailures(),
                                     state.totalExecutions(),
                                     state.lastFailureMessage(),
                                     state.updatedAt(),
                                     state.skippedOverlaps());
    }

    private static TaskStateResponse emptyStateResponse(String configSection, String artifactStr, String methodStr) {
        return new TaskStateResponse(configSection, artifactStr, methodStr, 0, 0, 0, 0, "", 0, 0);
    }

    /// Cluster-wide combine of an ALL-mode task's per-node [ScheduledTaskStateValue] rows, feeding
    /// [#toSummary] and [#buildStateResponse] the same 7-field shape a single global row used to
    /// carry — so both downstream call sites are unchanged past this seam. Not every field is
    /// summable: `totalExecutions`/`skippedOverlaps` are (every node's count is real activity, #680
    /// spec's explicit "totals summed"); `consecutiveFailures` takes the MAX across nodes (surfaces
    /// the worst-case node rather than hiding it behind a healthier one); `lastExecutionAt` takes
    /// the MAX (freshest activity, cluster-wide); `nextFireAt` takes the MIN (the soonest upcoming
    /// fire across the independent per-node timers is the next cluster-wide activity an operator
    /// would wait for); `lastFailureMessage`/`updatedAt` are taken together from whichever row has
    /// the highest `updatedAt`, so the message always describes the state it is paired with rather
    /// than an arbitrary older row's text next to a newer timestamp. [design intent — unverified
    /// beyond the field-level reasoning above; no review ruling pins these five choices specifically,
    /// only the two additive fields].
    private static ScheduledTaskStateValue combineNodeStates(ScheduledTaskStateValue a, ScheduledTaskStateValue b) {
        var latest = a.updatedAt() >= b.updatedAt()
                     ? a
                     : b;

        return new ScheduledTaskStateValue(Math.max(a.lastExecutionAt(), b.lastExecutionAt()),
                                           Math.min(a.nextFireAt(), b.nextFireAt()),
                                           Math.max(a.consecutiveFailures(), b.consecutiveFailures()),
                                           a.totalExecutions() + b.totalExecutions(),
                                           latest.lastFailureMessage(),
                                           latest.updatedAt(),
                                           a.skippedOverlaps() + b.skippedOverlaps());
    }

    /// Scans the live KV store for every per-node row belonging to `task` (same
    /// `matchesTaskIdentity` filter [#buildExecutionsByNode] uses) and folds them into one
    /// [ScheduledTaskStateValue] via [#combineNodeStates]. `Option.none()` means no per-node row
    /// exists yet for this ALL-mode task — no execution since the #841 upgrade, or none ever — in
    /// which case the caller reports zero/empty, exactly as a brand-new task would. The pre-#841
    /// global key is never read here: it is excluded by [#matchesTaskIdentity]'s own scan surface
    /// (it walks `ScheduledTaskStateKey` rows and [#collectNodeState] additionally requires
    /// `key.node().isPresent()`), so a stale global row can never be misread as a node's row.
    private Option<ScheduledTaskStateValue> aggregateAllModeState(ScheduledTask task) {
        var perNodeStates = new ArrayList<ScheduledTaskStateValue>();

        nodeSupplier.get()
                    .kvStore()
                    .forEach(ScheduledTaskStateKey.class,
                             ScheduledTaskStateValue.class,
                             (key, value) -> collectNodeState(perNodeStates, task, key, value));

        return perNodeStates.stream()
                            .reduce(ScheduledTaskRoutes::combineNodeStates)
                            .map(Option::some)
                            .orElseGet(Option::none);
    }

    private static void collectNodeState(List<ScheduledTaskStateValue> states,
                                         ScheduledTask task,
                                         ScheduledTaskStateKey key,
                                         ScheduledTaskStateValue value) {
        if (matchesTaskIdentity(key, task) && key.node().isPresent()) {
            states.add(value);
        }
    }

    /// #841: surfaces per-node execution attribution for an ALL-mode scheduled task, one row per
    /// node that has written a per-node `ScheduledTaskStateKey` for this task (see that key's
    /// javadoc for the wire-format rationale). Aggregates by scanning the live KV-store — the same
    /// `KVStore.forEach` idiom [#selectHostingHint] uses — rather than the single-row
    /// `ScheduledTaskStateRegistry` mirror, because the registry is keyed 1:1 and cannot represent
    /// several nodes' rows for the same task. A SINGLE-mode task, or an ALL-mode task with no
    /// per-node row yet (pre-#841 global-shaped state, or simply no execution since upgrade),
    /// reports an empty list here — its counters are visible instead via `/state`'s global key.
    private Promise<ScheduledTaskExecutionsByNodeResponse> getExecutionsByNode(String configSection,
                                                                               String artifactStr,
                                                                               String methodStr) {
        return findTask(configSection, artifactStr, methodStr).map(task -> buildExecutionsByNode(task,
                                                                                                 configSection,
                                                                                                 artifactStr,
                                                                                                 methodStr));
    }

    private ScheduledTaskExecutionsByNodeResponse buildExecutionsByNode(ScheduledTask task,
                                                                        String configSection,
                                                                        String artifactStr,
                                                                        String methodStr) {
        var executions = new ArrayList<ScheduledTaskNodeExecution>();

        nodeSupplier.get()
                    .kvStore()
                    .forEach(ScheduledTaskStateKey.class,
                             ScheduledTaskStateValue.class,
                             (key, value) -> collectNodeExecution(executions, task, key, value));
        executions.sort(Comparator.comparing(ScheduledTaskNodeExecution::nodeId));

        return new ScheduledTaskExecutionsByNodeResponse(configSection, artifactStr, methodStr, List.copyOf(executions));
    }

    /// Two filters, both required: `matchesTaskIdentity` excludes rows belonging to a DIFFERENT
    /// task the scan also walks past (the KV-store holds every scheduled task's state, not just
    /// this one); `key.node()` being present excludes a same-task GLOBAL row (pre-#841 shape, or a
    /// SINGLE-mode task's row) — that shape can never be node-scoped (see
    /// `ScheduledTaskStateKey`'s javadoc), so it is filtered here rather than misread as a node's
    /// execution count.
    private static void collectNodeExecution(List<ScheduledTaskNodeExecution> executions,
                                             ScheduledTask task,
                                             ScheduledTaskStateKey key,
                                             ScheduledTaskStateValue value) {
        if (!matchesTaskIdentity(key, task)) {
            return;
        }

        key.node()
           .onPresent(nodeId -> executions.add(new ScheduledTaskNodeExecution(nodeId.id(),
                                                                              value.totalExecutions(),
                                                                              value.lastExecutionAt())));
    }

    private static boolean matchesTaskIdentity(ScheduledTaskStateKey key, ScheduledTask task) {
        return key.configSection()
                  .equals(task.configSection())
               && key.artifact()
                     .equals(task.artifact())
               && key.methodName()
                     .equals(task.methodName());
    }

    private sealed interface InjectError extends Cause permits InjectError.General, InjectError.SliceNotLocal {
        InjectError DEV_MODE_DISABLED = General.DEV_MODE_DISABLED;
        InjectError MISSING_BODY = General.MISSING_BODY;
        InjectError MISSING_SECTION = General.MISSING_SECTION;
        InjectError MISSING_ARTIFACT = General.MISSING_ARTIFACT;
        InjectError MISSING_METHOD = General.MISSING_METHOD;

        enum General implements InjectError {
            DEV_MODE_DISABLED("scheduled-tasks inject requires AETHER_INSECURE_DEV_MODE=true"),
            MISSING_BODY("Request body is required"),
            MISSING_SECTION("section field is required"),
            MISSING_ARTIFACT("artifact field is required"),
            MISSING_METHOD("method field is required");
            private final String message;
            General(String message) {
                this.message = message;
            }
            @Override
            public String message() {
                return message;
            }
        }

        /// Surfaced when the inject route lands on a node that does NOT host the target
        /// slice's bridge. Callers (test harness, operator CLI) should retry the POST
        /// against the management endpoint of the node returned in `hostingNodeId`. This
        /// replaces the historic NPE that produced an opaque `500 {"error":"Internal Server
        /// Error"}` from `SliceInvokerImpl.findSenderBridge.unwrap()`.
        record SliceNotLocal(String hostingNodeId, String artifact) implements InjectError {
            @Override
            public String message() {
                return "scheduled-tasks inject for artifact=" + artifact
                     + " must run on a node hosting the slice; retry against node " + hostingNodeId;
            }
        }
    }

    /// 409 — `triggerTask` (POST .../trigger) found the task's `ScheduledTaskManager.tryClaim`
    /// already held: an automatic fire (fixed-rate tick or cron) is currently in flight for this
    /// task, or another manual trigger call raced ahead of this one and claimed first. Refusing
    /// the manual fire and reporting it honestly is preferable to letting it run concurrently with
    /// the in-progress execution and silently double-counting `totalExecutions` (#273 review, item 1).
    private record TriggerConflict(String configSection, String artifact, String method) implements Cause, HttpStatusAware {
        @Override
        public String message() {
            return "Scheduled task " + configSection
                 + "/" + artifact
                 + "." + method
                 + " has a run already in flight — refusing the manual trigger to avoid a concurrent"
                 + " double execution; retry once the in-progress run completes";
        }

        @Override
        public HttpStatus httpStatus() {
            return HttpStatus.CONFLICT;
        }
    }
}

package org.pragmatica.aether.forge.load;

import org.pragmatica.aether.forge.ForgeMetrics;
import org.pragmatica.aether.forge.load.pattern.TemplateProcessor;
import org.pragmatica.aether.forge.simulator.EntryPointMetrics;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/**
 * Config-driven load runner that generates HTTP load based on TOML configuration.
 * <p>
 * Supports:
 * <ul>
 *   <li>Multiple concurrent targets with independent rates</li>
 *   <li>Pattern-based data generation (uuid, random, range, choice, seq)</li>
 *   <li>Optional duration limits per target</li>
 *   <li>Pause/resume functionality</li>
 *   <li>Per-target metrics collection</li>
 * </ul>
 */
public final class ConfigurableLoadRunner {
    private static final Logger log = LoggerFactory.getLogger(ConfigurableLoadRunner.class);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Cause NO_TARGETS_CONFIGURED = LoadConfigError.ParseFailed.parseFailed("No targets configured");

    /**
     * Current runner state.
     */
    public enum State {
        IDLE,
        RUNNING,
        PAUSED,
        STOPPING
    }

    private final int port;
    private final ForgeMetrics metrics;
    private final EntryPointMetrics entryPointMetrics;
    private final HttpClient httpClient;

    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final AtomicReference<LoadConfig> currentConfig = new AtomicReference<>(LoadConfig.empty());
    private final AtomicReference<Double> rateMultiplier = new AtomicReference<>(1.0);

    private final Map<String, TargetRunner> activeRunners = new ConcurrentHashMap<>();
    private final List<Thread> runnerThreads = new CopyOnWriteArrayList<>();

    private final AtomicReference<ScheduledExecutorService> scheduler = new AtomicReference<>();

    private ConfigurableLoadRunner(int port, ForgeMetrics metrics, EntryPointMetrics entryPointMetrics) {
        this.port = port;
        this.metrics = metrics;
        this.entryPointMetrics = entryPointMetrics;
        this.httpClient = HttpClient.newBuilder()
                                    .connectTimeout(REQUEST_TIMEOUT)
                                    .executor(Executors.newVirtualThreadPerTaskExecutor())
                                    .build();
    }

    public static ConfigurableLoadRunner configurableLoadRunner(int port,
                                                                ForgeMetrics metrics,
                                                                EntryPointMetrics entryPointMetrics) {
        return new ConfigurableLoadRunner(port, metrics, entryPointMetrics);
    }

    /**
     * Load configuration from TOML string content.
     */
    public Result<LoadConfig> loadConfigFromString(String tomlContent) {
        return LoadConfigLoader.loadFromString(tomlContent)
                               .onSuccess(this::setConfig);
    }

    /**
     * Set the current configuration.
     */
    public void setConfig(LoadConfig config) {
        currentConfig.set(config);
        log.info("Loaded configuration with {} targets, total {} req/s",
                 config.targets()
                       .size(),
                 config.totalRequestsPerSecond());
    }

    /**
     * Get the current configuration.
     */
    public LoadConfig config() {
        return currentConfig.get();
    }

    /**
     * Start load generation using the current configuration.
     */
    public Result<State> start() {
        if (currentConfig.get()
                         .targets()
                         .isEmpty()) {
            return NO_TARGETS_CONFIGURED.result();
        }
        if (!state.compareAndSet(State.IDLE, State.RUNNING) &&
        !state.compareAndSet(State.PAUSED, State.RUNNING)) {
            log.warn("Cannot start - current state: {}", state.get());
            return Result.success(state.get());
        }
        log.info("Starting load generation with {} targets",
                 currentConfig.get()
                              .targets()
                              .size());
        var newScheduler = Executors.newScheduledThreadPool(2);
        scheduler.set(newScheduler);
        // Create and start runners for each target
        var runnerResults = currentConfig.get()
                                         .targets()
                                         .stream()
                                         .map(target -> createRunner(target).map(runner -> Map.entry(target, runner)))
                                         .toList();
        return Result.allOf(runnerResults)
                     .onFailure(cause -> {
                                    log.error("Failed to create runners: {}",
                                              cause.message());
                                    cleanupScheduler();
                                    state.set(State.IDLE);
                                })
                     .onSuccess(entries -> {
                                    entries.forEach(entry -> startRunner(entry.getValue(),
                                                                         entry.getKey()));
                                    // Rate sync scheduler - delayed start to ensure runners are initialized
        var sched = scheduler.get();
                                    sched.schedule(() -> sched.scheduleAtFixedRate(this::syncMetrics,
                                                                                   0,
                                                                                   100,
                                                                                   TimeUnit.MILLISECONDS),
                                                   200,
                                                   TimeUnit.MILLISECONDS);
                                })
                     .map(_ -> State.RUNNING);
    }

    private void cleanupScheduler() {
        var sched = scheduler.getAndSet(null);
        if (sched != null) {
            sched.shutdownNow();
        }
    }

    /**
     * Stop all load generation.
     */
    public void stop() {
        if (!state.compareAndSet(State.RUNNING, State.STOPPING) &&
        !state.compareAndSet(State.PAUSED, State.STOPPING)) {
            return;
        }
        log.info("Stopping load generation");
        // Stop all runners
        activeRunners.values()
                     .forEach(TargetRunner::stop);
        // Wait for threads
        for (var thread : runnerThreads) {
            try{
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread()
                      .interrupt();
            }
        }
        // Cleanup
        activeRunners.clear();
        runnerThreads.clear();
        cleanupScheduler();
        state.set(State.IDLE);
    }

    /**
     * Pause load generation (can be resumed).
     */
    public void pause() {
        if (state.compareAndSet(State.RUNNING, State.PAUSED)) {
            log.info("Pausing load generation");
            activeRunners.values()
                         .forEach(TargetRunner::pause);
        }
    }

    /**
     * Resume paused load generation.
     */
    public void resume() {
        if (state.compareAndSet(State.PAUSED, State.RUNNING)) {
            log.info("Resuming load generation");
            activeRunners.values()
                         .forEach(TargetRunner::resume);
        }
    }

    /**
     * Set the total target rate by calculating and applying a multiplier.
     * If running, stops and restarts with new rates.
     */
    public void setTotalRate(int targetTotalRate) {
        var config = currentConfig.get();
        var currentTotal = config.totalRequestsPerSecond();
        if (currentTotal <= 0) {
            log.warn("Cannot set rate - no targets configured");
            return;
        }
        var multiplier = (double) targetTotalRate / currentTotal;
        rateMultiplier.set(multiplier);
        log.info("Set rate multiplier to {} (target: {} req/s)", multiplier, targetTotalRate);
        // If running, restart with new rates
        if (state.get() == State.RUNNING || state.get() == State.PAUSED) {
            stop();
            // Reload config with multiplied rates
            var newConfig = config.withMultiplier(multiplier);
            setConfig(newConfig);
            start();
        } else {
            // Just update config for next start
            var newConfig = config.withMultiplier(multiplier);
            setConfig(newConfig);
        }
    }

    /**
     * Get current rate multiplier.
     */
    public double rateMultiplier() {
        return rateMultiplier.get();
    }

    /**
     * Get current state.
     */
    public State state() {
        return state.get();
    }

    /**
     * Check if running.
     */
    public boolean isRunning() {
        return state.get() == State.RUNNING;
    }

    /**
     * Get metrics for a specific target.
     */
    public Option<TargetMetrics> targetMetrics(String targetName) {
        var runner = activeRunners.get(targetName);
        return runner != null
               ? some(runner.getTargetMetrics())
               : none();
    }

    /**
     * Get metrics for all targets.
     */
    public Map<String, TargetMetrics> allTargetMetrics() {
        var result = new HashMap<String, TargetMetrics>();
        activeRunners.forEach((name, runner) -> result.put(name, runner.getTargetMetrics()));
        return result;
    }

    /**
     * Get list of active target names.
     */
    public List<String> activeTargets() {
        return List.copyOf(activeRunners.keySet());
    }

    private Result<TargetRunner> createRunner(LoadTarget target) {
        return compilePathProcessors(target.pathVars())
        .flatMap(pathProcessors -> compilePathTemplateProcessor(target.httpPath())
        .flatMap(pathTemplateProcessor -> compileBodyProcessor(target.body())
        .map(bodyProcessor -> TargetRunner.targetRunner(target,
                                                        pathProcessors,
                                                        pathTemplateProcessor,
                                                        bodyProcessor,
                                                        port,
                                                        httpClient,
                                                        metrics,
                                                        entryPointMetrics,
                                                        () -> state.get()))));
    }

    private Result<Option<TemplateProcessor>> compilePathTemplateProcessor(String httpPath) {
        return httpPath.contains("${")
               ? TemplateProcessor.compile(httpPath)
                                  .map(Option::some)
               : Result.success(Option.none());
    }

    private Result<Map<String, TemplateProcessor>> compilePathProcessors(Map<String, String> pathVars) {
        var entries = pathVars.entrySet()
                              .stream()
                              .map(entry -> TemplateProcessor.compile(entry.getValue())
                                                             .map(proc -> Map.entry(entry.getKey(),
                                                                                    proc)))
                              .toList();
        return Result.allOf(entries)
                     .map(list -> list.stream()
                                      .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
    }

    private Result<Option<TemplateProcessor>> compileBodyProcessor(Option<String> body) {
        return body.map(TemplateProcessor::compile)
                   .map(result -> result.map(Option::some))
                   .or(Result.success(none()));
    }

    private void startRunner(TargetRunner runner, LoadTarget target) {
        activeRunners.put(runner.name(), runner);
        var thread = Thread.ofVirtual()
                           .name("load-" + runner.name())
                           .start(runner::run);
        runnerThreads.add(thread);
        entryPointMetrics.setRate(runner.name(),
                                  target.rate()
                                        .requestsPerSecond());
    }

    private void syncMetrics() {
        activeRunners.forEach(this::syncRunnerMetrics);
    }

    private void syncRunnerMetrics(String name, TargetRunner runner) {
        entryPointMetrics.setRate(name,
                                  runner.getTargetMetrics()
                                        .targetRate());
    }

    /**
     * Metrics for a single target.
     */
    public record TargetMetrics(String name,
                                int targetRate,
                                int actualRate,
                                long totalRequests,
                                long successCount,
                                long failureCount,
                                double avgLatencyMs,
                                Option<Duration> remainingDuration) {
        public double successRate() {
            return totalRequests > 0
                   ? (double) successCount / totalRequests * 100
                   : 0;
        }
    }

    /**
     * Runner for a single target.
     */
    private record TargetRunner(LoadTarget target,
                                String name,
                                Map<String, TemplateProcessor> pathProcessors,
                                Option<TemplateProcessor> pathTemplateProcessor,
                                Option<TemplateProcessor> bodyProcessor,
                                int port,
                                HttpClient httpClient,
                                ForgeMetrics metrics,
                                EntryPointMetrics entryPointMetrics,
                                java.util.function.Supplier<State> stateSupplier,
                                AtomicBoolean running,
                                AtomicBoolean paused,
                                AtomicLong totalRequests,
                                AtomicLong successCount,
                                AtomicLong failureCount,
                                AtomicLong totalLatencyNanos,
                                AtomicReference<Instant> startTimeRef,
                                AtomicInteger actualRate) {
        private static final java.util.regex.Pattern HTTP_METHOD_PREFIX = java.util.regex.Pattern.compile("^(GET|POST|PUT|DELETE|PATCH)\\s+");

        static TargetRunner targetRunner(LoadTarget target,
                                         Map<String, TemplateProcessor> pathProcessors,
                                         Option<TemplateProcessor> pathTemplateProcessor,
                                         Option<TemplateProcessor> bodyProcessor,
                                         int port,
                                         HttpClient httpClient,
                                         ForgeMetrics metrics,
                                         EntryPointMetrics entryPointMetrics,
                                         java.util.function.Supplier<State> stateSupplier) {
            return new TargetRunner(target,
                                    target.name()
                                          .or(deriveNameFromTarget(target.target())),
                                    pathProcessors,
                                    pathTemplateProcessor,
                                    bodyProcessor,
                                    port,
                                    httpClient,
                                    metrics,
                                    entryPointMetrics,
                                    stateSupplier,
                                    new AtomicBoolean(true),
                                    new AtomicBoolean(false),
                                    new AtomicLong(0),
                                    new AtomicLong(0),
                                    new AtomicLong(0),
                                    new AtomicLong(0),
                                    new AtomicReference<>(),
                                    new AtomicInteger(0));
        }

        Instant startTime() {
            return startTimeRef.get();
        }

        void setStartTime(Instant time) {
            startTimeRef.set(time);
        }

        void stop() {
            running.set(false);
        }

        void pause() {
            paused.set(true);
        }

        void resume() {
            paused.set(false);
        }

        void run() {
            setStartTime(Instant.now());
            var rps = target.rate()
                            .requestsPerSecond();
            var intervalMicros = rps > 0
                                 ? 1_000_000 / rps
                                 : 1_000_000;
            var duration = target.duration();
            log.info("Starting target '{}' at {} req/s{}",
                     name,
                     rps,
                     duration.map(d -> " for " + d)
                             .or(""));
            while (running.get()) {
                try{
                    if (!waitIfPaused()) break;
                    if (checkDurationLimit(duration)) break;
                    executeRequest(intervalMicros);
                } catch (InterruptedException e) {
                    Thread.currentThread()
                          .interrupt();
                    break;
                } catch (Exception e) {
                    log.debug("Error in target '{}': {}", name, e.getMessage());
                }
            }
            log.info("Target '{}' stopped. Requests: {}, Success: {}, Failed: {}",
                     name,
                     totalRequests.get(),
                     successCount.get(),
                     failureCount.get());
        }

        private boolean waitIfPaused() throws InterruptedException {
            while (paused.get() && running.get()) {
                Thread.sleep(100);
            }
            return running.get();
        }

        private boolean checkDurationLimit(Option<Duration> duration) {
            if (duration.isPresent() && !duration.unwrap()
                                                 .isZero()) {
                var elapsed = Duration.between(startTime(), Instant.now());
                if (elapsed.compareTo(duration.unwrap()) >= 0) {
                    log.info("Target '{}' completed (duration limit reached)", name);
                    return true;
                }
            }
            return false;
        }

        private void executeRequest(long intervalMicros) throws InterruptedException {
            var requestStart = System.nanoTime();
            sendRequest();
            var requests = totalRequests.incrementAndGet();
            updateActualRate(requests);
            sleepForRemainingInterval(requestStart, intervalMicros);
        }

        private void updateActualRate(long requests) {
            var elapsedMs = Duration.between(startTime(),
                                             Instant.now())
                                    .toMillis();
            if (elapsedMs > 0) {
                actualRate.set((int)(requests * 1000 / elapsedMs));
            }
        }

        private void sleepForRemainingInterval(long requestStart, long intervalMicros) throws InterruptedException {
            var elapsedMicros = (System.nanoTime() - requestStart) / 1000;
            var sleepMicros = intervalMicros - elapsedMicros;
            if (sleepMicros > 0) {
                Thread.sleep(sleepMicros / 1000, (int)((sleepMicros % 1000) * 1000));
            }
        }

        private void sendRequest() {
            var requestStartTime = System.nanoTime();
            var path = buildPath();
            var body = buildBody();
            var method = target.isHttpPath()
                         ? target.httpMethod()
                                 .or(() -> inferMethod(path, body))
                         : "POST";
            var uri = URI.create("http://localhost:" + port + path);
            var requestBuilder = HttpRequest.newBuilder()
                                            .uri(uri)
                                            .timeout(REQUEST_TIMEOUT);
            switch (method) {
                case "POST" -> requestBuilder.header("Content-Type", "application/json")
                                             .POST(HttpRequest.BodyPublishers.ofString(body));
                case "PUT" -> requestBuilder.header("Content-Type", "application/json")
                                            .PUT(HttpRequest.BodyPublishers.ofString(body));
                case "DELETE" -> requestBuilder.DELETE();
                default -> requestBuilder.GET();
            }
            httpClient.sendAsync(requestBuilder.build(),
                                 HttpResponse.BodyHandlers.ofString())
                      .whenComplete((response, error) -> handleCompletion(response, error, requestStartTime));
        }

        private void handleCompletion(HttpResponse<String> response, Throwable error, long requestStartTime) {
            if (error != null) {
                recordFailure(System.nanoTime() - requestStartTime);
            } else {
                recordResponse(response, requestStartTime);
            }
        }

        private void recordResponse(HttpResponse<String> response, long requestStartTime) {
            var latencyNanos = System.nanoTime() - requestStartTime;
            totalLatencyNanos.addAndGet(latencyNanos);
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                recordSuccess(latencyNanos);
            } else {
                recordFailure(latencyNanos);
            }
        }

        private void recordSuccess(long latencyNanos) {
            successCount.incrementAndGet();
            metrics.recordSuccess(latencyNanos);
            entryPointMetrics.recordSuccess(name, latencyNanos);
        }

        private void recordFailure(long latencyNanos) {
            failureCount.incrementAndGet();
            metrics.recordFailure(latencyNanos);
            entryPointMetrics.recordFailure(name, latencyNanos);
        }

        private String buildPath() {
            String path;
            if (target.isHttpPath()) {
                // Extract path portion, stripping HTTP method prefix if present
                // Process inline ${pattern} templates first
                path = pathTemplateProcessor.map(TemplateProcessor::process)
                                            .or(target.httpPath());
            } else {
                // SliceName.method -> /api/invoke/SliceName/method
                var parts = target.target()
                                  .split("\\.", 2);
                if (parts.length == 2) {
                    path = "/api/invoke/" + parts[0] + "/" + parts[1];
                } else {
                    path = "/api/invoke/" + target.target();
                }
            }
            // Replace path variables ({keyName} placeholders from pathVars)
            for (var entry : pathProcessors.entrySet()) {
                path = path.replace("{" + entry.getKey() + "}",
                                    entry.getValue()
                                         .process());
            }
            return path;
        }

        private String buildBody() {
            return bodyProcessor.map(TemplateProcessor::process)
                                .or("");
        }

        private String inferMethod(String path, String body) {
            // Simple heuristic
            if (body != null && !body.isEmpty()) {
                return "POST";
            }
            if (path.contains("/delete") || path.endsWith("/cancel")) {
                return "DELETE";
            }
            return "GET";
        }

        TargetMetrics getTargetMetrics() {
            Option<Duration> remaining = none();
            if (target.duration()
                      .isPresent() && !target.duration()
                                             .unwrap()
                                             .isZero()) {
                var st = startTime();
                var elapsed = Duration.between(st != null
                                               ? st
                                               : Instant.now(), Instant.now());
                var rem = target.duration()
                                .unwrap()
                                .minus(elapsed);
                if (!rem.isNegative()) {
                    remaining = some(rem);
                }
            }
            var requests = totalRequests.get();
            return new TargetMetrics(name,
                                     target.rate()
                                           .requestsPerSecond(),
                                     actualRate.get(),
                                     requests,
                                     successCount.get(),
                                     failureCount.get(),
                                     requests > 0
                                     ? (double) totalLatencyNanos.get() / requests / 1_000_000
                                     : 0,
                                     remaining);
        }

        private static String deriveNameFromTarget(String target) {
            // Strip HTTP method prefix if present
            var path = HTTP_METHOD_PREFIX.matcher(target)
                                         .replaceFirst("");
            if (path.startsWith("/")) {
                // HTTP path: /api/orders/{id} -> orders
                var segments = path.split("/");
                for (int i = segments.length - 1; i >= 0; i--) {
                    var seg = segments[i];
                    if (!seg.isEmpty() && !seg.startsWith("{")) {
                        return seg;
                    }
                }
                return "target";
            } else {
                // SliceName.method -> method
                var dotIdx = path.lastIndexOf('.');
                return dotIdx >= 0
                       ? path.substring(dotIdx + 1)
                       : path;
            }
        }
    }
}

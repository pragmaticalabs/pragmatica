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

    private final Map<String, TargetRunner> activeRunners = new ConcurrentHashMap<>();
    private final List<Thread> runnerThreads = new CopyOnWriteArrayList<>();

    private ScheduledExecutorService scheduler;

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
        scheduler = Executors.newScheduledThreadPool(2);
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
        scheduler.schedule(() -> scheduler.scheduleAtFixedRate(this::syncMetrics, 0, 100, TimeUnit.MILLISECONDS),
                           200,
                           TimeUnit.MILLISECONDS);
                                })
                     .map(_ -> State.RUNNING);
    }

    private void cleanupScheduler() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
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
               ? some(runner.metrics())
               : none();
    }

    /**
     * Get metrics for all targets.
     */
    public Map<String, TargetMetrics> allTargetMetrics() {
        var result = new HashMap<String, TargetMetrics>();
        activeRunners.forEach((name, runner) -> result.put(name, runner.metrics()));
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
        .flatMap(pathProcessors -> compileBodyProcessor(target.body())
        .map(bodyProcessor -> new TargetRunner(target,
                                               pathProcessors,
                                               bodyProcessor,
                                               port,
                                               httpClient,
                                               metrics,
                                               entryPointMetrics,
                                               () -> state.get())));
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
                                  runner.metrics()
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
    private static final class TargetRunner {
        private final LoadTarget target;
        private final String name;
        private final Map<String, TemplateProcessor> pathProcessors;
        private final Option<TemplateProcessor> bodyProcessor;
        private final int port;
        private final HttpClient httpClient;
        private final ForgeMetrics metrics;
        private final EntryPointMetrics entryPointMetrics;
        private final java.util.function.Supplier<State> stateSupplier;

        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicBoolean paused = new AtomicBoolean(false);

        // Metrics
        private final AtomicLong totalRequests = new AtomicLong(0);
        private final AtomicLong successCount = new AtomicLong(0);
        private final AtomicLong failureCount = new AtomicLong(0);
        private final AtomicLong totalLatencyNanos = new AtomicLong(0);
        private volatile Instant startTime;
        private final AtomicInteger actualRate = new AtomicInteger(0);

        TargetRunner(LoadTarget target,
                     Map<String, TemplateProcessor> pathProcessors,
                     Option<TemplateProcessor> bodyProcessor,
                     int port,
                     HttpClient httpClient,
                     ForgeMetrics metrics,
                     EntryPointMetrics entryPointMetrics,
                     java.util.function.Supplier<State> stateSupplier) {
            this.target = target;
            this.name = target.name()
                              .or(deriveNameFromTarget(target.target()));
            this.pathProcessors = pathProcessors;
            this.bodyProcessor = bodyProcessor;
            this.port = port;
            this.httpClient = httpClient;
            this.metrics = metrics;
            this.entryPointMetrics = entryPointMetrics;
            this.stateSupplier = stateSupplier;
        }

        String name() {
            return name;
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
            startTime = Instant.now();
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
                    // Check if paused
                    while (paused.get() && running.get()) {
                        Thread.sleep(100);
                    }
                    if (!running.get()) break;
                    // Check duration limit
                    if (duration.isPresent() && !duration.unwrap()
                                                         .isZero()) {
                        var elapsed = Duration.between(startTime, Instant.now());
                        if (elapsed.compareTo(duration.unwrap()) >= 0) {
                            log.info("Target '{}' completed (duration limit reached)", name);
                            break;
                        }
                    }
                    var requestStart = System.nanoTime();
                    sendRequest();
                    var requests = totalRequests.incrementAndGet();
                    // Calculate actual rate
                    var elapsedMs = Duration.between(startTime,
                                                     Instant.now())
                                            .toMillis();
                    if (elapsedMs > 0) {
                        actualRate.set((int)(requests * 1000 / elapsedMs));
                    }
                    // Sleep for remaining interval
                    var elapsedMicros = (System.nanoTime() - requestStart) / 1000;
                    var sleepMicros = intervalMicros - elapsedMicros;
                    if (sleepMicros > 0) {
                        Thread.sleep(sleepMicros / 1000, (int)((sleepMicros % 1000) * 1000));
                    }
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

        private void sendRequest() {
            var requestStartTime = System.nanoTime();
            var path = buildPath();
            var body = buildBody();
            var method = target.isHttpPath()
                         ? inferMethod(target.target(), body)
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
            var path = target.target();
            if (!target.isHttpPath()) {
                // SliceName.method -> /api/invoke/SliceName/method
                var parts = target.target()
                                  .split("\\.", 2);
                if (parts.length == 2) {
                    path = "/api/invoke/" + parts[0] + "/" + parts[1];
                } else {
                    path = "/api/invoke/" + target.target();
                }
            }
            // Replace path variables
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

        TargetMetrics metrics() {
            Option<Duration> remaining = none();
            if (target.duration()
                      .isPresent() && !target.duration()
                                             .unwrap()
                                             .isZero()) {
                var elapsed = Duration.between(startTime != null
                                               ? startTime
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
            if (target.startsWith("/")) {
                // HTTP path: /api/orders/{id} -> orders
                var segments = target.split("/");
                for (int i = segments.length - 1; i >= 0; i--) {
                    var seg = segments[i];
                    if (!seg.isEmpty() && !seg.startsWith("{")) {
                        return seg;
                    }
                }
                return "target";
            } else {
                // SliceName.method -> method
                var dotIdx = target.lastIndexOf('.');
                return dotIdx >= 0
                       ? target.substring(dotIdx + 1)
                       : target;
            }
        }
    }
}

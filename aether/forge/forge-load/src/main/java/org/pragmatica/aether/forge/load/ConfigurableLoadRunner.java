package org.pragmatica.aether.forge.load;

import org.pragmatica.aether.forge.ForgeMetrics;
import org.pragmatica.aether.forge.load.pattern.TemplateProcessor;
import org.pragmatica.aether.forge.simulator.EntryPointMetrics;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.parse.Network;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
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
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Result.unitResult;

/// Config-driven load runner that generates HTTP load based on TOML configuration.
///
/// Supports:
///
///   - Multiple concurrent targets with independent rates
///   - Pattern-based data generation (uuid, random, range, choice, seq)
///   - Optional duration limits per target
///   - Pause/resume functionality
///   - Per-target metrics collection
///
public final class ConfigurableLoadRunner {
    private static final Logger log = LoggerFactory.getLogger(ConfigurableLoadRunner.class);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Cause NO_TARGETS_CONFIGURED = LoadConfigError.ParseFailed.parseFailed("No targets configured")
                                                                     .unwrap();

    /// Current runner state.
    public enum State {
        IDLE,
        RUNNING,
        PAUSED,
        STOPPING
    }

    private final Supplier<List<Integer>> portSupplier;
    private final ForgeMetrics metrics;
    private final EntryPointMetrics entryPointMetrics;
    private final HttpClient httpClient;
    private final AtomicInteger portRoundRobin = new AtomicInteger(0);

    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final AtomicReference<LoadConfig> currentConfig = new AtomicReference<>(LoadConfig.loadConfig()
                                                                                              .unwrap());
    private final AtomicReference<Double> rateMultiplier = new AtomicReference<>(1.0);

    private final Map<String, TargetRunner> activeRunners = new ConcurrentHashMap<>();
    private final List<Thread> runnerThreads = new CopyOnWriteArrayList<>();

    private final AtomicReference<ScheduledExecutorService> scheduler = new AtomicReference<>();

    private ConfigurableLoadRunner(Supplier<List<Integer>> portSupplier,
                                   ForgeMetrics metrics,
                                   EntryPointMetrics entryPointMetrics) {
        this.portSupplier = portSupplier;
        this.metrics = metrics;
        this.entryPointMetrics = entryPointMetrics;
        this.httpClient = buildHttpClient();
    }

    private static HttpClient buildHttpClient() {
        var builder = HttpClient.newBuilder()
                                .version(HttpClient.Version.HTTP_1_1)
                                .connectTimeout(REQUEST_TIMEOUT);
        return builder.executor(Executors.newVirtualThreadPerTaskExecutor())
                      .build();
    }

    public static ConfigurableLoadRunner configurableLoadRunner(int port,
                                                                ForgeMetrics metrics,
                                                                EntryPointMetrics entryPointMetrics) {
        return new ConfigurableLoadRunner(() -> List.of(port), metrics, entryPointMetrics);
    }

    public static ConfigurableLoadRunner configurableLoadRunner(Supplier<List<Integer>> portSupplier,
                                                                ForgeMetrics metrics,
                                                                EntryPointMetrics entryPointMetrics) {
        return new ConfigurableLoadRunner(portSupplier, metrics, entryPointMetrics);
    }

    private static final int DEFAULT_PORT = 8070;

    /// Select next port using round-robin across available ports.
    private int selectPort() {
        var ports = portSupplier.get();
        return ports.isEmpty()
               ? defaultPort()
               : portByRoundRobin(ports);
    }

    private int defaultPort() {
        log.warn("No ports available from supplier, using default {}", DEFAULT_PORT);
        return DEFAULT_PORT;
    }

    private int portByRoundRobin(List<Integer> ports) {
        var index = (portRoundRobin.getAndIncrement() & 0x7FFFFFFF) % ports.size();
        return ports.get(index);
    }

    /// Load configuration from TOML string content.
    public Result<LoadConfig> loadConfigFromString(String tomlContent) {
        return LoadConfigLoader.loadFromString(tomlContent)
                               .onSuccess(this::applyConfig);
    }

    /// Set the current configuration.
    public Result<Unit> applyConfig(LoadConfig config) {
        currentConfig.set(config);
        log.info("Loaded configuration with {} targets, total {} req/s",
                 config.targets()
                       .size(),
                 config.totalRequestsPerSecond());
        return unitResult();
    }

    /// Get the current configuration.
    public LoadConfig config() {
        return currentConfig.get();
    }

    /// Start load generation using the current configuration.
    public Result<State> start() {
        var hasNoTargets = currentConfig.get()
                                        .targets()
                                        .isEmpty();
        return hasNoTargets
               ? NO_TARGETS_CONFIGURED.result()
               : tryStart();
    }

    private Result<State> tryStart() {
        return tryTransitionToRunning()
               ? launchLoadGeneration()
               : cannotStart();
    }

    private Result<State> cannotStart() {
        log.warn("Cannot start - current state: {}", state.get());
        return success(state.get());
    }

    private Result<State> launchLoadGeneration() {
        var config = currentConfig.get();
        log.info("Starting load generation with {} targets",
                 config.targets()
                       .size());
        scheduler.set(Executors.newScheduledThreadPool(2));
        var runnerResults = buildRunnerEntries();
        return Result.allOf(runnerResults)
                     .onFailure(this::logRunnerCreationFailure)
                     .onSuccess(this::startAllRunners)
                     .map(_ -> State.RUNNING);
    }

    private boolean tryTransitionToRunning() {
        return state.compareAndSet(State.IDLE, State.RUNNING) || state.compareAndSet(State.PAUSED, State.RUNNING);
    }

    private List<Result<Map.Entry<LoadTarget, TargetRunner>>> buildRunnerEntries() {
        return currentConfig.get()
                            .targets()
                            .stream()
                            .map(this::toRunnerEntry)
                            .toList();
    }

    private Result<Map.Entry<LoadTarget, TargetRunner>> toRunnerEntry(LoadTarget target) {
        return assembleRunner(target).map(runner -> Map.entry(target, runner));
    }

    private void logRunnerCreationFailure(Cause cause) {
        log.error("Failed to create runners: {}", cause.message());
        cleanupScheduler();
        state.set(State.IDLE);
    }

    private void startAllRunners(List<Map.Entry<LoadTarget, TargetRunner>> entries) {
        entries.forEach(this::launchEntry);
        scheduleMetricsSync();
    }

    private void launchEntry(Map.Entry<LoadTarget, TargetRunner> entry) {
        launchRunner(entry.getValue(), entry.getKey());
    }

    private void scheduleMetricsSync() {
        var sched = scheduler.get();
        sched.schedule(() -> sched.scheduleAtFixedRate(this::syncMetrics, 0, 100, TimeUnit.MILLISECONDS),
                       200,
                       TimeUnit.MILLISECONDS);
    }

    private void cleanupScheduler() {
        option(scheduler.getAndSet(null)).onPresent(ScheduledExecutorService::shutdownNow);
    }

    /// Stop all load generation.
    public Result<Unit> stop() {
        if (!tryTransitionToStopping()) {
            return unitResult();
        }
        log.info("Stopping load generation");
        activeRunners.values()
                     .forEach(TargetRunner::stop);
        joinRunnerThreads();
        activeRunners.clear();
        runnerThreads.clear();
        cleanupScheduler();
        state.set(State.IDLE);
        return unitResult();
    }

    private boolean tryTransitionToStopping() {
        return state.compareAndSet(State.RUNNING, State.STOPPING) || state.compareAndSet(State.PAUSED, State.STOPPING);
    }

    private void joinRunnerThreads() {
        runnerThreads.forEach(this::joinThread);
    }

    private void joinThread(Thread thread) {
        try{
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread()
                  .interrupt();
        }
    }

    /// Pause load generation (can be resumed).
    public Result<Unit> pause() {
        if (state.compareAndSet(State.RUNNING, State.PAUSED)) {
            pauseAllRunners();
        }
        return unitResult();
    }

    private void pauseAllRunners() {
        log.info("Pausing load generation");
        activeRunners.values()
                     .forEach(TargetRunner::pause);
    }

    /// Resume paused load generation.
    public Result<Unit> resume() {
        if (state.compareAndSet(State.PAUSED, State.RUNNING)) {
            resumeAllRunners();
        }
        return unitResult();
    }

    private void resumeAllRunners() {
        log.info("Resuming load generation");
        activeRunners.values()
                     .forEach(TargetRunner::resume);
    }

    /// Set the total target rate by calculating and applying a multiplier.
    /// If running, stops and restarts with new rates.
    public Result<Unit> setTotalRate(int targetTotalRate) {
        var config = currentConfig.get();
        var currentTotal = config.totalRequestsPerSecond();
        return currentTotal <= 0
               ? noTargetsForRateChange()
               : setRateMultiplier(config, targetTotalRate, currentTotal);
    }

    private Result<Unit> noTargetsForRateChange() {
        log.warn("Cannot set rate - no targets configured");
        return unitResult();
    }

    private Result<Unit> setRateMultiplier(LoadConfig config, int targetTotalRate, int currentTotal) {
        var multiplier = (double) targetTotalRate / currentTotal;
        rateMultiplier.set(multiplier);
        log.info("Set rate multiplier to {} (target: {} req/s)", multiplier, targetTotalRate);
        return scaleAndRestart(config, multiplier);
    }

    private Result<Unit> scaleAndRestart(LoadConfig config, double multiplier) {
        var newConfig = LoadConfig.loadConfig(config, multiplier)
                                  .unwrap();
        if (state.get() == State.RUNNING || state.get() == State.PAUSED) {
            stop();
            applyConfig(newConfig);
            start();
        } else {
            applyConfig(newConfig);
        }
        return unitResult();
    }

    /// Get current rate multiplier.
    public double rateMultiplier() {
        return rateMultiplier.get();
    }

    /// Get current state.
    public State state() {
        return state.get();
    }

    /// Check if running.
    public boolean isRunning() {
        return state.get() == State.RUNNING;
    }

    /// Get metrics for a specific target.
    public Option<TargetMetrics> targetMetrics(String targetName) {
        return option(activeRunners.get(targetName)).map(TargetRunner::collectTargetMetrics);
    }

    /// Get metrics for all targets.
    public Map<String, TargetMetrics> allTargetMetrics() {
        var result = new HashMap<String, TargetMetrics>();
        activeRunners.forEach((name, runner) -> result.put(name, runner.collectTargetMetrics()));
        return result;
    }

    /// Get list of active target names.
    public List<String> activeTargets() {
        return List.copyOf(activeRunners.keySet());
    }

    private Result<TargetRunner> assembleRunner(LoadTarget target) {
        var pathResult = compilePathProcessors(target.pathVars());
        var templateResult = compilePathTemplate(target.httpPath());
        var bodyResult = compileBodyProcessor(target.body());
        return Result.all(pathResult, templateResult, bodyResult)
                     .flatMap((paths, tmpl, body) -> createTargetRunner(target, paths, tmpl, body));
    }

    private Result<TargetRunner> createTargetRunner(LoadTarget target,
                                                    Map<String, TemplateProcessor> pathProcessors,
                                                    Option<TemplateProcessor> pathTemplate,
                                                    Option<TemplateProcessor> bodyProcessor) {
        return TargetRunner.targetRunner(target,
                                         pathProcessors,
                                         pathTemplate,
                                         bodyProcessor,
                                         this::selectPort,
                                         httpClient,
                                         metrics,
                                         entryPointMetrics,
                                         () -> state.get());
    }

    private static Result<Option<TemplateProcessor>> compilePathTemplate(String httpPath) {
        return httpPath.contains("${")
               ? TemplateProcessor.compile(httpPath)
                                  .map(Option::some)
               : success(none());
    }

    private static Result<Map<String, TemplateProcessor>> compilePathProcessors(Map<String, String> pathVars) {
        var entries = pathVars.entrySet()
                              .stream()
                              .map(ConfigurableLoadRunner::compileEntry)
                              .toList();
        return Result.allOf(entries)
                     .map(ConfigurableLoadRunner::entriesToMap);
    }

    private static Result<Map.Entry<String, TemplateProcessor>> compileEntry(Map.Entry<String, String> entry) {
        var key = entry.getKey();
        var value = entry.getValue();
        return TemplateProcessor.compile(value)
                                .map(proc -> Map.entry(key, proc));
    }

    private static Map<String, TemplateProcessor> entriesToMap(List<Map.Entry<String, TemplateProcessor>> list) {
        return list.stream()
                   .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static Result<Option<TemplateProcessor>> compileBodyProcessor(Option<String> body) {
        return body.map(TemplateProcessor::compile)
                   .map(result -> result.map(Option::some))
                   .or(success(none()));
    }

    private void launchRunner(TargetRunner runner, LoadTarget target) {
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
        updateTargetLatency();
        activeRunners.forEach(this::syncRunnerMetrics);
    }

    private void updateTargetLatency() {
        var snapshots = entryPointMetrics.snapshotAndReset();
        snapshots.forEach(this::updateRunnerLatency);
    }

    private void updateRunnerLatency(EntryPointMetrics.EntryPointSnapshot snapshot) {
        var latencyMs = snapshot.avgLatencyMs();
        var runner = option(activeRunners.get(snapshot.name()));
        runner.filter(_ -> latencyMs > 0)
              .onPresent(r -> r.updateLatencyEma(latencyMs));
    }

    private void syncRunnerMetrics(String name, TargetRunner runner) {
        entryPointMetrics.setRate(name,
                                  runner.collectTargetMetrics()
                                        .targetRate());
    }

    /// Metrics for a single target.
    public record TargetMetrics(String name,
                                int targetRate,
                                int actualRate,
                                long totalRequests,
                                long successCount,
                                long failureCount,
                                double avgLatencyMs,
                                Option<Duration> remainingDuration) {
        static Result<TargetMetrics> targetMetrics(String name,
                                                   int targetRate,
                                                   int actualRate,
                                                   long totalRequests,
                                                   long successCount,
                                                   long failureCount,
                                                   double avgLatencyMs,
                                                   Option<Duration> remainingDuration) {
            return success(new TargetMetrics(name,
                                             targetRate,
                                             actualRate,
                                             totalRequests,
                                             successCount,
                                             failureCount,
                                             avgLatencyMs,
                                             remainingDuration));
        }

        public double successRate() {
            return totalRequests > 0
                   ? (double) successCount / totalRequests * 100
                   : 0;
        }
    }

    /// Runner for a single target.
    private record TargetRunner(LoadTarget target,
                                String name,
                                Map<String, TemplateProcessor> pathProcessors,
                                Option<TemplateProcessor> pathTemplateProcessor,
                                Option<TemplateProcessor> bodyProcessor,
                                Supplier<Integer> portSupplier,
                                HttpClient httpClient,
                                ForgeMetrics metrics,
                                EntryPointMetrics entryPointMetrics,
                                Supplier<State> stateSupplier,
                                AtomicBoolean running,
                                AtomicBoolean paused,
                                AtomicLong totalRequests,
                                AtomicLong successCount,
                                AtomicLong failureCount,
                                AtomicLong totalLatencyNanos,
                                AtomicReference<Instant> startTimeRef,
                                AtomicInteger actualRate,
                                AtomicReference<Double> emaAvgLatencyMs) {
        private static final Pattern HTTP_METHOD_PREFIX = Pattern.compile("^(GET|POST|PUT|DELETE|PATCH)\\s+");

        // ~2s effective window at 100ms snapshot interval
        private static final double LATENCY_EMA_ALPHA = 0.05;

        static Result<TargetRunner> targetRunner(LoadTarget target,
                                                 Map<String, TemplateProcessor> pathProcessors,
                                                 Option<TemplateProcessor> pathTemplateProcessor,
                                                 Option<TemplateProcessor> bodyProcessor,
                                                 Supplier<Integer> portSupplier,
                                                 HttpClient httpClient,
                                                 ForgeMetrics metrics,
                                                 EntryPointMetrics entryPointMetrics,
                                                 Supplier<State> stateSupplier) {
            return success(new TargetRunner(target,
                                            target.name()
                                                  .or(deriveNameFromTarget(target.target())),
                                            pathProcessors,
                                            pathTemplateProcessor,
                                            bodyProcessor,
                                            portSupplier,
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
                                            new AtomicInteger(0),
                                            new AtomicReference<>(0.0)));
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

        void updateLatencyEma(double instantMs) {
            var current = emaAvgLatencyMs.get();
            emaAvgLatencyMs.set(current == 0.0
                                ? instantMs
                                : LATENCY_EMA_ALPHA * instantMs + (1 - LATENCY_EMA_ALPHA) * current);
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
            runLoop(intervalMicros, duration);
            logCompletion();
        }

        @SuppressWarnings("JBCT-PAT-01")
        private void runLoop(long intervalMicros, Option<Duration> duration) {
            while (running.get()) {
                try{
                    if (!awaitUnpaused()) break;
                    if (isDurationExceeded(duration)) break;
                    performRequest(intervalMicros);
                } catch (Exception e) {
                    log.debug("Error in target '{}': {}", name, e.getMessage());
                }
            }
        }

        @SuppressWarnings("JBCT-PAT-01")
        private boolean awaitUnpaused() {
            try{
                while (paused.get() && running.get()) {
                    Thread.sleep(100);
                }
            } catch (InterruptedException e) {
                Thread.currentThread()
                      .interrupt();
                return false;
            }
            return running.get();
        }

        private boolean isDurationExceeded(Option<Duration> duration) {
            return duration.filter(d -> !d.isZero())
                           .map(this::isElapsedBeyond)
                           .or(false);
        }

        private boolean isElapsedBeyond(Duration limit) {
            var elapsed = Duration.between(startTime(), Instant.now());
            var exceeded = elapsed.compareTo(limit) >= 0;
            if (exceeded) {
                log.info("Target '{}' completed (duration limit reached)", name);
            }
            return exceeded;
        }

        private void performRequest(long intervalMicros) {
            var requestStart = System.nanoTime();
            sendRequest();
            var requests = totalRequests.incrementAndGet();
            refreshActualRate(requests);
            sleepRemaining(requestStart, intervalMicros);
        }

        private void refreshActualRate(long requests) {
            var elapsedMs = Duration.between(startTime(),
                                             Instant.now())
                                    .toMillis();
            var computedRate = elapsedMs > 0
                               ? (int)(requests * 1000 / elapsedMs)
                               : 0;
            actualRate.set(computedRate);
        }

        private void sleepRemaining(long requestStart, long intervalMicros) {
            var elapsedMicros = (System.nanoTime() - requestStart) / 1000;
            var sleepMicros = Math.max(0, intervalMicros - elapsedMicros);
            trySleep(sleepMicros);
        }

        private static void trySleep(long sleepMicros) {
            try{
                Thread.sleep(sleepMicros / 1000, (int)((sleepMicros % 1000) * 1000));
            } catch (InterruptedException e) {
                Thread.currentThread()
                      .interrupt();
            }
        }

        private void logCompletion() {
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
            var method = httpMethodFor(path, body);
            var uriStr = "http://localhost:" + portSupplier.get() + path;
            Network.parseURI(uriStr)
                   .onSuccess(uri -> dispatchRequest(uri, method, body, requestStartTime))
                   .onFailure(cause -> log.debug("Invalid URI '{}': {}",
                                                 uriStr,
                                                 cause.message()));
        }

        private void dispatchRequest(URI uri, String method, String body, long requestStartTime) {
            var requestBuilder = buildHttpRequest(uri, method, body);
            httpClient.sendAsync(requestBuilder.build(),
                                 HttpResponse.BodyHandlers.ofString())
                      .whenComplete((response, error) -> recordCompletion(response, error, requestStartTime));
        }

        private String httpMethodFor(String path, String body) {
            return target.isHttpPath()
                   ? target.httpMethod()
                           .or(() -> inferMethod(path, body))
                   : "POST";
        }

        private HttpRequest.Builder buildHttpRequest(URI uri, String method, String body) {
            var builder = HttpRequest.newBuilder()
                                     .uri(uri)
                                     .timeout(REQUEST_TIMEOUT);
            return methodHandler(builder, method, body);
        }

        private static HttpRequest.Builder methodHandler(HttpRequest.Builder builder, String method, String body) {
            return switch (method) {
                case "POST" -> withJsonBody(builder, body, true);
                case "PUT" -> withJsonBody(builder, body, false);
                case "DELETE" -> deleteRequest(builder);
                default -> getRequest(builder);
            };
        }

        private static HttpRequest.Builder withJsonBody(HttpRequest.Builder builder, String body, boolean isPost) {
            var withHeader = builder.header("Content-Type", "application/json");
            var publisher = HttpRequest.BodyPublishers.ofString(body);
            return isPost
                   ? withHeader.POST(publisher)
                   : withHeader.PUT(publisher);
        }

        @SuppressWarnings("JBCT-STY-03")
        private static HttpRequest.Builder getRequest(HttpRequest.Builder bldr) {
            return bldr.GET();
        }

        @SuppressWarnings("JBCT-STY-03")
        private static HttpRequest.Builder deleteRequest(HttpRequest.Builder bldr) {
            return bldr.DELETE();
        }

        private void recordCompletion(HttpResponse<String> response, Throwable error, long requestStartTime) {
            option(error).onPresent(_ -> recordFailure(System.nanoTime() - requestStartTime));
            option(response).onPresent(r -> recordResponse(r, requestStartTime));
        }

        private void recordResponse(HttpResponse<String> response, long requestStartTime) {
            var latencyNanos = System.nanoTime() - requestStartTime;
            totalLatencyNanos.addAndGet(latencyNanos);
            var statusCode = response.statusCode();
            var isSuccess = statusCode >= 200 && statusCode < 300;
            recordOutcome(isSuccess, latencyNanos, response);
        }

        private void recordOutcome(boolean isSuccess, long latencyNanos, HttpResponse<String> response) {
            // Routing only — no transformation
            if (isSuccess) {
                recordSuccess(latencyNanos);
            } else {
                recordHttpFailure(latencyNanos, response);
            }
        }

        private void recordHttpFailure(long latencyNanos, HttpResponse<String> response) {
            log.warn("HTTP {} from {}: {}", response.statusCode(), response.uri(), response.body());
            recordFailure(latencyNanos);
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
            var path = target.isHttpPath()
                       ? buildHttpPath()
                       : buildInvokePath();
            return substitutePathVars(path);
        }

        private String buildHttpPath() {
            return pathTemplateProcessor.map(TemplateProcessor::process)
                                        .or(target.httpPath());
        }

        private String buildInvokePath() {
            var parts = target.target()
                              .split("\\.", 2);
            return parts.length == 2
                   ? "/api/invoke/" + parts[0] + "/" + parts[1]
                   : "/api/invoke/" + target.target();
        }

        private String substitutePathVars(String path) {
            return pathProcessors.entrySet()
                                 .stream()
                                 .reduce(path,
                                         (p, e) -> substituteVar(p, e),
                                         (a, b) -> b);
        }

        private static String substituteVar(String path, Map.Entry<String, TemplateProcessor> entry) {
            return path.replace("{" + entry.getKey() + "}",
                                entry.getValue()
                                     .process());
        }

        private String buildBody() {
            return bodyProcessor.map(TemplateProcessor::process)
                                .or("");
        }

        private String inferMethod(String path, String body) {
            var hasBody = !body.isEmpty();
            var isDeletePath = path.contains("/delete") || path.endsWith("/cancel");
            return hasBody
                   ? "POST"
                   : isDeletePath
                     ? "DELETE"
                     : "GET";
        }

        TargetMetrics collectTargetMetrics() {
            var targetRate = target.rate()
                                   .requestsPerSecond();
            var total = totalRequests.get();
            var successes = successCount.get();
            var failures = failureCount.get();
            var avgLatency = emaAvgLatencyMs.get();
            var remaining = computeRemainingDuration();
            return TargetMetrics.targetMetrics(name,
                                               targetRate,
                                               actualRate.get(),
                                               total,
                                               successes,
                                               failures,
                                               avgLatency,
                                               remaining)
                                .unwrap();
        }

        private Option<Duration> computeRemainingDuration() {
            return target.duration()
                         .filter(d -> !d.isZero())
                         .flatMap(this::computeRemaining);
        }

        private Option<Duration> computeRemaining(Duration targetDuration) {
            var st = option(startTime()).or(Instant.now());
            var elapsed = Duration.between(st, Instant.now());
            var rem = targetDuration.minus(elapsed);
            return rem.isNegative()
                   ? none()
                   : some(rem);
        }

        private static String deriveNameFromTarget(String target) {
            var path = HTTP_METHOD_PREFIX.matcher(target)
                                         .replaceFirst("");
            return path.startsWith("/")
                   ? deriveFromHttpPath(path)
                   : deriveFromSliceMethod(path);
        }

        private static String deriveFromHttpPath(String path) {
            var segments = path.split("/");
            return findLastNonVariableSegment(segments);
        }

        private static String findLastNonVariableSegment(String[] segments) {
            var nonVariable = Arrays.stream(segments)
                                    .filter(TargetRunner::isNonVariableSegment);
            return nonVariable.reduce((first, second) -> second)
                              .orElse("target");
        }

        private static boolean isNonVariableSegment(String s) {
            return ! s.isEmpty() && !s.startsWith("{");
        }

        private static String deriveFromSliceMethod(String path) {
            var dotIdx = path.lastIndexOf('.');
            return dotIdx >= 0
                   ? path.substring(dotIdx + 1)
                   : path;
        }
    }
}

package org.pragmatica.aether.demo;

import org.pragmatica.aether.demo.simulator.EntryPointMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates continuous HTTP load against the cluster for demo purposes.
 * Supports per-entry-point rate control and metrics tracking.
 */
public final class LoadGenerator {
    private static final Logger log = LoggerFactory.getLogger(LoadGenerator.class);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

    // Known products that match InventoryServiceSlice mock data
    private static final List<String> PRODUCTS = List.of("PROD-ABC123", "PROD-DEF456", "PROD-GHI789");

    private final int port;
    private final DemoMetrics metrics;
    private final EntryPointMetrics entryPointMetrics;
    private final HttpClient httpClient;
    private final Random random = new Random();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong requestCounter = new AtomicLong(0);

    // Per-entry-point generators
    private final Map<String, EntryPointGenerator> generators = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduler;

    private LoadGenerator(int port, DemoMetrics metrics, EntryPointMetrics entryPointMetrics) {
        this.port = port;
        this.metrics = metrics;
        this.entryPointMetrics = entryPointMetrics;
        this.httpClient = HttpClient.newBuilder()
                                    .connectTimeout(REQUEST_TIMEOUT)
                                    .executor(Executors.newVirtualThreadPerTaskExecutor())
                                    .build();

        // Initialize default entry points
        initializeEntryPoints();
    }

    public static LoadGenerator loadGenerator(int port, DemoMetrics metrics, EntryPointMetrics entryPointMetrics) {
        return new LoadGenerator(port, metrics, entryPointMetrics);
    }

    /**
     * For backward compatibility with existing code.
     */
    public static LoadGenerator loadGenerator(int port, DemoMetrics metrics) {
        return new LoadGenerator(port, metrics, EntryPointMetrics.entryPointMetrics());
    }

    private void initializeEntryPoints() {
        // POST /api/orders - place order
        generators.put("placeOrder", new EntryPointGenerator(
            "placeOrder",
            () -> createOrderRequest(requestCounter.incrementAndGet()),
            "POST",
            "/api/orders"
        ));

        // GET /api/orders/{id} - get order status
        generators.put("getOrderStatus", new EntryPointGenerator(
            "getOrderStatus",
            this::createGetOrderRequest,
            "GET",
            "/api/orders/{id}"
        ));

        // DELETE /api/orders/{id} - cancel order
        generators.put("cancelOrder", new EntryPointGenerator(
            "cancelOrder",
            this::createCancelOrderRequest,
            "DELETE",
            "/api/orders/{id}"
        ));

        // GET /api/inventory/{productId} - check stock
        generators.put("checkStock", new EntryPointGenerator(
            "checkStock",
            this::createCheckStockRequest,
            "GET",
            "/api/inventory/{id}"
        ));

        // GET /api/pricing/{productId} - get price
        generators.put("getPrice", new EntryPointGenerator(
            "getPrice",
            this::createGetPriceRequest,
            "GET",
            "/api/pricing/{id}"
        ));
    }

    /**
     * Start generating load with default rates.
     */
    public void start(int defaultRequestsPerSecond) {
        if (running.getAndSet(true)) {
            log.warn("Load generator already running");
            return;
        }

        // Set default rate for placeOrder (main entry point)
        setRate("placeOrder", defaultRequestsPerSecond);

        log.info("Starting load generator at {} req/sec (placeOrder)", defaultRequestsPerSecond);

        scheduler = Executors.newScheduledThreadPool(generators.size() + 1);

        // Start a generation thread for each entry point
        for (var generator : generators.values()) {
            var thread = new Thread(() -> generateLoad(generator), "load-" + generator.name);
            thread.start();
        }

        // Rate sync scheduler (sync rates to metrics)
        scheduler.scheduleAtFixedRate(this::syncRatesToMetrics, 100, 100, TimeUnit.MILLISECONDS);
    }

    /**
     * Stop generating load.
     */
    public void stop() {
        log.info("Stopping load generator");
        running.set(false);

        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Set the rate for a specific entry point.
     */
    public void setRate(String entryPoint, int requestsPerSecond) {
        var generator = generators.get(entryPoint);
        if (generator != null) {
            log.info("Setting {} rate to {} req/sec", entryPoint, requestsPerSecond);
            generator.setRate(requestsPerSecond);
            entryPointMetrics.setRate(entryPoint, requestsPerSecond);
        } else {
            log.warn("Unknown entry point: {}", entryPoint);
        }
    }

    /**
     * Set the global rate (for backward compatibility - sets placeOrder rate).
     */
    public void setRate(int requestsPerSecond) {
        setRate("placeOrder", requestsPerSecond);
    }

    /**
     * Gradually ramp up to target rate for all active entry points.
     */
    public void rampUp(int targetRateValue, long durationMillis) {
        log.info("Ramping placeOrder load to {} req/sec over {}ms", targetRateValue, durationMillis);
        var generator = generators.get("placeOrder");
        if (generator != null) {
            generator.rampUp(targetRateValue, durationMillis);
        }
    }

    /**
     * Get current request rate (for placeOrder - backward compatibility).
     */
    public int currentRate() {
        var generator = generators.get("placeOrder");
        return generator != null ? generator.currentRate() : 0;
    }

    /**
     * Get current rate for a specific entry point.
     */
    public int currentRate(String entryPoint) {
        var generator = generators.get(entryPoint);
        return generator != null ? generator.currentRate() : 0;
    }

    /**
     * Get target request rate (for placeOrder - backward compatibility).
     */
    public int targetRate() {
        var generator = generators.get("placeOrder");
        return generator != null ? generator.targetRate() : 0;
    }

    /**
     * Check if generator is running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Get all entry point names.
     */
    public List<String> entryPoints() {
        return List.copyOf(generators.keySet());
    }

    /**
     * Get entry point metrics collector.
     */
    public EntryPointMetrics entryPointMetrics() {
        return entryPointMetrics;
    }

    private void generateLoad(EntryPointGenerator generator) {
        while (running.get()) {
            try {
                var rate = generator.currentRate();
                if (rate <= 0) {
                    Thread.sleep(100);
                    continue;
                }

                var intervalMicros = 1_000_000 / rate;
                var startNanos = System.nanoTime();

                sendRequest(generator);

                // Sleep for remaining interval
                var elapsedMicros = (System.nanoTime() - startNanos) / 1000;
                var sleepMicros = intervalMicros - elapsedMicros;
                if (sleepMicros > 0) {
                    Thread.sleep(sleepMicros / 1000, (int) ((sleepMicros % 1000) * 1000));
                }

                generator.adjustRate();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.debug("Error generating load for {}: {}", generator.name, e.getMessage());
            }
        }
    }

    private void sendRequest(EntryPointGenerator generator) {
        var startTime = System.nanoTime();
        var requestData = generator.requestFactory.get();

        var uri = URI.create("http://localhost:" + port + requestData.path());
        var requestBuilder = HttpRequest.newBuilder()
                                        .uri(uri)
                                        .timeout(REQUEST_TIMEOUT);

        switch (generator.method) {
            case "POST" -> requestBuilder
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestData.body()));
            case "DELETE" -> requestBuilder.DELETE();
            default -> requestBuilder.GET();
        }

        httpClient.sendAsync(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
                  .thenAccept(response -> {
                      var latencyNanos = System.nanoTime() - startTime;
                      if (response.statusCode() >= 200 && response.statusCode() < 300) {
                          metrics.recordSuccess(latencyNanos);
                          entryPointMetrics.recordSuccess(generator.name, latencyNanos);
                      } else {
                          metrics.recordFailure(latencyNanos);
                          entryPointMetrics.recordFailure(generator.name, latencyNanos);
                      }
                  })
                  .exceptionally(e -> {
                      var latencyNanos = System.nanoTime() - startTime;
                      metrics.recordFailure(latencyNanos);
                      entryPointMetrics.recordFailure(generator.name, latencyNanos);
                      return null;
                  });
    }

    private void syncRatesToMetrics() {
        for (var generator : generators.values()) {
            entryPointMetrics.setRate(generator.name, generator.currentRate());
        }
    }

    // Request factory methods

    private RequestData createOrderRequest(long requestId) {
        var customerId = String.format("CUST-%08d", requestId % 100_000_000);
        var productId = PRODUCTS.get(random.nextInt(PRODUCTS.size()));
        var quantity = 1 + random.nextInt(3);

        var body = String.format("""
            {"customerId":"%s","items":[{"productId":"%s","quantity":%d}]}""",
            customerId, productId, quantity);

        return new RequestData("/api/orders", body);
    }

    private RequestData createGetOrderRequest() {
        // Generate a plausible order ID
        var orderId = "ORD-" + System.currentTimeMillis() % 1000000;
        return new RequestData("/api/orders/" + orderId, "");
    }

    private RequestData createCancelOrderRequest() {
        var orderId = "ORD-" + System.currentTimeMillis() % 1000000;
        return new RequestData("/api/orders/" + orderId, "");
    }

    private RequestData createCheckStockRequest() {
        var productId = PRODUCTS.get(random.nextInt(PRODUCTS.size()));
        return new RequestData("/api/inventory/" + productId, "");
    }

    private RequestData createGetPriceRequest() {
        var productId = PRODUCTS.get(random.nextInt(PRODUCTS.size()));
        return new RequestData("/api/pricing/" + productId, "");
    }

    /**
     * Request data for an entry point call.
     */
    private record RequestData(String path, String body) {}

    /**
     * Generator for a single entry point with independent rate control.
     */
    private static final class EntryPointGenerator {
        final String name;
        final java.util.function.Supplier<RequestData> requestFactory;
        final String method;
        final String pathPattern;

        final AtomicInteger currentRate = new AtomicInteger(0);
        final AtomicInteger targetRate = new AtomicInteger(0);

        EntryPointGenerator(String name, java.util.function.Supplier<RequestData> requestFactory,
                           String method, String pathPattern) {
            this.name = name;
            this.requestFactory = requestFactory;
            this.method = method;
            this.pathPattern = pathPattern;
        }

        void setRate(int rate) {
            currentRate.set(rate);
            targetRate.set(rate);
        }

        void rampUp(int target, long durationMs) {
            targetRate.set(target);
            // Rate adjustment happens in adjustRate()
        }

        int currentRate() {
            return currentRate.get();
        }

        int targetRate() {
            return targetRate.get();
        }

        void adjustRate() {
            var current = currentRate.get();
            var target = targetRate.get();

            if (current == target) {
                return;
            }

            // Adjust by 10% toward target
            var delta = (target - current) / 10;
            if (delta == 0) {
                delta = target > current ? 1 : -1;
            }

            var newRate = current + delta;
            if ((delta > 0 && newRate > target) || (delta < 0 && newRate < target)) {
                newRate = target;
            }

            currentRate.set(newRate);
        }
    }
}

package org.pragmatica.aether.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generates continuous HTTP load against the cluster for demo purposes.
 * Sends simulated order requests to test slice request processing.
 */
public final class LoadGenerator {
    private static final Logger log = LoggerFactory.getLogger(LoadGenerator.class);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

    private final int port;
    private final DemoMetrics metrics;
    private final HttpClient httpClient;
    private final Random random = new Random();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger currentRate = new AtomicInteger(1000);
    private final AtomicInteger targetRate = new AtomicInteger(1000);
    private final AtomicLong requestCounter = new AtomicLong(0);

    private ScheduledExecutorService scheduler;
    private Thread loadThread;

    private LoadGenerator(int port, DemoMetrics metrics) {
        this.port = port;
        this.metrics = metrics;
        this.httpClient = HttpClient.newBuilder()
                                    .connectTimeout(REQUEST_TIMEOUT)
                                    .executor(Executors.newVirtualThreadPerTaskExecutor())
                                    .build();
    }

    public static LoadGenerator loadGenerator(int port, DemoMetrics metrics) {
        return new LoadGenerator(port, metrics);
    }

    /**
     * Start generating load at the specified rate.
     */
    public void start(int requestsPerSecond) {
        if (running.getAndSet(true)) {
            log.warn("Load generator already running");
            return;
        }

        currentRate.set(requestsPerSecond);
        targetRate.set(requestsPerSecond);

        log.info("Starting load generator at {} req/sec", requestsPerSecond);

        scheduler = Executors.newScheduledThreadPool(2);

        // Rate adjustment scheduler (for ramping)
        scheduler.scheduleAtFixedRate(this::adjustRate, 100, 100, TimeUnit.MILLISECONDS);

        // Load generation thread
        loadThread = new Thread(this::generateLoad, "load-generator");
        loadThread.start();
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

        if (loadThread != null) {
            loadThread.interrupt();
            try {
                loadThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Set the target request rate.
     */
    public void setRate(int requestsPerSecond) {
        log.info("Setting load rate to {} req/sec", requestsPerSecond);
        currentRate.set(requestsPerSecond);
        targetRate.set(requestsPerSecond);
    }

    /**
     * Gradually ramp up to target rate over specified duration.
     */
    public void rampUp(int targetRateValue, long durationMillis) {
        log.info("Ramping load from {} to {} req/sec over {}ms",
                 currentRate.get(), targetRateValue, durationMillis);
        targetRate.set(targetRateValue);
        // Rate adjustment happens in adjustRate()
    }

    /**
     * Get current request rate setting.
     */
    public int currentRate() {
        return currentRate.get();
    }

    /**
     * Get target request rate.
     */
    public int targetRate() {
        return targetRate.get();
    }

    /**
     * Check if generator is running.
     */
    public boolean isRunning() {
        return running.get();
    }

    private void generateLoad() {
        while (running.get()) {
            try {
                var rate = currentRate.get();
                if (rate <= 0) {
                    Thread.sleep(100);
                    continue;
                }

                var intervalMicros = 1_000_000 / rate;
                var startNanos = System.nanoTime();

                sendRequest();

                // Sleep for remaining interval
                var elapsedMicros = (System.nanoTime() - startNanos) / 1000;
                var sleepMicros = intervalMicros - elapsedMicros;
                if (sleepMicros > 0) {
                    Thread.sleep(sleepMicros / 1000, (int) ((sleepMicros % 1000) * 1000));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.debug("Error generating load: {}", e.getMessage());
            }
        }
    }

    private void sendRequest() {
        var requestId = requestCounter.incrementAndGet();
        var startTime = System.nanoTime();

        // Create a simulated order request
        var json = createOrderRequest(requestId);

        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/orders"))
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(json))
                                 .timeout(REQUEST_TIMEOUT)
                                 .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                  .thenAccept(response -> {
                      var latencyNanos = System.nanoTime() - startTime;
                      if (response.statusCode() >= 200 && response.statusCode() < 300) {
                          metrics.recordSuccess(latencyNanos);
                      } else {
                          metrics.recordFailure(latencyNanos);
                      }
                  })
                  .exceptionally(e -> {
                      metrics.recordFailure(System.nanoTime() - startTime);
                      return null;
                  });
    }

    // Product IDs that match InventoryServiceSlice mock data
    private static final String[] PRODUCTS = {"PROD-ABC123", "PROD-DEF456", "PROD-GHI789"};

    private String createOrderRequest(long requestId) {
        // CustomerId requires 8 digits: CUST-12345678
        var customerId = String.format("CUST-%08d", requestId % 100_000_000);
        var productId = PRODUCTS[random.nextInt(PRODUCTS.length)];
        var quantity = 1 + random.nextInt(3);  // Keep quantity low to avoid stock issues

        return String.format("""
            {"customerId":"%s","items":[{"productId":"%s","quantity":%d}]}""",
                             customerId, productId, quantity);
    }

    private void adjustRate() {
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

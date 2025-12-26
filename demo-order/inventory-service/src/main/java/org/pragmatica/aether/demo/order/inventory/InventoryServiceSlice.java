package org.pragmatica.aether.demo.order.inventory;

import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Inventory Service Slice - manages product stock and reservations.
 * <p>
 * Supports two modes:
 * - Infinite mode (default): Stock never depletes
 * - Realistic mode: Stock depletes and refills at configured rate
 */
public record InventoryServiceSlice() implements Slice {

    // Stock mode: true = infinite supply, false = realistic mode
    private static final AtomicBoolean INFINITE_MODE = new AtomicBoolean(true);

    // Initial stock levels per product
    private static final int INITIAL_STOCK = 1_000_000;

    // Refill configuration
    private static final AtomicInteger REFILL_RATE = new AtomicInteger(1000); // units per refill
    private static final AtomicInteger REFILL_INTERVAL_MS = new AtomicInteger(100); // milliseconds

    // Stock data
    private static final Map<String, AtomicInteger> STOCK = new ConcurrentHashMap<>();
    private static final Map<String, ReservedStock> RESERVATIONS = new ConcurrentHashMap<>();

    // Metrics
    private static final AtomicLong TOTAL_RESERVATIONS = new AtomicLong();
    private static final AtomicLong TOTAL_RELEASES = new AtomicLong();
    private static final AtomicLong STOCK_OUTS = new AtomicLong();

    // Refill scheduler
    private static final ScheduledExecutorService REFILL_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "inventory-refill");
        t.setDaemon(true);
        return t;
    });

    static {
        // Initialize stock for known products
        STOCK.put("PROD-ABC123", new AtomicInteger(INITIAL_STOCK));
        STOCK.put("PROD-DEF456", new AtomicInteger(INITIAL_STOCK));
        STOCK.put("PROD-GHI789", new AtomicInteger(INITIAL_STOCK));

        // Start refill scheduler
        REFILL_SCHEDULER.scheduleAtFixedRate(InventoryServiceSlice::refillStock,
            100, 100, TimeUnit.MILLISECONDS);
    }

    private record ReservedStock(String productId, int quantity) {}

    public static InventoryServiceSlice inventoryServiceSlice() {
        return new InventoryServiceSlice();
    }

    /**
     * Set stock mode.
     * @param infinite true for infinite supply, false for realistic mode
     */
    public static void setInfiniteMode(boolean infinite) {
        INFINITE_MODE.set(infinite);
    }

    /**
     * Get current stock mode.
     */
    public static boolean isInfiniteMode() {
        return INFINITE_MODE.get();
    }

    /**
     * Set refill rate (units per refill cycle).
     */
    public static void setRefillRate(int rate) {
        REFILL_RATE.set(rate);
    }

    /**
     * Get current stock levels for all products.
     */
    public static Map<String, Integer> getStockLevels() {
        var levels = new ConcurrentHashMap<String, Integer>();
        STOCK.forEach((k, v) -> levels.put(k, v.get()));
        return levels;
    }

    /**
     * Get inventory metrics.
     */
    public static InventoryMetrics getMetrics() {
        return new InventoryMetrics(
            TOTAL_RESERVATIONS.get(),
            TOTAL_RELEASES.get(),
            STOCK_OUTS.get(),
            INFINITE_MODE.get(),
            REFILL_RATE.get()
        );
    }

    /**
     * Reset stock to initial levels.
     */
    public static void resetStock() {
        STOCK.forEach((k, v) -> v.set(INITIAL_STOCK));
        RESERVATIONS.clear();
        TOTAL_RESERVATIONS.set(0);
        TOTAL_RELEASES.set(0);
        STOCK_OUTS.set(0);
    }

    private static void refillStock() {
        if (INFINITE_MODE.get()) {
            return; // No refill needed in infinite mode
        }

        var refillAmount = REFILL_RATE.get();
        STOCK.forEach((productId, stock) -> {
            var current = stock.get();
            if (current < INITIAL_STOCK) {
                var newValue = Math.min(current + refillAmount, INITIAL_STOCK);
                stock.set(newValue);
            }
        });
    }

    /**
     * Inventory metrics record.
     */
    public record InventoryMetrics(
        long totalReservations,
        long totalReleases,
        long stockOuts,
        boolean infiniteMode,
        int refillRate
    ) {}

    @Override
    public List<SliceMethod<?, ?>> methods() {
        return List.of(
            new SliceMethod<>(
                MethodName.methodName("checkStock").unwrap(),
                this::checkStock,
                new TypeToken<StockAvailability>() {},
                new TypeToken<CheckStockRequest>() {}
            ),
            new SliceMethod<>(
                MethodName.methodName("reserveStock").unwrap(),
                this::reserveStock,
                new TypeToken<StockReservation>() {},
                new TypeToken<ReserveStockRequest>() {}
            ),
            new SliceMethod<>(
                MethodName.methodName("releaseStock").unwrap(),
                this::releaseStock,
                new TypeToken<StockReleased>() {},
                new TypeToken<ReleaseStockRequest>() {}
            )
        );
    }

    @Override
    public Promise<Unit> stop() {
        REFILL_SCHEDULER.shutdown();
        try {
            if (!REFILL_SCHEDULER.awaitTermination(1, TimeUnit.SECONDS)) {
                REFILL_SCHEDULER.shutdownNow();
            }
        } catch (InterruptedException e) {
            REFILL_SCHEDULER.shutdownNow();
            Thread.currentThread().interrupt();
        }
        return Promise.success(Unit.unit());
    }

    private Promise<StockAvailability> checkStock(CheckStockRequest request) {
        var productId = request.productId();
        var stock = STOCK.get(productId);

        if (stock == null) {
            return new InventoryError.ProductNotFound(productId).promise();
        }

        var available = stock.get();
        var sufficient = available >= request.quantity();
        return Promise.success(new StockAvailability(productId, available, sufficient));
    }

    private Promise<StockReservation> reserveStock(ReserveStockRequest request) {
        var productId = request.productId();
        var stock = STOCK.get(productId);

        if (stock == null) {
            return new InventoryError.ProductNotFound(productId).promise();
        }

        var reservationId = "RES-" + UUID.randomUUID().toString().substring(0, 8);
        var quantity = request.quantity();

        if (INFINITE_MODE.get()) {
            // Infinite mode: don't actually decrement stock
            TOTAL_RESERVATIONS.incrementAndGet();
            // Still store reservation for proper release handling
            RESERVATIONS.put(reservationId, new ReservedStock(productId, quantity));
            return Promise.success(new StockReservation(reservationId, productId, quantity));
        }

        // Realistic mode: atomic compare-and-set loop to avoid race conditions
        while (true) {
            var current = stock.get();
            if (current < quantity) {
                STOCK_OUTS.incrementAndGet();
                return new InventoryError.InsufficientStock(productId, quantity, current).promise();
            }

            if (stock.compareAndSet(current, current - quantity)) {
                // CAS succeeded - reservation is safe
                TOTAL_RESERVATIONS.incrementAndGet();
                RESERVATIONS.put(reservationId, new ReservedStock(productId, quantity));
                return Promise.success(new StockReservation(reservationId, productId, quantity));
            }
            // CAS failed - another thread modified stock, retry
        }
    }

    private Promise<StockReleased> releaseStock(ReleaseStockRequest request) {
        var reservation = RESERVATIONS.remove(request.reservationId());

        if (reservation == null) {
            // In demo/load testing, reservations may not exist - just succeed
            return Promise.success(new StockReleased(request.reservationId()));
        }

        // Restore stock
        var stock = STOCK.get(reservation.productId());
        if (stock != null) {
            stock.addAndGet(reservation.quantity());
        }

        TOTAL_RELEASES.incrementAndGet();
        return Promise.success(new StockReleased(request.reservationId()));
    }
}

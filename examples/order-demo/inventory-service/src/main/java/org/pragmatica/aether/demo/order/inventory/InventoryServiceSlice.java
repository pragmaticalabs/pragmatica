package org.pragmatica.aether.demo.order.inventory;

import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.utility.IdGenerator;

import java.util.List;
import java.util.Map;
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
    // === Requests ===
    public record CheckStockRequest(String productId, int quantity) {}

    public record ReserveStockRequest(String productId, int quantity, String orderId) {}

    public record ReleaseStockRequest(String reservationId) {}

    // === Responses ===
    public record StockAvailability(String productId, int available, boolean sufficient) {}

    public record StockReservation(String reservationId, String productId, int quantity) {}

    public record StockReleased(String reservationId) {}

    public record InventoryMetrics(long totalReservations,
                                   long totalReleases,
                                   long stockOuts,
                                   boolean infiniteMode,
                                   int refillRate) {}

    // === Errors ===
    public sealed interface InventoryError extends Cause {
        record ProductNotFound(String productId) implements InventoryError {
            @Override
            public String message() {
                return "Product not found: " + productId;
            }
        }

        record InsufficientStock(String productId, int requested, int available) implements InventoryError {
            @Override
            public String message() {
                return "Insufficient stock for " + productId + ": requested " + requested + ", available " + available;
            }
        }
    }

    // === Static State ===
    private static final AtomicBoolean INFINITE_MODE = new AtomicBoolean(true);
    private static final int INITIAL_STOCK = 1_000_000;
    private static final AtomicInteger REFILL_RATE = new AtomicInteger(1000);
    private static final AtomicInteger REFILL_INTERVAL_MS = new AtomicInteger(100);

    private static final Map<String, AtomicInteger> STOCK = new ConcurrentHashMap<>();
    private static final Map<String, ReservedStock> RESERVATIONS = new ConcurrentHashMap<>();

    private static final AtomicLong TOTAL_RESERVATIONS = new AtomicLong();
    private static final AtomicLong TOTAL_RELEASES = new AtomicLong();
    private static final AtomicLong STOCK_OUTS = new AtomicLong();

    private static final ScheduledExecutorService REFILL_SCHEDULER = Executors.newSingleThreadScheduledExecutor(InventoryServiceSlice::createRefillThread);

    static {
        STOCK.put("PROD-ABC123", new AtomicInteger(INITIAL_STOCK));
        STOCK.put("PROD-DEF456", new AtomicInteger(INITIAL_STOCK));
        STOCK.put("PROD-GHI789", new AtomicInteger(INITIAL_STOCK));
        REFILL_SCHEDULER.scheduleAtFixedRate(InventoryServiceSlice::refillStock, 100, 100, TimeUnit.MILLISECONDS);
    }

    private record ReservedStock(String productId, int quantity) {}

    // === Factory ===
    public static InventoryServiceSlice inventoryServiceSlice() {
        return new InventoryServiceSlice();
    }

    // === Configuration Methods ===
    public static void setInfiniteMode(boolean infinite) {
        INFINITE_MODE.set(infinite);
    }

    public static boolean isInfiniteMode() {
        return INFINITE_MODE.get();
    }

    public static void setRefillRate(int rate) {
        REFILL_RATE.set(rate);
    }

    public static Map<String, Integer> getStockLevels() {
        var levels = new ConcurrentHashMap<String, Integer>();
        STOCK.forEach((k, v) -> levels.put(k, v.get()));
        return levels;
    }

    public static InventoryMetrics getMetrics() {
        return new InventoryMetrics(TOTAL_RESERVATIONS.get(),
                                    TOTAL_RELEASES.get(),
                                    STOCK_OUTS.get(),
                                    INFINITE_MODE.get(),
                                    REFILL_RATE.get());
    }

    public static void resetStock() {
        STOCK.forEach((k, v) -> v.set(INITIAL_STOCK));
        RESERVATIONS.clear();
        TOTAL_RESERVATIONS.set(0);
        TOTAL_RELEASES.set(0);
        STOCK_OUTS.set(0);
    }

    private static Thread createRefillThread(Runnable r) {
        var t = new Thread(r, "inventory-refill");
        t.setDaemon(true);
        return t;
    }

    private static void refillStock() {
        if (INFINITE_MODE.get()) return;
        var refillAmount = REFILL_RATE.get();
        STOCK.forEach((productId, stock) -> refillProductStock(stock, refillAmount));
    }

    private static void refillProductStock(AtomicInteger stock, int refillAmount) {
        var current = stock.get();
        if (current < INITIAL_STOCK) {
            stock.set(Math.min(current + refillAmount, INITIAL_STOCK));
        }
    }

    // === Slice Implementation ===
    @Override
    public List<SliceMethod<?, ?>> methods() {
        return List.of(new SliceMethod<>(MethodName.methodName("checkStock")
                                                   .expect("Invalid method name: checkStock"),
                                         this::checkStock,
                                         new TypeToken<StockAvailability>() {},
                                         new TypeToken<CheckStockRequest>() {}),
                       new SliceMethod<>(MethodName.methodName("reserveStock")
                                                   .expect("Invalid method name: reserveStock"),
                                         this::reserveStock,
                                         new TypeToken<StockReservation>() {},
                                         new TypeToken<ReserveStockRequest>() {}),
                       new SliceMethod<>(MethodName.methodName("releaseStock")
                                                   .expect("Invalid method name: releaseStock"),
                                         this::releaseStock,
                                         new TypeToken<StockReleased>() {},
                                         new TypeToken<ReleaseStockRequest>() {}));
    }

    @Override
    public Promise<Unit> stop() {
        REFILL_SCHEDULER.shutdown();
        try{
            if (!REFILL_SCHEDULER.awaitTermination(1, TimeUnit.SECONDS)) {
                REFILL_SCHEDULER.shutdownNow();
            }
        } catch (InterruptedException e) {
            REFILL_SCHEDULER.shutdownNow();
            Thread.currentThread()
                  .interrupt();
        }
        return Promise.success(Unit.unit());
    }

    private Promise<StockAvailability> checkStock(CheckStockRequest request) {
        return Option.option(STOCK.get(request.productId()))
                     .toResult(new InventoryError.ProductNotFound(request.productId()))
                     .async()
                     .map(stock -> toStockAvailability(request, stock));
    }

    private static StockAvailability toStockAvailability(CheckStockRequest request, AtomicInteger stock) {
        var available = stock.get();
        return new StockAvailability(request.productId(), available, available >= request.quantity());
    }

    private Promise<StockReservation> reserveStock(ReserveStockRequest request) {
        return Option.option(STOCK.get(request.productId()))
                     .toResult(new InventoryError.ProductNotFound(request.productId()))
                     .async()
                     .flatMap(stock -> doReserveStock(request, stock));
    }

    private Promise<StockReservation> doReserveStock(ReserveStockRequest request, AtomicInteger stock) {
        var reservationId = IdGenerator.generate("RES");
        var quantity = request.quantity();
        if (INFINITE_MODE.get()) {
            TOTAL_RESERVATIONS.incrementAndGet();
            RESERVATIONS.put(reservationId, new ReservedStock(request.productId(), quantity));
            return Promise.success(new StockReservation(reservationId, request.productId(), quantity));
        }
        while (true) {
            var current = stock.get();
            if (current < quantity) {
                STOCK_OUTS.incrementAndGet();
                return new InventoryError.InsufficientStock(request.productId(), quantity, current).promise();
            }
            if (stock.compareAndSet(current, current - quantity)) {
                TOTAL_RESERVATIONS.incrementAndGet();
                RESERVATIONS.put(reservationId, new ReservedStock(request.productId(), quantity));
                return Promise.success(new StockReservation(reservationId, request.productId(), quantity));
            }
        }
    }

    private Promise<StockReleased> releaseStock(ReleaseStockRequest request) {
        Option.option(RESERVATIONS.remove(request.reservationId()))
              .onPresent(InventoryServiceSlice::returnReservedStock);
        return Promise.success(new StockReleased(request.reservationId()));
    }

    private static void returnReservedStock(ReservedStock reservation) {
        Option.option(STOCK.get(reservation.productId()))
              .onPresent(stock -> stock.addAndGet(reservation.quantity()));
        TOTAL_RELEASES.incrementAndGet();
    }
}

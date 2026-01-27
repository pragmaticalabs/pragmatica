package org.pragmatica.aether.forge.api;

import org.pragmatica.aether.forge.LoadGenerator;
import org.pragmatica.aether.forge.api.ChaosRoutes.EventLogEntry;
import org.pragmatica.aether.forge.api.ForgeApiResponses.*;
import org.pragmatica.aether.forge.simulator.DataGenerator;
import org.pragmatica.aether.forge.simulator.SimulatorConfig;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.pragmatica.http.routing.PathParameter.aInteger;
import static org.pragmatica.http.routing.PathParameter.aString;
import static org.pragmatica.http.routing.Route.get;
import static org.pragmatica.http.routing.Route.in;
import static org.pragmatica.http.routing.Route.post;

/**
 * REST API routes for simulator operations.
 * <p>
 * Provides endpoints for:
 * <ul>
 *   <li>Entry point listing and rate control</li>
 *   <li>Inventory mode management (infinite/realistic)</li>
 *   <li>Simulated order operations (place, status, cancel)</li>
 *   <li>Simulated inventory and pricing queries</li>
 * </ul>
 */
public sealed interface SimulatorRoutes {
    /**
     * State holder for inventory simulation.
     */
    record InventoryState(AtomicLong reservations,
                          AtomicLong releases,
                          AtomicLong stockOuts,
                          AtomicReference<Boolean> infiniteMode) {
        public static InventoryState inventoryState() {
            return new InventoryState(new AtomicLong(0),
                                      new AtomicLong(0),
                                      new AtomicLong(0),
                                      new AtomicReference<>(true));
        }

        public boolean isInfiniteMode() {
            return infiniteMode.get();
        }

        public void setInfiniteMode(boolean mode) {
            infiniteMode.set(mode);
        }

        public void reset() {
            reservations.set(0);
            releases.set(0);
            stockOuts.set(0);
        }
    }

    /**
     * Create route source for all simulator-related endpoints.
     */
    static RouteSource simulatorRoutes(LoadGenerator loadGenerator,
                                       Supplier<SimulatorConfig> configSupplier,
                                       InventoryState inventoryState,
                                       Consumer<EventLogEntry> eventLogger) {
        return in("/api/simulator")
        .serve(entryPointsRoute(loadGenerator),
               setRateRoute(loadGenerator, eventLogger),
               getInventoryModeRoute(inventoryState),
               setInventoryModeRoute(inventoryState, eventLogger),
               inventoryMetricsRoute(inventoryState),
               placeOrderRoute(configSupplier),
               getOrderRoute(configSupplier),
               cancelOrderRoute(configSupplier),
               checkStockRoute(configSupplier),
               getPriceRoute(configSupplier));
    }

    // ========== Route Definitions ==========
    private static Route<EntryPointsResponse> entryPointsRoute(LoadGenerator loadGenerator) {
        return Route.<EntryPointsResponse> get("/entry-points")
                    .toJson(() -> getEntryPoints(loadGenerator));
    }

    private static Route<SimulatorRateResponse> setRateRoute(LoadGenerator loadGenerator,
                                                             Consumer<EventLogEntry> eventLogger) {
        return Route.<SimulatorRateResponse> post("/rate")
                    .withPath(aInteger())
                    .to(rate -> setRate(loadGenerator, eventLogger, rate))
                    .asJson();
    }

    private static Route<InventoryModeResponse> getInventoryModeRoute(InventoryState state) {
        return Route.<InventoryModeResponse> get("/inventory/mode")
                    .toJson(() -> getInventoryMode(state));
    }

    private static Route<InventoryModeSetResponse> setInventoryModeRoute(InventoryState state,
                                                                         Consumer<EventLogEntry> eventLogger) {
        return Route.<InventoryModeSetResponse> post("/inventory/mode")
                    .withPath(aString())
                    .to(mode -> setInventoryMode(state, eventLogger, mode))
                    .asJson();
    }

    private static Route<InventoryMetricsResponse> inventoryMetricsRoute(InventoryState state) {
        return Route.<InventoryMetricsResponse> get("/inventory/metrics")
                    .toJson(() -> getInventoryMetrics(state));
    }

    private static Route<PlaceOrderResponse> placeOrderRoute(Supplier<SimulatorConfig> configSupplier) {
        return Route.<PlaceOrderResponse> post("/orders/place")
                    .toJson(_ -> placeOrder(configSupplier));
    }

    private static Route<OrderStatusResponse> getOrderRoute(Supplier<SimulatorConfig> configSupplier) {
        return Route.<OrderStatusResponse> get("/orders")
                    .withPath(aString())
                    .to(orderId -> getOrderStatus(configSupplier, orderId))
                    .asJson();
    }

    private static Route<CancelOrderResponse> cancelOrderRoute(Supplier<SimulatorConfig> configSupplier) {
        return Route.<CancelOrderResponse> post("/orders/cancel")
                    .withPath(aString())
                    .to(orderId -> cancelOrder(configSupplier, orderId))
                    .asJson();
    }

    private static Route<CheckStockResponse> checkStockRoute(Supplier<SimulatorConfig> configSupplier) {
        return Route.<CheckStockResponse> get("/inventory")
                    .withPath(aString())
                    .to(productId -> checkStock(configSupplier, productId))
                    .asJson();
    }

    private static Route<GetPriceResponse> getPriceRoute(Supplier<SimulatorConfig> configSupplier) {
        return Route.<GetPriceResponse> get("/pricing")
                    .withPath(aString())
                    .to(productId -> getPrice(configSupplier, productId))
                    .asJson();
    }

    // ========== Handler Methods ==========
    private static EntryPointsResponse getEntryPoints(LoadGenerator loadGenerator) {
        var entryPointInfos = loadGenerator.entryPoints()
                                           .stream()
                                           .map(ep -> new EntryPointInfo(ep,
                                                                         loadGenerator.currentRate(ep)))
                                           .toList();
        return new EntryPointsResponse(entryPointInfos);
    }

    private static Promise<SimulatorRateResponse> setRate(LoadGenerator loadGenerator,
                                                          Consumer<EventLogEntry> eventLogger,
                                                          int rate) {
        loadGenerator.setRate(rate);
        eventLogger.accept(new EventLogEntry("RATE_SET", "Set global rate to " + rate + " req/sec"));
        return Promise.success(new SimulatorRateResponse(true, "global", rate));
    }

    private static InventoryModeResponse getInventoryMode(InventoryState state) {
        return new InventoryModeResponse(state.isInfiniteMode()
                                         ? "infinite"
                                         : "realistic");
    }

    private static Promise<InventoryModeSetResponse> setInventoryMode(InventoryState state,
                                                                      Consumer<EventLogEntry> eventLogger,
                                                                      String mode) {
        boolean infinite = "infinite".equalsIgnoreCase(mode);
        state.setInfiniteMode(infinite);
        String modeStr = infinite
                         ? "infinite"
                         : "realistic";
        eventLogger.accept(new EventLogEntry("INVENTORY_MODE", "Inventory mode set to " + modeStr));
        return Promise.success(new InventoryModeSetResponse(true, modeStr));
    }

    private static InventoryMetricsResponse getInventoryMetrics(InventoryState state) {
        return new InventoryMetricsResponse(state.reservations()
                                                 .get(),
                                            state.releases()
                                                 .get(),
                                            state.stockOuts()
                                                 .get(),
                                            state.isInfiniteMode(),
                                            100);
    }

    private static Promise<PlaceOrderResponse> placeOrder(Supplier<SimulatorConfig> configSupplier) {
        return applySimulation(configSupplier, "place-order").map(_ -> buildPlaceOrderResponse());
    }

    private static PlaceOrderResponse buildPlaceOrderResponse() {
        var random = ThreadLocalRandom.current();
        var orderId = String.format("ORD-%08d", random.nextInt(100_000_000));
        DataGenerator.OrderIdGenerator.trackOrderId(orderId);
        var total = 10.00 + random.nextDouble() * 990.00;
        return new PlaceOrderResponse(true, orderId, "CONFIRMED", String.format("USD %.2f", total));
    }

    private static Promise<OrderStatusResponse> getOrderStatus(Supplier<SimulatorConfig> configSupplier,
                                                               String orderId) {
        return applySimulation(configSupplier, "get-order-status").map(_ -> buildOrderStatusResponse(orderId));
    }

    private static OrderStatusResponse buildOrderStatusResponse(String orderId) {
        var random = ThreadLocalRandom.current();
        var statuses = List.of("CONFIRMED", "PROCESSING", "SHIPPED", "DELIVERED");
        var status = statuses.get(random.nextInt(statuses.size()));
        var total = 10.00 + random.nextDouble() * 990.00;
        var itemCount = 1 + random.nextInt(5);
        return new OrderStatusResponse(true, orderId, status, String.format("USD %.2f", total), itemCount);
    }

    private static Promise<CancelOrderResponse> cancelOrder(Supplier<SimulatorConfig> configSupplier,
                                                            String orderId) {
        return applySimulation(configSupplier, "cancel-order")
        .map(_ -> new CancelOrderResponse(true, orderId, "CANCELLED", "User requested cancellation"));
    }

    private static Promise<CheckStockResponse> checkStock(Supplier<SimulatorConfig> configSupplier,
                                                          String productId) {
        return applySimulation(configSupplier, "inventory-service").map(_ -> buildCheckStockResponse(productId));
    }

    private static CheckStockResponse buildCheckStockResponse(String productId) {
        var random = ThreadLocalRandom.current();
        var available = random.nextInt(1000);
        return new CheckStockResponse(true, productId, available, available > 0);
    }

    private static Promise<GetPriceResponse> getPrice(Supplier<SimulatorConfig> configSupplier,
                                                      String productId) {
        return applySimulation(configSupplier, "pricing-service").map(_ -> buildGetPriceResponse(productId));
    }

    private static GetPriceResponse buildGetPriceResponse(String productId) {
        var random = ThreadLocalRandom.current();
        var price = 5.00 + random.nextDouble() * 495.00;
        return new GetPriceResponse(true, productId, String.format("USD %.2f", price));
    }

    private static Promise<Unit> applySimulation(Supplier<SimulatorConfig> configSupplier,
                                                 String sliceName) {
        var config = configSupplier.get();
        var sliceConfig = config.sliceConfig(sliceName);
        return sliceConfig.buildSimulation()
                          .apply();
    }

    record unused() implements SimulatorRoutes {}
}

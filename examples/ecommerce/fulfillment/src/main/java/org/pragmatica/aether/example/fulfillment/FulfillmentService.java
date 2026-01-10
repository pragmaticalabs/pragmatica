package org.pragmatica.aether.example.fulfillment;

import org.pragmatica.aether.example.shared.Address;
import org.pragmatica.aether.example.shared.LineItem;
import org.pragmatica.aether.example.shared.Money;
import org.pragmatica.aether.example.shared.OrderId;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.utility.IdGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/**
 * Fulfillment and shipping slice.
 * Manages shipment creation, tracking, and shipping cost calculation.
 */
@Slice
public interface FulfillmentService {
    // === Requests ===
    record CalculateShippingRequest(List<LineItem> items, Address destination) {
        public static CalculateShippingRequest calculateShippingRequest(List<LineItem> items, Address destination) {
            return new CalculateShippingRequest(List.copyOf(items), destination);
        }
    }

    record CreateShipmentRequest(OrderId orderId,
                                 List<LineItem> items,
                                 Address shippingAddress,
                                 ShippingOption shippingOption) {
        public static CreateShipmentRequest createShipmentRequest(OrderId orderId,
                                                                  List<LineItem> items,
                                                                  Address address,
                                                                  ShippingOption option) {
            return new CreateShipmentRequest(orderId, List.copyOf(items), address, option);
        }
    }

    record TrackShipmentRequest(String trackingNumber) {
        public static TrackShipmentRequest trackShipmentRequest(String trackingNumber) {
            return new TrackShipmentRequest(trackingNumber);
        }
    }

    // === Responses ===
    record ShippingQuote(List<ShippingOptionQuote> options, Instant validUntil) {
        public record ShippingOptionQuote(ShippingOption option, Money cost, Instant estimatedDelivery) {}

        public static ShippingQuote quote(List<ShippingOptionQuote> options) {
            return new ShippingQuote(List.copyOf(options),
                                     Instant.now()
                                            .plusSeconds(3600));
        }
    }

    record Shipment(String shipmentId,
                    OrderId orderId,
                    String trackingNumber,
                    ShippingOption shippingOption,
                    Address destination,
                    ShipmentStatus status,
                    Instant estimatedDelivery,
                    Instant createdAt) {
        public enum ShipmentStatus {
            PENDING,
            LABEL_CREATED,
            PICKED_UP,
            IN_TRANSIT,
            OUT_FOR_DELIVERY,
            DELIVERED,
            EXCEPTION
        }

        public static Shipment shipment(OrderId orderId, Address destination, ShippingOption option) {
            return new Shipment(IdGenerator.generate("SHP"),
                                orderId,
                                IdGenerator.generate("TRK"),
                                option,
                                destination,
                                ShipmentStatus.LABEL_CREATED,
                                Instant.now()
                                       .plus(option.estimatedDelivery()
                                                   .duration()),
                                Instant.now());
        }

        public Shipment updateStatus(ShipmentStatus newStatus) {
            return new Shipment(shipmentId,
                                orderId,
                                trackingNumber,
                                shippingOption,
                                destination,
                                newStatus,
                                estimatedDelivery,
                                createdAt);
        }

        public String trackingUrl() {
            return "https://track.example.com/" + trackingNumber;
        }
    }

    record TrackingInfo(String trackingNumber,
                        Shipment.ShipmentStatus currentStatus,
                        Instant estimatedDelivery,
                        List<TrackingEvent> events) {
        public record TrackingEvent(Instant timestamp,
                                    String location,
                                    String description,
                                    Shipment.ShipmentStatus status) {}

        public static TrackingInfo trackingInfo(Shipment shipment) {
            var events = List.of(new TrackingEvent(shipment.createdAt(),
                                                   "Origin",
                                                   "Shipment created",
                                                   Shipment.ShipmentStatus.LABEL_CREATED));
            return new TrackingInfo(shipment.trackingNumber(), shipment.status(), shipment.estimatedDelivery(), events);
        }
    }

    // === Enums ===
    enum ShippingOption {
        STANDARD("Standard Shipping", timeSpan(5)
                                              .days(), BigDecimal.ZERO),
        EXPRESS("Express Shipping", timeSpan(2)
                                            .days(), new BigDecimal("9.99")),
        OVERNIGHT("Overnight Shipping", timeSpan(1)
                                                .days(), new BigDecimal("24.99")),
        SAME_DAY("Same Day Delivery", timeSpan(4)
                                              .hours(), new BigDecimal("49.99"));
        private final String displayName;
        private final TimeSpan estimatedDelivery;
        private final BigDecimal baseCost;
        ShippingOption(String displayName, TimeSpan estimatedDelivery, BigDecimal baseCost) {
            this.displayName = displayName;
            this.estimatedDelivery = estimatedDelivery;
            this.baseCost = baseCost;
        }
        public String displayName() {
            return displayName;
        }
        public TimeSpan estimatedDelivery() {
            return estimatedDelivery;
        }
        public Money cost() {
            return new Money(baseCost, Money.USD);
        }
    }

    // === Errors ===
    sealed interface FulfillmentError extends Cause {
        record ShipmentNotFound(String trackingNumber) implements FulfillmentError {
            @Override
            public String message() {
                return "Shipment not found: " + trackingNumber;
            }
        }

        record SameDayNotAvailable(String state) implements FulfillmentError {
            @Override
            public String message() {
                return "Same-day delivery not available in " + state;
            }
        }

        record InvalidDestination(String reason) implements FulfillmentError {
            @Override
            public String message() {
                return "Invalid shipping destination: " + reason;
            }
        }
    }

    // === Operations ===
    Promise<ShippingQuote> calculateShipping(CalculateShippingRequest request);

    Promise<Shipment> createShipment(CreateShipmentRequest request);

    Promise<TrackingInfo> trackShipment(TrackShipmentRequest request);

    // === Factory ===
    static FulfillmentService fulfillmentService() {
        record fulfillmentService(Map<String, Shipment> shipments) implements FulfillmentService {
            private static final Set<String> NO_SAME_DAY_STATES = Set.of("AK", "HI", "PR", "VI");
            private static final BigDecimal FREE_SHIPPING_THRESHOLD = BigDecimal.valueOf(100);

            fulfillmentService() {
                this(new ConcurrentHashMap<>());
            }

            @Override
            public Promise<ShippingQuote> calculateShipping(CalculateShippingRequest request) {
                var options = calculateAvailableOptions(request.items(), request.destination());
                return Promise.success(ShippingQuote.quote(options));
            }

            @Override
            public Promise<Shipment> createShipment(CreateShipmentRequest request) {
                if (request.shippingOption() == ShippingOption.SAME_DAY && NO_SAME_DAY_STATES.contains(request.shippingAddress()
                                                                                                              .state()
                                                                                                              .toUpperCase())) {
                    return new FulfillmentError.SameDayNotAvailable(request.shippingAddress()
                                                                           .state()).promise();
                }
                var shipment = Shipment.shipment(request.orderId(), request.shippingAddress(), request.shippingOption());
                shipments.put(shipment.trackingNumber(), shipment);
                return Promise.success(shipment);
            }

            @Override
            public Promise<TrackingInfo> trackShipment(TrackShipmentRequest request) {
                return Option.option(shipments.get(request.trackingNumber()))
                             .toResult(new FulfillmentError.ShipmentNotFound(request.trackingNumber()))
                             .map(TrackingInfo::trackingInfo)
                             .async();
            }

            private List<ShippingQuote.ShippingOptionQuote> calculateAvailableOptions(List<LineItem> items,
                                                                                      Address destination) {
                var itemValue = calculateItemValue(items);
                var isFreeShippingEligible = itemValue.compareTo(FREE_SHIPPING_THRESHOLD) >= 0;
                return Arrays.stream(ShippingOption.values())
                             .filter(option -> isOptionAvailable(option, destination))
                             .map(option -> createQuote(option, items, isFreeShippingEligible))
                             .toList();
            }

            private boolean isOptionAvailable(ShippingOption option, Address destination) {
                return option != ShippingOption.SAME_DAY || !NO_SAME_DAY_STATES.contains(destination.state()
                                                                                                    .toUpperCase());
            }

            private ShippingQuote.ShippingOptionQuote createQuote(ShippingOption option,
                                                                  List<LineItem> items,
                                                                  boolean freeShippingEligible) {
                var cost = calculateShippingCost(option, items, freeShippingEligible);
                var estimatedDelivery = Instant.now()
                                               .plus(option.estimatedDelivery()
                                                           .duration());
                return new ShippingQuote.ShippingOptionQuote(option, cost, estimatedDelivery);
            }

            private BigDecimal calculateItemValue(List<LineItem> items) {
                return items.stream()
                            .map(item -> BigDecimal.valueOf(item.quantity()
                                                                .value() * 50L))
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
            }

            private Money calculateShippingCost(ShippingOption option,
                                                List<LineItem> items,
                                                boolean freeShippingEligible) {
                if (option == ShippingOption.STANDARD && freeShippingEligible) {
                    return Money.ZERO_USD;
                }
                var baseCost = option.cost();
                var totalItems = items.stream()
                                      .mapToInt(i -> i.quantity()
                                                      .value())
                                      .sum();
                if (totalItems > 10) {
                    var surchargeAmount = BigDecimal.valueOf(totalItems - 10)
                                                    .multiply(BigDecimal.valueOf(0.5));
                    return baseCost.add(new Money(surchargeAmount, Money.USD))
                                   .or(baseCost);
                }
                return baseCost;
            }
        }
        return new fulfillmentService();
    }
}

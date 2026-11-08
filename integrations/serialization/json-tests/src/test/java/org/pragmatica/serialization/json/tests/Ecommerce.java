/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.serialization.json.tests;

import org.pragmatica.serialization.json.Json;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;

/// Shapes copied from `pragmatica/examples/ecommerce` (place-order, pricing, payment, fulfillment, shared).
///
/// Value objects (single-component records with a JBCT factory) travel unwrapped when nested, as their bare
/// component, so `Map<ProductId, ...>` keys are plain strings too; their factories run on decode.
public interface Ecommerce {
    record ProductId(String value) {
        public static Result<ProductId> productId(String value) {
            return value.isBlank()
                   ? Causes.cause("Invalid ProductId: " + value).result()
                   : Result.success(new ProductId(value));
        }
    }

    record CustomerId(String value) {
        public static Result<CustomerId> customerId(String value) {
            return value.isBlank()
                   ? Causes.cause("Invalid CustomerId: " + value).result()
                   : Result.success(new CustomerId(value));
        }
    }

    record OrderId(String value) {
        public static Result<OrderId> orderId(String value) {
            return value.isBlank()
                   ? Causes.cause("Invalid OrderId: " + value).result()
                   : Result.success(new OrderId(value));
        }
    }

    record Quantity(int value) {
        public static Result<Quantity> quantity(int value) {
            return value <= 0
                   ? Causes.cause("Invalid Quantity: " + value).result()
                   : Result.success(new Quantity(value));
        }
    }

    record Money(BigDecimal amount, Currency currency) {}

    record LineItem(ProductId productId, Quantity quantity) {}

    record RawLineItem(String productId, int quantity) {}

    record Address(String street, String city, String state, String postalCode, String country) {}

    record RawAddress(String street, String city, String state, String postalCode, String country) {}

    record RawPaymentMethod(String cardNumber, String expiryMonth, String expiryYear, String cvv, String cardholderName) {}

    enum ShippingOption {
        STANDARD,
        EXPRESS,
        OVERNIGHT,
        SAME_DAY
    }

    enum PaymentStatus {
        AUTHORIZED,
        CAPTURED,
        PENDING_CAPTURE
    }

    enum ShipmentStatus {
        PENDING,
        LABEL_CREATED,
        PICKED_UP,
        IN_TRANSIT,
        OUT_FOR_DELIVERY,
        DELIVERED,
        EXCEPTION
    }

    enum OrderStatus {
        CONFIRMED,
        PROCESSING,
        SHIPPED,
        DELIVERED,
        CANCELLED
    }

    @Json
    record PlaceOrderRequest(String customerId,
                             List<RawLineItem> items,
                             RawAddress shippingAddress,
                             RawPaymentMethod paymentMethod,
                             ShippingOption shippingOption,
                             Option<String> discountCode) {}

    record LinePrice(ProductId productId, Money unitPrice, int quantity, Money lineTotal) {}

    record PriceBreakdown(Map<ProductId, LinePrice> linePrices,
                          Money subtotal,
                          Money discountAmount,
                          Money taxAmount,
                          Money shippingCost,
                          Money total) {}

    record PaymentResult(String transactionId,
                         OrderId orderId,
                         Money amount,
                         String cardType,
                         String maskedCard,
                         Instant processedAt,
                         PaymentStatus status) {}

    record Shipment(String shipmentId,
                    OrderId orderId,
                    String trackingNumber,
                    ShippingOption shippingOption,
                    Address destination,
                    ShipmentStatus status,
                    Instant estimatedDelivery,
                    Instant createdAt) {}

    /// `payment/PaymentService.java:37`. Sent with `partialAmount` absent: a producer omitting nulls.
    @Json
    record RefundRequest(String transactionId, Option<Money> partialAmount, String reason) {}

    @Json
    record OrderConfirmation(OrderId orderId,
                             CustomerId customerId,
                             List<LineItem> items,
                             PriceBreakdown pricing,
                             PaymentResult payment,
                             Shipment shipment,
                             OrderStatus status,
                             Instant createdAt) {}
}

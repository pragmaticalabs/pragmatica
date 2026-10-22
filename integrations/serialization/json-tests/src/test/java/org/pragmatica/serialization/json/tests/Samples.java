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

import org.pragmatica.lang.Option;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.IntStream;

import static org.pragmatica.serialization.json.tests.Catalog.ItemListV2;
import static org.pragmatica.serialization.json.tests.Catalog.ItemV2;
import static org.pragmatica.serialization.json.tests.Ecommerce.*;
import static org.pragmatica.serialization.json.tests.Ticketing.BuyRequest;
import static org.pragmatica.serialization.json.tests.Ticketing.BuyResponse;

/// Deterministic sample payloads shared by tests and benchmarks. Sizes follow the sources:
/// catalog seeds 6 items; ecommerce k6 load tests send 1-4 line items.
public interface Samples {
    Currency USD = Currency.getInstance("USD");

    static BuyRequest buyRequest() {
        return new BuyRequest("6f1c2a9e-3b7d-4c51-9a0e-2d8f5b6c7e11",
                              "a3e9b7c1-5d2f-4e8a-b6c0-9f1d3e5a7b20",
                              "c7d1e3f5-a9b2-4c6d-8e0f-1a3b5c7d9e42",
                              "PREMIUM");
    }

    static BuyResponse buyResponse() {
        return new BuyResponse("b1f3d5e7-9a2c-4e6f-8b0d-2c4e6a8b0d13",
                               "t2e4f6a8-0b1c-4d3e-9f5a-7b9c1d3e5f24",
                               "c7d1e3f5-a9b2-4c6d-8e0f-1a3b5c7d9e42",
                               "rcpt-20261017-000184533",
                               12_950,
                               "EUR");
    }

    static ItemListV2 catalog() {
        return catalog(6);
    }

    static ItemListV2 catalog(int size) {
        var names = List.of("Mechanical Keyboard", "Café Crème Grinder", "12\" Vinyl Record", "USB-C Hub 7-in-1",
                            "Noise Cancelling Headphones", "Ergonomic Mouse");
        var tags = List.of(List.of("electronics", "peripherals"), List.of("kitchen", "coffee"), List.of("music", "analog"),
                           List.of("electronics", "accessories"), List.of("audio", "travel"), List.of("peripherals", "office"));
        var items = IntStream.range(0, size)
                             .mapToObj(i -> new ItemV2(1000 + i, names.get(i % 6), 1999 + 1250L * i, "USD", tags.get(i % 6)))
                             .toList();
        return new ItemListV2(items);
    }

    static PlaceOrderRequest placeOrderRequest() {
        return new PlaceOrderRequest("cust-7f3a91",
                                     List.of(new RawLineItem("PROD-ABC123", 2),
                                             new RawLineItem("PROD-XYZ789", 1),
                                             new RawLineItem("PROD-DEF456", 4)),
                                     new RawAddress("1600 Amphitheatre Parkway", "Mountain View", "CA", "94043", "US"),
                                     new RawPaymentMethod("4111111111111111", "12", "2028", "123", "Jordan Q. Example"),
                                     ShippingOption.EXPRESS,
                                     Option.some("SAVE10"));
    }

    static OrderConfirmation orderConfirmation() {
        var orderId = new OrderId("ord-2026-10-17-58f1c3");
        var items = List.of(lineItem("PROD-ABC123", 2), lineItem("PROD-XYZ789", 1), lineItem("PROD-DEF456", 4));
        var linePrices = new LinkedHashMap<ProductId, LinePrice>();
        linePrices.put(new ProductId("PROD-ABC123"), linePrice("PROD-ABC123", "29.99", 2));
        linePrices.put(new ProductId("PROD-XYZ789"), linePrice("PROD-XYZ789", "149.00", 1));
        linePrices.put(new ProductId("PROD-DEF456"), linePrice("PROD-DEF456", "4.25", 4));
        var pricing = new PriceBreakdown(linePrices, usd("225.98"), usd("22.60"), usd("18.08"), usd("9.99"), usd("231.45"));
        var payment = new PaymentResult("txn-9c2e7a41b3", orderId, usd("231.45"), "VISA", "************1111",
                                        Instant.parse("2026-10-17T09:41:27.512Z"), PaymentStatus.AUTHORIZED);
        var shipment = new Shipment("shp-4d8e2f", orderId, "1Z999AA10123456784", ShippingOption.EXPRESS,
                                    new Address("1600 Amphitheatre Parkway", "Mountain View", "CA", "94043", "US"),
                                    ShipmentStatus.LABEL_CREATED, Instant.parse("2026-10-19T17:00:00Z"),
                                    Instant.parse("2026-10-17T09:41:27.618Z"));
        return new OrderConfirmation(orderId, new CustomerId("cust-7f3a91"), new ArrayList<>(items), pricing, payment, shipment,
                                     OrderStatus.CONFIRMED, Instant.parse("2026-10-17T09:41:27.640Z"));
    }

    static RefundRequest refundRequest() {
        return new RefundRequest("txn-9c2e7a41b3", Option.none(), "Item arrived damaged; customer returned it within 30 days");
    }

    /// SYNTHETIC: no example carries doubles. Full-precision values, as Double.toString writes them.
    static Telemetry.Series telemetry() {
        var random = new java.util.Random(17);
        var samples = IntStream.range(0, 50)
                               .mapToObj(i -> new Telemetry.Sample("http.server.latency", 1_760_694_087_000L + 1000L * i,
                                                                   random.nextDouble() * 250, random.nextDouble() * 40,
                                                                   random.nextGaussian() * 1e-3 + 0.731))
                               .toList();
        return new Telemetry.Series("node-7", samples);
    }

    private static LineItem lineItem(String product, int quantity) {
        return new LineItem(new ProductId(product), new Quantity(quantity));
    }

    private static LinePrice linePrice(String product, String unit, int quantity) {
        var unitPrice = new BigDecimal(unit);
        return new LinePrice(new ProductId(product), usd(unit), quantity,
                             new Money(unitPrice.multiply(BigDecimal.valueOf(quantity)), USD));
    }

    private static Money usd(String amount) {
        return new Money(new BigDecimal(amount), USD);
    }
}

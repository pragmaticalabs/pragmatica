// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.testkit.fixture;

import org.pragmatica.aether.resource.db.PgSql;
import org.pragmatica.aether.resource.db.PgSqlConnector;
import org.pragmatica.aether.resource.db.RowMapper.RowAccessor;
import org.pragmatica.aether.resource.http.Http;
import org.pragmatica.aether.resource.http.HttpClient;
import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;


/// Focused fixture slice for the test kit's own acceptance test: a `@PgSql` store, an `@Http`
/// dependency, and a pub-sub publisher — the acceptance scenario from the issue, small enough to run
/// through the real generated `OrderIntakeFactory`.
@Slice
public interface OrderIntake {
    String INSERT_ORDER = "INSERT INTO orders (sku, qty) VALUES (?, ?) RETURNING id";

    record PlaceRequest(String sku, int qty) {}

    record PlaceResponse(long orderId, String status) {}

    record OrderPlaced(long orderId, String sku, int qty) {}

    Promise<PlaceResponse> place(PlaceRequest request);

    static Result<Long> orderId(RowAccessor row) {
        return row.getLong("id");
    }

    static OrderIntake orderIntake(@PgSql PgSqlConnector store,
                                   @Http HttpClient inventory,
                                   @OrderEvents Publisher<OrderPlaced> events) {
        record orderIntake(PgSqlConnector store, HttpClient inventory, Publisher<OrderPlaced> events) implements OrderIntake {
            @Override
            public Promise<PlaceResponse> place(PlaceRequest request) {
                return inventory.get("/stock/" + request.sku())
                                .flatMap(_ -> store.queryOne(INSERT_ORDER, OrderIntake::orderId, request.sku(), request.qty()))
                                .flatMap(orderId -> publishAndRespond(orderId, request));
            }

            private Promise<PlaceResponse> publishAndRespond(long orderId, PlaceRequest request) {
                return events.publish(new OrderPlaced(orderId, request.sku(), request.qty()))
                             .map(_ -> new PlaceResponse(orderId, "ACCEPTED"));
            }
        }

        return new orderIntake(store, inventory, events);
    }
}

// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package com.example.durabletopicstep;

import com.example.durabletopic.OnOrderPlaced;
import com.example.durabletopic.OrderPlaced;

import org.pragmatica.aether.slice.topic.MessageContext;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// Plain-interface step carrying a context-carrying subscriber (#386 D5), so the transitive
/// discovery path is exercised as well as the direct one: the processor finds the annotated method
/// on a dependency interface, reports and dispatches it under the step-qualified name
/// `listenerOnOrderPlaced`, and must apply the same durable-topic rule and emit the same unpacking
/// adapter it does for a directly-declared handler.
public interface OrderAuditListener {
    @OnOrderPlaced
    Promise<Unit> onOrderPlaced(OrderPlaced event, MessageContext context);

    static OrderAuditListener orderAuditListener() {
        return (_, _) -> Promise.unitPromise();
    }
}

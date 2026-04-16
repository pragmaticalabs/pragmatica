// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// Functional interface for publishing messages to a topic.
///
/// Provisioned via `@ResourceQualifier(type = Publisher.class, config = "messaging.xxx")`
/// on a slice factory method parameter. The runtime creates a `TopicPublisher` that
/// routes messages to subscriber methods registered for the same topic.
///
/// Example:
/// ```{@code
/// @ResourceQualifier(type = Publisher.class, config = "messaging.orders")
/// @Retention(RUNTIME) @Target(PARAMETER)
/// public @interface OrderPublisher {}
///
/// static OrderService orderService(@OrderPublisher Publisher<OrderEvent> pub) { ... }
/// }```
@FunctionalInterface public interface Publisher<T> {
    Promise<Unit> publish(T message);
}

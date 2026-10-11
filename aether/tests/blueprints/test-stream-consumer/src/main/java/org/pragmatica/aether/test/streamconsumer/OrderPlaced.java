// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.test.streamconsumer;

/// An APPLICATION-DEFINED stream event type — the whole point of this fixture (#526).
///
/// Every other stream in the blueprint corpus carries `String`, a type the node-wide codec already
/// knows, which is exactly why nothing caught the defect where stream resources were handed the
/// node codec instead of the deployed slice's. This record is known ONLY to the slice's own codec
/// (the slice processor registers it from the `StreamPublisher<OrderPlaced>` type argument), so a
/// publish that reaches the wrong codec fails with "No codec registered for class" rather than
/// quietly passing.
public record OrderPlaced(String orderId, String customer, int amount) {}

// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.durabletopic;

/// Payload of the `order-events` durable topic fixture.
public record OrderPlaced(String orderId, long amount) {}

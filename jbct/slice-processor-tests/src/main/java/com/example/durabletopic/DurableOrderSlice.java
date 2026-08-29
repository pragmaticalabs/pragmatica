// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package com.example.durabletopic;

import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.aether.slice.topic.MessageContext;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// Fixture for the #386 D5 context-carrying subscriber shape: a handler declaring
/// `(T event, MessageContext context)` on a topic whose `resources.toml` section declares
/// `durability = "durable"`.
///
/// The fixture's value is that it compiles for real. The generated factory unpacks a
/// `ContextualEvent` and invokes this two-argument method, so a regression in that adapter is a
/// javac failure in this module rather than a silently wrong string in an assertion.
@Slice
public interface DurableOrderSlice {
    @OnOrderPlaced
    Promise<Unit> onOrderPlaced(OrderPlaced event, MessageContext context);

    static DurableOrderSlice durableOrderSlice() {
        return (_, _) -> Promise.unitPromise();
    }
}

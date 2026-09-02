// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.test.durabletopic;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.pragmatica.aether.slice.Subscriber;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;


/// Subscriber qualifier binding the ALWAYS-FAILING group to `poison-events`. Its handler returns a
/// failed promise every time, which is the runtime's ack signal inverted — the event can never be
/// acked, so it exhausts the bounded retries and lands in the topic's dead-letter stream attributed
/// to THIS group only.
@ResourceQualifier(type = Subscriber.class, config = "poison-events")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PoisonFailingSubscriber {}

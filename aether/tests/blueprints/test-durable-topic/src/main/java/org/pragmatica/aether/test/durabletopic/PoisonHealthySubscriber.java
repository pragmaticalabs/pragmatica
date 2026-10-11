// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.test.durabletopic;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.pragmatica.aether.slice.Subscriber;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;


/// Subscriber qualifier binding the HEALTHY group to `poison-events`. It shares the topic with
/// [PoisonFailingSubscriber] and must be completely unaffected by that group's failures — same
/// events, separate cursors, separate retry budgets.
@ResourceQualifier(type = Subscriber.class, config = "poison-events")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PoisonHealthySubscriber {}

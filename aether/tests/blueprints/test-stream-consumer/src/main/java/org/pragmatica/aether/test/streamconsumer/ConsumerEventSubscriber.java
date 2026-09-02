// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.test.streamconsumer;

import org.pragmatica.aether.slice.StreamSubscriber;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/// Declarative stream-consumer qualifier (#488). Applied to a slice METHOD, it declares that the
/// runtime should deliver `streams.consumer-events` events to that method. The annotated method must
/// take exactly one parameter (the event type, or `List<eventType>` for batch mode) and return
/// `Promise<Unit>` — enforced by `MethodModel.validateStreamSubscriptions`.
@ResourceQualifier(type = StreamSubscriber.class, config = "streams.consumer-events") @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD) public@interface ConsumerEventSubscriber {}

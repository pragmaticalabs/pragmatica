// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.test.streamconsumer;

import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/// Publisher qualifier for the APPLICATION-TYPED stream (#526). Distinct from
/// [EventStreamPublisher] so the `String` stream that proves declarative consumption (#488) and the
/// `OrderPlaced` stream that proves codec scoping stay independent — a regression in one cannot
/// mask the other.
@ResourceQualifier(type = StreamPublisher.class, config = "streams.order-events") @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.PARAMETER) public@interface OrderStreamPublisher {}

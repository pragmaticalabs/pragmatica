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


/// Declarative consumer qualifier for the APPLICATION-TYPED stream (#526). The annotated method
/// takes an [OrderPlaced], so delivery only works if BOTH ends of the stream resolve the slice's
/// own codec: the publisher to encode and the reader to decode.
@ResourceQualifier(type = StreamSubscriber.class, config = "streams.order-events") @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD) public@interface OrderEventSubscriber {}

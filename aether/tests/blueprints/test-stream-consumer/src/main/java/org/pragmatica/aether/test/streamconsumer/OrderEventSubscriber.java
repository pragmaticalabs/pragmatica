// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.test.streamconsumer;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.pragmatica.aether.slice.StreamSubscriber;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;


/// Declarative consumer qualifier for the APPLICATION-TYPED stream (#526). The annotated method
/// takes an [OrderPlaced], so delivery only works if BOTH ends of the stream resolve the slice's
/// own codec: the publisher to encode and the reader to decode.
@ResourceQualifier(type = StreamSubscriber.class, config = "streams.order-events")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface OrderEventSubscriber {}

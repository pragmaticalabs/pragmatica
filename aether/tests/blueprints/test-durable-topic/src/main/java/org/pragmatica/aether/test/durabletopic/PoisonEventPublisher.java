// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.test.durabletopic;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;


/// Publisher qualifier for the durable `poison-events` topic — the topic whose events one of the two
/// bound consumer groups can never process.
@ResourceQualifier(type = Publisher.class, config = "poison-events")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface PoisonEventPublisher {}

// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.terra.example.events;

import java.lang.annotation.*;

import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.aether.slice.topic.Topic;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


@Slice
public interface Events {
    Topic<String> EVENTS = Topic.of("events", String.class);

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    @ResourceQualifier(type = Publisher.class, config = "EVENTS")
    @interface Outgoing {}

    Promise<Unit> send(String event);

    static Promise<Events> events(@Outgoing Publisher<String> publisher) {
        return Promise.success(publisher::publish);
    }
}

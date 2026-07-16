// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.message;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.MessageRouter;


public record RouteGroup(String name, List<MessageRouter.Entry<?>> entries) {
    public static Builder routeGroup(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private final List<MessageRouter.Entry<?>> entries = new ArrayList<>();

        private Builder(String name) {
            this.name = name;
        }

        public <M extends Message> Builder route(Class<M> type, Consumer<M> handler) {
            entries.add(MessageRouter.Entry.route(type, handler));

            return this;
        }

        @SafeVarargs
        public final <M extends Message> Builder fanOut(Class<M> type, Consumer<M>... handlers) {
            for (var handler : handlers) {
                entries.add(MessageRouter.Entry.route(type, handler));
            }

            return this;
        }

        public Builder entry(MessageRouter.Entry<?> entry) {
            entries.add(entry);

            return this;
        }

        public Builder merge(RouteGroup other) {
            entries.addAll(other.entries());

            return this;
        }

        public RouteGroup build() {
            return new RouteGroup(name, List.copyOf(entries));
        }
    }

    public List<MessageRouter.Entry<?>> toList() {
        return entries;
    }
}

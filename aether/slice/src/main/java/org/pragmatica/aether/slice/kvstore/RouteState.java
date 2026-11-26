// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.pragmatica.serialization.Codec;


/// The state of one declared route, the state of an [AetherValue.DeclaredRouteValue] (#3389). A lookup is one exhaustive
/// switch over these three: [Declared] answers "declared, not published yet", [Published] "a node published it", [Failed] "the
/// deploy that declared it failed terminally".
///
/// A `@Codec` sealed interface of its own, not nested in [AetherValue]: the codec generator reaches the permitted variants of a
/// top-level sealed interface, and the record component typed by it is written with the runtime variant's tag.
@Codec
public sealed interface RouteState {
    /// The route is declared by a blueprint and no node has published it.
    record Declared() implements RouteState {
        public static Declared declared() {
            return new Declared();
        }
    }

    /// A node published the route.
    record Published() implements RouteState {
        public static Published published() {
            return new Published();
        }
    }

    /// The deploy that declared the route failed terminally, for `reason`.
    record Failed(String reason) implements RouteState {
        public static Failed failed(String reason) {
            return new Failed(reason);
        }
    }
}

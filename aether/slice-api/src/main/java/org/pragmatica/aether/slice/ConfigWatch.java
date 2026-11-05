// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice;

/// What a slice's loading context needs to register `Watched` subscriptions: the node's feed and the declaring
/// slice artifact's string form, as an explicit value (not the optional `sliceId`).
public record ConfigWatch(ConfigChangeFeed feed, String owner) {
    public static ConfigWatch configWatch(ConfigChangeFeed feed, String owner) {
        return new ConfigWatch(feed, owner);
    }
}

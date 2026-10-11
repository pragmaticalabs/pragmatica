// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.pragmatica.lang.Option;


public record CoreTopology(Option<Integer> min, Option<Integer> max, int maxUnavailable) {
    public static CoreTopology coreTopology(Option<Integer> min, Option<Integer> max, int maxUnavailable) {
        return new CoreTopology(min, max, maxUnavailable);
    }

    public static CoreTopology defaultCoreTopology() {
        return new CoreTopology(Option.none(), Option.none(), 1);
    }
}

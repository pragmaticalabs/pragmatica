// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.terra.example.shop;

import org.pragmatica.aether.example.catalog.Catalog;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;
import org.pragmatica.terra.example.counter.Counter;
import org.pragmatica.terra.example.events.Events;


@Slice
public interface Shop {
    Promise<Integer> visit(String key);

    static Shop shop(Catalog catalog, Counter counter, Events events) {
        return key -> catalog.listV2(new Catalog.ListRequest())
                             .flatMap(_ -> events.send(key))
                             .flatMap(_ -> counter.value(key));
    }
}

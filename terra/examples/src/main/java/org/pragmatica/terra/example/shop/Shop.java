// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
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

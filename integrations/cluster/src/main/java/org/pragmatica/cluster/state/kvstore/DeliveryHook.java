// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import org.pragmatica.lang.Unit;


/// Called by a store's delivery loop before it delivers the batch numbered `deliverySeq`, on the delivering thread. The
/// production hook does nothing; [HeldDelivery] is the test hook that holds the dispatcher before a chosen batch (design
/// §5, "Test hook"), which every probe that must not pass by race needs.
@FunctionalInterface
public interface DeliveryHook {
    Unit beforeDelivery(long deliverySeq);

    static DeliveryHook none() {
        return _ -> Unit.unit();
    }
}

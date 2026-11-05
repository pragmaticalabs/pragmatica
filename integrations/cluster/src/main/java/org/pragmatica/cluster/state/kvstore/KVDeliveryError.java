// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.utils.Causes;


/// Failures of a store's notification delivery.
public sealed interface KVDeliveryError extends Cause {
    /// The write committed and was applied, but the store closed (teardown, or a dispatcher failure) before its
    /// notifications were delivered: the caller cannot know whether subscribers saw it. Never "not applied" (design §3.2,
    /// the `CommittedOutcomeUnknown` family); reconcile before retrying.
    record DeliveryOutcomeUnknown(String store, String reason, String message) implements KVDeliveryError {
        static final Fn2<DeliveryOutcomeUnknown, String, String> FACTORY = Causes.forTwoValues("Store %s closed before delivery (%s); the write committed, its outcome is unknown, reconcile before retrying",
                                                                                               DeliveryOutcomeUnknown::new);
    }
}

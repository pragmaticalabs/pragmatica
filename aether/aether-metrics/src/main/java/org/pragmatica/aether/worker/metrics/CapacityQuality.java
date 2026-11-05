// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.worker.metrics;

import org.pragmatica.serialization.Codec;


/// How complete a capacity sample is (spec 7.5). `THROUGHPUT_ONLY` samples were taken without the arrival
/// counters, so they cannot promote a predictor to Active.
@Codec
public enum CapacityQuality {
    FULL,
    THROUGHPUT_ONLY,
    /// A quality this node does not know (decoded from a newer producer). Required last by the codec processor (#964);
    /// consumers treat it like `THROUGHPUT_ONLY`: it never promotes a predictor.
    UNKNOWN
}

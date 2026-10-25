// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import org.pragmatica.serialization.Codec;


/// One `(slot, batchId)` pair of an effect-bearing entry. The batch id is content-derived (a hash of the batch's commands).
@Codec
public record AnchorPair(long slot, String batchId) {}

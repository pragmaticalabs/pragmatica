// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.lang.Option;


/// What a capacity request tells [SourceRanking] about itself (rc6 f5c §2.1).
///
/// `deadlineMs` is an absolute epoch-millisecond deadline, absent when the request has none (the S3 stand-in,
/// §2.2). `evaluatedAtMs` is the caller's injected-clock reading: [SourceRanking] never reads a clock itself.
/// `spotTolerant` is `false` unless the caller opts the request into spot capacity (§2.5).
public record SelectionRequest(Option<Long> deadlineMs, long evaluatedAtMs, boolean spotTolerant) {
    public static SelectionRequest selectionRequest(Option<Long> deadlineMs, long evaluatedAtMs, boolean spotTolerant) {
        return new SelectionRequest(deadlineMs, evaluatedAtMs, spotTolerant);
    }
}

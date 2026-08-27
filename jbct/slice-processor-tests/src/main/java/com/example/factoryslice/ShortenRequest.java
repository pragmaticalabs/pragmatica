// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package com.example.factoryslice;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;


/// Pure-body request record that declares a validating factory (#605). Jackson builds it through the
/// canonical constructor, so the generated route re-validates by decomposing the record through its
/// accessors back into `shortenRequest` before the delegate is reached.
public record ShortenRequest(String url, int ttlSeconds) {
    private static final Cause NON_POSITIVE_TTL = Causes.cause("ttlSeconds must be positive");

    public static Result<ShortenRequest> shortenRequest(String url, int ttlSeconds) {
        return Result.all(Verify.ensure(url, Verify.Is::present),
                          Verify.ensure(ttlSeconds, Verify.Is::positive, NON_POSITIVE_TTL))
                     .map(ShortenRequest::new);
    }
}

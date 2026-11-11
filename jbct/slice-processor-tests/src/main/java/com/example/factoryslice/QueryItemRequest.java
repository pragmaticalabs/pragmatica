// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.factoryslice;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;


/// Query-only request record whose factory rejects a blank tag (#3019).
public record QueryItemRequest(String tag) {
    public static Result<QueryItemRequest> queryItemRequest(String tag) {
        return Result.all(Verify.ensure(tag, Verify.Is::present))
                     .map(QueryItemRequest::new);
    }
}

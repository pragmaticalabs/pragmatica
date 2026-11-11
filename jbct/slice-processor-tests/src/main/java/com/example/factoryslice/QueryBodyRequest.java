// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.factoryslice;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;


/// Query + body request record whose factory rejects blank components (#3019).
public record QueryBodyRequest(String tag, String name) {
    public static Result<QueryBodyRequest> queryBodyRequest(String tag, String name) {
        return Result.all(Verify.ensure(tag, Verify.Is::present),
                          Verify.ensure(name, Verify.Is::present))
                     .map(QueryBodyRequest::new);
    }
}

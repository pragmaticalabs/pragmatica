// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.factoryslice;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;


/// Path + query request record whose factory rejects blank components (#3019).
public record PathQueryRequest(String code, String tag) {
    public static Result<PathQueryRequest> pathQueryRequest(String code, String tag) {
        return Result.all(Verify.ensure(code, Verify.Is::present),
                          Verify.ensure(tag, Verify.Is::present))
                     .map(PathQueryRequest::new);
    }
}

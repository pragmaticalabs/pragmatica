// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.factoryslice;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;


/// Query + body request record (#3019). Its factory rejects a missing or blank tag and blank string components, so a
/// request carrying one is answered 400 at the HTTP boundary instead of reaching the slice.
public record QueryBodyRequest(Option<String> tag, String name) {
    private static final Cause BLANK_TAG = Causes.cause("tag must be present and not blank");

    public static Result<QueryBodyRequest> queryBodyRequest(Option<String> tag, String name) {
        return Result.all(requiredTag(tag),
                          Verify.ensure(name, Verify.Is::present))
                     .map((_, validName) -> new QueryBodyRequest(tag, validName));
    }

    private static Result<String> requiredTag(Option<String> tag) {
        return tag.filter(Verify.Is::present)
                  .toResult(BLANK_TAG);
    }
}

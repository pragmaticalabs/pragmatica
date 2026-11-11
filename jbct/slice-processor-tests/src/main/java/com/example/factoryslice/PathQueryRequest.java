// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.factoryslice;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;


/// Path + query request record (#3019). Its factory rejects a missing or blank tag and blank string components, so a
/// request carrying one is answered 400 at the HTTP boundary instead of reaching the slice.
public record PathQueryRequest(String code, Option<String> tag) {
    private static final Cause BLANK_TAG = Causes.cause("tag must be present and not blank");

    public static Result<PathQueryRequest> pathQueryRequest(String code, Option<String> tag) {
        return Result.all(Verify.ensure(code, Verify.Is::present),
                          requiredTag(tag))
                     .map((validCode, _) -> new PathQueryRequest(validCode, tag));
    }

    private static Result<String> requiredTag(Option<String> tag) {
        return tag.filter(Verify.Is::present)
                  .toResult(BLANK_TAG);
    }
}

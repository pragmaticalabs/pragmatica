// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.pathbody;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;


/// A path + body request with a validating factory that refuses a missing slug: the body alone never carries the
/// slug, so the factory must judge only the merged value, never the decoded body.
public record RenameArticle(String slug, String title) {
    public static Result<RenameArticle> renameArticle(String slug, String title) {
        return Result.all(Verify.ensure(slug, Verify.Is::present),
                          Verify.ensure(title, Verify.Is::present))
                     .map(RenameArticle::new);
    }
}

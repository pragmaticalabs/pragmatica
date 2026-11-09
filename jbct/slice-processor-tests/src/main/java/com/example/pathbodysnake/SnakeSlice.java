// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.pathbodysnake;

import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;


/// Test slice for #2957 under `[json] naming = "snake_case"`: a merged route whose path-bound component's Java name
/// (`articleSlug`) differs from its wire name (`article_slug`).
@Slice
public interface SnakeSlice {
    Promise<SnakeEcho> rename(SnakeRename request);

    static SnakeSlice snakeSlice() {
        return request -> Promise.success(new SnakeEcho("slug=" + request.articleSlug() + ",text=" + request.bodyText()));
    }
}

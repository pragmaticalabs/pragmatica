// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.pathbody;

import org.pragmatica.lang.Option;


/// The Conduit `POST /articles/{slug}/comments` request (#2957): `slug` comes from the path, `comment` from the body.
public record AddComment(String slug, NewComment comment) {
    public record NewComment(Option<String> body) {}
}

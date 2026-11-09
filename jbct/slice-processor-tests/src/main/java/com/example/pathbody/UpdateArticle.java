// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.pathbody;

import org.pragmatica.lang.Patch;


/// The Conduit `PUT /articles/{slug}` request (#2957): `slug` comes from the path, the partial update from the body.
public record UpdateArticle(String slug, Changes article) {
    public record Changes(Patch<String> title, Patch<String> description) {}
}

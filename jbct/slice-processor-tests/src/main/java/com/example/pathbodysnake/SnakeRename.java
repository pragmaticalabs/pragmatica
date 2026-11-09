// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.pathbodysnake;

/// A merged request whose path-bound component has a camel-case Java name, so its Java name and its wire name differ.
public record SnakeRename(String articleSlug, String bodyText) {}

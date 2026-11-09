// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.pathbody;

/// Bound from the path by one route and decoded whole by another, the merged route declared first.
public record MergedFirst(String id, String text) {}

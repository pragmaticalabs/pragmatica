// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.patch;

import java.util.List;

import org.pragmatica.lang.Patch;


/// A `Patch` as a list element has no missing state: the generator refuses it (the route falls back to Jackson), and
/// Jackson refuses it too.
public record TagsUpdate(List<Patch<String>> tags) {}

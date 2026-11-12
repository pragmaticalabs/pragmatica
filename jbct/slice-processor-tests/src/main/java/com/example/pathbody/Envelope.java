// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.pathbody;

import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Patch;


/// A body that nests records the slice also binds from the path, directly and through every container kind.
public record Envelope(NestedNote inner,
                       Option<OptNote> opt,
                       Patch<PatchNote> patched,
                       List<ListNote> listed,
                       Map<String, MapNote> mapped) {}

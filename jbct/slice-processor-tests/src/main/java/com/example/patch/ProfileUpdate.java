// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.patch;

import org.pragmatica.lang.Patch;


/// A partial update of a [Profile] (#2884): a field left out keeps its value, `null` clears it, a value replaces it.
/// Decoded by the routes holder's generated codec.
public record ProfileUpdate(Patch<String> nickname, Patch<Integer> age) {}

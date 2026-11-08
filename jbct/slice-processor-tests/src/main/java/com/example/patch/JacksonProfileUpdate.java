// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.patch;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.pragmatica.lang.Patch;


/// The same partial update, kept on the Jackson fallback by its Jackson annotation: the generator refuses any type a
/// Jackson annotation shapes, so this body is decoded by the slice's mapper.
public record JacksonProfileUpdate(@JsonProperty("nickname") Patch<String> nickname, Patch<Integer> age) {}

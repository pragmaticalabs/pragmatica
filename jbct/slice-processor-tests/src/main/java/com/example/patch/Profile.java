// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.patch;

import org.pragmatica.lang.Option;


/// The stored profile a PUT-style partial update patches: nullable fields are `Option`.
public record Profile(String name, Option<String> nickname, Option<Integer> age) {}

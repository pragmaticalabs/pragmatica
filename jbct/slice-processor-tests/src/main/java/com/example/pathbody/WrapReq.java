// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.pathbody;

/// A request record that only a response record nests: the response root does not relax it, the nesting keeps it strict.
public record WrapReq(String id, String text) {}

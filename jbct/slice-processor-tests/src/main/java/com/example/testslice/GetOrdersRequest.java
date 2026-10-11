// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.testslice;

import org.pragmatica.lang.Option;


public record GetOrdersRequest(Long userId, Option<String> status, Option<Integer> limit) {}

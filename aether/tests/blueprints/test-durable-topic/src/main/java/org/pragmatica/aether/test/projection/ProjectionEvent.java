// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.test.projection;

/// One digit of the order-sensitive fold: the model after seq 1..6 is `123456`.
public record ProjectionEvent(int seq) {}

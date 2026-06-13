// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

public enum CacheStrategy {
    CACHE_ASIDE,
    READ_THROUGH,
    WRITE_THROUGH,
    WRITE_BACK,
    WRITE_AROUND
}

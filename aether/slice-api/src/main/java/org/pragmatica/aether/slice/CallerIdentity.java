// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice;

/// The identity of the caller of an inter-slice call (#2157), as the edge validated it. Implemented by the HTTP layer's
/// `SecurityContext`, which this module cannot name. It is IDENTITY data for slice logic and audit, never an
/// authorization decision: route security on the receiving node never reads it.
public interface CallerIdentity {}

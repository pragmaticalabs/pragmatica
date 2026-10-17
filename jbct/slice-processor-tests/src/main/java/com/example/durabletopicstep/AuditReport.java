// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.durabletopicstep;

/// Result side of the [OrderAuditSlice] host.
public record AuditReport(long auditedCount) {}

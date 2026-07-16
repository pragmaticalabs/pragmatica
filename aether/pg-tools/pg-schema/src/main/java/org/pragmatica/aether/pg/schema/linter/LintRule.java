// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.schema.linter;

import java.util.List;

import org.pragmatica.aether.pg.schema.event.SchemaEvent;
import org.pragmatica.aether.pg.schema.model.Schema;


public interface LintRule {
    String id();
    String description();
    LintDiagnostic.Severity defaultSeverity();
    List<LintDiagnostic> check(SchemaEvent event, Schema schemaBefore);
}

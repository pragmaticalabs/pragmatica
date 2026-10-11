// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
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

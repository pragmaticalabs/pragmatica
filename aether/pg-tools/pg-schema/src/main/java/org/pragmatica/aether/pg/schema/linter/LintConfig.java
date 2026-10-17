// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.pg.schema.linter;

import java.util.Map;
import java.util.Set;


public record LintConfig(Set<String> disabledRules, Map<String, LintDiagnostic.Severity> severityOverrides) {
    public static LintConfig defaults() {
        return new LintConfig(Set.of(), Map.of());
    }

    public boolean isEnabled(String ruleId) {
        return ! disabledRules.contains(ruleId);
    }

    public LintDiagnostic.Severity severity(String ruleId, LintDiagnostic.Severity defaultSeverity) {
        return severityOverrides.getOrDefault(ruleId, defaultSeverity);
    }
}

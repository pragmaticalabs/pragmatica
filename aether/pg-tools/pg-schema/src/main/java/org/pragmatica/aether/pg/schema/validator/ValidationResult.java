// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.pg.schema.validator;

import java.util.List;


public record ValidationResult(List<ValidationError> errors) {
    public static ValidationResult empty() {
        return new ValidationResult(List.of());
    }

    public boolean isValid() {
        return errors.isEmpty();
    }

    public boolean hasErrors() {
        return ! errors.isEmpty();
    }

    public int errorCount() {
        return errors.size();
    }

    public List<ValidationError> tableErrors() {
        return errors.stream()
                     .filter(e -> e instanceof ValidationError.TableNotFound)
                     .toList();
    }

    public List<ValidationError> columnErrors() {
        return errors.stream()
                     .filter(e -> e instanceof ValidationError.ColumnNotFound || e instanceof ValidationError.ColumnNotResolved)
                     .toList();
    }
}

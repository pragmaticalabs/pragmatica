package org.pragmatica.aether.pg.schema.validator;

import java.util.List;


/// Result of query validation — contains all found errors.
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
        return errors.stream().filter(e -> e instanceof ValidationError.TableNotFound)
                            .toList();
    }

    public List<ValidationError> columnErrors() {
        return errors.stream().filter(e -> e instanceof ValidationError.ColumnNotFound || e instanceof ValidationError.ColumnNotResolved)
                            .toList();
    }
}

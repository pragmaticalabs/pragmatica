package com.example.testslice;

import org.pragmatica.lang.Cause;

public sealed interface TestSliceError extends Cause {
    record NotFound(Long id) implements TestSliceError {
        @Override
        public String message() {
            return "Item not found: " + id;
        }
    }

    record InvalidInput(String field, String reason) implements TestSliceError {
        @Override
        public String message() {
            return "Invalid " + field + ": " + reason;
        }
    }

    record DuplicateEntry(String key) implements TestSliceError {
        @Override
        public String message() {
            return "Duplicate entry: " + key;
        }
    }
}

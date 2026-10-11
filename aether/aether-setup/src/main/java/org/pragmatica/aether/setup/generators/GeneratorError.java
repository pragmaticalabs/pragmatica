// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.setup.generators;

import org.pragmatica.lang.Cause;


public sealed interface GeneratorError extends Cause {
    record IoError(String details) implements GeneratorError {
        @Override
        public String message() {
            return "I/O error during generation: " + details;
        }
    }

    record UnsupportedEnvironment(String environment) implements GeneratorError {
        @Override
        public String message() {
            return "Unsupported environment for this generator: " + environment;
        }
    }

    static GeneratorError ioError(String details) {
        return new IoError(details);
    }

    static GeneratorError unsupportedEnvironment(String environment) {
        return new UnsupportedEnvironment(environment);
    }

    record unused() implements GeneratorError {
        @Override
        public String message() {
            return "";
        }
    }
}

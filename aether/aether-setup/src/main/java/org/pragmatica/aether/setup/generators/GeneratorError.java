package org.pragmatica.aether.setup.generators;

import org.pragmatica.lang.Cause;

/**
 * Errors that can occur during artifact generation.
 */
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
}

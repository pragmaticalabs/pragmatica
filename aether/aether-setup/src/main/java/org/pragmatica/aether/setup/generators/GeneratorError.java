package org.pragmatica.aether.setup.generators;

import org.pragmatica.lang.Cause;

/// Errors that can occur during artifact generation.
public sealed interface GeneratorError extends Cause {
    @SuppressWarnings("JBCT-VO-01")
    record IoError(String details) implements GeneratorError {
        @Override
        public String message() {
            return "I/O error during generation: " + details;
        }
    }

    @SuppressWarnings("JBCT-VO-01")
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

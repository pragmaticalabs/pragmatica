package org.pragmatica.aether.slice;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.some;

/// Sealed error hierarchy for slice loading failures.
///
/// Classification determines retry behavior:
/// - Fatal: structural problems that will never succeed on retry
/// - Intermittent: temporary conditions that may succeed on retry
public sealed interface SliceLoadingFailure extends Cause
 permits SliceLoadingFailure.Fatal, SliceLoadingFailure.Intermittent {
    /// Fatal failures — structural problems, non-retriable.
    non-sealed interface Fatal extends SliceLoadingFailure {
        /// Factory method not found in slice class.
        record FactoryMethodNotFound(String className, String methodName) implements Fatal {
            @Override
            public String message() {
                return "Factory method not found: " + className + "." + methodName + "() returning Promise<Slice>";
            }
        }

        /// Factory method parameter mismatch.
        record ParameterMismatch(String methodName, String detail) implements Fatal {
            @Override
            public String message() {
                return "Parameter mismatch in " + methodName + ": " + detail;
            }
        }

        /// Class loading failure (ClassNotFoundException, NoClassDefFoundError, etc.).
        record ClassLoadFailed(String className, Cause causeSource) implements Fatal {
            @Override
            public String message() {
                return "Failed to load class: " + className + ": " + causeSource.message();
            }

            @Override
            public Option<Cause> source() {
                return some(causeSource);
            }
        }

        /// Manifest is invalid or missing required attributes.
        record ManifestInvalid(String detail) implements Fatal {
            @Override
            public String message() {
                return "Invalid slice manifest: " + detail;
            }
        }

        /// Envelope format version is not supported by this runtime.
        record EnvelopeVersionUnsupported(String version) implements Fatal {
            @Override
            public String message() {
                return "Envelope format version " + version + " not supported by this runtime";
            }
        }

        /// Artifact coordinates in manifest don't match requested artifact.
        record ArtifactMismatch(String requested, String declared) implements Fatal {
            @Override
            public String message() {
                return "Artifact mismatch: requested " + requested + " but JAR manifest declares " + declared;
            }
        }

        /// Circular dependency detected during resolution.
        record CircularDependency(String artifactKey) implements Fatal {
            @Override
            public String message() {
                return "Circular dependency detected during resolution: " + artifactKey;
            }
        }

        /// Configuration error during resource provisioning (wraps ConfigError at boundary).
        record ConfigurationFailed(String section, Cause configError) implements Fatal {
            @Override
            public String message() {
                return "Failed to load config for resource: " + configError.message();
            }

            @Override
            public Option<Cause> source() {
                return some(configError);
            }
        }

        /// Resource factory not registered for the requested type.
        record ResourceFactoryNotFound(String resourceType) implements Fatal {
            @Override
            public String message() {
                return "No factory registered for resource type: " + resourceType;
            }
        }

        /// Resource creation failed during provisioning.
        record ResourceCreationFailed(String resourceType, String configSection, Cause causeSource) implements Fatal {
            @Override
            public String message() {
                return "Failed to create " + resourceType + " from config '" + configSection + "': " + causeSource.message();
            }

            @Override
            public Option<Cause> source() {
                return some(causeSource);
            }
        }

        /// Unexpected error from user-defined slice factory (wraps non-SliceLoadingFailure causes).
        record UnexpectedError(Cause causeSource) implements Fatal {
            @Override
            public String message() {
                return "Unexpected slice loading error: " + causeSource.message();
            }

            @Override
            public Option<Cause> source() {
                return some(causeSource);
            }
        }

        /// Extension point for user-defined fatal errors.
        interface Custom extends Fatal {}
    }

    /// Intermittent failures — temporary conditions, retriable.
    non-sealed interface Intermittent extends SliceLoadingFailure {
        /// Artifact not found in any repository (may appear later).
        record ArtifactNotFound(String artifact) implements Intermittent {
            @Override
            public String message() {
                return "Artifact not found in any repository: " + artifact;
            }
        }

        /// Network or I/O error during artifact download.
        record NetworkError(String detail, Cause causeSource) implements Intermittent {
            @Override
            public String message() {
                return "Network error: " + detail + ": " + causeSource.message();
            }

            @Override
            public Option<Cause> source() {
                return some(causeSource);
            }
        }

        /// Timeout during slice loading or activation.
        record Timeout(String operation, Cause causeSource) implements Intermittent {
            @Override
            public String message() {
                return "Timeout during " + operation + ": " + causeSource.message();
            }

            @Override
            public Option<Cause> source() {
                return some(causeSource);
            }
        }

        /// Resource temporarily unavailable.
        record ResourceUnavailable(String resource, Cause causeSource) implements Intermittent {
            @Override
            public String message() {
                return "Resource temporarily unavailable: " + resource + ": " + causeSource.message();
            }

            @Override
            public Option<Cause> source() {
                return some(causeSource);
            }
        }

        /// Extension point for user-defined intermittent errors.
        interface Custom extends Intermittent {}
    }

    /// Wraps a generic Cause into the SliceLoadingFailure hierarchy.
    /// If the cause is already a SliceLoadingFailure, returns it as-is.
    /// Otherwise, wraps it as Fatal.UnexpectedError.
    static SliceLoadingFailure classify(Cause cause) {
        if (cause instanceof SliceLoadingFailure failure) {
            return failure;
        }
        return new Fatal.UnexpectedError(cause);
    }

    /// Returns true if this failure is fatal (non-retriable).
    default boolean isFatal() {
        return this instanceof Fatal;
    }
}

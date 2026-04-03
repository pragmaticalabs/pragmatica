package org.pragmatica.aether.slice;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.CoreError;

import static org.pragmatica.lang.Option.some;


/// Sealed error hierarchy for slice loading failures.
///
/// Classification determines retry behavior:
/// - Fatal: structural problems that will never succeed on retry
/// - Intermittent: temporary conditions that may succeed on retry
public sealed interface SliceLoadingFailure extends Cause permits SliceLoadingFailure.Fatal, SliceLoadingFailure.Intermittent {
    non-sealed interface Fatal extends SliceLoadingFailure {
        record FactoryMethodNotFound(String className, String methodName) implements Fatal {
            @Override public String message() {
                return "Factory method not found: " + className + "." + methodName + "() returning Promise<Slice>";
            }
        }

        record ParameterMismatch(String methodName, String detail) implements Fatal {
            @Override public String message() {
                return "Parameter mismatch in " + methodName + ": " + detail;
            }
        }

        record ClassLoadFailed(String className, Cause causeSource) implements Fatal {
            @Override public String message() {
                return "Failed to load class: " + className + ": " + causeSource.message();
            }

            @Override public Option<Cause> source() {
                return some(causeSource);
            }
        }

        record ManifestInvalid(String detail) implements Fatal {
            @Override public String message() {
                return "Invalid slice manifest: " + detail;
            }
        }

        record EnvelopeVersionUnsupported(String version) implements Fatal {
            @Override public String message() {
                return "Envelope format version " + version + " not supported by this runtime";
            }
        }

        record ArtifactMismatch(String requested, String declared) implements Fatal {
            @Override public String message() {
                return "Artifact mismatch: requested " + requested + " but JAR manifest declares " + declared;
            }
        }

        record CircularDependency(String artifactKey) implements Fatal {
            @Override public String message() {
                return "Circular dependency detected during resolution: " + artifactKey;
            }
        }

        record ConfigurationFailed(String section, Cause configError) implements Fatal {
            @Override public String message() {
                return "Failed to load config for resource: " + configError.message();
            }

            @Override public Option<Cause> source() {
                return some(configError);
            }
        }

        record ResourceFactoryNotFound(String resourceType) implements Fatal {
            @Override public String message() {
                return "No factory registered for resource type: " + resourceType;
            }
        }

        record ResourceCreationFailed(String resourceType, String configSection, Cause causeSource) implements Fatal {
            @Override public String message() {
                return "Failed to create " + resourceType + " from config '" + configSection + "': " + causeSource.message();
            }

            @Override public Option<Cause> source() {
                return some(causeSource);
            }
        }

        record UnexpectedError(Cause causeSource) implements Fatal {
            @Override public String message() {
                return "Unexpected slice loading error: " + causeSource.message();
            }

            @Override public Option<Cause> source() {
                return some(causeSource);
            }
        }

        interface Custom extends Fatal {}
    }

    non-sealed interface Intermittent extends SliceLoadingFailure {
        record ArtifactNotFound(String artifact) implements Intermittent {
            @Override public String message() {
                return "Artifact not found in any repository: " + artifact;
            }
        }

        record NetworkError(String detail, Cause causeSource) implements Intermittent {
            @Override public String message() {
                return "Network error: " + detail + ": " + causeSource.message();
            }

            @Override public Option<Cause> source() {
                return some(causeSource);
            }
        }

        record Timeout(String operation, Cause causeSource) implements Intermittent {
            @Override public String message() {
                return "Timeout during " + operation + ": " + causeSource.message();
            }

            @Override public Option<Cause> source() {
                return some(causeSource);
            }
        }

        record ResourceUnavailable(String resource, Cause causeSource) implements Intermittent {
            @Override public String message() {
                return "Resource temporarily unavailable: " + resource + ": " + causeSource.message();
            }

            @Override public Option<Cause> source() {
                return some(causeSource);
            }
        }

        interface Custom extends Intermittent {}
    }

    static SliceLoadingFailure classify(Cause cause) {
        if (cause instanceof SliceLoadingFailure failure) {return failure;}
        if (cause instanceof CoreError.Timeout) {return new Intermittent.Timeout("slice activation", cause);}
        return new Fatal.UnexpectedError(cause);
    }

    default boolean isFatal() {
        return this instanceof Fatal;
    }
}

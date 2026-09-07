// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.CoreError;

import static org.pragmatica.lang.Option.some;


public sealed interface SliceLoadingFailure extends Cause permits SliceLoadingFailure.Fatal, SliceLoadingFailure.Intermittent {
    non-sealed interface Fatal extends SliceLoadingFailure {
        record FactoryMethodNotFound(String className, String methodName) implements Fatal {
            @Override
            public String message() {
                return "Factory method not found: " + className + "." + methodName + "() returning Promise<Slice>";
            }
        }

        record ParameterMismatch(String methodName, String detail) implements Fatal {
            @Override
            public String message() {
                return "Parameter mismatch in " + methodName + ": " + detail;
            }
        }

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

        record ManifestInvalid(String detail) implements Fatal {
            @Override
            public String message() {
                return "Invalid slice manifest: " + detail;
            }
        }

        record EnvelopeVersionUnsupported(String version) implements Fatal {
            @Override
            public String message() {
                return "Envelope format version " + version + " not supported by this runtime";
            }
        }

        record ArtifactMismatch(String requested, String declared) implements Fatal {
            @Override
            public String message() {
                return "Artifact mismatch: requested " + requested + " but JAR manifest declares " + declared;
            }
        }

        record CircularDependency(String artifactKey) implements Fatal {
            @Override
            public String message() {
                return "Circular dependency detected during resolution: " + artifactKey;
            }
        }

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

        record ResourceFactoryNotFound(String resourceType) implements Fatal {
            @Override
            public String message() {
                return "No resource provider registered for resource type: " + resourceType
                     + ". Bundle the module supplying a ResourceFactory for this type on the runtime classpath.";
            }
        }

        record ResourceCreationFailed(String resourceType, String configSection, Cause causeSource) implements Fatal {
            @Override
            public String message() {
                return "Failed to create " + resourceType
                     + " from config '" + configSection
                     + "': " + causeSource.message();
            }

            @Override
            public Option<Cause> source() {
                return some(causeSource);
            }
        }

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

        interface Custom extends Fatal {}
    }

    non-sealed interface Intermittent extends SliceLoadingFailure {
        record ArtifactNotFound(String artifact) implements Intermittent {
            @Override
            public String message() {
                return "Artifact not found in any repository: " + artifact;
            }
        }

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

        /// #916 — the slice is absent from the node's `SliceStore` at a point in the activation
        /// chain that requires it to be present. This is a race, not a defect: an unload issued for
        /// a previous deployment of the same artifact can still be in flight when the ACTIVATE for
        /// the new one arrives, and a retry after the unload settles succeeds.
        ///
        /// Typed `Intermittent` at the raise site rather than left to [SliceLoadingFailure#classify],
        /// whose catch-all is deliberately permanent. Untyped, this cause reached
        /// `Fatal.UnexpectedError`, and the cluster leader rolled the whole blueprint back under
        /// `ALL_OR_NOTHING` for a collision a bounded retry would have cleared. Same reasoning and
        /// same remedy as `SliceInvoker.verifyEndpointExists`, which typed its own activation-order
        /// race `Intermittent` for exactly this reason.
        record SliceNotInStore(String artifact, String operation) implements Intermittent {
            public static SliceNotInStore sliceNotInStore(String artifact, String operation) {
                return new SliceNotInStore(artifact, operation);
            }

            @Override
            public String message() {
                return "Slice " + artifact + " not present in SliceStore during " + operation
                     + " (a concurrent unload may still be in flight)";
            }
        }

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

        interface Custom extends Intermittent {}
    }

    /// Classifies an arbitrary `Cause` raised on a slice loading or activation path.
    ///
    /// **The catch-all stays permanent (#916 ruling).** Every cause this method does not recognise
    /// becomes `Fatal.UnexpectedError`, and that is deliberate, not an oversight. The cause universe
    /// here is open — any code on the loading or activation path can raise anything — so the default
    /// arm is chosen for the failure mode it produces, not for how often it is right.
    ///
    /// Permanent-by-default fails loudly and bounded: the leader marks the artifact permanently
    /// failed and, under `ALL_OR_NOTHING`, rolls the blueprint back, so the declared atomicity holds
    /// and the operator sees a `ROLLED_BACK` blueprint they can act on. Intermittent-by-default
    /// would fail quietly and unbounded in the direction that matters: a genuinely permanent failure
    /// reached through an unclassified path would be retried five times and then abandoned by
    /// `logMaxRetriesExceeded`, which does NOT roll back — leaving a half-deployed blueprint that an
    /// `ALL_OR_NOTHING` deployment promised could not exist. Trading a wrong rollback for a silently
    /// broken atomicity guarantee is the worse trade.
    ///
    /// The price of keeping it is an obligation: **a transient cause on these paths must be typed
    /// `Intermittent` where it is raised**, because reaching this method untyped means permanent.
    /// Two causes have already been paid for this way — `SliceInvoker.verifyEndpointExists`
    /// (activation-order race on a dependency's endpoint) and
    /// [Intermittent.SliceNotInStore] (#916, the unload/activate crossing).
    static SliceLoadingFailure classify(Cause cause) {
        if (cause instanceof SliceLoadingFailure failure) {
            return failure;
        }

        if (ResourceCapacityExhausted.isTransientCapacity(cause)) {
            return new Intermittent.ResourceUnavailable("resource capacity", cause);
        }

        if (cause instanceof CoreError.Timeout) {
            return new Intermittent.Timeout("slice activation", cause);
        }

        return new Fatal.UnexpectedError(cause);
    }

    default boolean isFatal() {
        return this instanceof Fatal;
    }
}

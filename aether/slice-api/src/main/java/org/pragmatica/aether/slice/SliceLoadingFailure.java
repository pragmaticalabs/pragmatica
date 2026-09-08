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
        /// Typed `Intermittent` at the raise site rather than left to
        /// [SliceLoadingFailure#classify(Cause, Unrecognised)]. Untyped, this cause reached
        /// `Fatal.UnexpectedError`, and the cluster leader rolled the whole blueprint back under
        /// `ALL_OR_NOTHING` for a collision a bounded retry would have cleared. Same reasoning and
        /// same remedy as `SliceInvoker.verifyEndpointExists`, which typed its own activation-order
        /// race `Intermittent` for exactly this reason.
        ///
        /// #930 UPDATE — this paragraph used to end "whose catch-all is deliberately permanent".
        /// There is no catch-all: the permanence of an unrecognised cause is now supplied by the
        /// caller. Typing the cause here is still the right call and for a stronger reason than
        /// before — permanence here is a property of the CAUSE (an in-flight unload always clears)
        /// rather than of the operation, so it must classify the same way at every raise site,
        /// including one that declares [Unrecognised#PERMANENT].
        record SliceNotInStore(String artifact, String operation) implements Intermittent {
            public static SliceNotInStore sliceNotInStore(String artifact, String operation) {
                return new SliceNotInStore(artifact, operation);
            }

            @Override
            public String message() {
                return "Slice " + artifact
                     + " not present in SliceStore during " + operation
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

        /// #930 — a cause [SliceLoadingFailure#classify(Cause, Unrecognised)] does not recognise,
        /// raised at a site that declared [Unrecognised#RETRY].
        ///
        /// The `Intermittent` counterpart of [Fatal.UnexpectedError], and it exists so the two
        /// dispositions are equally expressible. Naming the cause "unrecognised" rather than
        /// borrowing [ResourceUnavailable] is deliberate: the classifier knows nothing about this
        /// cause beyond the raise site's declaration, and an operator reading `failureReason()`
        /// must not be told a resource was unavailable when nothing established that.
        record UnrecognisedFailure(Cause causeSource) implements Intermittent {
            @Override
            public String message() {
                return "Unrecognised slice loading error, retryable at this site: " + causeSource.message();
            }

            @Override
            public Option<Cause> source() {
                return some(causeSource);
            }
        }

        interface Custom extends Intermittent {}
    }

    /// The permanence a raise site declares for a cause [#classify(Cause, Unrecognised)] does not
    /// recognise (#930).
    ///
    /// It exists so that permanence is an input to the operation rather than an inference from a
    /// value's Java type. Before #930 this method carried a catch-all that returned
    /// [Fatal.UnexpectedError] for everything unrecognised, and because almost no cause raised on
    /// the deployment path is constructed as a `SliceLoadingFailure` at all, that catch-all — not
    /// the raise site — was what decided whether a blueprint rolled back. There is deliberately no
    /// default here: a new raise site must name one or fail to compile.
    enum Unrecognised {
        /// The operation is retryable at this site: an unrecognised cause is reported
        /// [Intermittent] and the cluster re-drives it under the retry budget.
        RETRY,
        /// The operation is not retryable at this site: an unrecognised cause is reported
        /// [Fatal] and settles without consuming a retry budget.
        PERMANENT
    }

    /// Classifies an arbitrary `Cause` raised on a slice loading or activation path, with the
    /// permanence of an UNRECOGNISED cause supplied by the caller (#930).
    ///
    /// Three shapes are recognised regardless of what the caller declares, because each is
    /// evidence about the cause itself rather than about the operation: an already-typed
    /// `SliceLoadingFailure` (the raise site already decided), transient capacity exhaustion, and
    /// `CoreError.Timeout`. Everything else is unrecognised, and `unrecognised` decides it.
    ///
    /// **What replaced the #916 ruling.** #916 argued the catch-all must stay permanent, because an
    /// intermittent default would retry a genuinely fatal cause and then abandon it without a
    /// rollback. #930 removes the catch-all rather than re-aiming it, and #922 removes the
    /// abandonment: retry exhaustion now consults the owning blueprint's own durable apply record,
    /// so a spent budget on a deployment that never applied settles permanently WITH a rollback and
    /// a `DeploymentOutcomeValue`, while a workload that already applied keeps being reconciled.
    /// `ALL_OR_NOTHING` is therefore bounded by the apply's durable terminal, not by the Java type
    /// of whatever cause happened to surface — which is what let a consensus outage roll a
    /// blueprint back (#923).
    ///
    /// The obligation #916 created still stands and is unchanged: **a cause whose permanence is a
    /// property of the CAUSE rather than of the operation should be typed where it is raised**, so
    /// it classifies the same way at every site. [Intermittent.SliceNotInStore] (#916) and
    /// `SliceInvoker.verifyEndpointExists` are the two already paid for this way.
    static SliceLoadingFailure classify(Cause cause, Unrecognised unrecognised) {
        if (cause instanceof SliceLoadingFailure failure) {
            return failure;
        }

        if (ResourceCapacityExhausted.isTransientCapacity(cause)) {
            return new Intermittent.ResourceUnavailable("resource capacity", cause);
        }

        if (cause instanceof CoreError.Timeout) {
            return new Intermittent.Timeout("slice activation", cause);
        }

        return unrecognised == Unrecognised.RETRY
               ? new Intermittent.UnrecognisedFailure(cause)
               : new Fatal.UnexpectedError(cause);
    }

    default boolean isFatal() {
        return this instanceof Fatal;
    }
}

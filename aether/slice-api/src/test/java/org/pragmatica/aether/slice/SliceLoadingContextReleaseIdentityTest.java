// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.slice;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;

import static org.assertj.core.api.Assertions.assertThat;

/// A slice must release resources under the SAME id it provisioned them under (#892).
///
/// The two sides used to compute that id independently. Provisioning is scoped by
/// `SliceLoadingContext.SliceAwareResourceProvider` to the deployed `Artifact` string —
/// `groupId:artifactId:version`, three segments, set by `DependencyResolver` from
/// `artifact.asString()`. Release was driven by a slice's generated `stop()`, which passes a
/// compile-time literal from `FactoryClassGenerator.computeSliceArtifactCoordinate`:
/// `groupId:artifactId-kebab(SliceName)` — two segments, no version, and a different artifactId
/// segment. `SpiResourceProvider.releaseAll` matches scopes by string equality, so nothing was
/// ever released.
///
/// The invariant test below is the one that cannot be satisfied by agreement between two hand-typed
/// literals: it compares the id the facade PROVISIONED under against the id it RELEASED with, both
/// captured from the same run, and never names either.
class SliceLoadingContextReleaseIdentityTest {
    /// What `DependencyResolver` passes as the slice id: `artifact.asString()`.
    private static final String DEPLOYED_ARTIFACT = "org.example:app-order-intake:1.4.2";

    /// What a generated `stop()` passes to `releaseAll`, verbatim in shape from the tree's own
    /// generated output (e.g. `slice-testkit`'s `EndpointProbeFactory`:
    /// `org.pragmatica-lite.aether:slice-testkit-endpoint-probe`). Two segments, no version.
    private static final String GENERATED_COORDINATE = "org.example:app-order-intake";

    @Nested
    class WhenTheContextKnowsItsSlice {

        /// The invariant, stated without naming either string: whatever the facade scopes
        /// provisioning to is what it releases. A release id that has to AGREE with a separately
        /// computed literal is the defect's shape; this asserts there is only one value.
        ///
        /// Provisioning goes through the no-context overload because that is what the generator
        /// emits for a plain resource dependency (`FactoryClassGenerator.generateStandardProvideCall`);
        /// the composite attached below is what upgrades it to the context overload and lets the
        /// slice id reach the provider at all, exactly as on a deployed node.
        @Test
        void releaseAll_usesTheSameIdProvisioningIsScopedTo() {
            var recorder = new Recorder();
            var ctx = contextFor(recorder);

            ctx.resources()
               .provide(String.class, "any.section");
            ctx.resources()
               .releaseAll(GENERATED_COORDINATE);

            assertThat(recorder.provisioningScope()).describedAs("premise: the context must scope provisioning to a slice id at all")
                                                    .isNotNull();
            assertThat(recorder.releasedId()).describedAs("the released id must BE the provisioning scope, not a value that agrees with it")
                                             .isEqualTo(recorder.provisioningScope());
        }

        /// The reported defect, in its exact production shape: a generated two-segment coordinate
        /// arriving at a context scoped to a three-segment deployed artifact.
        @Test
        void releaseAll_releasesTheDeployedArtifact_whenCallerPassesTheGeneratedCoordinate() {
            var recorder = new Recorder();

            contextFor(recorder).resources()
                                .releaseAll(GENERATED_COORDINATE);

            assertThat(recorder.releasedId()).isEqualTo(DEPLOYED_ARTIFACT);
        }

        /// The identity is the CONTEXT's, so no caller-supplied string can steer it — including one
        /// naming another slice. This is what keeps the substitution from becoming a way for one
        /// slice's `stop()` to close another's resources.
        @Test
        void releaseAll_releasesTheDeployedArtifact_forAnyCallerSuppliedId() {
            var recorder = new Recorder();

            contextFor(recorder).resources()
                                .releaseAll("org.other:some-other-slice:9.9.9");

            assertThat(recorder.releasedId()).isEqualTo(DEPLOYED_ARTIFACT);
        }

        private static SliceLoadingContext contextFor(Recorder recorder) {
            var ctx = SliceLoadingContext.sliceLoadingContext(NoOpInvoker.INSTANCE,
                                                              recorder.facade(),
                                                              DEPLOYED_ARTIFACT);

            ctx.setSliceComposite(Option.some(ConfigurationProvider.builder().build()));

            return ctx;
        }
    }

    @Nested
    class WhenTheContextKnowsNoSlice {

        /// The caller's argument is NOT dead. `SliceLoadingContext.resources()` interposes the
        /// slice-aware wrapper only when `sliceId()` is present; without it the argument is the
        /// only id there is and must reach the provider unchanged.
        ///
        /// What this branch does NOT promise: that anything is released. A context with no slice id
        /// never injects a provisioning scope either, so its resources land in the provider's
        /// unattributed scope, which no release matches by design (#268 R2). The forwarding is
        /// preserved because it is the contract, not because it closes anything.
        @Test
        void releaseAll_forwardsTheCallersId_whenNoSliceIdIsKnown() {
            var recorder = new Recorder();

            SliceLoadingContext.sliceLoadingContext(NoOpInvoker.INSTANCE, recorder.facade())
                               .resources()
                               .releaseAll(GENERATED_COORDINATE);

            assertThat(recorder.releasedId()).isEqualTo(GENERATED_COORDINATE);
        }
    }

    /// The caller's id is substituted, NOT discarded — a parameter that looks meaningful and is
    /// silently dropped is the same shape as the defect this fixes. What disagreement MEANS is
    /// classified here, and the classification decides how loudly it is reported.
    @Nested
    class AgreementClassification {

        @Test
        void between_isExact_whenTheCallerNamesTheDeployedArtifact() {
            assertThat(SliceLoadingContext.ReleaseIdAgreement.between(DEPLOYED_ARTIFACT, DEPLOYED_ARTIFACT))
                    .describedAs("what a processor that could emit a version would produce")
                    .isEqualTo(SliceLoadingContext.ReleaseIdAgreement.EXACT);
        }

        @Test
        void between_isGeneratedBase_whenTheCallerNamesTheVersionLessBaseOfIt() {
            assertThat(SliceLoadingContext.ReleaseIdAgreement.between(GENERATED_COORDINATE, DEPLOYED_ARTIFACT))
                    .describedAs("the shape every generated stop() emits today — expected, so not a warning")
                    .isEqualTo(SliceLoadingContext.ReleaseIdAgreement.GENERATED_BASE);
        }

        @Test
        void between_isForeign_whenTheCallerNamesAnotherSlice() {
            assertThat(SliceLoadingContext.ReleaseIdAgreement.between("org.other:some-other-slice:9.9.9", DEPLOYED_ARTIFACT))
                    .describedAs("before the substitution this would have released another slice's resources")
                    .isEqualTo(SliceLoadingContext.ReleaseIdAgreement.FOREIGN);
        }

        /// The executable form of `FactoryClassGenerator.computeSliceArtifactCoordinate`'s warning
        /// that widening the emitted literal to a versioned coordinate "would restore exactly the
        /// two-independently-computed-strings shape that produced the defect" (v892 NOTE 6).
        ///
        /// That comment is prose, and a comment enforces nothing. This is what enforces it: a
        /// widened coordinate that AGREES is `EXACT` and silent, but one that has DRIFTED — the
        /// whole hazard of computing the string twice — is `FOREIGN`, so it is refused and reported
        /// at WARNING rather than silently honoured. It cannot fire for today's generator output,
        /// which does not parse as an `Artifact` at all.
        @Test
        void between_isForeign_whenAWidenedGeneratorCoordinateHasDriftedFromTheDeployedVersion() {
            var driftedButWellFormed = "org.example:app-order-intake:9.9.9";

            assertThat(SliceLoadingContext.ReleaseIdAgreement.between(driftedButWellFormed, DEPLOYED_ARTIFACT))
                    .describedAs("same artifact, different version — the drift a versioned generator literal would reintroduce")
                    .isEqualTo(SliceLoadingContext.ReleaseIdAgreement.FOREIGN);
        }

        /// The discrimination that makes GENERATED_BASE narrow rather than a prefix free-for-all: a
        /// DIFFERENT slice whose coordinate merely starts with the same characters is FOREIGN, not
        /// a base. Without the segment separator, `…-release-probe` would swallow
        /// `…-release-probe-extra`.
        @Test
        void between_isForeign_whenAnotherSlicesIdMerelySharesAPrefix() {
            assertThat(SliceLoadingContext.ReleaseIdAgreement.between(GENERATED_COORDINATE + "-extra", DEPLOYED_ARTIFACT))
                    .isEqualTo(SliceLoadingContext.ReleaseIdAgreement.FOREIGN);
        }
    }

    /// Captures both halves of the identity: the scope injected into a provisioning context, and
    /// the id handed to `releaseAll`.
    private static final class Recorder {
        private final AtomicReference<String> provisioningScope = new AtomicReference<>();
        private final AtomicReference<String> releasedId = new AtomicReference<>();

        String provisioningScope() {
            return provisioningScope.get();
        }

        String releasedId() {
            return releasedId.get();
        }

        ResourceProviderFacade facade() {
            return new ResourceProviderFacade() {
                @Override
                public <T> Promise<T> provide(Class<T> resourceType, String configSection) {
                    return Promise.success(null);
                }

                @Override
                public <T> Promise<T> provide(Class<T> resourceType, String configSection, ProvisioningContext context) {
                    context.extension(String.class).onSuccess(provisioningScope::set);

                    return Promise.success(null);
                }

                @Override
                public Promise<Unit> releaseAll(String sliceId) {
                    releasedId.set(sliceId);

                    return Promise.unitPromise();
                }
            };
        }
    }

    private enum NoOpInvoker implements SliceInvokerFacade {
        INSTANCE;

        @Override
        public <R, T> Result<MethodHandle<R, T>> methodHandle(String sliceArtifact,
                                                              String methodName,
                                                              TypeToken<T> requestType,
                                                              TypeToken<R> responseType) {
            return Result.success(null);
        }
    }
}

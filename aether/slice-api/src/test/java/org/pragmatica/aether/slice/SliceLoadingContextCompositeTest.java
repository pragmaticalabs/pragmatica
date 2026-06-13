// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.slice;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SliceLoadingContextCompositeTest {

    private static ResourceProviderFacade recordingFacade(AtomicReference<ProvisioningContext> capturedCtx) {
        return new ResourceProviderFacade() {
            @Override public <T> Promise<T> provide(Class<T> resourceType, String configSection) {
                return Promise.success(null);
            }

            @Override public <T> Promise<T> provide(Class<T> resourceType,
                                                    String configSection,
                                                    ProvisioningContext context) {
                capturedCtx.set(context);
                return Promise.success(null);
            }

            @Override public Promise<Unit> releaseAll(String sliceId) {
                return Promise.unitPromise();
            }
        };
    }

    private static SliceInvokerFacade noOpInvoker() {
        return new SliceInvokerFacade() {
            @Override public <R, T> org.pragmatica.lang.Result<MethodHandle<R, T>> methodHandle(String sliceArtifact,
                                                                                                 String methodName,
                                                                                                 org.pragmatica.lang.type.TypeToken<T> requestType,
                                                                                                 org.pragmatica.lang.type.TypeToken<R> responseType) {
                return org.pragmatica.lang.Result.success(null);
            }
        };
    }

    private static ConfigurationProvider emptyComposite() {
        return ConfigurationProvider.builder().build();
    }

    @Nested
    class WithCompositeAttached {

        @Test
        void provide_noContextOverload_upgradesToContextOverloadAndAttachesComposite() {
            var captured = new AtomicReference<ProvisioningContext>();
            var ctx = SliceLoadingContext.sliceLoadingContext(noOpInvoker(), recordingFacade(captured), "test-slice");
            var composite = emptyComposite();
            ctx.setSliceComposite(Option.some(composite));

            ctx.resources().provide(String.class, "any.section");

            assertThat(captured.get()).isNotNull();
            assertThat(captured.get().extension(ConfigurationProvider.class).isSuccess()).isTrue();
            captured.get().extension(ConfigurationProvider.class)
                          .onSuccess(c -> assertThat(c).isSameAs(composite));
        }

        @Test
        void provide_contextOverload_enrichesContextWithComposite() {
            var captured = new AtomicReference<ProvisioningContext>();
            var ctx = SliceLoadingContext.sliceLoadingContext(noOpInvoker(), recordingFacade(captured), "test-slice");
            var composite = emptyComposite();
            ctx.setSliceComposite(Option.some(composite));

            ctx.resources().provide(String.class, "any.section", ProvisioningContext.provisioningContext());

            assertThat(captured.get()).isNotNull();
            captured.get().extension(ConfigurationProvider.class)
                          .onSuccess(c -> assertThat(c).isSameAs(composite));
        }
    }

    @Nested
    class WithoutComposite {

        @Test
        void provide_noContextOverload_doesNotUpgradeWhenNoCompositePresent() {
            var captured = new AtomicReference<ProvisioningContext>();
            var ctx = SliceLoadingContext.sliceLoadingContext(noOpInvoker(), recordingFacade(captured), "test-slice");

            ctx.resources().provide(String.class, "any.section");

            // No context overload triggered when composite absent — captured stays null
            assertThat(captured.get()).isNull();
        }

        @Test
        void provide_contextOverload_passesThroughOriginalContext() {
            var captured = new AtomicReference<ProvisioningContext>();
            var ctx = SliceLoadingContext.sliceLoadingContext(noOpInvoker(), recordingFacade(captured), "test-slice");

            ctx.resources().provide(String.class, "any.section", ProvisioningContext.provisioningContext());

            assertThat(captured.get()).isNotNull();
            assertThat(captured.get().extension(ConfigurationProvider.class).isFailure()).isTrue();
        }
    }

    @Nested
    class CompositeBuilder {

        @Test
        void materializeComposite_invokesBuilder_andAttachesResult() {
            var captured = new AtomicReference<ProvisioningContext>();
            var ctx = SliceLoadingContext.sliceLoadingContext(noOpInvoker(), recordingFacade(captured), "test-slice");
            var composite = emptyComposite();
            ctx.setCompositeBuilder(_ -> Option.some(composite));

            ctx.materializeComposite(SliceLoadingContextCompositeTest.class.getClassLoader());

            assertThat(ctx.sliceComposite().isPresent()).isTrue();
            ctx.sliceComposite().onPresent(c -> assertThat(c).isSameAs(composite));
        }

        @Test
        void materializeComposite_isIdempotent() {
            var ctx = SliceLoadingContext.sliceLoadingContext(noOpInvoker(),
                                                              recordingFacade(new AtomicReference<>()),
                                                              "test-slice");
            var firstComposite = emptyComposite();
            var secondComposite = emptyComposite();
            ctx.setSliceComposite(Option.some(firstComposite));
            ctx.setCompositeBuilder(_ -> Option.some(secondComposite));

            ctx.materializeComposite(SliceLoadingContextCompositeTest.class.getClassLoader());

            // First composite wins — builder is not invoked when composite already present
            ctx.sliceComposite().onPresent(c -> assertThat(c).isSameAs(firstComposite));
        }

        @Test
        void materializeComposite_noBuilder_isNoOp() {
            var ctx = SliceLoadingContext.sliceLoadingContext(noOpInvoker(),
                                                              recordingFacade(new AtomicReference<>()),
                                                              "test-slice");

            ctx.materializeComposite(SliceLoadingContextCompositeTest.class.getClassLoader());

            assertThat(ctx.sliceComposite().isEmpty()).isTrue();
        }
    }
}

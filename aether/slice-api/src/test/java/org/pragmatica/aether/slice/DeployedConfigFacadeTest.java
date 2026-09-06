// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.slice;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.IntrinsicConfigProvider;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;

import static org.assertj.core.api.Assertions.assertThat;

/// Unit-level cover for what a deployed slice's `ctx.config()` actually answers (#889).
///
/// The end-to-end proof lives in slice-testkit's `DeployedConfigSectionTest`, which drives a real
/// jar through `SliceStore`. This pins the two halves that test can only observe in combination:
/// the facade's own read semantics, and the seam in [SliceLoadingContext] that decides which facade
/// a slice gets.
class DeployedConfigFacadeTest {
    private static final String SECTION = "app.endpoint";

    private static ConfigurationProvider provider(Map<String, String> values) {
        return IntrinsicConfigProvider.intrinsicConfigProvider("test", values);
    }

    private static ConfigFacade facade(Map<String, String> values) {
        return ConfigProviderFacade.configProviderFacade(provider(values));
    }

    @Nested
    class ReadSemantics {
        @Test
        void requireReadsAddressTheSectionQualifiedKey() {
            var config = facade(Map.of(SECTION + ".host", "endpoint.internal"));

            assertThat(config.requireString(SECTION, "host").unwrap()).isEqualTo("endpoint.internal");
            assertThat(config.requireString("other", "host").isFailure()).describedAs("a key in a different section must not answer")
                                                                         .isTrue();
        }

        @Test
        void typedRequireReadsParseTheirValues() {
            var config = facade(Map.of(SECTION + ".port", "8443",
                                        SECTION + ".size", "9000000000",
                                        SECTION + ".ratio", "0.25",
                                        SECTION + ".secure", "true"));

            assertThat(config.requireInt(SECTION, "port").unwrap()).isEqualTo(8443);
            assertThat(config.requireLong(SECTION, "size").unwrap()).isEqualTo(9_000_000_000L);
            assertThat(config.requireDouble(SECTION, "ratio").unwrap()).isEqualTo(0.25);
            assertThat(config.requireBoolean(SECTION, "secure").unwrap()).isTrue();
        }

        /// A non-numeric value must come back as a named failure, not as a `NumberFormatException`
        /// thrown out of the generated factory. The older `ConfigService` adapter reached
        /// `Long::parseLong` directly and would throw here.
        @Test
        void unparseableNumberFailsRatherThanThrows() {
            var config = facade(Map.of(SECTION + ".port", "not-a-number",
                                        SECTION + ".size", "not-a-number"));

            assertThat(config.requireInt(SECTION, "port").isFailure()).isTrue();
            assertThat(config.requireLong(SECTION, "size").isFailure()).isTrue();
        }

        @Test
        void missingRequiredKeyFailsAndNamesTheKey() {
            var result = facade(Map.of()).requireString(SECTION, "host");

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).describedAs("the operator has to be told WHICH key was missing")
                                                                  .contains(SECTION + ".host"));
        }

        /// The idiomatic spelling. A native TOML array `tags = ["alpha", "beta"]` reaches the
        /// provider as `[alpha, beta]` — `TomlDocument` stringifies every value through
        /// `toString()` — and must come back as the two elements, not as `["[alpha", "beta]"]`
        /// (review S4: that is what the first draft returned, silently).
        @Test
        void stringListAcceptsANativeTomlArray() {
            var config = facade(Map.of(SECTION + ".tags", "[alpha, beta]",
                                        SECTION + ".single", "[alpha]"));

            assertThat(config.requireStringList(SECTION, "tags").unwrap()).isEqualTo(List.of("alpha", "beta"));
            assertThat(config.requireStringList(SECTION, "single").unwrap()).isEqualTo(List.of("alpha"));
        }

        /// The fallback: comma-joined scalars, the encoding `ProviderBasedConfigService#splitCommaList`
        /// established. The legacy `ConfigService` adapter refused this method outright, so a
        /// `List<String>` config field could not have worked even with that adapter wired in.
        @Test
        void stringListSplitsOnCommasTrimmingAndDroppingEmpties() {
            var config = facade(Map.of(SECTION + ".tags", " alpha , beta ,, gamma "));

            assertThat(config.requireStringList(SECTION, "tags").unwrap()).isEqualTo(List.of("alpha", "beta", "gamma"));
        }

        /// Whitespace and empty slots inside a native array are trimmed and dropped exactly as they
        /// are in the comma-joined form: the two spellings must agree on every input they share.
        @Test
        void stringListTreatsBothSpellingsAlike() {
            var config = facade(Map.of(SECTION + ".native", " [ alpha ,, beta ] ",
                                        SECTION + ".scalar", " alpha ,, beta "));

            assertThat(config.requireStringList(SECTION, "native").unwrap()).isEqualTo(config.requireStringList(SECTION, "scalar").unwrap());
            assertThat(config.requireStringList(SECTION, "native").unwrap()).isEqualTo(List.of("alpha", "beta"));
        }

        /// Present-but-empty is a VALUE (review N2, decided): `require` pins presence, and an empty
        /// list is something a list is allowed to be. Both spellings of empty agree.
        @Test
        void stringListPresentButEmptySucceedsAsEmptyList() {
            var config = facade(Map.of(SECTION + ".native", "[]",
                                        SECTION + ".scalar", ""));

            assertThat(config.requireStringList(SECTION, "native").unwrap()).isEmpty();
            assertThat(config.requireStringList(SECTION, "scalar").unwrap()).isEmpty();
        }

        /// A nested array cannot be a string list. Refuse by name rather than hand back bracket
        /// fragments that look like elements.
        @Test
        void stringListRefusesANestedArrayByName() {
            var result = facade(Map.of(SECTION + ".tags", "[[alpha, beta], [gamma]]")).requireStringList(SECTION, "tags");

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> assertThat(cause.message()).contains(SECTION + ".tags")
                                                                  .contains("nested array"));
        }

        @Test
        void missingStringListFailsRatherThanReturningEmpty() {
            assertThat(facade(Map.of()).requireStringList(SECTION, "tags").isFailure()).describedAs("require means require; an optional list is declared as Option")
                                                                                        .isTrue();
        }

        @Test
        void optionalReadsReturnNoneWhenAbsentAndValueWhenPresent() {
            var populated = facade(Map.of(SECTION + ".weight", "7"));

            assertThat(populated.getInt(SECTION, "weight").or(-1)).isEqualTo(7);
            assertThat(facade(Map.of()).getInt(SECTION, "weight").isPresent()).isFalse();
            assertThat(facade(Map.of()).getString(SECTION, "host").isPresent()).isFalse();
            assertThat(facade(Map.of()).getBoolean(SECTION, "secure").isPresent()).isFalse();
            assertThat(facade(Map.of()).getLong(SECTION, "size").isPresent()).isFalse();
            assertThat(facade(Map.of()).getDouble(SECTION, "ratio").isPresent()).isFalse();
        }
    }

    /// The seam itself: which facade a loading context hands to the slice factory.
    @Nested
    class LoadingContextSeam {
        /// Absence must REFUSE by name, not degrade. Falling through to the no-op would report
        /// "Config service not available" — a missing-key shape — and send the reader to their
        /// resources.toml when the node in fact has no configuration provider at all.
        @Test
        void configRefusesByNameWhenNoCompositeIsAttached() {
            var context = SliceLoadingContext.sliceLoadingContext(noOpInvoker(), noOpResources(), "org.example:probe:1.0.0");
            var result = context.config().requireString(SECTION, "host");

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause -> {
                       assertThat(cause.message()).describedAs("the failure must name the missing SOURCE, not look like a missing key")
                                                   .contains("No configuration composite");
                       assertThat(cause.message()).describedAs("the operator has a cluster of slices and needs to know which one asked")
                                                   .contains("org.example:probe:1.0.0");
                       assertThat(cause.message()).contains(SECTION + ".host");
                   });
        }

        /// A caller that supplied a real facade through the config-carrying `SliceCreationContext`
        /// overload meant it, and must not be overridden by the refusal above.
        @Test
        void anExplicitlySuppliedFacadeWinsOverTheRefusal() {
            var supplied = ConfigProviderFacade.configProviderFacade(provider(Map.of(SECTION + ".host", "supplied.host")));
            var delegate = SliceCreationContext.sliceCreationContext(noOpInvoker(), noOpResources(), "slice", supplied);
            var context = SliceLoadingContext.sliceLoadingContext(delegate);

            assertThat(context.config().requireString(SECTION, "host").unwrap()).isEqualTo("supplied.host");
        }

        @Test
        void configServesTheCompositeOnceAttached() {
            var context = SliceLoadingContext.sliceLoadingContext(noOpInvoker(), noOpResources(), "slice");

            context.setSliceComposite(Option.some(provider(Map.of(SECTION + ".host", "endpoint.internal"))));

            assertThat(context.config().requireString(SECTION, "host").unwrap()).isEqualTo("endpoint.internal");
        }

        /// The deployment path never calls `setSliceComposite` directly — it registers a builder and
        /// `DependencyResolver` materializes it with the slice classloader, strictly before the
        /// generated factory runs. `config()` must therefore read through the reference on each
        /// call rather than latch a facade at construction time.
        @Test
        void configPicksUpACompositeMaterializedAfterTheContextWasBuilt() {
            var context = SliceLoadingContext.sliceLoadingContext(noOpInvoker(), noOpResources(), "slice");

            context.setCompositeBuilder(_ -> Option.some(provider(Map.of(SECTION + ".host", "materialized.host"))));

            assertThat(context.config().requireString(SECTION, "host").isFailure()).describedAs("a registered builder must not take effect before materialization")
                                                                                    .isTrue();

            context.materializeComposite(DeployedConfigFacadeTest.class.getClassLoader());

            assertThat(context.config().requireString(SECTION, "host").unwrap()).isEqualTo("materialized.host");
        }
    }

    private static SliceInvokerFacade noOpInvoker() {
        return new SliceInvokerFacade() {
            @Override
            public <R, T> Result<MethodHandle<R, T>> methodHandle(String sliceArtifact,
                                                                  String methodName,
                                                                  TypeToken<T> requestType,
                                                                  TypeToken<R> responseType) {
                return Result.success(null);
            }
        };
    }

    private static ResourceProviderFacade noOpResources() {
        return new ResourceProviderFacade() {
            @Override
            public <T> org.pragmatica.lang.Promise<T> provide(Class<T> resourceType, String configSection) {
                return org.pragmatica.lang.Promise.success(null);
            }

            @Override
            public <T> org.pragmatica.lang.Promise<T> provide(Class<T> resourceType,
                                                              String configSection,
                                                              ProvisioningContext context) {
                return org.pragmatica.lang.Promise.success(null);
            }
        };
    }
}

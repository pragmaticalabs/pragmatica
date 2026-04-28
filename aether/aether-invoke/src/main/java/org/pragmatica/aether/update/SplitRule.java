// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.update;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;


public sealed interface SplitRule {
    String resolveVariant(Map<String, String> headers, Map<String, String> cookies);

    record HeaderHashSplit(String headerName, int variantCount) implements SplitRule {
        @SuppressWarnings("JBCT-VO-02") public static HeaderHashSplit headerHashSplit(String headerName,
                                                                                      int variantCount) {
            return new HeaderHashSplit(headerName, variantCount);
        }

        @Override public String resolveVariant(Map<String, String> headers, Map<String, String> cookies) {
            var value = headers.getOrDefault(headerName, "");
            var hash = Math.abs(value.hashCode()) % variantCount;
            return "variant-" + hash;
        }
    }

    record CookieHashSplit(String cookieName, int variantCount) implements SplitRule {
        @SuppressWarnings("JBCT-VO-02") public static CookieHashSplit cookieHashSplit(String cookieName,
                                                                                      int variantCount) {
            return new CookieHashSplit(cookieName, variantCount);
        }

        @Override public String resolveVariant(Map<String, String> headers, Map<String, String> cookies) {
            var value = cookies.getOrDefault(cookieName, "");
            var hash = Math.abs(value.hashCode()) % variantCount;
            return "variant-" + hash;
        }
    }

    record HeaderMatchSplit(String headerName, Map<String, String> valueToVariant, String defaultVariant) implements SplitRule {
        @SuppressWarnings("JBCT-VO-02") public static HeaderMatchSplit headerMatchSplit(String headerName,
                                                                                        Map<String, String> valueToVariant,
                                                                                        String defaultVariant) {
            return new HeaderMatchSplit(headerName, valueToVariant, defaultVariant);
        }

        @Override public String resolveVariant(Map<String, String> headers, Map<String, String> cookies) {
            var value = headers.getOrDefault(headerName, "");
            return valueToVariant.getOrDefault(value, defaultVariant);
        }
    }

    record PercentageSplit(List<VariantWeight> weights) implements SplitRule {
        @SuppressWarnings("JBCT-VO-02") public static PercentageSplit percentageSplit(List<VariantWeight> weights) {
            return new PercentageSplit(weights);
        }

        public record VariantWeight(String variant, int weight) {
            @SuppressWarnings("JBCT-VO-02") public static VariantWeight variantWeight(String variant, int weight) {
                return new VariantWeight(variant, weight);
            }
        }

        @Override public String resolveVariant(Map<String, String> headers, Map<String, String> cookies) {
            var totalWeight = weights.stream().mapToInt(VariantWeight::weight)
                                            .sum();
            if (totalWeight <= 0) {return weights.isEmpty()
                                         ? "variant-0"
                                         : weights.getFirst().variant();}
            var random = ThreadLocalRandom.current().nextInt(totalWeight);
            return selectVariantByWeight(random);
        }

        private String selectVariantByWeight(int random) {
            var cumulative = 0;
            for (var vw : weights) {
                cumulative += vw.weight();
                if (random <cumulative) {return vw.variant();}
            }
            return weights.getLast().variant();
        }
    }
}

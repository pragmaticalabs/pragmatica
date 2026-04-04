package org.pragmatica.aether.update;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;


/// Sealed interface for A/B test traffic routing rules.
///
///
/// Each implementation defines a deterministic (or weighted-random) strategy
/// for resolving which variant a request should be routed to, based on
/// request headers, cookies, or configured percentage weights.
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

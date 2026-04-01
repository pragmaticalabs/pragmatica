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
    /// Resolves the variant name for a given request context.
    ///
    /// @param headers request headers
    /// @param cookies request cookies
    /// @return the variant name to route to
    String resolveVariant(Map<String, String> headers, Map<String, String> cookies);

    /// Routes based on a hash of a specific header value.
    ///
    /// @param headerName the header to hash
    /// @param variantCount number of variant buckets
    record HeaderHashSplit(String headerName, int variantCount) implements SplitRule {
        /// Factory method following JBCT naming convention.
        @SuppressWarnings("JBCT-VO-02")
        public static HeaderHashSplit headerHashSplit(String headerName, int variantCount) {
            return new HeaderHashSplit(headerName, variantCount);
        }

        @Override public String resolveVariant(Map<String, String> headers, Map<String, String> cookies) {
            var value = headers.getOrDefault(headerName, "");
            var hash = Math.abs(value.hashCode()) % variantCount;
            return "variant-" + hash;
        }
    }

    /// Routes based on a hash of a specific cookie value.
    ///
    /// @param cookieName the cookie to hash
    /// @param variantCount number of variant buckets
    record CookieHashSplit(String cookieName, int variantCount) implements SplitRule {
        /// Factory method following JBCT naming convention.
        @SuppressWarnings("JBCT-VO-02")
        public static CookieHashSplit cookieHashSplit(String cookieName, int variantCount) {
            return new CookieHashSplit(cookieName, variantCount);
        }

        @Override public String resolveVariant(Map<String, String> headers, Map<String, String> cookies) {
            var value = cookies.getOrDefault(cookieName, "");
            var hash = Math.abs(value.hashCode()) % variantCount;
            return "variant-" + hash;
        }
    }

    /// Routes based on exact match of a header value to a variant name.
    ///
    /// @param headerName the header to match
    /// @param valueToVariant mapping from header values to variant names
    /// @param defaultVariant variant to use when no match is found
    record HeaderMatchSplit(String headerName,
                            Map<String, String> valueToVariant,
                            String defaultVariant) implements SplitRule {
        /// Factory method following JBCT naming convention.
        @SuppressWarnings("JBCT-VO-02")
        public static HeaderMatchSplit headerMatchSplit(String headerName,
                                                        Map<String, String> valueToVariant,
                                                        String defaultVariant) {
            return new HeaderMatchSplit(headerName, valueToVariant, defaultVariant);
        }

        @Override public String resolveVariant(Map<String, String> headers, Map<String, String> cookies) {
            var value = headers.getOrDefault(headerName, "");
            return valueToVariant.getOrDefault(value, defaultVariant);
        }
    }

    /// Routes based on weighted random selection across variants.
    ///
    /// @param weights list of variant-weight pairs defining traffic distribution
    record PercentageSplit(List<VariantWeight> weights) implements SplitRule {
        /// Factory method following JBCT naming convention.
        @SuppressWarnings("JBCT-VO-02")
        public static PercentageSplit percentageSplit(List<VariantWeight> weights) {
            return new PercentageSplit(weights);
        }

        /// A variant name paired with its relative traffic weight.
        ///
        /// @param variant the variant identifier
        /// @param weight relative traffic weight (higher = more traffic)
        public record VariantWeight(String variant, int weight) {
            /// Factory method following JBCT naming convention.
            @SuppressWarnings("JBCT-VO-02")
            public static VariantWeight variantWeight(String variant, int weight) {
                return new VariantWeight(variant, weight);
            }
        }

        @Override public String resolveVariant(Map<String, String> headers, Map<String, String> cookies) {
            var totalWeight = weights.stream().mapToInt(VariantWeight::weight)
                                            .sum();
            if ( totalWeight <= 0) {
            return weights.isEmpty()
                   ? "variant-0"
                   : weights.getFirst().variant();}
            var random = ThreadLocalRandom.current().nextInt(totalWeight);
            return selectVariantByWeight(random);
        }

        private String selectVariantByWeight(int random) {
            var cumulative = 0;
            for ( var vw : weights) {
                cumulative += vw.weight();
                if ( random < cumulative) {
                return vw.variant();}
            }
            return weights.getLast().variant();
        }
    }
}

package org.pragmatica.jbct.shared;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Unit tests for the shared `excludePackages` / `[lint.layers]` glob compiler.
class PackageGlobTest {
    private static boolean matches(String glob, String packageName) {
        return PackageGlob.compile(glob)
                          .matcher(packageName)
                          .matches();
    }

    @Nested
    class AnyDepthWildcard {
        @Test
        void compile_anyDepth_matchesBarePackage() {
            assertThat(matches("com.example.core.**", "com.example.core")).isTrue();
        }

        @Test
        void compile_anyDepth_matchesDirectSubpackage() {
            assertThat(matches("com.example.core.**", "com.example.core.user")).isTrue();
        }

        @Test
        void compile_anyDepth_matchesDeepSubpackage() {
            assertThat(matches("com.example.core.**", "com.example.core.user.internal.dao")).isTrue();
        }

        @Test
        void compile_anyDepth_rejectsSiblingWithSharedPrefix() {
            assertThat(matches("com.example.core.**", "com.example.corex")).isFalse();
            assertThat(matches("com.example.core.**", "com.example.corex.user")).isFalse();
        }

        @Test
        void compile_anyDepth_rejectsParentPackage() {
            assertThat(matches("com.example.core.**", "com.example")).isFalse();
        }

        @Test
        void compile_anyDepth_rejectsUnrelatedPackage() {
            assertThat(matches("com.example.core.**", "com.other.core")).isFalse();
        }

        @Test
        void compile_bareAnyDepth_matchesEveryPackage() {
            assertThat(matches("**", "com")).isTrue();
            assertThat(matches("**", "com.example.core")).isTrue();
        }
    }

    @Nested
    class SingleSegmentWildcard {
        @Test
        void compile_singleStar_matchesExactlyOneSegment() {
            assertThat(matches("com.example.*", "com.example.core")).isTrue();
        }

        @Test
        void compile_singleStar_rejectsDeeperPackage() {
            assertThat(matches("com.example.*", "com.example.core.user")).isFalse();
        }

        @Test
        void compile_singleStar_rejectsBarePackage() {
            assertThat(matches("com.example.*", "com.example")).isFalse();
        }

        @Test
        void compile_singleStar_matchesPartialSegment() {
            assertThat(matches("com.*svc", "com.ordersvc")).isTrue();
            assertThat(matches("com.*svc", "com.order.svc")).isFalse();
        }
    }

    @Nested
    class AnyDepthPositions {
        @Test
        void compile_middleAnyDepth_spansZeroSegments() {
            assertThat(matches("com.**.impl", "com.impl")).isTrue();
        }

        @Test
        void compile_middleAnyDepth_spansOneSegment() {
            assertThat(matches("com.**.impl", "com.example.impl")).isTrue();
        }

        @Test
        void compile_middleAnyDepth_spansManySegments() {
            assertThat(matches("com.**.impl", "com.example.core.user.impl")).isTrue();
        }

        @Test
        void compile_middleAnyDepth_rejectsMissingSuffix() {
            assertThat(matches("com.**.impl", "com.example.core")).isFalse();
        }

        @Test
        void compile_middleAnyDepth_rejectsSiblingPrefix() {
            assertThat(matches("com.**.impl", "comx.example.impl")).isFalse();
        }

        @Test
        void compile_leadingAnyDepth_matchesBareAndNestedPackage() {
            assertThat(matches("**.adapter", "adapter")).isTrue();
            assertThat(matches("**.adapter", "com.example.adapter")).isTrue();
            assertThat(matches("**.adapter", "com.example.adapter.db")).isFalse();
        }

        @Test
        void compile_repeatedAnyDepth_collapsesIntoOne() {
            assertThat(matches("com.**.**.impl", "com.impl")).isTrue();
            assertThat(matches("com.**.**.impl", "com.example.core.impl")).isTrue();
        }
    }

    @Nested
    class LiteralGlobs {
        @Test
        void compile_literalGlob_matchesOnlyExactPackage() {
            assertThat(matches("com.example.adapter", "com.example.adapter")).isTrue();
            assertThat(matches("com.example.adapter", "com.example.adapter.db")).isFalse();
            assertThat(matches("com.example.adapter", "com.example.adapterx")).isFalse();
        }

        @Test
        void compile_dotSeparator_matchesOnlyRealDot() {
            assertThat(matches("com.example", "comXexample")).isFalse();
        }
    }
}

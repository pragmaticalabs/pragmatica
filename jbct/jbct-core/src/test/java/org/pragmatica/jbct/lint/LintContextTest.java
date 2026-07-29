package org.pragmatica.jbct.lint;

import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Unit tests for `excludePackages` glob matching — both the [LintContext#lintContext(List)] factory
/// and the [LintContext#withExcludePackages(List)] builder compile the same shared glob syntax.
class LintContextTest {
    @Nested
    class LintContextFactory {
        private final LintContext context = LintContext.lintContext(List.of("com.example.generated.**"));

        @Test
        void shouldLint_anyDepthGlob_excludesBarePackage() {
            assertThat(context.shouldLint("com.example.generated")).isFalse();
        }

        @Test
        void shouldLint_anyDepthGlob_excludesSubpackage() {
            assertThat(context.shouldLint("com.example.generated.dao")).isFalse();
            assertThat(context.shouldLint("com.example.generated.dao.internal")).isFalse();
        }

        @Test
        void shouldLint_anyDepthGlob_keepsSiblingWithSharedPrefix() {
            assertThat(context.shouldLint("com.example.generatedx")).isTrue();
            assertThat(context.shouldLint("com.example.generatedx.dao")).isTrue();
        }

        @Test
        void shouldLint_anyDepthGlob_keepsParentPackage() {
            assertThat(context.shouldLint("com.example")).isTrue();
        }

        @Test
        void shouldLint_noExcludes_lintsEverything() {
            assertThat(LintContext.lintContext(List.of()).shouldLint("com.example.generated")).isTrue();
        }
    }

    @Nested
    class WithExcludePackagesBuilder {
        private final LintContext context = LintContext.defaultContext()
                                                       .withExcludePackages(List.of("com.example.adapter.**"));

        @Test
        void shouldLint_anyDepthGlob_excludesBarePackage() {
            assertThat(context.shouldLint("com.example.adapter")).isFalse();
        }

        @Test
        void shouldLint_anyDepthGlob_excludesSubpackage() {
            assertThat(context.shouldLint("com.example.adapter.persistence")).isFalse();
        }

        @Test
        void shouldLint_anyDepthGlob_keepsSiblingWithSharedPrefix() {
            assertThat(context.shouldLint("com.example.adapters")).isTrue();
        }
    }

    @Nested
    class GlobPositions {
        @Test
        void shouldLint_singleStarGlob_excludesOneSegmentOnly() {
            var context = LintContext.lintContext(List.of("com.example.*"));

            assertThat(context.shouldLint("com.example.adapter")).isFalse();
            assertThat(context.shouldLint("com.example.adapter.db")).isTrue();
            assertThat(context.shouldLint("com.example")).isTrue();
        }

        @Test
        void shouldLint_middleAnyDepthGlob_excludesAnyDepthIncludingNone() {
            var context = LintContext.lintContext(List.of("com.**.impl"));

            assertThat(context.shouldLint("com.impl")).isFalse();
            assertThat(context.shouldLint("com.example.impl")).isFalse();
            assertThat(context.shouldLint("com.example.core.impl")).isFalse();
            assertThat(context.shouldLint("com.example.core")).isTrue();
        }

        @Test
        void shouldLint_leadingAnyDepthGlob_excludesBareAndNestedPackage() {
            var context = LintContext.lintContext(List.of("**.infrastructure"));

            assertThat(context.shouldLint("infrastructure")).isFalse();
            assertThat(context.shouldLint("com.example.infrastructure")).isFalse();
            assertThat(context.shouldLint("com.example.infrastructure.db")).isTrue();
        }
    }
}

package org.pragmatica.jbct.lint.layer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLinter;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// Layering-coverage census (#534).
///
/// The corpus deliberately uses packages with no convention layer keyword (`com.example.cloud.*`,
/// `com.example.util`), so an explicit `[lint.layers]` config is the only thing that can classify
/// them — the shape of the aether measurement the census exists to surface (adapter-only config: 0
/// findings over 46 provider files; same config plus a domain catch-all: 37).
class LayerCoverageTest {
    /// Classifies the cloud packages only — every other package stays unclassified.
    private static final LayerConfig ADAPTER_ONLY = LayerConfig.layerConfig(List.of(),
                                                                            List.of(),
                                                                            List.of("com.example.cloud.**"),
                                                                            List.of(),
                                                                            List.of());

    /// The same config widened by a domain glob, so the previously unclassified file is ranked.
    private static final LayerConfig WIDENED = LayerConfig.layerConfig(List.of("com.example.util"),
                                                                       List.of(),
                                                                       List.of("com.example.cloud.**"),
                                                                       List.of(),
                                                                       List.of());

    private static final String CLOUD_SOURCE = """
            package com.example.cloud.hetzner;
            class Client {}
            """;

    private static final String UTIL_SOURCE = """
            package com.example.util;
            class Helper {}
            """;

    private static final String TOOLS_SOURCE = """
            package com.example.tools;
            class Tool {}
            """;

    /// Would violate JBCT-ARCH-01 (util -> cloud is domain -> adapter) once the file's own package is
    /// ranked; silent while it is unclassified.
    private static final String ARCH_DIRECTION_SOURCE = """
            package com.example.util;
            import com.example.cloud.hetzner.Client;
            class Helper {}
            """;

    /// Would violate JBCT-ARCH-02 (lift outside the adapter zone) once the file's own package is
    /// ranked; silent while it is unclassified.
    private static final String ARCH_LIFT_SOURCE = """
            package com.example.util;
            class Helper {
                Object run() { return Result.lift(Err::new, () -> io()); }
            }
            """;

    @Nested
    class Gating {
        @Test
        void coverage_isAbsent_withoutLayersConfig() {
            assertEquals(Option.none(),
                         LayerCoverage.coverageOfSources(sources(CLOUD_SOURCE, UTIL_SOURCE),
                                                         LintContext.defaultContext()));
        }

        @Test
        void coverage_isAbsent_forEmptyLayersSection() {
            assertEquals(Option.none(),
                         LayerCoverage.coverageOfSources(sources(CLOUD_SOURCE, UTIL_SOURCE),
                                                         context(LayerConfig.DEFAULT)));
        }

        @Test
        void coverage_isPresent_withExplicitLayersConfig() {
            assertTrue(LayerCoverage.coverageOfSources(sources(CLOUD_SOURCE), context(ADAPTER_ONLY))
                                    .isPresent());
        }
    }

    @Nested
    class Counts {
        @Test
        void coverage_countsClassifiedAndUnclassified_forMixedCorpus() {
            assertEquals(Option.some(new LayerCoverage(1, 2, 0)),
                         LayerCoverage.coverageOfSources(sources(CLOUD_SOURCE, UTIL_SOURCE, TOOLS_SOURCE),
                                                         context(ADAPTER_ONLY)));
        }

        @Test
        void coverage_countsExcludedPackages_separately() {
            var context = context(ADAPTER_ONLY).withExcludePackages(List.of("com.example.tools"));

            assertEquals(Option.some(new LayerCoverage(1, 1, 1)),
                         LayerCoverage.coverageOfSources(sources(CLOUD_SOURCE, UTIL_SOURCE, TOOLS_SOURCE), context));
        }

        @Test
        void total_sumsAllBuckets() {
            assertEquals(3, new LayerCoverage(1, 1, 1).total());
        }

        @Test
        void coverage_countsMissingPackageDeclaration_asUnclassified() {
            assertEquals(Option.some(new LayerCoverage(0, 1, 0)),
                         LayerCoverage.coverageOfSources(sources("class Rootless {}\n"), context(ADAPTER_ONLY)));
        }

        @Test
        void coverage_ignoresPackageDeclarationInsideBlockComment() {
            assertEquals(Option.some(new LayerCoverage(0, 1, 0)),
                         LayerCoverage.coverageOfSources(sources("""
                                 /*
                                 package com.example.cloud.legacy;
                                 */
                                 package com.example.util;
                                 class Helper {}
                                 """), context(ADAPTER_ONLY)));
        }

        @Test
        void coverage_ignoresPackageDeclarationInsideLineComment() {
            assertEquals(Option.some(new LayerCoverage(0, 1, 0)),
                         LayerCoverage.coverageOfSources(sources("""
                                 // package com.example.cloud.legacy;
                                 package com.example.util;
                                 class Helper {}
                                 """), context(ADAPTER_ONLY)));
        }

        @Test
        void coverage_readsCollectedPaths_skippingUnreadableOnes(@TempDir Path dir) throws IOException {
            var paths = List.of(write(dir, "Client.java", CLOUD_SOURCE),
                                write(dir, "Helper.java", UTIL_SOURCE),
                                write(dir, "Tool.java", TOOLS_SOURCE),
                                dir.resolve("Missing.java"));

            assertEquals(Option.some(new LayerCoverage(1, 2, 0)),
                         LayerCoverage.coverage(paths, context(ADAPTER_ONLY)));
        }

        private Path write(Path dir, String name, String content) throws IOException {
            return Files.writeString(dir.resolve(name), content);
        }
    }

    @Nested
    class Rendering {
        @Test
        void render_reportsEvaluatedOfTotal() {
            assertEquals("layering: evaluated 46 of 3458 files, 3412 unclassified",
                         new LayerCoverage(46, 3412, 0).render());
        }

        @Test
        void render_appendsExcludedCount_whenPackagesExcluded() {
            assertEquals("layering: evaluated 46 of 3458 files, 3400 unclassified, 12 excluded",
                         new LayerCoverage(46, 3400, 12).render());
        }
    }

    /// The signal's reason for existing: both layering rules skip a file whose own package has no
    /// rank, and the census is what accounts for that skip.
    @Nested
    class ArchRuleSilence {
        @Test
        void archDirection_staysSilent_forFileTheCensusCountsUnclassified() {
            assertFalse(hasRule("JBCT-ARCH-01", ARCH_DIRECTION_SOURCE, context(ADAPTER_ONLY)));
            assertEquals(Option.some(new LayerCoverage(0, 1, 0)),
                         LayerCoverage.coverageOfSources(sources(ARCH_DIRECTION_SOURCE), context(ADAPTER_ONLY)));
        }

        @Test
        void archDirection_fires_whenConfigClassifiesTheSameFile() {
            assertTrue(hasRule("JBCT-ARCH-01", ARCH_DIRECTION_SOURCE, context(WIDENED)));
            assertEquals(Option.some(new LayerCoverage(1, 0, 0)),
                         LayerCoverage.coverageOfSources(sources(ARCH_DIRECTION_SOURCE), context(WIDENED)));
        }

        @Test
        void archLiftZone_staysSilent_forFileTheCensusCountsUnclassified() {
            assertFalse(hasRule("JBCT-ARCH-02", ARCH_LIFT_SOURCE, context(ADAPTER_ONLY)));
            assertEquals(Option.some(new LayerCoverage(0, 1, 0)),
                         LayerCoverage.coverageOfSources(sources(ARCH_LIFT_SOURCE), context(ADAPTER_ONLY)));
        }

        @Test
        void archLiftZone_fires_whenConfigClassifiesTheSameFile() {
            assertTrue(hasRule("JBCT-ARCH-02", ARCH_LIFT_SOURCE, context(WIDENED)));
            assertEquals(Option.some(new LayerCoverage(1, 0, 0)),
                         LayerCoverage.coverageOfSources(sources(ARCH_LIFT_SOURCE), context(WIDENED)));
        }

        @Test
        void bothArchRules_staySilent_whileTheCensusCountsBothFiles() {
            assertFalse(hasRule("JBCT-ARCH-01", ARCH_DIRECTION_SOURCE, context(ADAPTER_ONLY)));
            assertFalse(hasRule("JBCT-ARCH-02", ARCH_LIFT_SOURCE, context(ADAPTER_ONLY)));
            assertEquals(Option.some(new LayerCoverage(1, 2, 0)),
                         LayerCoverage.coverageOfSources(sources(CLOUD_SOURCE, ARCH_DIRECTION_SOURCE, ARCH_LIFT_SOURCE),
                                                         context(ADAPTER_ONLY)));
        }

        private boolean hasRule(String ruleId, String source, LintContext context) {
            return CstLinter.cstLinter(context)
                            .lint(SourceFile.sourceFile(Path.of("Test.java"), source))
                            .onFailure(cause -> fail("Parse failed: " + cause.message()))
                            .or(List.<Diagnostic> of())
                            .stream()
                            .anyMatch(diagnostic -> diagnostic.ruleId()
                                                              .equals(ruleId));
        }
    }

    private static LintContext context(LayerConfig layers) {
        return LintContext.defaultContext()
                          .withLayers(layers);
    }

    private static List<SourceFile> sources(String... contents) {
        return Arrays.stream(contents)
                     .map(content -> SourceFile.sourceFile(Path.of("Test.java"), content))
                     .toList();
    }
}

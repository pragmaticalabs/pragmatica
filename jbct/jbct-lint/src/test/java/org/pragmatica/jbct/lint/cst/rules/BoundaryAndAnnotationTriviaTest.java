package org.pragmatica.jbct.lint.cst.rules;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLinter;
import org.pragmatica.jbct.shared.SourceFile;

import static org.assertj.core.api.Assertions.assertThat;

/// Regression coverage for two defects reported from a real upgrade (peglib, ~600 tests).
///
/// Both were silent. A trailing comment after a BARE annotation put the comment inside the
/// `QualifiedName` node's span, so a name comparison against that node's raw text stopped
/// matching — which reads as the annotation having no effect. And matching a boundary type by
/// its last dotted segment made every domain type named `Optional` a false positive.
///
/// Each case is paired with the variant that already worked, so the asymmetry itself is the
/// assertion.
class BoundaryAndAnnotationTriviaTest {
    private CstLinter linter;

    @BeforeEach
    void setUp() {
        linter = CstLinter.cstLinter(LintContext.defaultContext());
    }

    private List<String> rulesFor(String fileName, String source) {
        return linter.lint(SourceFile.sourceFile(Path.of(fileName), source))
                     .map(diagnostics -> diagnostics.stream()
                                                    .map(Diagnostic::ruleId)
                                                    .toList())
                     .or(List.of());
    }

    // ===== A bare annotation still applies when a comment follows it =====

    @Test
    void contract_suppressesReturnKind_withTrailingComment() {
        assertThat(rulesFor("ContractRepro.java", """
                package demo;

                import org.pragmatica.lang.Contract;

                public final class ContractRepro {
                    @Contract  // JVM entry-point contract: main must return void.
                    public static void main(String[] args) {
                        System.out.println("hello");
                    }
                }
                """)).doesNotContain("JBCT-RET-01");
    }

    @Test
    void contract_suppressesReturnKind_withoutTrailingComment() {
        assertThat(rulesFor("ContractRepro.java", """
                package demo;

                import org.pragmatica.lang.Contract;

                public final class ContractRepro {
                    @Contract
                    public static void main(String[] args) {
                        System.out.println("hello");
                    }
                }
                """)).doesNotContain("JBCT-RET-01");
    }

    @Test
    void returnKind_stillFires_whenContractIsAbsent() {
        assertThat(rulesFor("Plain.java", """
                package demo;

                public final class Plain {
                    public static void main(String[] args) {
                        System.out.println("hello");
                    }
                }
                """)).contains("JBCT-RET-01");
    }

    /// The same defect suppressed a rule rather than a diagnostic: an annotated test method
    /// stopped being recognised as a test at all.
    @Test
    void testNaming_stillFires_whenTestAnnotationCarriesTrailingComment() {
        assertThat(rulesFor("TestNameRepro.java", """
                package demo;

                import org.junit.jupiter.api.Test;

                public class TestNameRepro {
                    @Test  // trailing comment
                    void badnamewithcomment() {}
                }
                """)).contains("JBCT-NAM-05");
    }

    // ===== A boundary type is matched by origin, not by simple name =====

    @Test
    void boundaryType_ignoresDomainTypeSharingTheSimpleName() {
        assertThat(rulesFor("BoundaryRepro.java", """
                package demo;

                public final class BoundaryRepro {
                    public sealed interface Expression permits Expression.Literal, Expression.Optional {
                        record Literal(String text) implements Expression {}

                        record Optional(Expression inner) implements Expression {}
                    }

                    public static String describe(Expression expr) {
                        return switch (expr) {
                            case Expression.Literal lit -> lit.text();
                            case Expression.Optional opt -> describe(opt.inner()) + "?";
                        };
                    }
                }
                """)).doesNotContain("JBCT-BND-01");
    }

    @Test
    void boundaryType_flagsJdkOptional_viaImport() {
        assertThat(rulesFor("Leak.java", """
                package demo;

                import java.util.Optional;

                public class Leak {
                    Optional<String> find() {
                        return Optional.empty();
                    }
                }
                """)).contains("JBCT-BND-01");
    }

    @Test
    void boundaryType_flagsJdkOptional_viaFullyQualifiedUse() {
        assertThat(rulesFor("Leak.java", """
                package demo;

                public class Leak {
                    java.util.Optional<String> find() {
                        return null;
                    }
                }
                """)).contains("JBCT-BND-01");
    }

    @Test
    void boundaryType_flagsJdkOptional_viaStarImport() {
        assertThat(rulesFor("Leak.java", """
                package demo;

                import java.util.*;

                public class Leak {
                    Optional<String> find() {
                        return Optional.empty();
                    }
                }
                """)).contains("JBCT-BND-01");
    }

    /// A locally declared type shadows a star import, so the star import alone is not evidence.
    @Test
    void boundaryType_ignoresLocalTypeShadowingAStarImport() {
        assertThat(rulesFor("Shadow.java", """
                package demo;

                import java.util.*;

                public class Shadow {
                    public record Optional(String inner) {}

                    Optional wrap(String s) {
                        return new Optional(s);
                    }
                }
                """)).doesNotContain("JBCT-BND-01");
    }

    @Test
    void boundaryType_ignoresSameNamedTypeImportedFromElsewhere() {
        assertThat(rulesFor("Elsewhere.java", """
                package demo;

                import com.acme.Optional;

                public class Elsewhere {
                    Optional wrap(String s) {
                        return null;
                    }
                }
                """)).doesNotContain("JBCT-BND-01");
    }
}

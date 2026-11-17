package org.pragmatica.jbct.lint.cst.rules;

import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLinter;
import org.pragmatica.jbct.shared.SourceFile;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// #1539: the `See:` link a finding prints must point at the anchor of its rule on the published rule catalogue, not at a
/// path in the coding-technology repository (`series/`, `skills/`), which no longer exists and answers 404.
class DocLinkTargetTest {
    private static final String CATALOGUE = "https://pragmatica.dev/java/jbct/rules/#";
    private static final List<String> LINKED_RULES = List.of("JBCT-RET-01", "JBCT-RET-02", "JBCT-RET-03", "JBCT-SEAL-01");

    @Test
    void everyLinkedFinding_printsItsRuleAnchorOnTheCatalogue() {
        var fixtures = RuleFixtures.all()
                                   .stream()
                                   .filter(fixture -> LINKED_RULES.contains(fixture.ruleId()))
                                   .toList();

        assertEquals(LINKED_RULES.size(), fixtures.size(), "a fixture per linked rule");
        fixtures.forEach(DocLinkTargetTest::assertLinkIsCatalogueAnchor);
    }

    private static void assertLinkIsCatalogueAnchor(RuleFixtures.RuleFixture fixture) {
        var diagnostic = lint(fixture.positiveSource()).stream()
                                                       .filter(d -> d.ruleId().equals(fixture.ruleId()))
                                                       .findFirst()
                                                       .orElseGet(() -> fail("no finding for " + fixture.ruleId()));

        assertEquals(CATALOGUE + fixture.ruleId().toLowerCase(Locale.ROOT),
                     diagnostic.docLink().or("<none>"),
                     fixture.ruleId());
    }

    private static List<Diagnostic> lint(String source) {
        return CstLinter.cstLinter(LintContext.defaultContext())
                        .lint(SourceFile.sourceFile(Path.of("Test.java"), source))
                        .onFailure(cause -> fail("Parse failed: " + cause.message()))
                        .or(List.of());
    }
}

package org.pragmatica.jbct.lint.cst.rules;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.pragmatica.jbct.config.JbctMode;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.DiagnosticSeverity;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.TechnicalWords;
import org.pragmatica.jbct.lint.cst.CstLinter;
import org.pragmatica.jbct.shared.SourceFile;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// JBCT-NAM-06 (technology or pattern word in a type name) and JBCT-NAM-07 (in a package segment), #2529.
class TechnicalNameRuleTest {
    private static final String TYPE_RULE = "JBCT-NAM-06";
    private static final String PACKAGE_RULE = "JBCT-NAM-07";

    // ---- part 1: JBCT-NAM-06, type names ----

    @ParameterizedTest(name = "type name carrying {0} is flagged")
    @ValueSource(strings = {"Repository", "Dao", "Controller", "Adapter", "Gateway", "Client", "Impl", "Service",
                            "Manager", "Helper", "Util", "Jooq", "Jpa", "Jdbc", "Postgres", "Http", "Rest", "Kafka"})
    void typeName_isFlagged_forEveryDefaultWord(String word) {
        assertTrue(hasType("package org.example;\nclass Seat" + word + " {}\n"), "suffix " + word);
        assertTrue(hasType("package org.example;\nclass " + word + "Seat {}\n"), "prefix " + word);
    }

    @ParameterizedTest(name = "type name {0} is clean")
    @ValueSource(strings = {"LoadSeat", "Restaurant", "Services", "Seat", "Clients", "Utility", "Managed", "Hypertext"})
    void typeName_isClean_whenNoPartIsAWord(String name) {
        assertFalse(hasType("package org.example;\nclass " + name + " {}\n"));
    }

    @Test
    void typeName_isFlagged_forEveryTypeKindAndForNestedTypes() {
        assertTrue(hasType("package org.example;\ninterface SeatGateway {}\n"));
        assertTrue(hasType("package org.example;\nrecord SeatClient(int id) {}\n"));
        assertTrue(hasType("package org.example;\nenum SeatManager { ONE }\n"));
        assertTrue(hasType("package org.example;\nclass Seat {\n    interface Lookup {}\n    class LookupHelper {}\n}\n"));
    }

    @Test
    void typeName_isSplitAtAcronymBoundaries() {
        assertTrue(hasType("package org.example;\nclass SeatDAO {}\n"));
        assertTrue(hasType("package org.example;\nclass HTTPSeat {}\n"));
        assertFalse(hasType("package org.example;\nclass SeatDAOs {}\n"));
    }

    @Test
    void typeName_reportsTheFlaggedPartsOnTheDeclarationLineAtInfo() {
        var diagnostics = lint("package org.example;\n\nclass JooqSeatRepository {}\n", LintContext.defaultContext(), TYPE_RULE);

        assertEquals(1, diagnostics.size());
        assertEquals(3, diagnostics.getFirst().line());
        assertEquals(DiagnosticSeverity.INFO, diagnostics.getFirst().severity());
        assertTrue(diagnostics.getFirst().message().contains("token 'Jooq' matches word 'jooq', token 'Repository' matches word 'repository'"),
                   diagnostics.getFirst().message());
        assertTrue(diagnostics.getFirst().details().contains("whole camel-case token"), diagnostics.getFirst().details());
    }

    @Test
    void add_extendsTheList_forTypeNames() {
        var context = withWords(TechnicalWords.technicalWords(List.of("Saga"), List.of()));

        assertTrue(hasType("package org.example;\nclass BookingSaga {}\n", context));
        assertFalse(hasType("package org.example;\nclass BookingSaga {}\n"));
    }

    @Test
    void add_ofASingleTokenWord_matchesATypeNameToken() {
        var context = withWords(TechnicalWords.technicalWords(List.of("Postgres"), List.of("jooq")));

        assertTrue(hasType("package org.example;\nclass PostgresOrderStore {}\n", context));
    }

    @Test
    void remove_narrowsTheList_forTypeNames() {
        var context = withWords(TechnicalWords.technicalWords(List.of(), List.of("Service", "client")));

        assertFalse(hasType("package org.example;\nclass SeatService {}\n", context));
        assertFalse(hasType("package org.example;\nclass SeatClient {}\n", context));
        assertTrue(hasType("package org.example;\nclass SeatManager {}\n", context));
        assertTrue(hasType("package org.example;\nclass SeatService {}\n"));
    }

    @Test
    void typeName_isSuppressed_bySuppressWarningsOnTheType() {
        assertFalse(hasType("""
                package org.example;
                @SuppressWarnings("JBCT-NAM-06")
                class SeatService {}
                """));
        assertTrue(hasType("""
                package org.example;
                @SuppressWarnings("JBCT-NAM-07")
                class SeatService {}
                """));
    }

    @Test
    void typeName_isSilent_outsideBusinessMode() {
        var infrastructure = LintContext.defaultContext()
                                        .withProject(JbctMode.INFRASTRUCTURE, Option.none());

        assertFalse(hasType("package org.example;\nclass SeatService {}\n", infrastructure));
        assertTrue(new CstTechnicalTypeNameRule().modes().equals(Set.of(JbctMode.BUSINESS)));
    }

    // ---- part 2: JBCT-NAM-07, package segments ----

    @ParameterizedTest(name = "package segment {0} is flagged")
    @ValueSource(strings = {"repository", "dao", "controller", "adapter", "gateway", "client", "impl", "service", "manager",
                            "helper", "util", "jooq", "jpa", "jdbc", "postgres", "http", "rest", "kafka", "internal", "utils",
                            "common", "persistence", "usecase", "domain", "application", "infra", "integration",
                            "bootstrap", "config"})
    void packageSegment_isFlagged_forEveryDefaultWord(String word) {
        assertTrue(hasPackage("package org.example." + word + ".seats;\nclass Seat {}\n"), "inner " + word);
        assertTrue(hasPackage("package org.example.seats." + word + ";\nclass Seat {}\n"), "last " + word);
    }

    @ParameterizedTest(name = "package {0} is clean")
    @ValueSource(strings = {"org.example", "org.example.seats", "org.example.restaurant", "org.example.services",
                            "org.example.domainmodel", "org.example.seat.booking"})
    void packageSegment_isClean_whenNoSegmentIsAWord(String name) {
        assertFalse(hasPackage("package " + name + ";\nclass Seat {}\n"));
    }

    @Test
    void packageSegment_isMatchedCaseInsensitively_andOnWholeSegmentsOnly() {
        assertTrue(hasPackage("package org.example.Usecase.seats;\nclass Seat {}\n"));
        assertFalse(hasPackage("package org.example.usecases;\nclass Seat {}\n"));
    }

    @Test
    void packageSegment_reportsOnePerFileOnThePackageLine_listingEverySegmentAtInfo() {
        var diagnostics = lint("// header\npackage org.example.usecase.adapter;\n\nclass Seat {}\nclass Booking {}\n",
                               LintContext.defaultContext(),
                               PACKAGE_RULE);

        assertEquals(1, diagnostics.size());
        assertEquals(2, diagnostics.getFirst().line());
        assertEquals(DiagnosticSeverity.INFO, diagnostics.getFirst().severity());
        assertTrue(diagnostics.getFirst().message().contains("segment 'usecase' matches word 'usecase', segment 'adapter' matches word 'adapter'"),
                   diagnostics.getFirst().message());
        assertTrue(diagnostics.getFirst().details().contains("whole segment"), diagnostics.getFirst().details());
    }

    @Test
    void packageSegment_isSilent_withoutPackageDeclaration() {
        assertFalse(hasPackage("class Seat {}\n"));
    }

    @Test
    void add_extendsTheList_forPackageSegments() {
        var context = withWords(TechnicalWords.technicalWords(List.of("Saga"), List.of()));

        assertTrue(hasPackage("package org.example.saga;\nclass Seat {}\n", context));
        assertFalse(hasPackage("package org.example.saga;\nclass Seat {}\n"));
    }

    @Test
    void remove_narrowsTheList_forPackageSegments() {
        var context = withWords(TechnicalWords.technicalWords(List.of(), List.of("domain", "Client")));

        assertFalse(hasPackage("package org.example.domain;\nclass Seat {}\n", context));
        assertFalse(hasPackage("package org.example.client;\nclass Seat {}\n", context));
        assertTrue(hasPackage("package org.example.usecase;\nclass Seat {}\n", context));
        assertTrue(hasPackage("package org.example.domain;\nclass Seat {}\n"));
    }

    @Test
    void packageSegment_isSuppressed_byExcludedPackageAndByRuleOff() {
        var excluded = LintContext.defaultContext()
                                  .withExcludePackages(List.of("org.example.usecase.**"));
        var off = LintContext.defaultContext();
        var disabled = off.withConfig(off.config().withDisabledRule(PACKAGE_RULE));
        var source = "package org.example.usecase.seats;\nclass Seat {}\n";

        assertTrue(hasPackage(source));
        assertFalse(hasPackage(source, excluded));
        assertTrue(CstLinter.cstLinter(disabled)
                            .lint(SourceFile.sourceFile(Path.of("Test.java"), source))
                            .or(List.of())
                            .stream()
                            .noneMatch(diagnostic -> diagnostic.ruleId().equals(PACKAGE_RULE)));
    }

    @Test
    void packageSegment_isSilent_outsideBusinessMode() {
        var infrastructure = LintContext.defaultContext()
                                        .withProject(JbctMode.INFRASTRUCTURE, Option.none());

        assertFalse(hasPackage("package org.example.usecase;\nclass Seat {}\n", infrastructure));
        assertEquals(Set.of(JbctMode.BUSINESS), new CstTechnicalPackageSegmentRule().modes());
    }

    private static boolean hasPackage(String source) {
        return hasPackage(source, LintContext.defaultContext());
    }

    private static boolean hasPackage(String source, LintContext context) {
        return !lint(source, context, PACKAGE_RULE).isEmpty();
    }

    private static LintContext withWords(TechnicalWords words) {
        var context = LintContext.defaultContext();

        return context.withConfig(context.config().withTechnicalWords(words));
    }

    private static boolean hasType(String source) {
        return hasType(source, LintContext.defaultContext());
    }

    private static boolean hasType(String source, LintContext context) {
        return !lint(source, context, TYPE_RULE).isEmpty();
    }

    private static List<Diagnostic> lint(String source, LintContext context, String ruleId) {
        return CstLinter.cstLinter(context)
                        .lint(SourceFile.sourceFile(Path.of("Test.java"), source))
                        .onFailure(cause -> fail("Parse failed: " + cause.message()))
                        .or(List.of())
                        .stream()
                        .filter(diagnostic -> diagnostic.ruleId()
                                                        .equals(ruleId))
                        .toList();
    }
}

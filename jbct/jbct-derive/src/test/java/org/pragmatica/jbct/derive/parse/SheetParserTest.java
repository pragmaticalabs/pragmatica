package org.pragmatica.jbct.derive.parse;

import org.junit.jupiter.api.Test;
import org.pragmatica.jbct.derive.Sheets;
import org.pragmatica.jbct.derive.model.Mode;
import org.pragmatica.lang.Cause;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// Parser-level tests: structural faults fail as [SheetError] with sheet-line fidelity, and the
/// living-system inputs parse into the typed model. Semantic completeness is the entry gate's job
/// (see EntryGateTest), not the parser's.
class SheetParserTest {
    private static Cause failureOf(String content) {
        return SheetParser.parse(content, "t.toml").fold(cause -> cause, sheet -> fail("expected a parse failure"));
    }

    @Test
    void parse_reportsLine_forMalformedToml() {
        var cause = failureOf("""
            schema_version = "0.1"
            [meta]
            system = "x"
            broken line without equals
            """);

        assertThat(cause).isInstanceOf(SheetError.class);
        assertThat(((SheetError) cause).line()).isEqualTo(4);
    }

    @Test
    void parse_fails_whenSchemaVersionMissing() {
        var cause = failureOf("""
            [meta]
            system = "x"
            era = "now"
            mode = "greenfield"
            """);

        assertThat(cause.message()).contains("schema_version");
    }

    @Test
    void parse_fails_whenSchemaMajorUnsupported() {
        var cause = failureOf("""
            schema_version = "1.0"
            [meta]
            system = "x"
            era = "now"
            mode = "greenfield"
            """);

        assertThat(cause.message()).contains("Unsupported schema_version");
    }

    @Test
    void parse_fails_whenMetaMissing() {
        var cause = failureOf("""
            schema_version = "0.1"
            """);

        assertThat(cause.message()).contains("meta");
    }

    @Test
    void parse_fails_whenRowMissingScope() {
        var cause = failureOf("""
            schema_version = "0.1"
            [meta]
            system = "x"
            era = "now"
            mode = "greenfield"
            [[answers.q1]]
            statement = "P95 under 200ms"
            status = "answered"
            """);

        assertThat(cause.message()).contains("answers.q1");
        assertThat(cause.message()).contains("scope");
    }

    @Test
    void parse_fails_whenDateIsUnquoted() {
        // Divergence from SPEC.md §3 (which shows `date = 2026-07-12`): the shared TomlParser rejects
        // bare dates, so this fails loudly with line info and advice to quote — it never vanishes.
        var cause = failureOf("""
            schema_version = "0.1"
            [meta]
            system = "x"
            era = "now"
            date = 2026-07-12
            mode = "greenfield"
            """);

        assertThat(cause).isInstanceOf(SheetError.class);
        assertThat(((SheetError) cause).line()).isEqualTo(5);
        assertThat(cause.message().toLowerCase()).contains("quote");
        assertThat(cause.message()).contains("date");
    }

    @Test
    void sheetLines_locatesSpacedHeader_atCorrectLine() {
        var lines = SheetLines.of("""
            schema_version = "0.1"
            [[ answers.q1 ]]
            scope = "path:x"
            [[answers.q1]]
            scope = "path:y"
            """);

        assertThat(lines.lineFor("answers.q1", 0)).isEqualTo(2);
        assertThat(lines.lineFor("answers.q1", 1)).isEqualTo(4);
    }

    @Test
    void parse_readsCurrentVectorAndFloors_forLivingSheet() {
        SheetParser.parse(Sheets.load("living-system.toml"), "living-system.toml")
                   .onFailure(cause -> fail("living sheet did not parse: " + cause.message()))
                   .onSuccess(sheet -> {
                       assertThat(sheet.meta().mode()).isEqualTo(Mode.LIVING);
                       assertThat(sheet.currentVector().isPresent()).isTrue();
                       assertThat(sheet.floors()).hasSize(1);
                       assertThat(sheet.floors().getFirst().hops()).hasSize(2);
                       sheet.currentVector()
                            .onPresent(vector -> {
                                assertThat(vector.topology()).hasSize(1);
                                assertThat(vector.recovery()).hasSize(1);
                            });
                   });
    }
}

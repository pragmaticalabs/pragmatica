package org.pragmatica.jbct.derive.emit;

import org.junit.jupiter.api.Test;
import org.pragmatica.jbct.derive.Sheets;
import org.pragmatica.jbct.derive.pipeline.Derive;
import org.pragmatica.jbct.derive.result.DeriveResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// Emit stage: the derive result renders to a human markdown report and a machine JSON document,
/// each carrying the full result (vector, pressure matrix, decision records, halts, judgment points).
class EmitTest {
    @Test
    void markdown_carriesEverySection_forACleanRun() {
        var report = MarkdownReport.render(derive("companies-house.toml"));

        assertThat(report).contains("# Derivation — companies-house");
        assertThat(report).contains("## Derived vector");
        assertThat(report).contains("## Pressure matrix (inert rows included)");
        assertThat(report).contains("## Mandate strikes (prune)");
        assertThat(report).contains("## Judgment points (emitted, never resolved)");
        assertThat(report).contains("substrate:private-only");
    }

    @Test
    void json_isBalanced_andCarriesExitCodeAndSections() {
        var json = JsonReport.render(derive("companies-house.toml"));

        assertThat(json).startsWith("{").endsWith("}");
        assertThat(balanced(json)).as("braces balance").isTrue();
        assertThat(json).contains("\"exit_code\":3");
        assertThat(json).contains("\"derived_vector\":[");
        assertThat(json).contains("\"pressure_matrix\":[");
        assertThat(json).contains("\"judgment_points\":[");
    }

    @Test
    void markdown_showsGateRejection_forFakeAnswer() {
        var sheet = """
            schema_version = "0.1"
            [meta]
            system = "t"
            era    = "e"
            mode   = "greenfield"
            [[answers.q1]]
            scope     = "path:render"
            statement = "P95 under 50ms"
            shape     = "system-clock"
            status    = "answered"
            """;
        var result = Derive.derive(sheet, "fake.toml")
                           .fold(cause -> fail("parse failed: " + cause.message()), derived -> derived);

        assertThat(result.gatePassed()).isFalse();
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(MarkdownReport.render(result)).contains("sheet rejected");
    }

    private static DeriveResult derive(String fileName) {
        return Derive.derive(Sheets.load(fileName), fileName)
                     .fold(cause -> fail("derive failed: " + cause.message()), derived -> derived);
    }

    private static boolean balanced(String json) {
        return json.chars().filter(c -> c == '{').count() == json.chars().filter(c -> c == '}').count()
            && json.chars().filter(c -> c == '[').count() == json.chars().filter(c -> c == ']').count();
    }
}

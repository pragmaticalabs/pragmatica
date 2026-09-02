package org.pragmatica.jbct.derive.pipeline;

import org.junit.jupiter.api.Test;
import org.pragmatica.jbct.derive.Inline;
import org.pragmatica.jbct.derive.Sheets;
import org.pragmatica.jbct.derive.model.AnswerSheet;
import org.pragmatica.jbct.derive.pipeline.Resolve.ResolveResult;
import org.pragmatica.jbct.derive.pipeline.Verify.VerifyResult;
import org.pragmatica.jbct.derive.result.Verification;
import org.pragmatica.jbct.derive.result.Verification.Status;

import static org.assertj.core.api.Assertions.assertThat;

/// Verify stage: the exit-gate arithmetic. Rule 1 (latency decomposition) runs against supplied
/// floors; a missing floor is `UNVERIFIED`, never a default; a floor exceeding its budget halts.
class VerifyTest {
    private static final String HEAD = """
        schema_version = "0.1"
        [meta]
        system = "t"
        era    = "e"
        mode   = "greenfield"
        """;

    @Test
    void verify_isUnverified_whenFloorMissing() {
        var result = verify(HEAD + """
            [[answers.q1]]
            scope     = "path:render"
            statement = "P95 under 50ms"
            shape     = "system-clock"
            price     = "felt speed"
            status    = "answered"
            """);

        assertThat(latency(result).status()).isEqualTo(Status.UNVERIFIED);
        assertThat(latency(result).detail()).contains("floor missing");
    }

    @Test
    void verify_isVerified_whenFloorWithinBudget() {
        var sheet = Inline.sheet(Sheets.load("living-system.toml"));
        var resolved = Resolve.resolve(sheet, Prune.prune(sheet), Press.press(sheet));
        var result = Verify.verify(sheet, resolved.vector(), resolved.recovery());

        assertThat(latency(result).status()).isEqualTo(Status.VERIFIED);
        assertThat(result.halts()).isEmpty();
    }

    @Test
    void verify_halts_whenFloorExceedsBudget() {
        var result = verify(HEAD + """
            [[answers.q1]]
            scope     = "path:x"
            statement = "P95 under 5ms"
            shape     = "system-clock"
            price     = "felt speed"
            status    = "answered"

            [[floors]]
            path = "path:x"
            hops = [{ name = "db", p50_ms = 10 }]
            """);

        assertThat(latency(result).status()).isEqualTo(Status.HALT);
        assertThat(result.halts()).isNotEmpty();
    }

    @Test
    void verify_reportsMechanismBill_always() {
        var result = verify(HEAD + """
            [[answers.q5]]
            scope     = "path:reads"
            statement = "high volume"
            shape     = "volume"
            status    = "answered"
            """);

        assertThat(result.verifications().stream().anyMatch(line -> line.rule().startsWith("mechanism bill"))).isTrue();
    }

    private static VerifyResult verify(String toml) {
        AnswerSheet sheet = Inline.sheet(toml);
        ResolveResult resolved = Resolve.resolve(sheet, Prune.prune(sheet), Press.press(sheet));

        return Verify.verify(sheet, resolved.vector(), resolved.recovery());
    }

    private static Verification latency(VerifyResult result) {
        return result.verifications()
                     .stream()
                     .filter(line -> line.rule().equals("latency decomposition"))
                     .findFirst()
                     .orElseThrow();
    }
}

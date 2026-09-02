package org.pragmatica.jbct.derive.pipeline;

import org.junit.jupiter.api.Test;
import org.pragmatica.jbct.derive.Inline;
import org.pragmatica.jbct.derive.model.Axis;
import org.pragmatica.jbct.derive.result.Strike;

import static org.assertj.core.api.Assertions.assertThat;

/// Prune stage: a Q6 mandate's explicit `strikes` remove `axis:value` pairs from the menu; nothing
/// else prunes.
class PruneTest {
    private static final String HEAD = """
        schema_version = "0.1"
        [meta]
        system = "t"
        era    = "e"
        mode   = "greenfield"
        """;

    @Test
    void prune_recordsStrike_forMandateWithStrikes() {
        var sheet = Inline.sheet(HEAD + """
            [[answers.q6]]
            scope     = "system"
            statement = "publication duty"
            kind      = "mandate"
            strikes   = ["substrate:private-only"]
            status    = "answered"
            """);

        var strikes = Prune.prune(sheet);

        assertThat(strikes).hasSize(1);
        assertThat(strikes.getFirst().axis().map(Axis::label).or("")).isEqualTo("substrate");
        assertThat(strikes.getFirst().value()).isEqualTo("private-only");
        assertThat(strikes.getFirst().display()).isEqualTo("substrate:private-only");
    }

    @Test
    void prune_isEmpty_forMandateWithoutStrikes() {
        var sheet = Inline.sheet(HEAD + """
            [[answers.q6]]
            scope     = "data-class:card-data"
            statement = "PCI — the core never sees card data"
            kind      = "mandate"
            status    = "answered"
            """);

        assertThat(Prune.prune(sheet)).isEmpty();
    }

    @Test
    void prune_ignores_nonMandateConstraints() {
        var sheet = Inline.sheet(HEAD + """
            [[answers.q6]]
            scope     = "data-class:filings"
            statement = "FOI 20-working-day duty"
            kind      = "audit"
            status    = "answered"
            """);

        assertThat(Prune.prune(sheet)).isEmpty();
    }

    @Test
    void prune_recordsRawStrike_forUnknownAxisLabel() {
        var sheet = Inline.sheet(HEAD + """
            [[answers.q6]]
            scope     = "system"
            statement = "a mandate striking a value on an axis the engine does not know"
            kind      = "mandate"
            strikes   = ["nonsense:value"]
            status    = "answered"
            """);

        var strike = Prune.prune(sheet).getFirst();

        assertThat(strike.axis().isEmpty()).isTrue();
        assertThat(strike.axisLabel()).isEqualTo("nonsense");
        assertThat(strike.value()).isEqualTo("value");
    }
}

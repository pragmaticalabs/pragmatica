package org.pragmatica.jbct.derive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.pragmatica.jbct.derive.gate.EntryGate;
import org.pragmatica.jbct.derive.model.Mode;
import org.pragmatica.jbct.derive.model.DomainShape;
import org.pragmatica.jbct.derive.parse.SheetParser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// The four published runs, transcribed into schema form, must PASS the entry gate cleanly
/// (issue #443 acceptance; SPEC.md §6). A divergence here is either a transcription bug or an
/// engine bug — both are findings. The runs are all greenfield derivations.
class GoldenSheetsTest {
    @ParameterizedTest
    @ValueSource(strings = {"companies-house.toml", "stack-overflow.toml", "shopify.toml", "discord.toml"})
    void entryGate_isClean_forPublishedRun(String fileName) {
        SheetParser.parse(Sheets.load(fileName), fileName)
                   .onFailure(cause -> fail("golden sheet did not parse: " + cause.message()))
                   .onSuccess(sheet -> assertThat(EntryGate.verdict(sheet).clean())
                       .as("gate findings: %s", EntryGate.check(sheet))
                       .isTrue());
    }

    @Test
    void parse_readsStructure_forCompaniesHouse() {
        SheetParser.parse(Sheets.load("companies-house.toml"), "companies-house.toml")
                   .onFailure(cause -> fail("parse failed: " + cause.message()))
                   .onSuccess(sheet -> {
                       assertThat(sheet.schemaVersion()).isEqualTo("0.1");
                       assertThat(sheet.meta().system()).isEqualTo("companies-house");
                       assertThat(sheet.meta().era()).isEqualTo("2017-2025");
                       assertThat(sheet.meta().mode()).isEqualTo(Mode.GREENFIELD);
                       assertThat(sheet.rows()).isNotEmpty();
                       assertThat(sheet.domainShapes().stream().map(DomainShape::operation))
                           .contains("incorporate", "accept-filing");
                       assertThat(sheet.currentVector().isEmpty()).isTrue();
                   });
    }
}

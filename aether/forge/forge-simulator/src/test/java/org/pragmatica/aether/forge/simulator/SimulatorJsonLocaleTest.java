package org.pragmatica.aether.forge.simulator;

import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #3051 - the simulator's JSON bodies are wire content. `%.4f` under a comma-decimal default
/// locale (`de`) printed `0,0000`, which is not a JSON number. The default locale is forced here
/// because no CI runner has one.
class SimulatorJsonLocaleTest {
    private Locale saved;

    @BeforeEach
    void forceGermanDefaultLocale() {
        saved = Locale.getDefault();
        Locale.setDefault(Locale.GERMANY);
    }

    @AfterEach
    void restoreDefaultLocale() {
        Locale.setDefault(saved);
    }

    @Test
    void sliceConfigJson_underCommaDecimalLocale_hasDotDecimals() {
        var json = SimulatorConfig.SliceConfig.sliceConfig().toJson();

        assertThat(json).contains("\"failureRate\":0.0000").contains("\"spikeChance\":0.0000");
    }
}

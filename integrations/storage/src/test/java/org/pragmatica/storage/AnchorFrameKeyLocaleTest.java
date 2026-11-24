package org.pragmatica.storage;

import java.util.Locale;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnchorFrameKeyLocaleTest {
    /// Under `fa-IR` the default-locale `%d` prints Persian digits, so a key written under one locale would differ from
    /// another's; the key must stay ASCII and round-trip through `versionOf`.
    @Test
    void key_underADigitChangingDefaultLocale_isAscii() {
        var saved = Locale.getDefault();

        try {
            Locale.setDefault(Locale.forLanguageTag("fa-IR"));

            assertThat(AnchorFrame.key(5L)).isEqualTo("v9223372036854775802");
            assertThat(AnchorFrame.versionOf(AnchorFrame.key(5L)).or(-1L)).isEqualTo(5L);
        } finally {
            Locale.setDefault(saved);
        }
    }
}

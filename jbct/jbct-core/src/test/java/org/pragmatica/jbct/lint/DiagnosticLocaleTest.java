package org.pragmatica.jbct.lint;

import java.util.Locale;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #3051 - `file:line:column` is the location format editors and CI annotators parse out of the
/// lint output. Under a locale with non-ASCII digits (`ar-EG` prints `%d` as Arabic-Indic digits) it
/// stopped being parseable. The default locale is forced here because no CI runner has one.
class DiagnosticLocaleTest {
    private Locale saved;

    @BeforeEach
    void forceArabicDefaultLocale() {
        saved = Locale.getDefault();
        Locale.setDefault(Locale.forLanguageTag("ar-EG"));
    }

    @AfterEach
    void restoreDefaultLocale() {
        Locale.setDefault(saved);
    }

    @Test
    void location_underArabicDefaultLocale_hasAsciiDigits() {
        var diagnostic = Diagnostic.diagnostic("JBCT-X", DiagnosticSeverity.WARNING, "A.java", 12, 3, "m", "");

        assertThat(diagnostic.location()).isEqualTo("A.java:12:3");
    }

    @Test
    void toHumanReadable_underArabicDefaultLocale_hasAsciiDigits() {
        var diagnostic = Diagnostic.diagnostic("JBCT-X", DiagnosticSeverity.WARNING, "A.java", 12, 3, "m", "");

        assertThat(diagnostic.toHumanReadable()).startsWith("A.java:12:3: ");
    }
}

package org.pragmatica.config.toml;

import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #1098 — the typed getters keep answering `Option` (so their `.or(default)` callers stand), but a
/// key that is PRESENT with the wrong type is recorded on the document, and
/// [TomlDocument#requireNoTypeMismatches()] refuses naming every such key, its expected type and
/// the raw value. An absent key records nothing; a convertible value records nothing.
class TomlDocumentTypeMismatchTest {
    private static TomlDocument parse(String toml) {
        return TomlParser.parse(toml).fold(cause -> fail("must parse: " + cause.message()), doc -> doc);
    }

    @Test
    void wrongTypedReads_areRecorded_andRefusedByName() {
        var doc = parse("""
            [server]
            port = "80x"
            size = "twelve"
            ratio = "half"
            secure = "yes"
            tags = "a"
            """);

        assertThat(doc.getInt("server", "port")).isEqualTo(Option.none());
        assertThat(doc.getLong("server", "size")).isEqualTo(Option.none());
        assertThat(doc.getDouble("server", "ratio")).isEqualTo(Option.none());
        assertThat(doc.getBoolean("server", "secure")).isEqualTo(Option.none());
        assertThat(doc.getStringList("server", "tags")).isEqualTo(Option.none());

        assertThat(doc.typeMismatches()).hasSize(5);
        doc.requireNoTypeMismatches()
           .onSuccess(_ -> fail("five wrong-typed reads must refuse"))
           .onFailure(cause -> assertThat(cause.message()).contains("server.port: expected integer, got \"80x\"")
                                                          .contains("server.size: expected integer, got \"twelve\"")
                                                          .contains("server.ratio: expected float, got \"half\"")
                                                          .contains("server.secure: expected boolean, got \"yes\"")
                                                          .contains("server.tags: expected array of strings, got \"a\""));
    }

    /// Absent keys and convertible values record nothing — the gate must not fire on a clean read.
    @Test
    void absentAndWellFormedReads_recordNothing() {
        var doc = parse("""
            [server]
            port = 80
            quoted = "81"
            secure = true
            tags = ["a", "b"]
            """);

        assertThat(doc.getInt("server", "port")).isEqualTo(Option.some(80));
        assertThat(doc.getInt("server", "quoted")).isEqualTo(Option.some(81));
        assertThat(doc.getBoolean("server", "secure")).isEqualTo(Option.some(true));
        assertThat(doc.getStringList("server", "tags").unwrap()).containsExactly("a", "b");
        assertThat(doc.getInt("server", "absent")).isEqualTo(Option.none());
        assertThat(doc.getInt("nope", "port")).isEqualTo(Option.none());

        assertThat(doc.typeMismatches()).isEmpty();
        assertThat(doc.requireNoTypeMismatches().isSuccess()).isTrue();
    }

    /// The ledger is read-side state, not content: two documents with equal content are equal
    /// whatever has been read from either, and a derived document starts with a fresh ledger.
    @Test
    void ledger_doesNotTakePartInEquality_andIsFreshOnDerivedDocuments() {
        var a = parse("[server]\nport = \"80x\"\n");
        var b = parse("[server]\nport = \"80x\"\n");

        a.getInt("server", "port");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a.typeMismatches()).hasSize(1);
        assertThat(b.typeMismatches()).isEmpty();
        assertThat(a.with("server", "host", "h").typeMismatches()).isEmpty();
    }
}

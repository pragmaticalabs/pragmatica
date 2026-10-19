package org.pragmatica.config.toml;

import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #1455 — the typed getters keep answering `Option` (so their `.or(default)` callers stand), but a
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

    /// Several offenders reach the operator as one separated list, in read order -- not run together.
    @Test
    void severalMismatches_areJoinedInReadOrder_withSeparator() {
        var doc = parse("""
            [server]
            port = "80x"
            secure = "yes"
            """);

        doc.getInt("server", "port");
        doc.getBoolean("server", "secure");

        doc.requireNoTypeMismatches()
           .onSuccess(_ -> fail("two wrong-typed reads must refuse"))
           .onFailure(cause -> assertThat(cause.message()).endsWith("server.port: expected integer, got \"80x\"; "
                                                                    + "server.secure: expected boolean, got \"yes\""));
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

    /// #1455: a value that must be parsed from its text (a token, a duration, a size) is recorded when
    /// the parser rejects it, with the caller's description of what is valid.
    @Test
    void getParsed_absentIsSilent_acceptedIsParsed_rejectedIsRecordedWithExpected() {
        var doc = parse("""
            [server]
            mode = "fast"
            wrong = "warp"
            """);

        assertThat(doc.getParsed("server", "absent", "one of: fast", TomlDocumentTypeMismatchTest::fastOnly)).isEqualTo(Option.none());
        assertThat(doc.typeMismatches()).isEmpty();
        assertThat(doc.getParsed("server", "mode", "one of: fast", TomlDocumentTypeMismatchTest::fastOnly)).isEqualTo(Option.some("FAST"));
        assertThat(doc.typeMismatches()).isEmpty();
        assertThat(doc.getParsed("server", "wrong", "one of: fast", TomlDocumentTypeMismatchTest::fastOnly)).isEqualTo(Option.none());
        doc.requireNoTypeMismatches()
           .onSuccess(_ -> fail("a rejected token must refuse"))
           .onFailure(cause -> assertThat(cause.message()).contains("server.wrong: expected one of: fast, got \"warp\""));
    }

    private static Option<String> fastOnly(String raw) {
        return raw.equals("fast")
               ? Option.some("FAST")
               : Option.none();
    }

    /// The ledger belongs to the document INSTANCE: two documents with equal content (equal as records) keep separate ledgers,
    /// so one parser's wrong-typed read cannot refuse another parser's load of the same text.
    @Test
    void ledgers_belongToTheInstance_notToTheContent() {
        var text = "[server]\nport = \"80x\"\n";
        var first = parse(text);
        var second = parse(text);

        assertThat(first).isEqualTo(second);
        assertThat(first.getInt("server", "port")).isEqualTo(Option.none());
        assertThat(first.typeMismatches()).hasSize(1);
        assertThat(second.typeMismatches()).isEmpty();
        assertThat(second.requireNoTypeMismatches().isSuccess()).isTrue();
        assertThat(first.requireNoTypeMismatches().isFailure()).isTrue();
    }
}

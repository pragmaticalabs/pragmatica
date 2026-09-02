package org.pragmatica.jbct.lint.cst.rules;

import java.nio.file.Path;
import java.util.List;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLinter;
import org.pragmatica.jbct.shared.SourceFile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// JBCT-VO-01 pub-sub fact records (#647).
///
/// A fact record published on a topic lives in its own top-level file, so no enclosing `@Slice`
/// annotation ties it to its subscription — but it does carry an in-file marker: a `Topic<Self>`
/// constant. The rest of the rule's behaviour is covered by `CstLinterTest.ValueObjectFactoryTests`.
class CstValueObjectFactoryRuleTest {
    private static final String RULE_ID = "JBCT-VO-01";

    private CstLinter linter;

    @BeforeEach
    void setUp() {
        linter = CstLinter.cstLinter(LintContext.defaultContext());
    }

    @Nested
    class FactRecords {
        @Test
        void clean_on_record_with_self_parameterized_topic() {
            assertFalse(hasRule("""
                    package org.example;
                    public record SeatReleased(String seatId, long version) {
                        public static final Topic<SeatReleased> TOPIC = Topic.of(SeatReleased.class, "seat-released");
                    }
                    """));
        }

        @Test
        void clean_on_record_with_fully_qualified_self_topic() {
            assertFalse(hasRule("""
                    package org.example;
                    public record SeatReleased(String seatId, long version) {
                        public static final org.pragmatica.aether.slice.topic.Topic<SeatReleased> TOPIC =
                            Topic.of(SeatReleased.class, "seat-released");
                    }
                    """));
        }

        @Test
        void flags_record_with_topic_of_another_type() {
            // A Topic<Other> constant makes the record a holder for someone else's fact, not the
            // fact itself — the rule's real target survives.
            assertTrue(hasRule("""
                    package org.example;
                    public record SeatReleasedPublisher(String seatId, long version) {
                        public static final Topic<SeatReleased> TOPIC = Topic.of(SeatReleased.class, "seat-released");
                    }
                    """));
        }

        @Test
        void flags_ordinary_dto_without_a_topic() {
            assertTrue(hasRule("""
                    package org.example;
                    public record SeatReleased(String seatId, long version) {}
                    """));
        }

        @Test
        void flags_outer_record_whose_nested_record_owns_the_topic() {
            // Only constants declared DIRECTLY by the record exempt it: the nested fact is exempt,
            // its outer holder is not.
            var flagged = flaggedRecords("""
                    package org.example;
                    public record SeatEnvelope(String seatId, long version) {
                        record SeatReleased(String seatId) {
                            static final Topic<SeatReleased> TOPIC = Topic.of(SeatReleased.class, "seat-released");
                        }
                    }
                    """);

            assertTrue(flagged.stream()
                              .anyMatch(message -> message.contains("'SeatEnvelope'")),
                       "outer holder must still be flagged, was: " + flagged);
            assertFalse(flagged.stream()
                               .anyMatch(message -> message.contains("'SeatReleased'")),
                        "nested fact record carries its own topic, was: " + flagged);
        }
    }

    private List<String> flaggedRecords(String source) {
        return lint(source).stream()
                           .filter(diagnostic -> diagnostic.ruleId()
                                                           .equals(RULE_ID))
                           .map(Diagnostic::message)
                           .toList();
    }

    private boolean hasRule(String source) {
        return lint(source).stream()
                           .anyMatch(diagnostic -> diagnostic.ruleId()
                                                             .equals(RULE_ID));
    }

    private List<Diagnostic> lint(String source) {
        return linter.lint(SourceFile.sourceFile(Path.of("Test.java"), source))
                     .onFailure(cause -> fail("Parse failed: " + cause.message()))
                     .or(List.of());
    }
}

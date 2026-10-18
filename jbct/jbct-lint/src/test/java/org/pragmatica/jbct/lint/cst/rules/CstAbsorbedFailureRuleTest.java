package org.pragmatica.jbct.lint.cst.rules;

import java.nio.file.Path;
import java.util.List;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLinter;
import org.pragmatica.jbct.shared.SourceFile;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// JBCT-REC-01 — a failure absorbed with no recorded reason.
///
/// The rule's whole risk is being a noise generator: the corpus that motivated it has eight
/// absorbing sites and every one is deliberate, so a rule that fires on legitimate absorption would
/// be trained away rather than acted on. The NearMisses below are therefore the load-bearing half —
/// each is a real shape taken from that corpus, not an invented one.
class CstAbsorbedFailureRuleTest {
    private static final String RULE = "JBCT-REC-01";

    private static List<String> rulesFor(String source) {
        return CstLinter.cstLinter(LintContext.defaultContext())
                        .lint(SourceFile.sourceFile(Path.of("Sample.java"), source))
                        .map(diagnostics -> diagnostics.stream()
                                                       .map(Diagnostic::ruleId)
                                                       .toList())
                        .or(List.of());
    }

    private static long countOf(String source) {
        return CstLinter.cstLinter(LintContext.defaultContext())
                        .lint(SourceFile.sourceFile(Path.of("Sample.java"), source))
                        .map(diagnostics -> diagnostics.stream()
                                                       .filter(diagnostic -> diagnostic.ruleId().equals(RULE))
                                                       .count())
                        .or(0L);
    }

    @Nested
    class Violations {
        @Test
        void undocumentedAbsorption_fires() {
            assertThat(rulesFor("""
                                package demo;
                                class Sample {
                                    Promise<Unit> notifyBuyer(Confirmation c) {
                                        return notifier.send(c).recover(_ -> Unit.unit());
                                    }
                                }
                                """)).contains(RULE);
        }

        /// Proportionality: a file that already documents one absorption does not get the next one
        /// for free. This is the regression the rule exists to catch — a `.recover(...)` added
        /// without a reason, in a file that looks compliant.
        @Test
        void secondAbsorptionWithOnlyOneJustification_fires() {
            assertThat(rulesFor("""
                                package demo;
                                class Sample {
                                    // FER: a notification failure must not fail the buy.
                                    Promise<Unit> notifyBuyer(Confirmation c) {
                                        return notifier.send(c).recover(_ -> Unit.unit());
                                    }

                                    Promise<Unit> publishFact(Confirmation c) {
                                        return publisher.publish(c.fact()).recover(_ -> Unit.unit());
                                    }
                                }
                                """)).contains(RULE);
        }
    }

    /// #1568 item 2: the tag must be found in a COMMENT. A string literal spelling it is code, not a recorded reason.
    @Nested
    class TagMustBeInAComment {
        @Test
        void tagOnlyInAStringLiteral_fires() {
            assertThat(countOf("""
                               package demo;
                               class Sample {
                                   static final String LABEL = "FER";

                                   Promise<Unit> notifyBuyer(Confirmation c) {
                                       return notifier.send(c).recover(_ -> Unit.unit());
                                   }
                               }
                               """)).isEqualTo(1);
        }

        @Test
        void tagOnlyInAStringLiteralInsideTheMethod_fires() {
            assertThat(countOf("""
                               package demo;
                               class Sample {
                                   Promise<Unit> notifyBuyer(Confirmation c) {
                                       log.info("design-out");
                                       return notifier.send(c).recover(_ -> Unit.unit());
                                   }
                               }
                               """)).isEqualTo(1);
        }
    }

    /// #1568 item 2: a comment directly above the method is that method's reason. Before, only the file-level
    /// proportional count ever credited it, so with two absorptions BOTH methods were flagged, the commented one included.
    @Nested
    class CommentAboveTheMethod {
        @Test
        void commentAboveOneOfTwo_flagsOnlyTheUncommentedMethod() {
            var source = """
                         package demo;
                         class Sample {
                             // FER: a notification failure must not fail the buy.
                             Promise<Unit> notifyBuyer(Confirmation c) {
                                 return notifier.send(c).recover(_ -> Unit.unit());
                             }

                             Promise<Unit> publishFact(Confirmation c) {
                                 return publisher.publish(c.fact()).recover(_ -> Unit.unit());
                             }
                         }
                         """;
            var diagnostics = CstLinter.cstLinter(LintContext.defaultContext())
                                       .lint(SourceFile.sourceFile(Path.of("Sample.java"), source))
                                       .or(List.of())
                                       .stream()
                                       .filter(diagnostic -> diagnostic.ruleId().equals(RULE))
                                       .toList();

            assertThat(diagnostics).hasSize(1);
            assertThat(diagnostics.getFirst().line()).as("the uncommented publishFact").isEqualTo(8);
        }

        @Test
        void javadocAndAnnotationAboveTheMethod_countAsItsReason() {
            assertThat(countOf("""
                               package demo;
                               class Sample {
                                   /// BER: the hold is released by its inverse when the charge fails.
                                   @SuppressWarnings("unused")
                                   Promise<Unit> releaseHold(Hold h) {
                                       return holds.release(h).recover(_ -> Unit.unit());
                                   }

                                   Promise<Unit> publishFact(Confirmation c) {
                                       return publisher.publish(c.fact()).recover(_ -> Unit.unit());
                                   }
                               }
                               """)).isEqualTo(1);
        }

        @Test
        void commentSeparatedByABlankLine_isNotTheMethodsReason() {
            assertThat(countOf("""
                               package demo;
                               class Sample {
                                   // FER: about something else entirely.

                                   Promise<Unit> notifyBuyer(Confirmation c) {
                                       return notifier.send(c).recover(_ -> Unit.unit());
                                   }

                                   Promise<Unit> publishFact(Confirmation c) {
                                       return publisher.publish(c.fact()).recover(_ -> Unit.unit());
                                   }
                               }
                               """)).isEqualTo(2);
        }
    }

    /// #1568 item 2: BER is Backward Error Recovery (compensate by an inverse); best-effort degradation is FER.
    @Nested
    class MessageTeachesTheBookMeaning {
        @Test
        void details_defineBerAsBackwardAndFerAsForward() {
            var details = CstLinter.cstLinter(LintContext.defaultContext())
                                   .lint(SourceFile.sourceFile(Path.of("Sample.java"), """
                                                               package demo;
                                                               class Sample {
                                                                   Promise<Unit> notifyBuyer(Confirmation c) {
                                                                       return notifier.send(c).recover(_ -> Unit.unit());
                                                                   }
                                                               }
                                                               """))
                                   .or(List.of())
                                   .stream()
                                   .filter(diagnostic -> diagnostic.ruleId().equals(RULE))
                                   .findFirst()
                                   .map(Diagnostic::details)
                                   .orElse("");

            assertThat(details).contains("Backward Error Recovery").contains("Forward Error Recovery");
            assertThat(details).doesNotContain("best-effort");
        }
    }

    @Nested
    class NearMisses {
        @Test
        void absorptionJustifiedOnTheMethod_isSilent() {
            assertThat(rulesFor("""
                                package demo;
                                class Sample {
                                    // FER: a notification failure is swallowed so it never fails the buy.
                                    Promise<Unit> notifyBuyer(Confirmation c) {
                                        return notifier.send(c).recover(_ -> Unit.unit());
                                    }
                                }
                                """)).doesNotContain(RULE);
        }

        /// `BuyTicket.voidReceipt` carries no comment of its own — its justification sits on its
        /// sole caller. Requiring the tag on the absorbing method would flag it.
        @Test
        void absorptionJustifiedOnTheCaller_isSilent() {
            assertThat(rulesFor("""
                                package demo;
                                class Sample {
                                    // Best-effort gateway void; the saga re-raises the original cause anyway.
                                    Promise<Unit> voidAuthorization(Authorized a) {
                                        return voidReceipt(a.receiptId());
                                    }

                                    Promise<Unit> voidReceipt(String receiptId) {
                                        return gateway.postJson("/void", receiptId).mapToUnit().recover(_ -> Unit.unit());
                                    }
                                }
                                """)).doesNotContain(RULE);
        }

        @Test
        void nonAbsorbingComposition_isSilent() {
            assertThat(rulesFor("""
                                package demo;
                                class Sample {
                                    Promise<Unit> notifyBuyer(Confirmation c) {
                                        return notifier.send(c).mapToUnit();
                                    }
                                }
                                """)).doesNotContain(RULE);
        }
    }

    /// A slice puts its implementation record inside its own factory method, so the factory's text
    /// encloses the record's `.recover(...)`. Attributing the absorption to both yields two
    /// diagnostics for one decision, the outer one at a line the reader cannot act on. Found by
    /// running the rule over a real corpus file rather than by imagining it.
    @Test
    void absorptionInsideANestedRecord_isReportedOnce() {
        assertThat(countOf("""
                           package demo;
                           interface Sample {
                               static Sample sample(Store store) {
                                   record sample(Store store) implements Sample {
                                       @Override
                                       public Promise<Unit> execute(Event event) {
                                           return store.project(event).recover(_ -> Unit.unit());
                                       }
                                   }

                                   return new sample(store);
                               }
                           }
                           """))
                  .as("one absorption, one diagnostic — attributed to the innermost method")
                  .isEqualTo(1);
    }
}

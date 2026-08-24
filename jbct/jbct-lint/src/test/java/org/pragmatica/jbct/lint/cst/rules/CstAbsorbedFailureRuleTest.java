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

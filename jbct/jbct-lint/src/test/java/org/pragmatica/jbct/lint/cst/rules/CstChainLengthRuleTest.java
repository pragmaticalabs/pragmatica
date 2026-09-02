package org.pragmatica.jbct.lint.cst.rules;

import java.nio.file.Path;
import java.util.List;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLinter;
import org.pragmatica.jbct.shared.SourceFile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// JBCT-SEQ-01 chain-length rule, pinned on the #645 probe.
///
/// The probe holds one instance of each way a statement's depth-0 dots can be summed, plus a
/// control:
///
///   - **class 1** — a local type declaration (line 8), whose text is the whole slice-implementation
///     record: every chain of every method it declares used to sum onto its declaration line. FIXED
///     here: a local type declaration is never a chain;
///   - **class 2** — `switch` arms (line 15), three independent 2-call chains summed to 6;
///   - **class 3** — ternary arms (line 23), 3 + 2 + 1 summed to 6;
///   - **control** — one genuine 7-step chain (line 27), inside the local record's own method, which
///     must keep firing at 7 — that is the true positive the class-1 FP used to mask.
///
/// Classes 2 and 3 are NOT fixed here: they need the measurement itself to change from "total
/// depth-0 dots in the statement" to "longest single chain in it", which is a rule-algorithm change
/// tracked as its own follow-up. They are asserted at their CURRENT (wrong) counts so this suite
/// states the outstanding debt explicitly and goes red when the follow-up lands.
class CstChainLengthRuleTest {
    private static final String RULE_ID = "JBCT-SEQ-01";

    /// Line 8 declares the local record; 15 / 23 / 27 are the switch / ternary / control returns.
    private static final int LOCAL_RECORD_LINE = 8;
    private static final int SWITCH_LINE = 15;
    private static final int TERNARY_LINE = 23;
    private static final int REAL_CHAIN_LINE = 27;

    private static final String PROBE = """
            package org.example;
            public interface Probe {
                record Request(String value) {}

                Promise<Request> execute(Request request);

                static Probe probe() {
                    record probe() implements Probe {
                        @Override
                        public Promise<Request> execute(Request request) {
                            return Promise.success(request);
                        }

                        String switchArms(int status) {
                            return switch (status) {
                                case 1 -> A.a().b();
                                case 2 -> A.a().b();
                                default -> A.a().b();
                            };
                        }

                        String ternary(A loaded) {
                            return loaded.one().two().three() ? A.a().b() : A.z();
                        }

                        String realChain(A a) {
                            return a.one().two().three().four().five().six().seven();
                        }
                    }
                    return new probe();
                }
            }
            interface A {}
            """;

    private CstLinter linter;

    @BeforeEach
    void setUp() {
        linter = CstLinter.cstLinter(LintContext.defaultContext());
    }

    @Test
    void chainLength_staysSilent_onALocalTypeDeclaration() {
        assertFalse(linesOf(PROBE).contains(LOCAL_RECORD_LINE),
                    "the slice implementation record's declaration is not a chain");
    }

    @Test
    void chainLength_reportsTheGenuineChain_insideALocalRecordsMethod() {
        assertTrue(linesOf(PROBE).contains(REAL_CHAIN_LINE), "a real 7-step chain must still be reported");
        assertTrue(messageAt(PROBE, REAL_CHAIN_LINE).contains("has 7 steps"),
                   "control chain must be measured at 7, was: " + messageAt(PROBE, REAL_CHAIN_LINE));
    }

    @Test
    void chainLength_reportsOnlyTheThreeStatementLines_onTheProbe() {
        assertEquals(List.of(SWITCH_LINE, TERNARY_LINE, REAL_CHAIN_LINE), linesOf(PROBE));
    }

    /// Class-2 debt: three independent 2-call chains in the switch arms are summed. The follow-up
    /// (longest single chain, not total depth-0 dots) turns this into no finding at all.
    @Test
    void chainLength_stillSumsSwitchArms_pendingTheLongestChainFollowUp() {
        assertTrue(messageAt(PROBE, SWITCH_LINE).contains("has 6 steps"),
                   "switch arms are summed today, was: " + messageAt(PROBE, SWITCH_LINE));
    }

    /// Class-3 debt: the ternary's 3 + 2 + 1 chains are summed; the longest is 3.
    @Test
    void chainLength_stillSumsTernaryArms_pendingTheLongestChainFollowUp() {
        assertTrue(messageAt(PROBE, TERNARY_LINE).contains("has 6 steps"),
                   "ternary arms are summed today, was: " + messageAt(PROBE, TERNARY_LINE));
    }

    @Test
    void chainLength_staysSilent_onALocalRecordWithOnlyShortChains() {
        assertEquals(List.of(), linesOf("""
                package org.example;
                public interface Service {
                    static Service service() {
                        record service() implements Service {
                            String one(A a) {
                                return a.one().two();
                            }
                            String two(A a) {
                                return a.three().four();
                            }
                            String three(A a) {
                                return a.five().six();
                            }
                        }
                        return new service();
                    }
                }
                """));
    }

    private List<Integer> linesOf(String source) {
        return lint(source).stream()
                           .filter(diagnostic -> diagnostic.ruleId()
                                                           .equals(RULE_ID))
                           .map(Diagnostic::line)
                           .sorted()
                           .toList();
    }

    private String messageAt(String source, int line) {
        return lint(source).stream()
                           .filter(diagnostic -> diagnostic.ruleId()
                                                           .equals(RULE_ID) && diagnostic.line() == line)
                           .map(Diagnostic::message)
                           .findFirst()
                           .orElse("<no JBCT-SEQ-01 at line " + line + ">");
    }

    private List<Diagnostic> lint(String source) {
        return linter.lint(SourceFile.sourceFile(Path.of("Test.java"), source))
                     .onFailure(cause -> fail("Parse failed: " + cause.message()))
                     .or(List.of());
    }
}

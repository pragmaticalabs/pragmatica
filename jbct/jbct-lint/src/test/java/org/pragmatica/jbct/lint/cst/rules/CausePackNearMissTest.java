package org.pragmatica.jbct.lint.cst.rules;

import java.nio.file.Path;
import java.util.List;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLinter;
import org.pragmatica.jbct.shared.SourceFile;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// The JBCT-CAUSE pack's near-misses — conforming shapes that a naive implementation of each rule
/// would flag. The fixture harness proves each rule fires and stays silent once; these pin the
/// boundaries that make the pack usable rather than a noise generator, each taken from a trap hit
/// while designing the pack rather than imagined.
class CausePackNearMissTest {
    private static List<String> rulesFor(String source) {
        return CstLinter.cstLinter(LintContext.defaultContext())
                        .lint(SourceFile.sourceFile(Path.of("Sample.java"), source))
                        .map(diagnostics -> diagnostics.stream()
                                                       .map(Diagnostic::ruleId)
                                                       .toList())
                        .or(List.of());
    }

    /// SEAL-02's placeholder exemption carries into CAUSE-01: `record unused()` is a
    /// permitted-subtype stub, not a zero-component cause.
    @Test
    void unusedPlaceholderFiller_staysExempt() {
        assertThat(rulesFor("""
                            package demo;
                            sealed interface UtilOps extends Cause {
                                record unused() implements UtilOps {}
                            }
                            """)).doesNotContain("JBCT-CAUSE-01");
    }

    /// The prescribed enum shape passes whole: one message field, one text argument per constant,
    /// the field-returning accessor — and a per-constant `isTerminal` body, which is a METHOD, not
    /// a data field.
    @Test
    void prescribedEnum_withPerConstantTerminalBody_isClean() {
        assertThat(rulesFor("""
                            package demo;
                            sealed interface TaxError extends Cause {
                                enum General implements TaxError {
                                    MISSING_ID("Tax id is required"),
                                    INVALID_ID("Tax id is invalid") {
                                        @Override
                                        public boolean isTerminal() { return true; }
                                    };

                                    private final String message;
                                    General(String message) { this.message = message; }
                                    @Override public String message() { return message; }
                                }
                            }
                            """)).doesNotContain("JBCT-CAUSE-01")
                                 .doesNotContain("JBCT-CAUSE-02");
    }

    /// Mixin recognition is by qualified spelling: implementing `Cause.Wrapped` marks the record a
    /// variant (so the pack sees it) without any same-file hierarchy interface — and the mixin
    /// itself is never treated as a malformed variant.
    @Test
    void qualifiedMixinImplements_marksVariant_withoutFlagging() {
        assertThat(rulesFor("""
                            package demo;
                            class Steps {
                                record WrapStep(Cause origin, String message) implements Cause.Wrapped {
                                    public Option<Cause> source() { return Option.option(origin); }
                                }
                            }
                            """)).contains("JBCT-CAUSE-05")
                                 .doesNotContain("JBCT-CAUSE-01");
    }

    /// Same-file hierarchy detection runs to a fixpoint: a variant of `E2 extends E1 extends
    /// Cause` is recognized where SEAL-02's direct-extends check went blind.
    @Test
    void transitiveSameFileHierarchy_isRecognized() {
        assertThat(rulesFor("""
                            package demo;
                            sealed interface Outer extends Cause {}
                            sealed interface Inner extends Outer {
                                class Broken implements Inner {}
                            }
                            """)).contains("JBCT-CAUSE-01");
    }

    /// The value-discarding form: a message-only record built with a value-formatting rung bakes
    /// the value into prose. Both CAUSE-01 (message-only record) and CAUSE-04 (0 retained vs 1
    /// formatted) fire, for different reasons, pointing at the same fix.
    @Test
    void valueDiscardingForm_firesArityAndShape() {
        var rules = rulesFor("""
                             package demo;
                             sealed interface RegError extends Cause {
                                 record InvalidEmail(String message) implements RegError {
                                     static final Fn1<InvalidEmail, String> FACTORY =
                                         Causes.forOneValue("Invalid email: %s", InvalidEmail::new);
                                 }
                             }
                             """);

        assertThat(rules).contains("JBCT-CAUSE-01")
                         .contains("JBCT-CAUSE-04");
    }

    /// `%%` and `%n` are not conversions; a matching template stays silent.
    @Test
    void escapedPercentAndNewline_doNotCountAsConversions() {
        assertThat(rulesFor("""
                            package demo;
                            sealed interface RegError extends Cause {
                                record Overrun(long used, String message) implements RegError {
                                    static final Fn1<Overrun, Long> FACTORY =
                                        Causes.forOneValue("Used %d%% of quota%n", Overrun::new);
                                }
                            }
                            """)).doesNotContain("JBCT-CAUSE-04");
    }

    /// The constructor REFERENCE is the sanctioned constructor use — CAUSE-08 matches
    /// instantiation expressions only.
    @Test
    void constructorReferenceAsFactory_isNotDirectConstruction() {
        assertThat(rulesFor("""
                            package demo;
                            sealed interface RegError extends Cause {
                                record Locked(String id, String message) implements RegError {
                                    static final Fn1<Locked, String> FACTORY =
                                        Causes.forOneValue("Locked: %s", Locked::new);
                                }
                            }
                            """)).doesNotContain("JBCT-CAUSE-08");
    }

    /// A `new` of a NON-cause record is out of scope, however cause-adjacent the file.
    @Test
    void directConstructionOfNonCauseRecord_isSilent() {
        assertThat(rulesFor("""
                            package demo;
                            sealed interface RegError extends Cause {
                                record Locked(String id, String message) implements RegError {}
                            }
                            class Builder {
                                record Draft(String id) {}
                                static Draft draft() { return new Draft("a"); }
                            }
                            """)).doesNotContain("JBCT-CAUSE-08");
    }

    /// Two-argument (typed) factory calls are the idiom, not a violation — CAUSE-07 flags only the
    /// single-argument anonymous form, and only when the template is a literal (declaration
    /// parameter lists never are).
    @Test
    void typedFactoryCall_isNotAnonymous() {
        assertThat(rulesFor("""
                            package demo;
                            sealed interface RegError extends Cause {
                                record InvalidEmail(String raw, String message) implements RegError {
                                    static final Fn1<InvalidEmail, String> FACTORY =
                                        Causes.forOneValue("Invalid email: %s", InvalidEmail::new);
                                }
                            }
                            """)).doesNotContain("JBCT-CAUSE-07");
    }

    /// Census bug 1: a doc comment attaches to the member node, so an UNMASKED modifier check
    /// read prose ("`false` by default") as the `default` keyword and flagged the abstract
    /// `message()` on `Cause` itself.
    @Test
    void abstractMessage_withDefaultInFollowingProse_isNotADefaultMethod() {
        assertThat(rulesFor("""
                            package demo;
                            interface MyError extends Cause {
                                String message();

                                /// Returns `false` by default — override per variant.
                                default boolean retryable() { return false; }
                            }
                            """)).doesNotContain("JBCT-CAUSE-02");
    }

    /// Census bug 2: a nested interface's default message() was attributed to the enclosing
    /// interface too and emitted twice. Direct-member scoping: exactly one diagnostic.
    @Test
    void nestedInterfaceDefaultMessage_isReportedExactlyOnce() {
        var count = CstLinter.cstLinter(LintContext.defaultContext())
                             .lint(SourceFile.sourceFile(Path.of("Sample.java"), """
                                   package demo;
                                   public sealed interface SqlError extends Cause {
                                       record ChannelClosed(String message) implements SqlError {}

                                       sealed interface ServerError extends SqlError {
                                           default String message() { return "computed"; }
                                       }

                                       record ServerWarning(String message) implements ServerError {}
                                   }
                                   """))
                             .map(diagnostics -> diagnostics.stream()
                                                            .filter(d -> d.ruleId().equals("JBCT-CAUSE-02"))
                                                            .count())
                             .or(0L);

        assertThat(count).isEqualTo(1);
    }

    /// Census design gap: the ungated CAUSE-08 fired 320 times on the pre-idiom smart-constructor
    /// pattern. A record with NO declared factory is not drifting from the idiom — the idiom is
    /// not there yet.
    @Test
    void preIdiomSmartConstructor_withoutFactory_isNotDirectConstruction() {
        assertThat(rulesFor("""
                            package demo;
                            sealed interface QuoteError extends Cause {
                                record InvalidRequest(String field, String message) implements QuoteError {
                                    static InvalidRequest invalidRequest(String field, Cause cause) {
                                        return new InvalidRequest(field, cause.message());
                                    }
                                }
                            }
                            """)).doesNotContain("JBCT-CAUSE-08");
    }

    /// Spec 3.8's second sanctioned constructor use: instantiation inside the record's OWN
    /// static factory member. A sentinel constant's initializer is not a bypass of the factory.
    @Test
    void directConstruction_insideStaticFieldInitializer_isExempt() {
        assertThat(rulesFor("""
                            package demo;
                            sealed interface RegError extends Cause {
                                record Locked(String id, String message) implements RegError {
                                    static final Fn1<Locked, String> FACTORY = Causes.forOneValue("Locked: %s", Locked::new);
                                    static final Locked UNKNOWN = new Locked("?", "Locked: ?");
                                }
                            }
                            """)).doesNotContain("JBCT-CAUSE-08");
    }

    /// The hand-rolled static factory (record-typed static method) is the shape the companion
    /// spec prescribes above the forXValues ceiling — there the instantiation IS the factory.
    @Test
    void directConstruction_insideHandRolledStaticFactory_isExempt() {
        assertThat(rulesFor("""
                            package demo;
                            sealed interface RegError extends Cause {
                                record Locked(String id, String message) implements RegError {
                                    static final Fn1<Locked, String> FACTORY = Causes.forOneValue("Locked: %s", Locked::new);
                                    static Locked locked(String id) {
                                        return new Locked(id, "Locked: " + id);
                                    }
                                }
                            }
                            """)).doesNotContain("JBCT-CAUSE-08");
    }

    /// The exemption must not widen to the whole record body: a static member that does NOT
    /// return the record type is not a factory, and construction there is still a bypass.
    @Test
    void directConstruction_inStaticHelperNotReturningRecordType_isFlagged() {
        assertThat(rulesFor("""
                            package demo;
                            sealed interface RegError extends Cause {
                                record Locked(String id, String message) implements RegError {
                                    static final Fn1<Locked, String> FACTORY = Causes.forOneValue("Locked: %s", Locked::new);
                                    static String describe(String id) {
                                        return new Locked(id, "manual").message();
                                    }
                                }
                            }
                            """)).contains("JBCT-CAUSE-08");
    }

    /// `Causes.cause(String)` is the sanctioned ad-hoc tier — deliberately never flagged.
    @Test
    void adHocCause_staysSanctioned() {
        assertThat(rulesFor("""
                            package demo;
                            class Boot {
                                static Cause failure() { return Causes.cause("boot failed"); }
                            }
                            """)).doesNotContain("JBCT-CAUSE-07");
    }
}

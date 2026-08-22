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


/// JBCT-RET-05 — a step that cannot fail, typed as if it can.
///
/// The rule previously matched only `Result<` returns and asked one question of the body: does it
/// mention success and not failure? That is the naive form, and it is wrong in both directions —
/// blind to every `Promise`-returning step, and firing on bodies whose failure channel comes from a
/// delegate rather than from themselves. These tests pin the three conditions separately, because
/// each was added for a different reason and a single fixture would not tell them apart.
class CstAlwaysSuccessResultRuleTest {
    private static final String RULE = "JBCT-RET-05";

    private static List<String> rulesFor(String source) {
        return CstLinter.cstLinter(LintContext.defaultContext())
                        .lint(SourceFile.sourceFile(Path.of("Sample.java"), source))
                        .map(diagnostics -> diagnostics.stream()
                                                       .map(Diagnostic::ruleId)
                                                       .toList())
                        .or(List.of());
    }

    @Nested
    class Violations {
        @Test
        void infallibleResult_fires() {
            assertThat(rulesFor("""
                                package demo;
                                class Sample {
                                    Result<String> build(String s) {
                                        return Result.success(s);
                                    }
                                }
                                """)).contains(RULE);
        }

        /// The extension that made the rule see half its subject matter: `Promise<T>` asserts
        /// fallible AND asynchronous, so an always-succeeding one overclaims twice.
        @Test
        void infalliblePromise_fires() {
            assertThat(rulesFor("""
                                package demo;
                                class Sample {
                                    Promise<String> build(String s) {
                                        return Promise.success(s);
                                    }
                                }
                                """)).contains(RULE);
        }
    }

    @Nested
    class NearMisses {
        /// Condition 3, the one the naive form lacked: the body names only success, but the
        /// delegate's failure propagates through the `flatMap`. Flagging this would advise deleting
        /// a real failure channel.
        @Test
        void delegatingViaFlatMap_isSilent() {
            assertThat(rulesFor("""
                                package demo;
                                class Sample {
                                    Result<String> build(Thing t) {
                                        return t.compute().flatMap(v -> Result.success(v.name()));
                                    }
                                }
                                """)).doesNotContain(RULE);
        }

        /// `.async()` lifts a `Result` into a `Promise`, carrying the failure with it — the exact
        /// shape of a JBCT step that adapts a synchronous rule into an asynchronous chain.
        @Test
        void delegatingViaAsyncLift_isSilent() {
            assertThat(rulesFor("""
                                package demo;
                                class Sample {
                                    Promise<String> build(Thing t) {
                                        return t.compute().async().map(v -> v.name());
                                    }
                                }
                                """)).doesNotContain(RULE);
        }

        /// An `@Override` does not own its signature. A trivially succeeding `Promise<Unit> stop()`
        /// on an adapter is the interface contract being satisfied — corpus-checking the `Promise`
        /// extension surfaced a cluster of exactly these and they are pure noise.
        @Test
        void overriddenContractMethod_isSilent() {
            assertThat(rulesFor("""
                                package demo;
                                class Sample implements Lifecycle {
                                    @Override
                                    public Promise<Unit> stop() {
                                        return Promise.success(Unit.unit());
                                    }
                                }
                                """)).doesNotContain(RULE);
        }

        @Test
        void genuinelyFallibleBody_isSilent() {
            assertThat(rulesFor("""
                                package demo;
                                class Sample {
                                    Result<String> build(String s) {
                                        return s.isEmpty() ? SampleError.EMPTY.result() : Result.success(s);
                                    }
                                }
                                """)).doesNotContain(RULE);
        }

        /// `Option<T>` asserts optionality, not failure; an always-present Option is a different
        /// smell and deliberately out of this rule's scope.
        @Test
        void alwaysPresentOption_isSilent() {
            assertThat(rulesFor("""
                                package demo;
                                class Sample {
                                    Option<String> build(String s) {
                                        return Option.some(s);
                                    }
                                }
                                """)).doesNotContain(RULE);
        }
    }
}

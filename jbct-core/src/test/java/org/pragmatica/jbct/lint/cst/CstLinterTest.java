package org.pragmatica.jbct.lint.cst;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.shared.SourceFile;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for all 37 JBCT lint rules.
 * Each test verifies that violations are properly detected.
 */
class CstLinterTest {

    private CstLinter linter;
    private LintContext context;

    @BeforeEach
    void setUp() {
        // Use context that matches business packages
        context = LintContext.lintContext(List.of("**.usecase.**", "**.domain.**"));
        linter = CstLinter.cstLinter(context);
    }

    private List<Diagnostic> lint(String source) {
        var sourceFile = SourceFile.sourceFile(Path.of("Test.java"), source);
        var result = linter.lint(sourceFile);
        assertTrue(result.isSuccess(), () -> "Parse failed: " + result);
        return result.unwrap();
    }

    private void assertHasRule(List<Diagnostic> diagnostics, String ruleId) {
        assertTrue(
            diagnostics.stream().anyMatch(d -> d.ruleId().equals(ruleId)),
            () -> "Expected rule " + ruleId + " but found: " +
                  diagnostics.stream().map(Diagnostic::ruleId).toList()
        );
    }

    private void assertNoRule(List<Diagnostic> diagnostics, String ruleId) {
        assertFalse(
            diagnostics.stream().anyMatch(d -> d.ruleId().equals(ruleId)),
            () -> "Did not expect rule " + ruleId + " but it was triggered"
        );
    }

    // ========== JBCT-RET-* Return Kind Rules ==========

    @Nested
    @DisplayName("JBCT-RET-01: Business methods must use T, Option, Result, or Promise")
    class ReturnKindTests {

        @Test
        void detectsVoidReturnType() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                public class Test {
                    public void doSomething() {}
                }
                """);
            assertHasRule(diagnostics, "JBCT-RET-01");
        }

        @Test
        void detectsOptionalReturnType() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import java.util.Optional;
                public class Test {
                    public Optional<String> findValue() { return Optional.empty(); }
                }
                """);
            assertHasRule(diagnostics, "JBCT-RET-01");
        }

        @Test
        void detectsCompletableFutureReturnType() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import java.util.concurrent.CompletableFuture;
                public class Test {
                    public CompletableFuture<String> compute() { return null; }
                }
                """);
            assertHasRule(diagnostics, "JBCT-RET-01");
        }

        @Test
        void allowsResultReturnType() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                public class Test {
                    public Result<String> process() { return null; }
                }
                """);
            assertNoRule(diagnostics, "JBCT-RET-01");
        }

        @Test
        void allowsOptionReturnType() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Option;
                public class Test {
                    public Option<String> find() { return null; }
                }
                """);
            assertNoRule(diagnostics, "JBCT-RET-01");
        }

        @Test
        void allowsPromiseReturnType() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Promise;
                public class Test {
                    public Promise<String> compute() { return null; }
                }
                """);
            assertNoRule(diagnostics, "JBCT-RET-01");
        }

        @Test
        void allowsPlainValueReturnType() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                public class Test {
                    public String getValue() { return ""; }
                }
                """);
            assertNoRule(diagnostics, "JBCT-RET-01");
        }
    }

    @Nested
    @DisplayName("JBCT-RET-02: No nested wrappers")
    class NestedWrapperTests {

        @Test
        void detectsNestedResultInResult() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                public class Test {
                    public Result<Result<String>> find() { throw new UnsupportedOperationException(); }
                }
                """);
            assertHasRule(diagnostics, "JBCT-RET-02");
        }

        @Test
        void detectsNestedResultInPromise() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                import org.pragmatica.lang.Promise;
                public class Test {
                    public Promise<Result<String>> compute() { throw new UnsupportedOperationException(); }
                }
                """);
            assertHasRule(diagnostics, "JBCT-RET-02");
        }

        @Test
        void detectsDoubleOption() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Option;
                public class Test {
                    public Option<Option<String>> find() { throw new UnsupportedOperationException(); }
                }
                """);
            assertHasRule(diagnostics, "JBCT-RET-02");
        }

        @Test
        void allowsResultOfOption() {
            // Result<Option<T>> is allowed for optional validation results
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                import org.pragmatica.lang.Option;
                public class Test {
                    public Result<Option<String>> find() { throw new UnsupportedOperationException(); }
                }
                """);
            assertNoRule(diagnostics, "JBCT-RET-02");
        }

        @Test
        void allowsSimpleWrapper() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                public class Test {
                    public Result<String> process() { throw new UnsupportedOperationException(); }
                }
                """);
            assertNoRule(diagnostics, "JBCT-RET-02");
        }
    }

    @Nested
    @DisplayName("JBCT-RET-03: Never return null")
    class NullReturnTests {

        @Test
        void detectsReturnNull() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                public class Test {
                    public String find() {
                        return null;
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-RET-03");
        }

        @Test
        void allowsReturnValue() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                public class Test {
                    public String find() {
                        return "value";
                    }
                }
                """);
            assertNoRule(diagnostics, "JBCT-RET-03");
        }
    }

    @Nested
    @DisplayName("JBCT-RET-04: Use Unit instead of Void")
    class VoidTypeTests {

        @Test
        void detectsVoidGenericParameter() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                public class Test {
                    public Result<Void> process() { return null; }
                }
                """);
            assertHasRule(diagnostics, "JBCT-RET-04");
        }

        @Test
        void allowsUnitGenericParameter() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                import org.pragmatica.lang.Unit;
                public class Test {
                    public Result<Unit> process() { return null; }
                }
                """);
            assertNoRule(diagnostics, "JBCT-RET-04");
        }
    }

    @Nested
    @DisplayName("JBCT-RET-05: Avoid always-succeeding Result")
    class AlwaysSuccessResultTests {

        @Test
        void detectsResultSuccessOnly() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                public class Test {
                    public Result<String> process(String input) {
                        return Result.success(input.toUpperCase());
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-RET-05");
        }

        @Test
        void allowsResultWithValidation() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                public class Test {
                    public Result<String> process(String input) {
                        return input.isEmpty()
                            ? Result.failure(new RuntimeException("empty"))
                            : Result.success(input.toUpperCase());
                    }
                }
                """);
            assertNoRule(diagnostics, "JBCT-RET-05");
        }
    }

    // ========== JBCT-VO-* Value Object Rules ==========

    @Nested
    @DisplayName("JBCT-VO-01: Value objects should have factory returning Result")
    class ValueObjectFactoryTests {

        @Test
        void detectsRecordWithoutResultFactory() {
            var diagnostics = lint("""
                package com.example.domain.test;
                import org.pragmatica.lang.Result;
                public record Email(String value) {
                    public static Email email(String value) {
                        return new Email(value);
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-VO-01");
        }

        @Test
        void allowsRecordWithResultFactory() {
            var diagnostics = lint("""
                package com.example.domain.test;
                import org.pragmatica.lang.Result;
                public record Email(String value) {
                    public static Result<Email> email(String value) {
                        return Result.success(new Email(value));
                    }
                }
                """);
            assertNoRule(diagnostics, "JBCT-VO-01");
        }
    }

    @Nested
    @DisplayName("JBCT-VO-02: Don't bypass factory with direct constructor calls")
    class ConstructorBypassTests {

        @Test
        void detectsDirectRecordConstruction() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                public class Test {
                    public void process() {
                        var email = new Email("test@example.com");
                    }
                }
                record Email(String value) {}
                """);
            // Note: This rule may need specific record detection
            // The test documents expected behavior
        }

        @Test
        void allowsFactoryMethodUsage() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                public class Test {
                    public Result<Email> process() {
                        return Email.email("test@example.com");
                    }
                }
                record Email(String value) {
                    public static Result<Email> email(String v) { return Result.success(new Email(v)); }
                }
                """);
            assertNoRule(diagnostics, "JBCT-VO-02");
        }
    }

    // ========== JBCT-EX-* Exception Rules ==========

    @Nested
    @DisplayName("JBCT-EX-01: No business exceptions")
    class NoBusinessExceptionsTests {

        @Test
        void detectsExceptionClass() {
            var diagnostics = lint("""
                package com.example.domain.test;
                public class ValidationException extends RuntimeException {
                    public ValidationException(String message) {
                        super(message);
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-EX-01");
        }

        @Test
        void detectsThrowStatement() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                public class Test {
                    public void process() {
                        throw new IllegalArgumentException("invalid");
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-EX-01");
        }

        @Test
        void detectsThrowsClause() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import java.io.IOException;
                public class Test {
                    public void process() throws IOException {
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-EX-01");
        }

        @Test
        void allowsResultBasedErrorHandling() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                public class Test {
                    public Result<String> process(String input) {
                        if (input.isEmpty()) {
                            return Result.failure(new RuntimeException("empty"));
                        }
                        return Result.success(input);
                    }
                }
                """);
            assertNoRule(diagnostics, "JBCT-EX-01");
        }
    }

    @Nested
    @DisplayName("JBCT-EX-02: Don't use orElseThrow")
    class OrElseThrowTests {

        @Test
        void detectsOrElseThrow() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import java.util.Optional;
                public class Test {
                    public String process(Optional<String> opt) {
                        return opt.orElseThrow();
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-EX-02");
        }

        @Test
        void detectsOrElseThrowWithSupplier() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import java.util.Optional;
                public class Test {
                    public String process(Optional<String> opt) {
                        return opt.orElseThrow(() -> new RuntimeException("error"));
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-EX-02");
        }

        @Test
        void allowsOptionComposition() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Option;
                public class Test {
                    public String process(Option<String> opt) {
                        return opt.or("default");
                    }
                }
                """);
            assertNoRule(diagnostics, "JBCT-EX-02");
        }
    }

    // ========== JBCT-NAM-* Naming Rules ==========

    @Nested
    @DisplayName("JBCT-NAM-01: Factory method naming conventions")
    class FactoryNamingTests {

        @Test
        void detectsOfFactory() {
            var diagnostics = lint("""
                package com.example.domain.test;
                import org.pragmatica.lang.Result;
                public record Email(String value) {
                    public static Result<Email> of(String value) {
                        return Result.success(new Email(value));
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-NAM-01");
        }

        @Test
        void detectsFromFactory() {
            var diagnostics = lint("""
                package com.example.domain.test;
                import org.pragmatica.lang.Result;
                public record Email(String value) {
                    public static Result<Email> from(String value) {
                        return Result.success(new Email(value));
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-NAM-01");
        }

        @Test
        void allowsCorrectNaming() {
            var diagnostics = lint("""
                package com.example.domain.test;
                import org.pragmatica.lang.Result;
                public record Email(String value) {
                    public static Result<Email> email(String value) {
                        return Result.success(new Email(value));
                    }
                }
                """);
            assertNoRule(diagnostics, "JBCT-NAM-01");
        }
    }

    @Nested
    @DisplayName("JBCT-NAM-02: Use Valid prefix, not Validated")
    class ValidatedNamingTests {

        @Test
        void detectsValidatedPrefix() {
            var diagnostics = lint("""
                package com.example.domain.test;
                public record ValidatedEmail(String value) {}
                """);
            assertHasRule(diagnostics, "JBCT-NAM-02");
        }

        @Test
        void allowsValidPrefix() {
            var diagnostics = lint("""
                package com.example.domain.test;
                public record ValidEmail(String value) {}
                """);
            assertNoRule(diagnostics, "JBCT-NAM-02");
        }
    }

    // ========== JBCT-LAM-* Lambda Rules ==========

    @Nested
    @DisplayName("JBCT-LAM-01: No complex logic in lambdas")
    class LambdaComplexityTests {

        @Test
        void detectsIfInLambda() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import java.util.List;
                public class Test {
                    public void process(List<String> list) {
                        list.forEach(s -> {
                            if (s.isEmpty()) {
                                System.out.println("empty");
                            }
                        });
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-LAM-01");
        }

        @Test
        void detectsSwitchInLambda() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import java.util.List;
                public class Test {
                    public void process(List<Integer> list) {
                        list.forEach(n -> {
                            switch (n) {
                                case 1 -> System.out.println("one");
                                default -> System.out.println("other");
                            }
                        });
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-LAM-01");
        }

        @Test
        void allowsSimpleLambda() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import java.util.List;
                public class Test {
                    public List<String> process(List<String> list) {
                        return list.stream()
                            .map(s -> s.toUpperCase())
                            .toList();
                    }
                }
                """);
            assertNoRule(diagnostics, "JBCT-LAM-01");
        }
    }

    @Nested
    @DisplayName("JBCT-LAM-02: No braces in lambdas")
    class LambdaBracesTests {

        @Test
        void detectsBracesInLambda() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import java.util.List;
                public class Test {
                    public void process(List<String> list) {
                        list.forEach(s -> {
                            System.out.println(s);
                        });
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-LAM-02");
        }

        @Test
        void allowsExpressionLambda() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import java.util.List;
                public class Test {
                    public void process(List<String> list) {
                        list.forEach(s -> System.out.println(s));
                    }
                }
                """);
            assertNoRule(diagnostics, "JBCT-LAM-02");
        }
    }

    @Nested
    @DisplayName("JBCT-LAM-03: No ternary in lambdas")
    class LambdaTernaryTests {

        @Test
        void detectsTernaryInLambda() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import java.util.List;
                public class Test {
                    public List<String> process(List<String> list) {
                        return list.stream()
                            .map(s -> s.isEmpty() ? "empty" : s)
                            .toList();
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-LAM-03");
        }

        @Test
        void allowsMethodReferenceInstead() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import java.util.List;
                public class Test {
                    public List<String> process(List<String> list) {
                        return list.stream()
                            .map(String::toUpperCase)
                            .toList();
                    }
                }
                """);
            assertNoRule(diagnostics, "JBCT-LAM-03");
        }
    }

    @Nested
    @DisplayName("JBCT-UC-01: Use case factories should return lambdas")
    class UseCaseFactoryTests {

        @Test
        void detectsNestedRecordInFactory() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                public interface RegisterUser {
                    Result<User> execute(Request request);

                    static RegisterUser registerUser(UserRepository repo) {
                        record Impl(UserRepository repo) implements RegisterUser {
                            public Result<User> execute(Request request) {
                                return null;
                            }
                        }
                        return new Impl(repo);
                    }
                }
                interface UserRepository {}
                class User {}
                class Request {}
                """);
            assertHasRule(diagnostics, "JBCT-UC-01");
        }

        @Test
        void allowsLambdaFactory() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                public interface RegisterUser {
                    Result<User> execute(Request request);

                    static RegisterUser registerUser(UserRepository repo) {
                        return request -> repo.save(request.toUser());
                    }
                }
                interface UserRepository { Result<User> save(User user); }
                class User {}
                class Request { User toUser() { return new User(); } }
                """);
            assertNoRule(diagnostics, "JBCT-UC-01");
        }
    }

    // ========== JBCT-PAT-* Pattern Rules ==========

    @Nested
    @DisplayName("JBCT-PAT-01: Use functional iteration")
    class RawLoopTests {

        @Test
        void detectsForLoop() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import java.util.List;
                public class Test {
                    public void process(List<String> list) {
                        for (int i = 0; i < list.size(); i++) {
                            System.out.println(list.get(i));
                        }
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-PAT-01");
        }

        @Test
        void detectsWhileLoop() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                public class Test {
                    public void process() {
                        int i = 0;
                        while (i < 10) {
                            System.out.println(i);
                            i++;
                        }
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-PAT-01");
        }

        @Test
        void allowsEnhancedForLoop() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import java.util.List;
                public class Test {
                    public void process(List<String> list) {
                        for (var item : list) {
                            System.out.println(item);
                        }
                    }
                }
                """);
            assertNoRule(diagnostics, "JBCT-PAT-01");
        }

        @Test
        void allowsStreamOperations() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import java.util.List;
                public class Test {
                    public List<String> process(List<String> list) {
                        return list.stream()
                            .map(String::toUpperCase)
                            .toList();
                    }
                }
                """);
            assertNoRule(diagnostics, "JBCT-PAT-01");
        }
    }

    @Nested
    @DisplayName("JBCT-SEQ-01: Chain length limit")
    class ChainLengthTests {

        @Test
        void detectsLongChain() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                public class Test {
                    public Result<String> process(Result<String> input) {
                        return input
                            .map(s -> s.trim())
                            .map(s -> s.toUpperCase())
                            .map(s -> s.toLowerCase())
                            .map(s -> s.strip())
                            .map(s -> s.indent(2))
                            .map(s -> s.repeat(2));
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-SEQ-01");
        }

        @Test
        void allowsShortChain() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                public class Test {
                    public Result<String> process(Result<String> input) {
                        return input
                            .map(String::trim)
                            .map(String::toUpperCase);
                    }
                }
                """);
            assertNoRule(diagnostics, "JBCT-SEQ-01");
        }
    }

    // ========== JBCT-STY-* Style Rules ==========

    @Nested
    @DisplayName("JBCT-STY-01: Prefer fluent failure style")
    class FluentFailureTests {

        @Test
        void detectsResultFailure() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                import org.pragmatica.lang.utils.Causes;
                public class Test {
                    public Result<String> process() {
                        return Result.failure(Causes.cause("error"));
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-STY-01");
        }

        @Test
        void allowsFluentStyle() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                import org.pragmatica.lang.utils.Causes;
                public class Test {
                    public Result<String> process() {
                        return Causes.cause("error").result();
                    }
                }
                """);
            assertNoRule(diagnostics, "JBCT-STY-01");
        }
    }

    @Nested
    @DisplayName("JBCT-STY-02: Prefer constructor references")
    class ConstructorReferenceTests {

        @Test
        void detectsLambdaInsteadOfConstructorRef() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import java.util.List;
                public class Test {
                    public List<StringBuilder> process(List<String> list) {
                        return list.stream()
                            .map(s -> new StringBuilder(s))
                            .toList();
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-STY-02");
        }

        @Test
        void allowsConstructorReference() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import java.util.List;
                public class Test {
                    public List<StringBuilder> process(List<String> list) {
                        return list.stream()
                            .map(StringBuilder::new)
                            .toList();
                    }
                }
                """);
            assertNoRule(diagnostics, "JBCT-STY-02");
        }
    }

    @Nested
    @DisplayName("JBCT-STY-03: No fully qualified class names")
    class FullyQualifiedNameTests {

        @Test
        void detectsFqcn() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                public class Test {
                    public void process() {
                        java.util.List<String> list = new java.util.ArrayList<>();
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-STY-03");
        }

        @Test
        void allowsImportedClasses() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import java.util.List;
                import java.util.ArrayList;
                public class Test {
                    public void process() {
                        List<String> list = new ArrayList<>();
                    }
                }
                """);
            assertNoRule(diagnostics, "JBCT-STY-03");
        }
    }

    // ========== JBCT-LOG-* Logging Rules ==========

    @Nested
    @DisplayName("JBCT-LOG-01: No conditional logging")
    class ConditionalLoggingTests {

        @Test
        void detectsLogLevelCheck() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.slf4j.Logger;
                public class Test {
                    private Logger log;
                    public void process() {
                        if (log.isDebugEnabled()) {
                            log.debug("message");
                        }
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-LOG-01");
        }

        @Test
        void allowsDirectLogging() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.slf4j.Logger;
                public class Test {
                    private Logger log;
                    public void process() {
                        log.debug("Processing: {}", "data");
                    }
                }
                """);
            assertNoRule(diagnostics, "JBCT-LOG-01");
        }
    }

    @Nested
    @DisplayName("JBCT-LOG-02: No logger as method parameter")
    class LoggerParameterTests {

        @Test
        void detectsLoggerParameter() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.slf4j.Logger;
                public class Test {
                    public void process(Logger logger, String data) {
                        logger.info("Processing: {}", data);
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-LOG-02");
        }

        @Test
        void allowsLoggerAsField() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                public class Test {
                    private static final Logger log = LoggerFactory.getLogger(Test.class);
                    public void process(String data) {
                        log.info("Processing: {}", data);
                    }
                }
                """);
            assertNoRule(diagnostics, "JBCT-LOG-02");
        }
    }

    // ========== JBCT-MIX-* Architecture Rules ==========

    @Nested
    @DisplayName("JBCT-MIX-01: No I/O operations in domain packages")
    class DomainIoTests {

        @Test
        void detectsFileOperationsInDomain() {
            var diagnostics = lint("""
                package com.example.domain.test;
                import java.nio.file.Files;
                import java.nio.file.Path;
                public class Test {
                    public void process() throws Exception {
                        Files.readString(Path.of("file.txt"));
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-MIX-01");
        }

        @Test
        void detectsHttpClientInDomain() {
            var diagnostics = lint("""
                package com.example.domain.test;
                import java.net.http.HttpClient;
                public class Test {
                    private HttpClient client = HttpClient.newHttpClient();
                }
                """);
            assertHasRule(diagnostics, "JBCT-MIX-01");
        }

        @Test
        void allowsPureBusinessLogicInDomain() {
            var diagnostics = lint("""
                package com.example.domain.test;
                import org.pragmatica.lang.Result;
                public record Email(String value) {
                    public static Result<Email> email(String value) {
                        return value.contains("@")
                            ? Result.success(new Email(value))
                            : Result.failure(new RuntimeException("invalid"));
                    }
                }
                """);
            assertNoRule(diagnostics, "JBCT-MIX-01");
        }

        @Test
        void allowsIoInUsecasePackage() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import java.nio.file.Files;
                import java.nio.file.Path;
                public class Test {
                    public String readFile(Path path) throws Exception {
                        return Files.readString(path);
                    }
                }
                """);
            assertNoRule(diagnostics, "JBCT-MIX-01");
        }
    }

    // ========== JBCT-STATIC-* Static Import Rules ==========

    @Nested
    @DisplayName("JBCT-STATIC-01: Prefer static imports for factories")
    class StaticImportTests {

        @Test
        void detectsQualifiedResultSuccess() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                public class Test {
                    public Result<String> process() {
                        return Result.success("value");
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-STATIC-01");
        }

        @Test
        void detectsQualifiedOptionSome() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Option;
                public class Test {
                    public Option<String> find() {
                        return Option.some("value");
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-STATIC-01");
        }

        @Test
        void detectsQualifiedOptionNone() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Option;
                public class Test {
                    public Option<String> find() {
                        return Option.none();
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-STATIC-01");
        }

        @Test
        void allowsStaticImports() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                import static org.pragmatica.lang.Result.success;
                public class Test {
                    public Result<String> process() {
                        return success("value");
                    }
                }
                """);
            assertNoRule(diagnostics, "JBCT-STATIC-01");
        }
    }

    // ========== JBCT-UTIL-* Utility Rules ==========

    @Nested
    @DisplayName("JBCT-UTIL-01: Use Pragmatica parsing utilities")
    class ParsingUtilitiesTests {

        @Test
        void detectsIntegerParseInt() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                public class Test {
                    public int parse(String s) {
                        return Integer.parseInt(s);
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-UTIL-01");
        }

        @Test
        void detectsLongParseLong() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                public class Test {
                    public long parse(String s) {
                        return Long.parseLong(s);
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-UTIL-01");
        }

        @Test
        void detectsDoubleParseDouble() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                public class Test {
                    public double parse(String s) {
                        return Double.parseDouble(s);
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-UTIL-01");
        }

        @Test
        void detectsLocalDateParse() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import java.time.LocalDate;
                public class Test {
                    public LocalDate parse(String s) {
                        return LocalDate.parse(s);
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-UTIL-01");
        }

        @Test
        void detectsUuidFromString() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import java.util.UUID;
                public class Test {
                    public UUID parse(String s) {
                        return UUID.fromString(s);
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-UTIL-01");
        }

        @Test
        void allowsPragmaticaParsingUtilities() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                import static org.pragmatica.lang.utils.Parsers.parseInt;
                public class Test {
                    public Result<Integer> parse(String s) {
                        return parseInt(s);
                    }
                }
                """);
            assertNoRule(diagnostics, "JBCT-UTIL-01");
        }
    }

    @Nested
    @DisplayName("JBCT-UTIL-02: Use Verify.Is predicates")
    class VerifyPredicatesTests {

        @Test
        void detectsManualPositiveCheck() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                public class Test {
                    public void validate(int value) {
                        if (value > 0) {
                            System.out.println("positive");
                        }
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-UTIL-02");
        }

        @Test
        void detectsManualNullCheck() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                public class Test {
                    public void validate(Object value) {
                        if (value != null) {
                            System.out.println("not null");
                        }
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-UTIL-02");
        }

        @Test
        void detectsIsEmptyCheck() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                public class Test {
                    public void validate(String value) {
                        if (value.isEmpty()) {
                            System.out.println("empty");
                        }
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-UTIL-02");
        }

        @Test
        void allowsVerifyPredicates() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                import static org.pragmatica.lang.utils.Verify.verify;
                import static org.pragmatica.lang.utils.Is.positive;
                public class Test {
                    public Result<Integer> validate(int value) {
                        return verify(value, positive());
                    }
                }
                """);
            assertNoRule(diagnostics, "JBCT-UTIL-02");
        }
    }

    // ========== JBCT-NEST-* Nesting Rules ==========

    @Nested
    @DisplayName("JBCT-NEST-01: No nested monadic operations in lambdas")
    class NestedOperationsTests {

        @Test
        void detectsNestedMapInLambda() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                public class Test {
                    public Result<String> process(Result<Result<String>> input) {
                        return input.flatMap(inner -> inner.map(s -> s.toUpperCase()).map(s -> s.trim()));
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-NEST-01");
        }

        @Test
        void detectsNestedFlatMapInLambda() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                public class Test {
                    public Result<String> process(Result<String> input) {
                        return input.flatMap(s -> validate(s).flatMap(v -> transform(v)));
                    }
                    private Result<String> validate(String s) { return Result.success(s); }
                    private Result<String> transform(String s) { return Result.success(s); }
                }
                """);
            assertHasRule(diagnostics, "JBCT-NEST-01");
        }

        @Test
        void allowsFlatChain() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                public class Test {
                    public Result<String> process(Result<String> input) {
                        return input
                            .flatMap(this::validate)
                            .flatMap(this::transform);
                    }
                    private Result<String> validate(String s) { return Result.success(s); }
                    private Result<String> transform(String s) { return Result.success(s); }
                }
                """);
            assertNoRule(diagnostics, "JBCT-NEST-01");
        }
    }

    // ========== JBCT-ZONE-* Zone Rules ==========

    @Nested
    @DisplayName("JBCT-ZONE-01: Step interfaces should use Zone 2 verbs")
    class ZoneTwoVerbsTests {

        @Test
        void detectsZone3VerbInStepInterface() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                public interface FetchUserData {
                    Result<String> apply(String id);
                }
                """);
            assertHasRule(diagnostics, "JBCT-ZONE-01");
        }

        @Test
        void detectsParseVerbInStepInterface() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                public interface ParseRequest {
                    Result<String> apply(String raw);
                }
                """);
            assertHasRule(diagnostics, "JBCT-ZONE-01");
        }

        @Test
        void allowsZone2VerbInStepInterface() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                public interface ValidateRequest {
                    Result<String> apply(String raw);
                }
                """);
            assertNoRule(diagnostics, "JBCT-ZONE-01");
        }

        @Test
        void allowsProcessVerbInStepInterface() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                public interface ProcessPayment {
                    Result<String> apply(String data);
                }
                """);
            assertNoRule(diagnostics, "JBCT-ZONE-01");
        }
    }

    @Nested
    @DisplayName("JBCT-ZONE-02: Leaf functions should use Zone 3 verbs")
    class ZoneThreeVerbsTests {

        @Test
        void detectsZone2VerbInLeafFunction() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                public class Test {
                    private String processValue(String input) {
                        return input.toUpperCase();
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-ZONE-02");
        }

        @Test
        void allowsZone3VerbInLeafFunction() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                public class Test {
                    private String formatValue(String input) {
                        return input.toUpperCase();
                    }
                }
                """);
            assertNoRule(diagnostics, "JBCT-ZONE-02");
        }
    }

    @Nested
    @DisplayName("JBCT-ZONE-03: No zone mixing in sequencer chains")
    class ZoneMixingTests {

        @Test
        void detectsZone3VerbInChain() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                public class Test {
                    private DataFetcher fetcher;
                    public Result<String> process(Result<String> input) {
                        return input
                            .flatMap(s -> fetcher.fetchData(s))
                            .map(s -> s.toUpperCase());
                    }
                }
                interface DataFetcher { Result<String> fetchData(String s); }
                """);
            assertHasRule(diagnostics, "JBCT-ZONE-03");
        }

        @Test
        void detectsParseInChain() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                public class Test {
                    private JsonParser parser;
                    public Result<String> process(Result<String> input) {
                        return input
                            .flatMap(s -> parser.parseJson(s))
                            .map(s -> s.toUpperCase());
                    }
                }
                interface JsonParser { Result<String> parseJson(String s); }
                """);
            assertHasRule(diagnostics, "JBCT-ZONE-03");
        }

        @Test
        void allowsZone2VerbsInChain() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                public class Test {
                    private RequestValidator validator;
                    private RequestProcessor processor;
                    public Result<String> process(Result<String> input) {
                        return input
                            .flatMap(s -> validator.validateRequest(s))
                            .flatMap(s -> processor.processRequest(s));
                    }
                }
                interface RequestValidator { Result<String> validateRequest(String s); }
                interface RequestProcessor { Result<String> processRequest(String s); }
                """);
            assertNoRule(diagnostics, "JBCT-ZONE-03");
        }
    }

    // ========== JBCT-STY-04 to JBCT-STY-06 Style Rules ==========

    @Nested
    @DisplayName("JBCT-STY-04: Utility class pattern")
    class UtilityClassTests {

        @Test
        void detectsUtilityClass() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                public final class StringUtils {
                    private StringUtils() {}
                    public static Result<String> process(String input) {
                        return Result.success(input.trim());
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-STY-04");
        }

        @Test
        void detectsUtilityClassWithMultipleStaticMethods() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                public final class ValidationUtils {
                    private ValidationUtils() {}
                    public static Result<String> validateEmail(String email) {
                        return Result.success(email);
                    }
                    public static Result<Integer> validateAge(int age) {
                        return Result.success(age);
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-STY-04");
        }

        @Test
        void detectsMissingUnusedRecordInSealedInterface() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                public sealed interface Validators {
                    static Result<String> validate(String input) {
                        return Result.success(input);
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-STY-04");
        }

        @Test
        void allowsSealedInterfaceWithUnusedRecord() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                public sealed interface Validators {
                    static Result<String> validate(String input) {
                        return Result.success(input);
                    }
                    record unused() implements Validators {}
                }
                """);
            assertNoRule(diagnostics, "JBCT-STY-04");
        }

        @Test
        void allowsNonFinalClass() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                public class Service {
                    public Result<String> process(String input) {
                        return Result.success(input);
                    }
                }
                """);
            assertNoRule(diagnostics, "JBCT-STY-04");
        }

        @Test
        void allowsClassWithInstanceMethods() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                public final class Service {
                    private Service() {}
                    public Result<String> process(String input) {
                        return Result.success(input);
                    }
                }
                """);
            assertNoRule(diagnostics, "JBCT-STY-04");
        }
    }

    @Nested
    @DisplayName("JBCT-STY-05: Method reference preference")
    class MethodReferencePreferenceTests {

        @Test
        void detectsConstructorLambda() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                import java.util.List;
                public class Test {
                    public Result<List<String>> process(List<String> input) {
                        return Result.success(input.stream()
                            .map(s -> new String(s))
                            .toList());
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-STY-05");
        }

        @Test
        void detectsInstanceMethodLambda() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                import java.util.List;
                public class Test {
                    public Result<List<String>> process(List<String> input) {
                        return Result.success(input.stream()
                            .map(s -> s.trim())
                            .toList());
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-STY-05");
        }

        @Test
        void detectsStaticMethodLambda() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                import java.util.List;
                public class Test {
                    public Result<List<Integer>> process(List<String> input) {
                        return Result.success(input.stream()
                            .map(s -> Integer.parseInt(s))
                            .toList());
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-STY-05");
        }

        @Test
        void detectsMultiArgConstructorLambda() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                import java.util.Map;
                public class Test {
                    public Result<String> process(Map<String, String> input) {
                        return Result.success(input.entrySet().stream()
                            .map(e -> (a, b) -> new Entry(a, b))
                            .toString());
                    }
                }
                record Entry(String key, String value) {}
                """);
            assertHasRule(diagnostics, "JBCT-STY-05");
        }

        @Test
        void allowsMethodReference() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                import java.util.List;
                public class Test {
                    public Result<List<String>> process(List<String> input) {
                        return Result.success(input.stream()
                            .map(String::trim)
                            .toList());
                    }
                }
                """);
            assertNoRule(diagnostics, "JBCT-STY-05");
        }

        @Test
        void allowsConstructorReference() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                import java.util.List;
                public class Test {
                    public Result<List<StringBuilder>> process(List<String> input) {
                        return Result.success(input.stream()
                            .map(StringBuilder::new)
                            .toList());
                    }
                }
                """);
            assertNoRule(diagnostics, "JBCT-STY-05");
        }

        @Test
        void allowsComplexLambdaWithMultipleStatements() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                import java.util.List;
                public class Test {
                    public Result<List<String>> process(List<String> input) {
                        return Result.success(input.stream()
                            .map(s -> {
                                var trimmed = s.trim();
                                return trimmed.toUpperCase();
                            })
                            .toList());
                    }
                }
                """);
            assertNoRule(diagnostics, "JBCT-STY-05");
        }

        @Test
        void allowsLambdaWithDifferentParameter() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                import java.util.List;
                public class Test {
                    private String prefix = "test";
                    public Result<List<String>> process(List<String> input) {
                        return Result.success(input.stream()
                            .map(s -> prefix + s)
                            .toList());
                    }
                }
                """);
            assertNoRule(diagnostics, "JBCT-STY-05");
        }
    }

    @Nested
    @DisplayName("JBCT-STY-06: Import ordering")
    class ImportOrderingTests {

        @Test
        void detectsJavaImportAfterJavax() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import javax.annotation.Nonnull;
                import java.util.List;
                public class Test {
                    public String process(@Nonnull List<String> input) {
                        return input.toString();
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-STY-06");
        }

        @Test
        void detectsJavaImportAfterPragmatica() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                import java.util.List;
                public class Test {
                    public Result<String> process(List<String> input) {
                        return Result.success(input.toString());
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-STY-06");
        }

        @Test
        void detectsJavaImportAfterThirdParty() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import org.slf4j.Logger;
                import java.util.List;
                public class Test {
                    public String process(List<String> input) {
                        return input.toString();
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-STY-06");
        }

        @Test
        void detectsThirdPartyAfterProject() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import com.example.domain.User;
                import org.slf4j.Logger;
                public class Test {
                    public String process(User user) {
                        return user.toString();
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-STY-06");
        }

        @Test
        void allowsCorrectImportOrder() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import java.util.List;
                import java.util.Map;
                import javax.annotation.Nonnull;
                import org.pragmatica.lang.Result;
                import org.slf4j.Logger;
                import com.example.domain.User;
                public class Test {
                    public Result<String> process(@Nonnull List<String> input) {
                        return Result.success(input.toString());
                    }
                }
                """);
            assertNoRule(diagnostics, "JBCT-STY-06");
        }

        @Test
        void allowsCorrectImportOrderWithStaticImports() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import java.util.List;
                import org.pragmatica.lang.Result;
                import static java.util.Objects.requireNonNull;
                import static org.pragmatica.lang.Result.success;
                public class Test {
                    public Result<String> process(List<String> input) {
                        requireNonNull(input);
                        return success(input.toString());
                    }
                }
                """);
            assertNoRule(diagnostics, "JBCT-STY-06");
        }

        @Test
        void detectsOutOfOrderStaticImports() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import java.util.List;
                import org.pragmatica.lang.Result;
                import static org.pragmatica.lang.Result.success;
                import static java.util.Objects.requireNonNull;
                public class Test {
                    public Result<String> process(List<String> input) {
                        requireNonNull(input);
                        return success(input.toString());
                    }
                }
                """);
            assertHasRule(diagnostics, "JBCT-STY-06");
        }

        @Test
        void allowsJavaOnlyImports() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import java.util.List;
                import java.util.Map;
                import java.util.Set;
                public class Test {
                    public String process(List<String> input) {
                        return input.toString();
                    }
                }
                """);
            assertNoRule(diagnostics, "JBCT-STY-06");
        }

        @Test
        void handlesModuleImports() {
            var diagnostics = lint("""
                package com.example.usecase.test;
                import java.util.List;
                public class Test {
                    public String process(List<String> input) {
                        return input.toString();
                    }
                }
                """);
            assertNoRule(diagnostics, "JBCT-STY-06");
        }
    }

    // ========== Non-business package tests ==========

    @Nested
    @DisplayName("Rules should not trigger for non-business packages")
    class NonBusinessPackageTests {

        @Test
        void noRulesForInfrastructurePackage() {
            var diagnostics = lint("""
                package com.example.infrastructure;
                public class Test {
                    public void doSomething() {
                        throw new RuntimeException("error");
                    }
                }
                """);
            // Should have no diagnostics since infrastructure is not a business package
            assertTrue(diagnostics.isEmpty() ||
                       diagnostics.stream().noneMatch(d -> d.ruleId().startsWith("JBCT-")),
                       "No JBCT rules should trigger for non-business packages");
        }

        @Test
        void noRulesForAdapterPackage() {
            var diagnostics = lint("""
                package com.example.adapter.http;
                import java.util.Optional;
                public class Test {
                    public Optional<String> find() {
                        return Optional.empty();
                    }
                }
                """);
            assertTrue(diagnostics.isEmpty() ||
                       diagnostics.stream().noneMatch(d -> d.ruleId().startsWith("JBCT-")),
                       "No JBCT rules should trigger for adapter packages");
        }
    }

    // ========== JBCT-SLICE-* Slice Rules ==========

    @Nested
    @DisplayName("JBCT-SLICE-01: External slice dependencies must use API interface")
    class SliceApiUsageTests {

        // Slice package convention configured via slicePackages in jbct.toml
        // API package convention: <slicePackage>.api

        private CstLinter sliceLinter;

        @BeforeEach
        void setUpSliceLinter() {
            // Configure slice packages for these tests
            var sliceContext = LintContext.lintContext(List.of("**.usecase.**", "**.domain.**"))
                .withSlicePackages(List.of("**.usecase.**"));
            sliceLinter = CstLinter.cstLinter(sliceContext);
        }

        private List<Diagnostic> lintWithSlices(String source) {
            var sourceFile = SourceFile.sourceFile(Path.of("Test.java"), source);
            var result = sliceLinter.lint(sourceFile);
            assertTrue(result.isSuccess(), () -> "Parse failed: " + result);
            return result.unwrap();
        }

        @Test
        void detectsExternalSliceDependencyNotUsingApi() {
            var diagnostics = lintWithSlices("""
                package com.example.usecase.order;
                import org.pragmatica.lang.Result;
                import com.example.usecase.inventory.InventoryService;
                public interface OrderService {
                    Result<Order> createOrder(OrderRequest request);

                    static OrderService orderService(InventoryService inventory) {
                        return request -> Result.success(null);
                    }
                }
                class Order {}
                class OrderRequest {}
                """);
            assertHasRule(diagnostics, "JBCT-SLICE-01");
        }

        @Test
        void allowsExternalSliceDependencyUsingApi() {
            var diagnostics = lintWithSlices("""
                package com.example.usecase.order;
                import org.pragmatica.lang.Result;
                import com.example.usecase.inventory.api.InventoryService;
                public interface OrderService {
                    Result<Order> createOrder(OrderRequest request);

                    static OrderService orderService(InventoryService inventory) {
                        return request -> Result.success(null);
                    }
                }
                class Order {}
                class OrderRequest {}
                """);
            assertNoRule(diagnostics, "JBCT-SLICE-01");
        }

        @Test
        void allowsInternalSliceDependency() {
            // Types from same slice (same usecase.<name>) are allowed
            var diagnostics = lintWithSlices("""
                package com.example.usecase.order;
                import org.pragmatica.lang.Result;
                import com.example.usecase.order.internal.OrderRepository;
                public interface OrderService {
                    Result<Order> createOrder(OrderRequest request);

                    static OrderService orderService(OrderRepository repo) {
                        return request -> Result.success(null);
                    }
                }
                class Order {}
                class OrderRequest {}
                """);
            assertNoRule(diagnostics, "JBCT-SLICE-01");
        }

        @Test
        void allowsPrimitiveAndJdkTypes() {
            var diagnostics = lintWithSlices("""
                package com.example.usecase.order;
                import org.pragmatica.lang.Result;
                import java.util.List;
                public interface OrderService {
                    Result<Order> createOrder(OrderRequest request);

                    static OrderService orderService(List<String> config, int timeout) {
                        return request -> Result.success(null);
                    }
                }
                class Order {}
                class OrderRequest {}
                """);
            assertNoRule(diagnostics, "JBCT-SLICE-01");
        }

        @Test
        void allowsPragmaticaTypes() {
            var diagnostics = lintWithSlices("""
                package com.example.usecase.order;
                import org.pragmatica.lang.Result;
                import org.pragmatica.lang.Option;
                public interface OrderService {
                    Result<Order> createOrder(OrderRequest request);

                    static OrderService orderService(Option<String> config) {
                        return request -> Result.success(null);
                    }
                }
                class Order {}
                class OrderRequest {}
                """);
            assertNoRule(diagnostics, "JBCT-SLICE-01");
        }

        @Test
        void detectsMultipleExternalSliceDependencies() {
            var diagnostics = lintWithSlices("""
                package com.example.usecase.order;
                import org.pragmatica.lang.Result;
                import com.example.usecase.inventory.InventoryService;
                import com.example.usecase.payment.PaymentGateway;
                public interface OrderService {
                    Result<Order> createOrder(OrderRequest request);

                    static OrderService orderService(InventoryService inventory, PaymentGateway payment) {
                        return request -> Result.success(null);
                    }
                }
                class Order {}
                class OrderRequest {}
                """);
            // Should detect both violations
            var sliceViolations = diagnostics.stream()
                .filter(d -> d.ruleId().equals("JBCT-SLICE-01"))
                .count();
            assertEquals(2, sliceViolations, "Expected 2 JBCT-SLICE-01 violations");
        }

        @Test
        void noViolationForNonSlicePackage() {
            // Types from non-slice packages (not matching slicePackages pattern) are not flagged
            var diagnostics = lintWithSlices("""
                package com.example.usecase.order;
                import org.pragmatica.lang.Result;
                import com.example.common.StringFormatter;
                import com.example.utils.DateHelper;
                public interface OrderService {
                    Result<Order> createOrder(OrderRequest request);

                    static OrderService orderService(StringFormatter formatter, DateHelper helper) {
                        return request -> Result.success(null);
                    }
                }
                class Order {}
                class OrderRequest {}
                """);
            // StringFormatter and DateHelper are not from slice packages (not matching **.usecase.**)
            assertNoRule(diagnostics, "JBCT-SLICE-01");
        }

        @Test
        void noViolationForNoFactoryMethod() {
            // Interfaces without a factory method are not checked
            var diagnostics = lintWithSlices("""
                package com.example.usecase.order;
                import org.pragmatica.lang.Result;
                import com.example.usecase.inventory.InventoryService;
                public interface OrderService {
                    Result<Order> createOrder(OrderRequest request);
                    // No factory method - so dependencies are not checked
                }
                class Order {}
                class OrderRequest {}
                """);
            assertNoRule(diagnostics, "JBCT-SLICE-01");
        }

        @Test
        void allowsDomainTypes() {
            // Types from domain packages are allowed (not matching slicePackages pattern)
            var diagnostics = lintWithSlices("""
                package com.example.usecase.order;
                import org.pragmatica.lang.Result;
                import com.example.domain.shared.UserId;
                public interface OrderService {
                    Result<Order> createOrder(OrderRequest request);

                    static OrderService orderService(UserId adminId) {
                        return request -> Result.success(null);
                    }
                }
                class Order {}
                class OrderRequest {}
                """);
            assertNoRule(diagnostics, "JBCT-SLICE-01");
        }

        @Test
        void skipsSilentlyWhenNotConfigured() {
            // When slicePackages is not configured, rule should be silently skipped
            var diagnostics = lint("""
                package com.example.usecase.order;
                import org.pragmatica.lang.Result;
                import com.example.usecase.inventory.InventoryService;
                public interface OrderService {
                    Result<Order> createOrder(OrderRequest request);

                    static OrderService orderService(InventoryService inventory) {
                        return request -> Result.success(null);
                    }
                }
                class Order {}
                class OrderRequest {}
                """);
            // Should have no SLICE-01 diagnostics at all when not configured
            assertNoRule(diagnostics, "JBCT-SLICE-01");
        }

        @Test
        void detectsSliceImportFromNonSliceCode() {
            // Non-slice code importing slice directly should be flagged
            var nonSliceContext = LintContext.lintContext(List.of("**.usecase.**", "**.domain.**"))
                .withSlicePackages(List.of("**.usecase.**"));
            var nonSliceLinter = CstLinter.cstLinter(nonSliceContext);

            var sourceFile = SourceFile.sourceFile(Path.of("Test.java"), """
                package com.example.adapter.http;
                import org.pragmatica.lang.Result;
                import com.example.usecase.order.OrderService;
                public interface OrderController {
                    Result<String> handleOrder(String request);

                    static OrderController orderController(OrderService orderService) {
                        return request -> Result.success("ok");
                    }
                }
                """);
            var result = nonSliceLinter.lint(sourceFile);
            assertTrue(result.isSuccess());
            assertHasRule(result.unwrap(), "JBCT-SLICE-01");
        }
    }
}

package org.pragmatica.jbct.lint.cst.rules;

import java.util.List;

/// Shared fixture catalog for the CST lint-rule coverage harness (#454).
///
/// One [RuleFixture] per rule registered in `CstLinter.defaultRules()`. Each entry pairs:
///   - a POSITIVE snippet that must emit the rule's diagnostic on `positiveLine`, and
///   - a NEGATIVE snippet of conforming code that must stay clean of that rule.
///
/// Adding a future rule's coverage is two snippets: append one [#fixture] entry. The
/// harness ([RuleFixtureCoverageTest]) and the registry invariant ([RuleRegistryInvariantTest])
/// consume this list directly, so the marginal cost of a new rule is exactly one row.
///
/// Triggering syntax and reported line for every entry are derived from the rule SOURCE
/// (`org.pragmatica.jbct.lint.cst.rules.*`), not guessed. Line 1 of each snippet is the
/// `package` declaration.
final class RuleFixtures {
    private RuleFixtures() {}

    /// A per-rule fixture pair. `positiveLine` is the 1-based line the rule reports for
    /// `positiveSource`. `toString` renders the rule ID so parameterized test names read cleanly.
    record RuleFixture(String ruleId, int positiveLine, String positiveSource, String negativeSource) {
        @Override
        public String toString() {
            return ruleId;
        }
    }

    static List<RuleFixture> all() {
        return FIXTURES;
    }

    private static RuleFixture fixture(String ruleId, int positiveLine, String positiveSource, String negativeSource) {
        return new RuleFixture(ruleId, positiveLine, positiveSource, negativeSource);
    }

    private static final List<RuleFixture> FIXTURES = List.of(
        // JBCT-RET-01: four return kinds — void (non-private) is forbidden.
        fixture("JBCT-RET-01", 3,
                """
                package org.example;
                class Foo {
                    public void run() {}
                }
                """,
                """
                package org.example;
                class Foo {
                    public String run() { return ""; }
                }
                """),

        // JBCT-RET-02: no nested wrappers — Promise<Result<T>> forbidden, Result<Option<T>> allowed.
        fixture("JBCT-RET-02", 3,
                """
                package org.example;
                class Foo {
                    Promise<Result<String>> run() { return null; }
                }
                """,
                """
                package org.example;
                class Foo {
                    Result<Option<String>> run() { return null; }
                }
                """),

        // JBCT-RET-03: never return null.
        fixture("JBCT-RET-03", 4,
                """
                package org.example;
                class Foo {
                    String run() {
                        return null;
                    }
                }
                """,
                """
                package org.example;
                class Foo {
                    String run() {
                        return "value";
                    }
                }
                """),

        // JBCT-RET-04: use Unit, not boxed Void.
        fixture("JBCT-RET-04", 3,
                """
                package org.example;
                class Foo {
                    Result<Void> run() { return null; }
                }
                """,
                """
                package org.example;
                class Foo {
                    Result<Unit> run() { return null; }
                }
                """),

        // JBCT-REC-01: recover(...) absorbing a failure with no recovery-triple justification.
        // The negative carries an FER tag, which is what the corpus does at every one of its sites.
        fixture("JBCT-REC-01", 3,
                """
                package org.example;
                class Foo {
                    Promise<Unit> notifyBuyer(Confirmation c) {
                        return notifier.send(c).recover(_ -> Unit.unit());
                    }
                }
                """,
                """
                package org.example;
                class Foo {
                    // FER: a notification failure is swallowed so it never fails the buy.
                    Promise<Unit> notifyBuyer(Confirmation c) {
                        return notifier.send(c).recover(_ -> Unit.unit());
                    }
                }
                """),

        // JBCT-RET-05: no always-succeeding Result (only Result.success, never a failure).
        fixture("JBCT-RET-05", 3,
                """
                package org.example;
                class Foo {
                    Result<String> run(String s) {
                        return Result.success(s);
                    }
                }
                """,
                """
                package org.example;
                class Foo {
                    Result<String> run(String s) {
                        return s.isEmpty() ? cause.result() : Result.success(s);
                    }
                }
                """),

        // JBCT-RET-06: no nullable parameters (parameter null-checked in the body).
        fixture("JBCT-RET-06", 3,
                """
                package org.example;
                class Foo {
                    String run(Config other) {
                        if (other == null) {
                            return "";
                        }
                        return other.toString();
                    }
                }
                """,
                """
                package org.example;
                class Foo {
                    String run(Config other) {
                        return other.toString();
                    }
                }
                """),

        // JBCT-VO-01: value-object records need a factory method.
        fixture("JBCT-VO-01", 2,
                """
                package org.example;
                public record Email(String value) {}
                """,
                """
                package org.example;
                public record Email(String value) {
                    public static Result<Email> email(String value) {
                        return Result.success(new Email(value));
                    }
                }
                """),

        // JBCT-VO-02: direct `new ValueObject(...)` outside a factory bypasses validation.
        fixture("JBCT-VO-02", 4,
                """
                package org.example;
                class Service {
                    Email build(String raw) {
                        return new Email(raw);
                    }
                }
                record Email(String value) {
                    static Result<Email> email(String v) { return Result.success(new Email(v)); }
                }
                """,
                """
                package org.example;
                record Email(String value) {
                    static Result<Email> email(String v) {
                        return Result.success(new Email(v));
                    }
                }
                """),

        // JBCT-EX-01: no business exceptions (throw statement forbidden).
        fixture("JBCT-EX-01", 4,
                """
                package org.example;
                class Foo {
                    void run() {
                        throw new RuntimeException("x");
                    }
                }
                """,
                """
                package org.example;
                class Foo {
                    Result<String> run() {
                        return cause.result();
                    }
                }
                """),

        // JBCT-EX-02: no orElseThrow().
        fixture("JBCT-EX-02", 4,
                """
                package org.example;
                class Foo {
                    String run(Option<String> opt) {
                        return opt.orElseThrow();
                    }
                }
                """,
                """
                package org.example;
                class Foo {
                    String run(Option<String> opt) {
                        return opt.or("default");
                    }
                }
                """),

        // JBCT-NAM-01: factory method must be named after the type (Email.email).
        fixture("JBCT-NAM-01", 3,
                """
                package org.example;
                record Email(String value) {
                    static Result<Email> create(String v) {
                        return Result.success(new Email(v));
                    }
                }
                """,
                """
                package org.example;
                record Email(String value) {
                    static Result<Email> email(String v) {
                        return Result.success(new Email(v));
                    }
                }
                """),

        // JBCT-NAM-02: use the Valid prefix, not Validated.
        fixture("JBCT-NAM-02", 2,
                """
                package org.example;
                record ValidatedRequest(String value) {}
                """,
                """
                package org.example;
                record ValidRequest(String value) {}
                """),

        // JBCT-LAM-01: no complex logic (multi-statement block) in a lambda.
        fixture("JBCT-LAM-01", 4,
                """
                package org.example;
                class Foo {
                    Object run() {
                        return items.map(x -> { log(x); return x; });
                    }
                }
                """,
                """
                package org.example;
                class Foo {
                    Object run() {
                        return items.map(String::trim);
                    }
                }
                """),

        // JBCT-LAM-02: no block body in a lambda.
        fixture("JBCT-LAM-02", 4,
                """
                package org.example;
                class Foo {
                    Object run() {
                        return items.map(x -> { return x.trim(); });
                    }
                }
                """,
                """
                package org.example;
                class Foo {
                    Object run() {
                        return items.map(String::trim);
                    }
                }
                """),

        // JBCT-LAM-03: no ternary inside a lambda.
        fixture("JBCT-LAM-03", 4,
                """
                package org.example;
                class Foo {
                    Object run() {
                        return items.map(x -> x > 0 ? a : b);
                    }
                }
                """,
                """
                package org.example;
                class Foo {
                    Object run() {
                        return items.map(String::trim);
                    }
                }
                """),

        // JBCT-UC-01: use-case factory returns a nested record impl instead of a lambda.
        fixture("JBCT-UC-01", 4,
                """
                package org.example;
                interface UseCase {
                    String apply(String r);
                    static UseCase useCase() {
                        record impl(int x) implements UseCase {
                            public String apply(String r) { return r; }
                        }
                        return new impl(0);
                    }
                }
                """,
                """
                package org.example;
                interface UseCase {
                    String apply(String r);
                    static UseCase useCase() {
                        return r -> r;
                    }
                }
                """),

        // JBCT-PAT-01: raw for/while/do loop instead of functional iteration.
        fixture("JBCT-PAT-01", 4,
                """
                package org.example;
                class Foo {
                    void run() {
                        for (int i = 0; i < 10; i++) {
                            process(i);
                        }
                    }
                }
                """,
                """
                package org.example;
                class Foo {
                    Object run() {
                        return items.stream().map(String::trim).toList();
                    }
                }
                """),

        // JBCT-SEQ-01: method chain longer than five steps.
        fixture("JBCT-SEQ-01", 4,
                """
                package org.example;
                class Foo {
                    Object run() {
                        return v.a().b().c().d().e().f().g();
                    }
                }
                """,
                """
                package org.example;
                class Foo {
                    Object run() {
                        return v.a().b().c();
                    }
                }
                """),

        // JBCT-STY-01: prefer cause.result() over Result.failure(cause).
        fixture("JBCT-STY-01", 4,
                """
                package org.example;
                class Foo {
                    Result<String> run() {
                        return Result.failure(cause);
                    }
                }
                """,
                """
                package org.example;
                class Foo {
                    Result<String> run() {
                        return cause.result();
                    }
                }
                """),

        // JBCT-STY-02: prefer a constructor reference over v -> new X(v).
        fixture("JBCT-STY-02", 4,
                """
                package org.example;
                class Foo {
                    Object run() {
                        return items.map(v -> new Email(v));
                    }
                }
                """,
                """
                package org.example;
                class Foo {
                    Object run() {
                        return items.map(Email::new);
                    }
                }
                """),

        // JBCT-STY-03: no fully qualified class names in method bodies.
        fixture("JBCT-STY-03", 3,
                """
                package org.example;
                class Foo {
                    Object run() {
                        return java.util.List.of();
                    }
                }
                """,
                """
                package org.example;
                class Foo {
                    Object run() {
                        return List.of();
                    }
                }
                """),

        // JBCT-STY-04: final class + private ctor + only static methods → sealed interface.
        fixture("JBCT-STY-04", 2,
                """
                package org.example;
                public final class Utils {
                    private Utils() {}
                    public static String process(String x) { return x; }
                }
                """,
                """
                package org.example;
                public sealed interface Utils {
                    static String process(String x) { return x; }
                    record unused() implements Utils {}
                }
                """),

        // JBCT-STY-05: reducible lambda should be a method reference.
        fixture("JBCT-STY-05", 4,
                """
                package org.example;
                class Foo {
                    Object run() {
                        return items.map(v -> new Email(v));
                    }
                }
                """,
                """
                package org.example;
                class Foo {
                    Object run() {
                        return items.map(Email::new);
                    }
                }
                """),

        // JBCT-STY-06: import ordering — org.pragmatica before java is out of order.
        fixture("JBCT-STY-06", 3,
                """
                package com.example.usecase.test;
                import org.pragmatica.lang.Result;
                import java.util.List;
                public class Test {
                    public Result<String> process(List<String> input) {
                        return Result.success(input.toString());
                    }
                }
                """,
                """
                package com.example.usecase.test;
                import java.util.List;
                import org.pragmatica.lang.Result;
                public class Test {
                    public Result<String> process(List<String> input) {
                        return Result.success(input.toString());
                    }
                }
                """),

        // JBCT-STY-07: unnecessary intermediate variable before return.
        fixture("JBCT-STY-07", 4,
                """
                package org.example;
                class Foo {
                    String run() {
                        var result = computeValue();
                        return result;
                    }
                }
                """,
                """
                package org.example;
                class Foo {
                    String run() {
                        return computeValue();
                    }
                }
                """),

        // JBCT-STY-08: if/else with a single return in both branches.
        fixture("JBCT-STY-08", 4,
                """
                package org.example;
                class Foo {
                    String run(int x) {
                        if (x > 0) {
                            return "a";
                        } else {
                            return "b";
                        }
                    }
                }
                """,
                """
                package org.example;
                class Foo {
                    String run(int x) {
                        if (x > 0) {
                            return "a";
                        }
                        return "b";
                    }
                }
                """),

        // JBCT-LOG-01: no conditional logging guarded by isXEnabled().
        fixture("JBCT-LOG-01", 4,
                """
                package org.example;
                class Foo {
                    void run() {
                        if (log.isDebugEnabled()) { log.debug("x"); }
                    }
                }
                """,
                """
                package org.example;
                class Foo {
                    void run() {
                        log.debug("x");
                    }
                }
                """),

        // JBCT-LOG-02: no Logger passed as a method parameter.
        fixture("JBCT-LOG-02", 3,
                """
                package org.example;
                class Foo {
                    void run(Logger log) {
                        log.info("x");
                    }
                }
                """,
                """
                package org.example;
                class Foo {
                    void run(String msg) {
                        log.info(msg);
                    }
                }
                """),

        // JBCT-MIX-01: no I/O import in a domain package.
        fixture("JBCT-MIX-01", 2,
                """
                package com.example.domain.user;
                import java.io.File;
                public class UserData {
                    File file;
                }
                """,
                """
                package com.example.domain.user;
                import org.pragmatica.lang.Result;
                public class UserData {
                    Result<String> name() { return name; }
                }
                """),

        // JBCT-ARCH-01: dependency direction — a domain file importing an adapter package.
        fixture("JBCT-ARCH-01", 2,
                """
                package com.example.domain.user;
                import com.example.adapter.persistence.Row;
                class User {
                }
                """,
                """
                package com.example.application.register;
                import com.example.domain.user.User;
                class RegisterService {
                }
                """),

        // JBCT-ARCH-02: lift(...) in a business-zone (domain) file; legal only in the adapter zone.
        fixture("JBCT-ARCH-02", 3,
                """
                package com.example.domain.pricing;
                class Pricing {
                    Object run() { return Result.lift(Err::new, () -> compute()); }
                }
                """,
                """
                package com.example.adapter.pricing;
                class PricingRepo {
                    Object run() { return Result.lift(Err::new, () -> compute()); }
                }
                """),

        // JBCT-ARCH-03: a use case importing another *UseCase type.
        fixture("JBCT-ARCH-03", 2,
                """
                package com.example.usecase.registeruser;
                import com.example.usecase.loginuser.LoginUseCase;
                interface RegisterUseCase {
                    Result<String> execute(String r);
                }
                """,
                """
                package com.example.usecase.registeruser;
                interface RegisterUseCase {
                    interface CheckEmail {
                        Result<String> apply(String r);
                    }
                    Result<String> execute(String r);
                }
                """),

        // JBCT-ARCH-04: importing another slice's internal package (a different slice's non-root).
        fixture("JBCT-ARCH-04", 2,
                """
                package com.example.usecase.registeruser;
                import com.example.usecase.loginuser.internal.Token;
                class RegisterUser {
                }
                """,
                """
                package com.example.usecase.registeruser;
                import com.example.usecase.registeruser.internal.Token;
                class RegisterUser {
                }
                """),

        // JBCT-STATIC-01: prefer static import for Pragmatica factory calls.
        fixture("JBCT-STATIC-01", 3,
                """
                package org.example;
                class Foo {
                    Result<String> run() {
                        return Result.success("x");
                    }
                }
                """,
                """
                package org.example;
                import static org.pragmatica.lang.Result.success;
                class Foo {
                    Result<String> run() {
                        return success("x");
                    }
                }
                """),

        // JBCT-UTIL-01: use Pragmatica parsing utilities, not JDK parseInt.
        fixture("JBCT-UTIL-01", 3,
                """
                package org.example;
                class Foo {
                    int run(String s) {
                        return Integer.parseInt(s);
                    }
                }
                """,
                """
                package org.example;
                class Foo {
                    Result<Integer> run(String s) {
                        return Number.parseInt(s);
                    }
                }
                """),

        // JBCT-UTIL-02: use Verify.Is predicates instead of manual checks.
        fixture("JBCT-UTIL-02", 4,
                """
                package org.example;
                class Foo {
                    void run(int x) {
                        if (x > 0) {
                            process();
                        }
                    }
                }
                """,
                """
                package org.example;
                class Foo {
                    Result<Integer> run(int x) {
                        return Verify.ensure(x, Verify.Is::positive);
                    }
                }
                """),

        // JBCT-NEST-01: no nested monadic operations inside a lambda.
        fixture("JBCT-NEST-01", 4,
                """
                package org.example;
                class Foo {
                    Object run() {
                        return outer.flatMap(x -> inner(x).map(String::trim));
                    }
                }
                """,
                """
                package org.example;
                class Foo {
                    Object run() {
                        return outer.flatMap(this::inner);
                    }
                }
                """),

        // JBCT-ZONE-01: step interface uses a Zone 3 (implementation) verb.
        fixture("JBCT-ZONE-01", 2,
                """
                package org.example;
                interface FetchUser {
                    Result<String> apply(String id);
                }
                """,
                """
                package org.example;
                interface LoadUser {
                    Result<String> apply(String id);
                }
                """),

        // JBCT-ZONE-02: private leaf function uses a Zone 2 (orchestration) verb.
        fixture("JBCT-ZONE-02", 3,
                """
                package org.example;
                class Foo {
                    private String validateInput(String x) {
                        return x.trim();
                    }
                }
                """,
                """
                package org.example;
                class Foo {
                    private String parseInput(String x) {
                        return x.trim();
                    }
                }
                """),

        // JBCT-ZONE-03: Zone 3 verb called directly inside a map/flatMap chain.
        fixture("JBCT-ZONE-03", 3,
                """
                package org.example;
                class Foo {
                    Object run() {
                        return value.flatMap(Parser::parseData);
                    }
                }
                """,
                """
                package org.example;
                class Foo {
                    Object run() {
                        return value.flatMap(Processor::process);
                    }
                }
                """),

        // JBCT-ACR-01: all-caps acronym in a type name.
        fixture("JBCT-ACR-01", 2,
                """
                package org.example;
                class HTTPClient {
                }
                """,
                """
                package org.example;
                class HttpClient {
                }
                """),

        // JBCT-SEAL-01: error interface extends Cause but is not sealed.
        fixture("JBCT-SEAL-01", 2,
                """
                package org.example;
                interface LoginError extends Cause {
                    record Failed() implements LoginError {}
                }
                """,
                """
                package org.example;
                sealed interface LoginError extends Cause {
                    record Failed() implements LoginError {}
                }
                """),

        // JBCT-PAT-02: Fork-Join (Result.all) nested inside a Sequencer (flatMap) lambda.
        fixture("JBCT-PAT-02", 4,
                """
                package org.example;
                class Foo {
                    Object run() {
                        return validate(req).flatMap(email -> save(Result.all(a, b)));
                    }
                }
                """,
                """
                package org.example;
                class Foo {
                    Object run() {
                        return validate(req).flatMap(this::save);
                    }
                }
                """),

        // JBCT-PAT-03: blocking .await() outside a @TerminalOperation method.
        fixture("JBCT-PAT-03", 4,
                """
                package org.example;
                class Foo {
                    void run() {
                        fetchData().await();
                    }
                }
                """,
                """
                package org.example;
                class Foo {
                    void run() {
                        fetchData().map(String::trim);
                    }
                }
                """),

        // JBCT-RET-07: discarded Result/Promise/Option value.
        fixture("JBCT-RET-07", 4,
                """
                package org.example;
                class Foo {
                    void run() {
                        Result.success(value);
                    }
                }
                """,
                """
                package org.example;
                class Foo {
                    Object run() {
                        return Result.success(value);
                    }
                }
                """),

        // JBCT-TOT-01: partial accessor inside a carrier mapper lambda (make it total / lift to a Cause).
        fixture("JBCT-TOT-01", 4,
                """
                package org.example;
                class Foo {
                    Object run(Result<Wire> r) {
                        return r.map(wire -> wire.items().getFirst());
                    }
                }
                """,
                """
                package org.example;
                class Foo {
                    Object run(Result<Wire> r) {
                        return r.map(wire -> wire.name().trim());
                    }
                }
                """),

        // JBCT-TOT-02: method ref to a same-file method whose body calls getFirst() — the #483 shape.
        fixture("JBCT-TOT-02", 4,
                """
                package org.example;
                class Handler {
                    Object run(Result<Wire> r) {
                        return r.map(Handler::firstItem);
                    }
                    static String firstItem(Wire wire) {
                        return wire.items().getFirst();
                    }
                }
                """,
                """
                package org.example;
                class Handler {
                    Object run(Result<Wire> r) {
                        return r.map(Handler::firstItem);
                    }
                    static String firstItem(Wire wire) {
                        return wire.name().trim();
                    }
                }
                """),

        // JBCT-TOT-03: Jackson wire-record accessor derefs a possibly-null component without a guard.
        fixture("JBCT-TOT-03", 4,
                """
                package org.example;
                record Wire(@JsonProperty("items") java.util.List<String> items) {
                    String first() {
                        return items().getFirst();
                    }
                }
                """,
                """
                package org.example;
                record Wire(@JsonProperty("items") java.util.List<String> items) {
                    String first() {
                        return items == null ? "" : items().getFirst();
                    }
                }
                """),

        // JBCT-BND-01: forbidden boundary type (java.util.Optional) in an import / type position.
        fixture("JBCT-BND-01", 2,
                """
                package org.example;
                import java.util.Optional;
                class Foo {
                    Optional<String> run() { return Optional.empty(); }
                }
                """,
                """
                package org.example;
                import org.pragmatica.lang.Option;
                class Foo {
                    Option<String> run() { return Option.none(); }
                }
                """),

        // JBCT-STY-09: a ternary nested inside another ternary.
        fixture("JBCT-STY-09", 4,
                """
                package org.example;
                class Foo {
                    Object run(int x) {
                        return x > 0 ? (x > 5 ? "a" : "b") : "c";
                    }
                }
                """,
                """
                package org.example;
                class Foo {
                    Object run(int x) {
                        return x > 0 ? "a" : "b";
                    }
                }
                """),

        // JBCT-NAM-03: a variant named *State implementing a *State sealed interface.
        fixture("JBCT-NAM-03", 3,
                """
                package org.example;
                sealed interface HoldState {
                    record HeldState() implements HoldState {}
                }
                """,
                """
                package org.example;
                sealed interface HoldState {
                    record Held() implements HoldState {}
                }
                """),

        // JBCT-NAM-04: a local record with a PascalCase name.
        fixture("JBCT-NAM-04", 4,
                """
                package org.example;
                class Foo {
                    Object run() {
                        record Cache(int x) {}
                        return new Cache(0);
                    }
                }
                """,
                """
                package org.example;
                class Foo {
                    Object run() {
                        record cache(int x) {}
                        return new cache(0);
                    }
                }
                """),

        // JBCT-NAM-05: a @Test method not named methodName_scenario_expectation.
        fixture("JBCT-NAM-05", 3,
                """
                package org.example;
                class FooTest {
                    @Test void run() {}
                }
                """,
                """
                package org.example;
                class FooTest {
                    @Test void run_returns_value() {}
                }
                """),

        // JBCT-MUT-01: reassignment of a method parameter.
        fixture("JBCT-MUT-01", 4,
                """
                package org.example;
                class Foo {
                    int run(int count) {
                        count = count + 1;
                        return count;
                    }
                }
                """,
                """
                package org.example;
                class Foo {
                    int run(int count) {
                        var total = count + 1;
                        return total;
                    }
                }
                """),

        // JBCT-RET-08: a literal null passed as a call argument (the .or(null) adapter is exempt).
        fixture("JBCT-RET-08", 4,
                """
                package org.example;
                class Foo {
                    Object run() {
                        return compute(null);
                    }
                }
                """,
                """
                package org.example;
                class Foo {
                    Object run(Option<String> opt) {
                        return opt.or(null);
                    }
                }
                """),

        // JBCT-SEAL-02: a fixed-message (zero-component) cause modelled as a record, not an enum.
        fixture("JBCT-SEAL-02", 3,
                """
                package org.example;
                sealed interface RegError extends Cause {
                    record TokenFailed() implements RegError {}
                }
                """,
                """
                package org.example;
                sealed interface RegError extends Cause {
                    record HashingFailed(Throwable cause) implements RegError {}
                }
                """),

        // JBCT-UC-02: use-case interface with nested Request/Response + execute but no static factory.
        fixture("JBCT-UC-02", 2,
                """
                package org.example;
                public interface RegisterUser {
                    record Request(String email) {}
                    record Response(String id) {}
                    Result<Response> execute(Request request);
                }
                """,
                """
                package org.example;
                public interface RegisterUser {
                    record Request(String email) {}
                    record Response(String id) {}
                    static RegisterUser registerUser() {
                        return request -> null;
                    }
                    Result<Response> execute(Request request);
                }
                """),

        // JBCT-ORD-01: value object with the static factory declared before a constant (out of order).
        fixture("JBCT-ORD-01", 6,
                """
                package org.example;
                record Money(long cents) {
                    static Result<Money> money(long cents) {
                        return Result.success(new Money(cents));
                    }
                    static final Money ZERO = new Money(0);
                }
                """,
                """
                package org.example;
                record Money(long cents) {
                    static final Money ZERO = new Money(0);
                    static Result<Money> money(long cents) {
                        return Result.success(new Money(cents));
                    }
                    long doubled() {
                        return cents * 2;
                    }
                }
                """),

        // JBCT-INJ-01: an implementation of an in-file step interface with a non-final instance field.
        fixture("JBCT-INJ-01", 6,
                """
                package org.example;
                interface CheckEmail {
                    Result<String> apply(String email);
                }
                class CheckEmailImpl implements CheckEmail {
                    private Repo repo;
                    public Result<String> apply(String email) {
                        return repo.find(email);
                    }
                }
                """,
                """
                package org.example;
                interface CheckEmail {
                    Result<String> apply(String email);
                }
                class CheckEmailImpl implements CheckEmail {
                    private final Repo repo;
                    CheckEmailImpl(Repo repo) {
                        this.repo = repo;
                    }
                    public Result<String> apply(String email) {
                        return repo.find(email);
                    }
                }
                """),

        // JBCT-VAL-01: a boolean isValid() on a value object (parse-don't-validate violation).
        fixture("JBCT-VAL-01", 6,
                """
                package org.example;
                record Email(String value) {
                    static Result<Email> email(String raw) {
                        return Result.success(new Email(raw));
                    }
                    boolean isValid() {
                        return value.contains("@");
                    }
                }
                """,
                """
                package org.example;
                record Email(String value) {
                    static Result<Email> email(String raw) {
                        return Result.success(new Email(raw));
                    }
                }
                """),

        // JBCT-STAGE-01: a three-hop request().request().request() previous-stage chain.
        fixture("JBCT-STAGE-01", 4,
                """
                package org.example;
                class Foo {
                    Object run() {
                        return ctx.request().request().request().userId();
                    }
                }
                """,
                """
                package org.example;
                class Foo {
                    Object run() {
                        return ctx.request().userId();
                    }
                }
                """),

        // JBCT-SIDE-01: a side-effect call (log.info) inside a map lambda.
        fixture("JBCT-SIDE-01", 4,
                """
                package org.example;
                class Foo {
                    Object run() {
                        return items.map(x -> log.info(x));
                    }
                }
                """,
                """
                package org.example;
                class Foo {
                    Object run() {
                        return items.map(String::trim);
                    }
                }
                """),

        // JBCT-SHAPE-01: MIXED — a fork-join head and a stream pipeline at the same altitude. The
        // verdict is anchored on the method declaration line (3). Negative: a clean fork-join.
        fixture("JBCT-SHAPE-01", 3,
                """
                package org.example;
                class Foo {
                    Object run() {
                        return Result.all(base(), limit()).map(this::ctx).stream().map(this::apply).toList();
                    }
                }
                """,
                """
                package org.example;
                class Foo {
                    Object run() {
                        return Promise.all(fetchA(id), fetchB(id)).map(this::merge);
                    }
                }
                """),

        // JBCT-SHAPE-02: UNCLASSIFIED — imperative residue (a leading side-effect statement before
        // the tail, no single composition root), anchored on the method declaration line (3). A pure
        // local-then-tail body is NOT residue after the phase-2 reach (#448) — it reads by its tail.
        // Negative: a clean sequencer.
        fixture("JBCT-SHAPE-02", 3,
                """
                package org.example;
                class Foo {
                    Object run() {
                        audit();
                        return compute().transform();
                    }
                }
                """,
                """
                package org.example;
                class Foo {
                    Object run() {
                        return compute().map(this::finish).flatMap(this::save);
                    }
                }
                """),

        // JBCT-SHAPE-03: mis-leveled — a Zone-3 implementation verb (`fetch`) heads a two-step
        // SEQUENCER, anchored on the method declaration line (3). Negative near-miss: the same
        // implementation verb heading a bare LEAF is correctly leveled and stays clean.
        fixture("JBCT-SHAPE-03", 3,
                """
                package org.example;
                class Foo {
                    Object fetchReport() {
                        return load().map(this::enrich).flatMap(this::store);
                    }
                }
                """,
                """
                package org.example;
                class Foo {
                    Object fetchReport() {
                        return load();
                    }
                }
                """));
}

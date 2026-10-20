package org.pragmatica.jbct.lint.cst.rules;

import java.util.Set;
import java.util.regex.Pattern;

import org.pragmatica.jbct.lint.cst.rules.Declarations.FileFacts;
import org.pragmatica.jbct.lint.cst.rules.Declarations.Slot;


/// Receiver-carrier heuristics for JBCT-ASYNC-01: is the receiver of an `onSuccess`/`onFailure`/`onResult` call a `Promise`
/// or a `Result`?
///
/// The linter sees one file's syntax and no types, and the two carriers share every callback name (`Result.on*` runs
/// inline; `Promise.on*` runs on a separate virtual thread). So the answer is inferred from the signals below and is `PROMISE`
/// only when one of them proves it; everything else is `UNKNOWN`, and the rule does not flag `UNKNOWN`.
///
/// 1. **The chain.** `Promise.x(...)` starts a promise chain, `Result.x(...)` a result chain; `.async()`, `.timeout(...)`,
///    `.withSuccess/withFailure/withResult(...)` and the `*Async` callbacks exist only on `Promise` and turn any chain into one;
///    `.await(...)` turns a promise chain into a `Result`. Every other combinator preserves the carrier it is called on.
/// 2. **Declarations in this file** ([Declarations]). A field, parameter, local or lambda parameter declared `Promise<...>` (or
///    `var x = <promise chain>`), a holder of promises read through its accessor (`supplier.get()`, `inFlight.compute(...)`), and
///    a method of this file declared to return `Promise<...>` (called bare, or as `x.m()` for a name no other declaration of the
///    file returns differently), name a promise.
/// 3. **The enclosing return.** `return <chain>.onX(...);` in a method declared to return `Promise<...>`: `onX` returns the
///    carrier it is called on, so that carrier is the declared return type.
///
/// **Limit.** A receiver whose type is declared in another file (`cluster.apply(...)`, `ctm.provision(...)`) is `UNKNOWN`
/// unless one of the signals above reaches it. That is the dominant source of missed sites, and the price of never guessing
/// `PROMISE` for a `Result` chain.
sealed interface PromiseCarrier permits PromiseCarrier.unused {
    record unused() implements PromiseCarrier {}

    enum Kind {
        PROMISE,
        RESULT,
        UNKNOWN
    }

    /// The state of a chain while it is read left to right; `HOLDER` has not yet yielded the promise it holds.
    enum State {
        PROMISE,
        RESULT,
        HOLDER,
        UNKNOWN
    }

    String IDENT = SourceText.IDENT;

    Pattern HEAD = Pattern.compile("\\s*(?:this\\s*\\.\\s*)?(" + IDENT + ")");
    Pattern MEMBER = Pattern.compile(IDENT);

    /// Combinators that exist on `Promise` only, so a chain containing one is a promise from there on.
    Set<String> PROMISE_ONLY = Set.of("async",
                                      "timeout",
                                      "withSuccess",
                                      "withFailure",
                                      "withResult",
                                      "onSuccessAsync",
                                      "onFailureAsync",
                                      "onResultAsync",
                                      "onSuccessRunAsync",
                                      "onFailureRunAsync",
                                      "onResultRunAsync");

    /// Terminals that leave the carrier: after them the value is no longer what the chain started as.
    Set<String> LEAVES_RESULT = Set.of("fold", "or", "orElse", "unwrap", "option", "toOption", "toResult", "isPresent", "get");

    /// Names too common across types to infer a carrier from a method of the same name declared in this file.
    Set<String> GENERIC_NAMES = Set.of("get", "apply", "call", "run", "accept", "test", "of", "put", "add", "remove", "map");

    /// Carrier of a receiver expression that starts at `offset`, read left to right: the head fixes the starting carrier, then
    /// each `.name(...)` either keeps it, switches it, or leaves it.
    static Kind kindOf(String receiver, FileFacts facts, int offset) {
        var text = receiver.strip();
        var head = HEAD.matcher(text);

        if (!head.lookingAt() || Declarations.CONTROL.contains(head.group(1))) {
            return Kind.UNKNOWN;
        }

        var name = head.group(1);
        var i = skipBlanks(text, head.end());
        var isCall = i < text.length() && text.charAt(i) == '(';
        var state = isCall
                    ? callHeadState(name, facts)
                    : namedHeadState(name, facts, offset);
        var viaType = !isCall && Character.isUpperCase(name.charAt(0));

        if (isCall) {
            var close = SourceText.closeOf(text, i);

            return close < 0
                   ? Kind.UNKNOWN
                   : kindOf(walk(text, close + 1, state, viaType, facts));
        }

        return kindOf(walk(text, i, state, viaType, facts));
    }

    private static Kind kindOf(State state) {
        return switch (state) {
            case PROMISE -> Kind.PROMISE;
            case RESULT -> Kind.RESULT;
            case HOLDER, UNKNOWN -> Kind.UNKNOWN;
        };
    }

    private static State callHeadState(String name, FileFacts facts) {
        if (facts.promiseMethods().contains(name)) {
            return State.PROMISE;
        }

        return facts.resultMethods().contains(name)
               ? State.RESULT
               : State.UNKNOWN;
    }

    private static State namedHeadState(String name, FileFacts facts, int offset) {
        return switch (name) {
            case "Promise" -> State.PROMISE;
            case "Result" -> State.RESULT;
            default -> facts.slotAt(name, offset)
                            .map(PromiseCarrier::stateOf)
                            .or(State.UNKNOWN);
        };
    }

    private static State stateOf(Slot slot) {
        return switch (slot) {
            case PROMISE -> State.PROMISE;
            case RESULT -> State.RESULT;
            case HOLDER -> State.HOLDER;
            case OTHER -> State.UNKNOWN;
        };
    }

    /// @param viaType the chain starts at a type name (`FileOps.delete(...)`): its methods are that type's, not this file's
    private static State walk(String text, int from, State start, boolean viaType, FileFacts facts) {
        var state = start;
        var i = skipBlanks(text, from);

        while (i < text.length()) {
            if (text.charAt(i) != '.') {
                return State.UNKNOWN;
            }

            i = skipBlanks(text, i + 1);

            if (i < text.length() && text.charAt(i) == '<') {
                var close = SourceText.closeAngle(text, i);

                if (close < 0) {
                    return State.UNKNOWN;
                }

                i = skipBlanks(text, close + 1);
            }

            var member = MEMBER.matcher(text)
                               .region(i, text.length());

            if (!member.lookingAt()) {
                return State.UNKNOWN;
            }

            i = skipBlanks(text, member.end());

            if (i >= text.length() || text.charAt(i) != '(') {
                state = State.UNKNOWN;
                continue;
            }

            var close = SourceText.closeOf(text, i);

            if (close < 0) {
                return State.UNKNOWN;
            }

            state = afterCall(state, member.group(), viaType, facts);
            i = skipBlanks(text, close + 1);
        }

        return state;
    }

    private static int skipBlanks(String text, int from) {
        var i = from;

        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }

        return i;
    }

    private static State afterCall(State state, String member, boolean viaType, FileFacts facts) {
        if (PROMISE_ONLY.contains(member)) {
            return State.PROMISE;
        }

        return switch (state) {
            case PROMISE -> "await".equals(member)
                            ? State.RESULT
                            : State.PROMISE;
            case RESULT -> LEAVES_RESULT.contains(member)
                           ? State.UNKNOWN
                           : State.RESULT;
            case HOLDER -> Declarations.HOLDER_ACCESSORS.contains(member)
                           ? State.PROMISE
                           : State.UNKNOWN;
            case UNKNOWN -> !viaType && isPromiseMethod(member, facts)
                            ? State.PROMISE
                            : State.UNKNOWN;
        };
    }

    private static boolean isPromiseMethod(String member, FileFacts facts) {
        return facts.promiseMethods().contains(member) && !GENERIC_NAMES.contains(member);
    }

    /// Declared return type of the method whose body encloses `offset` through plain blocks only. A lambda body in between
    /// returns for the lambda, not the method, so it makes the enclosing declaration irrelevant.
    static String enclosingReturnType(String masked, int offset, FileFacts facts) {
        return facts.innermostAt(offset)
                    .filter(method -> method.bodyStart() <= offset && !crossesLambda(masked, method.bodyStart(), offset))
                    .map(Declarations.MethodDecl::returnType)
                    .or("");
    }

    private static boolean crossesLambda(String masked, int from, int offset) {
        var depth = 0;

        for (var i = offset - 1; i >= from; i--) {
            var c = masked.charAt(i);

            if (c == '}') {
                depth++;
            } else if (c == '{') {
                if (depth > 0) {
                    depth--;
                } else if (precededByArrow(masked, i)) {
                    return true;
                }
            }
        }

        return false;
    }

    private static boolean precededByArrow(String masked, int brace) {
        var i = brace - 1;

        while (i >= 0 && Character.isWhitespace(masked.charAt(i))) {
            i--;
        }

        return i >= 1 && masked.charAt(i) == '>' && masked.charAt(i - 1) == '-';
    }
}

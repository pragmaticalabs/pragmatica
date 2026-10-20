package org.pragmatica.jbct.lint.cst.rules;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;


/// Receiver-carrier heuristics for JBCT-ASYNC-01: is the receiver of an `onSuccess`/`onFailure`/`onResult` call a `Promise`
/// or a `Result`?
///
/// The linter sees one file's syntax and no types, and the two carriers share every callback name (`Result.on*` runs
/// inline; `Promise.on*` runs on a separate virtual thread). So the answer is inferred from three signals and is `PROMISE`
/// only when one of them proves it; everything else is `UNKNOWN`, and the rule does not flag `UNKNOWN`.
///
/// 1. **The chain.** `Promise.x(...)` starts a promise chain, `Result.x(...)` a result chain; `.async()`, `.timeout(...)`,
///    `.withSuccess/withFailure/withResult(...)` and the `*Async` callbacks exist only on `Promise` and turn any chain into one;
///    `.await(...)` turns a promise chain into a `Result`. Every other combinator preserves the carrier it is called on.
/// 2. **Declarations in this file.** A field, parameter or local declared `Promise<...>` (or `var x = <promise chain>`), and a
///    method declared to return `Promise<...>`, name a promise when used as the head of the chain.
/// 3. **The enclosing return.** `return <chain>.onX(...);` in a method declared to return `Promise<...>` (or `Result<...>`):
///    `onX` returns the carrier it is called on, so that carrier is the declared return type.
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

    /// A method declared in the file: simple name, declared return type's simple name, parameter names and the span of its body.
    record MethodDecl(String name, String returnType, Set<String> params, int bodyStart, int bodyEnd) {}

    /// What one file declares. Names declared with both a Promise and a non-Promise type are dropped from `promiseNames`.
    record FileFacts(Set<String> promiseNames,
                     Set<String> resultNames,
                     Set<String> promiseMethods,
                     Set<String> resultMethods,
                     Map<String, List<MethodDecl>> methods) {}

    String IDENT = "[A-Za-z_$][A-Za-z0-9_$]*";

    Pattern DECLARED = Pattern.compile("(?<![.\\w$])((?:" + IDENT + "\\.)*)(" + IDENT + ")\\s*(<)?");
    Pattern DECLARED_NAME = Pattern.compile("\\s*(?:\\[\\]\\s*)*(" + IDENT + ")\\s*(\\(|=|;|,|\\)|:)");
    Pattern VAR_DECL = Pattern.compile("\\bvar\\s+(" + IDENT + ")\\s*=");
    Pattern METHOD_HEAD = Pattern.compile("(?<![\\w$.])(" + IDENT + ")\\s*\\(");
    Pattern HEAD = Pattern.compile("\\s*(?:this\\s*\\.\\s*)?(" + IDENT + ")");
    Pattern THROWS = Pattern.compile("\\s*throws\\s+[\\w.,\\s<>]+?\\s*\\{");

    Set<String> NOT_A_TYPE = Set.of("return", "new", "else", "case", "throw", "import", "package", "instanceof", "extends",
                                    "implements", "throws", "yield", "assert");
    Set<String> CONTROL = Set.of("if", "for", "while", "switch", "catch", "synchronized", "try", "do", "return", "new", "else",
                                 "throw", "super", "this");

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

    static FileFacts facts(String masked) {
        var promiseNames = new HashSet<String>();
        var resultNames = new HashSet<String>();
        var otherNames = new HashSet<String>();
        var promiseMethods = new HashSet<String>();
        var resultMethods = new HashSet<String>();
        var otherMethods = new HashSet<String>();

        scanDeclarations(masked, promiseNames, resultNames, otherNames, promiseMethods, resultMethods, otherMethods);

        var methods = methodTable(masked);
        var partial = new FileFacts(promiseNames, resultNames, promiseMethods, resultMethods, methods);

        for (var pass = 0; pass < 2; pass++) {
            inferVars(masked, partial, otherNames);
        }

        promiseNames.removeAll(otherNames);
        promiseNames.removeAll(resultNames);
        promiseMethods.removeAll(otherMethods);
        promiseMethods.removeAll(resultMethods);
        resultMethods.removeAll(otherMethods);
        return partial;
    }

    private static void scanDeclarations(String masked,
                                         Set<String> promiseNames,
                                         Set<String> resultNames,
                                         Set<String> otherNames,
                                         Set<String> promiseMethods,
                                         Set<String> resultMethods,
                                         Set<String> otherMethods) {
        var matcher = DECLARED.matcher(masked);

        while (matcher.find()) {
            var typeName = matcher.group(2);
            var end = matcher.end();

            if (NOT_A_TYPE.contains(typeName)) {
                continue;
            }

            if (matcher.group(3) != null) {
                var close = closeAngle(masked, matcher.end() - 1);

                if (close < 0) {
                    continue;
                }

                end = close + 1;
            }

            var named = DECLARED_NAME.matcher(masked).region(end, masked.length());

            if (!named.lookingAt() || NOT_A_TYPE.contains(named.group(1))) {
                continue;
            }

            record(typeName, named.group(1), "(".equals(named.group(2)), promiseNames, resultNames, otherNames,
                   promiseMethods, resultMethods, otherMethods);
        }
    }

    private static void record(String typeName,
                               String name,
                               boolean isMethod,
                               Set<String> promiseNames,
                               Set<String> resultNames,
                               Set<String> otherNames,
                               Set<String> promiseMethods,
                               Set<String> resultMethods,
                               Set<String> otherMethods) {
        var isPromise = "Promise".equals(typeName);
        var isResult = "Result".equals(typeName);

        if (isMethod) {
            (isPromise
             ? promiseMethods
             : isResult
               ? resultMethods
               : otherMethods).add(name);
            return;
        }

        (isPromise
         ? promiseNames
         : isResult
           ? resultNames
           : otherNames).add(name);
    }

    /// `var x = <chain>;` — the declared name takes the carrier of its initializer.
    private static void inferVars(String masked, FileFacts facts, Set<String> otherNames) {
        var matcher = VAR_DECL.matcher(masked);

        while (matcher.find()) {
            var end = statementEnd(masked, matcher.end());

            if (end < 0) {
                continue;
            }

            switch (kindOf(masked.substring(matcher.end(), end), facts)) {
                case PROMISE -> facts.promiseNames().add(matcher.group(1));
                case RESULT -> facts.resultNames().add(matcher.group(1));
                case UNKNOWN -> otherNames.add(matcher.group(1));
            }
        }
    }

    /// Methods with a body declared in this file, keyed by simple name (overloads share a key).
    private static Map<String, List<MethodDecl>> methodTable(String masked) {
        var table = new HashMap<String, List<MethodDecl>>();
        var matcher = METHOD_HEAD.matcher(masked);

        while (matcher.find()) {
            var name = matcher.group(1);

            if (CONTROL.contains(name)) {
                continue;
            }

            var open = matcher.end() - 1;
            var close = closeOf(masked, open);

            if (close < 0) {
                continue;
            }

            var bodyOpen = bodyOpenAfter(masked, close + 1);

            if (bodyOpen < 0) {
                continue;
            }

            var returnType = returnTypeBefore(masked, matcher.start());

            if (returnType.isEmpty()) {
                continue;
            }

            var bodyClose = closeOf(masked, bodyOpen);

            if (bodyClose > 0) {
                table.computeIfAbsent(name, _ -> new ArrayList<>())
                     .add(new MethodDecl(name, returnType, paramNames(masked.substring(open + 1, close)), bodyOpen + 1, bodyClose));
            }
        }

        return table;
    }

    private static int bodyOpenAfter(String masked, int from) {
        var throwsClause = THROWS.matcher(masked).region(from, masked.length());

        if (throwsClause.lookingAt()) {
            return throwsClause.end() - 1;
        }

        var i = from;

        while (i < masked.length() && Character.isWhitespace(masked.charAt(i))) {
            i++;
        }

        return i < masked.length() && masked.charAt(i) == '{'
               ? i
               : - 1;
    }

    /// Simple name of the type that precedes a method name (`Promise<Unit> run(` gives `Promise`), or empty when the token before
    /// the name is not a type — a call statement, a `new`, an operator.
    private static String returnTypeBefore(String masked, int nameStart) {
        var end = nameStart - 1;

        while (end >= 0 && Character.isWhitespace(masked.charAt(end))) {
            end--;
        }

        if (end >= 0 && masked.charAt(end) == '>') {
            end = openAngle(masked, end) - 1;

            while (end >= 0 && Character.isWhitespace(masked.charAt(end))) {
                end--;
            }
        }

        while (end >= 1 && masked.charAt(end) == ']' && masked.charAt(end - 1) == '[') {
            end -= 2;
        }

        var start = end;

        while (start >= 0 && isIdentChar(masked.charAt(start))) {
            start--;
        }

        if (start == end) {
            return "";
        }

        var token = masked.substring(start + 1, end + 1);

        return NOT_A_TYPE.contains(token)
               ? ""
               : token;
    }

    private static Set<String> paramNames(String list) {
        var names = new HashSet<String>();

        for (var chunk : list.split(",")) {
            var matcher = Pattern.compile(IDENT).matcher(chunk);
            String last = null;

            while (matcher.find()) {
                last = matcher.group();
            }

            if (last != null) {
                names.add(last);
            }
        }

        return names;
    }

    /// Carrier of a receiver expression, read left to right: the head fixes the starting carrier, then each `.name(...)` either
    /// keeps it, switches it, or leaves it.
    static Kind kindOf(String receiver, FileFacts facts) {
        var text = receiver.strip();
        var head = HEAD.matcher(text);

        if (!head.lookingAt() || CONTROL.contains(head.group(1))) {
            return Kind.UNKNOWN;
        }

        var name = head.group(1);
        var i = head.end();

        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }

        var kind = i < text.length() && text.charAt(i) == '('
                   ? callHeadKind(name, facts)
                   : namedHeadKind(name, facts);

        if (i < text.length() && text.charAt(i) == '(') {
            var close = closeOf(text, i);

            if (close < 0) {
                return Kind.UNKNOWN;
            }

            i = close + 1;
        }

        return walk(text, i, kind);
    }

    private static Kind callHeadKind(String name, FileFacts facts) {
        return facts.promiseMethods().contains(name)
               ? Kind.PROMISE
               : facts.resultMethods().contains(name)
                 ? Kind.RESULT
                 : Kind.UNKNOWN;
    }

    private static Kind namedHeadKind(String name, FileFacts facts) {
        return switch (name) {
            case "Promise" -> Kind.PROMISE;
            case "Result" -> Kind.RESULT;
            default -> facts.promiseNames().contains(name)
                       ? Kind.PROMISE
                       : facts.resultNames().contains(name)
                         ? Kind.RESULT
                         : Kind.UNKNOWN;
        };
    }

    private static Kind walk(String text, int from, Kind start) {
        var kind = start;
        var i = from;

        while (i < text.length()) {
            var c = text.charAt(i);

            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }

            if (c != '.') {
                return Kind.UNKNOWN;
            }

            i++;

            while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
                i++;
            }

            if (i < text.length() && text.charAt(i) == '<') {
                var close = closeAngle(text, i);

                if (close < 0) {
                    return Kind.UNKNOWN;
                }

                i = close + 1;
            }

            var matcher = Pattern.compile(IDENT).matcher(text).region(i, text.length());

            if (!matcher.lookingAt()) {
                return Kind.UNKNOWN;
            }

            var member = matcher.group();

            i = matcher.end();

            while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
                i++;
            }

            if (i < text.length() && text.charAt(i) == '(') {
                var close = closeOf(text, i);

                if (close < 0) {
                    return Kind.UNKNOWN;
                }

                i = close + 1;
                kind = afterCall(kind, member);
            } else {
                kind = Kind.UNKNOWN;
            }
        }

        return kind;
    }

    private static Kind afterCall(Kind kind, String member) {
        if (PROMISE_ONLY.contains(member)) {
            return Kind.PROMISE;
        }

        if ("await".equals(member) && kind == Kind.PROMISE) {
            return Kind.RESULT;
        }

        if (kind == Kind.RESULT && LEAVES_RESULT.contains(member)) {
            return Kind.UNKNOWN;
        }

        return kind;
    }

    /// Declared return type of the method whose body encloses `offset` through plain blocks only. A lambda body in between
    /// returns for the lambda, not the method, so it makes the enclosing declaration irrelevant.
    static String enclosingReturnType(String masked, int offset, FileFacts facts) {
        MethodDecl best = null;

        for (var overloads : facts.methods().values()) {
            for (var method : overloads) {
                if (method.bodyStart() <= offset && offset < method.bodyEnd()
                    && (best == null || method.bodyStart() > best.bodyStart())) {
                    best = method;
                }
            }
        }

        return best == null || crossesLambda(masked, best.bodyStart(), offset)
               ? ""
               : best.returnType();
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

    /// Offset of the `;` ending the statement that begins at `from`, skipping nested brackets; `-1` when none.
    static int statementEnd(String masked, int from) {
        var depth = 0;

        for (var i = from; i < masked.length(); i++) {
            var c = masked.charAt(i);

            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) {
                    return - 1;
                }

                depth--;
            } else if (c == ';' && depth == 0) {
                return i;
            }
        }

        return - 1;
    }

    /// Offset of the bracket closing the one at `open`, or `-1`.
    static int closeOf(String s, int open) {
        var depth = 0;

        for (var i = open; i < s.length(); i++) {
            var c = s.charAt(i);

            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;

                if (depth == 0) {
                    return i;
                }
            }
        }

        return - 1;
    }

    /// Offset of the `>` closing the `<` at `open`; `-1` when the span is not a type-argument list.
    static int closeAngle(String s, int open) {
        var depth = 0;

        for (var i = open; i < s.length(); i++) {
            var c = s.charAt(i);

            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;

                if (depth == 0) {
                    return i;
                }
            } else if (depth > 0 && (c == ';' || c == '{' || c == '}' || c == '(' || c == ')' || c == '=')) {
                return - 1;
            }
        }

        return - 1;
    }

    private static int openAngle(String s, int close) {
        var depth = 0;

        for (var i = close; i >= 0; i--) {
            var c = s.charAt(i);

            if (c == '>') {
                depth++;
            } else if (c == '<') {
                depth--;

                if (depth == 0) {
                    return i;
                }
            } else if (c == ';' || c == '{' || c == '}') {
                return close;
            }
        }

        return close;
    }

    static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    /// Start of the call chain whose last link ends at `dot`: scanned backward over balanced brackets to the first statement or
    /// argument boundary.
    static int chainStart(String s, int dot) {
        var depth = 0;

        for (var i = dot - 1; i >= 0; i--) {
            var c = s.charAt(i);

            if ((c == '{' || c == '}') && depth == 0) {
                return i + 1;
            }

            if (c == ')' || c == ']' || c == '}') {
                depth++;
            } else if (c == '(' || c == '[' || c == '{') {
                if (depth == 0) {
                    return i + 1;
                }

                depth--;
            } else if (depth == 0 && isBoundary(s, i)) {
                return i + 1;
            }
        }

        return 0;
    }

    private static boolean isBoundary(String s, int i) {
        var c = s.charAt(i);

        return c == ';' || c == ',' || c == '=' && !isComparison(s, i) || c == '?' || c == ':' && !isDoubleColon(s, i)
               || c == '>' && i > 0 && s.charAt(i - 1) == '-';
    }

    private static boolean isComparison(String s, int i) {
        var next = i + 1 < s.length()
                   ? s.charAt(i + 1)
                   : ' ';
        var prev = i > 0
                   ? s.charAt(i - 1)
                   : ' ';

        return next == '=' || prev == '=' || prev == '!' || prev == '<' || prev == '>';
    }

    private static boolean isDoubleColon(String s, int i) {
        return i > 0 && s.charAt(i - 1) == ':' || i + 1 < s.length() && s.charAt(i + 1) == ':';
    }

    /// A call chain's text with the statement prefix that precedes it removed, and whether that prefix was `return`.
    record Chain(String text, boolean isReturn) {}

    Pattern ELSE = Pattern.compile("^\\s*else\\b");
    Pattern HEADER = Pattern.compile("^\\s*(?:if|while|for|switch)\\s*(?=\\()");
    Pattern RETURN = Pattern.compile("^\\s*return\\b");

    /// Strips leading `else` and `if (...)`/`while (...)`/`for (...)` headers, then a leading `return`, from `chain`.
    static Chain chain(String chain) {
        var text = chain;
        var next = stripControl(text);

        while (!next.equals(text)) {
            text = next;
            next = stripControl(text);
        }

        var ret = RETURN.matcher(text);

        return ret.find()
               ? new Chain(text.substring(ret.end()), true)
               : new Chain(text, false);
    }

    private static String stripControl(String text) {
        var otherwise = ELSE.matcher(text);

        if (otherwise.find()) {
            return text.substring(otherwise.end());
        }

        var header = HEADER.matcher(text);

        if (header.find()) {
            var close = closeOf(text, header.end());

            return close > 0
                   ? text.substring(close + 1)
                   : text;
        }

        return text;
    }
}

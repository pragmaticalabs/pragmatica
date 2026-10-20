package org.pragmatica.jbct.lint.cst.rules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.pragmatica.lang.Option;


/// Detects writes to shared state in the text of a callback, for JBCT-ASYNC-01.
///
/// "Shared" means reachable by something other than the callback: a field, a captured variable, a static, or anything held by
/// one of them. The scan sees three shapes, each with a root identifier that must not be local to the callback (a lambda
/// parameter, or a name the callback itself declares):
///
/// - an assignment, compound assignment or `++`/`--` (`done = true`, `this.count += n`, `slots[i] = x`, `state.phase = p`);
/// - a call to a mutating verb on such a root (`pending.put(k, v)`, `running.set(false)`, `epoch.incrementAndGet()`,
///   `claimed.compareAndSet(a, b)`, `index.remove(k)`), or a setter-named call (`setPhase(p)`) with no receiver;
/// - the same two shapes inside a method of this file that the callback calls or references (`this::landed`,
///   `_ -> reapConfirmed(id)`), followed to a depth of three.
///
/// **Not a write**, on purpose: logging, scheduling, and resolving another promise (`succeed`/`fail`/`resolve`/`complete`) — the
/// three effects the rule's contract exempts. Metric recording and other verbs outside the catalog are not scanned for either.
///
/// **Limits.** The catalog is by verb name, not by receiver type, so a non-mutating method that shares a catalog name on a
/// shared receiver is a false positive (`cache.remove` on a copy, `buffer.set` on a thread-confined structure). A write made
/// by a method declared in another file (`context.dispatch(...)`, `tlsRotation::applied`) is not seen: that is the second source
/// of missed sites.
sealed interface SharedWrites permits SharedWrites.unused {
    record unused() implements SharedWrites {}

    /// A write found in a callback, described for the diagnostic.
    record Write(String description, List<String> via) {}

    int MAX_DEPTH = 3;

    String IDENT = "[A-Za-z_$][A-Za-z0-9_$]*";
    String PATH = "(?:" + IDENT + "\\s*\\.\\s*)*" + IDENT;

    Pattern ASSIGNMENT = Pattern.compile("(?<![\\w$=!<>+\\-*/%&|^.])(?:(this)\\s*\\.\\s*)?(" + PATH + ")\\s*(?:\\[[^\\]]*\\]\\s*)?"
                                         + "(=|\\+=|-=|\\*=|/=|%=|\\|=|&=|\\^=|<<=|>>=|>>>=|\\+\\+|--)(?!=)");
    Pattern PREFIX = Pattern.compile("(\\+\\+|--)\\s*(" + PATH + ")");

    String VERBS = "put|putAll|putIfAbsent|add|addAll|addFirst|addLast|remove|removeIf|removeAll|retainAll|clear|set|lazySet"
                   + "|getAndSet|incrementAndGet|getAndIncrement|decrementAndGet|getAndDecrement|addAndGet|getAndAdd"
                   + "|compareAndSet|compareAndExchange|weakCompareAndSet\\w*|updateAndGet|getAndUpdate|accumulateAndGet"
                   + "|getAndAccumulate|compute|computeIfAbsent|computeIfPresent|merge|replace|replaceAll|offer|offerFirst"
                   + "|offerLast|push|pop|poll|pollFirst|pollLast|append|increment|decrement|reset|setRelease|setPlain"
                   + "|setVolatile|setOpaque|accumulate";
    Pattern QUALIFIED_CALL = Pattern.compile("((?:" + IDENT + "(?:\\s*\\([^()]*(?:\\([^()]*\\)[^()]*)*\\))?\\s*\\.\\s*)+)(?:"
                                             + VERBS + ")\\s*\\(");
    Pattern SETTER_CALL = Pattern.compile("(?<![\\w$.])(?:this\\s*\\.\\s*)?(set[A-Z][A-Za-z0-9_$]*)\\s*\\(");
    Pattern PLAIN_CALL = Pattern.compile("(?<![\\w$.])(?:this\\s*\\.\\s*)?(" + IDENT + ")\\s*\\(");
    Pattern METHOD_REF = Pattern.compile("^\\s*(?:this|" + IDENT + ")\\s*::\\s*(" + IDENT + ")\\s*$");
    Pattern LAMBDA = Pattern.compile("^\\s*(?:\\(([^()]*)\\)|(" + IDENT + "))\\s*->\\s*(.*)$", Pattern.DOTALL);

    Pattern LOCAL_DECL = Pattern.compile("(?:\\b(?:final|var)\\s+|\\b[A-Z][\\w$]*(?:\\.[A-Z][\\w$]*)*(?:<[^;(){}=]*>)?(?:\\[\\])*\\s+"
                                         + "|\\b(?:int|long|boolean|double|byte|short|char|float)(?:\\[\\])*\\s+)(" + IDENT
                                         + ")\\s*(?:=|;|:|,|\\))");
    Pattern LAMBDA_PARAM = Pattern.compile("(" + IDENT + ")\\s*->");
    Pattern LAMBDA_PARAMS = Pattern.compile("\\(([^()]*)\\)\\s*->");
    Pattern CATCH_PARAM = Pattern.compile("\\bcatch\\s*\\([^)]*?(" + IDENT + ")\\s*\\)");
    Pattern PATTERN_BINDING = Pattern.compile("\\b(?:instanceof|case)\\s+(?:final\\s+)?[A-Z][\\w$.<>]*\\s+(" + IDENT + ")\\b");

    Set<String> NOT_SHARED_ROOTS = Set.of("log", "LOG", "logger", "LOGGER", "Promise", "Result", "Option", "Objects", "Math",
                                          "String", "System", "Unit");
    Set<String> NOT_ASSIGNMENT_ROOTS = Set.of("return", "case", "default", "else", "new", "throw", "yield");

    /// First shared write reachable from a callback argument (`x -> ...`, `() -> ...`, `this::name`, `Type::name`), or none.
    static Option<Write> inCallback(String argument, PromiseCarrier.FileFacts facts, String masked) {
        var lambda = LAMBDA.matcher(argument);

        if (lambda.matches()) {
            var params = lambda.group(1) != null
                         ? lambda.group(1)
                         : lambda.group(2);

            return inBody(paramNames(params), lambda.group(3), facts, masked, MAX_DEPTH, Set.of());
        }

        var reference = METHOD_REF.matcher(argument);

        return reference.matches()
               ? viaMethod(reference.group(1), facts, masked, MAX_DEPTH, Set.of())
               : Option.none();
    }

    private static Option<Write> inBody(Set<String> params,
                                        String body,
                                        PromiseCarrier.FileFacts facts,
                                        String masked,
                                        int depth,
                                        Set<String> seen) {
        var locals = new HashSet<String>(params);

        locals.addAll(declaredLocals(body));

        return direct(locals, body).orElse(() -> delegated(locals, body, facts, masked, depth, seen));
    }

    private static Option<Write> direct(Set<String> locals, String body) {
        return assignment(locals, body).orElse(() -> prefixStep(locals, body))
                                       .orElse(() -> mutatingCall(locals, body))
                                       .orElse(() -> setterCall(body));
    }

    private static Option<Write> assignment(Set<String> locals, String body) {
        var matcher = ASSIGNMENT.matcher(body);

        while (matcher.find()) {
            var root = root(matcher.group(2));

            if (NOT_ASSIGNMENT_ROOTS.contains(root)) {
                continue;
            }

            if (matcher.group(1) != null || !locals.contains(root)) {
                return Option.some(new Write("assigns " + matcher.group().strip(), List.of()));
            }
        }

        return Option.none();
    }

    private static Option<Write> prefixStep(Set<String> locals, String body) {
        var matcher = PREFIX.matcher(body);

        while (matcher.find()) {
            if (!locals.contains(root(matcher.group(2)))) {
                return Option.some(new Write("updates " + matcher.group().strip(), List.of()));
            }
        }

        return Option.none();
    }

    private static Option<Write> mutatingCall(Set<String> locals, String body) {
        var matcher = QUALIFIED_CALL.matcher(body);

        while (matcher.find()) {
            var qualifier = matcher.group(1);
            var root = root(qualifier);

            if (!locals.contains(root) && !NOT_SHARED_ROOTS.contains(root)) {
                return Option.some(new Write("calls " + compact(matcher.group()), List.of()));
            }
        }

        return Option.none();
    }

    private static Option<Write> setterCall(String body) {
        var matcher = SETTER_CALL.matcher(body);

        return matcher.find()
               ? Option.some(new Write("calls " + matcher.group(1) + "(...)", List.of()))
               : Option.none();
    }

    /// Follows plain calls into methods of this file; the first write found there is reported with the call path.
    private static Option<Write> delegated(Set<String> locals,
                                           String body,
                                           PromiseCarrier.FileFacts facts,
                                           String masked,
                                           int depth,
                                           Set<String> seen) {
        if (depth <= 0) {
            return Option.none();
        }

        var matcher = PLAIN_CALL.matcher(body);

        while (matcher.find()) {
            var name = matcher.group(1);

            if (!PromiseCarrier.CONTROL.contains(name) && !seen.contains(name)) {
                var found = viaMethod(name, facts, masked, depth, seen);

                if (found.isPresent()) {
                    return found;
                }
            }
        }

        return Option.none();
    }

    private static Option<Write> viaMethod(String name,
                                           PromiseCarrier.FileFacts facts,
                                           String masked,
                                           int depth,
                                           Set<String> seen) {
        var overloads = facts.methods().getOrDefault(name, List.of());
        var nowSeen = new HashSet<String>(seen);

        nowSeen.add(name);

        for (var method : overloads) {
            var found = inBody(method.params(), masked.substring(method.bodyStart(), method.bodyEnd()), facts, masked, depth - 1,
                               nowSeen);

            if (found.isPresent()) {
                return found.map(write -> prepend(name, write));
            }
        }

        return Option.none();
    }

    private static Write prepend(String method, Write write) {
        var via = new ArrayList<String>();

        via.add(method);
        via.addAll(write.via());
        return new Write(write.description(), List.copyOf(via));
    }

    private static Set<String> declaredLocals(String body) {
        var names = new HashSet<String>();

        collect(LOCAL_DECL, body, names, 1);
        collect(LAMBDA_PARAM, body, names, 1);
        collect(CATCH_PARAM, body, names, 1);
        collect(PATTERN_BINDING, body, names, 1);

        var multi = LAMBDA_PARAMS.matcher(body);

        while (multi.find()) {
            names.addAll(paramNames(multi.group(1)));
        }

        return names;
    }

    private static void collect(Pattern pattern, String text, Set<String> names, int group) {
        var matcher = pattern.matcher(text);

        while (matcher.find()) {
            names.add(matcher.group(group));
        }
    }

    private static Set<String> paramNames(String list) {
        var names = new HashSet<String>();
        var ident = Pattern.compile(IDENT);

        for (var chunk : list.split(",")) {
            var matcher = ident.matcher(chunk);
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

    private static String root(String path) {
        var stripped = path.strip();
        var end = 0;

        while (end < stripped.length() && PromiseCarrier.isIdentChar(stripped.charAt(end))) {
            end++;
        }

        return stripped.substring(0, end);
    }

    private static String compact(String call) {
        return call.replaceAll("\\s+", "");
    }
}

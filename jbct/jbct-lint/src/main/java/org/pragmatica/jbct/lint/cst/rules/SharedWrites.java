package org.pragmatica.jbct.lint.cst.rules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


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
///   `_ -> reapConfirmed(id)`), followed to a depth of three. A callee's parameter counts as local only when the call hands it a
///   value the callback owns; a constructor (`new Foo(v)`) is not followed, it writes the object being built.
///
/// **Not a write**, on purpose: logging, scheduling, and resolving another promise (`succeed`/`fail`/`resolve`/`complete`) — the
/// three effects the rule's contract exempts. Metric recording and other verbs outside the catalog are not scanned for either.
///
/// **Limits.** The catalog is by verb name, not by receiver type, so a non-mutating method that shares a catalog name on a
/// shared receiver is a false positive (`cache.remove` on a copy, `buffer.set` on a thread-confined structure). Locals are
/// recognized by declaration shape (`Type name =`, `var`, a lambda parameter), so a local declared with a lowercase type name is
/// read as shared. A write made by a method declared in another file (`context.dispatch(...)`, `tlsRotation::applied`) is not
/// seen: that is the second source of missed sites.
sealed interface SharedWrites permits SharedWrites.unused {
    record unused() implements SharedWrites {}

    /// A write found in a callback, described for the diagnostic.
    record Write(String description, List<String> via) {
        static Write write(String description, List<String> via) {
            return new Write(description, via);
        }
    }

    int MAX_DEPTH = 3;
    String IDENT = "[A-Za-z_$][A-Za-z0-9_$]*";
    String PATH = "(?:" + IDENT + "\\s*\\.\\s*)*" + IDENT;

    Pattern ASSIGNMENT = Pattern.compile("(?<![\\w$=!<>+\\-*/%&|^.])(?:(this)\\s*\\.\\s*)?(" + PATH
                                        + ")\\s*(?:\\[[^\\]]*\\]\\s*)?"
                                        + "(=|\\+=|-=|\\*=|/=|%=|\\|=|&=|\\^=|<<=|>>=|>>>=|\\+\\+|--)(?!=)");

    Pattern PREFIX = Pattern.compile("(\\+\\+|--)\\s*(" + PATH + ")");

    String VERBS = "put|putAll|putIfAbsent|add|addAll|addFirst|addLast|remove|removeIf|removeAll|retainAll|clear|set|lazySet"
                 + "|getAndSet|incrementAndGet|getAndIncrement|decrementAndGet|getAndDecrement|addAndGet|getAndAdd"
                 + "|compareAndSet|compareAndExchange|weakCompareAndSet\\w*|updateAndGet|getAndUpdate|accumulateAndGet"
                 + "|getAndAccumulate|compute|computeIfAbsent|computeIfPresent|merge|replace|replaceAll|offer|offerFirst"
                 + "|offerLast|push|pop|poll|pollFirst|pollLast|append|increment|decrement|reset|setRelease|setPlain"
                 + "|setVolatile|setOpaque|accumulate";

    Pattern QUALIFIED_CALL = Pattern.compile("((?:" + IDENT
                                            + "(?:\\s*\\([^()]*(?:\\([^()]*\\)[^()]*)*\\))?\\s*\\.\\s*)+)(?:" + VERBS
                                            + ")\\s*\\(");

    Pattern SETTER_CALL = Pattern.compile("(?<![\\w$.])(?:this\\s*\\.\\s*)?(set[A-Z][A-Za-z0-9_$]*)\\s*\\(");
    Pattern PLAIN_CALL = Pattern.compile("(?<![\\w$.])(?:this\\s*\\.\\s*)?(" + IDENT + ")\\s*\\(");
    Pattern METHOD_REF = Pattern.compile("^\\s*(?:this|" + IDENT + ")\\s*::\\s*(" + IDENT + ")\\s*$");
    Pattern NEW_BEFORE = Pattern.compile("\\bnew\\s+$");
    Pattern ARGUMENT_ROOT = Pattern.compile("(?:this\\s*\\.\\s*)?(" + IDENT + ")");
    Pattern LAMBDA = Pattern.compile("^\\s*(?:\\(([^()]*)\\)|(" + IDENT + "))\\s*->\\s*(.*)$",
                                     Pattern.DOTALL);

    Pattern LOCAL_DECL = Pattern.compile("(?:\\b(?:final|var)\\s+|\\b[A-Z][\\w$]*(?:\\.[A-Z][\\w$]*)*(?:<[^;(){}=]*>)?(?:\\[\\])*\\s+"
                                        + "|\\b(?:int|long|boolean|double|byte|short|char|float)(?:\\[\\])*\\s+)(" + IDENT
                                        + ")\\s*(?:=|;|:|,|\\))");

    Pattern LAMBDA_PARAM = Pattern.compile("(" + IDENT + ")\\s*->");
    Pattern LAMBDA_PARAMS = Pattern.compile("\\(([^()]*)\\)\\s*->");
    /// Roots whose mutating-looking calls touch nothing shared: `MDC.put` is thread-local, `String.format(..).replace(..)` is a value.
    Set<String> NOT_SHARED_ROOTS = Set.of("MDC", "String");
    Set<String> LITERALS = Set.of("new", "true", "false", "null");

    /// First shared write reachable from a callback argument (`x -> ...`, `() -> ...`, `this::name`, `Type::name`), or none.
    static Option<Write> inCallback(String argument, Declarations.FileFacts facts, String masked) {
        var lambda = LAMBDA.matcher(argument);

        if (lambda.matches()) {
            var params = lambda.group(1) != null
                         ? lambda.group(1)
                         : lambda.group(2);

            return inBody(Set.copyOf(SourceText.declaredNames(params)),
                          lambda.group(3),
                          facts,
                          masked,
                          MAX_DEPTH);
        }

        var reference = METHOD_REF.matcher(argument);

        return reference.matches()
               ? viaMethod(reference.group(1), facts, masked, MAX_DEPTH, List.of())
               : none();
    }

    private static Option<Write> inBody(Set<String> params,
                                        String body,
                                        Declarations.FileFacts facts,
                                        String masked,
                                        int depth) {
        var locals = new HashSet<String>(params);

        locals.addAll(declaredLocals(body));

        return direct(locals, body).orElse(() -> delegated(locals, body, facts, masked, depth));
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

            if (matcher.group(1) != null || !locals.contains(root)) {
                return some(Write.write("assigns " + target(matcher.group(1), matcher.group(2)),
                                        List.of()));
            }
        }

        return none();
    }

    private static Option<Write> prefixStep(Set<String> locals, String body) {
        var matcher = PREFIX.matcher(body);

        while (matcher.find()) {
            if (!locals.contains(root(matcher.group(2)))) {
                return some(Write.write("updates " + matcher.group(2).replaceAll("\\s+", ""),
                                        List.of()));
            }
        }

        return none();
    }

    private static Option<Write> mutatingCall(Set<String> locals, String body) {
        var matcher = QUALIFIED_CALL.matcher(body);

        while (matcher.find()) {
            var qualifier = matcher.group(1);
            var root = root(qualifier);

            if (!locals.contains(root) && !NOT_SHARED_ROOTS.contains(root)) {
                return some(Write.write("calls " + compact(matcher.group()) + "...)", List.of()));
            }
        }

        return none();
    }

    private static Option<Write> setterCall(String body) {
        var matcher = SETTER_CALL.matcher(body);

        return matcher.find()
               ? some(Write.write("calls " + matcher.group(1) + "(...)", List.of()))
               : none();
    }

    /// Follows plain calls into methods of this file; the first write found there is reported with the call path.
    ///
    /// A callee's parameter is local to it only when the call passes it a value the caller owns (a lambda parameter or local, a
    /// literal, a fresh `new`). A parameter bound to a field or a captured variable is that shared state under another name, so
    /// `releaseAfterBoth(remaining, gate)` writes shared state when its body decrements `remaining`.
    private static Option<Write> delegated(Set<String> locals,
                                           String body,
                                           Declarations.FileFacts facts,
                                           String masked,
                                           int depth) {
        if (depth <= 0) {
            return none();
        }

        var matcher = PLAIN_CALL.matcher(body);

        while (matcher.find()) {
            var found = isConstruction(body, matcher.start())
                        ? Option.<Write> none()
                        : viaMethod(matcher.group(1),
                                    facts,
                                    masked,
                                    depth,
                                    sharedArguments(locals, body, matcher.end() - 1));

            if (found.isPresent()) {
                return found;
            }
        }

        return none();
    }

    /// `new Foo(...)`: a constructor writes the object under construction, not state the callback shares.
    private static boolean isConstruction(String body, int callStart) {
        return NEW_BEFORE.matcher(body.substring(0, callStart)).find();
    }

    /// For each argument of the call whose `(` is at `open`: whether it is shared (rooted in a name the caller does not own).
    private static List<Boolean> sharedArguments(Set<String> locals, String body, int open) {
        var close = SourceText.closeOf(body, open);

        if (close < 0) {
            return List.of();
        }

        var shared = new ArrayList<Boolean>();

        for (var argument : SourceText.splitTopLevel(body.substring(open + 1, close))) {
            shared.add(isShared(locals, argument));
        }

        return shared;
    }

    private static boolean isShared(Set<String> locals, String argument) {
        var text = argument.strip();
        var matcher = ARGUMENT_ROOT.matcher(text);

        return matcher.lookingAt()
               && !LITERALS.contains(matcher.group(1))
               && !locals.contains(matcher.group(1));
    }

    private static Option<Write> viaMethod(String name,
                                           Declarations.FileFacts facts,
                                           String masked,
                                           int depth,
                                           List<Boolean> sharedArguments) {
        var overloads = facts.methods().getOrDefault(name, List.of());

        for (var method : overloads) {
            var found = inBody(localParams(method, sharedArguments),
                               masked.substring(method.bodyStart(), method.bodyEnd()),
                               facts,
                               masked,
                               depth - 1);

            if (found.isPresent()) {
                return found.map(write -> prepend(name, write));
            }
        }

        return none();
    }

    /// The callee's parameters that are local to it: all of them unless the call's arity matches and binds a parameter to shared state.
    private static Set<String> localParams(Declarations.MethodDecl method, List<Boolean> sharedArguments) {
        var params = method.params();

        if (params.size() != sharedArguments.size()) {
            return Set.copyOf(params);
        }

        var local = new HashSet<String>();

        for (var i = 0; i < params.size(); i++) {
            if (!sharedArguments.get(i)) {
                local.add(params.get(i));
            }
        }

        return local;
    }

    private static Write prepend(String method, Write write) {
        var via = new ArrayList<String>();

        via.add(method);
        via.addAll(write.via());

        return Write.write(write.description(), List.copyOf(via));
    }

    private static Set<String> declaredLocals(String body) {
        var names = new HashSet<String>();

        collect(LOCAL_DECL, body, names, 1);
        collect(LAMBDA_PARAM, body, names, 1);
        var multi = LAMBDA_PARAMS.matcher(body);

        while (multi.find()) {
            names.addAll(SourceText.declaredNames(multi.group(1)));
        }

        return names;
    }

    private static void collect(Pattern pattern, String text, Set<String> names, int group) {
        var matcher = pattern.matcher(text);

        while (matcher.find()) {
            names.add(matcher.group(group));
        }
    }

    private static String root(String path) {
        var stripped = path.strip();
        var end = 0;

        while (end < stripped.length() && SourceText.isIdentChar(stripped.charAt(end))) {
            end++;
        }

        return stripped.substring(0, end);
    }

    private static String target(String qualifier, String path) {
        return (qualifier == null
                ? ""
                : "this.") + path.replaceAll("\\s+", "");
    }

    private static String compact(String call) {
        return call.replaceAll("\\s+", "");
    }
}

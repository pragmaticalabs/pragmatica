package org.pragmatica.jbct.lint.cst.rules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// What one file declares, for JBCT-ASYNC-01: whether each field, parameter, local and lambda parameter is a `Promise`, a holder
/// of promises, or anything else, and which methods are declared to return `Promise`.
///
/// A name is resolved **by position**, not by file: the same identifier is routinely a `Promise` parameter in one method and an
/// unrelated `var` in the next, so a use takes the nearest preceding declaration inside its innermost enclosing method (then the
/// enclosing methods outward), and only then a field. A name with no declaration in the file is simply unknown.
///
/// **Holders.** A `Supplier<Promise<T>>`, `Fn1<Promise<T>, A>`, `Function<A, Promise<B>>`, `Map<K, Promise<V>>`,
/// `List<Promise<T>>`, `Option<Promise<T>>` or `AtomicReference<Promise<T>>` is not a promise but returns one from its accessors
/// (`get`, `apply`, `compute`, `or`, ...); see [#HOLDER_ACCESSORS].
sealed interface Declarations permits Declarations.unused {
    record unused() implements Declarations {}

    /// What a declared name holds.
    enum Slot {
        PROMISE,
        HOLDER,
        OTHER
    }

    /// A declaration of a name at an offset.
    record Decl(int offset, Slot slot) {
        static Decl decl(int offset, Slot slot) {
            return new Decl(offset, slot);
        }
    }

    /// A scope that opens names: a method (with or without a body) or a record. Holds the simple name, the declared return type's
    /// simple name (`record` for a record), parameter or component names in order, and the spans of its header (name through
    /// closing brace) and body (inside the braces; empty for a method without one).
    record MethodDecl(String name,
                      String returnType,
                      List<String> params,
                      int headerStart,
                      int bodyStart,
                      int bodyEnd) {
        static MethodDecl methodDecl(String name,
                                     String returnType,
                                     List<String> params,
                                     int headerStart,
                                     int bodyStart,
                                     int bodyEnd) {
            return new MethodDecl(name, returnType, params, headerStart, bodyStart, bodyEnd);
        }

        boolean covers(int offset) {
            return headerStart <= offset && offset < bodyEnd;
        }

        int size() {
            return bodyEnd - headerStart;
        }
    }

    /// Names, promise-returning methods, method bodies and scopes of one file.
    record FileFacts(Map<String, List<Decl>> names,
                     Set<String> promiseMethods,
                     Map<String, List<MethodDecl>> methods,
                     List<MethodDecl> scopes,
                     Map<Integer, Option<MethodDecl>> innermostCache) {
        static FileFacts fileFacts(Map<String, List<Decl>> names,
                                   Set<String> promiseMethods,
                                   Map<String, List<MethodDecl>> methods) {
            var scopes = new ArrayList<MethodDecl>();

            methods.values().forEach(scopes::addAll);

            return new FileFacts(names, promiseMethods, methods, scopes, new HashMap<>());
        }

        /// The innermost method whose header or body covers `offset`.
        Option<MethodDecl> innermostAt(int offset) {
            return innermostCache.computeIfAbsent(offset, this::findInnermost);
        }

        private Option<MethodDecl> findInnermost(int offset) {
            return scopes.stream()
                         .filter(method -> method.covers(offset))
                         .min(Comparator.comparingInt(MethodDecl::size))
                         .map(Option::some)
                         .orElseGet(Option::none);
        }

        /// What `name` holds at `offset`: the nearest earlier declaration within the innermost enclosing method, then each
        /// enclosing method outward, then fields (declarations outside every method) when they all agree; empty when unknown.
        Option<Slot> slotAt(String name, int offset) {
            var declared = names.getOrDefault(name, List.of());

            if (declared.isEmpty()) {
                return none();
            }

            var enclosing = scopes.stream()
                                  .filter(method -> method.covers(offset))
                                  .sorted(Comparator.comparingInt(MethodDecl::size))
                                  .toList();

            for (var method : enclosing) {
                var local = declared.stream()
                                    .filter(decl -> decl.offset() < offset && innermostAt(decl.offset()).filter(method::equals)
                                                                                         .isPresent())
                                    .reduce((_, later) -> later);

                if (local.isPresent()) {
                    return some(local.get().slot());
                }
            }

            return fieldSlot(declared);
        }

        private Option<Slot> fieldSlot(List<Decl> declared) {
            var slots = declared.stream()
                                .filter(decl -> innermostAt(decl.offset()).isEmpty())
                                .map(Decl::slot)
                                .distinct()
                                .toList();

            return slots.size() == 1
                   ? some(slots.getFirst())
                   : none();
        }
    }

    /// A declaration found while scanning, before it gets a slot (a `var` takes the carrier of its initializer).
    record Pending(int offset, String name, Option<Slot> slot, Option<String> initializer) {
        static Pending pending(int offset, String name, Option<Slot> slot, Option<String> initializer) {
            return new Pending(offset, name, slot, initializer);
        }
    }

    /// Accessors through which a holder yields the promise it holds.
    Set<String> HOLDER_ACCESSORS = Set.of("get",
                                          "apply",
                                          "call",
                                          "getFirst",
                                          "getLast",
                                          "peek",
                                          "poll",
                                          "pop",
                                          "take",
                                          "remove",
                                          "compute",
                                          "computeIfAbsent",
                                          "computeIfPresent",
                                          "getOrDefault",
                                          "putIfAbsent",
                                          "merge",
                                          "or",
                                          "orElse",
                                          "orElseGet",
                                          "unwrap",
                                          "getAndSet",
                                          "updateAndGet",
                                          "getAndUpdate",
                                          "accumulateAndGet",
                                          "getPlain",
                                          "getAcquire",
                                          "getOpaque");

    /// Types whose promise is the first type argument (`Supplier<Promise<T>>`, `Fn1<Promise<T>, A>`, `List<Promise<T>>`).
    Set<String> HOLDS_FIRST = Set.of("Supplier",
                                     "Callable",
                                     "Fn0",
                                     "Fn1",
                                     "Fn2",
                                     "Fn3",
                                     "Fn4",
                                     "Fn5",
                                     "Option",
                                     "Optional",
                                     "AtomicReference",
                                     "ThreadLocal",
                                     "List",
                                     "ArrayList",
                                     "LinkedList",
                                     "Set",
                                     "Deque",
                                     "Queue",
                                     "ArrayDeque",
                                     "Collection",
                                     "CopyOnWriteArrayList");

    /// Types whose promise is the last type argument (`Function<A, Promise<B>>`, `Map<K, Promise<V>>`).
    Set<String> HOLDS_LAST = Set.of("Function",
                                    "BiFunction",
                                    "Map",
                                    "HashMap",
                                    "ConcurrentHashMap",
                                    "ConcurrentMap",
                                    "LinkedHashMap",
                                    "TreeMap",
                                    "ConcurrentSkipListMap",
                                    "EnumMap");

    String IDENT = SourceText.IDENT;
    Pattern DECLARED = Pattern.compile("(?<![.\\w$])((?:" + IDENT + "\\.)*)(" + IDENT + ")\\s*(<)?");
    Pattern DECLARED_NAME = Pattern.compile("\\s*(?:\\[\\]\\s*)*(" + IDENT + ")\\s*(\\(|=|;|,|\\)|:)");
    Pattern VAR_DECL = Pattern.compile("\\bvar\\s+(" + IDENT + ")\\s*=");
    Pattern METHOD_HEAD = Pattern.compile("(?<![\\w$.])(" + IDENT + ")\\s*\\(");
    Pattern RECORD_HEAD = Pattern.compile("\\brecord\\s+" + IDENT + "\\s*(?:<[^>(){};]*>\\s*)?\\(");
    Pattern THROWS = Pattern.compile("\\s*throws\\s+[\\w.,\\s<>]+?\\s*\\{");
    Pattern THROWS_OR_BLANK = Pattern.compile("\\s*(?:throws\\s+[\\w.,\\s<>]+)?");
    Pattern LAMBDA_PARAM = Pattern.compile("(?<![\\w$.])(" + IDENT + ")\\s*->");
    Pattern LAMBDA_PARAMS = Pattern.compile("\\(([^()]*)\\)\\s*->");
    Pattern PROMISE_FACTORY_BEFORE = Pattern.compile("\\bPromise\\s*\\.\\s*(?:<[^>]*>\\s*)?promise\\s*\\(\\s*$");

    Set<String> NOT_A_TYPE = Set.of("return",
                                    "new",
                                    "else",
                                    "case",
                                    "throw",
                                    "import",
                                    "package",
                                    "instanceof",
                                    "extends",
                                    "implements",
                                    "throws",
                                    "yield",
                                    "assert",
                                    "var");

    Set<String> CONTROL = Set.of("if",
                                 "for",
                                 "while",
                                 "switch",
                                 "catch",
                                 "synchronized",
                                 "try",
                                 "do",
                                 "return",
                                 "new",
                                 "else",
                                 "throw",
                                 "super",
                                 "this");

    static FileFacts facts(String masked) {
        var methods = methodTable(masked);
        var promiseMethods = new HashSet<String>();
        var otherMethods = new HashSet<String>();
        var pending = new ArrayList<Pending>();
        var records = recordHeaders(masked);

        scanTyped(masked, records, pending, promiseMethods, otherMethods);
        scanLambdaParams(masked, pending);
        scanVars(masked, pending);
        promiseMethods.removeAll(otherMethods);
        var names = new HashMap<String, List<Decl>>();
        var facts = FileFacts.fileFacts(names, promiseMethods, methods);

        pending.stream().sorted(Comparator.comparingInt(Pending::offset)).forEach(entry -> resolve(entry, facts, names));

        return facts;
    }

    private static void resolve(Pending entry, FileFacts facts, Map<String, List<Decl>> names) {
        var slot = entry.slot().or(() -> slotOfInitializer(entry, facts));

        names.computeIfAbsent(entry.name(), _ -> new ArrayList<>()).add(Decl.decl(entry.offset(), slot));
    }

    private static Slot slotOfInitializer(Pending entry, FileFacts facts) {
        return PromiseCarrier.isPromise(entry.initializer().or(""),
                                        facts,
                                        entry.offset())
               ? Slot.PROMISE
               : Slot.OTHER;
    }

    /// `Type<Args> name` declarations: fields, parameters, locals, record components, and (when the name is followed by `(`) methods.
    private static void scanTyped(String masked,
                                  List<int[]> records,
                                  List<Pending> pending,
                                  Set<String> promiseMethods,
                                  Set<String> otherMethods) {
        var matcher = DECLARED.matcher(masked);

        while (matcher.find()) {
            var typeName = matcher.group(2);

            if (NOT_A_TYPE.contains(typeName)) {
                continue;
            }

            var end = typeEnd(masked, matcher);

            if (end < 0) {
                continue;
            }

            var named = DECLARED_NAME.matcher(masked).region(end, masked.length());

            if (!named.lookingAt() || NOT_A_TYPE.contains(named.group(1))) {
                continue;
            }

            var slot = slotOf(typeName, typeArguments(masked, matcher));
            var name = named.group(1);

            if ("(".equals(named.group(2))) {
                methodBucket(slot, promiseMethods, otherMethods).add(name);
            } else {
                pending.add(Pending.pending(named.start(1), name, some(slot), none()));
                if (insideRecordHeader(records, named.start(1))) {
                    methodBucket(slot, promiseMethods, otherMethods).add(name);
                }
            }
        }
    }

    private static Set<String> methodBucket(Slot slot, Set<String> promiseMethods, Set<String> otherMethods) {
        return slot == Slot.PROMISE
               ? promiseMethods
               : otherMethods;
    }

    private static boolean insideRecordHeader(List<int[]> records, int offset) {
        return records.stream()
                      .anyMatch(span -> span[0] <= offset && offset < span[1]);
    }

    private static List<int[]> recordHeaders(String masked) {
        var spans = new ArrayList<int[]>();
        var matcher = RECORD_HEAD.matcher(masked);

        while (matcher.find()) {
            var close = SourceText.closeOf(masked, matcher.end() - 1);

            if (close > 0) {
                spans.add(new int[]{matcher.end(), close});
            }
        }

        return spans;
    }

    /// Offset just past the type at the matcher's position, skipping a type-argument list; `-1` when the `<` opens no such list.
    private static int typeEnd(String masked, Matcher matcher) {
        if (matcher.group(3) == null) {
            return matcher.end();
        }

        var close = SourceText.closeAngle(masked, matcher.end() - 1);

        return close < 0
               ? -1
               : close + 1;
    }

    private static Option<String> typeArguments(String masked, Matcher matcher) {
        if (matcher.group(3) == null) {
            return none();
        }

        var open = matcher.end() - 1;

        return some(masked.substring(open + 1, SourceText.closeAngle(masked, open)));
    }

    private static Slot slotOf(String typeName, Option<String> typeArguments) {
        if ("Promise".equals(typeName)) {
            return Slot.PROMISE;
        }

        return typeArguments.filter(arguments -> holdsPromise(typeName, arguments))
                            .isPresent()
               ? Slot.HOLDER
               : Slot.OTHER;
    }

    private static boolean holdsPromise(String typeName, String arguments) {
        var pieces = SourceText.splitTopLevel(arguments);

        if (pieces.isEmpty()) {
            return false;
        }

        return heldArgument(typeName, pieces).strip()
                           .matches("(?s)Promise\\b.*");
    }

    private static String heldArgument(String typeName, List<String> pieces) {
        if (HOLDS_FIRST.contains(typeName)) {
            return pieces.getFirst();
        }

        return HOLDS_LAST.contains(typeName)
               ? pieces.getLast()
               : "";
    }

    private static void scanLambdaParams(String masked, List<Pending> pending) {
        var single = LAMBDA_PARAM.matcher(masked);

        while (single.find()) {
            pending.add(Pending.pending(single.start(1),
                                        single.group(1),
                                        some(lambdaSlot(masked, single.start(1))),
                                        none()));
        }

        var several = LAMBDA_PARAMS.matcher(masked);

        while (several.find()) {
            for (var name : SourceText.declaredNames(several.group(1))) {
                pending.add(Pending.pending(several.start(1), name, some(Slot.OTHER), none()));
            }
        }
    }

    /// The parameter of a lambda handed straight to `Promise.promise(...)` is the promise being created.
    private static Slot lambdaSlot(String masked, int paramStart) {
        var before = masked.substring(Math.max(0, paramStart - 60), paramStart);

        return PROMISE_FACTORY_BEFORE.matcher(before).find()
               ? Slot.PROMISE
               : Slot.OTHER;
    }

    private static void scanVars(String masked, List<Pending> pending) {
        var matcher = VAR_DECL.matcher(masked);

        while (matcher.find()) {
            var end = SourceText.statementEnd(masked, matcher.end());

            if (end > 0) {
                pending.add(Pending.pending(matcher.start(1),
                                            matcher.group(1),
                                            none(),
                                            some(masked.substring(matcher.end(), end))));
            }
        }
    }

    /// Methods declared in this file, keyed by simple name (overloads share a key), with or without a body. A record header
    /// (`record Attempt(Promise<T> promise) {...}`) has the same shape and is kept on purpose: it is the one scope in which its
    /// components are visible. It is never called by name, since `new Attempt(...)` is a construction.
    private static Map<String, List<MethodDecl>> methodTable(String masked) {
        var table = new HashMap<String, List<MethodDecl>>();
        var matcher = METHOD_HEAD.matcher(masked);

        while (matcher.find()) {
            var name = matcher.group(1);

            if (CONTROL.contains(name)) {
                continue;
            }

            var open = matcher.end() - 1;
            var close = SourceText.closeOf(masked, open);
            var returnType = returnTypeBefore(masked, matcher.start());

            if (close < 0 || returnType.isEmpty()) {
                continue;
            }

            var bodyOpen = bodyOpenAfter(masked, close + 1);
            var bodyClose = bodyOpen < 0
                            ? -1
                            : SourceText.closeOf(masked, bodyOpen);
            var params = SourceText.declaredNames(masked.substring(open + 1, close));

            if (bodyClose > 0) {
                add(table,
                    MethodDecl.methodDecl(name, returnType, params, matcher.start(1), bodyOpen + 1, bodyClose));
            } else if (endsWithSemicolon(masked, close + 1)) {
                add(table,
                    MethodDecl.methodDecl(name, returnType, params, matcher.start(1), close + 1, close + 1));
            }
        }

        return table;
    }

    private static void add(Map<String, List<MethodDecl>> table, MethodDecl method) {
        table.computeIfAbsent(method.name(), _ -> new ArrayList<>()).add(method);
    }

    /// A method declared without a body (interface or abstract): `)` then optional `throws ...` then `;`.
    private static boolean endsWithSemicolon(String masked, int from) {
        var end = masked.indexOf(';', from);

        return end > 0 && THROWS_OR_BLANK.matcher(masked.substring(from, end)).matches();
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
               : -1;
    }

    /// Simple name of the type that precedes a method name (`Promise<Unit> run(` gives `Promise`), or empty when the token before
    /// the name is not a type — a call statement, a `new`, an operator.
    private static String returnTypeBefore(String masked, int nameStart) {
        var end = nameStart - 1;

        while (end >= 0 && Character.isWhitespace(masked.charAt(end))) {
            end--;
        }

        if (end >= 0 && masked.charAt(end) == '>') {
            end = SourceText.openAngle(masked, end) - 1;
            while (end >= 0 && Character.isWhitespace(masked.charAt(end))) {
                end--;
            }
        }

        while (end >= 1 && masked.charAt(end) == ']' && masked.charAt(end - 1) == '[') {
            end -= 2;
        }

        var start = end;

        while (start >= 0 && SourceText.isIdentChar(masked.charAt(start))) {
            start--;
        }

        var token = start == end
                    ? ""
                    : masked.substring(start + 1, end + 1);

        return NOT_A_TYPE.contains(token)
               ? ""
               : token;
    }
}

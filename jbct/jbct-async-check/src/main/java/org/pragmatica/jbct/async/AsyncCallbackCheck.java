package org.pragmatica.jbct.async;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

/// JBCT-ASYNC-01, type-attributed: a `Promise` callback (`onSuccess`/`onFailure`/`onResult` and their `*Run`/`*Async`
/// forms) that writes shared state.
///
/// `Promise.on*` callbacks are events: each runs on its own virtual thread, concurrently with the promise's dependents
/// (the awaiting caller, the next `map`/`flatMap`) and in no order relative to them, so a flag, map or counter written
/// there can still be unwritten when the code that follows reads it. `withSuccess`/`withFailure`/`withResult` and a plain
/// chain step run in chain order, before any dependent sees the value. `Result.on*` runs inline and is never looked at:
/// the receiver is identified by the resolved method symbol (owner `org.pragmatica.lang.Promise`), not by its spelling.
///
/// The check runs after attribution, once per analysed top-level class, so receiver types, field/local distinction and
/// method symbols are exact, across files and across modules.
///
/// **A write** is, inside the callback lambda: an assignment, compound assignment or `++`/`--` to a field (or to an element
/// of a field array), or a call to a mutating verb (`put`, `add`, `remove`, `clear`, `set`, `incrementAndGet`,
/// `compareAndSet`, ...) on a receiver rooted in a field, a captured local, a static or `this`. **One level of delegation**
/// is followed: a method reference, or a call inside the lambda, to a method of an enclosing class whose source is in this
/// compilation; the same writes inside it count, and a parameter of it is shared when the call passes it a field or a captured
/// local. Logging, scheduling and resolving another promise are not writes.
///
/// **Suppression with a reason:** a comment `// detached: <reason>` on the statement or directly above it (a bare
/// `detached` does not suppress), or `@SuppressWarnings("JBCT-ASYNC-01")` on an enclosing declaration. `@Contract` does not suppress: it marks a
/// signature dictated by an external contract, and says nothing about when a callback's effect becomes visible.
///
/// **Limits.** A write made by a method that has no source here (a jar), by a second level of calls, or through a lambda held
/// in a variable is not seen. Verbs are matched by name on the receiver's resolved type, so a non-mutating method that shares
/// a catalog name on a shared receiver is a false positive.
public final class AsyncCallbackCheck implements TaskListener {
    static final String RULE_ID = "JBCT-ASYNC-01";

    private static final String PROMISE = "org.pragmatica.lang.Promise";
    private static final Set<String> CALLBACKS = Set.of("onSuccess", "onFailure", "onResult", "onSuccessRun", "onFailureRun",
                                                        "onResultRun", "onSuccessAsync", "onFailureAsync", "onResultAsync",
                                                        "onSuccessRunAsync", "onFailureRunAsync", "onResultRunAsync");
    private static final Set<String> VERBS = Set.of("put", "putAll", "putIfAbsent", "add", "addAll", "addFirst", "addLast",
                                                    "remove", "removeIf", "removeAll", "retainAll", "clear", "set", "lazySet",
                                                    "getAndSet", "incrementAndGet", "getAndIncrement", "decrementAndGet",
                                                    "getAndDecrement", "addAndGet", "getAndAdd", "compareAndSet",
                                                    "compareAndExchange", "weakCompareAndSet", "weakCompareAndSetPlain",
                                                    "weakCompareAndSetVolatile", "updateAndGet", "getAndUpdate",
                                                    "accumulateAndGet", "getAndAccumulate", "compute", "computeIfAbsent",
                                                    "computeIfPresent", "merge", "replace", "replaceAll", "offer", "offerFirst",
                                                    "offerLast", "push", "pop", "poll", "pollFirst", "pollLast", "append",
                                                    "increment", "decrement", "reset", "setRelease", "setPlain", "setVolatile",
                                                    "setOpaque", "accumulate");
    /// Receiver types whose mutating-looking methods are not writes to shared state of the callback.
    private static final Set<String> NOT_STATE = Set.of(PROMISE,
                                                        "org.pragmatica.lang.Result",
                                                        "org.pragmatica.lang.Option",
                                                        "java.util.Optional",
                                                        "java.lang.String",
                                                        "java.util.stream.Stream",
                                                        "org.slf4j.MDC");
    private static final Pattern DETACHED = Pattern.compile("(//|/\\*|\\*).*\\bdetached\\s*[:\\-–—]\\s*\\S.{2,}",
                                                            Pattern.CASE_INSENSITIVE);

    private static final boolean DEBUG = Boolean.getBoolean("jbct.async.debug");
    private static final int DEPTH = Integer.getInteger("jbct.async.depth", 1);
    private static final boolean VOID_CALLS = Boolean.getBoolean("jbct.async.voidcalls");
    private static final Set<String> BENIGN_OWNERS = Set.of("org.slf4j.Logger", "java.lang.System$Logger", "java.io.PrintStream",
                                                            "java.util.concurrent.ScheduledExecutorService",
                                                            "java.util.concurrent.Executor", "java.util.concurrent.ExecutorService",
                                                            "java.util.concurrent.CompletableFuture", "org.pragmatica.lang.Promise",
                                                            "org.pragmatica.lang.Result", "org.pragmatica.lang.Option",
                                                            "java.lang.Object", "org.slf4j.MDC");
    private static final boolean ANY_CLASS = Boolean.getBoolean("jbct.async.anyclass");

    private final Trees trees;
    private final Types types;
    private final Elements elements;
    private final Diagnostic.Kind severity;

    private AsyncCallbackCheck(Trees trees, Types types, Elements elements, Diagnostic.Kind severity) {
        this.trees = trees;
        this.types = types;
        this.elements = elements;
        this.severity = severity;
    }

    static AsyncCallbackCheck asyncCallbackCheck(Trees trees, Types types, Elements elements, Diagnostic.Kind severity) {
        return new AsyncCallbackCheck(trees, types, elements, severity);
    }

    @Override
    public void finished(TaskEvent event) {
        if (event.getKind() != TaskEvent.Kind.ANALYZE || event.getTypeElement() == null) {
            return;
        }

        var path = trees.getPath(event.getTypeElement());

        if (path != null) {
            new CallbackScanner().scan(path, null);
        }
    }

    /// What a callback was found to write: a description, and the delegate method it was found in, if any.
    private record Write(String description, String via) {}

    private final class CallbackScanner extends TreePathScanner<Void, Void> {
        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            if (isPromiseCallback(getCurrentPath()) && node.getArguments().size() == 1) {
                check(getCurrentPath(), node);
            }

            return super.visitMethodInvocation(node, unused);
        }
    }

    private boolean isPromiseCallback(TreePath path) {
        var element = trees.getElement(path);

        return element instanceof ExecutableElement method
               && CALLBACKS.contains(method.getSimpleName().toString())
               && method.getEnclosingElement() instanceof TypeElement owner
               && owner.getQualifiedName().contentEquals(PROMISE);
    }

    private void check(TreePath path, MethodInvocationTree call) {
        var argument = call.getArguments().getFirst();
        var argumentPath = new TreePath(path, argument);

        var write = callbackWrite(argumentPath, argument, enclosingTypes(path));

        if (DEBUG) {
            trees.printMessage(Diagnostic.Kind.NOTE,
                               "JBCT-ASYNC-DEBUG arg=" + argument.getKind() + " write=" + write.isPresent() + " calls=" + (write.isPresent() ? "" : calls(argumentPath, argument, enclosingTypes(path))),
                               call.getMethodSelect(),
                               path.getCompilationUnit());
        }

        write.filter(_ -> !isSuppressed(path)).ifPresent(found -> report(path, call, found));
    }

    /// Debug aid: the methods a write-free callback invokes, as `owner.method` (`*` marks a same-class method).
    private String calls(TreePath argumentPath, Tree argument, Set<Element> scope) {
        var names = new java.util.TreeSet<String>();

        if (argument instanceof MemberReferenceTree && trees.getElement(argumentPath) instanceof ExecutableElement target) {
            names.add(describeMethod(target, scope));
        }

        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                if (trees.getElement(getCurrentPath()) instanceof ExecutableElement method) {
                    names.add(describeMethod(method, scope));
                }

                return super.visitMethodInvocation(node, unused);
            }
        }.scan(argumentPath, null);
        return String.join(",", names);
    }

    private static String describeMethod(ExecutableElement method, Set<Element> scope) {
        return (scope.contains(method.getEnclosingElement()) ? "*" : "") + method.getEnclosingElement().getSimpleName() + "."
               + method.getSimpleName();
    }

    private Optional<Write> callbackWrite(TreePath argumentPath, Tree argument, Set<Element> scope) {
        if (argument instanceof LambdaExpressionTree lambda) {
            var finder = new WriteFinder(scope, declaredIn(argumentPath, lambda), DEPTH);

            finder.scan(new TreePath(argumentPath, lambda.getBody()), null);
            return Optional.ofNullable(finder.found);
        }

        if (argument instanceof MemberReferenceTree && trees.getElement(argumentPath) instanceof ExecutableElement target) {
            return delegated(target, List.of(), scope, DEPTH);
        }

        return Optional.empty();
    }

    private Set<Element> declaredIn(TreePath lambdaPath, LambdaExpressionTree lambda) {
        var declared = new HashSet<Element>();

        for (var parameter : lambda.getParameters()) {
            var element = trees.getElement(new TreePath(lambdaPath, parameter));

            if (element != null) {
                declared.add(element);
            }
        }

        return declared;
    }

    /// The classes enclosing the call site: only methods of these are followed as delegates.
    private Set<Element> enclosingTypes(TreePath path) {
        var enclosing = new HashSet<Element>();

        for (var current = path; current != null; current = current.getParentPath()) {
            if (current.getLeaf() instanceof ClassTree && trees.getElement(current) != null) {
                enclosing.add(trees.getElement(current));
            }
        }

        return enclosing;
    }

    private Optional<Write> delegated(ExecutableElement method, List<Boolean> sharedArguments, Set<Element> scope, int depth) {
        if (method.getKind() != ElementKind.METHOD || depth <= 0
            || !(ANY_CLASS ? isProjectOwner(method) : scope.contains(method.getEnclosingElement()))) {
            return Optional.empty();
        }

        var path = trees.getPath(method);

        if (path == null || !(path.getLeaf() instanceof MethodTree tree) || tree.getBody() == null) {
            return Optional.empty();
        }

        var declared = new HashSet<Element>();
        var parameters = tree.getParameters();

        for (var i = 0; i < parameters.size(); i++) {
            var shared = sharedArguments.size() == parameters.size() && sharedArguments.get(i);

            if (!shared) {
                declared.add(trees.getElement(new TreePath(path, parameters.get(i))));
            }
        }

        var finder = new WriteFinder(scope, declared, depth - 1);

        finder.scan(new TreePath(path, tree.getBody()), null);
        return Optional.ofNullable(finder.found).map(write -> new Write(write.description(), method.getSimpleName().toString()));
    }

    /// Scans a callback or delegate body for the first write to shared state.
    private final class WriteFinder extends TreePathScanner<Void, Void> {
        private final Set<Element> scope;
        private final Set<Element> declared;
        private final int depth;
        Write found;

        WriteFinder(Set<Element> scope, Set<Element> declared, int depth) {
            this.scope = scope;
            this.declared = declared;
            this.depth = depth;
        }

        @Override
        public Void scan(Tree tree, Void unused) {
            return found == null ? super.scan(tree, unused) : null;
        }

        @Override
        public Void visitVariable(VariableTree node, Void unused) {
            var element = trees.getElement(getCurrentPath());

            if (element != null) {
                declared.add(element);
            }

            return super.visitVariable(node, unused);
        }

        @Override
        public Void visitAssignment(AssignmentTree node, Void unused) {
            checkTarget(new TreePath(getCurrentPath(), node.getVariable()), "assigns");
            return super.visitAssignment(node, unused);
        }

        @Override
        public Void visitCompoundAssignment(CompoundAssignmentTree node, Void unused) {
            checkTarget(new TreePath(getCurrentPath(), node.getVariable()), "updates");
            return super.visitCompoundAssignment(node, unused);
        }

        @Override
        public Void visitUnary(UnaryTree node, Void unused) {
            switch (node.getKind()) {
                case POSTFIX_INCREMENT, POSTFIX_DECREMENT, PREFIX_INCREMENT, PREFIX_DECREMENT ->
                    checkTarget(new TreePath(getCurrentPath(), node.getExpression()), "updates");
                default -> {}
            }

            return super.visitUnary(node, unused);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            if (trees.getElement(getCurrentPath()) instanceof ExecutableElement method) {
                mutatingCall(getCurrentPath(), node, method);

                if (found == null && depth > 0) {
                    followDelegate(getCurrentPath(), node, method);
                }
            }

            return super.visitMethodInvocation(node, unused);
        }

        private void checkTarget(TreePath target, String verb) {
            if (found == null && isSharedTarget(target)) {
                found = new Write(verb + " " + describe(target.getLeaf()), null);
            }
        }

        private boolean isSharedTarget(TreePath target) {
            var path = strip(target);
            var element = trees.getElement(path);
            var isField = element != null && element.getKind() == ElementKind.FIELD;

            return switch (path.getLeaf()) {
                case IdentifierTree _ -> isField;
                case MemberSelectTree select -> isField && (isStatic(element) || isSharedRoot(new TreePath(path, select.getExpression())));
                case ArrayAccessTree access -> isSharedRoot(new TreePath(path, access.getExpression()));
                default -> false;
            };
        }

        private void mutatingCall(TreePath callPath, MethodInvocationTree node, ExecutableElement method) {
            if (VOID_CALLS && found == null && method.getReturnType().getKind() == TypeKind.VOID
                && node.getMethodSelect() instanceof MemberSelectTree voidSelect
                && method.getEnclosingElement() instanceof TypeElement voidOwner
                && !BENIGN_OWNERS.contains(voidOwner.getQualifiedName().toString())
                && !voidOwner.getQualifiedName().toString().startsWith("java.")
                && isSharedRoot(new TreePath(new TreePath(callPath, voidSelect), voidSelect.getExpression()))) {
                found = new Write("calls " + describe(voidSelect.getExpression()) + "." + method.getSimpleName() + "(...)", null);
                return;
            }

            if (found != null || !VERBS.contains(method.getSimpleName().toString())
                || !(node.getMethodSelect() instanceof MemberSelectTree select)) {
                return;
            }

            var selectPath = new TreePath(callPath, select);
            var receiver = new TreePath(selectPath, select.getExpression());
            var owner = method.getEnclosingElement() instanceof TypeElement type
                        ? type.getQualifiedName().toString()
                        : "";

            if (!NOT_STATE.contains(owner) && !isNotState(trees.getTypeMirror(receiver)) && isSharedRoot(receiver)) {
                found = new Write("calls " + describe(select.getExpression()) + "." + method.getSimpleName() + "(...)", null);
            }
        }

        private void followDelegate(TreePath callPath, MethodInvocationTree node, ExecutableElement method) {
            var shared = new ArrayList<Boolean>();

            node.getArguments().forEach(argument -> shared.add(isSharedRoot(new TreePath(callPath, argument))));
            delegated(method, shared, scope, depth).ifPresent(write -> found = write);
        }

        /// Whether the expression is rooted in something the callback does not own: a field, `this`, a static, or a local
        /// declared outside the callback.
        private boolean isSharedRoot(TreePath expression) {
            var current = strip(expression);

            while (true) {
                switch (current.getLeaf()) {
                    case IdentifierTree id -> {
                        return identifierIsShared(current, id);
                    }
                    case MemberSelectTree select -> {
                        var element = trees.getElement(current);

                        if (element != null && element.getKind() == ElementKind.FIELD && isStatic(element)) {
                            return true;
                        }

                        current = strip(new TreePath(current, select.getExpression()));
                    }
                    case MethodInvocationTree call -> {
                        if (!(call.getMethodSelect() instanceof MemberSelectTree select)) {
                            return true;
                        }

                        current = strip(new TreePath(new TreePath(current, select), select.getExpression()));
                    }
                    case ArrayAccessTree access -> current = strip(new TreePath(current, access.getExpression()));
                    default -> {
                        return false;
                    }
                }
            }
        }

        private boolean identifierIsShared(TreePath path, IdentifierTree id) {
            if (id.getName().contentEquals("this") || id.getName().contentEquals("super")) {
                return true;
            }

            var element = trees.getElement(path);

            return element != null && switch (element.getKind()) {
                case FIELD -> true;
                case LOCAL_VARIABLE, PARAMETER, EXCEPTION_PARAMETER, BINDING_VARIABLE, RESOURCE_VARIABLE ->
                    !declared.contains(element);
                default -> false;
            };
        }
    }

    private static boolean isProjectOwner(ExecutableElement method) {
        return method.getEnclosingElement() instanceof TypeElement owner && !NOT_STATE.contains(owner.getQualifiedName().toString());
    }

    private static boolean isStatic(Element element) {
        return element.getModifiers().contains(Modifier.STATIC);
    }

    private static TreePath strip(TreePath path) {
        var current = path;

        while (true) {
            if (current.getLeaf() instanceof ParenthesizedTree parenthesized) {
                current = new TreePath(current, parenthesized.getExpression());
            } else if (current.getLeaf() instanceof TypeCastTree cast) {
                current = new TreePath(current, cast.getExpression());
            } else {
                return current;
            }
        }
    }

    private boolean isNotState(TypeMirror type) {
        return type != null && type.getKind() == TypeKind.DECLARED && NOT_STATE.contains(types.erasure(type).toString());
    }

    private static String describe(Tree tree) {
        return tree.toString().replaceAll("\\s+", "");
    }

    private boolean isSuppressed(TreePath path) {
        return suppressedByAnnotation(path) || isDetachedByComment(path);
    }

    private boolean suppressedByAnnotation(TreePath path) {
        for (var current = path; current != null; current = current.getParentPath()) {
            var leaf = current.getLeaf();

            if ((leaf instanceof MethodTree || leaf instanceof ClassTree || leaf instanceof VariableTree)
                && trees.getElement(current) != null && suppresses(trees.getElement(current))) {
                return true;
            }
        }

        return false;
    }

    private static boolean suppresses(Element element) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            var name = mirror.getAnnotationType().asElement().getSimpleName().toString();

            if (name.equals("SuppressWarnings") && mentionsRule(mirror)) {
                return true;
            }
        }

        return false;
    }

    private static boolean mentionsRule(AnnotationMirror mirror) {
        for (AnnotationValue value : mirror.getElementValues().values()) {
            var text = value.toString();

            if (text.contains(RULE_ID) || text.contains("\"all\"")) {
                return true;
            }
        }

        return false;
    }

    /// A `// detached: <reason>` comment inside the statement that holds the call, or in the comment lines directly above it.
    private boolean isDetachedByComment(TreePath path) {
        var statement = path;

        while (statement != null && !(statement.getLeaf() instanceof StatementTree)) {
            statement = statement.getParentPath();
        }

        if (statement == null) {
            return false;
        }

        try {
            var unit = path.getCompilationUnit();
            var text = unit.getSourceFile().getCharContent(true).toString();
            var positions = trees.getSourcePositions();
            var start = (int) positions.getStartPosition(unit, statement.getLeaf());
            var end = (int) positions.getEndPosition(unit, statement.getLeaf());

            return start >= 0 && end >= start && hasDetachedTag(text, start, end);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean hasDetachedTag(String text, int start, int end) {
        var lineStart = text.lastIndexOf('\n', start - 1) + 1;
        var lines = new ArrayList<String>();

        for (var line : text.substring(lineStart, Math.min(text.length(), end)).split("\n", -1)) {
            lines.add(line);
        }

        var above = lineStart;

        while (above > 0) {
            var previousStart = text.lastIndexOf('\n', above - 2) + 1;
            var previous = text.substring(previousStart, above - 1).strip();

            if (!(previous.startsWith("//") || previous.startsWith("/*") || previous.startsWith("*"))) {
                break;
            }

            lines.add(previous);
            above = previousStart;
        }

        return lines.stream().anyMatch(line -> DETACHED.matcher(line).find());
    }

    private void report(TreePath path, MethodInvocationTree call, Write write) {
        var name = call.getMethodSelect() instanceof MemberSelectTree select ? select.getIdentifier().toString() : "on*";
        var via = write.via() == null ? "" : " (via " + write.via() + ")";

        trees.printMessage(severity,
                           RULE_ID + ": Promise." + name + " callback " + write.description() + via
                           + " but runs on its own thread, concurrently with the promise's dependents: use"
                           + " withSuccess/withFailure/withResult (or a chain step), or record a deliberately detached effect"
                           + " with '// detached: <reason>'",
                           call.getMethodSelect(),
                           path.getCompilationUnit());
    }
}

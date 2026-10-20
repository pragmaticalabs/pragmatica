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
import javax.tools.Diagnostic;

import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ExpressionStatementTree;
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

/// JBCT-ASYNC-01, type-attributed: every `Promise` callback must be justified.
///
/// `Promise.onSuccess/onFailure/onResult` and their `*Run`/`*Async` forms are events: each runs on its own virtual thread,
/// concurrently with the promise's dependents (the awaiting caller, the next `map`/`flatMap`) and in no order relative to them.
/// Any effect a callback has, other than the three that nothing downstream can order against, may therefore land after the code
/// that follows the promise has already looked. `withSuccess`/`withFailure`/`withResult` and a plain chain step run in chain
/// order, before any dependent sees the value. `Result.on*` runs inline and is never looked at: the receiver is identified by
/// the resolved method symbol (owner `org.pragmatica.lang.Promise`), not by its spelling, after attribution, so it is exact and
/// cross-file.
///
/// **The rule is about intent, not about visible writes.** Most racy callbacks write through a collaborator in another file, so
/// no rule that looks for writes can guard the class. Instead a callback is accepted when its effects are only:
/// - logging (SLF4J, `System.Logger`, `java.util.logging`, `System.out/err`, `MDC`);
/// - scheduling (`schedule*` methods, executors);
/// - resolving another promise (`succeed`, `fail`, `resolve`, `cancel` on a `Promise`) or completing a future;
/// and everything else is reported: an assignment or `++` to a field or captured variable, a call to a mutating verb on a
/// shared receiver, a `void` call (or a call whose result is discarded) that is not one of the above, a callback that is not a
/// lambda or method reference. A call to a method of an enclosing class is followed (three levels): the call is as clean as its
/// body. Reads (value-returning calls whose result is used, comparisons, the arguments of a logging call) are not effects.
///
/// **Justification:** a comment `// detached: <why it may run late>` on the statement or directly above it (a bare `detached`
/// does not count), or `@SuppressWarnings("JBCT-ASYNC-01")` on an enclosing declaration. `@Contract` does not suppress.
///
/// **Limits.** A `void` method without source here (a jar, another module) is an effect by assumption. A value-returning call
/// whose result is used is assumed to be a read, so an effect hidden behind one is not seen.
public final class AsyncCallbackCheck implements TaskListener {
    static final String RULE_ID = "JBCT-ASYNC-01";

    private static final String PROMISE = "org.pragmatica.lang.Promise";
    private static final int MAX_DEPTH = 3;
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
    /// `Result`/`Option` combinators run their function argument inline; the argument is scanned in its own right.
    private static final Set<String> COMBINATOR_OWNERS = Set.of("org.pragmatica.lang.Result", "org.pragmatica.lang.Option");
    private static final Set<String> RESOLVERS = Set.of("succeed", "fail", "resolve", "cancel");
    private static final Set<String> LOGGER_OWNERS = Set.of("org.slf4j.Logger", "org.slf4j.spi.LoggingEventBuilder",
                                                            "java.lang.System.Logger", "java.util.logging.Logger",
                                                            "java.io.PrintStream", "org.slf4j.MDC");
    private static final Set<String> EXECUTOR_OWNERS = Set.of("java.util.concurrent.ScheduledExecutorService",
                                                              "java.util.concurrent.Executor",
                                                              "java.util.concurrent.ExecutorService",
                                                              "java.util.concurrent.CompletableFuture");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");

    /// The comment opener must come right before `detached`, so a string literal or "not detached: ..." does not count.
    private static final Pattern DETACHED = Pattern.compile("(?://|/\\*+|^\\s*\\*+)\\s*detached\\s*[:\\-\u2013\u2014]\\s*\\S.{2,}",
                                                            Pattern.CASE_INSENSITIVE);

    private final Trees trees;
    private final Diagnostic.Kind severity;

    private AsyncCallbackCheck(Trees trees, Diagnostic.Kind severity) {
        this.trees = trees;
        this.severity = severity;
    }

    static AsyncCallbackCheck asyncCallbackCheck(Trees trees, Diagnostic.Kind severity) {
        return new AsyncCallbackCheck(trees, severity);
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

    /// An effect found in a callback: a description, and the delegate method it was found in, if any.
    private record Effect(String description, String via) {}

    private final class CallbackScanner extends TreePathScanner<Void, Void> {
        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            if (isPromiseCallback(getCurrentPath())) {
                check(getCurrentPath(), node);
            }

            return super.visitMethodInvocation(node, unused);
        }
    }

    private boolean isPromiseCallback(TreePath path) {
        return trees.getElement(path) instanceof ExecutableElement method
               && CALLBACKS.contains(method.getSimpleName().toString())
               && method.getEnclosingElement() instanceof TypeElement owner
               && owner.getQualifiedName().contentEquals(PROMISE);
    }

    private void check(TreePath path, MethodInvocationTree call) {
        var argumentPath = new TreePath(path, call.getArguments().getFirst());

        effectOf(argumentPath, enclosingTypes(path)).or(() -> siblingEffect(path, call))
                                                    .filter(_ -> !isSuppressed(path))
                                                    .ifPresent(effect -> report(path, call, effect));
    }

    /// A callback that is itself allowlisted is still reported when another callback of the same promise chain has an effect:
    /// the chain's callbacks run on their own threads, unordered with the dependents of the promise, so a log line or a
    /// resolved promise that depends on that effect has no guaranteed order either. One justification on the statement covers the
    /// whole chain.
    private Optional<Effect> siblingEffect(TreePath path, MethodInvocationTree call) {
        var outermost = path;

        while (outermost.getParentPath().getLeaf() instanceof MemberSelectTree select
               && select.getExpression() == outermost.getLeaf()
               && outermost.getParentPath().getParentPath().getLeaf() instanceof MethodInvocationTree) {
            outermost = outermost.getParentPath().getParentPath();
        }

        var scope = enclosingTypes(path);

        for (var current = outermost; current.getLeaf() instanceof MethodInvocationTree invocation; ) {
            if (isPromiseCallback(current) && invocation.getArguments().size() == 1) {
                var sibling = effectOf(new TreePath(current, invocation.getArguments().getFirst()), scope);

                if (sibling.isPresent()) {
                    return Optional.of(new Effect("is allowlisted, but the " + callbackName(invocation) + " callback of the same chain "
                                                  + sibling.get().description(), null));
                }
            }

            if (!(invocation.getMethodSelect() instanceof MemberSelectTree select
                  && select.getExpression() instanceof MethodInvocationTree)) {
                break;
            }

            current = new TreePath(new TreePath(current, select), select.getExpression());
        }

        return Optional.empty();
    }

    private static String callbackName(MethodInvocationTree invocation) {
        return invocation.getMethodSelect() instanceof MemberSelectTree select ? select.getIdentifier().toString() : "on*";
    }

    private Optional<Effect> effectOf(TreePath argumentPath, Set<Element> scope) {
        var argument = argumentPath.getLeaf();

        if (argument instanceof LambdaExpressionTree lambda) {
            var finder = new EffectFinder(scope, declaredIn(argumentPath, lambda), MAX_DEPTH, lambda.getBody());

            finder.scan(new TreePath(argumentPath, lambda.getBody()), null);
            return Optional.ofNullable(finder.found);
        }

        if (argument instanceof MemberReferenceTree && trees.getElement(argumentPath) instanceof ExecutableElement target) {
            return callEffect(target, List.of(), scope, MAX_DEPTH, true);
        }

        return isForwardedParameter(argumentPath)
               ? Optional.empty()
               : Optional.of(new Effect("is not a lambda or method reference, so its effect is unknown", null));
    }

    /// A callback handed on unchanged from a parameter of the enclosing method: the caller of that method supplies it, and is
    /// checked where it builds it.
    private boolean isForwardedParameter(TreePath argumentPath) {
        return argumentPath.getLeaf() instanceof IdentifierTree && trees.getElement(argumentPath) instanceof Element element
               && element.getKind() == ElementKind.PARAMETER;
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

    /// The effect of calling `method` where the callback cannot see its body to be clean: none for an allowlisted call or a
    /// delegate whose body is clean, the call itself for a method without source that returns nothing (or whose result is
    /// discarded).
    private Optional<Effect> callEffect(ExecutableElement method,
                                        List<Boolean> sharedArguments,
                                        Set<Element> scope,
                                        int depth,
                                        boolean resultDiscarded) {
        if (isAllowlisted(method)) {
            return Optional.empty();
        }

        var delegate = delegateBody(method, scope, depth);

        if (delegate.isPresent()) {
            return delegateEffect(method, delegate.get(), sharedArguments, scope, depth);
        }

        var voidLike = resultDiscarded || method.getReturnType().getKind() == TypeKind.VOID
                       || method.getReturnType().toString().equals("org.pragmatica.lang.Unit");

        return voidLike
               ? Optional.of(new Effect("calls " + ownerName(method) + "." + method.getSimpleName() + "(...)", null))
               : Optional.empty();
    }

    private Optional<TreePath> delegateBody(ExecutableElement method, Set<Element> scope, int depth) {
        if (method.getKind() != ElementKind.METHOD || depth <= 0 || !scope.contains(method.getEnclosingElement())) {
            return Optional.empty();
        }

        var path = trees.getPath(method);

        return path != null && path.getLeaf() instanceof MethodTree tree && tree.getBody() != null
               ? Optional.of(path)
               : Optional.empty();
    }

    private Optional<Effect> delegateEffect(ExecutableElement method,
                                            TreePath methodPath,
                                            List<Boolean> sharedArguments,
                                            Set<Element> scope,
                                            int depth) {
        var tree = (MethodTree) methodPath.getLeaf();
        var declared = new HashSet<Element>();
        var parameters = tree.getParameters();

        for (var i = 0; i < parameters.size(); i++) {
            var shared = sharedArguments.size() == parameters.size() && sharedArguments.get(i);

            if (!shared) {
                declared.add(trees.getElement(new TreePath(methodPath, parameters.get(i))));
            }
        }

        var finder = new EffectFinder(scope, declared, depth - 1, null);

        finder.scan(new TreePath(methodPath, tree.getBody()), null);
        return Optional.ofNullable(finder.found).map(effect -> new Effect(effect.description(), method.getSimpleName().toString()));
    }

    private static String ownerName(ExecutableElement method) {
        return method.getEnclosingElement().getSimpleName().toString();
    }

    /// Logging, scheduling and resolving another promise: the effects the rule accepts without a justification.
    private static boolean isAllowlisted(ExecutableElement method) {
        var name = method.getSimpleName().toString();
        var owner = method.getEnclosingElement() instanceof TypeElement type ? type.getQualifiedName().toString() : "";

        return LOGGER_OWNERS.contains(owner)
               || EXECUTOR_OWNERS.contains(owner)
               || name.startsWith("schedule")
               || owner.equals(PROMISE) && RESOLVERS.contains(name);
    }

    private static boolean isCombinator(ExecutableElement method) {
        return method.getEnclosingElement() instanceof TypeElement owner && COMBINATOR_OWNERS.contains(owner.getQualifiedName().toString());
    }

    /// Scans a callback or delegate body for the first effect that is neither allowlisted nor a read.
    private final class EffectFinder extends TreePathScanner<Void, Void> {
        private final Set<Element> scope;
        private final Set<Element> declared;
        private final int depth;
        private final Tree rootExpression;
        Effect found;

        EffectFinder(Set<Element> scope, Set<Element> declared, int depth, Tree rootExpression) {
            this.scope = scope;
            this.declared = declared;
            this.depth = depth;
            this.rootExpression = rootExpression;
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
        public Void visitMemberReference(MemberReferenceTree node, Void unused) {
            if (found == null && isConsumerOfCombinator(getCurrentPath())
                && trees.getElement(getCurrentPath()) instanceof ExecutableElement target) {
                callEffect(target, List.of(), scope, depth, true).ifPresent(effect -> found = effect);
            }

            return super.visitMemberReference(node, unused);
        }

        /// `opt.onPresent(this::land)`: a reference handed to a combinator that runs it inline is a call made by the callback.
        private boolean isConsumerOfCombinator(TreePath reference) {
            return reference.getParentPath().getLeaf() instanceof MethodInvocationTree
                   && trees.getElement(reference.getParentPath()) instanceof ExecutableElement combinator
                   && combinator.getEnclosingElement() instanceof TypeElement owner
                   && (COMBINATOR_OWNERS.contains(owner.getQualifiedName().toString())
                       || owner.getQualifiedName().contentEquals("java.util.Optional"));
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            var path = getCurrentPath();

            if (!(trees.getElement(path) instanceof ExecutableElement method)) {
                return super.visitMethodInvocation(node, unused);
            }

            if (isCombinator(method)) {
                return super.visitMethodInvocation(node, unused);
            }

            if (isAllowlisted(method)) {
                return null;
            }

            classify(path, node, method);
            return found == null ? super.visitMethodInvocation(node, unused) : null;
        }

        private void classify(TreePath path, MethodInvocationTree node, ExecutableElement method) {
            if (isSharedMutation(path, node, method)) {
                found = new Effect("calls " + describe(((MemberSelectTree) node.getMethodSelect()).getExpression()) + "."
                                   + method.getSimpleName() + "(...)", null);
                return;
            }

            var shared = new ArrayList<Boolean>();

            node.getArguments().forEach(argument -> shared.add(isSharedRoot(new TreePath(path, argument))));
            if (isLocalJdkCall(path, node, method)) {
                return;
            }

            callEffect(method, shared, scope, depth, isDiscarded(path)).ifPresent(effect -> found = effect);
        }

        /// A JDK method on a receiver the callback owns (a builder, a local list) is not an effect.
        private boolean isLocalJdkCall(TreePath path, MethodInvocationTree node, ExecutableElement method) {
            return method.getEnclosingElement() instanceof TypeElement owner
                   && owner.getQualifiedName().toString().startsWith("java.")
                   && node.getMethodSelect() instanceof MemberSelectTree select
                   && !isSharedRoot(new TreePath(new TreePath(path, select), select.getExpression()));
        }

        private boolean isDiscarded(TreePath path) {
            var parent = path.getParentPath().getLeaf();

            return parent instanceof ExpressionStatementTree || path.getLeaf() == rootExpression;
        }

        private boolean isSharedMutation(TreePath path, MethodInvocationTree node, ExecutableElement method) {
            return VERBS.contains(method.getSimpleName().toString())
                   && node.getMethodSelect() instanceof MemberSelectTree select
                   && !(method.getEnclosingElement() instanceof TypeElement owner && owner.getQualifiedName().toString().startsWith("org.pragmatica.lang."))
                   && isSharedRoot(new TreePath(new TreePath(path, select), select.getExpression()));
        }

        private void checkTarget(TreePath target, String verb) {
            if (found == null && isSharedTarget(target)) {
                found = new Effect(verb + " " + describe(target.getLeaf()), null);
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
                        if (select.getIdentifier().contentEquals("this") || select.getIdentifier().contentEquals("super")) {
                            return true;
                        }

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
            var element = trees.getElement(path);

            return element != null && switch (element.getKind()) {
                case FIELD -> true;
                case LOCAL_VARIABLE, PARAMETER, EXCEPTION_PARAMETER, BINDING_VARIABLE, RESOURCE_VARIABLE ->
                    !declared.contains(element);
                default -> false;
            };
        }
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
            if (mirror.getAnnotationType().asElement().getSimpleName().contentEquals("SuppressWarnings") && mentionsRule(mirror)) {
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
        var lineEnd = text.indexOf('\n', end) < 0 ? text.length() : text.indexOf('\n', end);
        var lines = new ArrayList<String>(List.of(text.substring(lineStart, lineEnd).split("\n", -1)));
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

        return lines.stream().map(line -> STRING_LITERAL.matcher(line).replaceAll("\"\"")).anyMatch(line -> DETACHED.matcher(line).find());
    }

    private void report(TreePath path, MethodInvocationTree call, Effect effect) {
        var name = call.getMethodSelect() instanceof MemberSelectTree select ? select.getIdentifier().toString() : "on*";
        var via = effect.via() == null ? "" : " (via " + effect.via() + ")";

        trees.printMessage(severity,
                           RULE_ID + ": Promise." + name + " callback " + effect.description() + via
                           + " but runs on its own thread, concurrently with the promise's dependents: use"
                           + " withSuccess/withFailure/withResult (or a chain step), or justify a deliberately detached effect"
                           + " with '// detached: <why it may run late>'",
                           call.getMethodSelect(),
                           path.getCompilationUnit());
    }
}

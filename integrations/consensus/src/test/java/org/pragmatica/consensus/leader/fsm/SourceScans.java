/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */
package org.pragmatica.consensus.leader.fsm;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/// #3846 source scans (memo r3 §2.4 as restated by ruling R1): every scan parses sources with the javac Tree API,
/// keys each hit by its ENCLOSING METHOD (never by a line) and returns a sorted set the caller asserts EXACTLY.
///
/// - [#sinkHits]: S1. ONE scan over the three sinks of the order (`SerialExecutor.execute`, `Fsm.dispatch`,
///   `Fsm.followUp`), matched by name over every call and every member reference, whatever the receiver, so an alias
///   (`var s = serial; s.execute(r)`) and a handler-side `episode::followUp` are hits too. It over-reports on purpose:
///   it fails closed.
/// - [#lifetimeEffects]: S3. `end()`, `schedule(..)`, `scheduleAtFixedRate(..)` whose receiver is not `SharedScheduler`.
/// - [#hookDispatchers] and [#hookFollowUps]: S4a and S4b, over one file (the leader states).
///
/// Self-contained on purpose (JDK only): `java SourceScans.java <leader main dir>` prints every scan for a quick look. The
/// standing assertions are [LeaderSinkScanTest] and [LeaderOrderPathScanTest]; the greps are `scripts/inventory-3846.py`.
/// - [#drainerExits]: R1's "not claimed" rows, the calls out of the drainer into code no scan can read.
final class SourceScans {
    /// The three sinks of the order, plus the two in-leader wrappers that reach `Fsm.dispatch` (#3846, lanes C/St/F
    /// delete them one by one; their entries leave the committed sets then).
    static final Set<String> SINKS = Set.of("execute", "dispatch", "followUp", "dispatchConfined", "dispatchSelf");
    static final Set<String> LIFETIME_EFFECTS = Set.of("end", "schedule", "scheduleAtFixedRate");
    static final Set<String> HOOKS = Set.of("onEntry", "onEntered", "onExit", "onCasLost");
    static final Set<String> DISPATCHES = Set.of("dispatch", "dispatchSelf", "dispatchConfined");
    /// Calls out of the drainer into code the scan cannot read (ruling R1): the message router and the react executor.
    /// Every one is a row of the paths table marked "not claimed". The observer lives inside `Fsm` (statemachine, S2's
    /// scope; production's is `FsmObserver.noop()`), and listeners are reached through `route`.
    static final Set<String> EXITS = Set.of("route", "react");
    static final String CUT = "serialized";

    private SourceScans() {}

    /// One call or member reference found in a method body.
    record Call(String enclosing, String name, String receiver, boolean cut) {}

    /// One method (or field initializer) with the calls of its body.
    record Body(String key, String simpleName, List<Call> calls) {}

    static Map<String, String> readDir(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            var sources = new TreeMap<String, String>();
            for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                sources.put(dir.relativize(file).toString(), Files.readString(file));
            }
            return sources;
        }
    }

    /// S1. Key: `File:Enclosing > sink`, value: the number of hits in that method.
    static TreeMap<String, Integer> sinkHits(Map<String, String> sources) {
        return count(sources, SINKS::contains, call -> true);
    }

    /// R1's "not claimed" rows. Key: `File:Enclosing > route|react`, value: the number of calls in that method.
    static TreeMap<String, Integer> drainerExits(Map<String, String> sources) {
        return count(sources, EXITS::contains, call -> true);
    }

    /// S3. Key: `File:Enclosing > name(receiver)`.
    static TreeMap<String, Integer> lifetimeEffects(Map<String, String> sources) {
        return count(sources, LIFETIME_EFFECTS::contains, SourceScans::notSharedScheduler);
    }

    private static boolean notSharedScheduler(Call call) {
        return !(call.receiver().equals("SharedScheduler") || call.receiver().endsWith(".SharedScheduler"));
    }

    private static TreeMap<String, Integer> count(Map<String, String> sources,
                                                  java.util.function.Predicate<String> names,
                                                  java.util.function.Predicate<Call> filter) {
        var hits = new TreeMap<String, Integer>();
        for (var entry : new TreeMap<>(sources).entrySet()) {
            for (var body : bodies(entry.getValue())) {
                body.calls().stream()
                    .filter(call -> names.test(call.name()))
                    .filter(filter)
                    .forEach(call -> hits.merge(label(entry.getKey(), call), 1, Integer::sum));
            }
        }
        return hits;
    }

    private static String label(String file, Call call) {
        var shown = LIFETIME_EFFECTS.contains(call.name()) ? call.name() + "(" + call.receiver() + ")" : call.name();

        return file.replaceAll(".*/", "") + ":" + call.enclosing() + " > " + shown;
    }

    /// S4a. The hook methods that reach a dispatch (`dispatch`, `dispatchSelf`, `dispatchConfined`), directly or
    /// through a member of D. D is resolved by simple name within the file, conservatively. A lambda passed to
    /// `serialized(` is a cut: it is a task, a separate admission.
    static TreeSet<String> hookDispatchers(String source) {
        var bodies = bodies(source);
        var byName = byName(bodies);
        var dispatching = new HashSet<String>();
        boolean grew = true;

        while (grew) {
            grew = false;
            for (var body : bodies) {
                if (!dispatching.contains(body.key()) && reachesDispatch(body, dispatching, byName)) {
                    dispatching.add(body.key());
                    grew = true;
                }
            }
        }
        var hooks = new TreeSet<String>();
        bodies.stream().filter(b -> HOOKS.contains(b.simpleName()) && dispatching.contains(b.key()))
              .forEach(b -> hooks.add(b.key()));
        return hooks;
    }

    private static boolean reachesDispatch(Body body, Set<String> dispatching, Map<String, List<Body>> byName) {
        return body.calls().stream()
                   .filter(call -> !call.cut())
                   .anyMatch(call -> DISPATCHES.contains(call.name()) || anyDispatching(byName.get(call.name()), dispatching));
    }

    private static boolean anyDispatching(List<Body> callees, Set<String> dispatching) {
        return callees != null && callees.stream().anyMatch(b -> dispatching.contains(b.key()));
    }

    /// S4b. The `followUp` sites reachable from a hook body through the same walk. Key: `Enclosing > followUp`.
    static TreeMap<String, Integer> hookFollowUps(String source) {
        var bodies = bodies(source);
        var byName = byName(bodies);
        var visited = new HashSet<String>();
        var queue = new ArrayDeque<Body>();
        var hits = new TreeMap<String, Integer>();

        bodies.stream().filter(b -> HOOKS.contains(b.simpleName())).forEach(queue::add);
        while (!queue.isEmpty()) {
            var body = queue.poll();
            if (!visited.add(body.key() + "@" + System.identityHashCode(body))) {
                continue;
            }
            for (var call : body.calls()) {
                if (call.cut()) {
                    continue;
                }
                if (call.name().equals("followUp")) {
                    hits.merge(body.key() + " > followUp", 1, Integer::sum);
                }
                queue.addAll(byName.getOrDefault(call.name(), List.of()));
            }
        }
        return hits;
    }

    private static Map<String, List<Body>> byName(List<Body> bodies) {
        var byName = new HashMap<String, List<Body>>();

        bodies.forEach(b -> byName.computeIfAbsent(b.simpleName(), k -> new ArrayList<>()).add(b));
        return byName;
    }

    /// Every method and field initializer of one source, with the calls of its body in source order.
    static List<Body> bodies(String source) {
        var unit = parse(source);
        var bodies = new ArrayList<Body>();

        new BodyScanner(bodies).scan(unit, null);
        return bodies;
    }

    private static CompilationUnitTree parse(String source) {
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var file = new SimpleJavaFileObject(URI.create("string:///Scanned.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        try {
            var compiler = ToolProvider.getSystemJavaCompiler();
            var task = (JavacTask) compiler.getTask(null, null, diagnostics, List.of("-proc:none"), null, List.of(file));
            var units = task.parse();
            var errors = diagnostics.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR).toList();

            if (!errors.isEmpty()) {
                throw new IllegalStateException("scan input does not parse: " + errors.getFirst());
            }
            return units.iterator().next();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /// Walks classes, methods and field initializers; a lambda or an anonymous class belongs to its enclosing method.
    private static final class BodyScanner extends TreeScanner<Void, Void> {
        private final List<Body> bodies;
        private final Deque<String> classes = new ArrayDeque<>();
        private Body current;
        private int cut;

        BodyScanner(List<Body> bodies) {
            this.bodies = bodies;
        }

        @Override
        public Void visitClass(ClassTree node, Void unused) {
            var name = node.getSimpleName().isEmpty() ? "$anon" : node.getSimpleName().toString();
            var outer = current;
            var outerCut = cut;

            classes.addLast(name);
            current = null;
            cut = 0;
            super.visitClass(node, unused);
            classes.removeLast();
            current = outer;
            cut = outerCut;
            return null;
        }

        @Override
        public Void visitMethod(MethodTree node, Void unused) {
            return inBody(node.getName().toString(), node.getName().toString(), () -> super.visitMethod(node, unused));
        }

        @Override
        public Void visitVariable(VariableTree node, Void unused) {
            if (current != null || classes.isEmpty()) {
                return super.visitVariable(node, unused);
            }
            return inBody("<field:" + node.getName() + ">", node.getName().toString(), () -> super.visitVariable(node, unused));
        }

        private Void inBody(String label, String simple, Runnable walk) {
            var outer = current;
            var outerCut = cut;

            current = new Body(String.join(".", classes) + "." + label, simple, new ArrayList<>());
            cut = 0;
            walk.run();
            bodies.add(current);
            current = outer;
            cut = outerCut;
            return null;
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
            var select = node.getMethodSelect();
            var name = nameOf(select);

            if (current == null) {
                return super.visitMethodInvocation(node, unused);
            }
            current.calls().add(new Call(current.key(), name, receiverOf(select), cut > 0));
            scan(select, unused);
            return scanArguments(node, name.equals(CUT), unused);
        }

        private Void scanArguments(MethodInvocationTree node, boolean isCut, Void unused) {
            if (isCut) {
                cut++;
            }
            scan(node.getArguments(), unused);
            if (isCut) {
                cut--;
            }
            return null;
        }

        @Override
        public Void visitMemberReference(MemberReferenceTree node, Void unused) {
            if (current != null) {
                current.calls().add(new Call(current.key(), node.getName().toString(), node.getQualifierExpression().toString(), cut > 0));
            }
            return super.visitMemberReference(node, unused);
        }

        private static String nameOf(ExpressionTree select) {
            return switch (select) {
                case IdentifierTree id -> id.getName().toString();
                case MemberSelectTree member -> member.getIdentifier().toString();
                default -> select.toString();
            };
        }

        private static String receiverOf(ExpressionTree select) {
            return select instanceof MemberSelectTree member ? member.getExpression().toString() : "";
        }
    }

    /// `java SourceScans.java <dir>` prints every scan over `dir` (the leader main sources), one `scan<TAB>key<TAB>count`
    /// line per hit.
    public static void main(String[] args) throws IOException {
        var sources = readDir(Path.of(args[0]));
        var state = sources.entrySet().stream().filter(e -> e.getKey().endsWith("LeaderElectionState.java"))
                           .map(Map.Entry::getValue).findFirst().orElse("");

        sinkHits(sources).forEach((k, v) -> System.out.println("S1\t" + k + "\t" + v));
        drainerExits(sources).forEach((k, v) -> System.out.println("X\t" + k + "\t" + v));
        lifetimeEffects(sources).forEach((k, v) -> System.out.println("S3\t" + k + "\t" + v));
        hookDispatchers(state).forEach(k -> System.out.println("S4a\t" + k + "\t1"));
        hookFollowUps(state).forEach((k, v) -> System.out.println("S4b\t" + k + "\t" + v));
    }
}

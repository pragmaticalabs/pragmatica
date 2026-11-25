/*
 *  Copyright (c) 2025-2026 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.pragmatica.testing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// The source scanner behind `NestedTestScanTest`: finds a nested class that holds test methods and that nothing runs.
///
/// The surefire exclude `**/*$*` (#3688) keeps `Outer$Inner` from being scanned as a test class of its own, which is what
/// stops a `@Nested` class running twice under more than one fork. The same exclude drops a **static** nested class with
/// `@Test` methods: JUnit never discovers it from `Outer`, so it runs nowhere and nothing reports it.
///
/// One invariant: a nested test holder is legal iff something RUNS it. A type is RUN when
/// 1. it is top-level; or
/// 2. it is `@Nested` and its enclosing type is RUN (recursively up to the top-level type); or
/// 3. its own file imports `org.junit.platform.launcher.` or `org.junit.platform.testkit.` and mentions `Type.class` in code, so
///    the mention is the list that launches it (a fixture deleted from that list stops being legal); or
/// 4. it is abstract and a RUN type extends it (the subclass is what runs).
///
/// Scope: every `src/test/java` and `src/it/java` tree under a root, the trees `FlakeScan` reads. A test method is one
/// annotated `@Test`, `@ParameterizedTest`, `@RepeatedTest`, `@TestFactory` or `@TestTemplate` directly in the class body.
/// The scan reads source, not classes: comments, string literals and text blocks are blanked first, then the braces are
/// walked. A class declared inside a method body or an anonymous class is neither member nor flagged.
///
/// Known blind spots, both listed rather than hidden: a nested class that only INHERITS its test methods from a base is not
/// seen (its own body holds none), and a test-method annotation behind a custom meta-annotation is not recognised.
final class NestedTestScan {
    private static final Pattern TEST_ANNOTATION = Pattern.compile("@\\s*(?:\\w+\\s*\\.\\s*)*(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\\b");
    private static final Pattern NESTED_ANNOTATION = Pattern.compile("@\\s*(?:\\w+\\s*\\.\\s*)*Nested\\b");
    private static final Pattern ABSTRACT_MODIFIER = Pattern.compile("\\babstract\\b");
    private static final Pattern KEYWORD = Pattern.compile("\\b(class|interface|enum|record)\\s+(\\w+)");
    private static final Set<String> MODIFIERS = Set.of("public", "protected", "private", "static", "final", "abstract", "sealed", "non-sealed", "strictfp");
    private static final Pattern TYPE_ARGUMENTS = Pattern.compile("<[^<>]*>");
    private static final Pattern LAUNCHER_IMPORT = Pattern.compile("\\bimport\\s+(?:static\\s+)?org\\s*\\.\\s*junit\\s*\\.\\s*platform\\s*\\.\\s*(?:launcher|testkit)\\s*\\.");
    private static final Pattern EXTENDS = Pattern.compile("\\bextends\\s+([\\w.]+)");

    /// One type declaration. `member` is true for a type declared directly inside another type (at any depth, never through a
    /// method body); `topLevel` for a type with no enclosing scope; `parent` is the enclosing type of a member (null otherwise).
    /// `launched` is true when the file imports the JUnit launcher or test kit and mentions `Name.class` in code.
    /// `hasTests` is filled while the file is read.
    static final class Type {
        final String file;
        final String name;
        final int line;
        final String kind;
        final boolean topLevel;
        final boolean member;
        final boolean isAbstract;
        final boolean nestedAnnotated;
        final String extendsName;
        final Type parent;
        final boolean launched;
        boolean hasTests = false;

        Type(String file, String name, int line, String kind, boolean topLevel, boolean member, boolean isAbstract, boolean nestedAnnotated, String extendsName,
             Type parent, boolean launched) {
            this.file = file;
            this.name = name;
            this.line = line;
            this.kind = kind;
            this.topLevel = topLevel;
            this.member = member;
            this.isAbstract = isAbstract;
            this.nestedAnnotated = nestedAnnotated;
            this.extendsName = extendsName;
            this.parent = parent;
            this.launched = launched;
        }

        boolean isTestHolder() {
            return kind.equals("class") && member && hasTests;
        }
    }

    /// A nested test class that nothing runs, with the line of its `class` keyword.
    /// `reason` names the clause that failed.
    record Violation(String file, int line, String name, String reason) {
        String message() {
            return file + ":" + line + " nested class " + name + " holds test methods but nothing runs it (" + reason + "): surefire's `**/*$*` exclude drops it;"
                   + " make it a `@Nested` inner class of a run type, a top-level class, or list `" + name + ".class` in a file that imports the JUnit launcher";
        }
    }

    /// The verdict over a tree, with the evidence that the scan reached what it claims to cover.
    /// `nestedTestClasses` counts the legal `@Nested` test holders (clause 2), `abstractBases` the legal abstract ones (clause 4) and
    /// `launched` the member types a launcher-importing file lists by `Name.class` (clause 3).
    record Scan(List<Violation> violations, List<String> examinedFiles, int nestedTestClasses, int abstractBases, int launched) {}

    private NestedTestScan() {}

    /// Every violation in every `src/test/java` or `src/it/java` tree under `root`, in path then line order.
    static Scan scan(Path root) throws IOException {
        var types = new ArrayList<Type>();
        var examined = new ArrayList<String>();

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return !dir.equals(root) && FlakeScan.SKIPPED_DIRECTORIES.contains(dir.getFileName().toString())
                       ? FileVisitResult.SKIP_SUBTREE
                       : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                var relative = root.relativize(file).toString().replace('\\', '/');

                if (relative.endsWith(".java") && FlakeScan.isScanned("/" + relative)) {
                    examined.add(relative);
                    types.addAll(typesOf(relative, new String(Files.readAllBytes(file), StandardCharsets.UTF_8)));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        examined.sort(Comparator.naturalOrder());
        return verdict(types, examined);
    }

    static Scan verdict(List<Type> types, List<String> examined) {
        var run = new RunSet(types);
        var violations = new ArrayList<Violation>();
        var nestedTestClasses = 0;
        var abstractBases = 0;
        var launched = 0;

        for (var type : types) {
            var clause = run.clauseOf(type);

            launched += type.member && clause == 3 ? 1 : 0;
            if (type.isTestHolder() && clause == 0) {
                violations.add(new Violation(type.file, type.line, type.name, run.reasonFor(type)));
            } else if (type.isTestHolder()) {
                nestedTestClasses += clause == 2 ? 1 : 0;
                abstractBases += clause == 4 ? 1 : 0;
            }
        }
        violations.sort(Comparator.comparing(Violation::file).thenComparingInt(Violation::line));
        return new Scan(violations, examined, nestedTestClasses, abstractBases, launched);
    }

    /// RUN(T) over the types of the tree, as the clause number that made T run (1 top-level, 2 `@Nested` in a run type, 3 launched
    /// by its own file, 4 abstract base of a run type) or 0 when nothing runs it.
    private static final class RunSet {
        private final Map<String, List<Type>> extenders = new HashMap<>();
        private final Map<Type, Integer> clauses = new IdentityHashMap<>();
        private final Set<Type> inProgress = Collections.newSetFromMap(new IdentityHashMap<>());

        RunSet(List<Type> types) {
            for (var type : types) {
                if (type.extendsName != null) {
                    extenders.computeIfAbsent(type.extendsName, k -> new ArrayList<>()).add(type);
                }
            }
        }

        int clauseOf(Type type) {
            var known = clauses.get(type);

            if (known != null) {
                return known;
            }
            if (!inProgress.add(type)) {
                return 0;
            }

            var clause = compute(type);

            inProgress.remove(type);
            clauses.put(type, clause);
            return clause;
        }

        private int compute(Type type) {
            if (type.topLevel) {
                return 1;
            }
            if (type.launched) {
                return 3;
            }
            if (type.nestedAnnotated && type.parent != null && clauseOf(type.parent) != 0) {
                return 2;
            }
            return type.isAbstract && extenders.getOrDefault(type.name, List.of()).stream().anyMatch(sub -> clauseOf(sub) != 0) ? 4 : 0;
        }

        String reasonFor(Type type) {
            if (type.nestedAnnotated && type.parent != null) {
                return "@Nested but enclosing " + type.parent.name + " is not run";
            }
            return type.isAbstract ? "abstract base that no run class extends" : "not @Nested";
        }
    }

    /// The type declarations of one source text, tests attributed to the type whose body holds them.
    static List<Type> typesOf(String file, String source) {
        var text = blank(source);
        var lineStarts = lineStarts(text);
        var found = new ArrayList<Type>();
        var scopes = new ArrayDeque<Scope>();
        var launcherFile = LAUNCHER_IMPORT.matcher(text).find();
        var headStart = 0;
        var parens = 0;

        for (var i = 0; i < text.length(); i++) {
            var c = text.charAt(i);

            if (c == '(') {
                parens++;
            } else if (c == ')') {
                parens--;
            } else if (c == ';' && parens == 0) {
                attributeTests(scopes, text.substring(headStart, i));
                headStart = i + 1;
            } else if (c == '{') {
                var head = text.substring(headStart, i);
                var declaration = parens == 0 ? declaration(head) : null;
                var opensType = declaration != null && (scopes.isEmpty() || scopes.peek().type() != null);

                if (opensType) {
                    var type = declare(file, head, declaration, lineOf(lineStarts, headStart + declaration.start(1)), scopes, launcherFile ? text : null);

                    found.add(type);
                    scopes.push(new Scope(type, headStart, parens));
                } else {
                    attributeTests(scopes, head);
                    scopes.push(new Scope(null, headStart, parens));
                }
                headStart = i + 1;
                parens = 0;
            } else if (c == '}' && !scopes.isEmpty()) {
                var closed = scopes.pop();

                // A brace that opened inside parentheses (an annotation array, a lambda or anonymous body) belongs to the statement
                // around it: that statement's head and parenthesis depth come back.
                headStart = closed.parens() > 0 ? closed.headStart() : i + 1;
                parens = closed.parens();
            }
        }
        return found;
    }

    /// The type keyword and name of `head` when it is a declaration: only annotations and modifiers may precede the keyword.
    /// Scanned by hand, not by a repeating regex group: a head can hold thousands of blanked characters and a group loop recurses per character.
    private static Matcher declaration(String head) {
        var keyword = KEYWORD.matcher(head);

        return keyword.find() && onlyAnnotationsAndModifiers(head.substring(0, keyword.start())) ? keyword : null;
    }

    private static boolean onlyAnnotationsAndModifiers(String prefix) {
        var i = 0;

        while (i < prefix.length()) {
            var c = prefix.charAt(i);

            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '@') {
                i = afterAnnotation(prefix, i + 1);
                if (i < 0) {
                    return false;
                }
            } else {
                var end = wordEnd(prefix, i);

                if (end == i || !MODIFIERS.contains(prefix.substring(i, end))) {
                    return false;
                }
                i = end;
            }
        }
        return true;
    }

    /// The index after the annotation whose name starts at `from` (and its balanced argument list), or -1 when it is malformed.
    private static int afterAnnotation(String text, int from) {
        var i = from;

        while (i < text.length() && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_' || text.charAt(i) == '.')) {
            i++;
        }
        if (i == from) {
            return -1;
        }

        var j = i;

        while (j < text.length() && Character.isWhitespace(text.charAt(j))) {
            j++;
        }
        if (j >= text.length() || text.charAt(j) != '(') {
            return i;
        }

        var depth = 0;

        for (var k = j; k < text.length(); k++) {
            depth += text.charAt(k) == '(' ? 1 : text.charAt(k) == ')' ? -1 : 0;
            if (depth == 0) {
                return k + 1;
            }
        }
        return -1;
    }

    private static int wordEnd(String text, int from) {
        var i = from;

        while (i < text.length() && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_' || text.charAt(i) == '-')) {
            i++;
        }
        return i;
    }

    private record Scope(Type type, int headStart, int parens) {}

    /// `launchedText` is the blanked source when the file imports the JUnit launcher or test kit, null otherwise.
    private static Type declare(String file, String head, Matcher declaration, int line, Deque<Scope> scopes, String launchedText) {
        var topLevel = scopes.isEmpty();
        var isMember = !topLevel && scopes.stream().allMatch(scope -> scope.type() != null);
        var afterName = head.substring(declaration.end());
        var withoutArguments = stripTypeArguments(afterName);
        var extended = EXTENDS.matcher(withoutArguments);
        var extendsName = extended.find() ? simpleName(extended.group(1)) : null;
        var name = declaration.group(2);
        var parent = isMember ? scopes.peek().type() : null;
        var launched = launchedText != null && Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\.\\s*class\\b").matcher(launchedText).find();

        return new Type(file,
                        name,
                        line,
                        declaration.group(1),
                        topLevel,
                        isMember,
                        ABSTRACT_MODIFIER.matcher(head.substring(0, declaration.end())).find(),
                        NESTED_ANNOTATION.matcher(head.substring(0, declaration.end())).find(),
                        extendsName,
                        parent,
                        launched);
    }

    private static void attributeTests(Deque<Scope> scopes, String head) {
        if (!scopes.isEmpty() && scopes.peek().type() != null && TEST_ANNOTATION.matcher(head).find()) {
            scopes.peek().type().hasTests = true;
        }
    }

    private static String stripTypeArguments(String text) {
        var current = text;
        var next = TYPE_ARGUMENTS.matcher(current).replaceAll("");

        while (!next.equals(current)) {
            current = next;
            next = TYPE_ARGUMENTS.matcher(current).replaceAll("");
        }
        return current;
    }

    private static String simpleName(String qualified) {
        return qualified.substring(qualified.lastIndexOf('.') + 1);
    }

    private static List<Integer> lineStarts(String text) {
        var starts = new ArrayList<Integer>();

        starts.add(0);
        for (var i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        return starts;
    }

    private static int lineOf(List<Integer> lineStarts, int offset) {
        var line = 1;

        while (line < lineStarts.size() && lineStarts.get(line) <= offset) {
            line++;
        }
        return line;
    }

    /// The source with comments blanked to spaces and string, char and text-block contents blanked, quotes and line breaks kept,
    /// so offsets and line numbers survive and no brace, parenthesis, semicolon or annotation in a literal or comment is read.
    static String blank(String source) {
        var out = new StringBuilder(source.length());
        var i = 0;
        var n = source.length();

        while (i < n) {
            var c = source.charAt(i);

            if (source.startsWith("//", i)) {
                var end = lineEnd(source, i);

                out.append(" ".repeat(end - i));
                i = end;
            } else if (source.startsWith("/*", i)) {
                var close = source.indexOf("*/", i + 2);
                var end = close < 0 ? n : close + 2;

                appendBlanked(out, source, i, end);
                i = end;
            } else if (source.startsWith("\"\"\"", i)) {
                var end = textBlockEnd(source, i + 3);

                out.append('"');
                appendBlanked(out, source, i + 1, end - 1);
                out.append('"');
                i = end;
            } else if (c == '"' || c == '\'') {
                var end = literalEnd(source, i, c);
                var closed = end - i >= 2 && source.charAt(end - 1) == c;

                out.append(c);
                out.append(" ".repeat(closed ? end - i - 2 : end - i - 1));
                out.append(closed ? String.valueOf(c) : "");
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int lineEnd(String source, int from) {
        var end = source.indexOf('\n', from);

        return end < 0 ? source.length() : end;
    }

    private static void appendBlanked(StringBuilder out, String source, int from, int to) {
        for (var k = from; k < to; k++) {
            out.append(source.charAt(k) == '\n' ? '\n' : ' ');
        }
    }

    /// The index after the closing `"""` of a text block whose content starts at `from`; the end of the source when unterminated.
    private static int textBlockEnd(String source, int from) {
        var k = from;

        while (k < source.length()) {
            if (source.charAt(k) == '\\') {
                k += 2;
            } else if (source.startsWith("\"\"\"", k)) {
                return k + 3;
            } else {
                k++;
            }
        }
        return source.length();
    }

    /// The index after the closing quote of a string or char literal opened at `from`; an unterminated one ends before its line break.
    private static int literalEnd(String source, int from, char quote) {
        var k = from + 1;

        while (k < source.length() && source.charAt(k) != quote && source.charAt(k) != '\n') {
            k += source.charAt(k) == '\\' ? 2 : 1;
        }
        return k < source.length() && source.charAt(k) == quote ? k + 1 : Math.min(k, source.length());
    }
}

package org.pragmatica.jbct.cli.skeleton;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.pragmatica.jbct.parser.CstNodes;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.Java25Parser;
import org.pragmatica.jbct.parser.LineIndex;
import org.pragmatica.jbct.parser.RuleKind;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.peg.token.TokenArray;


/// Reads the skeleton of a Java source: its types and their members, each with the exact range it occupies.
///
/// A pure function of the source text, built on the JBCT parser tree. What is listed: classes, interfaces, records,
/// enums and annotation types, at any nesting depth, with their methods, constructors and initializers; with
/// `fields` also fields and enum constants. What is NOT listed: anonymous classes (an enum constant's body is one),
/// local classes and lambdas, which lie inside the range of the method that holds them.
///
/// **Range.** From the first annotation or modifier of the declaration to its last character, the closing brace or
/// semicolon, inclusive. The parser's node spans reach into the trivia after a declaration and, for a class member,
/// exclude its annotations and modifiers, so the range is computed from the first and the last real token of the
/// member wrapper instead. A doc comment is outside the range; its first row is reported as `docRow`.
public final class Skeletons {
    private static final int NO_TOKEN = -1;
    /// One line terminator as the Java compiler counts them: CRLF is one, a lone CR or LF is one.
    private static final Pattern LINE_BREAK = Pattern.compile("\r\n|\r|\n");

    private Skeletons() {}

    /// The skeleton of `source`, or the position and text of its first parse error.
    public static Result<Skeleton> skeleton(String source, boolean fields) {
        return new Java25Parser().parse(source)
                                 .mapError(cause -> SourceNotParsed.sourceNotParsed(cause, source))
                                 .map(root -> Skeleton.skeleton(reader(source, root, fields).declarations(root)));
    }

    private static Reader reader(String source, Cursor root, boolean fields) {
        return new Reader(source,
                          root.cst().tokens(),
                          new LineIndex(source),
                          fields);
    }

    private record Reader(String text, TokenArray tokens, LineIndex lines, boolean fields) {
        List<Declaration> declarations(Cursor container) {
            return CstNodes.children(container)
                           .stream()
                           .flatMap(this::declare)
                           .toList();
        }

        private Stream<Declaration> declare(Cursor node) {
            return switch (node.kind()) {
                case ROOT, COMPILATION_UNIT, ORDINARY_UNIT, ENUM_CONSTS -> declarations(node).stream();
                case TYPE_DECL, CLASS_MEMBER, INTERFACE_MEMBER, RECORD_MEMBER, ANNOTATION_MEMBER -> member(node);
                case ENUM_CONST -> enumConstant(node);
                default -> Stream.empty();
            };
        }

        private Stream<Declaration> member(Cursor wrapper) {
            return core(wrapper).stream()
                       .flatMap(core -> describe(wrapper, core));
        }

        private Option<Cursor> core(Cursor wrapper) {
            return Option.from(CstNodes.children(wrapper)
                                       .stream()
                                       .filter(child -> !child.kindIs(RuleKind.ANNOTATION))
                                       .findFirst()).flatMap(this::unwrap);
        }

        private Option<Cursor> unwrap(Cursor node) {
            return switch (node.kind()) {
                case MEMBER, TYPE_KIND -> core(node);
                case CLASS_DECL, INTERFACE_DECL, ENUM_DECL, RECORD_DECL, ANNOTATION_DECL, METHOD_DECL, CONSTRUCTOR_DECL, COMPACT_CONSTRUCTOR, INITIALIZER_BLOCK, FIELD_DECL, INTERFACE_FIELD_DECL, RECORD_STATIC_FIELD, ANNOTATION_ELEM_DECL -> Option.some(node);
                default -> Option.none();
            };
        }

        private Stream<Declaration> describe(Cursor wrapper, Cursor core) {
            return switch (core.kind()) {
                case CLASS_DECL -> type(Kind.CLASS, wrapper, core);
                case INTERFACE_DECL -> type(Kind.INTERFACE, wrapper, core);
                case ENUM_DECL -> type(Kind.ENUM, wrapper, core);
                case RECORD_DECL -> type(Kind.RECORD, wrapper, core);
                case ANNOTATION_DECL -> type(Kind.ANNOTATION, wrapper, core);
                case METHOD_DECL -> single(Kind.METHOD, wrapper, methodName(core), methodSignature(core));
                case CONSTRUCTOR_DECL -> single(Kind.CONSTRUCTOR,
                                                wrapper,
                                                constructorName(core),
                                                "(" + paramList(core) + ")");
                case COMPACT_CONSTRUCTOR -> single(Kind.CONSTRUCTOR, wrapper, compactName(core), "(compact)");
                case ANNOTATION_ELEM_DECL -> single(Kind.METHOD, wrapper, methodName(core), "(): " + typeOf(core));
                case INITIALIZER_BLOCK -> single(Kind.INITIALIZER, wrapper, initializerName(core), "");
                case FIELD_DECL, INTERFACE_FIELD_DECL, RECORD_STATIC_FIELD -> fieldsOf(wrapper, core);
                default -> Stream.empty();
            };
        }

        // ===== Declarations =====
        private Stream<Declaration> single(Kind kind, Cursor wrapper, String name, String signature) {
            return Stream.of(make(kind, wrapper, name, signature, List.of()));
        }

        private Stream<Declaration> type(Kind kind, Cursor wrapper, Cursor core) {
            return Stream.of(make(kind, wrapper, typeName(core), "", bodyDeclarations(core)));
        }

        private List<Declaration> bodyDeclarations(Cursor core) {
            return CstNodes.children(core)
                           .stream()
                           .filter(this::isBody)
                           .findFirst()
                           .map(this::declarations)
                           .orElse(List.of());
        }

        private boolean isBody(Cursor node) {
            return node.kindIsAny(RuleKind.CLASS_BODY,
                                  RuleKind.INTERFACE_BODY,
                                  RuleKind.ENUM_BODY,
                                  RuleKind.RECORD_BODY,
                                  RuleKind.ANNOTATION_BODY);
        }

        private Stream<Declaration> enumConstant(Cursor constant) {
            return fields
                   ? Stream.of(make(Kind.ENUM_CONSTANT, constant, enumConstantName(constant), "", List.of()))
                   : Stream.empty();
        }

        private Stream<Declaration> fieldsOf(Cursor wrapper, Cursor core) {
            return fields
                   ? variables(core).map(variable -> make(Kind.FIELD,
                                                          wrapper,
                                                          firstName(variable),
                                                          ": " + typeOf(core),
                                                          List.of()))
                   : Stream.empty();
        }

        private Stream<Cursor> variables(Cursor core) {
            return CstNodes.children(core)
                           .stream()
                           .flatMap(child -> child.kindIs(RuleKind.VAR_DECLS)
                                             ? CstNodes.children(child).stream()
                                             : Stream.of(child))
                           .filter(child -> child.kindIsAny(RuleKind.VAR_DECL, RuleKind.INTERFACE_VAR_DECL));
        }

        private Declaration make(Kind kind, Cursor wrapper, String name, String signature, List<Declaration> children) {
            return Declaration.declaration(kind,
                                           name,
                                           signature,
                                           range(wrapper),
                                           docRow(wrapper),
                                           annotations(wrapper),
                                           children);
        }

        /// Every annotation of the declaration in source order: those before the modifiers sit on the wrapper, those
        /// after a modifier on the member or on the declaration itself.
        private List<String> annotations(Cursor wrapper) {
            return annotationsIn(wrapper).sorted(Comparator.comparingInt(Cursor::firstTokenIdx))
                                         .map(annotation -> "@" + CstNodes.annotationSimpleName(annotation))
                                         .toList();
        }

        private Stream<Cursor> annotationsIn(Cursor node) {
            return CstNodes.children(node)
                           .stream()
                           .flatMap(child -> child.kindIs(RuleKind.ANNOTATION)
                                             ? Stream.of(child)
                                             : holdsModifiers(child)
                                               ? annotationsIn(child)
                                               : Stream.empty());
        }

        private boolean holdsModifiers(Cursor node) {
            return node.kindIsAny(RuleKind.MEMBER,
                                  RuleKind.TYPE_KIND,
                                  RuleKind.CLASS_DECL,
                                  RuleKind.INTERFACE_DECL,
                                  RuleKind.ENUM_DECL,
                                  RuleKind.RECORD_DECL,
                                  RuleKind.ANNOTATION_DECL,
                                  RuleKind.METHOD_DECL,
                                  RuleKind.CONSTRUCTOR_DECL,
                                  RuleKind.COMPACT_CONSTRUCTOR,
                                  RuleKind.FIELD_DECL,
                                  RuleKind.INTERFACE_FIELD_DECL,
                                  RuleKind.RECORD_STATIC_FIELD,
                                  RuleKind.ANNOTATION_ELEM_DECL);
        }

        // ===== Names and signatures =====
        // A name is located by position (the first real token after what precedes it), never by token kind: a
        // contextual keyword such as `value` or `record` is a legal name and has a kind of its own.
        private String typeName(Cursor core) {
            var afterKeyword = forward(core.firstTokenIdx()) + 1;

            return nameAt("@".contentEquals(tokens.textAt(core.firstTokenIdx()))
                          ? afterKeyword + 1
                          : afterKeyword);
        }

        private String firstName(Cursor core) {
            return nameAt(core.firstTokenIdx());
        }

        /// The identifier right before the body: a compact constructor's modifiers and annotations belong to its core.
        private String compactName(Cursor core) {
            return nameAt(CstNodes.childByRule(core, RuleKind.BLOCK)
                                  .map(block -> backward(block.firstTokenIdx() - 1))
                                  .or(core.firstTokenIdx()));
        }

        private String methodName(Cursor core) {
            return nameAt(afterType(core));
        }

        private int afterType(Cursor core) {
            return CstNodes.childByRule(core, RuleKind.TYPE)
                           .map(type -> backward(type.lastTokenIdx()) + 1)
                           .or(core.firstTokenIdx());
        }

        private String constructorName(Cursor core) {
            return nameAt(CstNodes.childByRule(core, RuleKind.TYPE_PARAMS)
                                  .map(params -> backward(params.lastTokenIdx()) + 1)
                                  .or(afterAnnotations(core)));
        }

        /// The first token after the annotations that stand among the modifiers of `core`, or its first token.
        private int afterAnnotations(Cursor core) {
            return CstNodes.childrenByRule(core, RuleKind.ANNOTATION)
                           .stream()
                           .mapToInt(annotation -> backward(annotation.lastTokenIdx()) + 1)
                           .max()
                           .orElse(core.firstTokenIdx());
        }

        private String initializerName(Cursor core) {
            return "static".contentEquals(tokens.textAt(core.firstTokenIdx()))
                   ? "static"
                   : "";
        }

        private String enumConstantName(Cursor constant) {
            return nameAt(afterAnnotations(constant));
        }

        private String nameAt(int index) {
            return tokens.textAt(forward(index))
                         .toString();
        }

        private String methodSignature(Cursor core) {
            return "(" + paramList(core) + "): " + typeOf(core);
        }

        private String paramList(Cursor core) {
            return CstNodes.childByRule(core, RuleKind.PARAMS)
                           .map(params -> CstNodes.findAll(params,
                                                           node -> node.kindIsAny(RuleKind.PLAIN_PARAM,
                                                                                  RuleKind.LAST_PARAM))
                                                  .stream()
                                                  .map(this::paramType)
                                                  .toList())
                           .or(List.of())
                           .stream()
                           .reduce((left, right) -> left + ", " + right)
                           .orElse("");
        }

        private String paramType(Cursor param) {
            return typeOf(param) + (isVarargs(param)
                                    ? "..."
                                    : "");
        }

        private boolean isVarargs(Cursor param) {
            return IntStream.rangeClosed(param.firstTokenIdx(),
                                         param.lastTokenIdx())
                            .anyMatch(index -> "...".contentEquals(tokens.textAt(index)));
        }

        /// The type child of `node` as written: the source between its first and last real token, whitespace runs
        /// collapsed to one space. Never resolved.
        private String typeOf(Cursor node) {
            return CstNodes.childByRule(node, RuleKind.TYPE)
                           .map(type -> cut(type.firstTokenIdx(),
                                            type.lastTokenIdx()))
                           .or("");
        }

        private String cut(int first, int last) {
            return text.substring(tokens.startAt(forward(first)),
                                  tokens.endAt(backward(last)))
                       .replaceAll("\\s+", " ");
        }

        // ===== Coordinates =====
        private Range range(Cursor wrapper) {
            var start = tokens.startAt(forward(wrapper.firstTokenIdx()));
            var end = tokens.endAt(backward(wrapper.lastTokenIdx())) - 1;

            return Range.range(lines.lineAt(start), lines.columnAt(start), lines.lineAt(end), lines.columnAt(end));
        }

        private int forward(int index) {
            return tokens.isTrivia(index)
                   ? forward(index + 1)
                   : index;
        }

        private int backward(int index) {
            return tokens.isTrivia(index)
                   ? backward(index - 1)
                   : index;
        }

        // ===== Doc comments =====
        private Option<Integer> docRow(Cursor wrapper) {
            var doc = docBefore(forward(wrapper.firstTokenIdx()) - 1);

            return doc == NO_TOKEN
                   ? Option.none()
                   : Option.some(lines.lineAt(tokens.startAt(blockStart(doc))));
        }

        /// The nearest doc comment among the trivia directly before a declaration, or [#NO_TOKEN].
        private int docBefore(int index) {
            return index < 0 || !tokens.isTrivia(index)
                   ? NO_TOKEN
                   : isDoc(index)
                     ? index
                     : docBefore(index - 1);
        }

        private boolean isDoc(int index) {
            return tokens.kindAt(index) == TokenArray.KIND_DOC_LINE_COMMENT || tokens.kindAt(index) == TokenArray.KIND_DOC_BLOCK_COMMENT;
        }

        /// A run of `///` lines is one comment: its first row is the doc row.
        private int blockStart(int index) {
            var before = index - 2;

            return tokens.kindAt(index) == TokenArray.KIND_DOC_LINE_COMMENT && continuesLineDoc(before, index)
                   ? blockStart(before)
                   : index;
        }

        private boolean continuesLineDoc(int before, int index) {
            return before >= 0
                   && tokens.kindAt(before) == TokenArray.KIND_DOC_LINE_COMMENT
                   && singleNewline(index - 1);
        }

        private boolean singleNewline(int index) {
            return tokens.kindAt(index) == TokenArray.KIND_WHITESPACE && LINE_BREAK.matcher(tokens.textAt(index))
                                                                                  .results()
                                                                                  .count() == 1;
        }
    }
}

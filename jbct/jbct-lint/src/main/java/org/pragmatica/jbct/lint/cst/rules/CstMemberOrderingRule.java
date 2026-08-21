package org.pragmatica.jbct.lint.cst.rules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.pragmatica.jbct.lint.Diagnostic;
import org.pragmatica.jbct.lint.LintContext;
import org.pragmatica.jbct.lint.cst.CstLintRule;
import org.pragmatica.jbct.lint.cst.filetype.FileType;
import org.pragmatica.jbct.lint.cst.filetype.FileTypeClassifier;
import org.pragmatica.jbct.parser.CstNodes;
import org.pragmatica.jbct.parser.Cursor;
import org.pragmatica.jbct.parser.RuleKind;

import static org.pragmatica.jbct.parser.CstNodes.*;


/// JBCT-ORD-01: member ordering per file type.
///
/// Encodes the book's project-structure member-ordering tables for the two file kinds the ticket
/// names, gating on [FileTypeClassifier]:
///
///   - **value object** ([FileType#VALUE_OBJECT]): public constants -> constructor -> methods, where
///     the static factory and the accessors share one rank so their relative order is not enforced
///     (serialization pairs like `toJson`/`fromJson`, and private factory helpers near their use, are
///     not order-breaking). Private static-final constants (validation patterns, formatters, private
///     pre-built instances) are exempt from constants-first — they are implementation details,
///     conventionally placed at the bottom;
///   - **use case** ([FileType#USE_CASE]): nested records -> `execute` -> step interfaces -> factory.
///
/// Members of the principal type are read in source order and assigned an ordinal by role; the FIRST
/// member whose ordinal is lower than a preceding member's (the first out-of-order member) is
/// reported, and only that one. Member kinds absent from the file type's table — nested type
/// declarations in a value object, constants in a use-case interface — are ignored entirely: never
/// ranked, never counted, never treated as order-breaking. Other file types are not ordering-checked.
///
/// The use-case order follows the manuscript's canonical `project-structure.md` list and every
/// worked code example: the entry method (`execute`) comes early (right after the Request/Response
/// records) and the static factory is last. (An earlier draft encoded the #453 ticket's inverted
/// `records -> steps -> factory -> execute`; the book was reconciled to execute-early.)
public class CstMemberOrderingRule implements CstLintRule {
    private static final String RULE_ID = "JBCT-ORD-01";

    /// Returned for a member kind that is not in the file type's ordering table — ignored entirely:
    /// never ranked, never counted, never treated as out of order.
    private static final int IGNORED = -1;

    private static final int RANK_CONSTANT = 0;
    private static final int RANK_CONSTRUCTOR = 1;
    private static final int RANK_METHOD = 2;

    private static final int RANK_UC_RECORD = 0;
    private static final int RANK_UC_ENTRY = 1;
    private static final int RANK_UC_STEP = 2;
    private static final int RANK_UC_FACTORY = 3;

    @Override
    public String ruleId() {
        return RULE_ID;
    }

    @Override
    public Stream<Diagnostic> analyze(Cursor root, String source, LintContext ctx) {
        if (!ctx.shouldLint(packageName(root))) {
            return Stream.empty();
        }

        var fileType = FileTypeClassifier.classify(root);

        if (fileType != FileType.VALUE_OBJECT && fileType != FileType.USE_CASE) {
            return Stream.empty();
        }

        return FileTypeClassifier.principalType(root)
                                 .map(typeDecl -> firstInversion(root, typeDecl, fileType, ctx))
                                 .or(Stream.empty());
    }

    private Stream<Diagnostic> firstInversion(Cursor root, Cursor typeDecl, FileType fileType, LintContext ctx) {
        var ownName = FileTypeClassifier.declaredName(typeDecl);
        var maxRank = Integer.MIN_VALUE;

        for (var member : orderedMembers(root, typeDecl)) {
            var rank = rankFor(root, member, fileType, ownName);

            if (rank == IGNORED) {
                continue;
            }

            if (rank < maxRank) {
                return Stream.of(createDiagnostic(member, fileType, ctx));
            }

            maxRank = Math.max(maxRank, rank);
        }

        return Stream.empty();
    }

    private List<Cursor> orderedMembers(Cursor root, Cursor typeDecl) {
        var members = new ArrayList<Cursor>();

        members.addAll(directOf(root, typeDecl, CstNodes::isFieldDecl));
        members.addAll(directOf(root, typeDecl, node -> node.kindIs(RuleKind.CONSTRUCTOR_DECL)));
        members.addAll(directOf(root, typeDecl, node -> node.kindIs(RuleKind.COMPACT_CONSTRUCTOR)));
        members.addAll(FileTypeClassifier.directMethods(root, typeDecl));
        members.addAll(FileTypeClassifier.directNestedTypes(root, typeDecl));
        members.sort(Comparator.comparingInt(Cursor::spanStart));

        return members;
    }

    private List<Cursor> directOf(Cursor root, Cursor typeDecl, Predicate<Cursor> predicate) {
        return findAll(typeDecl, predicate).stream()
                     .filter(node -> FileTypeClassifier.directlyEncloses(root, typeDecl, node))
                     .toList();
    }

    private int rankFor(Cursor root, Cursor member, FileType fileType, String ownName) {
        return fileType == FileType.USE_CASE
               ? useCaseRank(root, member, ownName)
               : valueObjectRank(root, member, ownName);
    }

    private int valueObjectRank(Cursor root, Cursor member, String ownName) {
        return switch (member.kind()) {
            case FIELD_DECL, INTERFACE_FIELD_DECL, RECORD_STATIC_FIELD ->
                FileTypeClassifier.isPrivate(root, member) ? IGNORED : RANK_CONSTANT;
            case CONSTRUCTOR_DECL, COMPACT_CONSTRUCTOR -> RANK_CONSTRUCTOR;
            case MEMBER, INTERFACE_MEMBER, RECORD_MEMBER -> memberRank(root, member, ownName, RANK_METHOD, RANK_METHOD);
            default -> IGNORED;
        };
    }

    private int useCaseRank(Cursor root, Cursor member, String ownName) {
        return switch (member.kind()) {
            case TYPE_KIND -> useCaseTypeRank(member);
            case MEMBER, INTERFACE_MEMBER, RECORD_MEMBER -> memberRank(root, member, ownName, RANK_UC_FACTORY, RANK_UC_ENTRY);
            default -> IGNORED;
        };
    }

    /// A static method ranks as the factory only when it produces the type's own type; any other
    /// static method (a private helper, a converter) is ignored — never order-breaking. Instance
    /// methods rank as accessors / the entry method.
    private int memberRank(Cursor root, Cursor member, String ownName, int factoryRank, int instanceRank) {
        if (!FileTypeClassifier.isStatic(root, member)) {
            return instanceRank;
        }

        return FileTypeClassifier.producesOwnType(member, ownName)
               ? factoryRank
               : IGNORED;
    }

    private int useCaseTypeRank(Cursor member) {
        if (FileTypeClassifier.isRecord(member)) {
            return RANK_UC_RECORD;
        }

        return FileTypeClassifier.isInterface(member)
               ? RANK_UC_STEP
               : IGNORED;
    }

    private Diagnostic createDiagnostic(Cursor member, FileType fileType, LintContext ctx) {
        return Diagnostic.diagnostic(RULE_ID,
                                     ctx.severityFor(RULE_ID),
                                     ctx.fileName(),
                                     startLine(anchorOf(member)),
                                     startColumn(anchorOf(member)),
                                     "Member is out of order for a " + describe(fileType),
                                     expectedOrder(fileType));
    }

    private String describe(FileType fileType) {
        return fileType == FileType.USE_CASE
               ? "use-case interface"
               : "value object";
    }

    private String expectedOrder(FileType fileType) {
        return fileType == FileType.USE_CASE
               ? "Use-case interface order: nested records -> execute -> step interfaces -> static factory."
               : "Value object order: public constants -> constructor -> methods (private constants exempt; static factory and accessors unordered).";
    }
}

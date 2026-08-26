// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.parser.transform;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.pragmatica.aether.pg.parser.ast.common.DataTypeName;
import org.pragmatica.aether.pg.parser.ast.common.Identifier;
import org.pragmatica.aether.pg.parser.ast.common.QualifiedName;
import org.pragmatica.lang.Option;
import org.pragmatica.aether.pg.parser.PostgresParser.CstNode;
import org.pragmatica.aether.pg.parser.PostgresParser.SourceSpan;


public final class CstExtractor {
    private CstExtractor() {}

    public static Identifier extractIdentifier(CstNavigator nav) {
        var quoted = nav.child("QuotedIdentifier");

        if (quoted.isPresent()) {
            var text = quoted.unwrap().firstTokenText().or("");

            return Identifier.quoted(nav.span(), text);
        }

        var unicode = nav.child("UnicodeIdentifier");

        if (unicode.isPresent()) {
            var text = unicode.unwrap().firstTokenText().or("");

            return new Identifier(nav.span(), text, Identifier.QuoteStyle.UNICODE_QUOTED);
        }

        var unquoted = nav.tokenText("UnquotedIdentifier");

        if (unquoted.isPresent()) {
            return Identifier.unquoted(nav.span(), unquoted.unwrap());
        }

        return classifyRawIdentifier(nav.span(),
                                     nav.firstTokenText().or("???"));
    }

    /// peglib 0.7.x lexes an identifier as ONE token — `ColId`, or the kind of whatever keyword rule
    /// spells the same text under identifier fallback — instead of 0.6.0's
    /// `ColId -> QuotedIdentifier` nesting whose `< >` capture yielded the inner text. The quote
    /// style therefore has to be read back off the lexeme rather than off the node name, and the
    /// delimiters stripped here rather than by the grammar.
    private static Identifier classifyRawIdentifier(SourceSpan span, String raw) {
        if (raw.length() > 3 && (raw.startsWith("U&\"") || raw.startsWith("u&\"")) && raw.endsWith("\"")) {
            return new Identifier(span,
                                  unescapeDoubled(raw.substring(3, raw.length() - 1)),
                                  Identifier.QuoteStyle.UNICODE_QUOTED);
        }

        if (raw.length() > 1 && raw.startsWith("\"") && raw.endsWith("\"")) {
            return Identifier.quoted(span,
                                     unescapeDoubled(raw.substring(1, raw.length() - 1)));
        }

        return Identifier.unquoted(span, raw);
    }

    private static String unescapeDoubled(String inner) {
        return inner.replace("\"\"", "\"");
    }

    /// The identifier at a fixed grammatical POSITION — the first leaf child — rather than by rule
    /// name. Under peglib 0.7.x identifier fallback the same identifier can arrive three ways:
    /// as `Token ColId` (`id`), as another rule's kind when it spells that rule's literal
    /// (`public` -> `Token PublicKW`), or as an ANONYMOUS `Terminal` when it collides with an
    /// inline literal (`name` -> `Terminal [name]`, no kind at all). Looking one up by the name
    /// "ColId" therefore drops an arbitrary subset — silently, since a missing identifier reads as
    /// "no column here" rather than as an error.
    public static Option<Identifier> leadingIdentifier(CstNavigator nav) {
        for (var child : nav.children()) {
            switch (child) {
                case CstNode.Token tok -> {
                    return Option.present(classifyRawIdentifier(tok.span(), tok.text()));
                }
                case CstNode.Terminal term -> {
                    return Option.present(classifyRawIdentifier(term.span(), term.text()));
                }
                default -> {
                    return Option.empty();
                }
            }
        }

        return Option.empty();
    }

    /// The last leaf child appearing BEFORE the first nested rule — the identifier in shapes like
    /// `AlterColumnAction <- AlterKW ColumnKW? ColId AlterColumnCmd`, where the number of leading
    /// keywords varies and the name itself may arrive as any kind or as an anonymous Terminal.
    /// See [#leadingIdentifier] for why the name cannot be used to find it.
    public static Option<Identifier> identifierBeforeNested(CstNavigator nav) {
        Option<Identifier> last = Option.empty();

        for (var child : nav.children()) {
            switch (child) {
                case CstNode.Token tok -> last = Option.present(classifyRawIdentifier(tok.span(), tok.text()));
                case CstNode.Terminal term -> last = Option.present(classifyRawIdentifier(term.span(), term.text()));
                default -> {
                    return last;
                }
            }
        }

        return last;
    }

    /// Leaf identifiers of `nav`, skipping leaves whose text is one of `keywords` (compared
    /// case-insensitively). For fixed-shape rules such as
    /// `RenameAction <- RenameKW (ColumnKW ColId ToKW ColId / ToKW ColId)` the keywords are known
    /// and everything else is a name — which is the only way to pick the names out when they may
    /// arrive under any kind or as anonymous Terminals. See [#leadingIdentifier].
    public static List<Identifier> leafIdentifiers(CstNavigator nav, Set<String> keywords) {
        var result = new ArrayList<Identifier>();

        for (var child : nav.children()) {
            var leaf = switch (child) {
                case CstNode.Token tok -> Option.present(classifyRawIdentifier(tok.span(), tok.text()));
                case CstNode.Terminal term -> Option.present(classifyRawIdentifier(term.span(), term.text()));
                default -> Option.<Identifier> empty();
            };

            leaf.filter(id -> !keywords.contains(id.value().toLowerCase())).onPresent(result::add);
        }

        return result;
    }

    /// Text of a `StringLiteral` with its SQL delimiters removed. 0.6.0's grammar captured the
    /// inner text via a `< >` capture; 0.7.x hands back the whole lexeme, quotes included, so
    /// enum labels and defaults arrived as `'pending'` rather than `pending`.
    public static Option<String> stringLiteralText(CstNavigator nav) {
        return deepLeafText(nav).map(CstExtractor::unquoteSqlString);
    }

    private static String unquoteSqlString(String raw) {
        if (raw.length() > 2 && (raw.startsWith("E'") || raw.startsWith("e'")) && raw.endsWith("'")) {
            return raw.substring(2,
                                 raw.length() - 1)
                      .replace("''", "'");
        }

        if (raw.length() > 1 && raw.startsWith("'") && raw.endsWith("'")) {
            return raw.substring(1,
                                 raw.length() - 1)
                      .replace("''", "'");
        }

        return raw;
    }

    /// True when any direct leaf of `nav` has this text (case-insensitive). Needed where a keyword's
    /// kind is unpredictable: `CREATE UNIQUE INDEX` lexes UNIQUE as `UniqueColConstraint`, because
    /// that rule spells the same literal and claimed the kind, so findAll("UniqueKW") sees nothing.
    public static boolean hasLeafText(CstNavigator nav, String text) {
        for (var child : nav.children()) {
            var leaf = switch (child) {
                case CstNode.Token tok -> tok.text();
                case CstNode.Terminal term -> term.text();
                default -> "";
            };

            if (leaf.equalsIgnoreCase(text)) {
                return true;
            }
        }

        return false;
    }

    public static QualifiedName extractQualifiedName(CstNavigator nav) {
        var parts = new ArrayList<Identifier>();
        // `QualifiedName <- ColId ('.' (ColId / '*'))*`, so every direct TOKEN child is a name part
        // and the dots arrive as Terminals. Selecting by position rather than by the name "ColId" is
        // required under identifier fallback: a part that happens to spell a keyword is lexed under
        // THAT keyword's kind, so `public.users` yields PublicKW + ColId and a name-based lookup
        // silently drops the schema.
        for (var child : nav.children()) {
            switch (child) {
                case CstNode.Token tok -> parts.add(classifyRawIdentifier(tok.span(), tok.text()));
                // A part colliding with an inline literal arrives as an anonymous Terminal; the '.'
                // and '*' separators arrive the same way, so they are the only things to skip.
                case CstNode.Terminal term when!term.text().equals(".") && !term.text().equals("*") -> parts.add(classifyRawIdentifier(term.span(),
                                                                                                                                       term.text()));
                default -> {}
            }
        }

        if (parts.isEmpty()) {
            // `QualifiedTypeName` wraps a nested `QualifiedName`; descend before giving up.
            var nested = nav.child("QualifiedName");

            if (nested.isPresent()) {
                return extractQualifiedName(nested.unwrap());
            }

            nav.findAll("ColId").forEach(colId -> parts.add(extractIdentifier(colId)));
        }

        return new QualifiedName(nav.span(), parts);
    }

    /// Text of the first leaf under `nav`, descending through however many wrapper rules the
    /// grammar interposes. `ScalarType -> DateTimeType -> TimestampType -> Terminal [timestamptz]`
    /// cannot be reached by enumerating rule names — the enumeration was already missing
    /// `DateTimeType`, and every future type rule would have to be added to it by hand.
    private static Option<String> deepLeafText(CstNavigator nav) {
        for (var child : nav.children()) {
            switch (child) {
                case CstNode.Token tok -> {
                    return Option.present(tok.text());
                }
                case CstNode.Terminal term -> {
                    return Option.present(term.text());
                }
                case CstNode.NonTerminal nt -> {
                    var deeper = deepLeafText(CstNavigator.of(nt));

                    if (deeper.isPresent()) {
                        return deeper;
                    }
                }
                default -> {}
            }
        }

        return Option.empty();
    }

    public static DataTypeName extractDataType(CstNavigator nav) {
        var arrayType = nav.child("ArrayType");
        var arrayDims = arrayType.isPresent()
                        ? countArrayDimensions(arrayType.unwrap())
                        : 0;
        // 0.7.x always interposes ArrayType between DataType and ScalarType, even for a scalar with
        // zero dimensions; 0.6.0 nested ScalarType directly under DataType. Look through it, or every
        // ScalarType lookup below misses and the type reads as "unknown".
        var typeRoot = arrayType.or(nav);
        var scalarTokenText = typeRoot.tokenText("ScalarType");

        if (scalarTokenText.isPresent() && !scalarTokenText.unwrap().isEmpty()) {
            var scalarType = typeRoot.child("ScalarType");

            if (scalarType.isPresent() && scalarType.unwrap().has("TypeModifiers")) {} else {
                var dt = DataTypeName.builtin(nav.span(),
                                              scalarTokenText.unwrap().trim());

                return arrayDims > 0
                       ? DataTypeName.array(dt, arrayDims)
                       : dt;
            }
        }

        var scalarType = typeRoot.child("ScalarType").or(typeRoot);
        var directToken = scalarType.firstTokenText();

        if (directToken.isPresent() && !directToken.unwrap().isEmpty() && !scalarType.has("TypeModifiers")) {
            var dt = DataTypeName.builtin(nav.span(),
                                          directToken.unwrap().trim());

            return arrayDims > 0
                   ? DataTypeName.array(dt, arrayDims)
                   : dt;
        }

        var baseName = extractScalarTypeName(scalarType);
        var modifiers = extractTypeModifiers(scalarType);

        if (baseName.isEmpty()) {
            var qualTypeName = scalarType.child("QualifiedTypeName");

            if (qualTypeName.isPresent()) {
                var qname = extractQualifiedName(qualTypeName.unwrap());

                return DataTypeName.array(DataTypeName.custom(nav.span(), qname),
                                          arrayDims);
            }

            return DataTypeName.builtin(nav.span(), "unknown");
        }

        var dt = modifiers.isEmpty()
                 ? DataTypeName.builtin(nav.span(), baseName)
                 : DataTypeName.builtin(nav.span(), baseName, modifiers);

        return arrayDims > 0
               ? DataTypeName.array(dt, arrayDims)
               : dt;
    }

    /// Names of a `ColumnList <- ColId (',' ColId)*`, selected by POSITION -- every leaf child that
    /// is not a separator is a name. Reading them back by the rule name "ColId" silently returned a
    /// SHORT list: under peglib 0.7.x identifier fallback a name spelling a keyword is lexed under
    /// THAT keyword's kind, so `INSERT INTO t (id, version)` yielded only `id` and the dropped
    /// column went unvalidated. See [#leadingIdentifier].
    public static List<Identifier> extractColumnList(CstNavigator nav) {
        var result = new ArrayList<Identifier>();

        for (var child : nav.children()) {
            switch (child) {
                case CstNode.Token tok -> result.add(classifyRawIdentifier(tok.span(), tok.text()));
                case CstNode.Terminal term when!term.text().equals(",") -> result.add(classifyRawIdentifier(term.span(),
                                                                                                            term.text()));
                case CstNode.NonTerminal nt -> result.add(extractIdentifier(CstNavigator.of(nt)));
                default -> {}
            }
        }

        return result;
    }

    private static String extractScalarTypeName(CstNavigator scalarType) {
        var typeRules = List.of("NumericType",
                                "CharType",
                                "BooleanType",
                                "JsonType",
                                "UuidType",
                                "ByteaType",
                                "XmlType",
                                "MoneyType",
                                "SerialType",
                                "BitType",
                                "NetworkType",
                                "TsvectorType",
                                "DateType");

        for (var rule : typeRules) {
            var text = scalarType.tokenText(rule);

            if (text.isPresent() && !text.unwrap().isEmpty()) {
                return text.unwrap()
                           .trim();
            }
        }

        var dateTimeType = scalarType.child("DateTimeType");

        if (dateTimeType.isPresent()) {
            return extractDateTimeTypeName(dateTimeType.unwrap());
        }

        var tsType = scalarType.child("TimestampType");

        if (tsType.isPresent()) {
            return extractTimestampTypeName(tsType.unwrap());
        }

        var timeType = scalarType.child("TimeType");

        if (timeType.isPresent()) {
            return extractTimeTypeName(timeType.unwrap());
        }

        var intervalType = scalarType.child("IntervalType");

        if (intervalType.isPresent()) {
            return "interval";
        }

        return "";
    }

    private static String extractDateTimeTypeName(CstNavigator dateTimeType) {
        var tsType = dateTimeType.child("TimestampType");

        if (tsType.isPresent()) {
            return extractTimestampTypeName(tsType.unwrap());
        }

        var timeType = dateTimeType.child("TimeType");

        if (timeType.isPresent()) {
            return extractTimeTypeName(timeType.unwrap());
        }

        var dateType = dateTimeType.tokenText("DateType");

        if (dateType.isPresent()) {
            return "date";
        }

        var intervalType = dateTimeType.child("IntervalType");

        if (intervalType.isPresent()) {
            return "interval";
        }

        return "";
    }

    private static String extractTimestampTypeName(CstNavigator nav) {
        var text = deepLeafText(nav).or("timestamp").trim().toLowerCase();

        if (text.contains("timestamptz")) {
            return "timestamptz";
        }

        if (nav.has("WithoutKW")) {
            return "timestamp without time zone";
        }

        if (nav.has("WithKW")) {
            return "timestamp with time zone";
        }

        return "timestamp";
    }

    private static String extractTimeTypeName(CstNavigator nav) {
        var text = deepLeafText(nav).or("time").trim().toLowerCase();

        if (text.contains("timetz")) {
            return "timetz";
        }

        if (nav.has("WithoutKW")) {
            return "time without time zone";
        }

        if (nav.has("WithKW")) {
            return "time with time zone";
        }

        return "time";
    }

    private static List<Integer> extractTypeModifiers(CstNavigator scalarType) {
        var modifiers = new ArrayList<Integer>();

        collectNumericLiterals(scalarType.node(), modifiers);

        return modifiers;
    }

    private static void collectNumericLiterals(CstNode node, List<Integer> result) {
        switch (node) {
            case CstNode.Token tok when tok.ruleName().equals("NumericLiteral") -> {
                try {
                    result.add(Integer.parseInt(tok.text()));
                } catch (NumberFormatException e) {}
            }
            case CstNode.NonTerminal nt -> {
                for (var child : nt.children()) {
                    collectNumericLiterals(child, result);
                }
            }
            default -> {}
        }
    }

    private static int countArrayDimensions(CstNavigator arrayType) {
        return countBrackets(arrayType.node());
    }

    private static int countBrackets(CstNode node) {
        int count = 0;
        var text = switch (node) {
            case CstNode.Terminal t -> t.text();
            case CstNode.Token t -> t.text();
            default -> "";
        };

        if (text.equals("[")) {
            count++;
        }

        if (node instanceof CstNode.NonTerminal nt) {
            for (var child : nt.children()) {
                count += countBrackets(child);
            }
        }

        return count;
    }
}

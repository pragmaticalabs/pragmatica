package org.pragmatica.aether.pg.parser.transform;

import org.pragmatica.aether.pg.parser.ast.common.DataTypeName;
import org.pragmatica.aether.pg.parser.ast.common.Identifier;
import org.pragmatica.aether.pg.parser.ast.common.QualifiedName;
import org.pragmatica.lang.Option;
import org.pragmatica.aether.pg.parser.PostgresParser.CstNode;
import org.pragmatica.aether.pg.parser.PostgresParser.SourceSpan;

import java.util.ArrayList;
import java.util.List;

/// Extracts typed AST nodes from CST nodes.
public final class CstExtractor {
    private CstExtractor() {}

    /// Extract an Identifier from a ColId or ColLabel CST node.
    public static Identifier extractIdentifier(CstNavigator nav) {
        // ColId -> QuotedIdentifier / UnicodeIdentifier / UnquotedIdentifier
        var quoted = nav.child("QuotedIdentifier");
        if ( quoted.isPresent()) {
            var text = quoted.unwrap().firstTokenText()
                                    .or("");
            return Identifier.quoted(nav.span(), text);
        }
        var unicode = nav.child("UnicodeIdentifier");
        if ( unicode.isPresent()) {
            var text = unicode.unwrap().firstTokenText()
                                     .or("");
            return new Identifier(nav.span(), text, Identifier.QuoteStyle.UNICODE_QUOTED);
        }
        var unquoted = nav.tokenText("UnquotedIdentifier");
        if ( unquoted.isPresent()) {
        return Identifier.unquoted(nav.span(), unquoted.unwrap());}
        // Fallback: try to get any token text from the node
        var anyText = nav.firstTokenText().or("???");
        return Identifier.unquoted(nav.span(), anyText);
    }

    /// Extract a QualifiedName from a QualifiedName CST node.
    public static QualifiedName extractQualifiedName(CstNavigator nav) {
        var parts = new ArrayList<Identifier>();
        // First part is always a ColId
        var firstColId = nav.child("ColId");
        if ( firstColId.isPresent()) {
        parts.add(extractIdentifier(firstColId.unwrap()));}
        // Subsequent parts come from the QualifiedName continuation nodes
        // The grammar produces: ColId ('.' (ColId / '*'))*
        // In CST this appears as nested QualifiedName nodes containing ColId
        for ( var child : nav.findAll("ColId")) {
            // Skip the first one we already added (compare by span position)
            if ( firstColId.isPresent() && child.span().equals(firstColId.unwrap().span())) {
            continue;}
            if ( parts.isEmpty() || !child.span().equals(parts.getLast().span())) {
            parts.add(extractIdentifier(child));}
        }
        // Deduplicate by span position
        var deduped = new ArrayList<Identifier>();
        for ( var part : parts) {
        if ( deduped.isEmpty() || !part.span().equals(deduped.getLast().span())) {
        deduped.add(part);}}
        return new QualifiedName(nav.span(), deduped.isEmpty()
                                            ? parts
                                            : deduped);
    }

    /// Extract a DataTypeName from a DataType CST node.
    public static DataTypeName extractDataType(CstNavigator nav) {
        // CST structure varies between interpreted and generated parser:
        //   Interpreted: DataType { ScalarType="boolean" (Token), ArrayType {} }
        //   Generated:   DataType { ScalarType { token="boolean" }, ArrayType {} }
        // Handle both by checking Token-level and NonTerminal-level ScalarType.
        var arrayType = nav.child("ArrayType");
        var arrayDims = arrayType.isPresent()
                        ? countArrayDimensions(arrayType.unwrap())
                        : 0;
        // Try ScalarType as a direct Token first (interpreted parser produces this for simple types)
        var scalarTokenText = nav.tokenText("ScalarType");
        if ( scalarTokenText.isPresent() && !scalarTokenText.unwrap().isEmpty()) {
            var scalarType = nav.child("ScalarType");
            // Check if ScalarType has TypeModifiers children (e.g., varchar(255))
            if ( scalarType.isPresent() && scalarType.unwrap().has("TypeModifiers")) {} else {
                var dt = DataTypeName.builtin(nav.span(),
                                              scalarTokenText.unwrap().trim());
                return arrayDims > 0
                       ? DataTypeName.array(dt, arrayDims)
                       : dt;
            }
        }
        // ScalarType is a NonTerminal — extract type name from children
        var scalarType = nav.child("ScalarType").or(nav);
        // Try getting first token text directly from ScalarType (generated parser: TOK[token]="integer")
        var directToken = scalarType.firstTokenText();
        if ( directToken.isPresent() && !directToken.unwrap().isEmpty() && !scalarType.has("TypeModifiers")) {
            var dt = DataTypeName.builtin(nav.span(),
                                          directToken.unwrap().trim());
            return arrayDims > 0
                   ? DataTypeName.array(dt, arrayDims)
                   : dt;
        }
        var baseName = extractScalarTypeName(scalarType);
        var modifiers = extractTypeModifiers(scalarType);
        if ( baseName.isEmpty()) {
            // Custom type: QualifiedTypeName
            var qualTypeName = scalarType.child("QualifiedTypeName");
            if ( qualTypeName.isPresent()) {
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

    /// Extract a list of column names from a ColumnList CST node.
    public static List<Identifier> extractColumnList(CstNavigator nav) {
        var colIds = nav.findAll("ColId");
        return colIds.stream().map(CstExtractor::extractIdentifier)
                            .toList();
    }

    // === Private helpers ===
    private static String extractScalarTypeName(CstNavigator scalarType) {
        // Look for known type rules as both Token and NonTerminal nodes
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
        // Check for Token nodes directly (produced by < > in grammar)
        for ( var rule : typeRules) {
            var text = scalarType.tokenText(rule);
            if ( text.isPresent() && !text.unwrap().isEmpty()) {
            return text.unwrap().trim();}
        }
        // Timestamp/Time types have nested structure under DateTimeType
        var dateTimeType = scalarType.child("DateTimeType");
        if ( dateTimeType.isPresent()) {
        return extractDateTimeTypeName(dateTimeType.unwrap());}
        // Direct timestamp/time checks (if not wrapped in DateTimeType)
        var tsType = scalarType.child("TimestampType");
        if ( tsType.isPresent()) {
        return extractTimestampTypeName(tsType.unwrap());}
        var timeType = scalarType.child("TimeType");
        if ( timeType.isPresent()) {
        return extractTimeTypeName(timeType.unwrap());}
        var intervalType = scalarType.child("IntervalType");
        if ( intervalType.isPresent()) {
        return "interval";}
        return "";
    }

    private static String extractDateTimeTypeName(CstNavigator dateTimeType) {
        var tsType = dateTimeType.child("TimestampType");
        if ( tsType.isPresent()) {
        return extractTimestampTypeName(tsType.unwrap());}
        var timeType = dateTimeType.child("TimeType");
        if ( timeType.isPresent()) {
        return extractTimeTypeName(timeType.unwrap());}
        var dateType = dateTimeType.tokenText("DateType");
        if ( dateType.isPresent()) {
        return "date";}
        var intervalType = dateTimeType.child("IntervalType");
        if ( intervalType.isPresent()) {
        return "interval";}
        return "";
    }

    private static String extractTimestampTypeName(CstNavigator nav) {
        var text = nav.firstTokenText().or("timestamp")
                                     .trim()
                                     .toLowerCase();
        if ( text.contains("timestamptz")) {
        return "timestamptz";}
        if ( nav.has("WithoutKW")) {
        return "timestamp without time zone";}
        if ( nav.has("WithKW")) {
        return "timestamp with time zone";}
        return "timestamp";
    }

    private static String extractTimeTypeName(CstNavigator nav) {
        var text = nav.firstTokenText().or("time")
                                     .trim()
                                     .toLowerCase();
        if ( text.contains("timetz")) {
        return "timetz";}
        if ( nav.has("WithoutKW")) {
        return "time without time zone";}
        if ( nav.has("WithKW")) {
        return "time with time zone";}
        return "time";
    }

    private static List<Integer> extractTypeModifiers(CstNavigator scalarType) {
        var modifiers = new ArrayList<Integer>();
        collectNumericLiterals(scalarType.node(), modifiers);
        return modifiers;
    }

    private static void collectNumericLiterals(CstNode node, List<Integer> result) {
        switch ( node) {
            case CstNode.Token tok when tok.ruleName().equals("NumericLiteral") -> {
                try {
                    result.add(Integer.parseInt(tok.text()));
                }




















                catch (NumberFormatException e) {}
            }
            case CstNode.NonTerminal nt -> {
                for ( var child : nt.children()) {
                collectNumericLiterals(child, result);}
            }
            default -> {}
        }
    }

    private static int countArrayDimensions(CstNavigator arrayType) {
        // Count all '[' terminals recursively in the ArrayType subtree
        return countBrackets(arrayType.node());
    }

    private static int countBrackets(CstNode node) {
        int count = 0;
        var text = switch (node) {case CstNode.Terminal t -> t.text();case CstNode.Token t -> t.text();default -> "";};
        if ( text.equals("[")) {
        count++;}
        if ( node instanceof CstNode.NonTerminal nt) {
        for ( var child : nt.children()) {
        count += countBrackets(child);}}
        return count;
    }
}

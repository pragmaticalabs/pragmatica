package org.pragmatica.jbct.parser.v6;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.pragmatica.peg.v6.token.TokenArray;
import org.pragmatica.peg.v6.cst.CstArray;
import org.pragmatica.peg.v6.cst.CstArrayBuilder;
import org.pragmatica.peg.v6.cst.ParseResult;
import org.pragmatica.peg.v6.diagnostic.Diagnostic;

public final class Java25ParserV6 {

    private static final String[] RULE_TABLE = {"CompilationUnit", "OrdinaryUnit", "PackageDecl", "ImportDecl", "ModuleDecl", "ModuleDirective", "RequiresDirective", "ExportsDirective", "OpensDirective", "UsesDirective", "ProvidesDirective", "TypeDecl", "TypeKind", "ClassDecl", "InterfaceDecl", "AnnotationDecl", "AnnotationBody", "AnnotationMember", "AnnotationElemDecl", "EnumDecl", "RecordDecl", "ImplementsClause", "PermitsClause", "TypeList", "TypeParams", "TypeParam", "ClassBody", "ClassMember", "Member", "InitializerBlock", "EnumBody", "EnumConsts", "EnumConst", "RecordComponents", "RecordComp", "RecordBody", "RecordMember", "CompactConstructor", "FieldDecl", "VarDecls", "VarDecl", "VarInit", "MethodDecl", "Params", "Param", "Throws", "ConstructorDecl", "Block", "BlockStmt", "LocalTypeDecl", "LocalVar", "LocalVarType", "Stmt", "ForCtrl", "ForInit", "LocalVarNoSemi", "ResourceSpec", "Resource", "Catch", "Finally", "SwitchBlock", "SwitchRule", "SwitchLabel", "CaseItem", "Pattern", "TypePattern", "RecordPattern", "PatternList", "Guard", "Expr", "Assignment", "Ternary", "LogOr", "LogAnd", "BitOr", "BitXor", "BitAnd", "Equality", "Relational", "Shift", "Additive", "Multiplicative", "Unary", "Postfix", "PostOp", "Primary", "TypeExpr", "Lambda", "LambdaParams", "LambdaParam", "Args", "ExprList", "Type", "RefType", "AnnotatedTypeName", "Dims", "ArrayType", "DimExprs", "TypeArgs", "TypeArg", "QualifiedName", "Annotation", "AnnotationValue", "AnnotationElem", "Literal", "ERROR", "_ROOT"};

    private static final int RULE_CompilationUnit_KIND = 0;
    private static final int RULE_OrdinaryUnit_KIND = 1;
    private static final int RULE_PackageDecl_KIND = 2;
    private static final int RULE_ImportDecl_KIND = 3;
    private static final int RULE_ModuleDecl_KIND = 4;
    private static final int RULE_ModuleDirective_KIND = 5;
    private static final int RULE_RequiresDirective_KIND = 6;
    private static final int RULE_ExportsDirective_KIND = 7;
    private static final int RULE_OpensDirective_KIND = 8;
    private static final int RULE_UsesDirective_KIND = 9;
    private static final int RULE_ProvidesDirective_KIND = 10;
    private static final int RULE_TypeDecl_KIND = 11;
    private static final int RULE_TypeKind_KIND = 12;
    private static final int RULE_ClassDecl_KIND = 13;
    private static final int RULE_InterfaceDecl_KIND = 14;
    private static final int RULE_AnnotationDecl_KIND = 15;
    private static final int RULE_AnnotationBody_KIND = 16;
    private static final int RULE_AnnotationMember_KIND = 17;
    private static final int RULE_AnnotationElemDecl_KIND = 18;
    private static final int RULE_EnumDecl_KIND = 19;
    private static final int RULE_RecordDecl_KIND = 20;
    private static final int RULE_ImplementsClause_KIND = 21;
    private static final int RULE_PermitsClause_KIND = 22;
    private static final int RULE_TypeList_KIND = 23;
    private static final int RULE_TypeParams_KIND = 24;
    private static final int RULE_TypeParam_KIND = 25;
    private static final int RULE_ClassBody_KIND = 26;
    private static final int RULE_ClassMember_KIND = 27;
    private static final int RULE_Member_KIND = 28;
    private static final int RULE_InitializerBlock_KIND = 29;
    private static final int RULE_EnumBody_KIND = 30;
    private static final int RULE_EnumConsts_KIND = 31;
    private static final int RULE_EnumConst_KIND = 32;
    private static final int RULE_RecordComponents_KIND = 33;
    private static final int RULE_RecordComp_KIND = 34;
    private static final int RULE_RecordBody_KIND = 35;
    private static final int RULE_RecordMember_KIND = 36;
    private static final int RULE_CompactConstructor_KIND = 37;
    private static final int RULE_FieldDecl_KIND = 38;
    private static final int RULE_VarDecls_KIND = 39;
    private static final int RULE_VarDecl_KIND = 40;
    private static final int RULE_VarInit_KIND = 41;
    private static final int RULE_MethodDecl_KIND = 42;
    private static final int RULE_Params_KIND = 43;
    private static final int RULE_Param_KIND = 44;
    private static final int RULE_Throws_KIND = 45;
    private static final int RULE_ConstructorDecl_KIND = 46;
    private static final int RULE_Block_KIND = 47;
    private static final int RULE_BlockStmt_KIND = 48;
    private static final int RULE_LocalTypeDecl_KIND = 49;
    private static final int RULE_LocalVar_KIND = 50;
    private static final int RULE_LocalVarType_KIND = 51;
    private static final int RULE_Stmt_KIND = 52;
    private static final int RULE_ForCtrl_KIND = 53;
    private static final int RULE_ForInit_KIND = 54;
    private static final int RULE_LocalVarNoSemi_KIND = 55;
    private static final int RULE_ResourceSpec_KIND = 56;
    private static final int RULE_Resource_KIND = 57;
    private static final int RULE_Catch_KIND = 58;
    private static final int RULE_Finally_KIND = 59;
    private static final int RULE_SwitchBlock_KIND = 60;
    private static final int RULE_SwitchRule_KIND = 61;
    private static final int RULE_SwitchLabel_KIND = 62;
    private static final int RULE_CaseItem_KIND = 63;
    private static final int RULE_Pattern_KIND = 64;
    private static final int RULE_TypePattern_KIND = 65;
    private static final int RULE_RecordPattern_KIND = 66;
    private static final int RULE_PatternList_KIND = 67;
    private static final int RULE_Guard_KIND = 68;
    private static final int RULE_Expr_KIND = 69;
    private static final int RULE_Assignment_KIND = 70;
    private static final int RULE_Ternary_KIND = 71;
    private static final int RULE_LogOr_KIND = 72;
    private static final int RULE_LogAnd_KIND = 73;
    private static final int RULE_BitOr_KIND = 74;
    private static final int RULE_BitXor_KIND = 75;
    private static final int RULE_BitAnd_KIND = 76;
    private static final int RULE_Equality_KIND = 77;
    private static final int RULE_Relational_KIND = 78;
    private static final int RULE_Shift_KIND = 79;
    private static final int RULE_Additive_KIND = 80;
    private static final int RULE_Multiplicative_KIND = 81;
    private static final int RULE_Unary_KIND = 82;
    private static final int RULE_Postfix_KIND = 83;
    private static final int RULE_PostOp_KIND = 84;
    private static final int RULE_Primary_KIND = 85;
    private static final int RULE_TypeExpr_KIND = 86;
    private static final int RULE_Lambda_KIND = 87;
    private static final int RULE_LambdaParams_KIND = 88;
    private static final int RULE_LambdaParam_KIND = 89;
    private static final int RULE_Args_KIND = 90;
    private static final int RULE_ExprList_KIND = 91;
    private static final int RULE_Type_KIND = 92;
    private static final int RULE_RefType_KIND = 93;
    private static final int RULE_AnnotatedTypeName_KIND = 94;
    private static final int RULE_Dims_KIND = 95;
    private static final int RULE_ArrayType_KIND = 96;
    private static final int RULE_DimExprs_KIND = 97;
    private static final int RULE_TypeArgs_KIND = 98;
    private static final int RULE_TypeArg_KIND = 99;
    private static final int RULE_QualifiedName_KIND = 100;
    private static final int RULE_Annotation_KIND = 101;
    private static final int RULE_AnnotationValue_KIND = 102;
    private static final int RULE_AnnotationElem_KIND = 103;
    private static final int RULE_Literal_KIND = 104;
    private static final int RULE_ERROR_KIND = 105;
    private static final int RULE_ROOT_KIND = 106;

    private static final int KIND_INLINE_PACKAGE = 44;
    private static final int KIND_INLINE__SEMI = 89;
    private static final int KIND_INLINE_IMPORT = 49;
    private static final int KIND_INLINE_MODULE = 50;
    private static final int KIND_INLINE_STATIC = 51;
    private static final int KIND_INLINE__DOT = 90;
    private static final int KIND_INLINE__STAR = 91;
    private static final int KIND_INLINE_OPEN = 58;
    private static final int KIND_INLINE__LBRACE = 92;
    private static final int KIND_INLINE__RBRACE = 93;
    private static final int KIND_INLINE_REQUIRES = 42;
    private static final int KIND_INLINE_TRANSITIVE = 38;
    private static final int KIND_INLINE_EXPORTS = 45;
    private static final int KIND_INLINE_TO = 70;
    private static final int KIND_INLINE__COMMA = 94;
    private static final int KIND_INLINE_OPENS = 53;
    private static final int KIND_INLINE_USES = 59;
    private static final int KIND_INLINE_PROVIDES = 43;
    private static final int KIND_INLINE_WITH = 60;
    private static final int KIND_INLINE_DEFAULT = 47;
    private static final int KIND_INLINE_SYNCHRONIZED = 122;
    private static final int KIND_INLINE_PUBLIC = 140;
    private static final int KIND_INLINE_PROTECTED = 141;
    private static final int KIND_INLINE_PRIVATE = 142;
    private static final int KIND_INLINE_FINAL = 143;
    private static final int KIND_INLINE_ABSTRACT = 144;
    private static final int KIND_INLINE_NATIVE = 145;
    private static final int KIND_INLINE_TRANSIENT = 146;
    private static final int KIND_INLINE_VOLATILE = 147;
    private static final int KIND_INLINE_STRICTFP = 148;
    private static final int KIND_INLINE_SEALED = 149;
    private static final int KIND_INLINE_NON_MINUSSEALED = 150;
    private static final int KIND_INLINE_CLASS = 55;
    private static final int KIND_IDENTIFIER = 32;
    private static final int KIND_RECORDKW = 8;
    private static final int KIND_YIELDKW = 21;
    private static final int KIND_WHENKW = 24;
    private static final int KIND_INLINE_PERMITS = 48;
    private static final int KIND_INLINE_WHEN = 64;
    private static final int KIND_INLINE_VAR = 68;
    private static final int KIND_INLINE__ = 104;
    private static final int KIND_INLINE_RECORD = 116;
    private static final int KIND_INLINE_YIELD = 128;
    private static final int KIND_INLINE_EXTENDS = 46;
    private static final int KIND_INLINE_INTERFACE = 41;
    private static final int KIND_INLINE__AT = 95;
    private static final int KIND_INLINE__LPAREN = 96;
    private static final int KIND_INLINE__RPAREN = 97;
    private static final int KIND_INLINE_ENUM = 115;
    private static final int KIND_INLINE_IMPLEMENTS = 39;
    private static final int KIND_INLINE__LT = 98;
    private static final int KIND_INLINE__GT = 99;
    private static final int KIND_INLINE__AMP = 100;
    private static final int KIND_INLINE__EQ = 101;
    private static final int KIND_INLINE__DOT_DOT_DOT = 67;
    private static final int KIND_INLINE_THROWS = 52;
    private static final int KIND_INLINE_IF = 117;
    private static final int KIND_INLINE_ELSE = 61;
    private static final int KIND_INLINE_WHILE = 54;
    private static final int KIND_INLINE_FOR = 118;
    private static final int KIND_INLINE_DO = 119;
    private static final int KIND_INLINE_TRY = 120;
    private static final int KIND_INLINE_SWITCH = 121;
    private static final int KIND_INLINE_RETURN = 123;
    private static final int KIND_INLINE_THROW = 124;
    private static final int KIND_INLINE_BREAK = 125;
    private static final int KIND_INLINE_CONTINUE = 126;
    private static final int KIND_INLINE_ASSERT = 127;
    private static final int KIND_INLINE__COLON = 102;
    private static final int KIND_INLINE_CATCH = 129;
    private static final int KIND_INLINE__PIPE = 103;
    private static final int KIND_INLINE_FINALLY = 130;
    private static final int KIND_INLINE__MINUS_GT = 71;
    private static final int KIND_INLINE_CASE = 62;
    private static final int KIND_INLINE_NULL = 63;
    private static final int KIND_URSHIFTASSIGN = 28;
    private static final int KIND_RSHIFTASSIGN = 29;
    private static final int KIND_LSHIFTASSIGN = 30;
    private static final int KIND_INLINE__PLUS_EQ = 72;
    private static final int KIND_INLINE__MINUS_EQ = 73;
    private static final int KIND_INLINE__STAR_EQ = 74;
    private static final int KIND_INLINE__SLASH_EQ = 75;
    private static final int KIND_INLINE__PERCENT_EQ = 76;
    private static final int KIND_INLINE__AMP_EQ = 77;
    private static final int KIND_INLINE__PIPE_EQ = 78;
    private static final int KIND_INLINE__CARET_EQ = 79;
    private static final int KIND_INLINE__QMARK = 105;
    private static final int KIND_INLINE__PIPE_PIPE = 80;
    private static final int KIND_INLINE__AMP_AMP = 81;
    private static final int KIND_INLINE__CARET = 106;
    private static final int KIND_INLINE__EQ_EQ = 82;
    private static final int KIND_INLINE__BANG_EQ = 83;
    private static final int KIND_INLINE__LT_EQ = 84;
    private static final int KIND_INLINE__GT_EQ = 85;
    private static final int KIND_INLINE_INSTANCEOF = 40;
    private static final int KIND_URSHIFT = 25;
    private static final int KIND_LSHIFT = 27;
    private static final int KIND_RSHIFT = 26;
    private static final int KIND_INLINE__PLUS = 107;
    private static final int KIND_INLINE__MINUS = 108;
    private static final int KIND_INLINE__SLASH = 109;
    private static final int KIND_INLINE__PERCENT = 110;
    private static final int KIND_INLINE__PLUS_PLUS = 86;
    private static final int KIND_INLINE__MINUS_MINUS = 87;
    private static final int KIND_INLINE__BANG = 111;
    private static final int KIND_INLINE__TILDE = 112;
    private static final int KIND_INLINE_THIS = 65;
    private static final int KIND_INLINE__LBRACK = 113;
    private static final int KIND_INLINE__RBRACK = 114;
    private static final int KIND_INLINE__COLON_COLON = 88;
    private static final int KIND_INLINE_NEW = 69;
    private static final int KIND_INLINE_SUPER = 56;
    private static final int KIND_INLINE_BOOLEAN = 131;
    private static final int KIND_INLINE_BYTE = 132;
    private static final int KIND_INLINE_SHORT = 133;
    private static final int KIND_INLINE_INT = 134;
    private static final int KIND_INLINE_LONG = 135;
    private static final int KIND_INLINE_FLOAT = 136;
    private static final int KIND_INLINE_DOUBLE = 137;
    private static final int KIND_INLINE_CHAR = 138;
    private static final int KIND_INLINE_VOID = 139;
    private static final int KIND_INLINE_TRUE = 66;
    private static final int KIND_INLINE_FALSE = 57;
    private static final int KIND_CHARLIT = 34;
    private static final int KIND_STRINGLIT = 35;
    private static final int KIND_NUMLIT = 36;

    private static final int[] DEFAULT_SYNC = new int[] {89, 93, 94, 97, 114};

    private static final int[] ALIAS_MODIFIER = new int[] {47, 51, 122, 140, 141, 142, 143, 144, 145, 146, 147, 148, 149, 150};
    private static final int[] ALIAS_PRIMTYPE = new int[] {131, 132, 133, 134, 135, 136, 137, 138, 139};

    private static final int[] IDFALL_IDENTIFIER = new int[] {8, 21, 24, 32, 38, 42, 43, 45, 48, 50, 53, 58, 59, 60, 64, 68, 70, 104, 116, 128, 149};

    private final TokenArray tokens;
    private final CstArrayBuilder cst;
    private final List<Diagnostic> diagnostics;
    private int pos;
    private int splitGt;   // phantom > tokens remaining from a split >> or >>> token in type-arg context
    private int errorPos;
    private String expected;
    private int found;
    private int lastFailedRuleKind;
    private final java.util.Map<String, long[]> captures = new java.util.HashMap<>();
    private final java.util.ArrayDeque<java.util.Map<String, long[]>> captureScopeStack = new java.util.ArrayDeque<>();
    private final int maxDiagnostics;

    private Java25ParserV6(TokenArray tokens, int maxDiagnostics) {
        this.tokens = tokens;
        this.cst = new CstArrayBuilder(tokens.input(), tokens, RULE_TABLE);
        this.diagnostics = new ArrayList<>();
        this.pos = tokens.nextNonTrivia(0);
        this.errorPos = -1;
        this.expected = null;
        this.found = -1;
        this.lastFailedRuleKind = -1;
        this.maxDiagnostics = maxDiagnostics < 0 ? Integer.MAX_VALUE : maxDiagnostics;
    }

    public static ParseResult parse(TokenArray tokens) {
        return parse(tokens, Integer.MAX_VALUE);
    }

    public static ParseResult parse(TokenArray tokens, int maxDiagnostics) {
        Java25ParserV6 p = new Java25ParserV6(tokens, maxDiagnostics);
        int rootIdx = p.parseWithRecovery();
        CstArray cstArr = p.cst.build(rootIdx);
        return new ParseResult(cstArr, p.diagnostics);
    }

    public static ParseResult parseRuleFrom(TokenArray tokens, int fromTokenIdx, int ruleKind) {
        Java25ParserV6 p = new Java25ParserV6(tokens, Integer.MAX_VALUE);
        p.pos = tokens.nextNonTrivia(fromTokenIdx);
        int rootFirstTok = p.pos < tokens.count() ? p.pos : (tokens.count() == 0 ? 0 : tokens.count() - 1);
        int rootIdx = p.cst.beginNode(RULE_ROOT_KIND, rootFirstTok, -1);
        boolean ok = parseByKind(p, ruleKind, rootIdx);
        if (!ok) {
            // Mirror the full-parse recovery contract: emit an Error node
            // covering the failing token plus a diagnostic.
            int failedTok = p.pos < tokens.count() ? p.pos : tokens.count() - 1;
            int diagOffset = failedTok >= 0 && failedTok < tokens.count()
                ? tokens.startAt(failedTok) : tokens.input().length();
            int diagLen = failedTok >= 0 && failedTok < tokens.count()
                ? Math.max(1, tokens.endAt(failedTok) - tokens.startAt(failedTok)) : 1;
            String foundText = failedTok >= 0 && failedTok < tokens.count()
                ? String.valueOf(tokens.textAt(failedTok)) : "<end-of-input>";
            String expectedText = p.expected != null ? p.expected : "valid input";
            p.diagnostics.add(Diagnostic.error(diagOffset, diagLen,
                "syntax error", expectedText, foundText));
        }
        int rootLastTok;
        if (tokens.count() == 0) {
            rootLastTok = 0;
        } else if (p.pos > rootFirstTok && p.pos <= tokens.count()) {
            rootLastTok = p.pos - 1;
        } else {
            rootLastTok = rootFirstTok;
        }
        if (rootLastTok < rootFirstTok) rootLastTok = rootFirstTok;
        p.cst.endNode(rootIdx, rootLastTok);
        CstArray cstArr = p.cst.build(rootIdx);
        return new ParseResult(cstArr, p.diagnostics);
    }

    private static boolean parseByKind(Java25ParserV6 p, int kind, int parent) {
        switch (kind) {
            case RULE_CompilationUnit_KIND: return p.parseCompilationUnit(parent);
            case RULE_OrdinaryUnit_KIND: return p.parseOrdinaryUnit(parent);
            case RULE_PackageDecl_KIND: return p.parsePackageDecl(parent);
            case RULE_ImportDecl_KIND: return p.parseImportDecl(parent);
            case RULE_ModuleDecl_KIND: return p.parseModuleDecl(parent);
            case RULE_ModuleDirective_KIND: return p.parseModuleDirective(parent);
            case RULE_RequiresDirective_KIND: return p.parseRequiresDirective(parent);
            case RULE_ExportsDirective_KIND: return p.parseExportsDirective(parent);
            case RULE_OpensDirective_KIND: return p.parseOpensDirective(parent);
            case RULE_UsesDirective_KIND: return p.parseUsesDirective(parent);
            case RULE_ProvidesDirective_KIND: return p.parseProvidesDirective(parent);
            case RULE_TypeDecl_KIND: return p.parseTypeDecl(parent);
            case RULE_TypeKind_KIND: return p.parseTypeKind(parent);
            case RULE_ClassDecl_KIND: return p.parseClassDecl(parent);
            case RULE_InterfaceDecl_KIND: return p.parseInterfaceDecl(parent);
            case RULE_AnnotationDecl_KIND: return p.parseAnnotationDecl(parent);
            case RULE_AnnotationBody_KIND: return p.parseAnnotationBody(parent);
            case RULE_AnnotationMember_KIND: return p.parseAnnotationMember(parent);
            case RULE_AnnotationElemDecl_KIND: return p.parseAnnotationElemDecl(parent);
            case RULE_EnumDecl_KIND: return p.parseEnumDecl(parent);
            case RULE_RecordDecl_KIND: return p.parseRecordDecl(parent);
            case RULE_ImplementsClause_KIND: return p.parseImplementsClause(parent);
            case RULE_PermitsClause_KIND: return p.parsePermitsClause(parent);
            case RULE_TypeList_KIND: return p.parseTypeList(parent);
            case RULE_TypeParams_KIND: return p.parseTypeParams(parent);
            case RULE_TypeParam_KIND: return p.parseTypeParam(parent);
            case RULE_ClassBody_KIND: return p.parseClassBody(parent);
            case RULE_ClassMember_KIND: return p.parseClassMember(parent);
            case RULE_Member_KIND: return p.parseMember(parent);
            case RULE_InitializerBlock_KIND: return p.parseInitializerBlock(parent);
            case RULE_EnumBody_KIND: return p.parseEnumBody(parent);
            case RULE_EnumConsts_KIND: return p.parseEnumConsts(parent);
            case RULE_EnumConst_KIND: return p.parseEnumConst(parent);
            case RULE_RecordComponents_KIND: return p.parseRecordComponents(parent);
            case RULE_RecordComp_KIND: return p.parseRecordComp(parent);
            case RULE_RecordBody_KIND: return p.parseRecordBody(parent);
            case RULE_RecordMember_KIND: return p.parseRecordMember(parent);
            case RULE_CompactConstructor_KIND: return p.parseCompactConstructor(parent);
            case RULE_FieldDecl_KIND: return p.parseFieldDecl(parent);
            case RULE_VarDecls_KIND: return p.parseVarDecls(parent);
            case RULE_VarDecl_KIND: return p.parseVarDecl(parent);
            case RULE_VarInit_KIND: return p.parseVarInit(parent);
            case RULE_MethodDecl_KIND: return p.parseMethodDecl(parent);
            case RULE_Params_KIND: return p.parseParams(parent);
            case RULE_Param_KIND: return p.parseParam(parent);
            case RULE_Throws_KIND: return p.parseThrows(parent);
            case RULE_ConstructorDecl_KIND: return p.parseConstructorDecl(parent);
            case RULE_Block_KIND: return p.parseBlock(parent);
            case RULE_BlockStmt_KIND: return p.parseBlockStmt(parent);
            case RULE_LocalTypeDecl_KIND: return p.parseLocalTypeDecl(parent);
            case RULE_LocalVar_KIND: return p.parseLocalVar(parent);
            case RULE_LocalVarType_KIND: return p.parseLocalVarType(parent);
            case RULE_Stmt_KIND: return p.parseStmt(parent);
            case RULE_ForCtrl_KIND: return p.parseForCtrl(parent);
            case RULE_ForInit_KIND: return p.parseForInit(parent);
            case RULE_LocalVarNoSemi_KIND: return p.parseLocalVarNoSemi(parent);
            case RULE_ResourceSpec_KIND: return p.parseResourceSpec(parent);
            case RULE_Resource_KIND: return p.parseResource(parent);
            case RULE_Catch_KIND: return p.parseCatch(parent);
            case RULE_Finally_KIND: return p.parseFinally(parent);
            case RULE_SwitchBlock_KIND: return p.parseSwitchBlock(parent);
            case RULE_SwitchRule_KIND: return p.parseSwitchRule(parent);
            case RULE_SwitchLabel_KIND: return p.parseSwitchLabel(parent);
            case RULE_CaseItem_KIND: return p.parseCaseItem(parent);
            case RULE_Pattern_KIND: return p.parsePattern(parent);
            case RULE_TypePattern_KIND: return p.parseTypePattern(parent);
            case RULE_RecordPattern_KIND: return p.parseRecordPattern(parent);
            case RULE_PatternList_KIND: return p.parsePatternList(parent);
            case RULE_Guard_KIND: return p.parseGuard(parent);
            case RULE_Expr_KIND: return p.parseExpr(parent);
            case RULE_Assignment_KIND: return p.parseAssignment(parent);
            case RULE_Ternary_KIND: return p.parseTernary(parent);
            case RULE_LogOr_KIND: return p.parseLogOr(parent);
            case RULE_LogAnd_KIND: return p.parseLogAnd(parent);
            case RULE_BitOr_KIND: return p.parseBitOr(parent);
            case RULE_BitXor_KIND: return p.parseBitXor(parent);
            case RULE_BitAnd_KIND: return p.parseBitAnd(parent);
            case RULE_Equality_KIND: return p.parseEquality(parent);
            case RULE_Relational_KIND: return p.parseRelational(parent);
            case RULE_Shift_KIND: return p.parseShift(parent);
            case RULE_Additive_KIND: return p.parseAdditive(parent);
            case RULE_Multiplicative_KIND: return p.parseMultiplicative(parent);
            case RULE_Unary_KIND: return p.parseUnary(parent);
            case RULE_Postfix_KIND: return p.parsePostfix(parent);
            case RULE_PostOp_KIND: return p.parsePostOp(parent);
            case RULE_Primary_KIND: return p.parsePrimary(parent);
            case RULE_TypeExpr_KIND: return p.parseTypeExpr(parent);
            case RULE_Lambda_KIND: return p.parseLambda(parent);
            case RULE_LambdaParams_KIND: return p.parseLambdaParams(parent);
            case RULE_LambdaParam_KIND: return p.parseLambdaParam(parent);
            case RULE_Args_KIND: return p.parseArgs(parent);
            case RULE_ExprList_KIND: return p.parseExprList(parent);
            case RULE_Type_KIND: return p.parseType(parent);
            case RULE_RefType_KIND: return p.parseRefType(parent);
            case RULE_AnnotatedTypeName_KIND: return p.parseAnnotatedTypeName(parent);
            case RULE_Dims_KIND: return p.parseDims(parent);
            case RULE_ArrayType_KIND: return p.parseArrayType(parent);
            case RULE_DimExprs_KIND: return p.parseDimExprs(parent);
            case RULE_TypeArgs_KIND: return p.parseTypeArgs(parent);
            case RULE_TypeArg_KIND: return p.parseTypeArg(parent);
            case RULE_QualifiedName_KIND: return p.parseQualifiedName(parent);
            case RULE_Annotation_KIND: return p.parseAnnotation(parent);
            case RULE_AnnotationValue_KIND: return p.parseAnnotationValue(parent);
            case RULE_AnnotationElem_KIND: return p.parseAnnotationElem(parent);
            case RULE_Literal_KIND: return p.parseLiteral(parent);
            default: return false;
        }
    }

    public static Map<String, Integer> ruleKinds() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("CompilationUnit", RULE_CompilationUnit_KIND);
        m.put("OrdinaryUnit", RULE_OrdinaryUnit_KIND);
        m.put("PackageDecl", RULE_PackageDecl_KIND);
        m.put("ImportDecl", RULE_ImportDecl_KIND);
        m.put("ModuleDecl", RULE_ModuleDecl_KIND);
        m.put("ModuleDirective", RULE_ModuleDirective_KIND);
        m.put("RequiresDirective", RULE_RequiresDirective_KIND);
        m.put("ExportsDirective", RULE_ExportsDirective_KIND);
        m.put("OpensDirective", RULE_OpensDirective_KIND);
        m.put("UsesDirective", RULE_UsesDirective_KIND);
        m.put("ProvidesDirective", RULE_ProvidesDirective_KIND);
        m.put("TypeDecl", RULE_TypeDecl_KIND);
        m.put("TypeKind", RULE_TypeKind_KIND);
        m.put("ClassDecl", RULE_ClassDecl_KIND);
        m.put("InterfaceDecl", RULE_InterfaceDecl_KIND);
        m.put("AnnotationDecl", RULE_AnnotationDecl_KIND);
        m.put("AnnotationBody", RULE_AnnotationBody_KIND);
        m.put("AnnotationMember", RULE_AnnotationMember_KIND);
        m.put("AnnotationElemDecl", RULE_AnnotationElemDecl_KIND);
        m.put("EnumDecl", RULE_EnumDecl_KIND);
        m.put("RecordDecl", RULE_RecordDecl_KIND);
        m.put("ImplementsClause", RULE_ImplementsClause_KIND);
        m.put("PermitsClause", RULE_PermitsClause_KIND);
        m.put("TypeList", RULE_TypeList_KIND);
        m.put("TypeParams", RULE_TypeParams_KIND);
        m.put("TypeParam", RULE_TypeParam_KIND);
        m.put("ClassBody", RULE_ClassBody_KIND);
        m.put("ClassMember", RULE_ClassMember_KIND);
        m.put("Member", RULE_Member_KIND);
        m.put("InitializerBlock", RULE_InitializerBlock_KIND);
        m.put("EnumBody", RULE_EnumBody_KIND);
        m.put("EnumConsts", RULE_EnumConsts_KIND);
        m.put("EnumConst", RULE_EnumConst_KIND);
        m.put("RecordComponents", RULE_RecordComponents_KIND);
        m.put("RecordComp", RULE_RecordComp_KIND);
        m.put("RecordBody", RULE_RecordBody_KIND);
        m.put("RecordMember", RULE_RecordMember_KIND);
        m.put("CompactConstructor", RULE_CompactConstructor_KIND);
        m.put("FieldDecl", RULE_FieldDecl_KIND);
        m.put("VarDecls", RULE_VarDecls_KIND);
        m.put("VarDecl", RULE_VarDecl_KIND);
        m.put("VarInit", RULE_VarInit_KIND);
        m.put("MethodDecl", RULE_MethodDecl_KIND);
        m.put("Params", RULE_Params_KIND);
        m.put("Param", RULE_Param_KIND);
        m.put("Throws", RULE_Throws_KIND);
        m.put("ConstructorDecl", RULE_ConstructorDecl_KIND);
        m.put("Block", RULE_Block_KIND);
        m.put("BlockStmt", RULE_BlockStmt_KIND);
        m.put("LocalTypeDecl", RULE_LocalTypeDecl_KIND);
        m.put("LocalVar", RULE_LocalVar_KIND);
        m.put("LocalVarType", RULE_LocalVarType_KIND);
        m.put("Stmt", RULE_Stmt_KIND);
        m.put("ForCtrl", RULE_ForCtrl_KIND);
        m.put("ForInit", RULE_ForInit_KIND);
        m.put("LocalVarNoSemi", RULE_LocalVarNoSemi_KIND);
        m.put("ResourceSpec", RULE_ResourceSpec_KIND);
        m.put("Resource", RULE_Resource_KIND);
        m.put("Catch", RULE_Catch_KIND);
        m.put("Finally", RULE_Finally_KIND);
        m.put("SwitchBlock", RULE_SwitchBlock_KIND);
        m.put("SwitchRule", RULE_SwitchRule_KIND);
        m.put("SwitchLabel", RULE_SwitchLabel_KIND);
        m.put("CaseItem", RULE_CaseItem_KIND);
        m.put("Pattern", RULE_Pattern_KIND);
        m.put("TypePattern", RULE_TypePattern_KIND);
        m.put("RecordPattern", RULE_RecordPattern_KIND);
        m.put("PatternList", RULE_PatternList_KIND);
        m.put("Guard", RULE_Guard_KIND);
        m.put("Expr", RULE_Expr_KIND);
        m.put("Assignment", RULE_Assignment_KIND);
        m.put("Ternary", RULE_Ternary_KIND);
        m.put("LogOr", RULE_LogOr_KIND);
        m.put("LogAnd", RULE_LogAnd_KIND);
        m.put("BitOr", RULE_BitOr_KIND);
        m.put("BitXor", RULE_BitXor_KIND);
        m.put("BitAnd", RULE_BitAnd_KIND);
        m.put("Equality", RULE_Equality_KIND);
        m.put("Relational", RULE_Relational_KIND);
        m.put("Shift", RULE_Shift_KIND);
        m.put("Additive", RULE_Additive_KIND);
        m.put("Multiplicative", RULE_Multiplicative_KIND);
        m.put("Unary", RULE_Unary_KIND);
        m.put("Postfix", RULE_Postfix_KIND);
        m.put("PostOp", RULE_PostOp_KIND);
        m.put("Primary", RULE_Primary_KIND);
        m.put("TypeExpr", RULE_TypeExpr_KIND);
        m.put("Lambda", RULE_Lambda_KIND);
        m.put("LambdaParams", RULE_LambdaParams_KIND);
        m.put("LambdaParam", RULE_LambdaParam_KIND);
        m.put("Args", RULE_Args_KIND);
        m.put("ExprList", RULE_ExprList_KIND);
        m.put("Type", RULE_Type_KIND);
        m.put("RefType", RULE_RefType_KIND);
        m.put("AnnotatedTypeName", RULE_AnnotatedTypeName_KIND);
        m.put("Dims", RULE_Dims_KIND);
        m.put("ArrayType", RULE_ArrayType_KIND);
        m.put("DimExprs", RULE_DimExprs_KIND);
        m.put("TypeArgs", RULE_TypeArgs_KIND);
        m.put("TypeArg", RULE_TypeArg_KIND);
        m.put("QualifiedName", RULE_QualifiedName_KIND);
        m.put("Annotation", RULE_Annotation_KIND);
        m.put("AnnotationValue", RULE_AnnotationValue_KIND);
        m.put("AnnotationElem", RULE_AnnotationElem_KIND);
        m.put("Literal", RULE_Literal_KIND);
        return m;
    }

    private int parseWithRecovery() {
        // Synthetic root spanning the whole token stream. All start-rule
        // attempts and recovery Error nodes attach to it as children.
        int rootFirstTok = pos < tokens.count() ? pos : 0;
        int root = cst.beginNode(RULE_ROOT_KIND, rootFirstTok, -1);
        boolean firstAttempt = true;
        while (true) {
            // Skip any leading trivia at the current position before deciding
            // whether anything remains to parse.
            while (pos < tokens.count() && tokens.isTrivia(pos)) pos++;
            if (pos >= tokens.count()) {
                if (firstAttempt && diagnostics.size() < maxDiagnostics) {
                    // Empty / all-trivia input — record a diagnostic so callers
                    // know the parse couldn't even attempt the start rule.
                    int off = tokens.count() == 0 ? 0 : tokens.startAt(0);
                    diagnostics.add(Diagnostic.error(off, 1,
                        "empty input", "start of CompilationUnit", "<end-of-input>"));
                }
                break;
            }
            firstAttempt = false;
            int beforeNodes = cst.currentNodeCount();
            int beforePos = pos;
            // Phase 0.6.0-perf — reset furthest-failure tracker before each
            // attempt so the recorded diagnostic reflects this iteration.
            errorPos = -1;
            expected = null;
            found = -1;
            lastFailedRuleKind = -1;
            boolean parsedOk = parseCompilationUnit(root);
            if (!parsedOk) {
                // Roll back any partial CST built by the failed start-rule call.
                cst.truncate(beforeNodes);
                emitRecoveryError(root, beforePos);
            } else if (pos == beforePos) {
                // Start rule succeeded without consuming any token. Force
                // progress by skipping one token under an Error node, else we
                // loop forever on the same position.
                emitForcedAdvanceError(root, beforePos);
            }
            if (!parsedOk && pos == beforePos) {
                // Recovery couldn't move past the failing token (no sync, no EOF
                // beyond, etc.); break to avoid an infinite loop.
                break;
            }
            if (diagnostics.size() >= maxDiagnostics) {
                break;
            }
            // Loop to either consume more input via another start-rule call or
            // to record additional trailing-input diagnostics.
        }
        // Close the synthetic root over [rootFirstTok, lastConsumedTok]. If
        // no token was consumed (empty input) the span is a degenerate
        // [rootFirstTok, rootFirstTok] which the builder accepts.
        int rootLastTok;
        if (tokens.count() == 0) {
            rootLastTok = 0;
        } else if (pos > 0 && pos <= tokens.count()) {
            rootLastTok = pos - 1;
        } else {
            rootLastTok = rootFirstTok;
        }
        if (rootLastTok < rootFirstTok) rootLastTok = rootFirstTok;
        cst.endNode(root, rootLastTok);
        return root;
    }

    private void emitRecoveryError(int parent, int beforePos) {
        int failedTok = pos < tokens.count() ? pos : tokens.count() - 1;
        int syncTok = nextSyncToken(pos);
        int skipFirst = failedTok >= 0 ? failedTok : 0;
        int skipLast;
        int newPos;
        if (syncTok < tokens.count()) {
            skipLast = syncTok;
            newPos = tokens.nextNonTrivia(syncTok + 1);
        } else {
            skipLast = tokens.count() - 1;
            newPos = tokens.count();
        }
        if (skipLast < skipFirst) skipLast = skipFirst;
        if (skipFirst >= 0 && skipFirst < tokens.count()) {
            int errIdx = cst.beginNode(RULE_ERROR_KIND, skipFirst, parent);
            cst.endNode(errIdx, skipLast);
            cst.setFlag(errIdx, CstArray.FLAG_ERROR);
        }
        int diagOffset;
        if (errorPos >= 0) {
            diagOffset = errorPos;
        } else if (failedTok >= 0 && failedTok < tokens.count()) {
            diagOffset = tokens.startAt(failedTok);
        } else {
            diagOffset = tokens.input().length();
        }
        int diagLen;
        if (skipFirst >= 0 && skipFirst < tokens.count() && skipLast < tokens.count()) {
            diagLen = tokens.endAt(skipLast) - tokens.startAt(skipFirst);
            if (diagLen < 1) diagLen = 1;
        } else {
            diagLen = 1;
        }
        String foundText;
        if (failedTok >= 0 && failedTok < tokens.count()) {
            foundText = String.valueOf(tokens.textAt(failedTok));
        } else {
            foundText = "<end-of-input>";
        }
        String expectedText = expected != null ? expected : "valid input";
        if (diagnostics.size() < maxDiagnostics) {
            diagnostics.add(Diagnostic.error(diagOffset, diagLen,
                "syntax error", expectedText, foundText));
        }
        pos = newPos;
    }

    private void emitForcedAdvanceError(int parent, int atPos) {
        if (atPos < 0 || atPos >= tokens.count()) return;
        int errIdx = cst.beginNode(RULE_ERROR_KIND, atPos, parent);
        cst.endNode(errIdx, atPos);
        cst.setFlag(errIdx, CstArray.FLAG_ERROR);
        int diagOffset = tokens.startAt(atPos);
        int diagLen = tokens.endAt(atPos) - tokens.startAt(atPos);
        if (diagLen < 1) diagLen = 1;
        String foundText = String.valueOf(tokens.textAt(atPos));
        if (diagnostics.size() < maxDiagnostics) {
            diagnostics.add(Diagnostic.error(diagOffset, diagLen,
                "trailing input not consumed", "end of input", foundText));
        }
        pos = tokens.nextNonTrivia(atPos + 1);
    }

    private int nextSyncToken(int from) {
        int[] sync = syncForRule(lastFailedRuleKind);
        int i = from;
        int n = tokens.count();
        while (i < n) {
            if (tokens.isTrivia(i)) { i++; continue; }
            if (java.util.Arrays.binarySearch(sync, tokens.kindAt(i)) >= 0) {
                return i;
            }
            i++;
        }
        return n;
    }

    private int[] syncForRule(int ruleKind) {
        return DEFAULT_SYNC;
    }

    private void advance() {
        pos = tokens.nextNonTrivia(pos + 1);
    }

    // Consume one closing '>' in a generic type context.
    // Handles the Java angle-bracket ambiguity: >> and >>> are lexed as single
    // tokens but must be split into individual '>' tokens when closing nested
    // type-argument lists.  Returns true and advances state on success, false
    // (without modifying state) if neither a '>' nor a splittable >> / >>> is present.
    private boolean advanceGt() {
        if (splitGt > 0) {
            splitGt--;
            return true;  // consume phantom '>' — pos stays, no token array advance
        }
        int k = peek();
        if (k == KIND_INLINE__GT) {
            advance();
            return true;
        }
        if (k == KIND_RSHIFT) {
            advance();
            splitGt = 1;  // one phantom '>' left over from >>
            return true;
        }
        if (k == KIND_URSHIFT) {
            advance();
            splitGt = 2;  // two phantom '>' left over from >>>
            return true;
        }
        return false;
    }

    // Consume '<<' in an expression shift context.
    // The lexer emits '<<' as two consecutive INLINE__LT tokens rather than a
    // single KIND_LSHIFT token.  Returns true and advances past both tokens on
    // success, false (without modifying state) if no left-shift is present.
    private boolean advanceLShift() {
        int k = peek();
        if (k == KIND_LSHIFT) {
            advance();
            return true;
        }
        if (k == KIND_INLINE__LT) {
            int nextPos = tokens.nextNonTrivia(pos + 1);
            if (nextPos < tokens.count() && tokens.kindAt(nextPos) == KIND_INLINE__LT) {
                advance();  // consume first <
                advance();  // consume second <
                return true;
            }
        }
        return false;
    }

    // Consume '<<=' in an assignment context.
    // The lexer emits '<<=' as INLINE__LT + INLINE__LT_EQ rather than a
    // single KIND_LSHIFTASSIGN token.
    private boolean advanceLShiftAssign() {
        int k = peek();
        if (k == KIND_LSHIFTASSIGN) {
            advance();
            return true;
        }
        if (k == KIND_INLINE__LT) {
            int nextPos = tokens.nextNonTrivia(pos + 1);
            if (nextPos < tokens.count() && tokens.kindAt(nextPos) == KIND_INLINE__LT_EQ) {
                advance();  // consume <
                advance();  // consume <=
                return true;
            }
        }
        return false;
    }

    private int peek() {
        return pos < tokens.count() ? tokens.kindAt(pos) : -1;
    }

    private boolean fail(String expectedText, int ruleKind) {
        int offset = pos < tokens.count() ? tokens.startAt(pos) : tokens.input().length();
        if (offset >= errorPos) {
            errorPos = offset;
            expected = expectedText;
            found = peek();
            lastFailedRuleKind = ruleKind;
        }
        return false;
    }

    private boolean parseCompilationUnit(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_CompilationUnit_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseModuleDecl(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseOrdinaryUnit(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_CompilationUnit_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseOrdinaryUnit(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_OrdinaryUnit_KIND, firstTok, parent);
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parsePackageDecl(self)) { break; }
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        // zero-or-more: rep_1
        while (true) {
            int savedPos_rep_1 = pos;
            int savedNodes_rep_1 = cst.currentNodeCount();
            boolean iterOk_rep_1 = false;
            do {
                if (!parseImportDecl(self)) { break; }
                iterOk_rep_1 = true;
            } while (false);
            if (!iterOk_rep_1) {
                pos = savedPos_rep_1;
                cst.truncate(savedNodes_rep_1);
                break;
            }
            if (pos == savedPos_rep_1) break; // guard against infinite loops on zero-width matches
        }
        // zero-or-more: rep_2
        while (true) {
            int savedPos_rep_2 = pos;
            int savedNodes_rep_2 = cst.currentNodeCount();
            boolean iterOk_rep_2 = false;
            do {
                if (!parseTypeDecl(self)) { break; }
                iterOk_rep_2 = true;
            } while (false);
            if (!iterOk_rep_2) {
                pos = savedPos_rep_2;
                cst.truncate(savedNodes_rep_2);
                break;
            }
            if (pos == savedPos_rep_2) break; // guard against infinite loops on zero-width matches
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parsePackageDecl(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_PackageDecl_KIND, firstTok, parent);
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (!parseAnnotation(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        if (peek() != KIND_INLINE_PACKAGE) { fail("'package'", RULE_PackageDecl_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parseQualifiedName(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__SEMI) { fail("';'", RULE_PackageDecl_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseImportDecl(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ImportDecl_KIND, firstTok, parent);
        if (peek() != KIND_INLINE_IMPORT) { fail("'import'", RULE_ImportDecl_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // cut: no enclosing Choice — no-op
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE_MODULE) { fail("'module'", RULE_ImportDecl_KIND); break; }
                    advance();
                    if (!parseQualifiedName(self)) { break; }
                    if (peek() != KIND_INLINE__SEMI) { fail("';'", RULE_ImportDecl_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    // optional: opt_1
                    {
                        int savedPos_opt_1 = pos;
                        int savedNodes_opt_1 = cst.currentNodeCount();
                        boolean optOk_opt_1 = false;
                        do {
                            if (peek() != KIND_INLINE_STATIC) { fail("'static'", RULE_ImportDecl_KIND); break; }
                            advance();
                            optOk_opt_1 = true;
                        } while (false);
                        if (!optOk_opt_1) {
                            pos = savedPos_opt_1;
                            cst.truncate(savedNodes_opt_1);
                        }
                    }
                    if (!parseQualifiedName(self)) { break; }
                    // optional: opt_2
                    {
                        int savedPos_opt_2 = pos;
                        int savedNodes_opt_2 = cst.currentNodeCount();
                        boolean optOk_opt_2 = false;
                        do {
                            if (peek() != KIND_INLINE__DOT) { fail("'.'", RULE_ImportDecl_KIND); break; }
                            advance();
                            if (peek() != KIND_INLINE__STAR) { fail("'*'", RULE_ImportDecl_KIND); break; }
                            advance();
                            optOk_opt_2 = true;
                        } while (false);
                        if (!optOk_opt_2) {
                            pos = savedPos_opt_2;
                            cst.truncate(savedNodes_opt_2);
                        }
                    }
                    if (peek() != KIND_INLINE__SEMI) { fail("';'", RULE_ImportDecl_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_ImportDecl_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseModuleDecl(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ModuleDecl_KIND, firstTok, parent);
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (!parseAnnotation(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                if (peek() != KIND_INLINE_OPEN) { fail("'open'", RULE_ModuleDecl_KIND); break; }
                advance();
                optOk_opt_1 = true;
            } while (false);
            if (!optOk_opt_1) {
                pos = savedPos_opt_1;
                cst.truncate(savedNodes_opt_1);
            }
        }
        if (peek() != KIND_INLINE_MODULE) { fail("'module'", RULE_ModuleDecl_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parseQualifiedName(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__LBRACE) { fail("'{'", RULE_ModuleDecl_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // zero-or-more: rep_2
        while (true) {
            int savedPos_rep_2 = pos;
            int savedNodes_rep_2 = cst.currentNodeCount();
            boolean iterOk_rep_2 = false;
            do {
                if (!parseModuleDirective(self)) { break; }
                iterOk_rep_2 = true;
            } while (false);
            if (!iterOk_rep_2) {
                pos = savedPos_rep_2;
                cst.truncate(savedNodes_rep_2);
                break;
            }
            if (pos == savedPos_rep_2) break; // guard against infinite loops on zero-width matches
        }
        if (peek() != KIND_INLINE__RBRACE) { fail("'}'", RULE_ModuleDecl_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseModuleDirective(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ModuleDirective_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseRequiresDirective(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseExportsDirective(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseOpensDirective(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseUsesDirective(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseProvidesDirective(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_ModuleDirective_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseRequiresDirective(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_RequiresDirective_KIND, firstTok, parent);
        if (peek() != KIND_INLINE_REQUIRES) { fail("'requires'", RULE_RequiresDirective_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // cut: no enclosing Choice — no-op
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                // choice: alt_1
                {
                    int savedPos_alt_1 = pos;
                    int savedNodes_alt_1 = cst.currentNodeCount();
                    boolean matched_alt_1 = false;
                    boolean cutHit_alt_1 = false;
                    if (!matched_alt_1 && !cutHit_alt_1) {
                        do {
                            if (peek() != KIND_INLINE_TRANSITIVE) { fail("'transitive'", RULE_RequiresDirective_KIND); break; }
                            advance();
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1 && !cutHit_alt_1) {
                        do {
                            if (peek() != KIND_INLINE_STATIC) { fail("'static'", RULE_RequiresDirective_KIND); break; }
                            advance();
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1) { fail("<choice>", RULE_RequiresDirective_KIND); break; }
                }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        if (!parseQualifiedName(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__SEMI) { fail("';'", RULE_RequiresDirective_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseExportsDirective(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ExportsDirective_KIND, firstTok, parent);
        if (peek() != KIND_INLINE_EXPORTS) { fail("'exports'", RULE_ExportsDirective_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parseQualifiedName(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_INLINE_TO) { fail("'to'", RULE_ExportsDirective_KIND); break; }
                advance();
                if (!parseQualifiedName(self)) { break; }
                // zero-or-more: rep_1
                while (true) {
                    int savedPos_rep_1 = pos;
                    int savedNodes_rep_1 = cst.currentNodeCount();
                    boolean iterOk_rep_1 = false;
                    do {
                        if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_ExportsDirective_KIND); break; }
                        advance();
                        if (!parseQualifiedName(self)) { break; }
                        iterOk_rep_1 = true;
                    } while (false);
                    if (!iterOk_rep_1) {
                        pos = savedPos_rep_1;
                        cst.truncate(savedNodes_rep_1);
                        break;
                    }
                    if (pos == savedPos_rep_1) break; // guard against infinite loops on zero-width matches
                }
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (peek() != KIND_INLINE__SEMI) { fail("';'", RULE_ExportsDirective_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseOpensDirective(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_OpensDirective_KIND, firstTok, parent);
        if (peek() != KIND_INLINE_OPENS) { fail("'opens'", RULE_OpensDirective_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parseQualifiedName(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_INLINE_TO) { fail("'to'", RULE_OpensDirective_KIND); break; }
                advance();
                if (!parseQualifiedName(self)) { break; }
                // zero-or-more: rep_1
                while (true) {
                    int savedPos_rep_1 = pos;
                    int savedNodes_rep_1 = cst.currentNodeCount();
                    boolean iterOk_rep_1 = false;
                    do {
                        if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_OpensDirective_KIND); break; }
                        advance();
                        if (!parseQualifiedName(self)) { break; }
                        iterOk_rep_1 = true;
                    } while (false);
                    if (!iterOk_rep_1) {
                        pos = savedPos_rep_1;
                        cst.truncate(savedNodes_rep_1);
                        break;
                    }
                    if (pos == savedPos_rep_1) break; // guard against infinite loops on zero-width matches
                }
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (peek() != KIND_INLINE__SEMI) { fail("';'", RULE_OpensDirective_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseUsesDirective(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_UsesDirective_KIND, firstTok, parent);
        if (peek() != KIND_INLINE_USES) { fail("'uses'", RULE_UsesDirective_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parseQualifiedName(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__SEMI) { fail("';'", RULE_UsesDirective_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseProvidesDirective(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ProvidesDirective_KIND, firstTok, parent);
        if (peek() != KIND_INLINE_PROVIDES) { fail("'provides'", RULE_ProvidesDirective_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parseQualifiedName(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE_WITH) { fail("'with'", RULE_ProvidesDirective_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseQualifiedName(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_ProvidesDirective_KIND); break; }
                advance();
                if (!parseQualifiedName(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        if (peek() != KIND_INLINE__SEMI) { fail("';'", RULE_ProvidesDirective_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseTypeDecl(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_TypeDecl_KIND, firstTok, parent);
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (!parseAnnotation(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        // zero-or-more: rep_1
        while (true) {
            int savedPos_rep_1 = pos;
            int savedNodes_rep_1 = cst.currentNodeCount();
            boolean iterOk_rep_1 = false;
            do {
                if (java.util.Arrays.binarySearch(ALIAS_MODIFIER, peek()) < 0) { fail("Modifier", RULE_TypeDecl_KIND); break; }
                advance();
                iterOk_rep_1 = true;
            } while (false);
            if (!iterOk_rep_1) {
                pos = savedPos_rep_1;
                cst.truncate(savedNodes_rep_1);
                break;
            }
            if (pos == savedPos_rep_1) break; // guard against infinite loops on zero-width matches
        }
        if (!parseTypeKind(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseTypeKind(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_TypeKind_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseClassDecl(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseInterfaceDecl(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseEnumDecl(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseRecordDecl(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseAnnotationDecl(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_TypeKind_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseClassDecl(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ClassDecl_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_CLASS) { fail("ClassKW", RULE_ClassDecl_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (java.util.Arrays.binarySearch(IDFALL_IDENTIFIER, peek()) < 0) { fail("Identifier", RULE_ClassDecl_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parseTypeParams(self)) { break; }
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                if (peek() != KIND_INLINE_EXTENDS) { fail("'extends'", RULE_ClassDecl_KIND); break; }
                advance();
                if (!parseType(self)) { break; }
                optOk_opt_1 = true;
            } while (false);
            if (!optOk_opt_1) {
                pos = savedPos_opt_1;
                cst.truncate(savedNodes_opt_1);
            }
        }
        // optional: opt_2
        {
            int savedPos_opt_2 = pos;
            int savedNodes_opt_2 = cst.currentNodeCount();
            boolean optOk_opt_2 = false;
            do {
                if (!parseImplementsClause(self)) { break; }
                optOk_opt_2 = true;
            } while (false);
            if (!optOk_opt_2) {
                pos = savedPos_opt_2;
                cst.truncate(savedNodes_opt_2);
            }
        }
        // optional: opt_3
        {
            int savedPos_opt_3 = pos;
            int savedNodes_opt_3 = cst.currentNodeCount();
            boolean optOk_opt_3 = false;
            do {
                if (!parsePermitsClause(self)) { break; }
                optOk_opt_3 = true;
            } while (false);
            if (!optOk_opt_3) {
                pos = savedPos_opt_3;
                cst.truncate(savedNodes_opt_3);
            }
        }
        if (!parseClassBody(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseInterfaceDecl(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_InterfaceDecl_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_INTERFACE) { fail("InterfaceKW", RULE_InterfaceDecl_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (java.util.Arrays.binarySearch(IDFALL_IDENTIFIER, peek()) < 0) { fail("Identifier", RULE_InterfaceDecl_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parseTypeParams(self)) { break; }
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                if (peek() != KIND_INLINE_EXTENDS) { fail("'extends'", RULE_InterfaceDecl_KIND); break; }
                advance();
                if (!parseTypeList(self)) { break; }
                optOk_opt_1 = true;
            } while (false);
            if (!optOk_opt_1) {
                pos = savedPos_opt_1;
                cst.truncate(savedNodes_opt_1);
            }
        }
        // optional: opt_2
        {
            int savedPos_opt_2 = pos;
            int savedNodes_opt_2 = cst.currentNodeCount();
            boolean optOk_opt_2 = false;
            do {
                if (!parsePermitsClause(self)) { break; }
                optOk_opt_2 = true;
            } while (false);
            if (!optOk_opt_2) {
                pos = savedPos_opt_2;
                cst.truncate(savedNodes_opt_2);
            }
        }
        if (!parseClassBody(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseAnnotationDecl(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_AnnotationDecl_KIND, firstTok, parent);
        if (peek() != KIND_INLINE__AT) { fail("'@'", RULE_AnnotationDecl_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        { int __k = peek(); if (__k != KIND_INLINE_INTERFACE) { fail("InterfaceKW", RULE_AnnotationDecl_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (java.util.Arrays.binarySearch(IDFALL_IDENTIFIER, peek()) < 0) { fail("Identifier", RULE_AnnotationDecl_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseAnnotationBody(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseAnnotationBody(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_AnnotationBody_KIND, firstTok, parent);
        if (peek() != KIND_INLINE__LBRACE) { fail("'{'", RULE_AnnotationBody_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (!parseAnnotationMember(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        if (peek() != KIND_INLINE__RBRACE) { fail("'}'", RULE_AnnotationBody_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseAnnotationMember(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_AnnotationMember_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    // zero-or-more: rep_1
                    while (true) {
                        int savedPos_rep_1 = pos;
                        int savedNodes_rep_1 = cst.currentNodeCount();
                        boolean iterOk_rep_1 = false;
                        do {
                            if (!parseAnnotation(self)) { break; }
                            iterOk_rep_1 = true;
                        } while (false);
                        if (!iterOk_rep_1) {
                            pos = savedPos_rep_1;
                            cst.truncate(savedNodes_rep_1);
                            break;
                        }
                        if (pos == savedPos_rep_1) break; // guard against infinite loops on zero-width matches
                    }
                    // zero-or-more: rep_2
                    while (true) {
                        int savedPos_rep_2 = pos;
                        int savedNodes_rep_2 = cst.currentNodeCount();
                        boolean iterOk_rep_2 = false;
                        do {
                            if (java.util.Arrays.binarySearch(ALIAS_MODIFIER, peek()) < 0) { fail("Modifier", RULE_AnnotationMember_KIND); break; }
                            advance();
                            iterOk_rep_2 = true;
                        } while (false);
                        if (!iterOk_rep_2) {
                            pos = savedPos_rep_2;
                            cst.truncate(savedNodes_rep_2);
                            break;
                        }
                        if (pos == savedPos_rep_2) break; // guard against infinite loops on zero-width matches
                    }
                    // choice: alt_3
                    {
                        int savedPos_alt_3 = pos;
                        int savedNodes_alt_3 = cst.currentNodeCount();
                        boolean matched_alt_3 = false;
                        boolean cutHit_alt_3 = false;
                        if (!matched_alt_3 && !cutHit_alt_3) {
                            do {
                                if (!parseAnnotationElemDecl(self)) { break; }
                                matched_alt_3 = true;
                            } while (false);
                            if (!matched_alt_3) {
                                pos = savedPos_alt_3;
                                cst.truncate(savedNodes_alt_3);
                            }
                        }
                        if (!matched_alt_3 && !cutHit_alt_3) {
                            do {
                                if (!parseFieldDecl(self)) { break; }
                                matched_alt_3 = true;
                            } while (false);
                            if (!matched_alt_3) {
                                pos = savedPos_alt_3;
                                cst.truncate(savedNodes_alt_3);
                            }
                        }
                        if (!matched_alt_3 && !cutHit_alt_3) {
                            do {
                                if (!parseTypeKind(self)) { break; }
                                matched_alt_3 = true;
                            } while (false);
                            if (!matched_alt_3) {
                                pos = savedPos_alt_3;
                                cst.truncate(savedNodes_alt_3);
                            }
                        }
                        if (!matched_alt_3) { fail("<choice>", RULE_AnnotationMember_KIND); break; }
                    }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE__SEMI) { fail("';'", RULE_AnnotationMember_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_AnnotationMember_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseAnnotationElemDecl(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_AnnotationElemDecl_KIND, firstTok, parent);
        if (!parseType(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (java.util.Arrays.binarySearch(IDFALL_IDENTIFIER, peek()) < 0) { fail("Identifier", RULE_AnnotationElemDecl_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_AnnotationElemDecl_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_AnnotationElemDecl_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_INLINE_DEFAULT) { fail("'default'", RULE_AnnotationElemDecl_KIND); break; }
                advance();
                if (!parseAnnotationElem(self)) { break; }
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (peek() != KIND_INLINE__SEMI) { fail("';'", RULE_AnnotationElemDecl_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseEnumDecl(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_EnumDecl_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_ENUM) { fail("EnumKW", RULE_EnumDecl_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (java.util.Arrays.binarySearch(IDFALL_IDENTIFIER, peek()) < 0) { fail("Identifier", RULE_EnumDecl_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parseImplementsClause(self)) { break; }
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (!parseEnumBody(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseRecordDecl(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_RecordDecl_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_RECORD) { fail("RecordKW", RULE_RecordDecl_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // and-predicate: and_0
        {
            int savedPos_and_0 = pos;
            int savedNodes_and_0 = cst.currentNodeCount();
            boolean andOk_and_0 = false;
            do {
                if (java.util.Arrays.binarySearch(IDFALL_IDENTIFIER, peek()) < 0) { fail("Identifier", RULE_RecordDecl_KIND); break; }
                advance();
                // optional: opt_1
                {
                    int savedPos_opt_1 = pos;
                    int savedNodes_opt_1 = cst.currentNodeCount();
                    boolean optOk_opt_1 = false;
                    do {
                        if (!parseTypeParams(self)) { break; }
                        optOk_opt_1 = true;
                    } while (false);
                    if (!optOk_opt_1) {
                        pos = savedPos_opt_1;
                        cst.truncate(savedNodes_opt_1);
                    }
                }
                if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_RecordDecl_KIND); break; }
                advance();
                andOk_and_0 = true;
            } while (false);
            pos = savedPos_and_0;
            cst.truncate(savedNodes_and_0);
            if (!andOk_and_0) { fail("&<predicate>", RULE_RecordDecl_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        if (java.util.Arrays.binarySearch(IDFALL_IDENTIFIER, peek()) < 0) { fail("Identifier", RULE_RecordDecl_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_2
        {
            int savedPos_opt_2 = pos;
            int savedNodes_opt_2 = cst.currentNodeCount();
            boolean optOk_opt_2 = false;
            do {
                if (!parseTypeParams(self)) { break; }
                optOk_opt_2 = true;
            } while (false);
            if (!optOk_opt_2) {
                pos = savedPos_opt_2;
                cst.truncate(savedNodes_opt_2);
            }
        }
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_RecordDecl_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // optional: opt_3
        {
            int savedPos_opt_3 = pos;
            int savedNodes_opt_3 = cst.currentNodeCount();
            boolean optOk_opt_3 = false;
            do {
                if (!parseRecordComponents(self)) { break; }
                optOk_opt_3 = true;
            } while (false);
            if (!optOk_opt_3) {
                pos = savedPos_opt_3;
                cst.truncate(savedNodes_opt_3);
            }
        }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_RecordDecl_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // optional: opt_4
        {
            int savedPos_opt_4 = pos;
            int savedNodes_opt_4 = cst.currentNodeCount();
            boolean optOk_opt_4 = false;
            do {
                if (!parseImplementsClause(self)) { break; }
                optOk_opt_4 = true;
            } while (false);
            if (!optOk_opt_4) {
                pos = savedPos_opt_4;
                cst.truncate(savedNodes_opt_4);
            }
        }
        if (!parseRecordBody(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseImplementsClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ImplementsClause_KIND, firstTok, parent);
        if (peek() != KIND_INLINE_IMPLEMENTS) { fail("'implements'", RULE_ImplementsClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parseTypeList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parsePermitsClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_PermitsClause_KIND, firstTok, parent);
        if (peek() != KIND_INLINE_PERMITS) { fail("'permits'", RULE_PermitsClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parseTypeList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseTypeList(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_TypeList_KIND, firstTok, parent);
        if (!parseType(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_TypeList_KIND); break; }
                advance();
                if (!parseType(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseTypeParams(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_TypeParams_KIND, firstTok, parent);
        if (peek() != KIND_INLINE__LT) { fail("'<'", RULE_TypeParams_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseTypeParam(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            if (splitGt > 0) break; // phantom '>' available — at closing position
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_TypeParams_KIND); break; }
                advance();
                if (!parseTypeParam(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        if (!advanceGt()) { fail("'>'", RULE_TypeParams_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseTypeParam(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_TypeParam_KIND, firstTok, parent);
        if (java.util.Arrays.binarySearch(IDFALL_IDENTIFIER, peek()) < 0) { fail("Identifier", RULE_TypeParam_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_INLINE_EXTENDS) { fail("'extends'", RULE_TypeParam_KIND); break; }
                advance();
                if (!parseType(self)) { break; }
                // zero-or-more: rep_1
                while (true) {
                    int savedPos_rep_1 = pos;
                    int savedNodes_rep_1 = cst.currentNodeCount();
                    boolean iterOk_rep_1 = false;
                    do {
                        if (peek() != KIND_INLINE__AMP) { fail("'&'", RULE_TypeParam_KIND); break; }
                        advance();
                        if (!parseType(self)) { break; }
                        iterOk_rep_1 = true;
                    } while (false);
                    if (!iterOk_rep_1) {
                        pos = savedPos_rep_1;
                        cst.truncate(savedNodes_rep_1);
                        break;
                    }
                    if (pos == savedPos_rep_1) break; // guard against infinite loops on zero-width matches
                }
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseClassBody(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ClassBody_KIND, firstTok, parent);
        if (peek() != KIND_INLINE__LBRACE) { fail("'{'", RULE_ClassBody_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (!parseClassMember(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        if (peek() != KIND_INLINE__RBRACE) { fail("'}'", RULE_ClassBody_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseClassMember(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ClassMember_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    // zero-or-more: rep_1
                    while (true) {
                        int savedPos_rep_1 = pos;
                        int savedNodes_rep_1 = cst.currentNodeCount();
                        boolean iterOk_rep_1 = false;
                        do {
                            if (!parseAnnotation(self)) { break; }
                            iterOk_rep_1 = true;
                        } while (false);
                        if (!iterOk_rep_1) {
                            pos = savedPos_rep_1;
                            cst.truncate(savedNodes_rep_1);
                            break;
                        }
                        if (pos == savedPos_rep_1) break; // guard against infinite loops on zero-width matches
                    }
                    // zero-or-more: rep_2
                    while (true) {
                        int savedPos_rep_2 = pos;
                        int savedNodes_rep_2 = cst.currentNodeCount();
                        boolean iterOk_rep_2 = false;
                        do {
                            if (java.util.Arrays.binarySearch(ALIAS_MODIFIER, peek()) < 0) { fail("Modifier", RULE_ClassMember_KIND); break; }
                            advance();
                            iterOk_rep_2 = true;
                        } while (false);
                        if (!iterOk_rep_2) {
                            pos = savedPos_rep_2;
                            cst.truncate(savedNodes_rep_2);
                            break;
                        }
                        if (pos == savedPos_rep_2) break; // guard against infinite loops on zero-width matches
                    }
                    if (!parseMember(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseInitializerBlock(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE__SEMI) { fail("';'", RULE_ClassMember_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_ClassMember_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseMember(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_Member_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseConstructorDecl(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseTypeKind(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseMethodDecl(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseFieldDecl(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_Member_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseInitializerBlock(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_InitializerBlock_KIND, firstTok, parent);
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_INLINE_STATIC) { fail("'static'", RULE_InitializerBlock_KIND); break; }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (!parseBlock(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseEnumBody(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_EnumBody_KIND, firstTok, parent);
        if (peek() != KIND_INLINE__LBRACE) { fail("'{'", RULE_EnumBody_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parseEnumConsts(self)) { break; }
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                if (peek() != KIND_INLINE__SEMI) { fail("';'", RULE_EnumBody_KIND); break; }
                advance();
                // zero-or-more: rep_2
                while (true) {
                    int savedPos_rep_2 = pos;
                    int savedNodes_rep_2 = cst.currentNodeCount();
                    boolean iterOk_rep_2 = false;
                    do {
                        if (!parseClassMember(self)) { break; }
                        iterOk_rep_2 = true;
                    } while (false);
                    if (!iterOk_rep_2) {
                        pos = savedPos_rep_2;
                        cst.truncate(savedNodes_rep_2);
                        break;
                    }
                    if (pos == savedPos_rep_2) break; // guard against infinite loops on zero-width matches
                }
                optOk_opt_1 = true;
            } while (false);
            if (!optOk_opt_1) {
                pos = savedPos_opt_1;
                cst.truncate(savedNodes_opt_1);
            }
        }
        if (peek() != KIND_INLINE__RBRACE) { fail("'}'", RULE_EnumBody_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseEnumConsts(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_EnumConsts_KIND, firstTok, parent);
        if (!parseEnumConst(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_EnumConsts_KIND); break; }
                advance();
                if (!parseEnumConst(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_EnumConsts_KIND); break; }
                advance();
                optOk_opt_1 = true;
            } while (false);
            if (!optOk_opt_1) {
                pos = savedPos_opt_1;
                cst.truncate(savedNodes_opt_1);
            }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseEnumConst(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_EnumConst_KIND, firstTok, parent);
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (!parseAnnotation(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        if (java.util.Arrays.binarySearch(IDFALL_IDENTIFIER, peek()) < 0) { fail("Identifier", RULE_EnumConst_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_EnumConst_KIND); break; }
                advance();
                // optional: opt_2
                {
                    int savedPos_opt_2 = pos;
                    int savedNodes_opt_2 = cst.currentNodeCount();
                    boolean optOk_opt_2 = false;
                    do {
                        if (!parseArgs(self)) { break; }
                        optOk_opt_2 = true;
                    } while (false);
                    if (!optOk_opt_2) {
                        pos = savedPos_opt_2;
                        cst.truncate(savedNodes_opt_2);
                    }
                }
                if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_EnumConst_KIND); break; }
                advance();
                optOk_opt_1 = true;
            } while (false);
            if (!optOk_opt_1) {
                pos = savedPos_opt_1;
                cst.truncate(savedNodes_opt_1);
            }
        }
        // optional: opt_3
        {
            int savedPos_opt_3 = pos;
            int savedNodes_opt_3 = cst.currentNodeCount();
            boolean optOk_opt_3 = false;
            do {
                if (!parseClassBody(self)) { break; }
                optOk_opt_3 = true;
            } while (false);
            if (!optOk_opt_3) {
                pos = savedPos_opt_3;
                cst.truncate(savedNodes_opt_3);
            }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseRecordComponents(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_RecordComponents_KIND, firstTok, parent);
        if (!parseRecordComp(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_RecordComponents_KIND); break; }
                advance();
                if (!parseRecordComp(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseRecordComp(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_RecordComp_KIND, firstTok, parent);
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (!parseAnnotation(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        if (!parseType(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (java.util.Arrays.binarySearch(IDFALL_IDENTIFIER, peek()) < 0) { fail("Identifier", RULE_RecordComp_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseRecordBody(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_RecordBody_KIND, firstTok, parent);
        if (peek() != KIND_INLINE__LBRACE) { fail("'{'", RULE_RecordBody_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (!parseRecordMember(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        if (peek() != KIND_INLINE__RBRACE) { fail("'}'", RULE_RecordBody_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseRecordMember(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_RecordMember_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseCompactConstructor(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseClassMember(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_RecordMember_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseCompactConstructor(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_CompactConstructor_KIND, firstTok, parent);
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (!parseAnnotation(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        // zero-or-more: rep_1
        while (true) {
            int savedPos_rep_1 = pos;
            int savedNodes_rep_1 = cst.currentNodeCount();
            boolean iterOk_rep_1 = false;
            do {
                if (java.util.Arrays.binarySearch(ALIAS_MODIFIER, peek()) < 0) { fail("Modifier", RULE_CompactConstructor_KIND); break; }
                advance();
                iterOk_rep_1 = true;
            } while (false);
            if (!iterOk_rep_1) {
                pos = savedPos_rep_1;
                cst.truncate(savedNodes_rep_1);
                break;
            }
            if (pos == savedPos_rep_1) break; // guard against infinite loops on zero-width matches
        }
        if (java.util.Arrays.binarySearch(IDFALL_IDENTIFIER, peek()) < 0) { fail("Identifier", RULE_CompactConstructor_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseBlock(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseFieldDecl(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_FieldDecl_KIND, firstTok, parent);
        if (!parseType(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (!parseVarDecls(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__SEMI) { fail("';'", RULE_FieldDecl_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseVarDecls(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_VarDecls_KIND, firstTok, parent);
        if (!parseVarDecl(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_VarDecls_KIND); break; }
                advance();
                if (!parseVarDecl(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseVarDecl(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_VarDecl_KIND, firstTok, parent);
        if (java.util.Arrays.binarySearch(IDFALL_IDENTIFIER, peek()) < 0) { fail("Identifier", RULE_VarDecl_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parseDims(self)) { break; }
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                if (peek() != KIND_INLINE__EQ) { fail("'='", RULE_VarDecl_KIND); break; }
                advance();
                if (!parseVarInit(self)) { break; }
                optOk_opt_1 = true;
            } while (false);
            if (!optOk_opt_1) {
                pos = savedPos_opt_1;
                cst.truncate(savedNodes_opt_1);
            }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseVarInit(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_VarInit_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE__LBRACE) { fail("'{'", RULE_VarInit_KIND); break; }
                    advance();
                    // optional: opt_1
                    {
                        int savedPos_opt_1 = pos;
                        int savedNodes_opt_1 = cst.currentNodeCount();
                        boolean optOk_opt_1 = false;
                        do {
                            if (!parseVarInit(self)) { break; }
                            // zero-or-more: rep_2
                            while (true) {
                                int savedPos_rep_2 = pos;
                                int savedNodes_rep_2 = cst.currentNodeCount();
                                boolean iterOk_rep_2 = false;
                                do {
                                    if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_VarInit_KIND); break; }
                                    advance();
                                    if (!parseVarInit(self)) { break; }
                                    iterOk_rep_2 = true;
                                } while (false);
                                if (!iterOk_rep_2) {
                                    pos = savedPos_rep_2;
                                    cst.truncate(savedNodes_rep_2);
                                    break;
                                }
                                if (pos == savedPos_rep_2) break; // guard against infinite loops on zero-width matches
                            }
                            // optional: opt_3
                            {
                                int savedPos_opt_3 = pos;
                                int savedNodes_opt_3 = cst.currentNodeCount();
                                boolean optOk_opt_3 = false;
                                do {
                                    if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_VarInit_KIND); break; }
                                    advance();
                                    optOk_opt_3 = true;
                                } while (false);
                                if (!optOk_opt_3) {
                                    pos = savedPos_opt_3;
                                    cst.truncate(savedNodes_opt_3);
                                }
                            }
                            optOk_opt_1 = true;
                        } while (false);
                        if (!optOk_opt_1) {
                            pos = savedPos_opt_1;
                            cst.truncate(savedNodes_opt_1);
                        }
                    }
                    if (peek() != KIND_INLINE__RBRACE) { fail("'}'", RULE_VarInit_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseExpr(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_VarInit_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseMethodDecl(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_MethodDecl_KIND, firstTok, parent);
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parseTypeParams(self)) { break; }
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (!parseType(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (java.util.Arrays.binarySearch(IDFALL_IDENTIFIER, peek()) < 0) { fail("Identifier", RULE_MethodDecl_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_MethodDecl_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                if (!parseParams(self)) { break; }
                optOk_opt_1 = true;
            } while (false);
            if (!optOk_opt_1) {
                pos = savedPos_opt_1;
                cst.truncate(savedNodes_opt_1);
            }
        }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_MethodDecl_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // optional: opt_2
        {
            int savedPos_opt_2 = pos;
            int savedNodes_opt_2 = cst.currentNodeCount();
            boolean optOk_opt_2 = false;
            do {
                if (!parseDims(self)) { break; }
                optOk_opt_2 = true;
            } while (false);
            if (!optOk_opt_2) {
                pos = savedPos_opt_2;
                cst.truncate(savedNodes_opt_2);
            }
        }
        // optional: opt_3
        {
            int savedPos_opt_3 = pos;
            int savedNodes_opt_3 = cst.currentNodeCount();
            boolean optOk_opt_3 = false;
            do {
                if (!parseThrows(self)) { break; }
                optOk_opt_3 = true;
            } while (false);
            if (!optOk_opt_3) {
                pos = savedPos_opt_3;
                cst.truncate(savedNodes_opt_3);
            }
        }
        // choice: alt_4
        {
            int savedPos_alt_4 = pos;
            int savedNodes_alt_4 = cst.currentNodeCount();
            boolean matched_alt_4 = false;
            boolean cutHit_alt_4 = false;
            if (!matched_alt_4 && !cutHit_alt_4) {
                do {
                    if (!parseBlock(self)) { break; }
                    matched_alt_4 = true;
                } while (false);
                if (!matched_alt_4) {
                    pos = savedPos_alt_4;
                    cst.truncate(savedNodes_alt_4);
                }
            }
            if (!matched_alt_4 && !cutHit_alt_4) {
                do {
                    if (peek() != KIND_INLINE__SEMI) { fail("';'", RULE_MethodDecl_KIND); break; }
                    advance();
                    matched_alt_4 = true;
                } while (false);
                if (!matched_alt_4) {
                    pos = savedPos_alt_4;
                    cst.truncate(savedNodes_alt_4);
                }
            }
            if (!matched_alt_4) { fail("<choice>", RULE_MethodDecl_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseParams(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_Params_KIND, firstTok, parent);
        if (!parseParam(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_Params_KIND); break; }
                advance();
                if (!parseParam(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseParam(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_Param_KIND, firstTok, parent);
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (!parseAnnotation(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        // zero-or-more: rep_1
        while (true) {
            int savedPos_rep_1 = pos;
            int savedNodes_rep_1 = cst.currentNodeCount();
            boolean iterOk_rep_1 = false;
            do {
                if (java.util.Arrays.binarySearch(ALIAS_MODIFIER, peek()) < 0) { fail("Modifier", RULE_Param_KIND); break; }
                advance();
                iterOk_rep_1 = true;
            } while (false);
            if (!iterOk_rep_1) {
                pos = savedPos_rep_1;
                cst.truncate(savedNodes_rep_1);
                break;
            }
            if (pos == savedPos_rep_1) break; // guard against infinite loops on zero-width matches
        }
        if (!parseType(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_2
        {
            int savedPos_opt_2 = pos;
            int savedNodes_opt_2 = cst.currentNodeCount();
            boolean optOk_opt_2 = false;
            do {
                if (peek() != KIND_INLINE__DOT_DOT_DOT) { fail("'...'", RULE_Param_KIND); break; }
                advance();
                optOk_opt_2 = true;
            } while (false);
            if (!optOk_opt_2) {
                pos = savedPos_opt_2;
                cst.truncate(savedNodes_opt_2);
            }
        }
        if (java.util.Arrays.binarySearch(IDFALL_IDENTIFIER, peek()) < 0) { fail("Identifier", RULE_Param_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // optional: opt_3
        {
            int savedPos_opt_3 = pos;
            int savedNodes_opt_3 = cst.currentNodeCount();
            boolean optOk_opt_3 = false;
            do {
                if (!parseDims(self)) { break; }
                optOk_opt_3 = true;
            } while (false);
            if (!optOk_opt_3) {
                pos = savedPos_opt_3;
                cst.truncate(savedNodes_opt_3);
            }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseThrows(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_Throws_KIND, firstTok, parent);
        if (peek() != KIND_INLINE_THROWS) { fail("'throws'", RULE_Throws_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parseTypeList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseConstructorDecl(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ConstructorDecl_KIND, firstTok, parent);
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parseTypeParams(self)) { break; }
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (java.util.Arrays.binarySearch(IDFALL_IDENTIFIER, peek()) < 0) { fail("Identifier", RULE_ConstructorDecl_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_ConstructorDecl_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                if (!parseParams(self)) { break; }
                optOk_opt_1 = true;
            } while (false);
            if (!optOk_opt_1) {
                pos = savedPos_opt_1;
                cst.truncate(savedNodes_opt_1);
            }
        }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_ConstructorDecl_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // optional: opt_2
        {
            int savedPos_opt_2 = pos;
            int savedNodes_opt_2 = cst.currentNodeCount();
            boolean optOk_opt_2 = false;
            do {
                if (!parseThrows(self)) { break; }
                optOk_opt_2 = true;
            } while (false);
            if (!optOk_opt_2) {
                pos = savedPos_opt_2;
                cst.truncate(savedNodes_opt_2);
            }
        }
        if (!parseBlock(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseBlock(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_Block_KIND, firstTok, parent);
        if (peek() != KIND_INLINE__LBRACE) { fail("'{'", RULE_Block_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (!parseBlockStmt(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        if (peek() != KIND_INLINE__RBRACE) { fail("'}'", RULE_Block_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseBlockStmt(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_BlockStmt_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseLocalVar(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseLocalTypeDecl(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseStmt(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_BlockStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseLocalTypeDecl(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_LocalTypeDecl_KIND, firstTok, parent);
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (!parseAnnotation(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        // zero-or-more: rep_1
        while (true) {
            int savedPos_rep_1 = pos;
            int savedNodes_rep_1 = cst.currentNodeCount();
            boolean iterOk_rep_1 = false;
            do {
                if (java.util.Arrays.binarySearch(ALIAS_MODIFIER, peek()) < 0) { fail("Modifier", RULE_LocalTypeDecl_KIND); break; }
                advance();
                iterOk_rep_1 = true;
            } while (false);
            if (!iterOk_rep_1) {
                pos = savedPos_rep_1;
                cst.truncate(savedNodes_rep_1);
                break;
            }
            if (pos == savedPos_rep_1) break; // guard against infinite loops on zero-width matches
        }
        if (!parseTypeKind(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseLocalVar(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_LocalVar_KIND, firstTok, parent);
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (!parseAnnotation(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        // zero-or-more: rep_1
        while (true) {
            int savedPos_rep_1 = pos;
            int savedNodes_rep_1 = cst.currentNodeCount();
            boolean iterOk_rep_1 = false;
            do {
                if (java.util.Arrays.binarySearch(ALIAS_MODIFIER, peek()) < 0) { fail("Modifier", RULE_LocalVar_KIND); break; }
                advance();
                iterOk_rep_1 = true;
            } while (false);
            if (!iterOk_rep_1) {
                pos = savedPos_rep_1;
                cst.truncate(savedNodes_rep_1);
                break;
            }
            if (pos == savedPos_rep_1) break; // guard against infinite loops on zero-width matches
        }
        if (!parseLocalVarType(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (!parseVarDecls(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__SEMI) { fail("';'", RULE_LocalVar_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseLocalVarType(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_LocalVarType_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE_VAR) { fail("'var'", RULE_LocalVarType_KIND); break; }
                    advance();
                    // no-op: not-predicate over char-level expression — handled by lexer
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseType(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_LocalVarType_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseStmt(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_Stmt_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseBlock(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_IF) { fail("IfKW", RULE_Stmt_KIND); break; } }
                    advance();
                    cutHit_alt_0 = true;
                    if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_Stmt_KIND); break; }
                    advance();
                    if (!parseExpr(self)) { break; }
                    if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_Stmt_KIND); break; }
                    advance();
                    if (!parseStmt(self)) { break; }
                    // optional: opt_1
                    {
                        int savedPos_opt_1 = pos;
                        int savedNodes_opt_1 = cst.currentNodeCount();
                        boolean optOk_opt_1 = false;
                        do {
                            if (peek() != KIND_INLINE_ELSE) { fail("'else'", RULE_Stmt_KIND); break; }
                            advance();
                            if (!parseStmt(self)) { break; }
                            optOk_opt_1 = true;
                        } while (false);
                        if (!optOk_opt_1) {
                            pos = savedPos_opt_1;
                            cst.truncate(savedNodes_opt_1);
                        }
                    }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_WHILE) { fail("WhileKW", RULE_Stmt_KIND); break; } }
                    advance();
                    cutHit_alt_0 = true;
                    if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_Stmt_KIND); break; }
                    advance();
                    if (!parseExpr(self)) { break; }
                    if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_Stmt_KIND); break; }
                    advance();
                    if (!parseStmt(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_FOR) { fail("ForKW", RULE_Stmt_KIND); break; } }
                    advance();
                    cutHit_alt_0 = true;
                    if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_Stmt_KIND); break; }
                    advance();
                    if (!parseForCtrl(self)) { break; }
                    if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_Stmt_KIND); break; }
                    advance();
                    if (!parseStmt(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_DO) { fail("DoKW", RULE_Stmt_KIND); break; } }
                    advance();
                    cutHit_alt_0 = true;
                    if (!parseStmt(self)) { break; }
                    if (peek() != KIND_INLINE_WHILE) { fail("'while'", RULE_Stmt_KIND); break; }
                    advance();
                    if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_Stmt_KIND); break; }
                    advance();
                    if (!parseExpr(self)) { break; }
                    if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_Stmt_KIND); break; }
                    advance();
                    if (peek() != KIND_INLINE__SEMI) { fail("';'", RULE_Stmt_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_TRY) { fail("TryKW", RULE_Stmt_KIND); break; } }
                    advance();
                    cutHit_alt_0 = true;
                    // optional: opt_2
                    {
                        int savedPos_opt_2 = pos;
                        int savedNodes_opt_2 = cst.currentNodeCount();
                        boolean optOk_opt_2 = false;
                        do {
                            if (!parseResourceSpec(self)) { break; }
                            optOk_opt_2 = true;
                        } while (false);
                        if (!optOk_opt_2) {
                            pos = savedPos_opt_2;
                            cst.truncate(savedNodes_opt_2);
                        }
                    }
                    if (!parseBlock(self)) { break; }
                    // zero-or-more: rep_3
                    while (true) {
                        int savedPos_rep_3 = pos;
                        int savedNodes_rep_3 = cst.currentNodeCount();
                        boolean iterOk_rep_3 = false;
                        do {
                            if (!parseCatch(self)) { break; }
                            iterOk_rep_3 = true;
                        } while (false);
                        if (!iterOk_rep_3) {
                            pos = savedPos_rep_3;
                            cst.truncate(savedNodes_rep_3);
                            break;
                        }
                        if (pos == savedPos_rep_3) break; // guard against infinite loops on zero-width matches
                    }
                    // optional: opt_4
                    {
                        int savedPos_opt_4 = pos;
                        int savedNodes_opt_4 = cst.currentNodeCount();
                        boolean optOk_opt_4 = false;
                        do {
                            if (!parseFinally(self)) { break; }
                            optOk_opt_4 = true;
                        } while (false);
                        if (!optOk_opt_4) {
                            pos = savedPos_opt_4;
                            cst.truncate(savedNodes_opt_4);
                        }
                    }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_SWITCH) { fail("SwitchKW", RULE_Stmt_KIND); break; } }
                    advance();
                    cutHit_alt_0 = true;
                    if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_Stmt_KIND); break; }
                    advance();
                    if (!parseExpr(self)) { break; }
                    if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_Stmt_KIND); break; }
                    advance();
                    if (!parseSwitchBlock(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_RETURN) { fail("ReturnKW", RULE_Stmt_KIND); break; } }
                    advance();
                    // optional: opt_5
                    {
                        int savedPos_opt_5 = pos;
                        int savedNodes_opt_5 = cst.currentNodeCount();
                        boolean optOk_opt_5 = false;
                        do {
                            if (!parseExpr(self)) { break; }
                            optOk_opt_5 = true;
                        } while (false);
                        if (!optOk_opt_5) {
                            pos = savedPos_opt_5;
                            cst.truncate(savedNodes_opt_5);
                        }
                    }
                    if (peek() != KIND_INLINE__SEMI) { fail("';'", RULE_Stmt_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_THROW) { fail("ThrowKW", RULE_Stmt_KIND); break; } }
                    advance();
                    if (!parseExpr(self)) { break; }
                    if (peek() != KIND_INLINE__SEMI) { fail("';'", RULE_Stmt_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_BREAK) { fail("BreakKW", RULE_Stmt_KIND); break; } }
                    advance();
                    // optional: opt_6
                    {
                        int savedPos_opt_6 = pos;
                        int savedNodes_opt_6 = cst.currentNodeCount();
                        boolean optOk_opt_6 = false;
                        do {
                            if (java.util.Arrays.binarySearch(IDFALL_IDENTIFIER, peek()) < 0) { fail("Identifier", RULE_Stmt_KIND); break; }
                            advance();
                            optOk_opt_6 = true;
                        } while (false);
                        if (!optOk_opt_6) {
                            pos = savedPos_opt_6;
                            cst.truncate(savedNodes_opt_6);
                        }
                    }
                    if (peek() != KIND_INLINE__SEMI) { fail("';'", RULE_Stmt_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_CONTINUE) { fail("ContinueKW", RULE_Stmt_KIND); break; } }
                    advance();
                    // optional: opt_7
                    {
                        int savedPos_opt_7 = pos;
                        int savedNodes_opt_7 = cst.currentNodeCount();
                        boolean optOk_opt_7 = false;
                        do {
                            if (java.util.Arrays.binarySearch(IDFALL_IDENTIFIER, peek()) < 0) { fail("Identifier", RULE_Stmt_KIND); break; }
                            advance();
                            optOk_opt_7 = true;
                        } while (false);
                        if (!optOk_opt_7) {
                            pos = savedPos_opt_7;
                            cst.truncate(savedNodes_opt_7);
                        }
                    }
                    if (peek() != KIND_INLINE__SEMI) { fail("';'", RULE_Stmt_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_ASSERT) { fail("AssertKW", RULE_Stmt_KIND); break; } }
                    advance();
                    if (!parseExpr(self)) { break; }
                    // optional: opt_8
                    {
                        int savedPos_opt_8 = pos;
                        int savedNodes_opt_8 = cst.currentNodeCount();
                        boolean optOk_opt_8 = false;
                        do {
                            if (peek() != KIND_INLINE__COLON) { fail("':'", RULE_Stmt_KIND); break; }
                            advance();
                            if (!parseExpr(self)) { break; }
                            optOk_opt_8 = true;
                        } while (false);
                        if (!optOk_opt_8) {
                            pos = savedPos_opt_8;
                            cst.truncate(savedNodes_opt_8);
                        }
                    }
                    if (peek() != KIND_INLINE__SEMI) { fail("';'", RULE_Stmt_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_SYNCHRONIZED) { fail("SynchronizedKW", RULE_Stmt_KIND); break; } }
                    advance();
                    cutHit_alt_0 = true;
                    if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_Stmt_KIND); break; }
                    advance();
                    if (!parseExpr(self)) { break; }
                    if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_Stmt_KIND); break; }
                    advance();
                    if (!parseBlock(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_YIELD) { fail("YieldKW", RULE_Stmt_KIND); break; } }
                    advance();
                    if (!parseExpr(self)) { break; }
                    if (peek() != KIND_INLINE__SEMI) { fail("';'", RULE_Stmt_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (java.util.Arrays.binarySearch(IDFALL_IDENTIFIER, peek()) < 0) { fail("Identifier", RULE_Stmt_KIND); break; }
                    advance();
                    if (peek() != KIND_INLINE__COLON) { fail("':'", RULE_Stmt_KIND); break; }
                    advance();
                    if (!parseStmt(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseExpr(self)) { break; }
                    if (peek() != KIND_INLINE__SEMI) { fail("';'", RULE_Stmt_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE__SEMI) { fail("';'", RULE_Stmt_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_Stmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseForCtrl(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ForCtrl_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    // optional: opt_1
                    {
                        int savedPos_opt_1 = pos;
                        int savedNodes_opt_1 = cst.currentNodeCount();
                        boolean optOk_opt_1 = false;
                        do {
                            if (!parseForInit(self)) { break; }
                            optOk_opt_1 = true;
                        } while (false);
                        if (!optOk_opt_1) {
                            pos = savedPos_opt_1;
                            cst.truncate(savedNodes_opt_1);
                        }
                    }
                    if (peek() != KIND_INLINE__SEMI) { fail("';'", RULE_ForCtrl_KIND); break; }
                    advance();
                    // optional: opt_2
                    {
                        int savedPos_opt_2 = pos;
                        int savedNodes_opt_2 = cst.currentNodeCount();
                        boolean optOk_opt_2 = false;
                        do {
                            if (!parseExpr(self)) { break; }
                            optOk_opt_2 = true;
                        } while (false);
                        if (!optOk_opt_2) {
                            pos = savedPos_opt_2;
                            cst.truncate(savedNodes_opt_2);
                        }
                    }
                    if (peek() != KIND_INLINE__SEMI) { fail("';'", RULE_ForCtrl_KIND); break; }
                    advance();
                    // optional: opt_3
                    {
                        int savedPos_opt_3 = pos;
                        int savedNodes_opt_3 = cst.currentNodeCount();
                        boolean optOk_opt_3 = false;
                        do {
                            if (!parseExprList(self)) { break; }
                            optOk_opt_3 = true;
                        } while (false);
                        if (!optOk_opt_3) {
                            pos = savedPos_opt_3;
                            cst.truncate(savedNodes_opt_3);
                        }
                    }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseLocalVarType(self)) { break; }
                    if (java.util.Arrays.binarySearch(IDFALL_IDENTIFIER, peek()) < 0) { fail("Identifier", RULE_ForCtrl_KIND); break; }
                    advance();
                    if (peek() != KIND_INLINE__COLON) { fail("':'", RULE_ForCtrl_KIND); break; }
                    advance();
                    if (!parseExpr(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_ForCtrl_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseForInit(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ForInit_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseLocalVarNoSemi(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseExprList(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_ForInit_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseLocalVarNoSemi(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_LocalVarNoSemi_KIND, firstTok, parent);
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (!parseAnnotation(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        // zero-or-more: rep_1
        while (true) {
            int savedPos_rep_1 = pos;
            int savedNodes_rep_1 = cst.currentNodeCount();
            boolean iterOk_rep_1 = false;
            do {
                if (java.util.Arrays.binarySearch(ALIAS_MODIFIER, peek()) < 0) { fail("Modifier", RULE_LocalVarNoSemi_KIND); break; }
                advance();
                iterOk_rep_1 = true;
            } while (false);
            if (!iterOk_rep_1) {
                pos = savedPos_rep_1;
                cst.truncate(savedNodes_rep_1);
                break;
            }
            if (pos == savedPos_rep_1) break; // guard against infinite loops on zero-width matches
        }
        if (!parseLocalVarType(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (!parseVarDecls(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseResourceSpec(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ResourceSpec_KIND, firstTok, parent);
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_ResourceSpec_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseResource(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__SEMI) { fail("';'", RULE_ResourceSpec_KIND); break; }
                advance();
                if (!parseResource(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                if (peek() != KIND_INLINE__SEMI) { fail("';'", RULE_ResourceSpec_KIND); break; }
                advance();
                optOk_opt_1 = true;
            } while (false);
            if (!optOk_opt_1) {
                pos = savedPos_opt_1;
                cst.truncate(savedNodes_opt_1);
            }
        }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_ResourceSpec_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseResource(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_Resource_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    // zero-or-more: rep_1
                    while (true) {
                        int savedPos_rep_1 = pos;
                        int savedNodes_rep_1 = cst.currentNodeCount();
                        boolean iterOk_rep_1 = false;
                        do {
                            if (!parseAnnotation(self)) { break; }
                            iterOk_rep_1 = true;
                        } while (false);
                        if (!iterOk_rep_1) {
                            pos = savedPos_rep_1;
                            cst.truncate(savedNodes_rep_1);
                            break;
                        }
                        if (pos == savedPos_rep_1) break; // guard against infinite loops on zero-width matches
                    }
                    // zero-or-more: rep_2
                    while (true) {
                        int savedPos_rep_2 = pos;
                        int savedNodes_rep_2 = cst.currentNodeCount();
                        boolean iterOk_rep_2 = false;
                        do {
                            if (java.util.Arrays.binarySearch(ALIAS_MODIFIER, peek()) < 0) { fail("Modifier", RULE_Resource_KIND); break; }
                            advance();
                            iterOk_rep_2 = true;
                        } while (false);
                        if (!iterOk_rep_2) {
                            pos = savedPos_rep_2;
                            cst.truncate(savedNodes_rep_2);
                            break;
                        }
                        if (pos == savedPos_rep_2) break; // guard against infinite loops on zero-width matches
                    }
                    if (!parseLocalVarType(self)) { break; }
                    if (java.util.Arrays.binarySearch(IDFALL_IDENTIFIER, peek()) < 0) { fail("Identifier", RULE_Resource_KIND); break; }
                    advance();
                    if (peek() != KIND_INLINE__EQ) { fail("'='", RULE_Resource_KIND); break; }
                    advance();
                    if (!parseExpr(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseQualifiedName(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_Resource_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseCatch(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_Catch_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_CATCH) { fail("CatchKW", RULE_Catch_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_Catch_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (java.util.Arrays.binarySearch(ALIAS_MODIFIER, peek()) < 0) { fail("Modifier", RULE_Catch_KIND); break; }
                advance();
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        if (!parseType(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_1
        while (true) {
            int savedPos_rep_1 = pos;
            int savedNodes_rep_1 = cst.currentNodeCount();
            boolean iterOk_rep_1 = false;
            do {
                if (peek() != KIND_INLINE__PIPE) { fail("'|'", RULE_Catch_KIND); break; }
                advance();
                if (!parseType(self)) { break; }
                iterOk_rep_1 = true;
            } while (false);
            if (!iterOk_rep_1) {
                pos = savedPos_rep_1;
                cst.truncate(savedNodes_rep_1);
                break;
            }
            if (pos == savedPos_rep_1) break; // guard against infinite loops on zero-width matches
        }
        if (java.util.Arrays.binarySearch(IDFALL_IDENTIFIER, peek()) < 0) { fail("Identifier", RULE_Catch_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_Catch_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseBlock(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseFinally(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_Finally_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_FINALLY) { fail("FinallyKW", RULE_Finally_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parseBlock(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseSwitchBlock(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_SwitchBlock_KIND, firstTok, parent);
        if (peek() != KIND_INLINE__LBRACE) { fail("'{'", RULE_SwitchBlock_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (!parseSwitchRule(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        if (peek() != KIND_INLINE__RBRACE) { fail("'}'", RULE_SwitchBlock_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseSwitchRule(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_SwitchRule_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseSwitchLabel(self)) { break; }
                    if (peek() != KIND_INLINE__MINUS_GT) { fail("'->'", RULE_SwitchRule_KIND); break; }
                    advance();
                    // choice: alt_1
                    {
                        int savedPos_alt_1 = pos;
                        int savedNodes_alt_1 = cst.currentNodeCount();
                        boolean matched_alt_1 = false;
                        boolean cutHit_alt_1 = false;
                        if (!matched_alt_1 && !cutHit_alt_1) {
                            do {
                                if (!parseExpr(self)) { break; }
                                if (peek() != KIND_INLINE__SEMI) { fail("';'", RULE_SwitchRule_KIND); break; }
                                advance();
                                matched_alt_1 = true;
                            } while (false);
                            if (!matched_alt_1) {
                                pos = savedPos_alt_1;
                                cst.truncate(savedNodes_alt_1);
                            }
                        }
                        if (!matched_alt_1 && !cutHit_alt_1) {
                            do {
                                if (!parseBlock(self)) { break; }
                                matched_alt_1 = true;
                            } while (false);
                            if (!matched_alt_1) {
                                pos = savedPos_alt_1;
                                cst.truncate(savedNodes_alt_1);
                            }
                        }
                        if (!matched_alt_1 && !cutHit_alt_1) {
                            do {
                                { int __k = peek(); if (__k != KIND_INLINE_THROW) { fail("ThrowKW", RULE_SwitchRule_KIND); break; } }
                                advance();
                                if (!parseExpr(self)) { break; }
                                if (peek() != KIND_INLINE__SEMI) { fail("';'", RULE_SwitchRule_KIND); break; }
                                advance();
                                matched_alt_1 = true;
                            } while (false);
                            if (!matched_alt_1) {
                                pos = savedPos_alt_1;
                                cst.truncate(savedNodes_alt_1);
                            }
                        }
                        if (!matched_alt_1) { fail("<choice>", RULE_SwitchRule_KIND); break; }
                    }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseSwitchLabel(self)) { break; }
                    if (peek() != KIND_INLINE__COLON) { fail("':'", RULE_SwitchRule_KIND); break; }
                    advance();
                    // zero-or-more: rep_2
                    while (true) {
                        int savedPos_rep_2 = pos;
                        int savedNodes_rep_2 = cst.currentNodeCount();
                        boolean iterOk_rep_2 = false;
                        do {
                            if (!parseBlockStmt(self)) { break; }
                            iterOk_rep_2 = true;
                        } while (false);
                        if (!iterOk_rep_2) {
                            pos = savedPos_rep_2;
                            cst.truncate(savedNodes_rep_2);
                            break;
                        }
                        if (pos == savedPos_rep_2) break; // guard against infinite loops on zero-width matches
                    }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_SwitchRule_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseSwitchLabel(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_SwitchLabel_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE_CASE) { fail("'case'", RULE_SwitchLabel_KIND); break; }
                    advance();
                    cutHit_alt_0 = true;
                    // choice: alt_1
                    {
                        int savedPos_alt_1 = pos;
                        int savedNodes_alt_1 = cst.currentNodeCount();
                        boolean matched_alt_1 = false;
                        boolean cutHit_alt_1 = false;
                        if (!matched_alt_1 && !cutHit_alt_1) {
                            do {
                                if (peek() != KIND_INLINE_NULL) { fail("'null'", RULE_SwitchLabel_KIND); break; }
                                advance();
                                // optional: opt_2
                                {
                                    int savedPos_opt_2 = pos;
                                    int savedNodes_opt_2 = cst.currentNodeCount();
                                    boolean optOk_opt_2 = false;
                                    do {
                                        if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_SwitchLabel_KIND); break; }
                                        advance();
                                        if (peek() != KIND_INLINE_DEFAULT) { fail("'default'", RULE_SwitchLabel_KIND); break; }
                                        advance();
                                        optOk_opt_2 = true;
                                    } while (false);
                                    if (!optOk_opt_2) {
                                        pos = savedPos_opt_2;
                                        cst.truncate(savedNodes_opt_2);
                                    }
                                }
                                matched_alt_1 = true;
                            } while (false);
                            if (!matched_alt_1) {
                                pos = savedPos_alt_1;
                                cst.truncate(savedNodes_alt_1);
                            }
                        }
                        if (!matched_alt_1 && !cutHit_alt_1) {
                            do {
                                if (!parseCaseItem(self)) { break; }
                                // zero-or-more: rep_3
                                while (true) {
                                    int savedPos_rep_3 = pos;
                                    int savedNodes_rep_3 = cst.currentNodeCount();
                                    boolean iterOk_rep_3 = false;
                                    do {
                                        if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_SwitchLabel_KIND); break; }
                                        advance();
                                        if (!parseCaseItem(self)) { break; }
                                        iterOk_rep_3 = true;
                                    } while (false);
                                    if (!iterOk_rep_3) {
                                        pos = savedPos_rep_3;
                                        cst.truncate(savedNodes_rep_3);
                                        break;
                                    }
                                    if (pos == savedPos_rep_3) break; // guard against infinite loops on zero-width matches
                                }
                                // optional: opt_4
                                {
                                    int savedPos_opt_4 = pos;
                                    int savedNodes_opt_4 = cst.currentNodeCount();
                                    boolean optOk_opt_4 = false;
                                    do {
                                        if (!parseGuard(self)) { break; }
                                        optOk_opt_4 = true;
                                    } while (false);
                                    if (!optOk_opt_4) {
                                        pos = savedPos_opt_4;
                                        cst.truncate(savedNodes_opt_4);
                                    }
                                }
                                matched_alt_1 = true;
                            } while (false);
                            if (!matched_alt_1) {
                                pos = savedPos_alt_1;
                                cst.truncate(savedNodes_alt_1);
                            }
                        }
                        if (!matched_alt_1) { fail("<choice>", RULE_SwitchLabel_KIND); break; }
                    }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE_DEFAULT) { fail("'default'", RULE_SwitchLabel_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_SwitchLabel_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseCaseItem(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_CaseItem_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parsePattern(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseQualifiedName(self)) { break; }
                    // and-predicate: and_1
                    {
                        int savedPos_and_1 = pos;
                        int savedNodes_and_1 = cst.currentNodeCount();
                        boolean andOk_and_1 = false;
                        do {
                            // choice: alt_2
                            {
                                int savedPos_alt_2 = pos;
                                int savedNodes_alt_2 = cst.currentNodeCount();
                                boolean matched_alt_2 = false;
                                boolean cutHit_alt_2 = false;
                                if (!matched_alt_2 && !cutHit_alt_2) {
                                    do {
                                        if (peek() != KIND_INLINE__MINUS_GT) { fail("'->'", RULE_CaseItem_KIND); break; }
                                        advance();
                                        matched_alt_2 = true;
                                    } while (false);
                                    if (!matched_alt_2) {
                                        pos = savedPos_alt_2;
                                        cst.truncate(savedNodes_alt_2);
                                    }
                                }
                                if (!matched_alt_2 && !cutHit_alt_2) {
                                    do {
                                        if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_CaseItem_KIND); break; }
                                        advance();
                                        matched_alt_2 = true;
                                    } while (false);
                                    if (!matched_alt_2) {
                                        pos = savedPos_alt_2;
                                        cst.truncate(savedNodes_alt_2);
                                    }
                                }
                                if (!matched_alt_2 && !cutHit_alt_2) {
                                    do {
                                        if (peek() != KIND_INLINE__COLON) { fail("':'", RULE_CaseItem_KIND); break; }
                                        advance();
                                        matched_alt_2 = true;
                                    } while (false);
                                    if (!matched_alt_2) {
                                        pos = savedPos_alt_2;
                                        cst.truncate(savedNodes_alt_2);
                                    }
                                }
                                if (!matched_alt_2 && !cutHit_alt_2) {
                                    do {
                                        if (peek() != KIND_INLINE_WHEN) { fail("'when'", RULE_CaseItem_KIND); break; }
                                        advance();
                                        matched_alt_2 = true;
                                    } while (false);
                                    if (!matched_alt_2) {
                                        pos = savedPos_alt_2;
                                        cst.truncate(savedNodes_alt_2);
                                    }
                                }
                                if (!matched_alt_2) { fail("<choice>", RULE_CaseItem_KIND); break; }
                            }
                            andOk_and_1 = true;
                        } while (false);
                        pos = savedPos_and_1;
                        cst.truncate(savedNodes_and_1);
                        if (!andOk_and_1) { fail("&<predicate>", RULE_CaseItem_KIND); break; }
                    }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseExpr(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_CaseItem_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parsePattern(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_Pattern_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseRecordPattern(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseTypePattern(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_Pattern_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseTypePattern(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_TypePattern_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    // and-predicate: and_1
                    {
                        int savedPos_and_1 = pos;
                        int savedNodes_and_1 = cst.currentNodeCount();
                        boolean andOk_and_1 = false;
                        do {
                            if (!parseLocalVarType(self)) { break; }
                            if (java.util.Arrays.binarySearch(IDFALL_IDENTIFIER, peek()) < 0) { fail("Identifier", RULE_TypePattern_KIND); break; }
                            advance();
                            andOk_and_1 = true;
                        } while (false);
                        pos = savedPos_and_1;
                        cst.truncate(savedNodes_and_1);
                        if (!andOk_and_1) { fail("&<predicate>", RULE_TypePattern_KIND); break; }
                    }
                    if (!parseLocalVarType(self)) { break; }
                    if (java.util.Arrays.binarySearch(IDFALL_IDENTIFIER, peek()) < 0) { fail("Identifier", RULE_TypePattern_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE__) { fail("'_'", RULE_TypePattern_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_TypePattern_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseRecordPattern(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_RecordPattern_KIND, firstTok, parent);
        if (!parseRefType(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_RecordPattern_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parsePatternList(self)) { break; }
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_RecordPattern_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parsePatternList(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_PatternList_KIND, firstTok, parent);
        if (!parsePattern(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_PatternList_KIND); break; }
                advance();
                if (!parsePattern(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseGuard(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_Guard_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_WHEN) { fail("WhenKW", RULE_Guard_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseExpr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_Expr_KIND, firstTok, parent);
        if (!parseAssignment(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseAssignment(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_Assignment_KIND, firstTok, parent);
        if (!parseTernary(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                // choice: alt_1
                {
                    int savedPos_alt_1 = pos;
                    int savedNodes_alt_1 = cst.currentNodeCount();
                    boolean matched_alt_1 = false;
                    boolean cutHit_alt_1 = false;
                    if (!matched_alt_1 && !cutHit_alt_1) {
                        do {
                            if (peek() != KIND_URSHIFTASSIGN) { fail("URShiftAssign", RULE_Assignment_KIND); break; }
                            advance();
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1 && !cutHit_alt_1) {
                        do {
                            if (peek() != KIND_RSHIFTASSIGN) { fail("RShiftAssign", RULE_Assignment_KIND); break; }
                            advance();
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1 && !cutHit_alt_1) {
                        do {
                            if (!advanceLShiftAssign()) { fail("LShiftAssign", RULE_Assignment_KIND); break; }
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1 && !cutHit_alt_1) {
                        do {
                            if (peek() != KIND_INLINE__EQ) { fail("'='", RULE_Assignment_KIND); break; }
                            advance();
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1 && !cutHit_alt_1) {
                        do {
                            if (peek() != KIND_INLINE__PLUS_EQ) { fail("'+='", RULE_Assignment_KIND); break; }
                            advance();
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1 && !cutHit_alt_1) {
                        do {
                            if (peek() != KIND_INLINE__MINUS_EQ) { fail("'-='", RULE_Assignment_KIND); break; }
                            advance();
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1 && !cutHit_alt_1) {
                        do {
                            if (peek() != KIND_INLINE__STAR_EQ) { fail("'*='", RULE_Assignment_KIND); break; }
                            advance();
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1 && !cutHit_alt_1) {
                        do {
                            if (peek() != KIND_INLINE__SLASH_EQ) { fail("'/='", RULE_Assignment_KIND); break; }
                            advance();
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1 && !cutHit_alt_1) {
                        do {
                            if (peek() != KIND_INLINE__PERCENT_EQ) { fail("'%='", RULE_Assignment_KIND); break; }
                            advance();
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1 && !cutHit_alt_1) {
                        do {
                            if (peek() != KIND_INLINE__AMP_EQ) { fail("'&='", RULE_Assignment_KIND); break; }
                            advance();
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1 && !cutHit_alt_1) {
                        do {
                            if (peek() != KIND_INLINE__PIPE_EQ) { fail("'|='", RULE_Assignment_KIND); break; }
                            advance();
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1 && !cutHit_alt_1) {
                        do {
                            if (peek() != KIND_INLINE__CARET_EQ) { fail("'^='", RULE_Assignment_KIND); break; }
                            advance();
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1) { fail("<choice>", RULE_Assignment_KIND); break; }
                }
                if (!parseAssignment(self)) { break; }
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseTernary(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_Ternary_KIND, firstTok, parent);
        if (!parseLogOr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_INLINE__QMARK) { fail("'?'", RULE_Ternary_KIND); break; }
                advance();
                if (!parseExpr(self)) { break; }
                if (peek() != KIND_INLINE__COLON) { fail("':'", RULE_Ternary_KIND); break; }
                advance();
                if (!parseTernary(self)) { break; }
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseLogOr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_LogOr_KIND, firstTok, parent);
        if (!parseLogAnd(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__PIPE_PIPE) { fail("'||'", RULE_LogOr_KIND); break; }
                advance();
                if (!parseLogAnd(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseLogAnd(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_LogAnd_KIND, firstTok, parent);
        if (!parseBitOr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__AMP_AMP) { fail("'&&'", RULE_LogAnd_KIND); break; }
                advance();
                if (!parseBitOr(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseBitOr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_BitOr_KIND, firstTok, parent);
        if (!parseBitXor(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                // not-predicate: not_1
                {
                    int savedPos_not_1 = pos;
                    int savedNodes_not_1 = cst.currentNodeCount();
                    boolean notMatched_not_1 = false;
                    do {
                        if (peek() != KIND_INLINE__PIPE_PIPE) { fail("'||'", RULE_BitOr_KIND); break; }
                        advance();
                        notMatched_not_1 = true;
                    } while (false);
                    pos = savedPos_not_1;
                    cst.truncate(savedNodes_not_1);
                    if (notMatched_not_1) { fail("!<predicate>", RULE_BitOr_KIND); break; }
                }
                // not-predicate: not_2
                {
                    int savedPos_not_2 = pos;
                    int savedNodes_not_2 = cst.currentNodeCount();
                    boolean notMatched_not_2 = false;
                    do {
                        if (peek() != KIND_INLINE__PIPE_EQ) { fail("'|='", RULE_BitOr_KIND); break; }
                        advance();
                        notMatched_not_2 = true;
                    } while (false);
                    pos = savedPos_not_2;
                    cst.truncate(savedNodes_not_2);
                    if (notMatched_not_2) { fail("!<predicate>", RULE_BitOr_KIND); break; }
                }
                if (peek() != KIND_INLINE__PIPE) { fail("'|'", RULE_BitOr_KIND); break; }
                advance();
                if (!parseBitXor(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseBitXor(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_BitXor_KIND, firstTok, parent);
        if (!parseBitAnd(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                // not-predicate: not_1
                {
                    int savedPos_not_1 = pos;
                    int savedNodes_not_1 = cst.currentNodeCount();
                    boolean notMatched_not_1 = false;
                    do {
                        if (peek() != KIND_INLINE__CARET_EQ) { fail("'^='", RULE_BitXor_KIND); break; }
                        advance();
                        notMatched_not_1 = true;
                    } while (false);
                    pos = savedPos_not_1;
                    cst.truncate(savedNodes_not_1);
                    if (notMatched_not_1) { fail("!<predicate>", RULE_BitXor_KIND); break; }
                }
                if (peek() != KIND_INLINE__CARET) { fail("'^'", RULE_BitXor_KIND); break; }
                advance();
                if (!parseBitAnd(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseBitAnd(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_BitAnd_KIND, firstTok, parent);
        if (!parseEquality(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                // not-predicate: not_1
                {
                    int savedPos_not_1 = pos;
                    int savedNodes_not_1 = cst.currentNodeCount();
                    boolean notMatched_not_1 = false;
                    do {
                        if (peek() != KIND_INLINE__AMP_AMP) { fail("'&&'", RULE_BitAnd_KIND); break; }
                        advance();
                        notMatched_not_1 = true;
                    } while (false);
                    pos = savedPos_not_1;
                    cst.truncate(savedNodes_not_1);
                    if (notMatched_not_1) { fail("!<predicate>", RULE_BitAnd_KIND); break; }
                }
                // not-predicate: not_2
                {
                    int savedPos_not_2 = pos;
                    int savedNodes_not_2 = cst.currentNodeCount();
                    boolean notMatched_not_2 = false;
                    do {
                        if (peek() != KIND_INLINE__AMP_EQ) { fail("'&='", RULE_BitAnd_KIND); break; }
                        advance();
                        notMatched_not_2 = true;
                    } while (false);
                    pos = savedPos_not_2;
                    cst.truncate(savedNodes_not_2);
                    if (notMatched_not_2) { fail("!<predicate>", RULE_BitAnd_KIND); break; }
                }
                if (peek() != KIND_INLINE__AMP) { fail("'&'", RULE_BitAnd_KIND); break; }
                advance();
                if (!parseEquality(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseEquality(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_Equality_KIND, firstTok, parent);
        if (!parseRelational(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                // choice: alt_1
                {
                    int savedPos_alt_1 = pos;
                    int savedNodes_alt_1 = cst.currentNodeCount();
                    boolean matched_alt_1 = false;
                    boolean cutHit_alt_1 = false;
                    if (!matched_alt_1 && !cutHit_alt_1) {
                        do {
                            if (peek() != KIND_INLINE__EQ_EQ) { fail("'=='", RULE_Equality_KIND); break; }
                            advance();
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1 && !cutHit_alt_1) {
                        do {
                            if (peek() != KIND_INLINE__BANG_EQ) { fail("'!='", RULE_Equality_KIND); break; }
                            advance();
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1) { fail("<choice>", RULE_Equality_KIND); break; }
                }
                if (!parseRelational(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseRelational(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_Relational_KIND, firstTok, parent);
        if (!parseShift(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                // choice: alt_1
                {
                    int savedPos_alt_1 = pos;
                    int savedNodes_alt_1 = cst.currentNodeCount();
                    boolean matched_alt_1 = false;
                    boolean cutHit_alt_1 = false;
                    if (!matched_alt_1 && !cutHit_alt_1) {
                        do {
                            // choice: alt_2
                            {
                                int savedPos_alt_2 = pos;
                                int savedNodes_alt_2 = cst.currentNodeCount();
                                boolean matched_alt_2 = false;
                                boolean cutHit_alt_2 = false;
                                if (!matched_alt_2 && !cutHit_alt_2) {
                                    do {
                                        if (peek() != KIND_INLINE__LT_EQ) { fail("'<='", RULE_Relational_KIND); break; }
                                        advance();
                                        matched_alt_2 = true;
                                    } while (false);
                                    if (!matched_alt_2) {
                                        pos = savedPos_alt_2;
                                        cst.truncate(savedNodes_alt_2);
                                    }
                                }
                                if (!matched_alt_2 && !cutHit_alt_2) {
                                    do {
                                        if (peek() != KIND_INLINE__GT_EQ) { fail("'>='", RULE_Relational_KIND); break; }
                                        advance();
                                        matched_alt_2 = true;
                                    } while (false);
                                    if (!matched_alt_2) {
                                        pos = savedPos_alt_2;
                                        cst.truncate(savedNodes_alt_2);
                                    }
                                }
                                if (!matched_alt_2 && !cutHit_alt_2) {
                                    do {
                                        if (peek() != KIND_INLINE__LT) { fail("'<'", RULE_Relational_KIND); break; }
                                        advance();
                                        matched_alt_2 = true;
                                    } while (false);
                                    if (!matched_alt_2) {
                                        pos = savedPos_alt_2;
                                        cst.truncate(savedNodes_alt_2);
                                    }
                                }
                                if (!matched_alt_2 && !cutHit_alt_2) {
                                    do {
                                        if (peek() != KIND_INLINE__GT) { fail("'>'", RULE_Relational_KIND); break; }
                                        advance();
                                        matched_alt_2 = true;
                                    } while (false);
                                    if (!matched_alt_2) {
                                        pos = savedPos_alt_2;
                                        cst.truncate(savedNodes_alt_2);
                                    }
                                }
                                if (!matched_alt_2) { fail("<choice>", RULE_Relational_KIND); break; }
                            }
                            if (!parseShift(self)) { break; }
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1 && !cutHit_alt_1) {
                        do {
                            if (peek() != KIND_INLINE_INSTANCEOF) { fail("'instanceof'", RULE_Relational_KIND); break; }
                            advance();
                            // choice: alt_3
                            {
                                int savedPos_alt_3 = pos;
                                int savedNodes_alt_3 = cst.currentNodeCount();
                                boolean matched_alt_3 = false;
                                boolean cutHit_alt_3 = false;
                                if (!matched_alt_3 && !cutHit_alt_3) {
                                    do {
                                        if (!parsePattern(self)) { break; }
                                        matched_alt_3 = true;
                                    } while (false);
                                    if (!matched_alt_3) {
                                        pos = savedPos_alt_3;
                                        cst.truncate(savedNodes_alt_3);
                                    }
                                }
                                if (!matched_alt_3 && !cutHit_alt_3) {
                                    do {
                                        if (!parseType(self)) { break; }
                                        matched_alt_3 = true;
                                    } while (false);
                                    if (!matched_alt_3) {
                                        pos = savedPos_alt_3;
                                        cst.truncate(savedNodes_alt_3);
                                    }
                                }
                                if (!matched_alt_3) { fail("<choice>", RULE_Relational_KIND); break; }
                            }
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1) { fail("<choice>", RULE_Relational_KIND); break; }
                }
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseShift(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_Shift_KIND, firstTok, parent);
        if (!parseAdditive(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                // choice: alt_1
                {
                    int savedPos_alt_1 = pos;
                    int savedNodes_alt_1 = cst.currentNodeCount();
                    boolean matched_alt_1 = false;
                    boolean cutHit_alt_1 = false;
                    if (!matched_alt_1 && !cutHit_alt_1) {
                        do {
                            if (peek() != KIND_URSHIFT) { fail("URShift", RULE_Shift_KIND); break; }
                            advance();
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1 && !cutHit_alt_1) {
                        do {
                            if (!advanceLShift()) { fail("LShift", RULE_Shift_KIND); break; }
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1 && !cutHit_alt_1) {
                        do {
                            if (peek() != KIND_RSHIFT) { fail("RShift", RULE_Shift_KIND); break; }
                            advance();
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1) { fail("<choice>", RULE_Shift_KIND); break; }
                }
                if (!parseAdditive(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseAdditive(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_Additive_KIND, firstTok, parent);
        if (!parseMultiplicative(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                // choice: alt_1
                {
                    int savedPos_alt_1 = pos;
                    int savedNodes_alt_1 = cst.currentNodeCount();
                    boolean matched_alt_1 = false;
                    boolean cutHit_alt_1 = false;
                    if (!matched_alt_1 && !cutHit_alt_1) {
                        do {
                            // not-predicate: not_2
                            {
                                int savedPos_not_2 = pos;
                                int savedNodes_not_2 = cst.currentNodeCount();
                                boolean notMatched_not_2 = false;
                                do {
                                    if (peek() != KIND_INLINE__PLUS_EQ) { fail("'+='", RULE_Additive_KIND); break; }
                                    advance();
                                    notMatched_not_2 = true;
                                } while (false);
                                pos = savedPos_not_2;
                                cst.truncate(savedNodes_not_2);
                                if (notMatched_not_2) { fail("!<predicate>", RULE_Additive_KIND); break; }
                            }
                            if (peek() != KIND_INLINE__PLUS) { fail("'+'", RULE_Additive_KIND); break; }
                            advance();
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1 && !cutHit_alt_1) {
                        do {
                            // not-predicate: not_3
                            {
                                int savedPos_not_3 = pos;
                                int savedNodes_not_3 = cst.currentNodeCount();
                                boolean notMatched_not_3 = false;
                                do {
                                    if (peek() != KIND_INLINE__MINUS_EQ) { fail("'-='", RULE_Additive_KIND); break; }
                                    advance();
                                    notMatched_not_3 = true;
                                } while (false);
                                pos = savedPos_not_3;
                                cst.truncate(savedNodes_not_3);
                                if (notMatched_not_3) { fail("!<predicate>", RULE_Additive_KIND); break; }
                            }
                            // not-predicate: not_4
                            {
                                int savedPos_not_4 = pos;
                                int savedNodes_not_4 = cst.currentNodeCount();
                                boolean notMatched_not_4 = false;
                                do {
                                    if (peek() != KIND_INLINE__MINUS_GT) { fail("'->'", RULE_Additive_KIND); break; }
                                    advance();
                                    notMatched_not_4 = true;
                                } while (false);
                                pos = savedPos_not_4;
                                cst.truncate(savedNodes_not_4);
                                if (notMatched_not_4) { fail("!<predicate>", RULE_Additive_KIND); break; }
                            }
                            if (peek() != KIND_INLINE__MINUS) { fail("'-'", RULE_Additive_KIND); break; }
                            advance();
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1) { fail("<choice>", RULE_Additive_KIND); break; }
                }
                if (!parseMultiplicative(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseMultiplicative(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_Multiplicative_KIND, firstTok, parent);
        if (!parseUnary(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                // choice: alt_1
                {
                    int savedPos_alt_1 = pos;
                    int savedNodes_alt_1 = cst.currentNodeCount();
                    boolean matched_alt_1 = false;
                    boolean cutHit_alt_1 = false;
                    if (!matched_alt_1 && !cutHit_alt_1) {
                        do {
                            // not-predicate: not_2
                            {
                                int savedPos_not_2 = pos;
                                int savedNodes_not_2 = cst.currentNodeCount();
                                boolean notMatched_not_2 = false;
                                do {
                                    if (peek() != KIND_INLINE__STAR_EQ) { fail("'*='", RULE_Multiplicative_KIND); break; }
                                    advance();
                                    notMatched_not_2 = true;
                                } while (false);
                                pos = savedPos_not_2;
                                cst.truncate(savedNodes_not_2);
                                if (notMatched_not_2) { fail("!<predicate>", RULE_Multiplicative_KIND); break; }
                            }
                            if (peek() != KIND_INLINE__STAR) { fail("'*'", RULE_Multiplicative_KIND); break; }
                            advance();
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1 && !cutHit_alt_1) {
                        do {
                            // not-predicate: not_3
                            {
                                int savedPos_not_3 = pos;
                                int savedNodes_not_3 = cst.currentNodeCount();
                                boolean notMatched_not_3 = false;
                                do {
                                    if (peek() != KIND_INLINE__SLASH_EQ) { fail("'/='", RULE_Multiplicative_KIND); break; }
                                    advance();
                                    notMatched_not_3 = true;
                                } while (false);
                                pos = savedPos_not_3;
                                cst.truncate(savedNodes_not_3);
                                if (notMatched_not_3) { fail("!<predicate>", RULE_Multiplicative_KIND); break; }
                            }
                            if (peek() != KIND_INLINE__SLASH) { fail("'/'", RULE_Multiplicative_KIND); break; }
                            advance();
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1 && !cutHit_alt_1) {
                        do {
                            // not-predicate: not_4
                            {
                                int savedPos_not_4 = pos;
                                int savedNodes_not_4 = cst.currentNodeCount();
                                boolean notMatched_not_4 = false;
                                do {
                                    if (peek() != KIND_INLINE__PERCENT_EQ) { fail("'%='", RULE_Multiplicative_KIND); break; }
                                    advance();
                                    notMatched_not_4 = true;
                                } while (false);
                                pos = savedPos_not_4;
                                cst.truncate(savedNodes_not_4);
                                if (notMatched_not_4) { fail("!<predicate>", RULE_Multiplicative_KIND); break; }
                            }
                            if (peek() != KIND_INLINE__PERCENT) { fail("'%'", RULE_Multiplicative_KIND); break; }
                            advance();
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1) { fail("<choice>", RULE_Multiplicative_KIND); break; }
                }
                if (!parseUnary(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseUnary(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_Unary_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    // choice: alt_1
                    {
                        int savedPos_alt_1 = pos;
                        int savedNodes_alt_1 = cst.currentNodeCount();
                        boolean matched_alt_1 = false;
                        boolean cutHit_alt_1 = false;
                        if (!matched_alt_1 && !cutHit_alt_1) {
                            do {
                                if (peek() != KIND_INLINE__PLUS_PLUS) { fail("'++'", RULE_Unary_KIND); break; }
                                advance();
                                matched_alt_1 = true;
                            } while (false);
                            if (!matched_alt_1) {
                                pos = savedPos_alt_1;
                                cst.truncate(savedNodes_alt_1);
                            }
                        }
                        if (!matched_alt_1 && !cutHit_alt_1) {
                            do {
                                if (peek() != KIND_INLINE__MINUS_MINUS) { fail("'--'", RULE_Unary_KIND); break; }
                                advance();
                                matched_alt_1 = true;
                            } while (false);
                            if (!matched_alt_1) {
                                pos = savedPos_alt_1;
                                cst.truncate(savedNodes_alt_1);
                            }
                        }
                        if (!matched_alt_1 && !cutHit_alt_1) {
                            do {
                                if (peek() != KIND_INLINE__PLUS) { fail("'+'", RULE_Unary_KIND); break; }
                                advance();
                                matched_alt_1 = true;
                            } while (false);
                            if (!matched_alt_1) {
                                pos = savedPos_alt_1;
                                cst.truncate(savedNodes_alt_1);
                            }
                        }
                        if (!matched_alt_1 && !cutHit_alt_1) {
                            do {
                                if (peek() != KIND_INLINE__MINUS) { fail("'-'", RULE_Unary_KIND); break; }
                                advance();
                                matched_alt_1 = true;
                            } while (false);
                            if (!matched_alt_1) {
                                pos = savedPos_alt_1;
                                cst.truncate(savedNodes_alt_1);
                            }
                        }
                        if (!matched_alt_1 && !cutHit_alt_1) {
                            do {
                                if (peek() != KIND_INLINE__BANG) { fail("'!'", RULE_Unary_KIND); break; }
                                advance();
                                matched_alt_1 = true;
                            } while (false);
                            if (!matched_alt_1) {
                                pos = savedPos_alt_1;
                                cst.truncate(savedNodes_alt_1);
                            }
                        }
                        if (!matched_alt_1 && !cutHit_alt_1) {
                            do {
                                if (peek() != KIND_INLINE__TILDE) { fail("'~'", RULE_Unary_KIND); break; }
                                advance();
                                matched_alt_1 = true;
                            } while (false);
                            if (!matched_alt_1) {
                                pos = savedPos_alt_1;
                                cst.truncate(savedNodes_alt_1);
                            }
                        }
                        if (!matched_alt_1) { fail("<choice>", RULE_Unary_KIND); break; }
                    }
                    if (!parseUnary(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_Unary_KIND); break; }
                    advance();
                    if (!parseType(self)) { break; }
                    // zero-or-more: rep_2
                    while (true) {
                        int savedPos_rep_2 = pos;
                        int savedNodes_rep_2 = cst.currentNodeCount();
                        boolean iterOk_rep_2 = false;
                        do {
                            if (peek() != KIND_INLINE__AMP) { fail("'&'", RULE_Unary_KIND); break; }
                            advance();
                            if (!parseType(self)) { break; }
                            iterOk_rep_2 = true;
                        } while (false);
                        if (!iterOk_rep_2) {
                            pos = savedPos_rep_2;
                            cst.truncate(savedNodes_rep_2);
                            break;
                        }
                        if (pos == savedPos_rep_2) break; // guard against infinite loops on zero-width matches
                    }
                    if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_Unary_KIND); break; }
                    advance();
                    if (!parseUnary(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parsePostfix(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_Unary_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parsePostfix(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_Postfix_KIND, firstTok, parent);
        if (!parsePrimary(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (!parsePostOp(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parsePostOp(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_PostOp_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE__DOT) { fail("'.'", RULE_PostOp_KIND); break; }
                    advance();
                    // optional: opt_1
                    {
                        int savedPos_opt_1 = pos;
                        int savedNodes_opt_1 = cst.currentNodeCount();
                        boolean optOk_opt_1 = false;
                        do {
                            if (!parseTypeArgs(self)) { break; }
                            optOk_opt_1 = true;
                        } while (false);
                        if (!optOk_opt_1) {
                            pos = savedPos_opt_1;
                            cst.truncate(savedNodes_opt_1);
                        }
                    }
                    if (java.util.Arrays.binarySearch(IDFALL_IDENTIFIER, peek()) < 0) { fail("Identifier", RULE_PostOp_KIND); break; }
                    advance();
                    // optional: opt_2
                    {
                        int savedPos_opt_2 = pos;
                        int savedNodes_opt_2 = cst.currentNodeCount();
                        boolean optOk_opt_2 = false;
                        do {
                            if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_PostOp_KIND); break; }
                            advance();
                            // optional: opt_3
                            {
                                int savedPos_opt_3 = pos;
                                int savedNodes_opt_3 = cst.currentNodeCount();
                                boolean optOk_opt_3 = false;
                                do {
                                    if (!parseArgs(self)) { break; }
                                    optOk_opt_3 = true;
                                } while (false);
                                if (!optOk_opt_3) {
                                    pos = savedPos_opt_3;
                                    cst.truncate(savedNodes_opt_3);
                                }
                            }
                            if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_PostOp_KIND); break; }
                            advance();
                            optOk_opt_2 = true;
                        } while (false);
                        if (!optOk_opt_2) {
                            pos = savedPos_opt_2;
                            cst.truncate(savedNodes_opt_2);
                        }
                    }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE__DOT) { fail("'.'", RULE_PostOp_KIND); break; }
                    advance();
                    if (peek() != KIND_INLINE_CLASS) { fail("'class'", RULE_PostOp_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE__DOT) { fail("'.'", RULE_PostOp_KIND); break; }
                    advance();
                    if (peek() != KIND_INLINE_THIS) { fail("'this'", RULE_PostOp_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE__LBRACK) { fail("'['", RULE_PostOp_KIND); break; }
                    advance();
                    if (!parseExpr(self)) { break; }
                    if (peek() != KIND_INLINE__RBRACK) { fail("']'", RULE_PostOp_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_PostOp_KIND); break; }
                    advance();
                    // optional: opt_4
                    {
                        int savedPos_opt_4 = pos;
                        int savedNodes_opt_4 = cst.currentNodeCount();
                        boolean optOk_opt_4 = false;
                        do {
                            if (!parseArgs(self)) { break; }
                            optOk_opt_4 = true;
                        } while (false);
                        if (!optOk_opt_4) {
                            pos = savedPos_opt_4;
                            cst.truncate(savedNodes_opt_4);
                        }
                    }
                    if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_PostOp_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE__PLUS_PLUS) { fail("'++'", RULE_PostOp_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE__MINUS_MINUS) { fail("'--'", RULE_PostOp_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE__COLON_COLON) { fail("'::'", RULE_PostOp_KIND); break; }
                    advance();
                    // optional: opt_5
                    {
                        int savedPos_opt_5 = pos;
                        int savedNodes_opt_5 = cst.currentNodeCount();
                        boolean optOk_opt_5 = false;
                        do {
                            if (!parseTypeArgs(self)) { break; }
                            optOk_opt_5 = true;
                        } while (false);
                        if (!optOk_opt_5) {
                            pos = savedPos_opt_5;
                            cst.truncate(savedNodes_opt_5);
                        }
                    }
                    // choice: alt_6
                    {
                        int savedPos_alt_6 = pos;
                        int savedNodes_alt_6 = cst.currentNodeCount();
                        boolean matched_alt_6 = false;
                        boolean cutHit_alt_6 = false;
                        if (!matched_alt_6 && !cutHit_alt_6) {
                            do {
                                if (java.util.Arrays.binarySearch(IDFALL_IDENTIFIER, peek()) < 0) { fail("Identifier", RULE_PostOp_KIND); break; }
                                advance();
                                matched_alt_6 = true;
                            } while (false);
                            if (!matched_alt_6) {
                                pos = savedPos_alt_6;
                                cst.truncate(savedNodes_alt_6);
                            }
                        }
                        if (!matched_alt_6 && !cutHit_alt_6) {
                            do {
                                if (peek() != KIND_INLINE_NEW) { fail("'new'", RULE_PostOp_KIND); break; }
                                advance();
                                matched_alt_6 = true;
                            } while (false);
                            if (!matched_alt_6) {
                                pos = savedPos_alt_6;
                                cst.truncate(savedNodes_alt_6);
                            }
                        }
                        if (!matched_alt_6) { fail("<choice>", RULE_PostOp_KIND); break; }
                    }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_PostOp_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parsePrimary(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_Primary_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseLiteral(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE_THIS) { fail("'this'", RULE_Primary_KIND); break; }
                    advance();
                    // no-op: not-predicate over char-level expression — handled by lexer
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE_SUPER) { fail("'super'", RULE_Primary_KIND); break; }
                    advance();
                    // no-op: not-predicate over char-level expression — handled by lexer
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE_NEW) { fail("'new'", RULE_Primary_KIND); break; }
                    advance();
                    // no-op: not-predicate over char-level expression — handled by lexer
                    // optional: opt_1
                    {
                        int savedPos_opt_1 = pos;
                        int savedNodes_opt_1 = cst.currentNodeCount();
                        boolean optOk_opt_1 = false;
                        do {
                            if (!parseTypeArgs(self)) { break; }
                            optOk_opt_1 = true;
                        } while (false);
                        if (!optOk_opt_1) {
                            pos = savedPos_opt_1;
                            cst.truncate(savedNodes_opt_1);
                        }
                    }
                    if (!parseArrayType(self)) { break; }
                    // choice: alt_2
                    {
                        int savedPos_alt_2 = pos;
                        int savedNodes_alt_2 = cst.currentNodeCount();
                        boolean matched_alt_2 = false;
                        boolean cutHit_alt_2 = false;
                        if (!matched_alt_2 && !cutHit_alt_2) {
                            do {
                                if (!parseDimExprs(self)) { break; }
                                // optional: opt_3
                                {
                                    int savedPos_opt_3 = pos;
                                    int savedNodes_opt_3 = cst.currentNodeCount();
                                    boolean optOk_opt_3 = false;
                                    do {
                                        if (!parseDims(self)) { break; }
                                        optOk_opt_3 = true;
                                    } while (false);
                                    if (!optOk_opt_3) {
                                        pos = savedPos_opt_3;
                                        cst.truncate(savedNodes_opt_3);
                                    }
                                }
                                matched_alt_2 = true;
                            } while (false);
                            if (!matched_alt_2) {
                                pos = savedPos_alt_2;
                                cst.truncate(savedNodes_alt_2);
                            }
                        }
                        if (!matched_alt_2 && !cutHit_alt_2) {
                            do {
                                if (!parseDims(self)) { break; }
                                if (!parseVarInit(self)) { break; }
                                matched_alt_2 = true;
                            } while (false);
                            if (!matched_alt_2) {
                                pos = savedPos_alt_2;
                                cst.truncate(savedNodes_alt_2);
                            }
                        }
                        if (!matched_alt_2 && !cutHit_alt_2) {
                            do {
                                if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_Primary_KIND); break; }
                                advance();
                                // optional: opt_4
                                {
                                    int savedPos_opt_4 = pos;
                                    int savedNodes_opt_4 = cst.currentNodeCount();
                                    boolean optOk_opt_4 = false;
                                    do {
                                        if (!parseArgs(self)) { break; }
                                        optOk_opt_4 = true;
                                    } while (false);
                                    if (!optOk_opt_4) {
                                        pos = savedPos_opt_4;
                                        cst.truncate(savedNodes_opt_4);
                                    }
                                }
                                if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_Primary_KIND); break; }
                                advance();
                                // optional: opt_5
                                {
                                    int savedPos_opt_5 = pos;
                                    int savedNodes_opt_5 = cst.currentNodeCount();
                                    boolean optOk_opt_5 = false;
                                    do {
                                        if (!parseClassBody(self)) { break; }
                                        optOk_opt_5 = true;
                                    } while (false);
                                    if (!optOk_opt_5) {
                                        pos = savedPos_opt_5;
                                        cst.truncate(savedNodes_opt_5);
                                    }
                                }
                                matched_alt_2 = true;
                            } while (false);
                            if (!matched_alt_2) {
                                pos = savedPos_alt_2;
                                cst.truncate(savedNodes_alt_2);
                            }
                        }
                        if (!matched_alt_2) { fail("<choice>", RULE_Primary_KIND); break; }
                    }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_SWITCH) { fail("SwitchKW", RULE_Primary_KIND); break; } }
                    advance();
                    if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_Primary_KIND); break; }
                    advance();
                    if (!parseExpr(self)) { break; }
                    if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_Primary_KIND); break; }
                    advance();
                    if (!parseSwitchBlock(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseLambda(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_Primary_KIND); break; }
                    advance();
                    if (!parseExpr(self)) { break; }
                    if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_Primary_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseTypeExpr(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseQualifiedName(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_Primary_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseTypeExpr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_TypeExpr_KIND, firstTok, parent);
        if (!parseType(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE__DOT) { fail("'.'", RULE_TypeExpr_KIND); break; }
                    advance();
                    if (peek() != KIND_INLINE_CLASS) { fail("'class'", RULE_TypeExpr_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE__COLON_COLON) { fail("'::'", RULE_TypeExpr_KIND); break; }
                    advance();
                    // optional: opt_1
                    {
                        int savedPos_opt_1 = pos;
                        int savedNodes_opt_1 = cst.currentNodeCount();
                        boolean optOk_opt_1 = false;
                        do {
                            if (!parseTypeArgs(self)) { break; }
                            optOk_opt_1 = true;
                        } while (false);
                        if (!optOk_opt_1) {
                            pos = savedPos_opt_1;
                            cst.truncate(savedNodes_opt_1);
                        }
                    }
                    // choice: alt_2
                    {
                        int savedPos_alt_2 = pos;
                        int savedNodes_alt_2 = cst.currentNodeCount();
                        boolean matched_alt_2 = false;
                        boolean cutHit_alt_2 = false;
                        if (!matched_alt_2 && !cutHit_alt_2) {
                            do {
                                if (peek() != KIND_INLINE_NEW) { fail("'new'", RULE_TypeExpr_KIND); break; }
                                advance();
                                matched_alt_2 = true;
                            } while (false);
                            if (!matched_alt_2) {
                                pos = savedPos_alt_2;
                                cst.truncate(savedNodes_alt_2);
                            }
                        }
                        if (!matched_alt_2 && !cutHit_alt_2) {
                            do {
                                if (java.util.Arrays.binarySearch(IDFALL_IDENTIFIER, peek()) < 0) { fail("Identifier", RULE_TypeExpr_KIND); break; }
                                advance();
                                matched_alt_2 = true;
                            } while (false);
                            if (!matched_alt_2) {
                                pos = savedPos_alt_2;
                                cst.truncate(savedNodes_alt_2);
                            }
                        }
                        if (!matched_alt_2) { fail("<choice>", RULE_TypeExpr_KIND); break; }
                    }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_TypeExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseLambda(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_Lambda_KIND, firstTok, parent);
        if (!parseLambdaParams(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__MINUS_GT) { fail("'->'", RULE_Lambda_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseExpr(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseBlock(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_Lambda_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseLambdaParams(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_LambdaParams_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (java.util.Arrays.binarySearch(IDFALL_IDENTIFIER, peek()) < 0) { fail("Identifier", RULE_LambdaParams_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE__) { fail("'_'", RULE_LambdaParams_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_LambdaParams_KIND); break; }
                    advance();
                    // optional: opt_1
                    {
                        int savedPos_opt_1 = pos;
                        int savedNodes_opt_1 = cst.currentNodeCount();
                        boolean optOk_opt_1 = false;
                        do {
                            if (!parseLambdaParam(self)) { break; }
                            optOk_opt_1 = true;
                        } while (false);
                        if (!optOk_opt_1) {
                            pos = savedPos_opt_1;
                            cst.truncate(savedNodes_opt_1);
                        }
                    }
                    // zero-or-more: rep_2
                    while (true) {
                        int savedPos_rep_2 = pos;
                        int savedNodes_rep_2 = cst.currentNodeCount();
                        boolean iterOk_rep_2 = false;
                        do {
                            if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_LambdaParams_KIND); break; }
                            advance();
                            if (!parseLambdaParam(self)) { break; }
                            iterOk_rep_2 = true;
                        } while (false);
                        if (!iterOk_rep_2) {
                            pos = savedPos_rep_2;
                            cst.truncate(savedNodes_rep_2);
                            break;
                        }
                        if (pos == savedPos_rep_2) break; // guard against infinite loops on zero-width matches
                    }
                    if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_LambdaParams_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_LambdaParams_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseLambdaParam(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_LambdaParam_KIND, firstTok, parent);
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (!parseAnnotation(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        // zero-or-more: rep_1
        while (true) {
            int savedPos_rep_1 = pos;
            int savedNodes_rep_1 = cst.currentNodeCount();
            boolean iterOk_rep_1 = false;
            do {
                if (java.util.Arrays.binarySearch(ALIAS_MODIFIER, peek()) < 0) { fail("Modifier", RULE_LambdaParam_KIND); break; }
                advance();
                iterOk_rep_1 = true;
            } while (false);
            if (!iterOk_rep_1) {
                pos = savedPos_rep_1;
                cst.truncate(savedNodes_rep_1);
                break;
            }
            if (pos == savedPos_rep_1) break; // guard against infinite loops on zero-width matches
        }
        // optional: opt_2
        {
            int savedPos_opt_2 = pos;
            int savedNodes_opt_2 = cst.currentNodeCount();
            boolean optOk_opt_2 = false;
            do {
                // choice: alt_3
                {
                    int savedPos_alt_3 = pos;
                    int savedNodes_alt_3 = cst.currentNodeCount();
                    boolean matched_alt_3 = false;
                    boolean cutHit_alt_3 = false;
                    if (!matched_alt_3 && !cutHit_alt_3) {
                        do {
                            if (peek() != KIND_INLINE_VAR) { fail("'var'", RULE_LambdaParam_KIND); break; }
                            advance();
                            // no-op: not-predicate over char-level expression — handled by lexer
                            matched_alt_3 = true;
                        } while (false);
                        if (!matched_alt_3) {
                            pos = savedPos_alt_3;
                            cst.truncate(savedNodes_alt_3);
                        }
                    }
                    if (!matched_alt_3 && !cutHit_alt_3) {
                        do {
                            if (!parseType(self)) { break; }
                            matched_alt_3 = true;
                        } while (false);
                        if (!matched_alt_3) {
                            pos = savedPos_alt_3;
                            cst.truncate(savedNodes_alt_3);
                        }
                    }
                    if (!matched_alt_3) { fail("<choice>", RULE_LambdaParam_KIND); break; }
                }
                // and-predicate: and_4
                {
                    int savedPos_and_4 = pos;
                    int savedNodes_and_4 = cst.currentNodeCount();
                    boolean andOk_and_4 = false;
                    do {
                        // choice: alt_5
                        {
                            int savedPos_alt_5 = pos;
                            int savedNodes_alt_5 = cst.currentNodeCount();
                            boolean matched_alt_5 = false;
                            boolean cutHit_alt_5 = false;
                            if (!matched_alt_5 && !cutHit_alt_5) {
                                do {
                                    if (peek() != KIND_INLINE__DOT_DOT_DOT) { fail("'...'", RULE_LambdaParam_KIND); break; }
                                    advance();
                                    matched_alt_5 = true;
                                } while (false);
                                if (!matched_alt_5) {
                                    pos = savedPos_alt_5;
                                    cst.truncate(savedNodes_alt_5);
                                }
                            }
                            if (!matched_alt_5 && !cutHit_alt_5) {
                                do {
                                    if (java.util.Arrays.binarySearch(IDFALL_IDENTIFIER, peek()) < 0) { fail("Identifier", RULE_LambdaParam_KIND); break; }
                                    advance();
                                    matched_alt_5 = true;
                                } while (false);
                                if (!matched_alt_5) {
                                    pos = savedPos_alt_5;
                                    cst.truncate(savedNodes_alt_5);
                                }
                            }
                            if (!matched_alt_5 && !cutHit_alt_5) {
                                do {
                                    if (peek() != KIND_INLINE__) { fail("'_'", RULE_LambdaParam_KIND); break; }
                                    advance();
                                    matched_alt_5 = true;
                                } while (false);
                                if (!matched_alt_5) {
                                    pos = savedPos_alt_5;
                                    cst.truncate(savedNodes_alt_5);
                                }
                            }
                            if (!matched_alt_5) { fail("<choice>", RULE_LambdaParam_KIND); break; }
                        }
                        andOk_and_4 = true;
                    } while (false);
                    pos = savedPos_and_4;
                    cst.truncate(savedNodes_and_4);
                    if (!andOk_and_4) { fail("&<predicate>", RULE_LambdaParam_KIND); break; }
                }
                optOk_opt_2 = true;
            } while (false);
            if (!optOk_opt_2) {
                pos = savedPos_opt_2;
                cst.truncate(savedNodes_opt_2);
            }
        }
        // optional: opt_6
        {
            int savedPos_opt_6 = pos;
            int savedNodes_opt_6 = cst.currentNodeCount();
            boolean optOk_opt_6 = false;
            do {
                if (peek() != KIND_INLINE__DOT_DOT_DOT) { fail("'...'", RULE_LambdaParam_KIND); break; }
                advance();
                optOk_opt_6 = true;
            } while (false);
            if (!optOk_opt_6) {
                pos = savedPos_opt_6;
                cst.truncate(savedNodes_opt_6);
            }
        }
        // choice: alt_7
        {
            int savedPos_alt_7 = pos;
            int savedNodes_alt_7 = cst.currentNodeCount();
            boolean matched_alt_7 = false;
            boolean cutHit_alt_7 = false;
            if (!matched_alt_7 && !cutHit_alt_7) {
                do {
                    if (java.util.Arrays.binarySearch(IDFALL_IDENTIFIER, peek()) < 0) { fail("Identifier", RULE_LambdaParam_KIND); break; }
                    advance();
                    matched_alt_7 = true;
                } while (false);
                if (!matched_alt_7) {
                    pos = savedPos_alt_7;
                    cst.truncate(savedNodes_alt_7);
                }
            }
            if (!matched_alt_7 && !cutHit_alt_7) {
                do {
                    if (peek() != KIND_INLINE__) { fail("'_'", RULE_LambdaParam_KIND); break; }
                    advance();
                    matched_alt_7 = true;
                } while (false);
                if (!matched_alt_7) {
                    pos = savedPos_alt_7;
                    cst.truncate(savedNodes_alt_7);
                }
            }
            if (!matched_alt_7) { fail("<choice>", RULE_LambdaParam_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseArgs(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_Args_KIND, firstTok, parent);
        if (!parseExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_Args_KIND); break; }
                advance();
                if (!parseExpr(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseExprList(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ExprList_KIND, firstTok, parent);
        if (!parseExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_ExprList_KIND); break; }
                advance();
                if (!parseExpr(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseType(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_Type_KIND, firstTok, parent);
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (!parseAnnotation(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        // choice: alt_1
        {
            int savedPos_alt_1 = pos;
            int savedNodes_alt_1 = cst.currentNodeCount();
            boolean matched_alt_1 = false;
            boolean cutHit_alt_1 = false;
            if (!matched_alt_1 && !cutHit_alt_1) {
                do {
                    if (java.util.Arrays.binarySearch(ALIAS_PRIMTYPE, peek()) < 0) { fail("PrimType", RULE_Type_KIND); break; }
                    advance();
                    matched_alt_1 = true;
                } while (false);
                if (!matched_alt_1) {
                    pos = savedPos_alt_1;
                    cst.truncate(savedNodes_alt_1);
                }
            }
            if (!matched_alt_1 && !cutHit_alt_1) {
                do {
                    if (!parseRefType(self)) { break; }
                    matched_alt_1 = true;
                } while (false);
                if (!matched_alt_1) {
                    pos = savedPos_alt_1;
                    cst.truncate(savedNodes_alt_1);
                }
            }
            if (!matched_alt_1) { fail("<choice>", RULE_Type_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        // optional: opt_2
        {
            int savedPos_opt_2 = pos;
            int savedNodes_opt_2 = cst.currentNodeCount();
            boolean optOk_opt_2 = false;
            do {
                if (!parseDims(self)) { break; }
                optOk_opt_2 = true;
            } while (false);
            if (!optOk_opt_2) {
                pos = savedPos_opt_2;
                cst.truncate(savedNodes_opt_2);
            }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseRefType(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_RefType_KIND, firstTok, parent);
        if (!parseAnnotatedTypeName(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                // and-predicate: and_1
                {
                    int savedPos_and_1 = pos;
                    int savedNodes_and_1 = cst.currentNodeCount();
                    boolean andOk_and_1 = false;
                    do {
                        if (peek() != KIND_INLINE__DOT) { fail("'.'", RULE_RefType_KIND); break; }
                        advance();
                        // choice: alt_2
                        {
                            int savedPos_alt_2 = pos;
                            int savedNodes_alt_2 = cst.currentNodeCount();
                            boolean matched_alt_2 = false;
                            boolean cutHit_alt_2 = false;
                            if (!matched_alt_2 && !cutHit_alt_2) {
                                do {
                                    if (peek() != KIND_INLINE__AT) { fail("'@'", RULE_RefType_KIND); break; }
                                    advance();
                                    matched_alt_2 = true;
                                } while (false);
                                if (!matched_alt_2) {
                                    pos = savedPos_alt_2;
                                    cst.truncate(savedNodes_alt_2);
                                }
                            }
                            if (!matched_alt_2 && !cutHit_alt_2) {
                                do {
                                    if (java.util.Arrays.binarySearch(IDFALL_IDENTIFIER, peek()) < 0) { fail("Identifier", RULE_RefType_KIND); break; }
                                    advance();
                                    matched_alt_2 = true;
                                } while (false);
                                if (!matched_alt_2) {
                                    pos = savedPos_alt_2;
                                    cst.truncate(savedNodes_alt_2);
                                }
                            }
                            if (!matched_alt_2) { fail("<choice>", RULE_RefType_KIND); break; }
                        }
                        andOk_and_1 = true;
                    } while (false);
                    pos = savedPos_and_1;
                    cst.truncate(savedNodes_and_1);
                    if (!andOk_and_1) { fail("&<predicate>", RULE_RefType_KIND); break; }
                }
                if (peek() != KIND_INLINE__DOT) { fail("'.'", RULE_RefType_KIND); break; }
                advance();
                if (!parseAnnotatedTypeName(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseAnnotatedTypeName(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_AnnotatedTypeName_KIND, firstTok, parent);
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (!parseAnnotation(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        if (java.util.Arrays.binarySearch(IDFALL_IDENTIFIER, peek()) < 0) { fail("Identifier", RULE_AnnotatedTypeName_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                if (!parseTypeArgs(self)) { break; }
                optOk_opt_1 = true;
            } while (false);
            if (!optOk_opt_1) {
                pos = savedPos_opt_1;
                cst.truncate(savedNodes_opt_1);
            }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseDims(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_Dims_KIND, firstTok, parent);
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (!parseAnnotation(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        if (peek() != KIND_INLINE__LBRACK) { fail("'['", RULE_Dims_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (peek() != KIND_INLINE__RBRACK) { fail("']'", RULE_Dims_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // zero-or-more: rep_1
        while (true) {
            int savedPos_rep_1 = pos;
            int savedNodes_rep_1 = cst.currentNodeCount();
            boolean iterOk_rep_1 = false;
            do {
                // zero-or-more: rep_2
                while (true) {
                    int savedPos_rep_2 = pos;
                    int savedNodes_rep_2 = cst.currentNodeCount();
                    boolean iterOk_rep_2 = false;
                    do {
                        if (!parseAnnotation(self)) { break; }
                        iterOk_rep_2 = true;
                    } while (false);
                    if (!iterOk_rep_2) {
                        pos = savedPos_rep_2;
                        cst.truncate(savedNodes_rep_2);
                        break;
                    }
                    if (pos == savedPos_rep_2) break; // guard against infinite loops on zero-width matches
                }
                if (peek() != KIND_INLINE__LBRACK) { fail("'['", RULE_Dims_KIND); break; }
                advance();
                if (peek() != KIND_INLINE__RBRACK) { fail("']'", RULE_Dims_KIND); break; }
                advance();
                iterOk_rep_1 = true;
            } while (false);
            if (!iterOk_rep_1) {
                pos = savedPos_rep_1;
                cst.truncate(savedNodes_rep_1);
                break;
            }
            if (pos == savedPos_rep_1) break; // guard against infinite loops on zero-width matches
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseArrayType(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ArrayType_KIND, firstTok, parent);
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (!parseAnnotation(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        // choice: alt_1
        {
            int savedPos_alt_1 = pos;
            int savedNodes_alt_1 = cst.currentNodeCount();
            boolean matched_alt_1 = false;
            boolean cutHit_alt_1 = false;
            if (!matched_alt_1 && !cutHit_alt_1) {
                do {
                    if (java.util.Arrays.binarySearch(ALIAS_PRIMTYPE, peek()) < 0) { fail("PrimType", RULE_ArrayType_KIND); break; }
                    advance();
                    matched_alt_1 = true;
                } while (false);
                if (!matched_alt_1) {
                    pos = savedPos_alt_1;
                    cst.truncate(savedNodes_alt_1);
                }
            }
            if (!matched_alt_1 && !cutHit_alt_1) {
                do {
                    if (!parseRefType(self)) { break; }
                    matched_alt_1 = true;
                } while (false);
                if (!matched_alt_1) {
                    pos = savedPos_alt_1;
                    cst.truncate(savedNodes_alt_1);
                }
            }
            if (!matched_alt_1) { fail("<choice>", RULE_ArrayType_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseDimExprs(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_DimExprs_KIND, firstTok, parent);
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (!parseAnnotation(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        // and-predicate: and_1
        {
            int savedPos_and_1 = pos;
            int savedNodes_and_1 = cst.currentNodeCount();
            boolean andOk_and_1 = false;
            do {
                if (peek() != KIND_INLINE__LBRACK) { fail("'['", RULE_DimExprs_KIND); break; }
                advance();
                // not-predicate: not_2
                {
                    int savedPos_not_2 = pos;
                    int savedNodes_not_2 = cst.currentNodeCount();
                    boolean notMatched_not_2 = false;
                    do {
                        if (peek() != KIND_INLINE__RBRACK) { fail("']'", RULE_DimExprs_KIND); break; }
                        advance();
                        notMatched_not_2 = true;
                    } while (false);
                    pos = savedPos_not_2;
                    cst.truncate(savedNodes_not_2);
                    if (notMatched_not_2) { fail("!<predicate>", RULE_DimExprs_KIND); break; }
                }
                andOk_and_1 = true;
            } while (false);
            pos = savedPos_and_1;
            cst.truncate(savedNodes_and_1);
            if (!andOk_and_1) { fail("&<predicate>", RULE_DimExprs_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        if (peek() != KIND_INLINE__LBRACK) { fail("'['", RULE_DimExprs_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__RBRACK) { fail("']'", RULE_DimExprs_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // zero-or-more: rep_3
        while (true) {
            int savedPos_rep_3 = pos;
            int savedNodes_rep_3 = cst.currentNodeCount();
            boolean iterOk_rep_3 = false;
            do {
                // zero-or-more: rep_4
                while (true) {
                    int savedPos_rep_4 = pos;
                    int savedNodes_rep_4 = cst.currentNodeCount();
                    boolean iterOk_rep_4 = false;
                    do {
                        if (!parseAnnotation(self)) { break; }
                        iterOk_rep_4 = true;
                    } while (false);
                    if (!iterOk_rep_4) {
                        pos = savedPos_rep_4;
                        cst.truncate(savedNodes_rep_4);
                        break;
                    }
                    if (pos == savedPos_rep_4) break; // guard against infinite loops on zero-width matches
                }
                // and-predicate: and_5
                {
                    int savedPos_and_5 = pos;
                    int savedNodes_and_5 = cst.currentNodeCount();
                    boolean andOk_and_5 = false;
                    do {
                        if (peek() != KIND_INLINE__LBRACK) { fail("'['", RULE_DimExprs_KIND); break; }
                        advance();
                        // not-predicate: not_6
                        {
                            int savedPos_not_6 = pos;
                            int savedNodes_not_6 = cst.currentNodeCount();
                            boolean notMatched_not_6 = false;
                            do {
                                if (peek() != KIND_INLINE__RBRACK) { fail("']'", RULE_DimExprs_KIND); break; }
                                advance();
                                notMatched_not_6 = true;
                            } while (false);
                            pos = savedPos_not_6;
                            cst.truncate(savedNodes_not_6);
                            if (notMatched_not_6) { fail("!<predicate>", RULE_DimExprs_KIND); break; }
                        }
                        andOk_and_5 = true;
                    } while (false);
                    pos = savedPos_and_5;
                    cst.truncate(savedNodes_and_5);
                    if (!andOk_and_5) { fail("&<predicate>", RULE_DimExprs_KIND); break; }
                }
                if (peek() != KIND_INLINE__LBRACK) { fail("'['", RULE_DimExprs_KIND); break; }
                advance();
                if (!parseExpr(self)) { break; }
                if (peek() != KIND_INLINE__RBRACK) { fail("']'", RULE_DimExprs_KIND); break; }
                advance();
                iterOk_rep_3 = true;
            } while (false);
            if (!iterOk_rep_3) {
                pos = savedPos_rep_3;
                cst.truncate(savedNodes_rep_3);
                break;
            }
            if (pos == savedPos_rep_3) break; // guard against infinite loops on zero-width matches
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseTypeArgs(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_TypeArgs_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE__LT) { fail("'<'", RULE_TypeArgs_KIND); break; }
                    advance();
                    if (peek() != KIND_INLINE__GT) { fail("'>'", RULE_TypeArgs_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE__LT) { fail("'<'", RULE_TypeArgs_KIND); break; }
                    advance();
                    if (!parseTypeArg(self)) { break; }
                    // zero-or-more: rep_1
                    while (true) {
                        if (splitGt > 0) break; // phantom '>' available — at closing position
                        int savedPos_rep_1 = pos;
                        int savedNodes_rep_1 = cst.currentNodeCount();
                        boolean iterOk_rep_1 = false;
                        do {
                            if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_TypeArgs_KIND); break; }
                            advance();
                            if (!parseTypeArg(self)) { break; }
                            iterOk_rep_1 = true;
                        } while (false);
                        if (!iterOk_rep_1) {
                            pos = savedPos_rep_1;
                            cst.truncate(savedNodes_rep_1);
                            break;
                        }
                        if (pos == savedPos_rep_1) break; // guard against infinite loops on zero-width matches
                    }
                    if (!advanceGt()) { fail("'>'", RULE_TypeArgs_KIND); break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_TypeArgs_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseTypeArg(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_TypeArg_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseType(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE__QMARK) { fail("'?'", RULE_TypeArg_KIND); break; }
                    advance();
                    // optional: opt_1
                    {
                        int savedPos_opt_1 = pos;
                        int savedNodes_opt_1 = cst.currentNodeCount();
                        boolean optOk_opt_1 = false;
                        do {
                            // zero-or-more: rep_2
                            while (true) {
                                int savedPos_rep_2 = pos;
                                int savedNodes_rep_2 = cst.currentNodeCount();
                                boolean iterOk_rep_2 = false;
                                do {
                                    if (!parseAnnotation(self)) { break; }
                                    iterOk_rep_2 = true;
                                } while (false);
                                if (!iterOk_rep_2) {
                                    pos = savedPos_rep_2;
                                    cst.truncate(savedNodes_rep_2);
                                    break;
                                }
                                if (pos == savedPos_rep_2) break; // guard against infinite loops on zero-width matches
                            }
                            // choice: alt_3
                            {
                                int savedPos_alt_3 = pos;
                                int savedNodes_alt_3 = cst.currentNodeCount();
                                boolean matched_alt_3 = false;
                                boolean cutHit_alt_3 = false;
                                if (!matched_alt_3 && !cutHit_alt_3) {
                                    do {
                                        if (peek() != KIND_INLINE_EXTENDS) { fail("'extends'", RULE_TypeArg_KIND); break; }
                                        advance();
                                        matched_alt_3 = true;
                                    } while (false);
                                    if (!matched_alt_3) {
                                        pos = savedPos_alt_3;
                                        cst.truncate(savedNodes_alt_3);
                                    }
                                }
                                if (!matched_alt_3 && !cutHit_alt_3) {
                                    do {
                                        if (peek() != KIND_INLINE_SUPER) { fail("'super'", RULE_TypeArg_KIND); break; }
                                        advance();
                                        matched_alt_3 = true;
                                    } while (false);
                                    if (!matched_alt_3) {
                                        pos = savedPos_alt_3;
                                        cst.truncate(savedNodes_alt_3);
                                    }
                                }
                                if (!matched_alt_3) { fail("<choice>", RULE_TypeArg_KIND); break; }
                            }
                            if (!parseType(self)) { break; }
                            optOk_opt_1 = true;
                        } while (false);
                        if (!optOk_opt_1) {
                            pos = savedPos_opt_1;
                            cst.truncate(savedNodes_opt_1);
                        }
                    }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_TypeArg_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseQualifiedName(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_QualifiedName_KIND, firstTok, parent);
        if (java.util.Arrays.binarySearch(IDFALL_IDENTIFIER, peek()) < 0) { fail("Identifier", RULE_QualifiedName_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                // and-predicate: and_1
                {
                    int savedPos_and_1 = pos;
                    int savedNodes_and_1 = cst.currentNodeCount();
                    boolean andOk_and_1 = false;
                    do {
                        if (peek() != KIND_INLINE__DOT) { fail("'.'", RULE_QualifiedName_KIND); break; }
                        advance();
                        if (java.util.Arrays.binarySearch(IDFALL_IDENTIFIER, peek()) < 0) { fail("Identifier", RULE_QualifiedName_KIND); break; }
                        advance();
                        andOk_and_1 = true;
                    } while (false);
                    pos = savedPos_and_1;
                    cst.truncate(savedNodes_and_1);
                    if (!andOk_and_1) { fail("&<predicate>", RULE_QualifiedName_KIND); break; }
                }
                if (peek() != KIND_INLINE__DOT) { fail("'.'", RULE_QualifiedName_KIND); break; }
                advance();
                if (java.util.Arrays.binarySearch(IDFALL_IDENTIFIER, peek()) < 0) { fail("Identifier", RULE_QualifiedName_KIND); break; }
                advance();
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseAnnotation(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_Annotation_KIND, firstTok, parent);
        if (peek() != KIND_INLINE__AT) { fail("'@'", RULE_Annotation_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // not-predicate: not_0
        {
            int savedPos_not_0 = pos;
            int savedNodes_not_0 = cst.currentNodeCount();
            boolean notMatched_not_0 = false;
            do {
                if (peek() != KIND_INLINE_INTERFACE) { fail("'interface'", RULE_Annotation_KIND); break; }
                advance();
                notMatched_not_0 = true;
            } while (false);
            pos = savedPos_not_0;
            cst.truncate(savedNodes_not_0);
            if (notMatched_not_0) { fail("!<predicate>", RULE_Annotation_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        if (!parseQualifiedName(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_Annotation_KIND); break; }
                advance();
                // optional: opt_2
                {
                    int savedPos_opt_2 = pos;
                    int savedNodes_opt_2 = cst.currentNodeCount();
                    boolean optOk_opt_2 = false;
                    do {
                        if (!parseAnnotationValue(self)) { break; }
                        optOk_opt_2 = true;
                    } while (false);
                    if (!optOk_opt_2) {
                        pos = savedPos_opt_2;
                        cst.truncate(savedNodes_opt_2);
                    }
                }
                if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_Annotation_KIND); break; }
                advance();
                optOk_opt_1 = true;
            } while (false);
            if (!optOk_opt_1) {
                pos = savedPos_opt_1;
                cst.truncate(savedNodes_opt_1);
            }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseAnnotationValue(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_AnnotationValue_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (java.util.Arrays.binarySearch(IDFALL_IDENTIFIER, peek()) < 0) { fail("Identifier", RULE_AnnotationValue_KIND); break; }
                    advance();
                    if (peek() != KIND_INLINE__EQ) { fail("'='", RULE_AnnotationValue_KIND); break; }
                    advance();
                    if (!parseAnnotationElem(self)) { break; }
                    // zero-or-more: rep_1
                    while (true) {
                        int savedPos_rep_1 = pos;
                        int savedNodes_rep_1 = cst.currentNodeCount();
                        boolean iterOk_rep_1 = false;
                        do {
                            if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_AnnotationValue_KIND); break; }
                            advance();
                            if (java.util.Arrays.binarySearch(IDFALL_IDENTIFIER, peek()) < 0) { fail("Identifier", RULE_AnnotationValue_KIND); break; }
                            advance();
                            if (peek() != KIND_INLINE__EQ) { fail("'='", RULE_AnnotationValue_KIND); break; }
                            advance();
                            if (!parseAnnotationElem(self)) { break; }
                            iterOk_rep_1 = true;
                        } while (false);
                        if (!iterOk_rep_1) {
                            pos = savedPos_rep_1;
                            cst.truncate(savedNodes_rep_1);
                            break;
                        }
                        if (pos == savedPos_rep_1) break; // guard against infinite loops on zero-width matches
                    }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseAnnotationElem(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_AnnotationValue_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseAnnotationElem(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_AnnotationElem_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseAnnotation(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE__LBRACE) { fail("'{'", RULE_AnnotationElem_KIND); break; }
                    advance();
                    // optional: opt_1
                    {
                        int savedPos_opt_1 = pos;
                        int savedNodes_opt_1 = cst.currentNodeCount();
                        boolean optOk_opt_1 = false;
                        do {
                            if (!parseAnnotationElem(self)) { break; }
                            // zero-or-more: rep_2
                            while (true) {
                                int savedPos_rep_2 = pos;
                                int savedNodes_rep_2 = cst.currentNodeCount();
                                boolean iterOk_rep_2 = false;
                                do {
                                    if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_AnnotationElem_KIND); break; }
                                    advance();
                                    if (!parseAnnotationElem(self)) { break; }
                                    iterOk_rep_2 = true;
                                } while (false);
                                if (!iterOk_rep_2) {
                                    pos = savedPos_rep_2;
                                    cst.truncate(savedNodes_rep_2);
                                    break;
                                }
                                if (pos == savedPos_rep_2) break; // guard against infinite loops on zero-width matches
                            }
                            // optional: opt_3
                            {
                                int savedPos_opt_3 = pos;
                                int savedNodes_opt_3 = cst.currentNodeCount();
                                boolean optOk_opt_3 = false;
                                do {
                                    if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_AnnotationElem_KIND); break; }
                                    advance();
                                    optOk_opt_3 = true;
                                } while (false);
                                if (!optOk_opt_3) {
                                    pos = savedPos_opt_3;
                                    cst.truncate(savedNodes_opt_3);
                                }
                            }
                            optOk_opt_1 = true;
                        } while (false);
                        if (!optOk_opt_1) {
                            pos = savedPos_opt_1;
                            cst.truncate(savedNodes_opt_1);
                        }
                    }
                    if (peek() != KIND_INLINE__RBRACE) { fail("'}'", RULE_AnnotationElem_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseTernary(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_AnnotationElem_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseLiteral(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_Literal_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    // choice: alt_1
                    {
                        int savedPos_alt_1 = pos;
                        int savedNodes_alt_1 = cst.currentNodeCount();
                        boolean matched_alt_1 = false;
                        boolean cutHit_alt_1 = false;
                        if (!matched_alt_1 && !cutHit_alt_1) {
                            do {
                                if (peek() != KIND_INLINE_NULL) { fail("'null'", RULE_Literal_KIND); break; }
                                advance();
                                matched_alt_1 = true;
                            } while (false);
                            if (!matched_alt_1) {
                                pos = savedPos_alt_1;
                                cst.truncate(savedNodes_alt_1);
                            }
                        }
                        if (!matched_alt_1 && !cutHit_alt_1) {
                            do {
                                if (peek() != KIND_INLINE_TRUE) { fail("'true'", RULE_Literal_KIND); break; }
                                advance();
                                matched_alt_1 = true;
                            } while (false);
                            if (!matched_alt_1) {
                                pos = savedPos_alt_1;
                                cst.truncate(savedNodes_alt_1);
                            }
                        }
                        if (!matched_alt_1 && !cutHit_alt_1) {
                            do {
                                if (peek() != KIND_INLINE_FALSE) { fail("'false'", RULE_Literal_KIND); break; }
                                advance();
                                matched_alt_1 = true;
                            } while (false);
                            if (!matched_alt_1) {
                                pos = savedPos_alt_1;
                                cst.truncate(savedNodes_alt_1);
                            }
                        }
                        if (!matched_alt_1) { fail("<choice>", RULE_Literal_KIND); break; }
                    }
                    // no-op: not-predicate over char-level expression — handled by lexer
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_CHARLIT) { fail("CharLit", RULE_Literal_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_STRINGLIT) { fail("StringLit", RULE_Literal_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_NUMLIT) { fail("NumLit", RULE_Literal_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_Literal_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

}

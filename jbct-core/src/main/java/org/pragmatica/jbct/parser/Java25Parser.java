package org.pragmatica.jbct.parser;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generated PEG parser with CST (Concrete Syntax Tree) output.
 * This parser preserves all source information including trivia (whitespace/comments).
 * Depends only on pragmatica-lite:core for Result type.
 */
public final class Java25Parser {

    // === Rule ID Types ===

    public sealed interface RuleId {
        int ordinal();
        String name();

        record CompilationUnit() implements RuleId {
            public int ordinal() { return 0; }
            public String name() { return "CompilationUnit"; }
        }
        record OrdinaryUnit() implements RuleId {
            public int ordinal() { return 1; }
            public String name() { return "OrdinaryUnit"; }
        }
        record PackageDecl() implements RuleId {
            public int ordinal() { return 2; }
            public String name() { return "PackageDecl"; }
        }
        record ImportDecl() implements RuleId {
            public int ordinal() { return 3; }
            public String name() { return "ImportDecl"; }
        }
        record ModuleDecl() implements RuleId {
            public int ordinal() { return 4; }
            public String name() { return "ModuleDecl"; }
        }
        record ModuleDirective() implements RuleId {
            public int ordinal() { return 5; }
            public String name() { return "ModuleDirective"; }
        }
        record RequiresDirective() implements RuleId {
            public int ordinal() { return 6; }
            public String name() { return "RequiresDirective"; }
        }
        record ExportsDirective() implements RuleId {
            public int ordinal() { return 7; }
            public String name() { return "ExportsDirective"; }
        }
        record OpensDirective() implements RuleId {
            public int ordinal() { return 8; }
            public String name() { return "OpensDirective"; }
        }
        record UsesDirective() implements RuleId {
            public int ordinal() { return 9; }
            public String name() { return "UsesDirective"; }
        }
        record ProvidesDirective() implements RuleId {
            public int ordinal() { return 10; }
            public String name() { return "ProvidesDirective"; }
        }
        record TypeDecl() implements RuleId {
            public int ordinal() { return 11; }
            public String name() { return "TypeDecl"; }
        }
        record TypeKind() implements RuleId {
            public int ordinal() { return 12; }
            public String name() { return "TypeKind"; }
        }
        record ClassDecl() implements RuleId {
            public int ordinal() { return 13; }
            public String name() { return "ClassDecl"; }
        }
        record InterfaceDecl() implements RuleId {
            public int ordinal() { return 14; }
            public String name() { return "InterfaceDecl"; }
        }
        record AnnotationDecl() implements RuleId {
            public int ordinal() { return 15; }
            public String name() { return "AnnotationDecl"; }
        }
        record AnnotationBody() implements RuleId {
            public int ordinal() { return 16; }
            public String name() { return "AnnotationBody"; }
        }
        record AnnotationMember() implements RuleId {
            public int ordinal() { return 17; }
            public String name() { return "AnnotationMember"; }
        }
        record AnnotationElemDecl() implements RuleId {
            public int ordinal() { return 18; }
            public String name() { return "AnnotationElemDecl"; }
        }
        record EnumDecl() implements RuleId {
            public int ordinal() { return 19; }
            public String name() { return "EnumDecl"; }
        }
        record RecordDecl() implements RuleId {
            public int ordinal() { return 20; }
            public String name() { return "RecordDecl"; }
        }
        record ImplementsClause() implements RuleId {
            public int ordinal() { return 21; }
            public String name() { return "ImplementsClause"; }
        }
        record PermitsClause() implements RuleId {
            public int ordinal() { return 22; }
            public String name() { return "PermitsClause"; }
        }
        record TypeList() implements RuleId {
            public int ordinal() { return 23; }
            public String name() { return "TypeList"; }
        }
        record TypeParams() implements RuleId {
            public int ordinal() { return 24; }
            public String name() { return "TypeParams"; }
        }
        record TypeParam() implements RuleId {
            public int ordinal() { return 25; }
            public String name() { return "TypeParam"; }
        }
        record ClassBody() implements RuleId {
            public int ordinal() { return 26; }
            public String name() { return "ClassBody"; }
        }
        record ClassMember() implements RuleId {
            public int ordinal() { return 27; }
            public String name() { return "ClassMember"; }
        }
        record Member() implements RuleId {
            public int ordinal() { return 28; }
            public String name() { return "Member"; }
        }
        record InitializerBlock() implements RuleId {
            public int ordinal() { return 29; }
            public String name() { return "InitializerBlock"; }
        }
        record EnumBody() implements RuleId {
            public int ordinal() { return 30; }
            public String name() { return "EnumBody"; }
        }
        record EnumConsts() implements RuleId {
            public int ordinal() { return 31; }
            public String name() { return "EnumConsts"; }
        }
        record EnumConst() implements RuleId {
            public int ordinal() { return 32; }
            public String name() { return "EnumConst"; }
        }
        record RecordComponents() implements RuleId {
            public int ordinal() { return 33; }
            public String name() { return "RecordComponents"; }
        }
        record RecordComp() implements RuleId {
            public int ordinal() { return 34; }
            public String name() { return "RecordComp"; }
        }
        record RecordBody() implements RuleId {
            public int ordinal() { return 35; }
            public String name() { return "RecordBody"; }
        }
        record RecordMember() implements RuleId {
            public int ordinal() { return 36; }
            public String name() { return "RecordMember"; }
        }
        record CompactConstructor() implements RuleId {
            public int ordinal() { return 37; }
            public String name() { return "CompactConstructor"; }
        }
        record FieldDecl() implements RuleId {
            public int ordinal() { return 38; }
            public String name() { return "FieldDecl"; }
        }
        record VarDecls() implements RuleId {
            public int ordinal() { return 39; }
            public String name() { return "VarDecls"; }
        }
        record VarDecl() implements RuleId {
            public int ordinal() { return 40; }
            public String name() { return "VarDecl"; }
        }
        record VarInit() implements RuleId {
            public int ordinal() { return 41; }
            public String name() { return "VarInit"; }
        }
        record MethodDecl() implements RuleId {
            public int ordinal() { return 42; }
            public String name() { return "MethodDecl"; }
        }
        record Params() implements RuleId {
            public int ordinal() { return 43; }
            public String name() { return "Params"; }
        }
        record Param() implements RuleId {
            public int ordinal() { return 44; }
            public String name() { return "Param"; }
        }
        record Throws() implements RuleId {
            public int ordinal() { return 45; }
            public String name() { return "Throws"; }
        }
        record ConstructorDecl() implements RuleId {
            public int ordinal() { return 46; }
            public String name() { return "ConstructorDecl"; }
        }
        record Block() implements RuleId {
            public int ordinal() { return 47; }
            public String name() { return "Block"; }
        }
        record BlockStmt() implements RuleId {
            public int ordinal() { return 48; }
            public String name() { return "BlockStmt"; }
        }
        record LocalTypeDecl() implements RuleId {
            public int ordinal() { return 49; }
            public String name() { return "LocalTypeDecl"; }
        }
        record LocalVar() implements RuleId {
            public int ordinal() { return 50; }
            public String name() { return "LocalVar"; }
        }
        record LocalVarType() implements RuleId {
            public int ordinal() { return 51; }
            public String name() { return "LocalVarType"; }
        }
        record Stmt() implements RuleId {
            public int ordinal() { return 52; }
            public String name() { return "Stmt"; }
        }
        record ReturnKW() implements RuleId {
            public int ordinal() { return 53; }
            public String name() { return "ReturnKW"; }
        }
        record ThrowKW() implements RuleId {
            public int ordinal() { return 54; }
            public String name() { return "ThrowKW"; }
        }
        record BreakKW() implements RuleId {
            public int ordinal() { return 55; }
            public String name() { return "BreakKW"; }
        }
        record ContinueKW() implements RuleId {
            public int ordinal() { return 56; }
            public String name() { return "ContinueKW"; }
        }
        record AssertKW() implements RuleId {
            public int ordinal() { return 57; }
            public String name() { return "AssertKW"; }
        }
        record YieldKW() implements RuleId {
            public int ordinal() { return 58; }
            public String name() { return "YieldKW"; }
        }
        record ForCtrl() implements RuleId {
            public int ordinal() { return 59; }
            public String name() { return "ForCtrl"; }
        }
        record ForInit() implements RuleId {
            public int ordinal() { return 60; }
            public String name() { return "ForInit"; }
        }
        record LocalVarNoSemi() implements RuleId {
            public int ordinal() { return 61; }
            public String name() { return "LocalVarNoSemi"; }
        }
        record ResourceSpec() implements RuleId {
            public int ordinal() { return 62; }
            public String name() { return "ResourceSpec"; }
        }
        record Resource() implements RuleId {
            public int ordinal() { return 63; }
            public String name() { return "Resource"; }
        }
        record Catch() implements RuleId {
            public int ordinal() { return 64; }
            public String name() { return "Catch"; }
        }
        record Finally() implements RuleId {
            public int ordinal() { return 65; }
            public String name() { return "Finally"; }
        }
        record SwitchBlock() implements RuleId {
            public int ordinal() { return 66; }
            public String name() { return "SwitchBlock"; }
        }
        record SwitchRule() implements RuleId {
            public int ordinal() { return 67; }
            public String name() { return "SwitchRule"; }
        }
        record SwitchLabel() implements RuleId {
            public int ordinal() { return 68; }
            public String name() { return "SwitchLabel"; }
        }
        record CaseItem() implements RuleId {
            public int ordinal() { return 69; }
            public String name() { return "CaseItem"; }
        }
        record Pattern() implements RuleId {
            public int ordinal() { return 70; }
            public String name() { return "Pattern"; }
        }
        record TypePattern() implements RuleId {
            public int ordinal() { return 71; }
            public String name() { return "TypePattern"; }
        }
        record RecordPattern() implements RuleId {
            public int ordinal() { return 72; }
            public String name() { return "RecordPattern"; }
        }
        record PatternList() implements RuleId {
            public int ordinal() { return 73; }
            public String name() { return "PatternList"; }
        }
        record Guard() implements RuleId {
            public int ordinal() { return 74; }
            public String name() { return "Guard"; }
        }
        record Expr() implements RuleId {
            public int ordinal() { return 75; }
            public String name() { return "Expr"; }
        }
        record Assignment() implements RuleId {
            public int ordinal() { return 76; }
            public String name() { return "Assignment"; }
        }
        record Ternary() implements RuleId {
            public int ordinal() { return 77; }
            public String name() { return "Ternary"; }
        }
        record LogOr() implements RuleId {
            public int ordinal() { return 78; }
            public String name() { return "LogOr"; }
        }
        record LogAnd() implements RuleId {
            public int ordinal() { return 79; }
            public String name() { return "LogAnd"; }
        }
        record BitOr() implements RuleId {
            public int ordinal() { return 80; }
            public String name() { return "BitOr"; }
        }
        record BitXor() implements RuleId {
            public int ordinal() { return 81; }
            public String name() { return "BitXor"; }
        }
        record BitAnd() implements RuleId {
            public int ordinal() { return 82; }
            public String name() { return "BitAnd"; }
        }
        record Equality() implements RuleId {
            public int ordinal() { return 83; }
            public String name() { return "Equality"; }
        }
        record Relational() implements RuleId {
            public int ordinal() { return 84; }
            public String name() { return "Relational"; }
        }
        record Shift() implements RuleId {
            public int ordinal() { return 85; }
            public String name() { return "Shift"; }
        }
        record Additive() implements RuleId {
            public int ordinal() { return 86; }
            public String name() { return "Additive"; }
        }
        record Multiplicative() implements RuleId {
            public int ordinal() { return 87; }
            public String name() { return "Multiplicative"; }
        }
        record Unary() implements RuleId {
            public int ordinal() { return 88; }
            public String name() { return "Unary"; }
        }
        record Postfix() implements RuleId {
            public int ordinal() { return 89; }
            public String name() { return "Postfix"; }
        }
        record PostOp() implements RuleId {
            public int ordinal() { return 90; }
            public String name() { return "PostOp"; }
        }
        record Primary() implements RuleId {
            public int ordinal() { return 91; }
            public String name() { return "Primary"; }
        }
        record Lambda() implements RuleId {
            public int ordinal() { return 92; }
            public String name() { return "Lambda"; }
        }
        record LambdaParams() implements RuleId {
            public int ordinal() { return 93; }
            public String name() { return "LambdaParams"; }
        }
        record LambdaParam() implements RuleId {
            public int ordinal() { return 94; }
            public String name() { return "LambdaParam"; }
        }
        record Args() implements RuleId {
            public int ordinal() { return 95; }
            public String name() { return "Args"; }
        }
        record ExprList() implements RuleId {
            public int ordinal() { return 96; }
            public String name() { return "ExprList"; }
        }
        record Type() implements RuleId {
            public int ordinal() { return 97; }
            public String name() { return "Type"; }
        }
        record PrimType() implements RuleId {
            public int ordinal() { return 98; }
            public String name() { return "PrimType"; }
        }
        record RefType() implements RuleId {
            public int ordinal() { return 99; }
            public String name() { return "RefType"; }
        }
        record AnnotatedTypeName() implements RuleId {
            public int ordinal() { return 100; }
            public String name() { return "AnnotatedTypeName"; }
        }
        record Dims() implements RuleId {
            public int ordinal() { return 101; }
            public String name() { return "Dims"; }
        }
        record TypeArgs() implements RuleId {
            public int ordinal() { return 102; }
            public String name() { return "TypeArgs"; }
        }
        record TypeArg() implements RuleId {
            public int ordinal() { return 103; }
            public String name() { return "TypeArg"; }
        }
        record QualifiedName() implements RuleId {
            public int ordinal() { return 104; }
            public String name() { return "QualifiedName"; }
        }
        record Identifier() implements RuleId {
            public int ordinal() { return 105; }
            public String name() { return "Identifier"; }
        }
        record Modifier() implements RuleId {
            public int ordinal() { return 106; }
            public String name() { return "Modifier"; }
        }
        record Annotation() implements RuleId {
            public int ordinal() { return 107; }
            public String name() { return "Annotation"; }
        }
        record AnnotationValue() implements RuleId {
            public int ordinal() { return 108; }
            public String name() { return "AnnotationValue"; }
        }
        record AnnotationElem() implements RuleId {
            public int ordinal() { return 109; }
            public String name() { return "AnnotationElem"; }
        }
        record Literal() implements RuleId {
            public int ordinal() { return 110; }
            public String name() { return "Literal"; }
        }
        record CharLit() implements RuleId {
            public int ordinal() { return 111; }
            public String name() { return "CharLit"; }
        }
        record StringLit() implements RuleId {
            public int ordinal() { return 112; }
            public String name() { return "StringLit"; }
        }
        record NumLit() implements RuleId {
            public int ordinal() { return 113; }
            public String name() { return "NumLit"; }
        }
        record Keyword() implements RuleId {
            public int ordinal() { return 114; }
            public String name() { return "Keyword"; }
        }
        // Built-in types for anonymous terminals
        record PegLiteral() implements RuleId {
            public int ordinal() { return -1; }
            public String name() { return "literal"; }
        }
        record PegCharClass() implements RuleId {
            public int ordinal() { return -2; }
            public String name() { return "char"; }
        }
        record PegAny() implements RuleId {
            public int ordinal() { return -3; }
            public String name() { return "any"; }
        }
        record PegToken() implements RuleId {
            public int ordinal() { return -4; }
            public String name() { return "token"; }
        }
    }

    // Rule ID singleton instances
    private static final RuleId.CompilationUnit RULE_COMPILATION_UNIT = new RuleId.CompilationUnit();
    private static final RuleId.OrdinaryUnit RULE_ORDINARY_UNIT = new RuleId.OrdinaryUnit();
    private static final RuleId.PackageDecl RULE_PACKAGE_DECL = new RuleId.PackageDecl();
    private static final RuleId.ImportDecl RULE_IMPORT_DECL = new RuleId.ImportDecl();
    private static final RuleId.ModuleDecl RULE_MODULE_DECL = new RuleId.ModuleDecl();
    private static final RuleId.ModuleDirective RULE_MODULE_DIRECTIVE = new RuleId.ModuleDirective();
    private static final RuleId.RequiresDirective RULE_REQUIRES_DIRECTIVE = new RuleId.RequiresDirective();
    private static final RuleId.ExportsDirective RULE_EXPORTS_DIRECTIVE = new RuleId.ExportsDirective();
    private static final RuleId.OpensDirective RULE_OPENS_DIRECTIVE = new RuleId.OpensDirective();
    private static final RuleId.UsesDirective RULE_USES_DIRECTIVE = new RuleId.UsesDirective();
    private static final RuleId.ProvidesDirective RULE_PROVIDES_DIRECTIVE = new RuleId.ProvidesDirective();
    private static final RuleId.TypeDecl RULE_TYPE_DECL = new RuleId.TypeDecl();
    private static final RuleId.TypeKind RULE_TYPE_KIND = new RuleId.TypeKind();
    private static final RuleId.ClassDecl RULE_CLASS_DECL = new RuleId.ClassDecl();
    private static final RuleId.InterfaceDecl RULE_INTERFACE_DECL = new RuleId.InterfaceDecl();
    private static final RuleId.AnnotationDecl RULE_ANNOTATION_DECL = new RuleId.AnnotationDecl();
    private static final RuleId.AnnotationBody RULE_ANNOTATION_BODY = new RuleId.AnnotationBody();
    private static final RuleId.AnnotationMember RULE_ANNOTATION_MEMBER = new RuleId.AnnotationMember();
    private static final RuleId.AnnotationElemDecl RULE_ANNOTATION_ELEM_DECL = new RuleId.AnnotationElemDecl();
    private static final RuleId.EnumDecl RULE_ENUM_DECL = new RuleId.EnumDecl();
    private static final RuleId.RecordDecl RULE_RECORD_DECL = new RuleId.RecordDecl();
    private static final RuleId.ImplementsClause RULE_IMPLEMENTS_CLAUSE = new RuleId.ImplementsClause();
    private static final RuleId.PermitsClause RULE_PERMITS_CLAUSE = new RuleId.PermitsClause();
    private static final RuleId.TypeList RULE_TYPE_LIST = new RuleId.TypeList();
    private static final RuleId.TypeParams RULE_TYPE_PARAMS = new RuleId.TypeParams();
    private static final RuleId.TypeParam RULE_TYPE_PARAM = new RuleId.TypeParam();
    private static final RuleId.ClassBody RULE_CLASS_BODY = new RuleId.ClassBody();
    private static final RuleId.ClassMember RULE_CLASS_MEMBER = new RuleId.ClassMember();
    private static final RuleId.Member RULE_MEMBER = new RuleId.Member();
    private static final RuleId.InitializerBlock RULE_INITIALIZER_BLOCK = new RuleId.InitializerBlock();
    private static final RuleId.EnumBody RULE_ENUM_BODY = new RuleId.EnumBody();
    private static final RuleId.EnumConsts RULE_ENUM_CONSTS = new RuleId.EnumConsts();
    private static final RuleId.EnumConst RULE_ENUM_CONST = new RuleId.EnumConst();
    private static final RuleId.RecordComponents RULE_RECORD_COMPONENTS = new RuleId.RecordComponents();
    private static final RuleId.RecordComp RULE_RECORD_COMP = new RuleId.RecordComp();
    private static final RuleId.RecordBody RULE_RECORD_BODY = new RuleId.RecordBody();
    private static final RuleId.RecordMember RULE_RECORD_MEMBER = new RuleId.RecordMember();
    private static final RuleId.CompactConstructor RULE_COMPACT_CONSTRUCTOR = new RuleId.CompactConstructor();
    private static final RuleId.FieldDecl RULE_FIELD_DECL = new RuleId.FieldDecl();
    private static final RuleId.VarDecls RULE_VAR_DECLS = new RuleId.VarDecls();
    private static final RuleId.VarDecl RULE_VAR_DECL = new RuleId.VarDecl();
    private static final RuleId.VarInit RULE_VAR_INIT = new RuleId.VarInit();
    private static final RuleId.MethodDecl RULE_METHOD_DECL = new RuleId.MethodDecl();
    private static final RuleId.Params RULE_PARAMS = new RuleId.Params();
    private static final RuleId.Param RULE_PARAM = new RuleId.Param();
    private static final RuleId.Throws RULE_THROWS = new RuleId.Throws();
    private static final RuleId.ConstructorDecl RULE_CONSTRUCTOR_DECL = new RuleId.ConstructorDecl();
    private static final RuleId.Block RULE_BLOCK = new RuleId.Block();
    private static final RuleId.BlockStmt RULE_BLOCK_STMT = new RuleId.BlockStmt();
    private static final RuleId.LocalTypeDecl RULE_LOCAL_TYPE_DECL = new RuleId.LocalTypeDecl();
    private static final RuleId.LocalVar RULE_LOCAL_VAR = new RuleId.LocalVar();
    private static final RuleId.LocalVarType RULE_LOCAL_VAR_TYPE = new RuleId.LocalVarType();
    private static final RuleId.Stmt RULE_STMT = new RuleId.Stmt();
    private static final RuleId.ReturnKW RULE_RETURN_K_W = new RuleId.ReturnKW();
    private static final RuleId.ThrowKW RULE_THROW_K_W = new RuleId.ThrowKW();
    private static final RuleId.BreakKW RULE_BREAK_K_W = new RuleId.BreakKW();
    private static final RuleId.ContinueKW RULE_CONTINUE_K_W = new RuleId.ContinueKW();
    private static final RuleId.AssertKW RULE_ASSERT_K_W = new RuleId.AssertKW();
    private static final RuleId.YieldKW RULE_YIELD_K_W = new RuleId.YieldKW();
    private static final RuleId.ForCtrl RULE_FOR_CTRL = new RuleId.ForCtrl();
    private static final RuleId.ForInit RULE_FOR_INIT = new RuleId.ForInit();
    private static final RuleId.LocalVarNoSemi RULE_LOCAL_VAR_NO_SEMI = new RuleId.LocalVarNoSemi();
    private static final RuleId.ResourceSpec RULE_RESOURCE_SPEC = new RuleId.ResourceSpec();
    private static final RuleId.Resource RULE_RESOURCE = new RuleId.Resource();
    private static final RuleId.Catch RULE_CATCH = new RuleId.Catch();
    private static final RuleId.Finally RULE_FINALLY = new RuleId.Finally();
    private static final RuleId.SwitchBlock RULE_SWITCH_BLOCK = new RuleId.SwitchBlock();
    private static final RuleId.SwitchRule RULE_SWITCH_RULE = new RuleId.SwitchRule();
    private static final RuleId.SwitchLabel RULE_SWITCH_LABEL = new RuleId.SwitchLabel();
    private static final RuleId.CaseItem RULE_CASE_ITEM = new RuleId.CaseItem();
    private static final RuleId.Pattern RULE_PATTERN = new RuleId.Pattern();
    private static final RuleId.TypePattern RULE_TYPE_PATTERN = new RuleId.TypePattern();
    private static final RuleId.RecordPattern RULE_RECORD_PATTERN = new RuleId.RecordPattern();
    private static final RuleId.PatternList RULE_PATTERN_LIST = new RuleId.PatternList();
    private static final RuleId.Guard RULE_GUARD = new RuleId.Guard();
    private static final RuleId.Expr RULE_EXPR = new RuleId.Expr();
    private static final RuleId.Assignment RULE_ASSIGNMENT = new RuleId.Assignment();
    private static final RuleId.Ternary RULE_TERNARY = new RuleId.Ternary();
    private static final RuleId.LogOr RULE_LOG_OR = new RuleId.LogOr();
    private static final RuleId.LogAnd RULE_LOG_AND = new RuleId.LogAnd();
    private static final RuleId.BitOr RULE_BIT_OR = new RuleId.BitOr();
    private static final RuleId.BitXor RULE_BIT_XOR = new RuleId.BitXor();
    private static final RuleId.BitAnd RULE_BIT_AND = new RuleId.BitAnd();
    private static final RuleId.Equality RULE_EQUALITY = new RuleId.Equality();
    private static final RuleId.Relational RULE_RELATIONAL = new RuleId.Relational();
    private static final RuleId.Shift RULE_SHIFT = new RuleId.Shift();
    private static final RuleId.Additive RULE_ADDITIVE = new RuleId.Additive();
    private static final RuleId.Multiplicative RULE_MULTIPLICATIVE = new RuleId.Multiplicative();
    private static final RuleId.Unary RULE_UNARY = new RuleId.Unary();
    private static final RuleId.Postfix RULE_POSTFIX = new RuleId.Postfix();
    private static final RuleId.PostOp RULE_POST_OP = new RuleId.PostOp();
    private static final RuleId.Primary RULE_PRIMARY = new RuleId.Primary();
    private static final RuleId.Lambda RULE_LAMBDA = new RuleId.Lambda();
    private static final RuleId.LambdaParams RULE_LAMBDA_PARAMS = new RuleId.LambdaParams();
    private static final RuleId.LambdaParam RULE_LAMBDA_PARAM = new RuleId.LambdaParam();
    private static final RuleId.Args RULE_ARGS = new RuleId.Args();
    private static final RuleId.ExprList RULE_EXPR_LIST = new RuleId.ExprList();
    private static final RuleId.Type RULE_TYPE = new RuleId.Type();
    private static final RuleId.PrimType RULE_PRIM_TYPE = new RuleId.PrimType();
    private static final RuleId.RefType RULE_REF_TYPE = new RuleId.RefType();
    private static final RuleId.AnnotatedTypeName RULE_ANNOTATED_TYPE_NAME = new RuleId.AnnotatedTypeName();
    private static final RuleId.Dims RULE_DIMS = new RuleId.Dims();
    private static final RuleId.TypeArgs RULE_TYPE_ARGS = new RuleId.TypeArgs();
    private static final RuleId.TypeArg RULE_TYPE_ARG = new RuleId.TypeArg();
    private static final RuleId.QualifiedName RULE_QUALIFIED_NAME = new RuleId.QualifiedName();
    private static final RuleId.Identifier RULE_IDENTIFIER = new RuleId.Identifier();
    private static final RuleId.Modifier RULE_MODIFIER = new RuleId.Modifier();
    private static final RuleId.Annotation RULE_ANNOTATION = new RuleId.Annotation();
    private static final RuleId.AnnotationValue RULE_ANNOTATION_VALUE = new RuleId.AnnotationValue();
    private static final RuleId.AnnotationElem RULE_ANNOTATION_ELEM = new RuleId.AnnotationElem();
    private static final RuleId.Literal RULE_LITERAL = new RuleId.Literal();
    private static final RuleId.CharLit RULE_CHAR_LIT = new RuleId.CharLit();
    private static final RuleId.StringLit RULE_STRING_LIT = new RuleId.StringLit();
    private static final RuleId.NumLit RULE_NUM_LIT = new RuleId.NumLit();
    private static final RuleId.Keyword RULE_KEYWORD = new RuleId.Keyword();
    private static final RuleId.PegLiteral RULE_PEG_LITERAL = new RuleId.PegLiteral();
    private static final RuleId.PegCharClass RULE_PEG_CHAR_CLASS = new RuleId.PegCharClass();
    private static final RuleId.PegAny RULE_PEG_ANY = new RuleId.PegAny();
    private static final RuleId.PegToken RULE_PEG_TOKEN = new RuleId.PegToken();

    // === CST Types ===

    public record SourceLocation(int line, int column, int offset) {
        public static final SourceLocation START = new SourceLocation(1, 1, 0);
        public static SourceLocation at(int line, int column, int offset) {
            return new SourceLocation(line, column, offset);
        }
        @Override public String toString() { return line + ":" + column; }
    }

    public record SourceSpan(SourceLocation start, SourceLocation end) {
        public static SourceSpan of(SourceLocation start, SourceLocation end) {
            return new SourceSpan(start, end);
        }
        public int length() { return end.offset() - start.offset(); }
        public String extract(String source) { return source.substring(start.offset(), end.offset()); }
        @Override public String toString() { return start + "-" + end; }
    }

    public sealed interface Trivia {
        SourceSpan span();
        String text();
        record Whitespace(SourceSpan span, String text) implements Trivia {}
        record LineComment(SourceSpan span, String text) implements Trivia {}
        record BlockComment(SourceSpan span, String text) implements Trivia {}
    }

    public sealed interface CstNode {
        SourceSpan span();
        RuleId rule();
        List<Trivia> leadingTrivia();
        List<Trivia> trailingTrivia();

        record Terminal(SourceSpan span, RuleId rule, String text,
                        List<Trivia> leadingTrivia, List<Trivia> trailingTrivia) implements CstNode {}

        record NonTerminal(SourceSpan span, RuleId rule, List<CstNode> children,
                           List<Trivia> leadingTrivia, List<Trivia> trailingTrivia) implements CstNode {}

        record Token(SourceSpan span, RuleId rule, String text,
                     List<Trivia> leadingTrivia, List<Trivia> trailingTrivia) implements CstNode {}
        record Error(SourceSpan span, String skippedText, String expected,
                     List<Trivia> leadingTrivia, List<Trivia> trailingTrivia) implements CstNode {
            @Override public RuleId rule() { return null; }
        }
    }

    public record ParseError(SourceLocation location, String reason) implements Cause {
        @Override
        public String message() {
            return reason + " at " + location;
        }
    }

    // === Advanced Diagnostic Types ===

    public enum Severity {
        ERROR("error"),
        WARNING("warning"),
        INFO("info"),
        HINT("hint");

        private final String display;

        Severity(String display) {
            this.display = display;
        }

        public String display() {
            return display;
        }
    }

    public record DiagnosticLabel(SourceSpan span, String message, boolean primary) {
        public static DiagnosticLabel primary(SourceSpan span, String message) {
            return new DiagnosticLabel(span, message, true);
        }
        public static DiagnosticLabel secondary(SourceSpan span, String message) {
            return new DiagnosticLabel(span, message, false);
        }
    }

    public record Diagnostic(
        Severity severity,
        String code,
        String message,
        SourceSpan span,
        List<DiagnosticLabel> labels,
        List<String> notes
    ) {
        public static Diagnostic error(String message, SourceSpan span) {
            return new Diagnostic(Severity.ERROR, null, message, span, List.of(), List.of());
        }

        public static Diagnostic error(String code, String message, SourceSpan span) {
            return new Diagnostic(Severity.ERROR, code, message, span, List.of(), List.of());
        }

        public static Diagnostic warning(String message, SourceSpan span) {
            return new Diagnostic(Severity.WARNING, null, message, span, List.of(), List.of());
        }

        public Diagnostic withLabel(String labelMessage) {
            var newLabels = new ArrayList<>(labels);
            newLabels.add(DiagnosticLabel.primary(span, labelMessage));
            return new Diagnostic(severity, code, message, span, List.copyOf(newLabels), notes);
        }

        public Diagnostic withSecondaryLabel(SourceSpan labelSpan, String labelMessage) {
            var newLabels = new ArrayList<>(labels);
            newLabels.add(DiagnosticLabel.secondary(labelSpan, labelMessage));
            return new Diagnostic(severity, code, message, span, List.copyOf(newLabels), notes);
        }

        public Diagnostic withNote(String note) {
            var newNotes = new ArrayList<>(notes);
            newNotes.add(note);
            return new Diagnostic(severity, code, message, span, labels, List.copyOf(newNotes));
        }

        public Diagnostic withHelp(String help) {
            return withNote("help: " + help);
        }

        public String format(String source, String filename) {
            var sb = new StringBuilder();
            var lines = source.split("\n", -1);

            // Header: error[E0001]: message
            sb.append(severity.display());
            if (code != null) {
                sb.append("[").append(code).append("]");
            }
            sb.append(": ").append(message).append("\n");

            // Location: --> filename:line:column
            var loc = span.start();
            sb.append("  --> ");
            if (filename != null) {
                sb.append(filename).append(":");
            }
            sb.append(loc.line()).append(":").append(loc.column()).append("\n");

            // Find all lines we need to display
            int minLine = span.start().line();
            int maxLine = span.end().line();
            for (var label : labels) {
                minLine = Math.min(minLine, label.span().start().line());
                maxLine = Math.max(maxLine, label.span().end().line());
            }

            // Calculate gutter width
            int gutterWidth = String.valueOf(maxLine).length();

            // Empty line before source
            sb.append(" ".repeat(gutterWidth + 1)).append("|\n");

            // Display source lines with labels
            for (int lineNum = minLine; lineNum <= maxLine; lineNum++) {
                if (lineNum < 1 || lineNum > lines.length) continue;

                String lineContent = lines[lineNum - 1];
                String lineNumStr = String.format("%" + gutterWidth + "d", lineNum);

                // Source line
                sb.append(lineNumStr).append(" | ").append(lineContent).append("\n");

                // Underline labels on this line
                var lineLabels = getLabelsOnLine(lineNum);
                if (!lineLabels.isEmpty()) {
                    sb.append(" ".repeat(gutterWidth)).append(" | ");
                    sb.append(formatUnderlines(lineNum, lineContent, lineLabels));
                    sb.append("\n");
                }
            }

            // Empty line after source
            sb.append(" ".repeat(gutterWidth + 1)).append("|\n");

            // Notes
            for (var note : notes) {
                sb.append(" ".repeat(gutterWidth + 1)).append("= ").append(note).append("\n");
            }

            return sb.toString();
        }

        private List<DiagnosticLabel> getLabelsOnLine(int lineNum) {
            var result = new ArrayList<DiagnosticLabel>();
            if (span.start().line() <= lineNum && span.end().line() >= lineNum) {
                if (labels.isEmpty()) {
                    result.add(DiagnosticLabel.primary(span, ""));
                }
            }
            for (var label : labels) {
                if (label.span().start().line() <= lineNum && label.span().end().line() >= lineNum) {
                    result.add(label);
                }
            }
            return result;
        }

        private String formatUnderlines(int lineNum, String lineContent, List<DiagnosticLabel> lineLabels) {
            var sb = new StringBuilder();
            int currentCol = 1;

            var sorted = lineLabels.stream()
                .sorted((a, b) -> Integer.compare(a.span().start().column(), b.span().start().column()))
                .toList();

            for (var label : sorted) {
                int startCol = label.span().start().line() == lineNum ? label.span().start().column() : 1;
                int endCol = label.span().end().line() == lineNum
                    ? label.span().end().column()
                    : lineContent.length() + 1;

                while (currentCol < startCol) {
                    sb.append(" ");
                    currentCol++;
                }

                char underlineChar = label.primary() ? '^' : '-';
                int underlineLen = Math.max(1, endCol - startCol);
                sb.append(String.valueOf(underlineChar).repeat(underlineLen));
                currentCol += underlineLen;

                if (!label.message().isEmpty()) {
                    sb.append(" ").append(label.message());
                }
            }

            return sb.toString();
        }

        public String formatSimple() {
            var loc = span.start();
            return String.format("%s:%d:%d: %s: %s",
                "input", loc.line(), loc.column(), severity.display(), message);
        }
    }

    public record ParseResultWithDiagnostics(
        CstNode node,
        List<Diagnostic> diagnostics,
        String source
    ) {
        public static ParseResultWithDiagnostics success(CstNode node, String source) {
            return new ParseResultWithDiagnostics(node, List.of(), source);
        }

        public static ParseResultWithDiagnostics withErrors(CstNode node, List<Diagnostic> diagnostics, String source) {
            return new ParseResultWithDiagnostics(node, List.copyOf(diagnostics), source);
        }

        public boolean isSuccess() {
            return node != null && diagnostics.isEmpty();
        }

        public boolean hasErrors() {
            return !diagnostics.isEmpty();
        }

        public boolean hasNode() {
            return node != null;
        }

        public String formatDiagnostics(String filename) {
            if (diagnostics.isEmpty()) {
                return "";
            }
            var sb = new StringBuilder();
            for (var diag : diagnostics) {
                sb.append(diag.format(source, filename));
                sb.append("\n");
            }
            return sb.toString();
        }

        public String formatDiagnostics() {
            return formatDiagnostics("input");
        }

        public int errorCount() {
            return (int) diagnostics.stream()
                .filter(d -> d.severity() == Severity.ERROR)
                .count();
        }

        public int warningCount() {
            return (int) diagnostics.stream()
                .filter(d -> d.severity() == Severity.WARNING)
                .count();
        }
    }

    // === Parse Context ===

    private String input;
    private int pos;
    private int line;
    private int column;
    private Map<Long, CstParseResult> cache;
    private Map<String, String> captures;
    private boolean inTokenBoundary;
    private List<Diagnostic> diagnostics;
    private SourceLocation furthestFailure;
    private String furthestExpected;

    private void init(String input) {
        this.input = input;
        this.pos = 0;
        this.line = 1;
        this.column = 1;
        this.cache = new HashMap<>();
        this.captures = new HashMap<>();
        this.inTokenBoundary = false;
        this.diagnostics = new ArrayList<>();
        this.furthestFailure = null;
        this.furthestExpected = null;
    }

    private SourceLocation location() {
        return SourceLocation.at(line, column, pos);
    }

    private boolean isAtEnd() {
        return pos >= input.length();
    }

    private char peek() {
        return input.charAt(pos);
    }

    private char peek(int offset) {
        return input.charAt(pos + offset);
    }

    private char advance() {
        char c = input.charAt(pos++);
        if (c == '\n') {
            line++;
            column = 1;
        } else {
            column++;
        }
        return c;
    }

    private int remaining() {
        return input.length() - pos;
    }

    private String substring(int start, int end) {
        return input.substring(start, end);
    }

    private long cacheKey(int ruleId, int position) {
        return ((long) ruleId << 32) | position;
    }

    private void restoreLocation(SourceLocation loc) {
        this.pos = loc.offset();
        this.line = loc.line();
        this.column = loc.column();
    }

    private void trackFailure(String expected) {
        var loc = location();
        if (furthestFailure == null || loc.offset() > furthestFailure.offset()) {
            furthestFailure = loc;
            furthestExpected = expected;
        }
    }

    private SourceSpan skipToRecoveryPoint() {
        var start = location();
        while (!isAtEnd()) {
            char c = peek();
            if (c == '\n' || c == ';' || c == ',' || c == '}' || c == ')' || c == ']') {
                break;
            }
            advance();
        }
        return SourceSpan.of(start, location());
    }

    private void addDiagnostic(String message, SourceSpan span) {
        diagnostics.add(Diagnostic.error(message, span));
    }

    private void addDiagnostic(String message, SourceSpan span, String label) {
        diagnostics.add(Diagnostic.error(message, span).withLabel(label));
    }

    // === Public Parse Methods ===

    public Result<CstNode> parse(String input) {
        init(input);
        var leadingTrivia = skipWhitespace();
        var result = parse_CompilationUnit(leadingTrivia);
        if (result.isFailure()) {
            return Result.failure(new ParseError(location(), "expected " + result.expected));
        }
        var trailingTrivia = skipWhitespace(); // Capture trailing trivia
        if (!isAtEnd()) {
            return Result.failure(new ParseError(location(), "unexpected input"));
        }
        // Attach trailing trivia to root node
        var rootNode = attachTrailingTrivia(result.node, trailingTrivia);
        return Result.success(rootNode);
    }

    /**
     * Parse with advanced error recovery and Rust-style diagnostics.
     * Returns a result containing the CST (with Error nodes for unparseable regions)
     * and a list of diagnostics.
     */
    public ParseResultWithDiagnostics parseWithDiagnostics(String input) {
        init(input);
        var leadingTrivia = skipWhitespace();
        var result = parse_CompilationUnit(leadingTrivia);

        if (result.isFailure()) {
            // Record the failure and attempt recovery
            var errorLoc = furthestFailure != null ? furthestFailure : location();
            var errorSpan = SourceSpan.of(errorLoc, errorLoc);
            addDiagnostic("expected " + (furthestExpected != null ? furthestExpected : result.expected), errorSpan);

            // Skip to recovery point and try to continue
            var skippedSpan = skipToRecoveryPoint();
            if (skippedSpan.length() > 0) {
                var skippedText = skippedSpan.extract(input);
                var expected = furthestExpected != null ? furthestExpected : result.expected;
                var errorNode = new CstNode.Error(skippedSpan, skippedText, expected, leadingTrivia, List.of());
                return ParseResultWithDiagnostics.withErrors(errorNode, diagnostics, input);
            }
            return ParseResultWithDiagnostics.withErrors(null, diagnostics, input);
        }

        var trailingTrivia = skipWhitespace();
        if (!isAtEnd()) {
            // Unexpected trailing input
            var errorStart = location();
            var skippedSpan = skipToRecoveryPoint();
            var skippedText = skippedSpan.extract(input);
            addDiagnostic("unexpected input", skippedSpan, "expected end of input");

            // Attach error node to result
            var rootNode = attachTrailingTrivia(result.node, trailingTrivia);
            return ParseResultWithDiagnostics.withErrors(rootNode, diagnostics, input);
        }

        var rootNode = attachTrailingTrivia(result.node, trailingTrivia);
        if (diagnostics.isEmpty()) {
            return ParseResultWithDiagnostics.success(rootNode, input);
        }
        return ParseResultWithDiagnostics.withErrors(rootNode, diagnostics, input);
    }

    // === Rule Parsing Methods ===

    private CstParseResult parse_CompilationUnit(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(0, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_0 = parse_ModuleDecl(trivia1);
        if (alt0_0.isSuccess() && alt0_0.node != null) {
            children.add(alt0_0.node);
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_1 = parse_OrdinaryUnit(trivia2);
        if (alt0_1.isSuccess() && alt0_1.node != null) {
            children.add(alt0_1.node);
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_COMPILATION_UNIT, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_OrdinaryUnit(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(1, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var optStart1 = location();
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem1 = parse_PackageDecl(trivia2);
            if (optElem1.isSuccess() && optElem1.node != null) {
                children.add(optElem1.node);
            }
            var elem0_0 = optElem1.isSuccess() ? optElem1 : CstParseResult.success(null, "", location());
            if (optElem1.isFailure()) {
                restoreLocation(optStart1);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart3 = location();
            while (true) {
                var beforeLoc3 = location();
                var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem3 = parse_ImportDecl(trivia4);
                if (zomElem3.isSuccess() && zomElem3.node != null) {
                    children.add(zomElem3.node);
                }
                if (zomElem3.isFailure() || location().offset() == beforeLoc3.offset()) {
                    restoreLocation(beforeLoc3);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart3.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_2 = CstParseResult.success(null, "", location());
            var zomStart5 = location();
            while (true) {
                var beforeLoc5 = location();
                var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem5 = parse_TypeDecl(trivia6);
                if (zomElem5.isSuccess() && zomElem5.node != null) {
                    children.add(zomElem5.node);
                }
                if (zomElem5.isFailure() || location().offset() == beforeLoc5.offset()) {
                    restoreLocation(beforeLoc5);
                    break;
                }
            }
            elem0_2 = CstParseResult.success(null, substring(zomStart5.offset(), pos), location());
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_ORDINARY_UNIT, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_PackageDecl(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(2, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            CstParseResult elem0_0 = CstParseResult.success(null, "", location());
            var zomStart1 = location();
            while (true) {
                var beforeLoc1 = location();
                var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem1 = parse_Annotation(trivia2);
                if (zomElem1.isSuccess() && zomElem1.node != null) {
                    children.add(zomElem1.node);
                }
                if (zomElem1.isFailure() || location().offset() == beforeLoc1.offset()) {
                    restoreLocation(beforeLoc1);
                    break;
                }
            }
            elem0_0 = CstParseResult.success(null, substring(zomStart1.offset(), pos), location());
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_1 = matchLiteralCst("package", false);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_2 = parse_QualifiedName(trivia4);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_3 = matchLiteralCst(";", false);
            if (elem0_3.isSuccess() && elem0_3.node != null) {
                children.add(elem0_3.node);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_PACKAGE_DECL, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_ImportDecl(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(3, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_0 = CstParseResult.success(null, "", location());
        var seqStart1 = location();
        if (alt0_0.isSuccess()) {
            var elem1_0 = matchLiteralCst("import", false);
            if (elem1_0.isSuccess() && elem1_0.node != null) {
                children.add(elem1_0.node);
            }
            if (elem1_0.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_0;
            }
        }
        if (alt0_0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem1_1 = matchLiteralCst("module", false);
            if (elem1_1.isSuccess() && elem1_1.node != null) {
                children.add(elem1_1.node);
            }
            if (elem1_1.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_1;
            }
        }
        if (alt0_0.isSuccess()) {
            var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem1_2 = parse_QualifiedName(trivia4);
            if (elem1_2.isSuccess() && elem1_2.node != null) {
                children.add(elem1_2.node);
            }
            if (elem1_2.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_2;
            }
        }
        if (alt0_0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem1_3 = matchLiteralCst(";", false);
            if (elem1_3.isSuccess() && elem1_3.node != null) {
                children.add(elem1_3.node);
            }
            if (elem1_3.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_3;
            }
        }
        if (alt0_0.isSuccess()) {
            alt0_0 = CstParseResult.success(null, substring(seqStart1.offset(), pos), location());
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_1 = CstParseResult.success(null, "", location());
        var seqStart6 = location();
        if (alt0_1.isSuccess()) {
            var elem6_0 = matchLiteralCst("import", false);
            if (elem6_0.isSuccess() && elem6_0.node != null) {
                children.add(elem6_0.node);
            }
            if (elem6_0.isFailure()) {
                restoreLocation(seqStart6);
                alt0_1 = elem6_0;
            }
        }
        if (alt0_1.isSuccess()) {
            var optStart8 = location();
            if (!inTokenBoundary) skipWhitespace();
            var optElem8 = matchLiteralCst("static", false);
            if (optElem8.isSuccess() && optElem8.node != null) {
                children.add(optElem8.node);
            }
            var elem6_1 = optElem8.isSuccess() ? optElem8 : CstParseResult.success(null, "", location());
            if (optElem8.isFailure()) {
                restoreLocation(optStart8);
            }
            if (elem6_1.isFailure()) {
                restoreLocation(seqStart6);
                alt0_1 = elem6_1;
            }
        }
        if (alt0_1.isSuccess()) {
            var trivia10 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem6_2 = parse_QualifiedName(trivia10);
            if (elem6_2.isSuccess() && elem6_2.node != null) {
                children.add(elem6_2.node);
            }
            if (elem6_2.isFailure()) {
                restoreLocation(seqStart6);
                alt0_1 = elem6_2;
            }
        }
        if (alt0_1.isSuccess()) {
            var optStart11 = location();
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult optElem11 = CstParseResult.success(null, "", location());
            var seqStart13 = location();
            if (optElem11.isSuccess()) {
                var elem13_0 = matchLiteralCst(".", false);
                if (elem13_0.isSuccess() && elem13_0.node != null) {
                    children.add(elem13_0.node);
                }
                if (elem13_0.isFailure()) {
                    restoreLocation(seqStart13);
                    optElem11 = elem13_0;
                }
            }
            if (optElem11.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var elem13_1 = matchLiteralCst("*", false);
                if (elem13_1.isSuccess() && elem13_1.node != null) {
                    children.add(elem13_1.node);
                }
                if (elem13_1.isFailure()) {
                    restoreLocation(seqStart13);
                    optElem11 = elem13_1;
                }
            }
            if (optElem11.isSuccess()) {
                optElem11 = CstParseResult.success(null, substring(seqStart13.offset(), pos), location());
            }
            var elem6_3 = optElem11.isSuccess() ? optElem11 : CstParseResult.success(null, "", location());
            if (optElem11.isFailure()) {
                restoreLocation(optStart11);
            }
            if (elem6_3.isFailure()) {
                restoreLocation(seqStart6);
                alt0_1 = elem6_3;
            }
        }
        if (alt0_1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem6_4 = matchLiteralCst(";", false);
            if (elem6_4.isSuccess() && elem6_4.node != null) {
                children.add(elem6_4.node);
            }
            if (elem6_4.isFailure()) {
                restoreLocation(seqStart6);
                alt0_1 = elem6_4;
            }
        }
        if (alt0_1.isSuccess()) {
            alt0_1 = CstParseResult.success(null, substring(seqStart6.offset(), pos), location());
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_IMPORT_DECL, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_ModuleDecl(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(4, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            CstParseResult elem0_0 = CstParseResult.success(null, "", location());
            var zomStart1 = location();
            while (true) {
                var beforeLoc1 = location();
                var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem1 = parse_Annotation(trivia2);
                if (zomElem1.isSuccess() && zomElem1.node != null) {
                    children.add(zomElem1.node);
                }
                if (zomElem1.isFailure() || location().offset() == beforeLoc1.offset()) {
                    restoreLocation(beforeLoc1);
                    break;
                }
            }
            elem0_0 = CstParseResult.success(null, substring(zomStart1.offset(), pos), location());
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            var optStart3 = location();
            if (!inTokenBoundary) skipWhitespace();
            var optElem3 = matchLiteralCst("open", false);
            if (optElem3.isSuccess() && optElem3.node != null) {
                children.add(optElem3.node);
            }
            var elem0_1 = optElem3.isSuccess() ? optElem3 : CstParseResult.success(null, "", location());
            if (optElem3.isFailure()) {
                restoreLocation(optStart3);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_2 = matchLiteralCst("module", false);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_3 = parse_QualifiedName(trivia6);
            if (elem0_3.isSuccess() && elem0_3.node != null) {
                children.add(elem0_3.node);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_4 = matchLiteralCst("{", false);
            if (elem0_4.isSuccess() && elem0_4.node != null) {
                children.add(elem0_4.node);
            }
            if (elem0_4.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_4;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_5 = CstParseResult.success(null, "", location());
            var zomStart8 = location();
            while (true) {
                var beforeLoc8 = location();
                var trivia9 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem8 = parse_ModuleDirective(trivia9);
                if (zomElem8.isSuccess() && zomElem8.node != null) {
                    children.add(zomElem8.node);
                }
                if (zomElem8.isFailure() || location().offset() == beforeLoc8.offset()) {
                    restoreLocation(beforeLoc8);
                    break;
                }
            }
            elem0_5 = CstParseResult.success(null, substring(zomStart8.offset(), pos), location());
            if (elem0_5.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_5;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_6 = matchLiteralCst("}", false);
            if (elem0_6.isSuccess() && elem0_6.node != null) {
                children.add(elem0_6.node);
            }
            if (elem0_6.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_6;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_MODULE_DECL, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_ModuleDirective(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(5, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_0 = parse_RequiresDirective(trivia1);
        if (alt0_0.isSuccess() && alt0_0.node != null) {
            children.add(alt0_0.node);
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_1 = parse_ExportsDirective(trivia2);
        if (alt0_1.isSuccess() && alt0_1.node != null) {
            children.add(alt0_1.node);
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_2 = parse_OpensDirective(trivia3);
        if (alt0_2.isSuccess() && alt0_2.node != null) {
            children.add(alt0_2.node);
        }
        if (alt0_2.isSuccess()) {
            result = alt0_2;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_3 = parse_UsesDirective(trivia4);
        if (alt0_3.isSuccess() && alt0_3.node != null) {
            children.add(alt0_3.node);
        }
        if (alt0_3.isSuccess()) {
            result = alt0_3;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia5 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_4 = parse_ProvidesDirective(trivia5);
        if (alt0_4.isSuccess() && alt0_4.node != null) {
            children.add(alt0_4.node);
        }
        if (alt0_4.isSuccess()) {
            result = alt0_4;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        }
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_MODULE_DIRECTIVE, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_RequiresDirective(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(6, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("requires", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem2 = null;
                var choiceStart4 = location();
                var savedChildren4 = new ArrayList<>(children);
                children.clear();
                children.addAll(savedChildren4);
                var alt4_0 = matchLiteralCst("transitive", false);
                if (alt4_0.isSuccess() && alt4_0.node != null) {
                    children.add(alt4_0.node);
                }
                if (alt4_0.isSuccess()) {
                    zomElem2 = alt4_0;
                } else {
                    restoreLocation(choiceStart4);
                children.clear();
                children.addAll(savedChildren4);
                var alt4_1 = matchLiteralCst("static", false);
                if (alt4_1.isSuccess() && alt4_1.node != null) {
                    children.add(alt4_1.node);
                }
                if (alt4_1.isSuccess()) {
                    zomElem2 = alt4_1;
                } else {
                    restoreLocation(choiceStart4);
                }
                }
                if (zomElem2 == null) {
                    children.clear();
                    children.addAll(savedChildren4);
                    zomElem2 = CstParseResult.failure("one of alternatives");
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            var trivia7 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_2 = parse_QualifiedName(trivia7);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_3 = matchLiteralCst(";", false);
            if (elem0_3.isSuccess() && elem0_3.node != null) {
                children.add(elem0_3.node);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_REQUIRES_DIRECTIVE, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_ExportsDirective(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(7, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("exports", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_QualifiedName(trivia2);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            var optStart3 = location();
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult optElem3 = CstParseResult.success(null, "", location());
            var seqStart5 = location();
            if (optElem3.isSuccess()) {
                var elem5_0 = matchLiteralCst("to", false);
                if (elem5_0.isSuccess() && elem5_0.node != null) {
                    children.add(elem5_0.node);
                }
                if (elem5_0.isFailure()) {
                    restoreLocation(seqStart5);
                    optElem3 = elem5_0;
                }
            }
            if (optElem3.isSuccess()) {
                var trivia7 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var elem5_1 = parse_QualifiedName(trivia7);
                if (elem5_1.isSuccess() && elem5_1.node != null) {
                    children.add(elem5_1.node);
                }
                if (elem5_1.isFailure()) {
                    restoreLocation(seqStart5);
                    optElem3 = elem5_1;
                }
            }
            if (optElem3.isSuccess()) {
                CstParseResult elem5_2 = CstParseResult.success(null, "", location());
                var zomStart8 = location();
                while (true) {
                    var beforeLoc8 = location();
                    if (!inTokenBoundary) skipWhitespace();
                    CstParseResult zomElem8 = CstParseResult.success(null, "", location());
                    var seqStart10 = location();
                    if (zomElem8.isSuccess()) {
                        var elem10_0 = matchLiteralCst(",", false);
                        if (elem10_0.isSuccess() && elem10_0.node != null) {
                            children.add(elem10_0.node);
                        }
                        if (elem10_0.isFailure()) {
                            restoreLocation(seqStart10);
                            zomElem8 = elem10_0;
                        }
                    }
                    if (zomElem8.isSuccess()) {
                        var trivia12 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                        var elem10_1 = parse_QualifiedName(trivia12);
                        if (elem10_1.isSuccess() && elem10_1.node != null) {
                            children.add(elem10_1.node);
                        }
                        if (elem10_1.isFailure()) {
                            restoreLocation(seqStart10);
                            zomElem8 = elem10_1;
                        }
                    }
                    if (zomElem8.isSuccess()) {
                        zomElem8 = CstParseResult.success(null, substring(seqStart10.offset(), pos), location());
                    }
                    if (zomElem8.isFailure() || location().offset() == beforeLoc8.offset()) {
                        restoreLocation(beforeLoc8);
                        break;
                    }
                }
                elem5_2 = CstParseResult.success(null, substring(zomStart8.offset(), pos), location());
                if (elem5_2.isFailure()) {
                    restoreLocation(seqStart5);
                    optElem3 = elem5_2;
                }
            }
            if (optElem3.isSuccess()) {
                optElem3 = CstParseResult.success(null, substring(seqStart5.offset(), pos), location());
            }
            var elem0_2 = optElem3.isSuccess() ? optElem3 : CstParseResult.success(null, "", location());
            if (optElem3.isFailure()) {
                restoreLocation(optStart3);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_3 = matchLiteralCst(";", false);
            if (elem0_3.isSuccess() && elem0_3.node != null) {
                children.add(elem0_3.node);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_EXPORTS_DIRECTIVE, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_OpensDirective(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(8, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("opens", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_QualifiedName(trivia2);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            var optStart3 = location();
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult optElem3 = CstParseResult.success(null, "", location());
            var seqStart5 = location();
            if (optElem3.isSuccess()) {
                var elem5_0 = matchLiteralCst("to", false);
                if (elem5_0.isSuccess() && elem5_0.node != null) {
                    children.add(elem5_0.node);
                }
                if (elem5_0.isFailure()) {
                    restoreLocation(seqStart5);
                    optElem3 = elem5_0;
                }
            }
            if (optElem3.isSuccess()) {
                var trivia7 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var elem5_1 = parse_QualifiedName(trivia7);
                if (elem5_1.isSuccess() && elem5_1.node != null) {
                    children.add(elem5_1.node);
                }
                if (elem5_1.isFailure()) {
                    restoreLocation(seqStart5);
                    optElem3 = elem5_1;
                }
            }
            if (optElem3.isSuccess()) {
                CstParseResult elem5_2 = CstParseResult.success(null, "", location());
                var zomStart8 = location();
                while (true) {
                    var beforeLoc8 = location();
                    if (!inTokenBoundary) skipWhitespace();
                    CstParseResult zomElem8 = CstParseResult.success(null, "", location());
                    var seqStart10 = location();
                    if (zomElem8.isSuccess()) {
                        var elem10_0 = matchLiteralCst(",", false);
                        if (elem10_0.isSuccess() && elem10_0.node != null) {
                            children.add(elem10_0.node);
                        }
                        if (elem10_0.isFailure()) {
                            restoreLocation(seqStart10);
                            zomElem8 = elem10_0;
                        }
                    }
                    if (zomElem8.isSuccess()) {
                        var trivia12 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                        var elem10_1 = parse_QualifiedName(trivia12);
                        if (elem10_1.isSuccess() && elem10_1.node != null) {
                            children.add(elem10_1.node);
                        }
                        if (elem10_1.isFailure()) {
                            restoreLocation(seqStart10);
                            zomElem8 = elem10_1;
                        }
                    }
                    if (zomElem8.isSuccess()) {
                        zomElem8 = CstParseResult.success(null, substring(seqStart10.offset(), pos), location());
                    }
                    if (zomElem8.isFailure() || location().offset() == beforeLoc8.offset()) {
                        restoreLocation(beforeLoc8);
                        break;
                    }
                }
                elem5_2 = CstParseResult.success(null, substring(zomStart8.offset(), pos), location());
                if (elem5_2.isFailure()) {
                    restoreLocation(seqStart5);
                    optElem3 = elem5_2;
                }
            }
            if (optElem3.isSuccess()) {
                optElem3 = CstParseResult.success(null, substring(seqStart5.offset(), pos), location());
            }
            var elem0_2 = optElem3.isSuccess() ? optElem3 : CstParseResult.success(null, "", location());
            if (optElem3.isFailure()) {
                restoreLocation(optStart3);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_3 = matchLiteralCst(";", false);
            if (elem0_3.isSuccess() && elem0_3.node != null) {
                children.add(elem0_3.node);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_OPENS_DIRECTIVE, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_UsesDirective(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(9, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("uses", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_QualifiedName(trivia2);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_2 = matchLiteralCst(";", false);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_USES_DIRECTIVE, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_ProvidesDirective(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(10, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("provides", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_QualifiedName(trivia2);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_2 = matchLiteralCst("with", false);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_3 = parse_QualifiedName(trivia4);
            if (elem0_3.isSuccess() && elem0_3.node != null) {
                children.add(elem0_3.node);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_4 = CstParseResult.success(null, "", location());
            var zomStart5 = location();
            while (true) {
                var beforeLoc5 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem5 = CstParseResult.success(null, "", location());
                var seqStart7 = location();
                if (zomElem5.isSuccess()) {
                    var elem7_0 = matchLiteralCst(",", false);
                    if (elem7_0.isSuccess() && elem7_0.node != null) {
                        children.add(elem7_0.node);
                    }
                    if (elem7_0.isFailure()) {
                        restoreLocation(seqStart7);
                        zomElem5 = elem7_0;
                    }
                }
                if (zomElem5.isSuccess()) {
                    var trivia9 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem7_1 = parse_QualifiedName(trivia9);
                    if (elem7_1.isSuccess() && elem7_1.node != null) {
                        children.add(elem7_1.node);
                    }
                    if (elem7_1.isFailure()) {
                        restoreLocation(seqStart7);
                        zomElem5 = elem7_1;
                    }
                }
                if (zomElem5.isSuccess()) {
                    zomElem5 = CstParseResult.success(null, substring(seqStart7.offset(), pos), location());
                }
                if (zomElem5.isFailure() || location().offset() == beforeLoc5.offset()) {
                    restoreLocation(beforeLoc5);
                    break;
                }
            }
            elem0_4 = CstParseResult.success(null, substring(zomStart5.offset(), pos), location());
            if (elem0_4.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_4;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_5 = matchLiteralCst(";", false);
            if (elem0_5.isSuccess() && elem0_5.node != null) {
                children.add(elem0_5.node);
            }
            if (elem0_5.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_5;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_PROVIDES_DIRECTIVE, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_TypeDecl(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(11, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            CstParseResult elem0_0 = CstParseResult.success(null, "", location());
            var zomStart1 = location();
            while (true) {
                var beforeLoc1 = location();
                var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem1 = parse_Annotation(trivia2);
                if (zomElem1.isSuccess() && zomElem1.node != null) {
                    children.add(zomElem1.node);
                }
                if (zomElem1.isFailure() || location().offset() == beforeLoc1.offset()) {
                    restoreLocation(beforeLoc1);
                    break;
                }
            }
            elem0_0 = CstParseResult.success(null, substring(zomStart1.offset(), pos), location());
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart3 = location();
            while (true) {
                var beforeLoc3 = location();
                var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem3 = parse_Modifier(trivia4);
                if (zomElem3.isSuccess() && zomElem3.node != null) {
                    children.add(zomElem3.node);
                }
                if (zomElem3.isFailure() || location().offset() == beforeLoc3.offset()) {
                    restoreLocation(beforeLoc3);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart3.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            var trivia5 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_2 = parse_TypeKind(trivia5);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_TYPE_DECL, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_TypeKind(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(12, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_0 = parse_ClassDecl(trivia1);
        if (alt0_0.isSuccess() && alt0_0.node != null) {
            children.add(alt0_0.node);
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_1 = parse_InterfaceDecl(trivia2);
        if (alt0_1.isSuccess() && alt0_1.node != null) {
            children.add(alt0_1.node);
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_2 = parse_EnumDecl(trivia3);
        if (alt0_2.isSuccess() && alt0_2.node != null) {
            children.add(alt0_2.node);
        }
        if (alt0_2.isSuccess()) {
            result = alt0_2;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_3 = parse_RecordDecl(trivia4);
        if (alt0_3.isSuccess() && alt0_3.node != null) {
            children.add(alt0_3.node);
        }
        if (alt0_3.isSuccess()) {
            result = alt0_3;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia5 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_4 = parse_AnnotationDecl(trivia5);
        if (alt0_4.isSuccess() && alt0_4.node != null) {
            children.add(alt0_4.node);
        }
        if (alt0_4.isSuccess()) {
            result = alt0_4;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        }
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_TYPE_KIND, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_ClassDecl(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(13, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("class", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_Identifier(trivia2);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            var optStart3 = location();
            var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem3 = parse_TypeParams(trivia4);
            if (optElem3.isSuccess() && optElem3.node != null) {
                children.add(optElem3.node);
            }
            var elem0_2 = optElem3.isSuccess() ? optElem3 : CstParseResult.success(null, "", location());
            if (optElem3.isFailure()) {
                restoreLocation(optStart3);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            var optStart5 = location();
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult optElem5 = CstParseResult.success(null, "", location());
            var seqStart7 = location();
            if (optElem5.isSuccess()) {
                var elem7_0 = matchLiteralCst("extends", false);
                if (elem7_0.isSuccess() && elem7_0.node != null) {
                    children.add(elem7_0.node);
                }
                if (elem7_0.isFailure()) {
                    restoreLocation(seqStart7);
                    optElem5 = elem7_0;
                }
            }
            if (optElem5.isSuccess()) {
                var trivia9 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var elem7_1 = parse_Type(trivia9);
                if (elem7_1.isSuccess() && elem7_1.node != null) {
                    children.add(elem7_1.node);
                }
                if (elem7_1.isFailure()) {
                    restoreLocation(seqStart7);
                    optElem5 = elem7_1;
                }
            }
            if (optElem5.isSuccess()) {
                optElem5 = CstParseResult.success(null, substring(seqStart7.offset(), pos), location());
            }
            var elem0_3 = optElem5.isSuccess() ? optElem5 : CstParseResult.success(null, "", location());
            if (optElem5.isFailure()) {
                restoreLocation(optStart5);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            var optStart10 = location();
            var trivia11 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem10 = parse_ImplementsClause(trivia11);
            if (optElem10.isSuccess() && optElem10.node != null) {
                children.add(optElem10.node);
            }
            var elem0_4 = optElem10.isSuccess() ? optElem10 : CstParseResult.success(null, "", location());
            if (optElem10.isFailure()) {
                restoreLocation(optStart10);
            }
            if (elem0_4.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_4;
            }
        }
        if (result.isSuccess()) {
            var optStart12 = location();
            var trivia13 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem12 = parse_PermitsClause(trivia13);
            if (optElem12.isSuccess() && optElem12.node != null) {
                children.add(optElem12.node);
            }
            var elem0_5 = optElem12.isSuccess() ? optElem12 : CstParseResult.success(null, "", location());
            if (optElem12.isFailure()) {
                restoreLocation(optStart12);
            }
            if (elem0_5.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_5;
            }
        }
        if (result.isSuccess()) {
            var trivia14 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_6 = parse_ClassBody(trivia14);
            if (elem0_6.isSuccess() && elem0_6.node != null) {
                children.add(elem0_6.node);
            }
            if (elem0_6.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_6;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_CLASS_DECL, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_InterfaceDecl(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(14, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("interface", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_Identifier(trivia2);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            var optStart3 = location();
            var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem3 = parse_TypeParams(trivia4);
            if (optElem3.isSuccess() && optElem3.node != null) {
                children.add(optElem3.node);
            }
            var elem0_2 = optElem3.isSuccess() ? optElem3 : CstParseResult.success(null, "", location());
            if (optElem3.isFailure()) {
                restoreLocation(optStart3);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            var optStart5 = location();
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult optElem5 = CstParseResult.success(null, "", location());
            var seqStart7 = location();
            if (optElem5.isSuccess()) {
                var elem7_0 = matchLiteralCst("extends", false);
                if (elem7_0.isSuccess() && elem7_0.node != null) {
                    children.add(elem7_0.node);
                }
                if (elem7_0.isFailure()) {
                    restoreLocation(seqStart7);
                    optElem5 = elem7_0;
                }
            }
            if (optElem5.isSuccess()) {
                var trivia9 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var elem7_1 = parse_TypeList(trivia9);
                if (elem7_1.isSuccess() && elem7_1.node != null) {
                    children.add(elem7_1.node);
                }
                if (elem7_1.isFailure()) {
                    restoreLocation(seqStart7);
                    optElem5 = elem7_1;
                }
            }
            if (optElem5.isSuccess()) {
                optElem5 = CstParseResult.success(null, substring(seqStart7.offset(), pos), location());
            }
            var elem0_3 = optElem5.isSuccess() ? optElem5 : CstParseResult.success(null, "", location());
            if (optElem5.isFailure()) {
                restoreLocation(optStart5);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            var optStart10 = location();
            var trivia11 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem10 = parse_PermitsClause(trivia11);
            if (optElem10.isSuccess() && optElem10.node != null) {
                children.add(optElem10.node);
            }
            var elem0_4 = optElem10.isSuccess() ? optElem10 : CstParseResult.success(null, "", location());
            if (optElem10.isFailure()) {
                restoreLocation(optStart10);
            }
            if (elem0_4.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_4;
            }
        }
        if (result.isSuccess()) {
            var trivia12 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_5 = parse_ClassBody(trivia12);
            if (elem0_5.isSuccess() && elem0_5.node != null) {
                children.add(elem0_5.node);
            }
            if (elem0_5.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_5;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_INTERFACE_DECL, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_AnnotationDecl(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(15, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("@", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_1 = matchLiteralCst("interface", false);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_2 = parse_Identifier(trivia3);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_3 = parse_AnnotationBody(trivia4);
            if (elem0_3.isSuccess() && elem0_3.node != null) {
                children.add(elem0_3.node);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_ANNOTATION_DECL, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_AnnotationBody(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(16, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("{", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem2 = parse_AnnotationMember(trivia3);
                if (zomElem2.isSuccess() && zomElem2.node != null) {
                    children.add(zomElem2.node);
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_2 = matchLiteralCst("}", false);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_ANNOTATION_BODY, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_AnnotationMember(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(17, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_0 = CstParseResult.success(null, "", location());
        var seqStart1 = location();
        if (alt0_0.isSuccess()) {
            CstParseResult elem1_0 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem2 = parse_Annotation(trivia3);
                if (zomElem2.isSuccess() && zomElem2.node != null) {
                    children.add(zomElem2.node);
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem1_0 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem1_0.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_0;
            }
        }
        if (alt0_0.isSuccess()) {
            CstParseResult elem1_1 = CstParseResult.success(null, "", location());
            var zomStart4 = location();
            while (true) {
                var beforeLoc4 = location();
                var trivia5 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem4 = parse_Modifier(trivia5);
                if (zomElem4.isSuccess() && zomElem4.node != null) {
                    children.add(zomElem4.node);
                }
                if (zomElem4.isFailure() || location().offset() == beforeLoc4.offset()) {
                    restoreLocation(beforeLoc4);
                    break;
                }
            }
            elem1_1 = CstParseResult.success(null, substring(zomStart4.offset(), pos), location());
            if (elem1_1.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_1;
            }
        }
        if (alt0_0.isSuccess()) {
            CstParseResult elem1_2 = null;
            var choiceStart7 = location();
            var savedChildren7 = new ArrayList<>(children);
            children.clear();
            children.addAll(savedChildren7);
            var trivia8 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var alt7_0 = parse_AnnotationElemDecl(trivia8);
            if (alt7_0.isSuccess() && alt7_0.node != null) {
                children.add(alt7_0.node);
            }
            if (alt7_0.isSuccess()) {
                elem1_2 = alt7_0;
            } else {
                restoreLocation(choiceStart7);
            children.clear();
            children.addAll(savedChildren7);
            var trivia9 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var alt7_1 = parse_FieldDecl(trivia9);
            if (alt7_1.isSuccess() && alt7_1.node != null) {
                children.add(alt7_1.node);
            }
            if (alt7_1.isSuccess()) {
                elem1_2 = alt7_1;
            } else {
                restoreLocation(choiceStart7);
            children.clear();
            children.addAll(savedChildren7);
            var trivia10 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var alt7_2 = parse_TypeKind(trivia10);
            if (alt7_2.isSuccess() && alt7_2.node != null) {
                children.add(alt7_2.node);
            }
            if (alt7_2.isSuccess()) {
                elem1_2 = alt7_2;
            } else {
                restoreLocation(choiceStart7);
            }
            }
            }
            if (elem1_2 == null) {
                children.clear();
                children.addAll(savedChildren7);
                elem1_2 = CstParseResult.failure("one of alternatives");
            }
            if (elem1_2.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_2;
            }
        }
        if (alt0_0.isSuccess()) {
            alt0_0 = CstParseResult.success(null, substring(seqStart1.offset(), pos), location());
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_1 = matchLiteralCst(";", false);
        if (alt0_1.isSuccess() && alt0_1.node != null) {
            children.add(alt0_1.node);
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_ANNOTATION_MEMBER, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_AnnotationElemDecl(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(18, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_Type(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_Identifier(trivia2);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_2 = matchLiteralCst("(", false);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_3 = matchLiteralCst(")", false);
            if (elem0_3.isSuccess() && elem0_3.node != null) {
                children.add(elem0_3.node);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            var optStart5 = location();
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult optElem5 = CstParseResult.success(null, "", location());
            var seqStart7 = location();
            if (optElem5.isSuccess()) {
                var elem7_0 = matchLiteralCst("default", false);
                if (elem7_0.isSuccess() && elem7_0.node != null) {
                    children.add(elem7_0.node);
                }
                if (elem7_0.isFailure()) {
                    restoreLocation(seqStart7);
                    optElem5 = elem7_0;
                }
            }
            if (optElem5.isSuccess()) {
                var trivia9 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var elem7_1 = parse_AnnotationElem(trivia9);
                if (elem7_1.isSuccess() && elem7_1.node != null) {
                    children.add(elem7_1.node);
                }
                if (elem7_1.isFailure()) {
                    restoreLocation(seqStart7);
                    optElem5 = elem7_1;
                }
            }
            if (optElem5.isSuccess()) {
                optElem5 = CstParseResult.success(null, substring(seqStart7.offset(), pos), location());
            }
            var elem0_4 = optElem5.isSuccess() ? optElem5 : CstParseResult.success(null, "", location());
            if (optElem5.isFailure()) {
                restoreLocation(optStart5);
            }
            if (elem0_4.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_4;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_5 = matchLiteralCst(";", false);
            if (elem0_5.isSuccess() && elem0_5.node != null) {
                children.add(elem0_5.node);
            }
            if (elem0_5.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_5;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_ANNOTATION_ELEM_DECL, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_EnumDecl(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(19, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("enum", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_Identifier(trivia2);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            var optStart3 = location();
            var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem3 = parse_ImplementsClause(trivia4);
            if (optElem3.isSuccess() && optElem3.node != null) {
                children.add(optElem3.node);
            }
            var elem0_2 = optElem3.isSuccess() ? optElem3 : CstParseResult.success(null, "", location());
            if (optElem3.isFailure()) {
                restoreLocation(optStart3);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            var trivia5 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_3 = parse_EnumBody(trivia5);
            if (elem0_3.isSuccess() && elem0_3.node != null) {
                children.add(elem0_3.node);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_ENUM_DECL, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_RecordDecl(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(20, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("record", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_Identifier(trivia2);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            var optStart3 = location();
            var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem3 = parse_TypeParams(trivia4);
            if (optElem3.isSuccess() && optElem3.node != null) {
                children.add(optElem3.node);
            }
            var elem0_2 = optElem3.isSuccess() ? optElem3 : CstParseResult.success(null, "", location());
            if (optElem3.isFailure()) {
                restoreLocation(optStart3);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_3 = matchLiteralCst("(", false);
            if (elem0_3.isSuccess() && elem0_3.node != null) {
                children.add(elem0_3.node);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            var optStart6 = location();
            var trivia7 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem6 = parse_RecordComponents(trivia7);
            if (optElem6.isSuccess() && optElem6.node != null) {
                children.add(optElem6.node);
            }
            var elem0_4 = optElem6.isSuccess() ? optElem6 : CstParseResult.success(null, "", location());
            if (optElem6.isFailure()) {
                restoreLocation(optStart6);
            }
            if (elem0_4.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_4;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_5 = matchLiteralCst(")", false);
            if (elem0_5.isSuccess() && elem0_5.node != null) {
                children.add(elem0_5.node);
            }
            if (elem0_5.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_5;
            }
        }
        if (result.isSuccess()) {
            var optStart9 = location();
            var trivia10 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem9 = parse_ImplementsClause(trivia10);
            if (optElem9.isSuccess() && optElem9.node != null) {
                children.add(optElem9.node);
            }
            var elem0_6 = optElem9.isSuccess() ? optElem9 : CstParseResult.success(null, "", location());
            if (optElem9.isFailure()) {
                restoreLocation(optStart9);
            }
            if (elem0_6.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_6;
            }
        }
        if (result.isSuccess()) {
            var trivia11 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_7 = parse_RecordBody(trivia11);
            if (elem0_7.isSuccess() && elem0_7.node != null) {
                children.add(elem0_7.node);
            }
            if (elem0_7.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_7;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_RECORD_DECL, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_ImplementsClause(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(21, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("implements", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_TypeList(trivia2);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_IMPLEMENTS_CLAUSE, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_PermitsClause(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(22, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("permits", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_TypeList(trivia2);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_PERMITS_CLAUSE, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_TypeList(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(23, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_Type(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem2 = CstParseResult.success(null, "", location());
                var seqStart4 = location();
                if (zomElem2.isSuccess()) {
                    var elem4_0 = matchLiteralCst(",", false);
                    if (elem4_0.isSuccess() && elem4_0.node != null) {
                        children.add(elem4_0.node);
                    }
                    if (elem4_0.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_0;
                    }
                }
                if (zomElem2.isSuccess()) {
                    var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem4_1 = parse_Type(trivia6);
                    if (elem4_1.isSuccess() && elem4_1.node != null) {
                        children.add(elem4_1.node);
                    }
                    if (elem4_1.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_1;
                    }
                }
                if (zomElem2.isSuccess()) {
                    zomElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_TYPE_LIST, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_TypeParams(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(24, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("<", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_TypeParam(trivia2);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_2 = CstParseResult.success(null, "", location());
            var zomStart3 = location();
            while (true) {
                var beforeLoc3 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem3 = CstParseResult.success(null, "", location());
                var seqStart5 = location();
                if (zomElem3.isSuccess()) {
                    var elem5_0 = matchLiteralCst(",", false);
                    if (elem5_0.isSuccess() && elem5_0.node != null) {
                        children.add(elem5_0.node);
                    }
                    if (elem5_0.isFailure()) {
                        restoreLocation(seqStart5);
                        zomElem3 = elem5_0;
                    }
                }
                if (zomElem3.isSuccess()) {
                    var trivia7 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem5_1 = parse_TypeParam(trivia7);
                    if (elem5_1.isSuccess() && elem5_1.node != null) {
                        children.add(elem5_1.node);
                    }
                    if (elem5_1.isFailure()) {
                        restoreLocation(seqStart5);
                        zomElem3 = elem5_1;
                    }
                }
                if (zomElem3.isSuccess()) {
                    zomElem3 = CstParseResult.success(null, substring(seqStart5.offset(), pos), location());
                }
                if (zomElem3.isFailure() || location().offset() == beforeLoc3.offset()) {
                    restoreLocation(beforeLoc3);
                    break;
                }
            }
            elem0_2 = CstParseResult.success(null, substring(zomStart3.offset(), pos), location());
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_3 = matchLiteralCst(">", false);
            if (elem0_3.isSuccess() && elem0_3.node != null) {
                children.add(elem0_3.node);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_TYPE_PARAMS, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_TypeParam(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(25, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_Identifier(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            var optStart2 = location();
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult optElem2 = CstParseResult.success(null, "", location());
            var seqStart4 = location();
            if (optElem2.isSuccess()) {
                var elem4_0 = matchLiteralCst("extends", false);
                if (elem4_0.isSuccess() && elem4_0.node != null) {
                    children.add(elem4_0.node);
                }
                if (elem4_0.isFailure()) {
                    restoreLocation(seqStart4);
                    optElem2 = elem4_0;
                }
            }
            if (optElem2.isSuccess()) {
                var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var elem4_1 = parse_Type(trivia6);
                if (elem4_1.isSuccess() && elem4_1.node != null) {
                    children.add(elem4_1.node);
                }
                if (elem4_1.isFailure()) {
                    restoreLocation(seqStart4);
                    optElem2 = elem4_1;
                }
            }
            if (optElem2.isSuccess()) {
                CstParseResult elem4_2 = CstParseResult.success(null, "", location());
                var zomStart7 = location();
                while (true) {
                    var beforeLoc7 = location();
                    if (!inTokenBoundary) skipWhitespace();
                    CstParseResult zomElem7 = CstParseResult.success(null, "", location());
                    var seqStart9 = location();
                    if (zomElem7.isSuccess()) {
                        var elem9_0 = matchLiteralCst("&", false);
                        if (elem9_0.isSuccess() && elem9_0.node != null) {
                            children.add(elem9_0.node);
                        }
                        if (elem9_0.isFailure()) {
                            restoreLocation(seqStart9);
                            zomElem7 = elem9_0;
                        }
                    }
                    if (zomElem7.isSuccess()) {
                        var trivia11 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                        var elem9_1 = parse_Type(trivia11);
                        if (elem9_1.isSuccess() && elem9_1.node != null) {
                            children.add(elem9_1.node);
                        }
                        if (elem9_1.isFailure()) {
                            restoreLocation(seqStart9);
                            zomElem7 = elem9_1;
                        }
                    }
                    if (zomElem7.isSuccess()) {
                        zomElem7 = CstParseResult.success(null, substring(seqStart9.offset(), pos), location());
                    }
                    if (zomElem7.isFailure() || location().offset() == beforeLoc7.offset()) {
                        restoreLocation(beforeLoc7);
                        break;
                    }
                }
                elem4_2 = CstParseResult.success(null, substring(zomStart7.offset(), pos), location());
                if (elem4_2.isFailure()) {
                    restoreLocation(seqStart4);
                    optElem2 = elem4_2;
                }
            }
            if (optElem2.isSuccess()) {
                optElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
            }
            var elem0_1 = optElem2.isSuccess() ? optElem2 : CstParseResult.success(null, "", location());
            if (optElem2.isFailure()) {
                restoreLocation(optStart2);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_TYPE_PARAM, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_ClassBody(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(26, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("{", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem2 = parse_ClassMember(trivia3);
                if (zomElem2.isSuccess() && zomElem2.node != null) {
                    children.add(zomElem2.node);
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_2 = matchLiteralCst("}", false);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_CLASS_BODY, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_ClassMember(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(27, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_0 = CstParseResult.success(null, "", location());
        var seqStart1 = location();
        if (alt0_0.isSuccess()) {
            CstParseResult elem1_0 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem2 = parse_Annotation(trivia3);
                if (zomElem2.isSuccess() && zomElem2.node != null) {
                    children.add(zomElem2.node);
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem1_0 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem1_0.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_0;
            }
        }
        if (alt0_0.isSuccess()) {
            CstParseResult elem1_1 = CstParseResult.success(null, "", location());
            var zomStart4 = location();
            while (true) {
                var beforeLoc4 = location();
                var trivia5 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem4 = parse_Modifier(trivia5);
                if (zomElem4.isSuccess() && zomElem4.node != null) {
                    children.add(zomElem4.node);
                }
                if (zomElem4.isFailure() || location().offset() == beforeLoc4.offset()) {
                    restoreLocation(beforeLoc4);
                    break;
                }
            }
            elem1_1 = CstParseResult.success(null, substring(zomStart4.offset(), pos), location());
            if (elem1_1.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_1;
            }
        }
        if (alt0_0.isSuccess()) {
            var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem1_2 = parse_Member(trivia6);
            if (elem1_2.isSuccess() && elem1_2.node != null) {
                children.add(elem1_2.node);
            }
            if (elem1_2.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_2;
            }
        }
        if (alt0_0.isSuccess()) {
            alt0_0 = CstParseResult.success(null, substring(seqStart1.offset(), pos), location());
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia7 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_1 = parse_InitializerBlock(trivia7);
        if (alt0_1.isSuccess() && alt0_1.node != null) {
            children.add(alt0_1.node);
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_2 = matchLiteralCst(";", false);
        if (alt0_2.isSuccess() && alt0_2.node != null) {
            children.add(alt0_2.node);
        }
        if (alt0_2.isSuccess()) {
            result = alt0_2;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_CLASS_MEMBER, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Member(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(28, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_0 = parse_ConstructorDecl(trivia1);
        if (alt0_0.isSuccess() && alt0_0.node != null) {
            children.add(alt0_0.node);
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_1 = parse_TypeKind(trivia2);
        if (alt0_1.isSuccess() && alt0_1.node != null) {
            children.add(alt0_1.node);
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_2 = parse_MethodDecl(trivia3);
        if (alt0_2.isSuccess() && alt0_2.node != null) {
            children.add(alt0_2.node);
        }
        if (alt0_2.isSuccess()) {
            result = alt0_2;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_3 = parse_FieldDecl(trivia4);
        if (alt0_3.isSuccess() && alt0_3.node != null) {
            children.add(alt0_3.node);
        }
        if (alt0_3.isSuccess()) {
            result = alt0_3;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_MEMBER, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_InitializerBlock(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(29, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var optStart1 = location();
            if (!inTokenBoundary) skipWhitespace();
            var optElem1 = matchLiteralCst("static", false);
            if (optElem1.isSuccess() && optElem1.node != null) {
                children.add(optElem1.node);
            }
            var elem0_0 = optElem1.isSuccess() ? optElem1 : CstParseResult.success(null, "", location());
            if (optElem1.isFailure()) {
                restoreLocation(optStart1);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_Block(trivia3);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_INITIALIZER_BLOCK, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_EnumBody(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(30, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("{", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            var optStart2 = location();
            var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem2 = parse_EnumConsts(trivia3);
            if (optElem2.isSuccess() && optElem2.node != null) {
                children.add(optElem2.node);
            }
            var elem0_1 = optElem2.isSuccess() ? optElem2 : CstParseResult.success(null, "", location());
            if (optElem2.isFailure()) {
                restoreLocation(optStart2);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            var optStart4 = location();
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult optElem4 = CstParseResult.success(null, "", location());
            var seqStart6 = location();
            if (optElem4.isSuccess()) {
                var elem6_0 = matchLiteralCst(";", false);
                if (elem6_0.isSuccess() && elem6_0.node != null) {
                    children.add(elem6_0.node);
                }
                if (elem6_0.isFailure()) {
                    restoreLocation(seqStart6);
                    optElem4 = elem6_0;
                }
            }
            if (optElem4.isSuccess()) {
                CstParseResult elem6_1 = CstParseResult.success(null, "", location());
                var zomStart8 = location();
                while (true) {
                    var beforeLoc8 = location();
                    var trivia9 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var zomElem8 = parse_ClassMember(trivia9);
                    if (zomElem8.isSuccess() && zomElem8.node != null) {
                        children.add(zomElem8.node);
                    }
                    if (zomElem8.isFailure() || location().offset() == beforeLoc8.offset()) {
                        restoreLocation(beforeLoc8);
                        break;
                    }
                }
                elem6_1 = CstParseResult.success(null, substring(zomStart8.offset(), pos), location());
                if (elem6_1.isFailure()) {
                    restoreLocation(seqStart6);
                    optElem4 = elem6_1;
                }
            }
            if (optElem4.isSuccess()) {
                optElem4 = CstParseResult.success(null, substring(seqStart6.offset(), pos), location());
            }
            var elem0_2 = optElem4.isSuccess() ? optElem4 : CstParseResult.success(null, "", location());
            if (optElem4.isFailure()) {
                restoreLocation(optStart4);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_3 = matchLiteralCst("}", false);
            if (elem0_3.isSuccess() && elem0_3.node != null) {
                children.add(elem0_3.node);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_ENUM_BODY, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_EnumConsts(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(31, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_EnumConst(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem2 = CstParseResult.success(null, "", location());
                var seqStart4 = location();
                if (zomElem2.isSuccess()) {
                    var elem4_0 = matchLiteralCst(",", false);
                    if (elem4_0.isSuccess() && elem4_0.node != null) {
                        children.add(elem4_0.node);
                    }
                    if (elem4_0.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_0;
                    }
                }
                if (zomElem2.isSuccess()) {
                    var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem4_1 = parse_EnumConst(trivia6);
                    if (elem4_1.isSuccess() && elem4_1.node != null) {
                        children.add(elem4_1.node);
                    }
                    if (elem4_1.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_1;
                    }
                }
                if (zomElem2.isSuccess()) {
                    zomElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            var optStart7 = location();
            if (!inTokenBoundary) skipWhitespace();
            var optElem7 = matchLiteralCst(",", false);
            if (optElem7.isSuccess() && optElem7.node != null) {
                children.add(optElem7.node);
            }
            var elem0_2 = optElem7.isSuccess() ? optElem7 : CstParseResult.success(null, "", location());
            if (optElem7.isFailure()) {
                restoreLocation(optStart7);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_ENUM_CONSTS, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_EnumConst(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(32, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            CstParseResult elem0_0 = CstParseResult.success(null, "", location());
            var zomStart1 = location();
            while (true) {
                var beforeLoc1 = location();
                var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem1 = parse_Annotation(trivia2);
                if (zomElem1.isSuccess() && zomElem1.node != null) {
                    children.add(zomElem1.node);
                }
                if (zomElem1.isFailure() || location().offset() == beforeLoc1.offset()) {
                    restoreLocation(beforeLoc1);
                    break;
                }
            }
            elem0_0 = CstParseResult.success(null, substring(zomStart1.offset(), pos), location());
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_Identifier(trivia3);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            var optStart4 = location();
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult optElem4 = CstParseResult.success(null, "", location());
            var seqStart6 = location();
            if (optElem4.isSuccess()) {
                var elem6_0 = matchLiteralCst("(", false);
                if (elem6_0.isSuccess() && elem6_0.node != null) {
                    children.add(elem6_0.node);
                }
                if (elem6_0.isFailure()) {
                    restoreLocation(seqStart6);
                    optElem4 = elem6_0;
                }
            }
            if (optElem4.isSuccess()) {
                var optStart8 = location();
                var trivia9 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var optElem8 = parse_Args(trivia9);
                if (optElem8.isSuccess() && optElem8.node != null) {
                    children.add(optElem8.node);
                }
                var elem6_1 = optElem8.isSuccess() ? optElem8 : CstParseResult.success(null, "", location());
                if (optElem8.isFailure()) {
                    restoreLocation(optStart8);
                }
                if (elem6_1.isFailure()) {
                    restoreLocation(seqStart6);
                    optElem4 = elem6_1;
                }
            }
            if (optElem4.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var elem6_2 = matchLiteralCst(")", false);
                if (elem6_2.isSuccess() && elem6_2.node != null) {
                    children.add(elem6_2.node);
                }
                if (elem6_2.isFailure()) {
                    restoreLocation(seqStart6);
                    optElem4 = elem6_2;
                }
            }
            if (optElem4.isSuccess()) {
                optElem4 = CstParseResult.success(null, substring(seqStart6.offset(), pos), location());
            }
            var elem0_2 = optElem4.isSuccess() ? optElem4 : CstParseResult.success(null, "", location());
            if (optElem4.isFailure()) {
                restoreLocation(optStart4);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            var optStart11 = location();
            var trivia12 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem11 = parse_ClassBody(trivia12);
            if (optElem11.isSuccess() && optElem11.node != null) {
                children.add(optElem11.node);
            }
            var elem0_3 = optElem11.isSuccess() ? optElem11 : CstParseResult.success(null, "", location());
            if (optElem11.isFailure()) {
                restoreLocation(optStart11);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_ENUM_CONST, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_RecordComponents(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(33, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_RecordComp(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem2 = CstParseResult.success(null, "", location());
                var seqStart4 = location();
                if (zomElem2.isSuccess()) {
                    var elem4_0 = matchLiteralCst(",", false);
                    if (elem4_0.isSuccess() && elem4_0.node != null) {
                        children.add(elem4_0.node);
                    }
                    if (elem4_0.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_0;
                    }
                }
                if (zomElem2.isSuccess()) {
                    var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem4_1 = parse_RecordComp(trivia6);
                    if (elem4_1.isSuccess() && elem4_1.node != null) {
                        children.add(elem4_1.node);
                    }
                    if (elem4_1.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_1;
                    }
                }
                if (zomElem2.isSuccess()) {
                    zomElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_RECORD_COMPONENTS, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_RecordComp(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(34, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            CstParseResult elem0_0 = CstParseResult.success(null, "", location());
            var zomStart1 = location();
            while (true) {
                var beforeLoc1 = location();
                var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem1 = parse_Annotation(trivia2);
                if (zomElem1.isSuccess() && zomElem1.node != null) {
                    children.add(zomElem1.node);
                }
                if (zomElem1.isFailure() || location().offset() == beforeLoc1.offset()) {
                    restoreLocation(beforeLoc1);
                    break;
                }
            }
            elem0_0 = CstParseResult.success(null, substring(zomStart1.offset(), pos), location());
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_Type(trivia3);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_2 = parse_Identifier(trivia4);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_RECORD_COMP, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_RecordBody(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(35, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("{", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem2 = parse_RecordMember(trivia3);
                if (zomElem2.isSuccess() && zomElem2.node != null) {
                    children.add(zomElem2.node);
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_2 = matchLiteralCst("}", false);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_RECORD_BODY, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_RecordMember(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(36, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_0 = parse_CompactConstructor(trivia1);
        if (alt0_0.isSuccess() && alt0_0.node != null) {
            children.add(alt0_0.node);
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_1 = parse_ClassMember(trivia2);
        if (alt0_1.isSuccess() && alt0_1.node != null) {
            children.add(alt0_1.node);
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_RECORD_MEMBER, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_CompactConstructor(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(37, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            CstParseResult elem0_0 = CstParseResult.success(null, "", location());
            var zomStart1 = location();
            while (true) {
                var beforeLoc1 = location();
                var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem1 = parse_Annotation(trivia2);
                if (zomElem1.isSuccess() && zomElem1.node != null) {
                    children.add(zomElem1.node);
                }
                if (zomElem1.isFailure() || location().offset() == beforeLoc1.offset()) {
                    restoreLocation(beforeLoc1);
                    break;
                }
            }
            elem0_0 = CstParseResult.success(null, substring(zomStart1.offset(), pos), location());
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart3 = location();
            while (true) {
                var beforeLoc3 = location();
                var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem3 = parse_Modifier(trivia4);
                if (zomElem3.isSuccess() && zomElem3.node != null) {
                    children.add(zomElem3.node);
                }
                if (zomElem3.isFailure() || location().offset() == beforeLoc3.offset()) {
                    restoreLocation(beforeLoc3);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart3.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            var trivia5 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_2 = parse_Identifier(trivia5);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_3 = parse_Block(trivia6);
            if (elem0_3.isSuccess() && elem0_3.node != null) {
                children.add(elem0_3.node);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_COMPACT_CONSTRUCTOR, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_FieldDecl(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(38, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_Type(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_VarDecls(trivia2);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_2 = matchLiteralCst(";", false);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_FIELD_DECL, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_VarDecls(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(39, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_VarDecl(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem2 = CstParseResult.success(null, "", location());
                var seqStart4 = location();
                if (zomElem2.isSuccess()) {
                    var elem4_0 = matchLiteralCst(",", false);
                    if (elem4_0.isSuccess() && elem4_0.node != null) {
                        children.add(elem4_0.node);
                    }
                    if (elem4_0.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_0;
                    }
                }
                if (zomElem2.isSuccess()) {
                    var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem4_1 = parse_VarDecl(trivia6);
                    if (elem4_1.isSuccess() && elem4_1.node != null) {
                        children.add(elem4_1.node);
                    }
                    if (elem4_1.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_1;
                    }
                }
                if (zomElem2.isSuccess()) {
                    zomElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_VAR_DECLS, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_VarDecl(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(40, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_Identifier(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            var optStart2 = location();
            var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem2 = parse_Dims(trivia3);
            if (optElem2.isSuccess() && optElem2.node != null) {
                children.add(optElem2.node);
            }
            var elem0_1 = optElem2.isSuccess() ? optElem2 : CstParseResult.success(null, "", location());
            if (optElem2.isFailure()) {
                restoreLocation(optStart2);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            var optStart4 = location();
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult optElem4 = CstParseResult.success(null, "", location());
            var seqStart6 = location();
            if (optElem4.isSuccess()) {
                var elem6_0 = matchLiteralCst("=", false);
                if (elem6_0.isSuccess() && elem6_0.node != null) {
                    children.add(elem6_0.node);
                }
                if (elem6_0.isFailure()) {
                    restoreLocation(seqStart6);
                    optElem4 = elem6_0;
                }
            }
            if (optElem4.isSuccess()) {
                var trivia8 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var elem6_1 = parse_VarInit(trivia8);
                if (elem6_1.isSuccess() && elem6_1.node != null) {
                    children.add(elem6_1.node);
                }
                if (elem6_1.isFailure()) {
                    restoreLocation(seqStart6);
                    optElem4 = elem6_1;
                }
            }
            if (optElem4.isSuccess()) {
                optElem4 = CstParseResult.success(null, substring(seqStart6.offset(), pos), location());
            }
            var elem0_2 = optElem4.isSuccess() ? optElem4 : CstParseResult.success(null, "", location());
            if (optElem4.isFailure()) {
                restoreLocation(optStart4);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_VAR_DECL, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_VarInit(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(41, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_0 = CstParseResult.success(null, "", location());
        var seqStart1 = location();
        if (alt0_0.isSuccess()) {
            var elem1_0 = matchLiteralCst("{", false);
            if (elem1_0.isSuccess() && elem1_0.node != null) {
                children.add(elem1_0.node);
            }
            if (elem1_0.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_0;
            }
        }
        if (alt0_0.isSuccess()) {
            var optStart3 = location();
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult optElem3 = CstParseResult.success(null, "", location());
            var seqStart5 = location();
            if (optElem3.isSuccess()) {
                var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var elem5_0 = parse_VarInit(trivia6);
                if (elem5_0.isSuccess() && elem5_0.node != null) {
                    children.add(elem5_0.node);
                }
                if (elem5_0.isFailure()) {
                    restoreLocation(seqStart5);
                    optElem3 = elem5_0;
                }
            }
            if (optElem3.isSuccess()) {
                CstParseResult elem5_1 = CstParseResult.success(null, "", location());
                var zomStart7 = location();
                while (true) {
                    var beforeLoc7 = location();
                    if (!inTokenBoundary) skipWhitespace();
                    CstParseResult zomElem7 = CstParseResult.success(null, "", location());
                    var seqStart9 = location();
                    if (zomElem7.isSuccess()) {
                        var elem9_0 = matchLiteralCst(",", false);
                        if (elem9_0.isSuccess() && elem9_0.node != null) {
                            children.add(elem9_0.node);
                        }
                        if (elem9_0.isFailure()) {
                            restoreLocation(seqStart9);
                            zomElem7 = elem9_0;
                        }
                    }
                    if (zomElem7.isSuccess()) {
                        var trivia11 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                        var elem9_1 = parse_VarInit(trivia11);
                        if (elem9_1.isSuccess() && elem9_1.node != null) {
                            children.add(elem9_1.node);
                        }
                        if (elem9_1.isFailure()) {
                            restoreLocation(seqStart9);
                            zomElem7 = elem9_1;
                        }
                    }
                    if (zomElem7.isSuccess()) {
                        zomElem7 = CstParseResult.success(null, substring(seqStart9.offset(), pos), location());
                    }
                    if (zomElem7.isFailure() || location().offset() == beforeLoc7.offset()) {
                        restoreLocation(beforeLoc7);
                        break;
                    }
                }
                elem5_1 = CstParseResult.success(null, substring(zomStart7.offset(), pos), location());
                if (elem5_1.isFailure()) {
                    restoreLocation(seqStart5);
                    optElem3 = elem5_1;
                }
            }
            if (optElem3.isSuccess()) {
                var optStart12 = location();
                if (!inTokenBoundary) skipWhitespace();
                var optElem12 = matchLiteralCst(",", false);
                if (optElem12.isSuccess() && optElem12.node != null) {
                    children.add(optElem12.node);
                }
                var elem5_2 = optElem12.isSuccess() ? optElem12 : CstParseResult.success(null, "", location());
                if (optElem12.isFailure()) {
                    restoreLocation(optStart12);
                }
                if (elem5_2.isFailure()) {
                    restoreLocation(seqStart5);
                    optElem3 = elem5_2;
                }
            }
            if (optElem3.isSuccess()) {
                optElem3 = CstParseResult.success(null, substring(seqStart5.offset(), pos), location());
            }
            var elem1_1 = optElem3.isSuccess() ? optElem3 : CstParseResult.success(null, "", location());
            if (optElem3.isFailure()) {
                restoreLocation(optStart3);
            }
            if (elem1_1.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_1;
            }
        }
        if (alt0_0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem1_2 = matchLiteralCst("}", false);
            if (elem1_2.isSuccess() && elem1_2.node != null) {
                children.add(elem1_2.node);
            }
            if (elem1_2.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_2;
            }
        }
        if (alt0_0.isSuccess()) {
            alt0_0 = CstParseResult.success(null, substring(seqStart1.offset(), pos), location());
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia15 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_1 = parse_Expr(trivia15);
        if (alt0_1.isSuccess() && alt0_1.node != null) {
            children.add(alt0_1.node);
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_VAR_INIT, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_MethodDecl(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(42, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var optStart1 = location();
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem1 = parse_TypeParams(trivia2);
            if (optElem1.isSuccess() && optElem1.node != null) {
                children.add(optElem1.node);
            }
            var elem0_0 = optElem1.isSuccess() ? optElem1 : CstParseResult.success(null, "", location());
            if (optElem1.isFailure()) {
                restoreLocation(optStart1);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_Type(trivia3);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_2 = parse_Identifier(trivia4);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_3 = matchLiteralCst("(", false);
            if (elem0_3.isSuccess() && elem0_3.node != null) {
                children.add(elem0_3.node);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            var optStart6 = location();
            var trivia7 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem6 = parse_Params(trivia7);
            if (optElem6.isSuccess() && optElem6.node != null) {
                children.add(optElem6.node);
            }
            var elem0_4 = optElem6.isSuccess() ? optElem6 : CstParseResult.success(null, "", location());
            if (optElem6.isFailure()) {
                restoreLocation(optStart6);
            }
            if (elem0_4.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_4;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_5 = matchLiteralCst(")", false);
            if (elem0_5.isSuccess() && elem0_5.node != null) {
                children.add(elem0_5.node);
            }
            if (elem0_5.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_5;
            }
        }
        if (result.isSuccess()) {
            var optStart9 = location();
            var trivia10 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem9 = parse_Dims(trivia10);
            if (optElem9.isSuccess() && optElem9.node != null) {
                children.add(optElem9.node);
            }
            var elem0_6 = optElem9.isSuccess() ? optElem9 : CstParseResult.success(null, "", location());
            if (optElem9.isFailure()) {
                restoreLocation(optStart9);
            }
            if (elem0_6.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_6;
            }
        }
        if (result.isSuccess()) {
            var optStart11 = location();
            var trivia12 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem11 = parse_Throws(trivia12);
            if (optElem11.isSuccess() && optElem11.node != null) {
                children.add(optElem11.node);
            }
            var elem0_7 = optElem11.isSuccess() ? optElem11 : CstParseResult.success(null, "", location());
            if (optElem11.isFailure()) {
                restoreLocation(optStart11);
            }
            if (elem0_7.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_7;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_8 = null;
            var choiceStart14 = location();
            var savedChildren14 = new ArrayList<>(children);
            children.clear();
            children.addAll(savedChildren14);
            var trivia15 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var alt14_0 = parse_Block(trivia15);
            if (alt14_0.isSuccess() && alt14_0.node != null) {
                children.add(alt14_0.node);
            }
            if (alt14_0.isSuccess()) {
                elem0_8 = alt14_0;
            } else {
                restoreLocation(choiceStart14);
            children.clear();
            children.addAll(savedChildren14);
            var alt14_1 = matchLiteralCst(";", false);
            if (alt14_1.isSuccess() && alt14_1.node != null) {
                children.add(alt14_1.node);
            }
            if (alt14_1.isSuccess()) {
                elem0_8 = alt14_1;
            } else {
                restoreLocation(choiceStart14);
            }
            }
            if (elem0_8 == null) {
                children.clear();
                children.addAll(savedChildren14);
                elem0_8 = CstParseResult.failure("one of alternatives");
            }
            if (elem0_8.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_8;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_METHOD_DECL, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Params(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(43, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_Param(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem2 = CstParseResult.success(null, "", location());
                var seqStart4 = location();
                if (zomElem2.isSuccess()) {
                    var elem4_0 = matchLiteralCst(",", false);
                    if (elem4_0.isSuccess() && elem4_0.node != null) {
                        children.add(elem4_0.node);
                    }
                    if (elem4_0.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_0;
                    }
                }
                if (zomElem2.isSuccess()) {
                    var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem4_1 = parse_Param(trivia6);
                    if (elem4_1.isSuccess() && elem4_1.node != null) {
                        children.add(elem4_1.node);
                    }
                    if (elem4_1.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_1;
                    }
                }
                if (zomElem2.isSuccess()) {
                    zomElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_PARAMS, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Param(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(44, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            CstParseResult elem0_0 = CstParseResult.success(null, "", location());
            var zomStart1 = location();
            while (true) {
                var beforeLoc1 = location();
                var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem1 = parse_Annotation(trivia2);
                if (zomElem1.isSuccess() && zomElem1.node != null) {
                    children.add(zomElem1.node);
                }
                if (zomElem1.isFailure() || location().offset() == beforeLoc1.offset()) {
                    restoreLocation(beforeLoc1);
                    break;
                }
            }
            elem0_0 = CstParseResult.success(null, substring(zomStart1.offset(), pos), location());
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart3 = location();
            while (true) {
                var beforeLoc3 = location();
                var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem3 = parse_Modifier(trivia4);
                if (zomElem3.isSuccess() && zomElem3.node != null) {
                    children.add(zomElem3.node);
                }
                if (zomElem3.isFailure() || location().offset() == beforeLoc3.offset()) {
                    restoreLocation(beforeLoc3);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart3.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            var trivia5 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_2 = parse_Type(trivia5);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            var optStart6 = location();
            if (!inTokenBoundary) skipWhitespace();
            var optElem6 = matchLiteralCst("...", false);
            if (optElem6.isSuccess() && optElem6.node != null) {
                children.add(optElem6.node);
            }
            var elem0_3 = optElem6.isSuccess() ? optElem6 : CstParseResult.success(null, "", location());
            if (optElem6.isFailure()) {
                restoreLocation(optStart6);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            var trivia8 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_4 = parse_Identifier(trivia8);
            if (elem0_4.isSuccess() && elem0_4.node != null) {
                children.add(elem0_4.node);
            }
            if (elem0_4.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_4;
            }
        }
        if (result.isSuccess()) {
            var optStart9 = location();
            var trivia10 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem9 = parse_Dims(trivia10);
            if (optElem9.isSuccess() && optElem9.node != null) {
                children.add(optElem9.node);
            }
            var elem0_5 = optElem9.isSuccess() ? optElem9 : CstParseResult.success(null, "", location());
            if (optElem9.isFailure()) {
                restoreLocation(optStart9);
            }
            if (elem0_5.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_5;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_PARAM, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Throws(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(45, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("throws", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_TypeList(trivia2);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_THROWS, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_ConstructorDecl(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(46, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var optStart1 = location();
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem1 = parse_TypeParams(trivia2);
            if (optElem1.isSuccess() && optElem1.node != null) {
                children.add(optElem1.node);
            }
            var elem0_0 = optElem1.isSuccess() ? optElem1 : CstParseResult.success(null, "", location());
            if (optElem1.isFailure()) {
                restoreLocation(optStart1);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_Identifier(trivia3);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_2 = matchLiteralCst("(", false);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            var optStart5 = location();
            var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem5 = parse_Params(trivia6);
            if (optElem5.isSuccess() && optElem5.node != null) {
                children.add(optElem5.node);
            }
            var elem0_3 = optElem5.isSuccess() ? optElem5 : CstParseResult.success(null, "", location());
            if (optElem5.isFailure()) {
                restoreLocation(optStart5);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_4 = matchLiteralCst(")", false);
            if (elem0_4.isSuccess() && elem0_4.node != null) {
                children.add(elem0_4.node);
            }
            if (elem0_4.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_4;
            }
        }
        if (result.isSuccess()) {
            var optStart8 = location();
            var trivia9 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem8 = parse_Throws(trivia9);
            if (optElem8.isSuccess() && optElem8.node != null) {
                children.add(optElem8.node);
            }
            var elem0_5 = optElem8.isSuccess() ? optElem8 : CstParseResult.success(null, "", location());
            if (optElem8.isFailure()) {
                restoreLocation(optStart8);
            }
            if (elem0_5.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_5;
            }
        }
        if (result.isSuccess()) {
            var trivia10 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_6 = parse_Block(trivia10);
            if (elem0_6.isSuccess() && elem0_6.node != null) {
                children.add(elem0_6.node);
            }
            if (elem0_6.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_6;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_CONSTRUCTOR_DECL, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Block(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(47, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("{", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem2 = parse_BlockStmt(trivia3);
                if (zomElem2.isSuccess() && zomElem2.node != null) {
                    children.add(zomElem2.node);
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_2 = matchLiteralCst("}", false);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_BLOCK, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_BlockStmt(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(48, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_0 = parse_LocalVar(trivia1);
        if (alt0_0.isSuccess() && alt0_0.node != null) {
            children.add(alt0_0.node);
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_1 = parse_LocalTypeDecl(trivia2);
        if (alt0_1.isSuccess() && alt0_1.node != null) {
            children.add(alt0_1.node);
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_2 = parse_Stmt(trivia3);
        if (alt0_2.isSuccess() && alt0_2.node != null) {
            children.add(alt0_2.node);
        }
        if (alt0_2.isSuccess()) {
            result = alt0_2;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_BLOCK_STMT, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_LocalTypeDecl(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(49, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            CstParseResult elem0_0 = CstParseResult.success(null, "", location());
            var zomStart1 = location();
            while (true) {
                var beforeLoc1 = location();
                var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem1 = parse_Annotation(trivia2);
                if (zomElem1.isSuccess() && zomElem1.node != null) {
                    children.add(zomElem1.node);
                }
                if (zomElem1.isFailure() || location().offset() == beforeLoc1.offset()) {
                    restoreLocation(beforeLoc1);
                    break;
                }
            }
            elem0_0 = CstParseResult.success(null, substring(zomStart1.offset(), pos), location());
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart3 = location();
            while (true) {
                var beforeLoc3 = location();
                var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem3 = parse_Modifier(trivia4);
                if (zomElem3.isSuccess() && zomElem3.node != null) {
                    children.add(zomElem3.node);
                }
                if (zomElem3.isFailure() || location().offset() == beforeLoc3.offset()) {
                    restoreLocation(beforeLoc3);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart3.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            var trivia5 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_2 = parse_TypeKind(trivia5);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_LOCAL_TYPE_DECL, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_LocalVar(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(50, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            CstParseResult elem0_0 = CstParseResult.success(null, "", location());
            var zomStart1 = location();
            while (true) {
                var beforeLoc1 = location();
                var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem1 = parse_Modifier(trivia2);
                if (zomElem1.isSuccess() && zomElem1.node != null) {
                    children.add(zomElem1.node);
                }
                if (zomElem1.isFailure() || location().offset() == beforeLoc1.offset()) {
                    restoreLocation(beforeLoc1);
                    break;
                }
            }
            elem0_0 = CstParseResult.success(null, substring(zomStart1.offset(), pos), location());
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_LocalVarType(trivia3);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_2 = parse_VarDecls(trivia4);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_3 = matchLiteralCst(";", false);
            if (elem0_3.isSuccess() && elem0_3.node != null) {
                children.add(elem0_3.node);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_LOCAL_VAR, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_LocalVarType(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(51, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_0 = matchLiteralCst("var", false);
        if (alt0_0.isSuccess() && alt0_0.node != null) {
            children.add(alt0_0.node);
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_1 = parse_Type(trivia2);
        if (alt0_1.isSuccess() && alt0_1.node != null) {
            children.add(alt0_1.node);
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_LOCAL_VAR_TYPE, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Stmt(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(52, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_0 = parse_Block(trivia1);
        if (alt0_0.isSuccess() && alt0_0.node != null) {
            children.add(alt0_0.node);
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_1 = CstParseResult.success(null, "", location());
        var seqStart2 = location();
        if (alt0_1.isSuccess()) {
            var elem2_0 = matchLiteralCst("if", false);
            if (elem2_0.isSuccess() && elem2_0.node != null) {
                children.add(elem2_0.node);
            }
            if (elem2_0.isFailure()) {
                restoreLocation(seqStart2);
                alt0_1 = elem2_0;
            }
        }
        if (alt0_1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem2_1 = matchLiteralCst("(", false);
            if (elem2_1.isSuccess() && elem2_1.node != null) {
                children.add(elem2_1.node);
            }
            if (elem2_1.isFailure()) {
                restoreLocation(seqStart2);
                alt0_1 = elem2_1;
            }
        }
        if (alt0_1.isSuccess()) {
            var trivia5 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem2_2 = parse_Expr(trivia5);
            if (elem2_2.isSuccess() && elem2_2.node != null) {
                children.add(elem2_2.node);
            }
            if (elem2_2.isFailure()) {
                restoreLocation(seqStart2);
                alt0_1 = elem2_2;
            }
        }
        if (alt0_1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem2_3 = matchLiteralCst(")", false);
            if (elem2_3.isSuccess() && elem2_3.node != null) {
                children.add(elem2_3.node);
            }
            if (elem2_3.isFailure()) {
                restoreLocation(seqStart2);
                alt0_1 = elem2_3;
            }
        }
        if (alt0_1.isSuccess()) {
            var trivia7 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem2_4 = parse_Stmt(trivia7);
            if (elem2_4.isSuccess() && elem2_4.node != null) {
                children.add(elem2_4.node);
            }
            if (elem2_4.isFailure()) {
                restoreLocation(seqStart2);
                alt0_1 = elem2_4;
            }
        }
        if (alt0_1.isSuccess()) {
            var optStart8 = location();
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult optElem8 = CstParseResult.success(null, "", location());
            var seqStart10 = location();
            if (optElem8.isSuccess()) {
                var elem10_0 = matchLiteralCst("else", false);
                if (elem10_0.isSuccess() && elem10_0.node != null) {
                    children.add(elem10_0.node);
                }
                if (elem10_0.isFailure()) {
                    restoreLocation(seqStart10);
                    optElem8 = elem10_0;
                }
            }
            if (optElem8.isSuccess()) {
                var trivia12 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var elem10_1 = parse_Stmt(trivia12);
                if (elem10_1.isSuccess() && elem10_1.node != null) {
                    children.add(elem10_1.node);
                }
                if (elem10_1.isFailure()) {
                    restoreLocation(seqStart10);
                    optElem8 = elem10_1;
                }
            }
            if (optElem8.isSuccess()) {
                optElem8 = CstParseResult.success(null, substring(seqStart10.offset(), pos), location());
            }
            var elem2_5 = optElem8.isSuccess() ? optElem8 : CstParseResult.success(null, "", location());
            if (optElem8.isFailure()) {
                restoreLocation(optStart8);
            }
            if (elem2_5.isFailure()) {
                restoreLocation(seqStart2);
                alt0_1 = elem2_5;
            }
        }
        if (alt0_1.isSuccess()) {
            alt0_1 = CstParseResult.success(null, substring(seqStart2.offset(), pos), location());
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_2 = CstParseResult.success(null, "", location());
        var seqStart13 = location();
        if (alt0_2.isSuccess()) {
            var elem13_0 = matchLiteralCst("while", false);
            if (elem13_0.isSuccess() && elem13_0.node != null) {
                children.add(elem13_0.node);
            }
            if (elem13_0.isFailure()) {
                restoreLocation(seqStart13);
                alt0_2 = elem13_0;
            }
        }
        if (alt0_2.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem13_1 = matchLiteralCst("(", false);
            if (elem13_1.isSuccess() && elem13_1.node != null) {
                children.add(elem13_1.node);
            }
            if (elem13_1.isFailure()) {
                restoreLocation(seqStart13);
                alt0_2 = elem13_1;
            }
        }
        if (alt0_2.isSuccess()) {
            var trivia16 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem13_2 = parse_Expr(trivia16);
            if (elem13_2.isSuccess() && elem13_2.node != null) {
                children.add(elem13_2.node);
            }
            if (elem13_2.isFailure()) {
                restoreLocation(seqStart13);
                alt0_2 = elem13_2;
            }
        }
        if (alt0_2.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem13_3 = matchLiteralCst(")", false);
            if (elem13_3.isSuccess() && elem13_3.node != null) {
                children.add(elem13_3.node);
            }
            if (elem13_3.isFailure()) {
                restoreLocation(seqStart13);
                alt0_2 = elem13_3;
            }
        }
        if (alt0_2.isSuccess()) {
            var trivia18 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem13_4 = parse_Stmt(trivia18);
            if (elem13_4.isSuccess() && elem13_4.node != null) {
                children.add(elem13_4.node);
            }
            if (elem13_4.isFailure()) {
                restoreLocation(seqStart13);
                alt0_2 = elem13_4;
            }
        }
        if (alt0_2.isSuccess()) {
            alt0_2 = CstParseResult.success(null, substring(seqStart13.offset(), pos), location());
        }
        if (alt0_2.isSuccess()) {
            result = alt0_2;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_3 = CstParseResult.success(null, "", location());
        var seqStart19 = location();
        if (alt0_3.isSuccess()) {
            var elem19_0 = matchLiteralCst("for", false);
            if (elem19_0.isSuccess() && elem19_0.node != null) {
                children.add(elem19_0.node);
            }
            if (elem19_0.isFailure()) {
                restoreLocation(seqStart19);
                alt0_3 = elem19_0;
            }
        }
        if (alt0_3.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem19_1 = matchLiteralCst("(", false);
            if (elem19_1.isSuccess() && elem19_1.node != null) {
                children.add(elem19_1.node);
            }
            if (elem19_1.isFailure()) {
                restoreLocation(seqStart19);
                alt0_3 = elem19_1;
            }
        }
        if (alt0_3.isSuccess()) {
            var trivia22 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem19_2 = parse_ForCtrl(trivia22);
            if (elem19_2.isSuccess() && elem19_2.node != null) {
                children.add(elem19_2.node);
            }
            if (elem19_2.isFailure()) {
                restoreLocation(seqStart19);
                alt0_3 = elem19_2;
            }
        }
        if (alt0_3.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem19_3 = matchLiteralCst(")", false);
            if (elem19_3.isSuccess() && elem19_3.node != null) {
                children.add(elem19_3.node);
            }
            if (elem19_3.isFailure()) {
                restoreLocation(seqStart19);
                alt0_3 = elem19_3;
            }
        }
        if (alt0_3.isSuccess()) {
            var trivia24 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem19_4 = parse_Stmt(trivia24);
            if (elem19_4.isSuccess() && elem19_4.node != null) {
                children.add(elem19_4.node);
            }
            if (elem19_4.isFailure()) {
                restoreLocation(seqStart19);
                alt0_3 = elem19_4;
            }
        }
        if (alt0_3.isSuccess()) {
            alt0_3 = CstParseResult.success(null, substring(seqStart19.offset(), pos), location());
        }
        if (alt0_3.isSuccess()) {
            result = alt0_3;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_4 = CstParseResult.success(null, "", location());
        var seqStart25 = location();
        if (alt0_4.isSuccess()) {
            var elem25_0 = matchLiteralCst("do", false);
            if (elem25_0.isSuccess() && elem25_0.node != null) {
                children.add(elem25_0.node);
            }
            if (elem25_0.isFailure()) {
                restoreLocation(seqStart25);
                alt0_4 = elem25_0;
            }
        }
        if (alt0_4.isSuccess()) {
            var trivia27 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem25_1 = parse_Stmt(trivia27);
            if (elem25_1.isSuccess() && elem25_1.node != null) {
                children.add(elem25_1.node);
            }
            if (elem25_1.isFailure()) {
                restoreLocation(seqStart25);
                alt0_4 = elem25_1;
            }
        }
        if (alt0_4.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem25_2 = matchLiteralCst("while", false);
            if (elem25_2.isSuccess() && elem25_2.node != null) {
                children.add(elem25_2.node);
            }
            if (elem25_2.isFailure()) {
                restoreLocation(seqStart25);
                alt0_4 = elem25_2;
            }
        }
        if (alt0_4.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem25_3 = matchLiteralCst("(", false);
            if (elem25_3.isSuccess() && elem25_3.node != null) {
                children.add(elem25_3.node);
            }
            if (elem25_3.isFailure()) {
                restoreLocation(seqStart25);
                alt0_4 = elem25_3;
            }
        }
        if (alt0_4.isSuccess()) {
            var trivia30 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem25_4 = parse_Expr(trivia30);
            if (elem25_4.isSuccess() && elem25_4.node != null) {
                children.add(elem25_4.node);
            }
            if (elem25_4.isFailure()) {
                restoreLocation(seqStart25);
                alt0_4 = elem25_4;
            }
        }
        if (alt0_4.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem25_5 = matchLiteralCst(")", false);
            if (elem25_5.isSuccess() && elem25_5.node != null) {
                children.add(elem25_5.node);
            }
            if (elem25_5.isFailure()) {
                restoreLocation(seqStart25);
                alt0_4 = elem25_5;
            }
        }
        if (alt0_4.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem25_6 = matchLiteralCst(";", false);
            if (elem25_6.isSuccess() && elem25_6.node != null) {
                children.add(elem25_6.node);
            }
            if (elem25_6.isFailure()) {
                restoreLocation(seqStart25);
                alt0_4 = elem25_6;
            }
        }
        if (alt0_4.isSuccess()) {
            alt0_4 = CstParseResult.success(null, substring(seqStart25.offset(), pos), location());
        }
        if (alt0_4.isSuccess()) {
            result = alt0_4;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_5 = CstParseResult.success(null, "", location());
        var seqStart33 = location();
        if (alt0_5.isSuccess()) {
            var elem33_0 = matchLiteralCst("try", false);
            if (elem33_0.isSuccess() && elem33_0.node != null) {
                children.add(elem33_0.node);
            }
            if (elem33_0.isFailure()) {
                restoreLocation(seqStart33);
                alt0_5 = elem33_0;
            }
        }
        if (alt0_5.isSuccess()) {
            var optStart35 = location();
            var trivia36 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem35 = parse_ResourceSpec(trivia36);
            if (optElem35.isSuccess() && optElem35.node != null) {
                children.add(optElem35.node);
            }
            var elem33_1 = optElem35.isSuccess() ? optElem35 : CstParseResult.success(null, "", location());
            if (optElem35.isFailure()) {
                restoreLocation(optStart35);
            }
            if (elem33_1.isFailure()) {
                restoreLocation(seqStart33);
                alt0_5 = elem33_1;
            }
        }
        if (alt0_5.isSuccess()) {
            var trivia37 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem33_2 = parse_Block(trivia37);
            if (elem33_2.isSuccess() && elem33_2.node != null) {
                children.add(elem33_2.node);
            }
            if (elem33_2.isFailure()) {
                restoreLocation(seqStart33);
                alt0_5 = elem33_2;
            }
        }
        if (alt0_5.isSuccess()) {
            CstParseResult elem33_3 = CstParseResult.success(null, "", location());
            var zomStart38 = location();
            while (true) {
                var beforeLoc38 = location();
                var trivia39 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem38 = parse_Catch(trivia39);
                if (zomElem38.isSuccess() && zomElem38.node != null) {
                    children.add(zomElem38.node);
                }
                if (zomElem38.isFailure() || location().offset() == beforeLoc38.offset()) {
                    restoreLocation(beforeLoc38);
                    break;
                }
            }
            elem33_3 = CstParseResult.success(null, substring(zomStart38.offset(), pos), location());
            if (elem33_3.isFailure()) {
                restoreLocation(seqStart33);
                alt0_5 = elem33_3;
            }
        }
        if (alt0_5.isSuccess()) {
            var optStart40 = location();
            var trivia41 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem40 = parse_Finally(trivia41);
            if (optElem40.isSuccess() && optElem40.node != null) {
                children.add(optElem40.node);
            }
            var elem33_4 = optElem40.isSuccess() ? optElem40 : CstParseResult.success(null, "", location());
            if (optElem40.isFailure()) {
                restoreLocation(optStart40);
            }
            if (elem33_4.isFailure()) {
                restoreLocation(seqStart33);
                alt0_5 = elem33_4;
            }
        }
        if (alt0_5.isSuccess()) {
            alt0_5 = CstParseResult.success(null, substring(seqStart33.offset(), pos), location());
        }
        if (alt0_5.isSuccess()) {
            result = alt0_5;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_6 = CstParseResult.success(null, "", location());
        var seqStart42 = location();
        if (alt0_6.isSuccess()) {
            var elem42_0 = matchLiteralCst("switch", false);
            if (elem42_0.isSuccess() && elem42_0.node != null) {
                children.add(elem42_0.node);
            }
            if (elem42_0.isFailure()) {
                restoreLocation(seqStart42);
                alt0_6 = elem42_0;
            }
        }
        if (alt0_6.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem42_1 = matchLiteralCst("(", false);
            if (elem42_1.isSuccess() && elem42_1.node != null) {
                children.add(elem42_1.node);
            }
            if (elem42_1.isFailure()) {
                restoreLocation(seqStart42);
                alt0_6 = elem42_1;
            }
        }
        if (alt0_6.isSuccess()) {
            var trivia45 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem42_2 = parse_Expr(trivia45);
            if (elem42_2.isSuccess() && elem42_2.node != null) {
                children.add(elem42_2.node);
            }
            if (elem42_2.isFailure()) {
                restoreLocation(seqStart42);
                alt0_6 = elem42_2;
            }
        }
        if (alt0_6.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem42_3 = matchLiteralCst(")", false);
            if (elem42_3.isSuccess() && elem42_3.node != null) {
                children.add(elem42_3.node);
            }
            if (elem42_3.isFailure()) {
                restoreLocation(seqStart42);
                alt0_6 = elem42_3;
            }
        }
        if (alt0_6.isSuccess()) {
            var trivia47 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem42_4 = parse_SwitchBlock(trivia47);
            if (elem42_4.isSuccess() && elem42_4.node != null) {
                children.add(elem42_4.node);
            }
            if (elem42_4.isFailure()) {
                restoreLocation(seqStart42);
                alt0_6 = elem42_4;
            }
        }
        if (alt0_6.isSuccess()) {
            alt0_6 = CstParseResult.success(null, substring(seqStart42.offset(), pos), location());
        }
        if (alt0_6.isSuccess()) {
            result = alt0_6;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_7 = CstParseResult.success(null, "", location());
        var seqStart48 = location();
        if (alt0_7.isSuccess()) {
            var trivia49 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem48_0 = parse_ReturnKW(trivia49);
            if (elem48_0.isSuccess() && elem48_0.node != null) {
                children.add(elem48_0.node);
            }
            if (elem48_0.isFailure()) {
                restoreLocation(seqStart48);
                alt0_7 = elem48_0;
            }
        }
        if (alt0_7.isSuccess()) {
            var optStart50 = location();
            var trivia51 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem50 = parse_Expr(trivia51);
            if (optElem50.isSuccess() && optElem50.node != null) {
                children.add(optElem50.node);
            }
            var elem48_1 = optElem50.isSuccess() ? optElem50 : CstParseResult.success(null, "", location());
            if (optElem50.isFailure()) {
                restoreLocation(optStart50);
            }
            if (elem48_1.isFailure()) {
                restoreLocation(seqStart48);
                alt0_7 = elem48_1;
            }
        }
        if (alt0_7.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem48_2 = matchLiteralCst(";", false);
            if (elem48_2.isSuccess() && elem48_2.node != null) {
                children.add(elem48_2.node);
            }
            if (elem48_2.isFailure()) {
                restoreLocation(seqStart48);
                alt0_7 = elem48_2;
            }
        }
        if (alt0_7.isSuccess()) {
            alt0_7 = CstParseResult.success(null, substring(seqStart48.offset(), pos), location());
        }
        if (alt0_7.isSuccess()) {
            result = alt0_7;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_8 = CstParseResult.success(null, "", location());
        var seqStart53 = location();
        if (alt0_8.isSuccess()) {
            var trivia54 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem53_0 = parse_ThrowKW(trivia54);
            if (elem53_0.isSuccess() && elem53_0.node != null) {
                children.add(elem53_0.node);
            }
            if (elem53_0.isFailure()) {
                restoreLocation(seqStart53);
                alt0_8 = elem53_0;
            }
        }
        if (alt0_8.isSuccess()) {
            var trivia55 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem53_1 = parse_Expr(trivia55);
            if (elem53_1.isSuccess() && elem53_1.node != null) {
                children.add(elem53_1.node);
            }
            if (elem53_1.isFailure()) {
                restoreLocation(seqStart53);
                alt0_8 = elem53_1;
            }
        }
        if (alt0_8.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem53_2 = matchLiteralCst(";", false);
            if (elem53_2.isSuccess() && elem53_2.node != null) {
                children.add(elem53_2.node);
            }
            if (elem53_2.isFailure()) {
                restoreLocation(seqStart53);
                alt0_8 = elem53_2;
            }
        }
        if (alt0_8.isSuccess()) {
            alt0_8 = CstParseResult.success(null, substring(seqStart53.offset(), pos), location());
        }
        if (alt0_8.isSuccess()) {
            result = alt0_8;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_9 = CstParseResult.success(null, "", location());
        var seqStart57 = location();
        if (alt0_9.isSuccess()) {
            var trivia58 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem57_0 = parse_BreakKW(trivia58);
            if (elem57_0.isSuccess() && elem57_0.node != null) {
                children.add(elem57_0.node);
            }
            if (elem57_0.isFailure()) {
                restoreLocation(seqStart57);
                alt0_9 = elem57_0;
            }
        }
        if (alt0_9.isSuccess()) {
            var optStart59 = location();
            var trivia60 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem59 = parse_Identifier(trivia60);
            if (optElem59.isSuccess() && optElem59.node != null) {
                children.add(optElem59.node);
            }
            var elem57_1 = optElem59.isSuccess() ? optElem59 : CstParseResult.success(null, "", location());
            if (optElem59.isFailure()) {
                restoreLocation(optStart59);
            }
            if (elem57_1.isFailure()) {
                restoreLocation(seqStart57);
                alt0_9 = elem57_1;
            }
        }
        if (alt0_9.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem57_2 = matchLiteralCst(";", false);
            if (elem57_2.isSuccess() && elem57_2.node != null) {
                children.add(elem57_2.node);
            }
            if (elem57_2.isFailure()) {
                restoreLocation(seqStart57);
                alt0_9 = elem57_2;
            }
        }
        if (alt0_9.isSuccess()) {
            alt0_9 = CstParseResult.success(null, substring(seqStart57.offset(), pos), location());
        }
        if (alt0_9.isSuccess()) {
            result = alt0_9;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_10 = CstParseResult.success(null, "", location());
        var seqStart62 = location();
        if (alt0_10.isSuccess()) {
            var trivia63 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem62_0 = parse_ContinueKW(trivia63);
            if (elem62_0.isSuccess() && elem62_0.node != null) {
                children.add(elem62_0.node);
            }
            if (elem62_0.isFailure()) {
                restoreLocation(seqStart62);
                alt0_10 = elem62_0;
            }
        }
        if (alt0_10.isSuccess()) {
            var optStart64 = location();
            var trivia65 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem64 = parse_Identifier(trivia65);
            if (optElem64.isSuccess() && optElem64.node != null) {
                children.add(optElem64.node);
            }
            var elem62_1 = optElem64.isSuccess() ? optElem64 : CstParseResult.success(null, "", location());
            if (optElem64.isFailure()) {
                restoreLocation(optStart64);
            }
            if (elem62_1.isFailure()) {
                restoreLocation(seqStart62);
                alt0_10 = elem62_1;
            }
        }
        if (alt0_10.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem62_2 = matchLiteralCst(";", false);
            if (elem62_2.isSuccess() && elem62_2.node != null) {
                children.add(elem62_2.node);
            }
            if (elem62_2.isFailure()) {
                restoreLocation(seqStart62);
                alt0_10 = elem62_2;
            }
        }
        if (alt0_10.isSuccess()) {
            alt0_10 = CstParseResult.success(null, substring(seqStart62.offset(), pos), location());
        }
        if (alt0_10.isSuccess()) {
            result = alt0_10;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_11 = CstParseResult.success(null, "", location());
        var seqStart67 = location();
        if (alt0_11.isSuccess()) {
            var trivia68 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem67_0 = parse_AssertKW(trivia68);
            if (elem67_0.isSuccess() && elem67_0.node != null) {
                children.add(elem67_0.node);
            }
            if (elem67_0.isFailure()) {
                restoreLocation(seqStart67);
                alt0_11 = elem67_0;
            }
        }
        if (alt0_11.isSuccess()) {
            var trivia69 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem67_1 = parse_Expr(trivia69);
            if (elem67_1.isSuccess() && elem67_1.node != null) {
                children.add(elem67_1.node);
            }
            if (elem67_1.isFailure()) {
                restoreLocation(seqStart67);
                alt0_11 = elem67_1;
            }
        }
        if (alt0_11.isSuccess()) {
            var optStart70 = location();
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult optElem70 = CstParseResult.success(null, "", location());
            var seqStart72 = location();
            if (optElem70.isSuccess()) {
                var elem72_0 = matchLiteralCst(":", false);
                if (elem72_0.isSuccess() && elem72_0.node != null) {
                    children.add(elem72_0.node);
                }
                if (elem72_0.isFailure()) {
                    restoreLocation(seqStart72);
                    optElem70 = elem72_0;
                }
            }
            if (optElem70.isSuccess()) {
                var trivia74 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var elem72_1 = parse_Expr(trivia74);
                if (elem72_1.isSuccess() && elem72_1.node != null) {
                    children.add(elem72_1.node);
                }
                if (elem72_1.isFailure()) {
                    restoreLocation(seqStart72);
                    optElem70 = elem72_1;
                }
            }
            if (optElem70.isSuccess()) {
                optElem70 = CstParseResult.success(null, substring(seqStart72.offset(), pos), location());
            }
            var elem67_2 = optElem70.isSuccess() ? optElem70 : CstParseResult.success(null, "", location());
            if (optElem70.isFailure()) {
                restoreLocation(optStart70);
            }
            if (elem67_2.isFailure()) {
                restoreLocation(seqStart67);
                alt0_11 = elem67_2;
            }
        }
        if (alt0_11.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem67_3 = matchLiteralCst(";", false);
            if (elem67_3.isSuccess() && elem67_3.node != null) {
                children.add(elem67_3.node);
            }
            if (elem67_3.isFailure()) {
                restoreLocation(seqStart67);
                alt0_11 = elem67_3;
            }
        }
        if (alt0_11.isSuccess()) {
            alt0_11 = CstParseResult.success(null, substring(seqStart67.offset(), pos), location());
        }
        if (alt0_11.isSuccess()) {
            result = alt0_11;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_12 = CstParseResult.success(null, "", location());
        var seqStart76 = location();
        if (alt0_12.isSuccess()) {
            var elem76_0 = matchLiteralCst("synchronized", false);
            if (elem76_0.isSuccess() && elem76_0.node != null) {
                children.add(elem76_0.node);
            }
            if (elem76_0.isFailure()) {
                restoreLocation(seqStart76);
                alt0_12 = elem76_0;
            }
        }
        if (alt0_12.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem76_1 = matchLiteralCst("(", false);
            if (elem76_1.isSuccess() && elem76_1.node != null) {
                children.add(elem76_1.node);
            }
            if (elem76_1.isFailure()) {
                restoreLocation(seqStart76);
                alt0_12 = elem76_1;
            }
        }
        if (alt0_12.isSuccess()) {
            var trivia79 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem76_2 = parse_Expr(trivia79);
            if (elem76_2.isSuccess() && elem76_2.node != null) {
                children.add(elem76_2.node);
            }
            if (elem76_2.isFailure()) {
                restoreLocation(seqStart76);
                alt0_12 = elem76_2;
            }
        }
        if (alt0_12.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem76_3 = matchLiteralCst(")", false);
            if (elem76_3.isSuccess() && elem76_3.node != null) {
                children.add(elem76_3.node);
            }
            if (elem76_3.isFailure()) {
                restoreLocation(seqStart76);
                alt0_12 = elem76_3;
            }
        }
        if (alt0_12.isSuccess()) {
            var trivia81 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem76_4 = parse_Block(trivia81);
            if (elem76_4.isSuccess() && elem76_4.node != null) {
                children.add(elem76_4.node);
            }
            if (elem76_4.isFailure()) {
                restoreLocation(seqStart76);
                alt0_12 = elem76_4;
            }
        }
        if (alt0_12.isSuccess()) {
            alt0_12 = CstParseResult.success(null, substring(seqStart76.offset(), pos), location());
        }
        if (alt0_12.isSuccess()) {
            result = alt0_12;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_13 = CstParseResult.success(null, "", location());
        var seqStart82 = location();
        if (alt0_13.isSuccess()) {
            var trivia83 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem82_0 = parse_YieldKW(trivia83);
            if (elem82_0.isSuccess() && elem82_0.node != null) {
                children.add(elem82_0.node);
            }
            if (elem82_0.isFailure()) {
                restoreLocation(seqStart82);
                alt0_13 = elem82_0;
            }
        }
        if (alt0_13.isSuccess()) {
            var trivia84 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem82_1 = parse_Expr(trivia84);
            if (elem82_1.isSuccess() && elem82_1.node != null) {
                children.add(elem82_1.node);
            }
            if (elem82_1.isFailure()) {
                restoreLocation(seqStart82);
                alt0_13 = elem82_1;
            }
        }
        if (alt0_13.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem82_2 = matchLiteralCst(";", false);
            if (elem82_2.isSuccess() && elem82_2.node != null) {
                children.add(elem82_2.node);
            }
            if (elem82_2.isFailure()) {
                restoreLocation(seqStart82);
                alt0_13 = elem82_2;
            }
        }
        if (alt0_13.isSuccess()) {
            alt0_13 = CstParseResult.success(null, substring(seqStart82.offset(), pos), location());
        }
        if (alt0_13.isSuccess()) {
            result = alt0_13;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_14 = CstParseResult.success(null, "", location());
        var seqStart86 = location();
        if (alt0_14.isSuccess()) {
            var trivia87 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem86_0 = parse_Identifier(trivia87);
            if (elem86_0.isSuccess() && elem86_0.node != null) {
                children.add(elem86_0.node);
            }
            if (elem86_0.isFailure()) {
                restoreLocation(seqStart86);
                alt0_14 = elem86_0;
            }
        }
        if (alt0_14.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem86_1 = matchLiteralCst(":", false);
            if (elem86_1.isSuccess() && elem86_1.node != null) {
                children.add(elem86_1.node);
            }
            if (elem86_1.isFailure()) {
                restoreLocation(seqStart86);
                alt0_14 = elem86_1;
            }
        }
        if (alt0_14.isSuccess()) {
            var trivia89 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem86_2 = parse_Stmt(trivia89);
            if (elem86_2.isSuccess() && elem86_2.node != null) {
                children.add(elem86_2.node);
            }
            if (elem86_2.isFailure()) {
                restoreLocation(seqStart86);
                alt0_14 = elem86_2;
            }
        }
        if (alt0_14.isSuccess()) {
            alt0_14 = CstParseResult.success(null, substring(seqStart86.offset(), pos), location());
        }
        if (alt0_14.isSuccess()) {
            result = alt0_14;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_15 = CstParseResult.success(null, "", location());
        var seqStart90 = location();
        if (alt0_15.isSuccess()) {
            var trivia91 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem90_0 = parse_Expr(trivia91);
            if (elem90_0.isSuccess() && elem90_0.node != null) {
                children.add(elem90_0.node);
            }
            if (elem90_0.isFailure()) {
                restoreLocation(seqStart90);
                alt0_15 = elem90_0;
            }
        }
        if (alt0_15.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem90_1 = matchLiteralCst(";", false);
            if (elem90_1.isSuccess() && elem90_1.node != null) {
                children.add(elem90_1.node);
            }
            if (elem90_1.isFailure()) {
                restoreLocation(seqStart90);
                alt0_15 = elem90_1;
            }
        }
        if (alt0_15.isSuccess()) {
            alt0_15 = CstParseResult.success(null, substring(seqStart90.offset(), pos), location());
        }
        if (alt0_15.isSuccess()) {
            result = alt0_15;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_16 = matchLiteralCst(";", false);
        if (alt0_16.isSuccess() && alt0_16.node != null) {
            children.add(alt0_16.node);
        }
        if (alt0_16.isSuccess()) {
            result = alt0_16;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        }
        }
        }
        }
        }
        }
        }
        }
        }
        }
        }
        }
        }
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_STMT, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_ReturnKW(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(53, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        var tbStart0 = location();
        inTokenBoundary = true;
        var savedChildrenTb0 = new ArrayList<>(children);
        CstParseResult tbElem0 = CstParseResult.success(null, "", location());
        var seqStart1 = location();
        if (tbElem0.isSuccess()) {
            var elem1_0 = matchLiteralCst("return", false);
            if (elem1_0.isFailure()) {
                restoreLocation(seqStart1);
                tbElem0 = elem1_0;
            }
        }
        if (tbElem0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var notStart3 = location();
            var notElem3 = matchCharClassCst("a-zA-Z0-9_$", false, false);
            restoreLocation(notStart3);
            var elem1_1 = notElem3.isSuccess() ? CstParseResult.failure("not match") : CstParseResult.success(null, "", location());
            if (elem1_1.isFailure()) {
                restoreLocation(seqStart1);
                tbElem0 = elem1_1;
            }
        }
        if (tbElem0.isSuccess()) {
            tbElem0 = CstParseResult.success(null, substring(seqStart1.offset(), pos), location());
        }
        inTokenBoundary = false;
        children.clear();
        children.addAll(savedChildrenTb0);
        CstParseResult result;
        if (tbElem0.isSuccess()) {
            var tbText0 = substring(tbStart0.offset(), pos);
            var tbSpan0 = SourceSpan.of(tbStart0, location());
            var tbNode0 = new CstNode.Token(tbSpan0, RULE_PEG_TOKEN, tbText0, List.of(), List.of());
            children.add(tbNode0);
            result = CstParseResult.success(tbNode0, tbText0, location());
        } else {
            result = tbElem0;
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.Token(span, RULE_RETURN_K_W, result.text, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_ThrowKW(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(54, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        var tbStart0 = location();
        inTokenBoundary = true;
        var savedChildrenTb0 = new ArrayList<>(children);
        CstParseResult tbElem0 = CstParseResult.success(null, "", location());
        var seqStart1 = location();
        if (tbElem0.isSuccess()) {
            var elem1_0 = matchLiteralCst("throw", false);
            if (elem1_0.isFailure()) {
                restoreLocation(seqStart1);
                tbElem0 = elem1_0;
            }
        }
        if (tbElem0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var notStart3 = location();
            var notElem3 = matchCharClassCst("a-zA-Z0-9_$", false, false);
            restoreLocation(notStart3);
            var elem1_1 = notElem3.isSuccess() ? CstParseResult.failure("not match") : CstParseResult.success(null, "", location());
            if (elem1_1.isFailure()) {
                restoreLocation(seqStart1);
                tbElem0 = elem1_1;
            }
        }
        if (tbElem0.isSuccess()) {
            tbElem0 = CstParseResult.success(null, substring(seqStart1.offset(), pos), location());
        }
        inTokenBoundary = false;
        children.clear();
        children.addAll(savedChildrenTb0);
        CstParseResult result;
        if (tbElem0.isSuccess()) {
            var tbText0 = substring(tbStart0.offset(), pos);
            var tbSpan0 = SourceSpan.of(tbStart0, location());
            var tbNode0 = new CstNode.Token(tbSpan0, RULE_PEG_TOKEN, tbText0, List.of(), List.of());
            children.add(tbNode0);
            result = CstParseResult.success(tbNode0, tbText0, location());
        } else {
            result = tbElem0;
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.Token(span, RULE_THROW_K_W, result.text, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_BreakKW(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(55, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        var tbStart0 = location();
        inTokenBoundary = true;
        var savedChildrenTb0 = new ArrayList<>(children);
        CstParseResult tbElem0 = CstParseResult.success(null, "", location());
        var seqStart1 = location();
        if (tbElem0.isSuccess()) {
            var elem1_0 = matchLiteralCst("break", false);
            if (elem1_0.isFailure()) {
                restoreLocation(seqStart1);
                tbElem0 = elem1_0;
            }
        }
        if (tbElem0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var notStart3 = location();
            var notElem3 = matchCharClassCst("a-zA-Z0-9_$", false, false);
            restoreLocation(notStart3);
            var elem1_1 = notElem3.isSuccess() ? CstParseResult.failure("not match") : CstParseResult.success(null, "", location());
            if (elem1_1.isFailure()) {
                restoreLocation(seqStart1);
                tbElem0 = elem1_1;
            }
        }
        if (tbElem0.isSuccess()) {
            tbElem0 = CstParseResult.success(null, substring(seqStart1.offset(), pos), location());
        }
        inTokenBoundary = false;
        children.clear();
        children.addAll(savedChildrenTb0);
        CstParseResult result;
        if (tbElem0.isSuccess()) {
            var tbText0 = substring(tbStart0.offset(), pos);
            var tbSpan0 = SourceSpan.of(tbStart0, location());
            var tbNode0 = new CstNode.Token(tbSpan0, RULE_PEG_TOKEN, tbText0, List.of(), List.of());
            children.add(tbNode0);
            result = CstParseResult.success(tbNode0, tbText0, location());
        } else {
            result = tbElem0;
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.Token(span, RULE_BREAK_K_W, result.text, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_ContinueKW(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(56, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        var tbStart0 = location();
        inTokenBoundary = true;
        var savedChildrenTb0 = new ArrayList<>(children);
        CstParseResult tbElem0 = CstParseResult.success(null, "", location());
        var seqStart1 = location();
        if (tbElem0.isSuccess()) {
            var elem1_0 = matchLiteralCst("continue", false);
            if (elem1_0.isFailure()) {
                restoreLocation(seqStart1);
                tbElem0 = elem1_0;
            }
        }
        if (tbElem0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var notStart3 = location();
            var notElem3 = matchCharClassCst("a-zA-Z0-9_$", false, false);
            restoreLocation(notStart3);
            var elem1_1 = notElem3.isSuccess() ? CstParseResult.failure("not match") : CstParseResult.success(null, "", location());
            if (elem1_1.isFailure()) {
                restoreLocation(seqStart1);
                tbElem0 = elem1_1;
            }
        }
        if (tbElem0.isSuccess()) {
            tbElem0 = CstParseResult.success(null, substring(seqStart1.offset(), pos), location());
        }
        inTokenBoundary = false;
        children.clear();
        children.addAll(savedChildrenTb0);
        CstParseResult result;
        if (tbElem0.isSuccess()) {
            var tbText0 = substring(tbStart0.offset(), pos);
            var tbSpan0 = SourceSpan.of(tbStart0, location());
            var tbNode0 = new CstNode.Token(tbSpan0, RULE_PEG_TOKEN, tbText0, List.of(), List.of());
            children.add(tbNode0);
            result = CstParseResult.success(tbNode0, tbText0, location());
        } else {
            result = tbElem0;
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.Token(span, RULE_CONTINUE_K_W, result.text, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_AssertKW(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(57, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        var tbStart0 = location();
        inTokenBoundary = true;
        var savedChildrenTb0 = new ArrayList<>(children);
        CstParseResult tbElem0 = CstParseResult.success(null, "", location());
        var seqStart1 = location();
        if (tbElem0.isSuccess()) {
            var elem1_0 = matchLiteralCst("assert", false);
            if (elem1_0.isFailure()) {
                restoreLocation(seqStart1);
                tbElem0 = elem1_0;
            }
        }
        if (tbElem0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var notStart3 = location();
            var notElem3 = matchCharClassCst("a-zA-Z0-9_$", false, false);
            restoreLocation(notStart3);
            var elem1_1 = notElem3.isSuccess() ? CstParseResult.failure("not match") : CstParseResult.success(null, "", location());
            if (elem1_1.isFailure()) {
                restoreLocation(seqStart1);
                tbElem0 = elem1_1;
            }
        }
        if (tbElem0.isSuccess()) {
            tbElem0 = CstParseResult.success(null, substring(seqStart1.offset(), pos), location());
        }
        inTokenBoundary = false;
        children.clear();
        children.addAll(savedChildrenTb0);
        CstParseResult result;
        if (tbElem0.isSuccess()) {
            var tbText0 = substring(tbStart0.offset(), pos);
            var tbSpan0 = SourceSpan.of(tbStart0, location());
            var tbNode0 = new CstNode.Token(tbSpan0, RULE_PEG_TOKEN, tbText0, List.of(), List.of());
            children.add(tbNode0);
            result = CstParseResult.success(tbNode0, tbText0, location());
        } else {
            result = tbElem0;
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.Token(span, RULE_ASSERT_K_W, result.text, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_YieldKW(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(58, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        var tbStart0 = location();
        inTokenBoundary = true;
        var savedChildrenTb0 = new ArrayList<>(children);
        CstParseResult tbElem0 = CstParseResult.success(null, "", location());
        var seqStart1 = location();
        if (tbElem0.isSuccess()) {
            var elem1_0 = matchLiteralCst("yield", false);
            if (elem1_0.isFailure()) {
                restoreLocation(seqStart1);
                tbElem0 = elem1_0;
            }
        }
        if (tbElem0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var notStart3 = location();
            var notElem3 = matchCharClassCst("a-zA-Z0-9_$", false, false);
            restoreLocation(notStart3);
            var elem1_1 = notElem3.isSuccess() ? CstParseResult.failure("not match") : CstParseResult.success(null, "", location());
            if (elem1_1.isFailure()) {
                restoreLocation(seqStart1);
                tbElem0 = elem1_1;
            }
        }
        if (tbElem0.isSuccess()) {
            tbElem0 = CstParseResult.success(null, substring(seqStart1.offset(), pos), location());
        }
        inTokenBoundary = false;
        children.clear();
        children.addAll(savedChildrenTb0);
        CstParseResult result;
        if (tbElem0.isSuccess()) {
            var tbText0 = substring(tbStart0.offset(), pos);
            var tbSpan0 = SourceSpan.of(tbStart0, location());
            var tbNode0 = new CstNode.Token(tbSpan0, RULE_PEG_TOKEN, tbText0, List.of(), List.of());
            children.add(tbNode0);
            result = CstParseResult.success(tbNode0, tbText0, location());
        } else {
            result = tbElem0;
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.Token(span, RULE_YIELD_K_W, result.text, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_ForCtrl(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(59, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_0 = CstParseResult.success(null, "", location());
        var seqStart1 = location();
        if (alt0_0.isSuccess()) {
            var optStart2 = location();
            var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem2 = parse_ForInit(trivia3);
            if (optElem2.isSuccess() && optElem2.node != null) {
                children.add(optElem2.node);
            }
            var elem1_0 = optElem2.isSuccess() ? optElem2 : CstParseResult.success(null, "", location());
            if (optElem2.isFailure()) {
                restoreLocation(optStart2);
            }
            if (elem1_0.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_0;
            }
        }
        if (alt0_0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem1_1 = matchLiteralCst(";", false);
            if (elem1_1.isSuccess() && elem1_1.node != null) {
                children.add(elem1_1.node);
            }
            if (elem1_1.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_1;
            }
        }
        if (alt0_0.isSuccess()) {
            var optStart5 = location();
            var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem5 = parse_Expr(trivia6);
            if (optElem5.isSuccess() && optElem5.node != null) {
                children.add(optElem5.node);
            }
            var elem1_2 = optElem5.isSuccess() ? optElem5 : CstParseResult.success(null, "", location());
            if (optElem5.isFailure()) {
                restoreLocation(optStart5);
            }
            if (elem1_2.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_2;
            }
        }
        if (alt0_0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem1_3 = matchLiteralCst(";", false);
            if (elem1_3.isSuccess() && elem1_3.node != null) {
                children.add(elem1_3.node);
            }
            if (elem1_3.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_3;
            }
        }
        if (alt0_0.isSuccess()) {
            var optStart8 = location();
            var trivia9 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem8 = parse_ExprList(trivia9);
            if (optElem8.isSuccess() && optElem8.node != null) {
                children.add(optElem8.node);
            }
            var elem1_4 = optElem8.isSuccess() ? optElem8 : CstParseResult.success(null, "", location());
            if (optElem8.isFailure()) {
                restoreLocation(optStart8);
            }
            if (elem1_4.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_4;
            }
        }
        if (alt0_0.isSuccess()) {
            alt0_0 = CstParseResult.success(null, substring(seqStart1.offset(), pos), location());
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_1 = CstParseResult.success(null, "", location());
        var seqStart10 = location();
        if (alt0_1.isSuccess()) {
            var trivia11 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem10_0 = parse_LocalVarType(trivia11);
            if (elem10_0.isSuccess() && elem10_0.node != null) {
                children.add(elem10_0.node);
            }
            if (elem10_0.isFailure()) {
                restoreLocation(seqStart10);
                alt0_1 = elem10_0;
            }
        }
        if (alt0_1.isSuccess()) {
            var trivia12 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem10_1 = parse_Identifier(trivia12);
            if (elem10_1.isSuccess() && elem10_1.node != null) {
                children.add(elem10_1.node);
            }
            if (elem10_1.isFailure()) {
                restoreLocation(seqStart10);
                alt0_1 = elem10_1;
            }
        }
        if (alt0_1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem10_2 = matchLiteralCst(":", false);
            if (elem10_2.isSuccess() && elem10_2.node != null) {
                children.add(elem10_2.node);
            }
            if (elem10_2.isFailure()) {
                restoreLocation(seqStart10);
                alt0_1 = elem10_2;
            }
        }
        if (alt0_1.isSuccess()) {
            var trivia14 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem10_3 = parse_Expr(trivia14);
            if (elem10_3.isSuccess() && elem10_3.node != null) {
                children.add(elem10_3.node);
            }
            if (elem10_3.isFailure()) {
                restoreLocation(seqStart10);
                alt0_1 = elem10_3;
            }
        }
        if (alt0_1.isSuccess()) {
            alt0_1 = CstParseResult.success(null, substring(seqStart10.offset(), pos), location());
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_FOR_CTRL, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_ForInit(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(60, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_0 = parse_LocalVarNoSemi(trivia1);
        if (alt0_0.isSuccess() && alt0_0.node != null) {
            children.add(alt0_0.node);
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_1 = parse_ExprList(trivia2);
        if (alt0_1.isSuccess() && alt0_1.node != null) {
            children.add(alt0_1.node);
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_FOR_INIT, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_LocalVarNoSemi(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(61, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            CstParseResult elem0_0 = CstParseResult.success(null, "", location());
            var zomStart1 = location();
            while (true) {
                var beforeLoc1 = location();
                var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem1 = parse_Modifier(trivia2);
                if (zomElem1.isSuccess() && zomElem1.node != null) {
                    children.add(zomElem1.node);
                }
                if (zomElem1.isFailure() || location().offset() == beforeLoc1.offset()) {
                    restoreLocation(beforeLoc1);
                    break;
                }
            }
            elem0_0 = CstParseResult.success(null, substring(zomStart1.offset(), pos), location());
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_LocalVarType(trivia3);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_2 = parse_VarDecls(trivia4);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_LOCAL_VAR_NO_SEMI, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_ResourceSpec(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(62, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("(", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_Resource(trivia2);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_2 = CstParseResult.success(null, "", location());
            var zomStart3 = location();
            while (true) {
                var beforeLoc3 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem3 = CstParseResult.success(null, "", location());
                var seqStart5 = location();
                if (zomElem3.isSuccess()) {
                    var elem5_0 = matchLiteralCst(";", false);
                    if (elem5_0.isSuccess() && elem5_0.node != null) {
                        children.add(elem5_0.node);
                    }
                    if (elem5_0.isFailure()) {
                        restoreLocation(seqStart5);
                        zomElem3 = elem5_0;
                    }
                }
                if (zomElem3.isSuccess()) {
                    var trivia7 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem5_1 = parse_Resource(trivia7);
                    if (elem5_1.isSuccess() && elem5_1.node != null) {
                        children.add(elem5_1.node);
                    }
                    if (elem5_1.isFailure()) {
                        restoreLocation(seqStart5);
                        zomElem3 = elem5_1;
                    }
                }
                if (zomElem3.isSuccess()) {
                    zomElem3 = CstParseResult.success(null, substring(seqStart5.offset(), pos), location());
                }
                if (zomElem3.isFailure() || location().offset() == beforeLoc3.offset()) {
                    restoreLocation(beforeLoc3);
                    break;
                }
            }
            elem0_2 = CstParseResult.success(null, substring(zomStart3.offset(), pos), location());
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            var optStart8 = location();
            if (!inTokenBoundary) skipWhitespace();
            var optElem8 = matchLiteralCst(";", false);
            if (optElem8.isSuccess() && optElem8.node != null) {
                children.add(optElem8.node);
            }
            var elem0_3 = optElem8.isSuccess() ? optElem8 : CstParseResult.success(null, "", location());
            if (optElem8.isFailure()) {
                restoreLocation(optStart8);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_4 = matchLiteralCst(")", false);
            if (elem0_4.isSuccess() && elem0_4.node != null) {
                children.add(elem0_4.node);
            }
            if (elem0_4.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_4;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_RESOURCE_SPEC, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Resource(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(63, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_0 = CstParseResult.success(null, "", location());
        var seqStart1 = location();
        if (alt0_0.isSuccess()) {
            CstParseResult elem1_0 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem2 = parse_Modifier(trivia3);
                if (zomElem2.isSuccess() && zomElem2.node != null) {
                    children.add(zomElem2.node);
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem1_0 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem1_0.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_0;
            }
        }
        if (alt0_0.isSuccess()) {
            var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem1_1 = parse_LocalVarType(trivia4);
            if (elem1_1.isSuccess() && elem1_1.node != null) {
                children.add(elem1_1.node);
            }
            if (elem1_1.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_1;
            }
        }
        if (alt0_0.isSuccess()) {
            var trivia5 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem1_2 = parse_Identifier(trivia5);
            if (elem1_2.isSuccess() && elem1_2.node != null) {
                children.add(elem1_2.node);
            }
            if (elem1_2.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_2;
            }
        }
        if (alt0_0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem1_3 = matchLiteralCst("=", false);
            if (elem1_3.isSuccess() && elem1_3.node != null) {
                children.add(elem1_3.node);
            }
            if (elem1_3.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_3;
            }
        }
        if (alt0_0.isSuccess()) {
            var trivia7 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem1_4 = parse_Expr(trivia7);
            if (elem1_4.isSuccess() && elem1_4.node != null) {
                children.add(elem1_4.node);
            }
            if (elem1_4.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_4;
            }
        }
        if (alt0_0.isSuccess()) {
            alt0_0 = CstParseResult.success(null, substring(seqStart1.offset(), pos), location());
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia8 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_1 = parse_QualifiedName(trivia8);
        if (alt0_1.isSuccess() && alt0_1.node != null) {
            children.add(alt0_1.node);
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_RESOURCE, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Catch(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(64, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("catch", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_1 = matchLiteralCst("(", false);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_2 = CstParseResult.success(null, "", location());
            var zomStart3 = location();
            while (true) {
                var beforeLoc3 = location();
                var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem3 = parse_Modifier(trivia4);
                if (zomElem3.isSuccess() && zomElem3.node != null) {
                    children.add(zomElem3.node);
                }
                if (zomElem3.isFailure() || location().offset() == beforeLoc3.offset()) {
                    restoreLocation(beforeLoc3);
                    break;
                }
            }
            elem0_2 = CstParseResult.success(null, substring(zomStart3.offset(), pos), location());
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            var trivia5 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_3 = parse_Type(trivia5);
            if (elem0_3.isSuccess() && elem0_3.node != null) {
                children.add(elem0_3.node);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_4 = CstParseResult.success(null, "", location());
            var zomStart6 = location();
            while (true) {
                var beforeLoc6 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem6 = CstParseResult.success(null, "", location());
                var seqStart8 = location();
                if (zomElem6.isSuccess()) {
                    var elem8_0 = matchLiteralCst("|", false);
                    if (elem8_0.isSuccess() && elem8_0.node != null) {
                        children.add(elem8_0.node);
                    }
                    if (elem8_0.isFailure()) {
                        restoreLocation(seqStart8);
                        zomElem6 = elem8_0;
                    }
                }
                if (zomElem6.isSuccess()) {
                    var trivia10 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem8_1 = parse_Type(trivia10);
                    if (elem8_1.isSuccess() && elem8_1.node != null) {
                        children.add(elem8_1.node);
                    }
                    if (elem8_1.isFailure()) {
                        restoreLocation(seqStart8);
                        zomElem6 = elem8_1;
                    }
                }
                if (zomElem6.isSuccess()) {
                    zomElem6 = CstParseResult.success(null, substring(seqStart8.offset(), pos), location());
                }
                if (zomElem6.isFailure() || location().offset() == beforeLoc6.offset()) {
                    restoreLocation(beforeLoc6);
                    break;
                }
            }
            elem0_4 = CstParseResult.success(null, substring(zomStart6.offset(), pos), location());
            if (elem0_4.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_4;
            }
        }
        if (result.isSuccess()) {
            var trivia11 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_5 = parse_Identifier(trivia11);
            if (elem0_5.isSuccess() && elem0_5.node != null) {
                children.add(elem0_5.node);
            }
            if (elem0_5.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_5;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_6 = matchLiteralCst(")", false);
            if (elem0_6.isSuccess() && elem0_6.node != null) {
                children.add(elem0_6.node);
            }
            if (elem0_6.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_6;
            }
        }
        if (result.isSuccess()) {
            var trivia13 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_7 = parse_Block(trivia13);
            if (elem0_7.isSuccess() && elem0_7.node != null) {
                children.add(elem0_7.node);
            }
            if (elem0_7.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_7;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_CATCH, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Finally(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(65, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("finally", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_Block(trivia2);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_FINALLY, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_SwitchBlock(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(66, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("{", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem2 = parse_SwitchRule(trivia3);
                if (zomElem2.isSuccess() && zomElem2.node != null) {
                    children.add(zomElem2.node);
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_2 = matchLiteralCst("}", false);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_SWITCH_BLOCK, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_SwitchRule(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(67, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_0 = CstParseResult.success(null, "", location());
        var seqStart1 = location();
        if (alt0_0.isSuccess()) {
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem1_0 = parse_SwitchLabel(trivia2);
            if (elem1_0.isSuccess() && elem1_0.node != null) {
                children.add(elem1_0.node);
            }
            if (elem1_0.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_0;
            }
        }
        if (alt0_0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem1_1 = matchLiteralCst("->", false);
            if (elem1_1.isSuccess() && elem1_1.node != null) {
                children.add(elem1_1.node);
            }
            if (elem1_1.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_1;
            }
        }
        if (alt0_0.isSuccess()) {
            CstParseResult elem1_2 = null;
            var choiceStart5 = location();
            var savedChildren5 = new ArrayList<>(children);
            children.clear();
            children.addAll(savedChildren5);
            CstParseResult alt5_0 = CstParseResult.success(null, "", location());
            var seqStart6 = location();
            if (alt5_0.isSuccess()) {
                var trivia7 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var elem6_0 = parse_Expr(trivia7);
                if (elem6_0.isSuccess() && elem6_0.node != null) {
                    children.add(elem6_0.node);
                }
                if (elem6_0.isFailure()) {
                    restoreLocation(seqStart6);
                    alt5_0 = elem6_0;
                }
            }
            if (alt5_0.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var elem6_1 = matchLiteralCst(";", false);
                if (elem6_1.isSuccess() && elem6_1.node != null) {
                    children.add(elem6_1.node);
                }
                if (elem6_1.isFailure()) {
                    restoreLocation(seqStart6);
                    alt5_0 = elem6_1;
                }
            }
            if (alt5_0.isSuccess()) {
                alt5_0 = CstParseResult.success(null, substring(seqStart6.offset(), pos), location());
            }
            if (alt5_0.isSuccess()) {
                elem1_2 = alt5_0;
            } else {
                restoreLocation(choiceStart5);
            children.clear();
            children.addAll(savedChildren5);
            var trivia9 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var alt5_1 = parse_Block(trivia9);
            if (alt5_1.isSuccess() && alt5_1.node != null) {
                children.add(alt5_1.node);
            }
            if (alt5_1.isSuccess()) {
                elem1_2 = alt5_1;
            } else {
                restoreLocation(choiceStart5);
            children.clear();
            children.addAll(savedChildren5);
            CstParseResult alt5_2 = CstParseResult.success(null, "", location());
            var seqStart10 = location();
            if (alt5_2.isSuccess()) {
                var elem10_0 = matchLiteralCst("throw", false);
                if (elem10_0.isSuccess() && elem10_0.node != null) {
                    children.add(elem10_0.node);
                }
                if (elem10_0.isFailure()) {
                    restoreLocation(seqStart10);
                    alt5_2 = elem10_0;
                }
            }
            if (alt5_2.isSuccess()) {
                var trivia12 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var elem10_1 = parse_Expr(trivia12);
                if (elem10_1.isSuccess() && elem10_1.node != null) {
                    children.add(elem10_1.node);
                }
                if (elem10_1.isFailure()) {
                    restoreLocation(seqStart10);
                    alt5_2 = elem10_1;
                }
            }
            if (alt5_2.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var elem10_2 = matchLiteralCst(";", false);
                if (elem10_2.isSuccess() && elem10_2.node != null) {
                    children.add(elem10_2.node);
                }
                if (elem10_2.isFailure()) {
                    restoreLocation(seqStart10);
                    alt5_2 = elem10_2;
                }
            }
            if (alt5_2.isSuccess()) {
                alt5_2 = CstParseResult.success(null, substring(seqStart10.offset(), pos), location());
            }
            if (alt5_2.isSuccess()) {
                elem1_2 = alt5_2;
            } else {
                restoreLocation(choiceStart5);
            }
            }
            }
            if (elem1_2 == null) {
                children.clear();
                children.addAll(savedChildren5);
                elem1_2 = CstParseResult.failure("one of alternatives");
            }
            if (elem1_2.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_2;
            }
        }
        if (alt0_0.isSuccess()) {
            alt0_0 = CstParseResult.success(null, substring(seqStart1.offset(), pos), location());
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_1 = CstParseResult.success(null, "", location());
        var seqStart14 = location();
        if (alt0_1.isSuccess()) {
            var trivia15 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem14_0 = parse_SwitchLabel(trivia15);
            if (elem14_0.isSuccess() && elem14_0.node != null) {
                children.add(elem14_0.node);
            }
            if (elem14_0.isFailure()) {
                restoreLocation(seqStart14);
                alt0_1 = elem14_0;
            }
        }
        if (alt0_1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem14_1 = matchLiteralCst(":", false);
            if (elem14_1.isSuccess() && elem14_1.node != null) {
                children.add(elem14_1.node);
            }
            if (elem14_1.isFailure()) {
                restoreLocation(seqStart14);
                alt0_1 = elem14_1;
            }
        }
        if (alt0_1.isSuccess()) {
            CstParseResult elem14_2 = CstParseResult.success(null, "", location());
            var zomStart17 = location();
            while (true) {
                var beforeLoc17 = location();
                var trivia18 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem17 = parse_BlockStmt(trivia18);
                if (zomElem17.isSuccess() && zomElem17.node != null) {
                    children.add(zomElem17.node);
                }
                if (zomElem17.isFailure() || location().offset() == beforeLoc17.offset()) {
                    restoreLocation(beforeLoc17);
                    break;
                }
            }
            elem14_2 = CstParseResult.success(null, substring(zomStart17.offset(), pos), location());
            if (elem14_2.isFailure()) {
                restoreLocation(seqStart14);
                alt0_1 = elem14_2;
            }
        }
        if (alt0_1.isSuccess()) {
            alt0_1 = CstParseResult.success(null, substring(seqStart14.offset(), pos), location());
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_SWITCH_RULE, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_SwitchLabel(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(68, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_0 = CstParseResult.success(null, "", location());
        var seqStart1 = location();
        if (alt0_0.isSuccess()) {
            var elem1_0 = matchLiteralCst("case", false);
            if (elem1_0.isSuccess() && elem1_0.node != null) {
                children.add(elem1_0.node);
            }
            if (elem1_0.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_0;
            }
        }
        if (alt0_0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem1_1 = matchLiteralCst("null", false);
            if (elem1_1.isSuccess() && elem1_1.node != null) {
                children.add(elem1_1.node);
            }
            if (elem1_1.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_1;
            }
        }
        if (alt0_0.isSuccess()) {
            var optStart4 = location();
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult optElem4 = CstParseResult.success(null, "", location());
            var seqStart6 = location();
            if (optElem4.isSuccess()) {
                var elem6_0 = matchLiteralCst(",", false);
                if (elem6_0.isSuccess() && elem6_0.node != null) {
                    children.add(elem6_0.node);
                }
                if (elem6_0.isFailure()) {
                    restoreLocation(seqStart6);
                    optElem4 = elem6_0;
                }
            }
            if (optElem4.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var elem6_1 = matchLiteralCst("default", false);
                if (elem6_1.isSuccess() && elem6_1.node != null) {
                    children.add(elem6_1.node);
                }
                if (elem6_1.isFailure()) {
                    restoreLocation(seqStart6);
                    optElem4 = elem6_1;
                }
            }
            if (optElem4.isSuccess()) {
                optElem4 = CstParseResult.success(null, substring(seqStart6.offset(), pos), location());
            }
            var elem1_2 = optElem4.isSuccess() ? optElem4 : CstParseResult.success(null, "", location());
            if (optElem4.isFailure()) {
                restoreLocation(optStart4);
            }
            if (elem1_2.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_2;
            }
        }
        if (alt0_0.isSuccess()) {
            alt0_0 = CstParseResult.success(null, substring(seqStart1.offset(), pos), location());
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_1 = CstParseResult.success(null, "", location());
        var seqStart9 = location();
        if (alt0_1.isSuccess()) {
            var elem9_0 = matchLiteralCst("case", false);
            if (elem9_0.isSuccess() && elem9_0.node != null) {
                children.add(elem9_0.node);
            }
            if (elem9_0.isFailure()) {
                restoreLocation(seqStart9);
                alt0_1 = elem9_0;
            }
        }
        if (alt0_1.isSuccess()) {
            var trivia11 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem9_1 = parse_CaseItem(trivia11);
            if (elem9_1.isSuccess() && elem9_1.node != null) {
                children.add(elem9_1.node);
            }
            if (elem9_1.isFailure()) {
                restoreLocation(seqStart9);
                alt0_1 = elem9_1;
            }
        }
        if (alt0_1.isSuccess()) {
            CstParseResult elem9_2 = CstParseResult.success(null, "", location());
            var zomStart12 = location();
            while (true) {
                var beforeLoc12 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem12 = CstParseResult.success(null, "", location());
                var seqStart14 = location();
                if (zomElem12.isSuccess()) {
                    var elem14_0 = matchLiteralCst(",", false);
                    if (elem14_0.isSuccess() && elem14_0.node != null) {
                        children.add(elem14_0.node);
                    }
                    if (elem14_0.isFailure()) {
                        restoreLocation(seqStart14);
                        zomElem12 = elem14_0;
                    }
                }
                if (zomElem12.isSuccess()) {
                    var trivia16 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem14_1 = parse_CaseItem(trivia16);
                    if (elem14_1.isSuccess() && elem14_1.node != null) {
                        children.add(elem14_1.node);
                    }
                    if (elem14_1.isFailure()) {
                        restoreLocation(seqStart14);
                        zomElem12 = elem14_1;
                    }
                }
                if (zomElem12.isSuccess()) {
                    zomElem12 = CstParseResult.success(null, substring(seqStart14.offset(), pos), location());
                }
                if (zomElem12.isFailure() || location().offset() == beforeLoc12.offset()) {
                    restoreLocation(beforeLoc12);
                    break;
                }
            }
            elem9_2 = CstParseResult.success(null, substring(zomStart12.offset(), pos), location());
            if (elem9_2.isFailure()) {
                restoreLocation(seqStart9);
                alt0_1 = elem9_2;
            }
        }
        if (alt0_1.isSuccess()) {
            var optStart17 = location();
            var trivia18 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem17 = parse_Guard(trivia18);
            if (optElem17.isSuccess() && optElem17.node != null) {
                children.add(optElem17.node);
            }
            var elem9_3 = optElem17.isSuccess() ? optElem17 : CstParseResult.success(null, "", location());
            if (optElem17.isFailure()) {
                restoreLocation(optStart17);
            }
            if (elem9_3.isFailure()) {
                restoreLocation(seqStart9);
                alt0_1 = elem9_3;
            }
        }
        if (alt0_1.isSuccess()) {
            alt0_1 = CstParseResult.success(null, substring(seqStart9.offset(), pos), location());
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_2 = matchLiteralCst("default", false);
        if (alt0_2.isSuccess() && alt0_2.node != null) {
            children.add(alt0_2.node);
        }
        if (alt0_2.isSuccess()) {
            result = alt0_2;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_SWITCH_LABEL, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_CaseItem(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(69, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_0 = parse_Pattern(trivia1);
        if (alt0_0.isSuccess() && alt0_0.node != null) {
            children.add(alt0_0.node);
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_1 = CstParseResult.success(null, "", location());
        var seqStart2 = location();
        if (alt0_1.isSuccess()) {
            var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem2_0 = parse_QualifiedName(trivia3);
            if (elem2_0.isSuccess() && elem2_0.node != null) {
                children.add(elem2_0.node);
            }
            if (elem2_0.isFailure()) {
                restoreLocation(seqStart2);
                alt0_1 = elem2_0;
            }
        }
        if (alt0_1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var andStart4 = location();
            var savedChildrenAnd4 = new ArrayList<>(children);
            CstParseResult andElem4 = null;
            var choiceStart6 = location();
            var alt6_0 = matchLiteralCst("->", false);
            if (alt6_0.isSuccess()) {
                andElem4 = alt6_0;
            } else {
                restoreLocation(choiceStart6);
            var alt6_1 = matchLiteralCst(",", false);
            if (alt6_1.isSuccess()) {
                andElem4 = alt6_1;
            } else {
                restoreLocation(choiceStart6);
            var alt6_2 = matchLiteralCst(":", false);
            if (alt6_2.isSuccess()) {
                andElem4 = alt6_2;
            } else {
                restoreLocation(choiceStart6);
            var alt6_3 = matchLiteralCst("when", false);
            if (alt6_3.isSuccess()) {
                andElem4 = alt6_3;
            } else {
                restoreLocation(choiceStart6);
            }
            }
            }
            }
            if (andElem4 == null) {
                andElem4 = CstParseResult.failure("one of alternatives");
            }
            restoreLocation(andStart4);
            children.clear();
            children.addAll(savedChildrenAnd4);
            var elem2_1 = andElem4.isSuccess() ? CstParseResult.success(null, "", location()) : andElem4;
            if (elem2_1.isFailure()) {
                restoreLocation(seqStart2);
                alt0_1 = elem2_1;
            }
        }
        if (alt0_1.isSuccess()) {
            alt0_1 = CstParseResult.success(null, substring(seqStart2.offset(), pos), location());
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia11 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_2 = parse_Expr(trivia11);
        if (alt0_2.isSuccess() && alt0_2.node != null) {
            children.add(alt0_2.node);
        }
        if (alt0_2.isSuccess()) {
            result = alt0_2;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_CASE_ITEM, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Pattern(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(70, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_0 = parse_RecordPattern(trivia1);
        if (alt0_0.isSuccess() && alt0_0.node != null) {
            children.add(alt0_0.node);
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_1 = parse_TypePattern(trivia2);
        if (alt0_1.isSuccess() && alt0_1.node != null) {
            children.add(alt0_1.node);
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_PATTERN, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_TypePattern(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(71, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_0 = CstParseResult.success(null, "", location());
        var seqStart1 = location();
        if (alt0_0.isSuccess()) {
            var andStart2 = location();
            var savedChildrenAnd2 = new ArrayList<>(children);
            CstParseResult andElem2 = CstParseResult.success(null, "", location());
            var seqStart4 = location();
            if (andElem2.isSuccess()) {
                var trivia5 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var elem4_0 = parse_LocalVarType(trivia5);
                if (elem4_0.isFailure()) {
                    restoreLocation(seqStart4);
                    andElem2 = elem4_0;
                }
            }
            if (andElem2.isSuccess()) {
                var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var elem4_1 = parse_Identifier(trivia6);
                if (elem4_1.isFailure()) {
                    restoreLocation(seqStart4);
                    andElem2 = elem4_1;
                }
            }
            if (andElem2.isSuccess()) {
                andElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
            }
            restoreLocation(andStart2);
            children.clear();
            children.addAll(savedChildrenAnd2);
            var elem1_0 = andElem2.isSuccess() ? CstParseResult.success(null, "", location()) : andElem2;
            if (elem1_0.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_0;
            }
        }
        if (alt0_0.isSuccess()) {
            var trivia7 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem1_1 = parse_LocalVarType(trivia7);
            if (elem1_1.isSuccess() && elem1_1.node != null) {
                children.add(elem1_1.node);
            }
            if (elem1_1.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_1;
            }
        }
        if (alt0_0.isSuccess()) {
            var trivia8 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem1_2 = parse_Identifier(trivia8);
            if (elem1_2.isSuccess() && elem1_2.node != null) {
                children.add(elem1_2.node);
            }
            if (elem1_2.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_2;
            }
        }
        if (alt0_0.isSuccess()) {
            alt0_0 = CstParseResult.success(null, substring(seqStart1.offset(), pos), location());
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_1 = matchLiteralCst("_", false);
        if (alt0_1.isSuccess() && alt0_1.node != null) {
            children.add(alt0_1.node);
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_TYPE_PATTERN, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_RecordPattern(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(72, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_RefType(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_1 = matchLiteralCst("(", false);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            var optStart3 = location();
            var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem3 = parse_PatternList(trivia4);
            if (optElem3.isSuccess() && optElem3.node != null) {
                children.add(optElem3.node);
            }
            var elem0_2 = optElem3.isSuccess() ? optElem3 : CstParseResult.success(null, "", location());
            if (optElem3.isFailure()) {
                restoreLocation(optStart3);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_3 = matchLiteralCst(")", false);
            if (elem0_3.isSuccess() && elem0_3.node != null) {
                children.add(elem0_3.node);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_RECORD_PATTERN, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_PatternList(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(73, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_Pattern(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem2 = CstParseResult.success(null, "", location());
                var seqStart4 = location();
                if (zomElem2.isSuccess()) {
                    var elem4_0 = matchLiteralCst(",", false);
                    if (elem4_0.isSuccess() && elem4_0.node != null) {
                        children.add(elem4_0.node);
                    }
                    if (elem4_0.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_0;
                    }
                }
                if (zomElem2.isSuccess()) {
                    var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem4_1 = parse_Pattern(trivia6);
                    if (elem4_1.isSuccess() && elem4_1.node != null) {
                        children.add(elem4_1.node);
                    }
                    if (elem4_1.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_1;
                    }
                }
                if (zomElem2.isSuccess()) {
                    zomElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_PATTERN_LIST, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Guard(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(74, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("when", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_Expr(trivia2);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_GUARD, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Expr(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(75, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        var trivia0 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var result = parse_Assignment(trivia0);
        if (result.isSuccess() && result.node != null) {
            children.add(result.node);
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_EXPR, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Assignment(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(76, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_Ternary(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            var optStart2 = location();
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult optElem2 = CstParseResult.success(null, "", location());
            var seqStart4 = location();
            if (optElem2.isSuccess()) {
                CstParseResult elem4_0 = null;
                var choiceStart6 = location();
                var savedChildren6 = new ArrayList<>(children);
                children.clear();
                children.addAll(savedChildren6);
                var alt6_0 = matchLiteralCst("=", false);
                if (alt6_0.isSuccess() && alt6_0.node != null) {
                    children.add(alt6_0.node);
                }
                if (alt6_0.isSuccess()) {
                    elem4_0 = alt6_0;
                } else {
                    restoreLocation(choiceStart6);
                children.clear();
                children.addAll(savedChildren6);
                var alt6_1 = matchLiteralCst(">>>=", false);
                if (alt6_1.isSuccess() && alt6_1.node != null) {
                    children.add(alt6_1.node);
                }
                if (alt6_1.isSuccess()) {
                    elem4_0 = alt6_1;
                } else {
                    restoreLocation(choiceStart6);
                children.clear();
                children.addAll(savedChildren6);
                var alt6_2 = matchLiteralCst(">>=", false);
                if (alt6_2.isSuccess() && alt6_2.node != null) {
                    children.add(alt6_2.node);
                }
                if (alt6_2.isSuccess()) {
                    elem4_0 = alt6_2;
                } else {
                    restoreLocation(choiceStart6);
                children.clear();
                children.addAll(savedChildren6);
                var alt6_3 = matchLiteralCst("<<=", false);
                if (alt6_3.isSuccess() && alt6_3.node != null) {
                    children.add(alt6_3.node);
                }
                if (alt6_3.isSuccess()) {
                    elem4_0 = alt6_3;
                } else {
                    restoreLocation(choiceStart6);
                children.clear();
                children.addAll(savedChildren6);
                var alt6_4 = matchLiteralCst("+=", false);
                if (alt6_4.isSuccess() && alt6_4.node != null) {
                    children.add(alt6_4.node);
                }
                if (alt6_4.isSuccess()) {
                    elem4_0 = alt6_4;
                } else {
                    restoreLocation(choiceStart6);
                children.clear();
                children.addAll(savedChildren6);
                var alt6_5 = matchLiteralCst("-=", false);
                if (alt6_5.isSuccess() && alt6_5.node != null) {
                    children.add(alt6_5.node);
                }
                if (alt6_5.isSuccess()) {
                    elem4_0 = alt6_5;
                } else {
                    restoreLocation(choiceStart6);
                children.clear();
                children.addAll(savedChildren6);
                var alt6_6 = matchLiteralCst("*=", false);
                if (alt6_6.isSuccess() && alt6_6.node != null) {
                    children.add(alt6_6.node);
                }
                if (alt6_6.isSuccess()) {
                    elem4_0 = alt6_6;
                } else {
                    restoreLocation(choiceStart6);
                children.clear();
                children.addAll(savedChildren6);
                var alt6_7 = matchLiteralCst("/=", false);
                if (alt6_7.isSuccess() && alt6_7.node != null) {
                    children.add(alt6_7.node);
                }
                if (alt6_7.isSuccess()) {
                    elem4_0 = alt6_7;
                } else {
                    restoreLocation(choiceStart6);
                children.clear();
                children.addAll(savedChildren6);
                var alt6_8 = matchLiteralCst("%=", false);
                if (alt6_8.isSuccess() && alt6_8.node != null) {
                    children.add(alt6_8.node);
                }
                if (alt6_8.isSuccess()) {
                    elem4_0 = alt6_8;
                } else {
                    restoreLocation(choiceStart6);
                children.clear();
                children.addAll(savedChildren6);
                var alt6_9 = matchLiteralCst("&=", false);
                if (alt6_9.isSuccess() && alt6_9.node != null) {
                    children.add(alt6_9.node);
                }
                if (alt6_9.isSuccess()) {
                    elem4_0 = alt6_9;
                } else {
                    restoreLocation(choiceStart6);
                children.clear();
                children.addAll(savedChildren6);
                var alt6_10 = matchLiteralCst("|=", false);
                if (alt6_10.isSuccess() && alt6_10.node != null) {
                    children.add(alt6_10.node);
                }
                if (alt6_10.isSuccess()) {
                    elem4_0 = alt6_10;
                } else {
                    restoreLocation(choiceStart6);
                children.clear();
                children.addAll(savedChildren6);
                var alt6_11 = matchLiteralCst("^=", false);
                if (alt6_11.isSuccess() && alt6_11.node != null) {
                    children.add(alt6_11.node);
                }
                if (alt6_11.isSuccess()) {
                    elem4_0 = alt6_11;
                } else {
                    restoreLocation(choiceStart6);
                }
                }
                }
                }
                }
                }
                }
                }
                }
                }
                }
                }
                if (elem4_0 == null) {
                    children.clear();
                    children.addAll(savedChildren6);
                    elem4_0 = CstParseResult.failure("one of alternatives");
                }
                if (elem4_0.isFailure()) {
                    restoreLocation(seqStart4);
                    optElem2 = elem4_0;
                }
            }
            if (optElem2.isSuccess()) {
                var trivia19 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var elem4_1 = parse_Assignment(trivia19);
                if (elem4_1.isSuccess() && elem4_1.node != null) {
                    children.add(elem4_1.node);
                }
                if (elem4_1.isFailure()) {
                    restoreLocation(seqStart4);
                    optElem2 = elem4_1;
                }
            }
            if (optElem2.isSuccess()) {
                optElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
            }
            var elem0_1 = optElem2.isSuccess() ? optElem2 : CstParseResult.success(null, "", location());
            if (optElem2.isFailure()) {
                restoreLocation(optStart2);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_ASSIGNMENT, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Ternary(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(77, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_LogOr(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            var optStart2 = location();
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult optElem2 = CstParseResult.success(null, "", location());
            var seqStart4 = location();
            if (optElem2.isSuccess()) {
                var elem4_0 = matchLiteralCst("?", false);
                if (elem4_0.isSuccess() && elem4_0.node != null) {
                    children.add(elem4_0.node);
                }
                if (elem4_0.isFailure()) {
                    restoreLocation(seqStart4);
                    optElem2 = elem4_0;
                }
            }
            if (optElem2.isSuccess()) {
                var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var elem4_1 = parse_Expr(trivia6);
                if (elem4_1.isSuccess() && elem4_1.node != null) {
                    children.add(elem4_1.node);
                }
                if (elem4_1.isFailure()) {
                    restoreLocation(seqStart4);
                    optElem2 = elem4_1;
                }
            }
            if (optElem2.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var elem4_2 = matchLiteralCst(":", false);
                if (elem4_2.isSuccess() && elem4_2.node != null) {
                    children.add(elem4_2.node);
                }
                if (elem4_2.isFailure()) {
                    restoreLocation(seqStart4);
                    optElem2 = elem4_2;
                }
            }
            if (optElem2.isSuccess()) {
                var trivia8 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var elem4_3 = parse_Ternary(trivia8);
                if (elem4_3.isSuccess() && elem4_3.node != null) {
                    children.add(elem4_3.node);
                }
                if (elem4_3.isFailure()) {
                    restoreLocation(seqStart4);
                    optElem2 = elem4_3;
                }
            }
            if (optElem2.isSuccess()) {
                optElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
            }
            var elem0_1 = optElem2.isSuccess() ? optElem2 : CstParseResult.success(null, "", location());
            if (optElem2.isFailure()) {
                restoreLocation(optStart2);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_TERNARY, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_LogOr(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(78, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_LogAnd(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem2 = CstParseResult.success(null, "", location());
                var seqStart4 = location();
                if (zomElem2.isSuccess()) {
                    var elem4_0 = matchLiteralCst("||", false);
                    if (elem4_0.isSuccess() && elem4_0.node != null) {
                        children.add(elem4_0.node);
                    }
                    if (elem4_0.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_0;
                    }
                }
                if (zomElem2.isSuccess()) {
                    var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem4_1 = parse_LogAnd(trivia6);
                    if (elem4_1.isSuccess() && elem4_1.node != null) {
                        children.add(elem4_1.node);
                    }
                    if (elem4_1.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_1;
                    }
                }
                if (zomElem2.isSuccess()) {
                    zomElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_LOG_OR, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_LogAnd(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(79, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_BitOr(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem2 = CstParseResult.success(null, "", location());
                var seqStart4 = location();
                if (zomElem2.isSuccess()) {
                    var elem4_0 = matchLiteralCst("&&", false);
                    if (elem4_0.isSuccess() && elem4_0.node != null) {
                        children.add(elem4_0.node);
                    }
                    if (elem4_0.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_0;
                    }
                }
                if (zomElem2.isSuccess()) {
                    var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem4_1 = parse_BitOr(trivia6);
                    if (elem4_1.isSuccess() && elem4_1.node != null) {
                        children.add(elem4_1.node);
                    }
                    if (elem4_1.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_1;
                    }
                }
                if (zomElem2.isSuccess()) {
                    zomElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_LOG_AND, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_BitOr(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(80, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_BitXor(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem2 = CstParseResult.success(null, "", location());
                var seqStart4 = location();
                if (zomElem2.isSuccess()) {
                    var notStart5 = location();
                    var savedChildrenNot5 = new ArrayList<>(children);
                    var notElem5 = matchLiteralCst("||", false);
                    restoreLocation(notStart5);
                    children.clear();
                    children.addAll(savedChildrenNot5);
                    var elem4_0 = notElem5.isSuccess() ? CstParseResult.failure("not match") : CstParseResult.success(null, "", location());
                    if (elem4_0.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_0;
                    }
                }
                if (zomElem2.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var notStart7 = location();
                    var savedChildrenNot7 = new ArrayList<>(children);
                    var notElem7 = matchLiteralCst("|=", false);
                    restoreLocation(notStart7);
                    children.clear();
                    children.addAll(savedChildrenNot7);
                    var elem4_1 = notElem7.isSuccess() ? CstParseResult.failure("not match") : CstParseResult.success(null, "", location());
                    if (elem4_1.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_1;
                    }
                }
                if (zomElem2.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var elem4_2 = matchLiteralCst("|", false);
                    if (elem4_2.isSuccess() && elem4_2.node != null) {
                        children.add(elem4_2.node);
                    }
                    if (elem4_2.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_2;
                    }
                }
                if (zomElem2.isSuccess()) {
                    var trivia10 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem4_3 = parse_BitXor(trivia10);
                    if (elem4_3.isSuccess() && elem4_3.node != null) {
                        children.add(elem4_3.node);
                    }
                    if (elem4_3.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_3;
                    }
                }
                if (zomElem2.isSuccess()) {
                    zomElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_BIT_OR, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_BitXor(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(81, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_BitAnd(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem2 = CstParseResult.success(null, "", location());
                var seqStart4 = location();
                if (zomElem2.isSuccess()) {
                    var notStart5 = location();
                    var savedChildrenNot5 = new ArrayList<>(children);
                    var notElem5 = matchLiteralCst("^=", false);
                    restoreLocation(notStart5);
                    children.clear();
                    children.addAll(savedChildrenNot5);
                    var elem4_0 = notElem5.isSuccess() ? CstParseResult.failure("not match") : CstParseResult.success(null, "", location());
                    if (elem4_0.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_0;
                    }
                }
                if (zomElem2.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var elem4_1 = matchLiteralCst("^", false);
                    if (elem4_1.isSuccess() && elem4_1.node != null) {
                        children.add(elem4_1.node);
                    }
                    if (elem4_1.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_1;
                    }
                }
                if (zomElem2.isSuccess()) {
                    var trivia8 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem4_2 = parse_BitAnd(trivia8);
                    if (elem4_2.isSuccess() && elem4_2.node != null) {
                        children.add(elem4_2.node);
                    }
                    if (elem4_2.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_2;
                    }
                }
                if (zomElem2.isSuccess()) {
                    zomElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_BIT_XOR, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_BitAnd(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(82, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_Equality(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem2 = CstParseResult.success(null, "", location());
                var seqStart4 = location();
                if (zomElem2.isSuccess()) {
                    var notStart5 = location();
                    var savedChildrenNot5 = new ArrayList<>(children);
                    var notElem5 = matchLiteralCst("&&", false);
                    restoreLocation(notStart5);
                    children.clear();
                    children.addAll(savedChildrenNot5);
                    var elem4_0 = notElem5.isSuccess() ? CstParseResult.failure("not match") : CstParseResult.success(null, "", location());
                    if (elem4_0.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_0;
                    }
                }
                if (zomElem2.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var notStart7 = location();
                    var savedChildrenNot7 = new ArrayList<>(children);
                    var notElem7 = matchLiteralCst("&=", false);
                    restoreLocation(notStart7);
                    children.clear();
                    children.addAll(savedChildrenNot7);
                    var elem4_1 = notElem7.isSuccess() ? CstParseResult.failure("not match") : CstParseResult.success(null, "", location());
                    if (elem4_1.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_1;
                    }
                }
                if (zomElem2.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var elem4_2 = matchLiteralCst("&", false);
                    if (elem4_2.isSuccess() && elem4_2.node != null) {
                        children.add(elem4_2.node);
                    }
                    if (elem4_2.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_2;
                    }
                }
                if (zomElem2.isSuccess()) {
                    var trivia10 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem4_3 = parse_Equality(trivia10);
                    if (elem4_3.isSuccess() && elem4_3.node != null) {
                        children.add(elem4_3.node);
                    }
                    if (elem4_3.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_3;
                    }
                }
                if (zomElem2.isSuccess()) {
                    zomElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_BIT_AND, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Equality(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(83, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_Relational(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem2 = CstParseResult.success(null, "", location());
                var seqStart4 = location();
                if (zomElem2.isSuccess()) {
                    CstParseResult elem4_0 = null;
                    var choiceStart6 = location();
                    var savedChildren6 = new ArrayList<>(children);
                    children.clear();
                    children.addAll(savedChildren6);
                    var alt6_0 = matchLiteralCst("==", false);
                    if (alt6_0.isSuccess() && alt6_0.node != null) {
                        children.add(alt6_0.node);
                    }
                    if (alt6_0.isSuccess()) {
                        elem4_0 = alt6_0;
                    } else {
                        restoreLocation(choiceStart6);
                    children.clear();
                    children.addAll(savedChildren6);
                    var alt6_1 = matchLiteralCst("!=", false);
                    if (alt6_1.isSuccess() && alt6_1.node != null) {
                        children.add(alt6_1.node);
                    }
                    if (alt6_1.isSuccess()) {
                        elem4_0 = alt6_1;
                    } else {
                        restoreLocation(choiceStart6);
                    }
                    }
                    if (elem4_0 == null) {
                        children.clear();
                        children.addAll(savedChildren6);
                        elem4_0 = CstParseResult.failure("one of alternatives");
                    }
                    if (elem4_0.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_0;
                    }
                }
                if (zomElem2.isSuccess()) {
                    var trivia9 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem4_1 = parse_Relational(trivia9);
                    if (elem4_1.isSuccess() && elem4_1.node != null) {
                        children.add(elem4_1.node);
                    }
                    if (elem4_1.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_1;
                    }
                }
                if (zomElem2.isSuccess()) {
                    zomElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_EQUALITY, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Relational(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(84, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_Shift(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            var optStart2 = location();
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult optElem2 = null;
            var choiceStart4 = location();
            var savedChildren4 = new ArrayList<>(children);
            children.clear();
            children.addAll(savedChildren4);
            CstParseResult alt4_0 = CstParseResult.success(null, "", location());
            var seqStart5 = location();
            if (alt4_0.isSuccess()) {
                CstParseResult elem5_0 = null;
                var choiceStart7 = location();
                var savedChildren7 = new ArrayList<>(children);
                children.clear();
                children.addAll(savedChildren7);
                var alt7_0 = matchLiteralCst("<=", false);
                if (alt7_0.isSuccess() && alt7_0.node != null) {
                    children.add(alt7_0.node);
                }
                if (alt7_0.isSuccess()) {
                    elem5_0 = alt7_0;
                } else {
                    restoreLocation(choiceStart7);
                children.clear();
                children.addAll(savedChildren7);
                var alt7_1 = matchLiteralCst(">=", false);
                if (alt7_1.isSuccess() && alt7_1.node != null) {
                    children.add(alt7_1.node);
                }
                if (alt7_1.isSuccess()) {
                    elem5_0 = alt7_1;
                } else {
                    restoreLocation(choiceStart7);
                children.clear();
                children.addAll(savedChildren7);
                var alt7_2 = matchLiteralCst("<", false);
                if (alt7_2.isSuccess() && alt7_2.node != null) {
                    children.add(alt7_2.node);
                }
                if (alt7_2.isSuccess()) {
                    elem5_0 = alt7_2;
                } else {
                    restoreLocation(choiceStart7);
                children.clear();
                children.addAll(savedChildren7);
                var alt7_3 = matchLiteralCst(">", false);
                if (alt7_3.isSuccess() && alt7_3.node != null) {
                    children.add(alt7_3.node);
                }
                if (alt7_3.isSuccess()) {
                    elem5_0 = alt7_3;
                } else {
                    restoreLocation(choiceStart7);
                }
                }
                }
                }
                if (elem5_0 == null) {
                    children.clear();
                    children.addAll(savedChildren7);
                    elem5_0 = CstParseResult.failure("one of alternatives");
                }
                if (elem5_0.isFailure()) {
                    restoreLocation(seqStart5);
                    alt4_0 = elem5_0;
                }
            }
            if (alt4_0.isSuccess()) {
                var trivia12 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var elem5_1 = parse_Shift(trivia12);
                if (elem5_1.isSuccess() && elem5_1.node != null) {
                    children.add(elem5_1.node);
                }
                if (elem5_1.isFailure()) {
                    restoreLocation(seqStart5);
                    alt4_0 = elem5_1;
                }
            }
            if (alt4_0.isSuccess()) {
                alt4_0 = CstParseResult.success(null, substring(seqStart5.offset(), pos), location());
            }
            if (alt4_0.isSuccess()) {
                optElem2 = alt4_0;
            } else {
                restoreLocation(choiceStart4);
            children.clear();
            children.addAll(savedChildren4);
            CstParseResult alt4_1 = CstParseResult.success(null, "", location());
            var seqStart13 = location();
            if (alt4_1.isSuccess()) {
                var elem13_0 = matchLiteralCst("instanceof", false);
                if (elem13_0.isSuccess() && elem13_0.node != null) {
                    children.add(elem13_0.node);
                }
                if (elem13_0.isFailure()) {
                    restoreLocation(seqStart13);
                    alt4_1 = elem13_0;
                }
            }
            if (alt4_1.isSuccess()) {
                CstParseResult elem13_1 = null;
                var choiceStart16 = location();
                var savedChildren16 = new ArrayList<>(children);
                children.clear();
                children.addAll(savedChildren16);
                var trivia17 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var alt16_0 = parse_Pattern(trivia17);
                if (alt16_0.isSuccess() && alt16_0.node != null) {
                    children.add(alt16_0.node);
                }
                if (alt16_0.isSuccess()) {
                    elem13_1 = alt16_0;
                } else {
                    restoreLocation(choiceStart16);
                children.clear();
                children.addAll(savedChildren16);
                var trivia18 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var alt16_1 = parse_Type(trivia18);
                if (alt16_1.isSuccess() && alt16_1.node != null) {
                    children.add(alt16_1.node);
                }
                if (alt16_1.isSuccess()) {
                    elem13_1 = alt16_1;
                } else {
                    restoreLocation(choiceStart16);
                }
                }
                if (elem13_1 == null) {
                    children.clear();
                    children.addAll(savedChildren16);
                    elem13_1 = CstParseResult.failure("one of alternatives");
                }
                if (elem13_1.isFailure()) {
                    restoreLocation(seqStart13);
                    alt4_1 = elem13_1;
                }
            }
            if (alt4_1.isSuccess()) {
                alt4_1 = CstParseResult.success(null, substring(seqStart13.offset(), pos), location());
            }
            if (alt4_1.isSuccess()) {
                optElem2 = alt4_1;
            } else {
                restoreLocation(choiceStart4);
            }
            }
            if (optElem2 == null) {
                children.clear();
                children.addAll(savedChildren4);
                optElem2 = CstParseResult.failure("one of alternatives");
            }
            var elem0_1 = optElem2.isSuccess() ? optElem2 : CstParseResult.success(null, "", location());
            if (optElem2.isFailure()) {
                restoreLocation(optStart2);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_RELATIONAL, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Shift(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(85, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_Additive(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem2 = CstParseResult.success(null, "", location());
                var seqStart4 = location();
                if (zomElem2.isSuccess()) {
                    CstParseResult elem4_0 = null;
                    var choiceStart6 = location();
                    var savedChildren6 = new ArrayList<>(children);
                    children.clear();
                    children.addAll(savedChildren6);
                    CstParseResult alt6_0 = CstParseResult.success(null, "", location());
                    var seqStart7 = location();
                    if (alt6_0.isSuccess()) {
                        var notStart8 = location();
                        var savedChildrenNot8 = new ArrayList<>(children);
                        var notElem8 = matchLiteralCst("<<=", false);
                        restoreLocation(notStart8);
                        children.clear();
                        children.addAll(savedChildrenNot8);
                        var elem7_0 = notElem8.isSuccess() ? CstParseResult.failure("not match") : CstParseResult.success(null, "", location());
                        if (elem7_0.isFailure()) {
                            restoreLocation(seqStart7);
                            alt6_0 = elem7_0;
                        }
                    }
                    if (alt6_0.isSuccess()) {
                        if (!inTokenBoundary) skipWhitespace();
                        var elem7_1 = matchLiteralCst("<<", false);
                        if (elem7_1.isSuccess() && elem7_1.node != null) {
                            children.add(elem7_1.node);
                        }
                        if (elem7_1.isFailure()) {
                            restoreLocation(seqStart7);
                            alt6_0 = elem7_1;
                        }
                    }
                    if (alt6_0.isSuccess()) {
                        alt6_0 = CstParseResult.success(null, substring(seqStart7.offset(), pos), location());
                    }
                    if (alt6_0.isSuccess()) {
                        elem4_0 = alt6_0;
                    } else {
                        restoreLocation(choiceStart6);
                    children.clear();
                    children.addAll(savedChildren6);
                    CstParseResult alt6_1 = CstParseResult.success(null, "", location());
                    var seqStart11 = location();
                    if (alt6_1.isSuccess()) {
                        var notStart12 = location();
                        var savedChildrenNot12 = new ArrayList<>(children);
                        var notElem12 = matchLiteralCst(">>>=", false);
                        restoreLocation(notStart12);
                        children.clear();
                        children.addAll(savedChildrenNot12);
                        var elem11_0 = notElem12.isSuccess() ? CstParseResult.failure("not match") : CstParseResult.success(null, "", location());
                        if (elem11_0.isFailure()) {
                            restoreLocation(seqStart11);
                            alt6_1 = elem11_0;
                        }
                    }
                    if (alt6_1.isSuccess()) {
                        if (!inTokenBoundary) skipWhitespace();
                        var elem11_1 = matchLiteralCst(">>>", false);
                        if (elem11_1.isSuccess() && elem11_1.node != null) {
                            children.add(elem11_1.node);
                        }
                        if (elem11_1.isFailure()) {
                            restoreLocation(seqStart11);
                            alt6_1 = elem11_1;
                        }
                    }
                    if (alt6_1.isSuccess()) {
                        alt6_1 = CstParseResult.success(null, substring(seqStart11.offset(), pos), location());
                    }
                    if (alt6_1.isSuccess()) {
                        elem4_0 = alt6_1;
                    } else {
                        restoreLocation(choiceStart6);
                    children.clear();
                    children.addAll(savedChildren6);
                    CstParseResult alt6_2 = CstParseResult.success(null, "", location());
                    var seqStart15 = location();
                    if (alt6_2.isSuccess()) {
                        var notStart16 = location();
                        var savedChildrenNot16 = new ArrayList<>(children);
                        var notElem16 = matchLiteralCst(">>=", false);
                        restoreLocation(notStart16);
                        children.clear();
                        children.addAll(savedChildrenNot16);
                        var elem15_0 = notElem16.isSuccess() ? CstParseResult.failure("not match") : CstParseResult.success(null, "", location());
                        if (elem15_0.isFailure()) {
                            restoreLocation(seqStart15);
                            alt6_2 = elem15_0;
                        }
                    }
                    if (alt6_2.isSuccess()) {
                        if (!inTokenBoundary) skipWhitespace();
                        var notStart18 = location();
                        var savedChildrenNot18 = new ArrayList<>(children);
                        var notElem18 = matchLiteralCst(">>>=", false);
                        restoreLocation(notStart18);
                        children.clear();
                        children.addAll(savedChildrenNot18);
                        var elem15_1 = notElem18.isSuccess() ? CstParseResult.failure("not match") : CstParseResult.success(null, "", location());
                        if (elem15_1.isFailure()) {
                            restoreLocation(seqStart15);
                            alt6_2 = elem15_1;
                        }
                    }
                    if (alt6_2.isSuccess()) {
                        if (!inTokenBoundary) skipWhitespace();
                        var elem15_2 = matchLiteralCst(">>", false);
                        if (elem15_2.isSuccess() && elem15_2.node != null) {
                            children.add(elem15_2.node);
                        }
                        if (elem15_2.isFailure()) {
                            restoreLocation(seqStart15);
                            alt6_2 = elem15_2;
                        }
                    }
                    if (alt6_2.isSuccess()) {
                        alt6_2 = CstParseResult.success(null, substring(seqStart15.offset(), pos), location());
                    }
                    if (alt6_2.isSuccess()) {
                        elem4_0 = alt6_2;
                    } else {
                        restoreLocation(choiceStart6);
                    }
                    }
                    }
                    if (elem4_0 == null) {
                        children.clear();
                        children.addAll(savedChildren6);
                        elem4_0 = CstParseResult.failure("one of alternatives");
                    }
                    if (elem4_0.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_0;
                    }
                }
                if (zomElem2.isSuccess()) {
                    var trivia21 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem4_1 = parse_Additive(trivia21);
                    if (elem4_1.isSuccess() && elem4_1.node != null) {
                        children.add(elem4_1.node);
                    }
                    if (elem4_1.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_1;
                    }
                }
                if (zomElem2.isSuccess()) {
                    zomElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_SHIFT, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Additive(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(86, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_Multiplicative(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem2 = CstParseResult.success(null, "", location());
                var seqStart4 = location();
                if (zomElem2.isSuccess()) {
                    CstParseResult elem4_0 = null;
                    var choiceStart6 = location();
                    var savedChildren6 = new ArrayList<>(children);
                    children.clear();
                    children.addAll(savedChildren6);
                    CstParseResult alt6_0 = CstParseResult.success(null, "", location());
                    var seqStart7 = location();
                    if (alt6_0.isSuccess()) {
                        var notStart8 = location();
                        var savedChildrenNot8 = new ArrayList<>(children);
                        var notElem8 = matchLiteralCst("+=", false);
                        restoreLocation(notStart8);
                        children.clear();
                        children.addAll(savedChildrenNot8);
                        var elem7_0 = notElem8.isSuccess() ? CstParseResult.failure("not match") : CstParseResult.success(null, "", location());
                        if (elem7_0.isFailure()) {
                            restoreLocation(seqStart7);
                            alt6_0 = elem7_0;
                        }
                    }
                    if (alt6_0.isSuccess()) {
                        if (!inTokenBoundary) skipWhitespace();
                        var elem7_1 = matchLiteralCst("+", false);
                        if (elem7_1.isSuccess() && elem7_1.node != null) {
                            children.add(elem7_1.node);
                        }
                        if (elem7_1.isFailure()) {
                            restoreLocation(seqStart7);
                            alt6_0 = elem7_1;
                        }
                    }
                    if (alt6_0.isSuccess()) {
                        alt6_0 = CstParseResult.success(null, substring(seqStart7.offset(), pos), location());
                    }
                    if (alt6_0.isSuccess()) {
                        elem4_0 = alt6_0;
                    } else {
                        restoreLocation(choiceStart6);
                    children.clear();
                    children.addAll(savedChildren6);
                    CstParseResult alt6_1 = CstParseResult.success(null, "", location());
                    var seqStart11 = location();
                    if (alt6_1.isSuccess()) {
                        var notStart12 = location();
                        var savedChildrenNot12 = new ArrayList<>(children);
                        var notElem12 = matchLiteralCst("-=", false);
                        restoreLocation(notStart12);
                        children.clear();
                        children.addAll(savedChildrenNot12);
                        var elem11_0 = notElem12.isSuccess() ? CstParseResult.failure("not match") : CstParseResult.success(null, "", location());
                        if (elem11_0.isFailure()) {
                            restoreLocation(seqStart11);
                            alt6_1 = elem11_0;
                        }
                    }
                    if (alt6_1.isSuccess()) {
                        if (!inTokenBoundary) skipWhitespace();
                        var notStart14 = location();
                        var savedChildrenNot14 = new ArrayList<>(children);
                        var notElem14 = matchLiteralCst("->", false);
                        restoreLocation(notStart14);
                        children.clear();
                        children.addAll(savedChildrenNot14);
                        var elem11_1 = notElem14.isSuccess() ? CstParseResult.failure("not match") : CstParseResult.success(null, "", location());
                        if (elem11_1.isFailure()) {
                            restoreLocation(seqStart11);
                            alt6_1 = elem11_1;
                        }
                    }
                    if (alt6_1.isSuccess()) {
                        if (!inTokenBoundary) skipWhitespace();
                        var elem11_2 = matchLiteralCst("-", false);
                        if (elem11_2.isSuccess() && elem11_2.node != null) {
                            children.add(elem11_2.node);
                        }
                        if (elem11_2.isFailure()) {
                            restoreLocation(seqStart11);
                            alt6_1 = elem11_2;
                        }
                    }
                    if (alt6_1.isSuccess()) {
                        alt6_1 = CstParseResult.success(null, substring(seqStart11.offset(), pos), location());
                    }
                    if (alt6_1.isSuccess()) {
                        elem4_0 = alt6_1;
                    } else {
                        restoreLocation(choiceStart6);
                    }
                    }
                    if (elem4_0 == null) {
                        children.clear();
                        children.addAll(savedChildren6);
                        elem4_0 = CstParseResult.failure("one of alternatives");
                    }
                    if (elem4_0.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_0;
                    }
                }
                if (zomElem2.isSuccess()) {
                    var trivia17 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem4_1 = parse_Multiplicative(trivia17);
                    if (elem4_1.isSuccess() && elem4_1.node != null) {
                        children.add(elem4_1.node);
                    }
                    if (elem4_1.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_1;
                    }
                }
                if (zomElem2.isSuccess()) {
                    zomElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_ADDITIVE, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Multiplicative(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(87, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_Unary(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem2 = CstParseResult.success(null, "", location());
                var seqStart4 = location();
                if (zomElem2.isSuccess()) {
                    CstParseResult elem4_0 = null;
                    var choiceStart6 = location();
                    var savedChildren6 = new ArrayList<>(children);
                    children.clear();
                    children.addAll(savedChildren6);
                    CstParseResult alt6_0 = CstParseResult.success(null, "", location());
                    var seqStart7 = location();
                    if (alt6_0.isSuccess()) {
                        var notStart8 = location();
                        var savedChildrenNot8 = new ArrayList<>(children);
                        var notElem8 = matchLiteralCst("*=", false);
                        restoreLocation(notStart8);
                        children.clear();
                        children.addAll(savedChildrenNot8);
                        var elem7_0 = notElem8.isSuccess() ? CstParseResult.failure("not match") : CstParseResult.success(null, "", location());
                        if (elem7_0.isFailure()) {
                            restoreLocation(seqStart7);
                            alt6_0 = elem7_0;
                        }
                    }
                    if (alt6_0.isSuccess()) {
                        if (!inTokenBoundary) skipWhitespace();
                        var elem7_1 = matchLiteralCst("*", false);
                        if (elem7_1.isSuccess() && elem7_1.node != null) {
                            children.add(elem7_1.node);
                        }
                        if (elem7_1.isFailure()) {
                            restoreLocation(seqStart7);
                            alt6_0 = elem7_1;
                        }
                    }
                    if (alt6_0.isSuccess()) {
                        alt6_0 = CstParseResult.success(null, substring(seqStart7.offset(), pos), location());
                    }
                    if (alt6_0.isSuccess()) {
                        elem4_0 = alt6_0;
                    } else {
                        restoreLocation(choiceStart6);
                    children.clear();
                    children.addAll(savedChildren6);
                    CstParseResult alt6_1 = CstParseResult.success(null, "", location());
                    var seqStart11 = location();
                    if (alt6_1.isSuccess()) {
                        var notStart12 = location();
                        var savedChildrenNot12 = new ArrayList<>(children);
                        var notElem12 = matchLiteralCst("/=", false);
                        restoreLocation(notStart12);
                        children.clear();
                        children.addAll(savedChildrenNot12);
                        var elem11_0 = notElem12.isSuccess() ? CstParseResult.failure("not match") : CstParseResult.success(null, "", location());
                        if (elem11_0.isFailure()) {
                            restoreLocation(seqStart11);
                            alt6_1 = elem11_0;
                        }
                    }
                    if (alt6_1.isSuccess()) {
                        if (!inTokenBoundary) skipWhitespace();
                        var elem11_1 = matchLiteralCst("/", false);
                        if (elem11_1.isSuccess() && elem11_1.node != null) {
                            children.add(elem11_1.node);
                        }
                        if (elem11_1.isFailure()) {
                            restoreLocation(seqStart11);
                            alt6_1 = elem11_1;
                        }
                    }
                    if (alt6_1.isSuccess()) {
                        alt6_1 = CstParseResult.success(null, substring(seqStart11.offset(), pos), location());
                    }
                    if (alt6_1.isSuccess()) {
                        elem4_0 = alt6_1;
                    } else {
                        restoreLocation(choiceStart6);
                    children.clear();
                    children.addAll(savedChildren6);
                    CstParseResult alt6_2 = CstParseResult.success(null, "", location());
                    var seqStart15 = location();
                    if (alt6_2.isSuccess()) {
                        var notStart16 = location();
                        var savedChildrenNot16 = new ArrayList<>(children);
                        var notElem16 = matchLiteralCst("%=", false);
                        restoreLocation(notStart16);
                        children.clear();
                        children.addAll(savedChildrenNot16);
                        var elem15_0 = notElem16.isSuccess() ? CstParseResult.failure("not match") : CstParseResult.success(null, "", location());
                        if (elem15_0.isFailure()) {
                            restoreLocation(seqStart15);
                            alt6_2 = elem15_0;
                        }
                    }
                    if (alt6_2.isSuccess()) {
                        if (!inTokenBoundary) skipWhitespace();
                        var elem15_1 = matchLiteralCst("%", false);
                        if (elem15_1.isSuccess() && elem15_1.node != null) {
                            children.add(elem15_1.node);
                        }
                        if (elem15_1.isFailure()) {
                            restoreLocation(seqStart15);
                            alt6_2 = elem15_1;
                        }
                    }
                    if (alt6_2.isSuccess()) {
                        alt6_2 = CstParseResult.success(null, substring(seqStart15.offset(), pos), location());
                    }
                    if (alt6_2.isSuccess()) {
                        elem4_0 = alt6_2;
                    } else {
                        restoreLocation(choiceStart6);
                    }
                    }
                    }
                    if (elem4_0 == null) {
                        children.clear();
                        children.addAll(savedChildren6);
                        elem4_0 = CstParseResult.failure("one of alternatives");
                    }
                    if (elem4_0.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_0;
                    }
                }
                if (zomElem2.isSuccess()) {
                    var trivia19 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem4_1 = parse_Unary(trivia19);
                    if (elem4_1.isSuccess() && elem4_1.node != null) {
                        children.add(elem4_1.node);
                    }
                    if (elem4_1.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_1;
                    }
                }
                if (zomElem2.isSuccess()) {
                    zomElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_MULTIPLICATIVE, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Unary(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(88, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_0 = CstParseResult.success(null, "", location());
        var seqStart1 = location();
        if (alt0_0.isSuccess()) {
            CstParseResult elem1_0 = null;
            var choiceStart3 = location();
            var savedChildren3 = new ArrayList<>(children);
            children.clear();
            children.addAll(savedChildren3);
            var alt3_0 = matchLiteralCst("++", false);
            if (alt3_0.isSuccess() && alt3_0.node != null) {
                children.add(alt3_0.node);
            }
            if (alt3_0.isSuccess()) {
                elem1_0 = alt3_0;
            } else {
                restoreLocation(choiceStart3);
            children.clear();
            children.addAll(savedChildren3);
            var alt3_1 = matchLiteralCst("--", false);
            if (alt3_1.isSuccess() && alt3_1.node != null) {
                children.add(alt3_1.node);
            }
            if (alt3_1.isSuccess()) {
                elem1_0 = alt3_1;
            } else {
                restoreLocation(choiceStart3);
            children.clear();
            children.addAll(savedChildren3);
            var alt3_2 = matchLiteralCst("+", false);
            if (alt3_2.isSuccess() && alt3_2.node != null) {
                children.add(alt3_2.node);
            }
            if (alt3_2.isSuccess()) {
                elem1_0 = alt3_2;
            } else {
                restoreLocation(choiceStart3);
            children.clear();
            children.addAll(savedChildren3);
            var alt3_3 = matchLiteralCst("-", false);
            if (alt3_3.isSuccess() && alt3_3.node != null) {
                children.add(alt3_3.node);
            }
            if (alt3_3.isSuccess()) {
                elem1_0 = alt3_3;
            } else {
                restoreLocation(choiceStart3);
            children.clear();
            children.addAll(savedChildren3);
            var alt3_4 = matchLiteralCst("!", false);
            if (alt3_4.isSuccess() && alt3_4.node != null) {
                children.add(alt3_4.node);
            }
            if (alt3_4.isSuccess()) {
                elem1_0 = alt3_4;
            } else {
                restoreLocation(choiceStart3);
            children.clear();
            children.addAll(savedChildren3);
            var alt3_5 = matchLiteralCst("~", false);
            if (alt3_5.isSuccess() && alt3_5.node != null) {
                children.add(alt3_5.node);
            }
            if (alt3_5.isSuccess()) {
                elem1_0 = alt3_5;
            } else {
                restoreLocation(choiceStart3);
            }
            }
            }
            }
            }
            }
            if (elem1_0 == null) {
                children.clear();
                children.addAll(savedChildren3);
                elem1_0 = CstParseResult.failure("one of alternatives");
            }
            if (elem1_0.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_0;
            }
        }
        if (alt0_0.isSuccess()) {
            var trivia10 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem1_1 = parse_Unary(trivia10);
            if (elem1_1.isSuccess() && elem1_1.node != null) {
                children.add(elem1_1.node);
            }
            if (elem1_1.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_1;
            }
        }
        if (alt0_0.isSuccess()) {
            alt0_0 = CstParseResult.success(null, substring(seqStart1.offset(), pos), location());
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_1 = CstParseResult.success(null, "", location());
        var seqStart11 = location();
        if (alt0_1.isSuccess()) {
            var elem11_0 = matchLiteralCst("(", false);
            if (elem11_0.isSuccess() && elem11_0.node != null) {
                children.add(elem11_0.node);
            }
            if (elem11_0.isFailure()) {
                restoreLocation(seqStart11);
                alt0_1 = elem11_0;
            }
        }
        if (alt0_1.isSuccess()) {
            var trivia13 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem11_1 = parse_Type(trivia13);
            if (elem11_1.isSuccess() && elem11_1.node != null) {
                children.add(elem11_1.node);
            }
            if (elem11_1.isFailure()) {
                restoreLocation(seqStart11);
                alt0_1 = elem11_1;
            }
        }
        if (alt0_1.isSuccess()) {
            CstParseResult elem11_2 = CstParseResult.success(null, "", location());
            var zomStart14 = location();
            while (true) {
                var beforeLoc14 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem14 = CstParseResult.success(null, "", location());
                var seqStart16 = location();
                if (zomElem14.isSuccess()) {
                    var elem16_0 = matchLiteralCst("&", false);
                    if (elem16_0.isSuccess() && elem16_0.node != null) {
                        children.add(elem16_0.node);
                    }
                    if (elem16_0.isFailure()) {
                        restoreLocation(seqStart16);
                        zomElem14 = elem16_0;
                    }
                }
                if (zomElem14.isSuccess()) {
                    var trivia18 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem16_1 = parse_Type(trivia18);
                    if (elem16_1.isSuccess() && elem16_1.node != null) {
                        children.add(elem16_1.node);
                    }
                    if (elem16_1.isFailure()) {
                        restoreLocation(seqStart16);
                        zomElem14 = elem16_1;
                    }
                }
                if (zomElem14.isSuccess()) {
                    zomElem14 = CstParseResult.success(null, substring(seqStart16.offset(), pos), location());
                }
                if (zomElem14.isFailure() || location().offset() == beforeLoc14.offset()) {
                    restoreLocation(beforeLoc14);
                    break;
                }
            }
            elem11_2 = CstParseResult.success(null, substring(zomStart14.offset(), pos), location());
            if (elem11_2.isFailure()) {
                restoreLocation(seqStart11);
                alt0_1 = elem11_2;
            }
        }
        if (alt0_1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem11_3 = matchLiteralCst(")", false);
            if (elem11_3.isSuccess() && elem11_3.node != null) {
                children.add(elem11_3.node);
            }
            if (elem11_3.isFailure()) {
                restoreLocation(seqStart11);
                alt0_1 = elem11_3;
            }
        }
        if (alt0_1.isSuccess()) {
            var trivia20 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem11_4 = parse_Unary(trivia20);
            if (elem11_4.isSuccess() && elem11_4.node != null) {
                children.add(elem11_4.node);
            }
            if (elem11_4.isFailure()) {
                restoreLocation(seqStart11);
                alt0_1 = elem11_4;
            }
        }
        if (alt0_1.isSuccess()) {
            alt0_1 = CstParseResult.success(null, substring(seqStart11.offset(), pos), location());
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia21 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_2 = parse_Postfix(trivia21);
        if (alt0_2.isSuccess() && alt0_2.node != null) {
            children.add(alt0_2.node);
        }
        if (alt0_2.isSuccess()) {
            result = alt0_2;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_UNARY, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Postfix(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(89, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_Primary(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem2 = parse_PostOp(trivia3);
                if (zomElem2.isSuccess() && zomElem2.node != null) {
                    children.add(zomElem2.node);
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_POSTFIX, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_PostOp(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(90, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_0 = CstParseResult.success(null, "", location());
        var seqStart1 = location();
        if (alt0_0.isSuccess()) {
            var elem1_0 = matchLiteralCst(".", false);
            if (elem1_0.isSuccess() && elem1_0.node != null) {
                children.add(elem1_0.node);
            }
            if (elem1_0.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_0;
            }
        }
        if (alt0_0.isSuccess()) {
            var optStart3 = location();
            var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem3 = parse_TypeArgs(trivia4);
            if (optElem3.isSuccess() && optElem3.node != null) {
                children.add(optElem3.node);
            }
            var elem1_1 = optElem3.isSuccess() ? optElem3 : CstParseResult.success(null, "", location());
            if (optElem3.isFailure()) {
                restoreLocation(optStart3);
            }
            if (elem1_1.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_1;
            }
        }
        if (alt0_0.isSuccess()) {
            var trivia5 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem1_2 = parse_Identifier(trivia5);
            if (elem1_2.isSuccess() && elem1_2.node != null) {
                children.add(elem1_2.node);
            }
            if (elem1_2.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_2;
            }
        }
        if (alt0_0.isSuccess()) {
            var optStart6 = location();
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult optElem6 = CstParseResult.success(null, "", location());
            var seqStart8 = location();
            if (optElem6.isSuccess()) {
                var elem8_0 = matchLiteralCst("(", false);
                if (elem8_0.isSuccess() && elem8_0.node != null) {
                    children.add(elem8_0.node);
                }
                if (elem8_0.isFailure()) {
                    restoreLocation(seqStart8);
                    optElem6 = elem8_0;
                }
            }
            if (optElem6.isSuccess()) {
                var optStart10 = location();
                var trivia11 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var optElem10 = parse_Args(trivia11);
                if (optElem10.isSuccess() && optElem10.node != null) {
                    children.add(optElem10.node);
                }
                var elem8_1 = optElem10.isSuccess() ? optElem10 : CstParseResult.success(null, "", location());
                if (optElem10.isFailure()) {
                    restoreLocation(optStart10);
                }
                if (elem8_1.isFailure()) {
                    restoreLocation(seqStart8);
                    optElem6 = elem8_1;
                }
            }
            if (optElem6.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var elem8_2 = matchLiteralCst(")", false);
                if (elem8_2.isSuccess() && elem8_2.node != null) {
                    children.add(elem8_2.node);
                }
                if (elem8_2.isFailure()) {
                    restoreLocation(seqStart8);
                    optElem6 = elem8_2;
                }
            }
            if (optElem6.isSuccess()) {
                optElem6 = CstParseResult.success(null, substring(seqStart8.offset(), pos), location());
            }
            var elem1_3 = optElem6.isSuccess() ? optElem6 : CstParseResult.success(null, "", location());
            if (optElem6.isFailure()) {
                restoreLocation(optStart6);
            }
            if (elem1_3.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_3;
            }
        }
        if (alt0_0.isSuccess()) {
            alt0_0 = CstParseResult.success(null, substring(seqStart1.offset(), pos), location());
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_1 = CstParseResult.success(null, "", location());
        var seqStart13 = location();
        if (alt0_1.isSuccess()) {
            var elem13_0 = matchLiteralCst(".", false);
            if (elem13_0.isSuccess() && elem13_0.node != null) {
                children.add(elem13_0.node);
            }
            if (elem13_0.isFailure()) {
                restoreLocation(seqStart13);
                alt0_1 = elem13_0;
            }
        }
        if (alt0_1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem13_1 = matchLiteralCst("class", false);
            if (elem13_1.isSuccess() && elem13_1.node != null) {
                children.add(elem13_1.node);
            }
            if (elem13_1.isFailure()) {
                restoreLocation(seqStart13);
                alt0_1 = elem13_1;
            }
        }
        if (alt0_1.isSuccess()) {
            alt0_1 = CstParseResult.success(null, substring(seqStart13.offset(), pos), location());
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_2 = CstParseResult.success(null, "", location());
        var seqStart16 = location();
        if (alt0_2.isSuccess()) {
            var elem16_0 = matchLiteralCst(".", false);
            if (elem16_0.isSuccess() && elem16_0.node != null) {
                children.add(elem16_0.node);
            }
            if (elem16_0.isFailure()) {
                restoreLocation(seqStart16);
                alt0_2 = elem16_0;
            }
        }
        if (alt0_2.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem16_1 = matchLiteralCst("this", false);
            if (elem16_1.isSuccess() && elem16_1.node != null) {
                children.add(elem16_1.node);
            }
            if (elem16_1.isFailure()) {
                restoreLocation(seqStart16);
                alt0_2 = elem16_1;
            }
        }
        if (alt0_2.isSuccess()) {
            alt0_2 = CstParseResult.success(null, substring(seqStart16.offset(), pos), location());
        }
        if (alt0_2.isSuccess()) {
            result = alt0_2;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_3 = CstParseResult.success(null, "", location());
        var seqStart19 = location();
        if (alt0_3.isSuccess()) {
            var elem19_0 = matchLiteralCst("[", false);
            if (elem19_0.isSuccess() && elem19_0.node != null) {
                children.add(elem19_0.node);
            }
            if (elem19_0.isFailure()) {
                restoreLocation(seqStart19);
                alt0_3 = elem19_0;
            }
        }
        if (alt0_3.isSuccess()) {
            var trivia21 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem19_1 = parse_Expr(trivia21);
            if (elem19_1.isSuccess() && elem19_1.node != null) {
                children.add(elem19_1.node);
            }
            if (elem19_1.isFailure()) {
                restoreLocation(seqStart19);
                alt0_3 = elem19_1;
            }
        }
        if (alt0_3.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem19_2 = matchLiteralCst("]", false);
            if (elem19_2.isSuccess() && elem19_2.node != null) {
                children.add(elem19_2.node);
            }
            if (elem19_2.isFailure()) {
                restoreLocation(seqStart19);
                alt0_3 = elem19_2;
            }
        }
        if (alt0_3.isSuccess()) {
            alt0_3 = CstParseResult.success(null, substring(seqStart19.offset(), pos), location());
        }
        if (alt0_3.isSuccess()) {
            result = alt0_3;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_4 = CstParseResult.success(null, "", location());
        var seqStart23 = location();
        if (alt0_4.isSuccess()) {
            var elem23_0 = matchLiteralCst("(", false);
            if (elem23_0.isSuccess() && elem23_0.node != null) {
                children.add(elem23_0.node);
            }
            if (elem23_0.isFailure()) {
                restoreLocation(seqStart23);
                alt0_4 = elem23_0;
            }
        }
        if (alt0_4.isSuccess()) {
            var optStart25 = location();
            var trivia26 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem25 = parse_Args(trivia26);
            if (optElem25.isSuccess() && optElem25.node != null) {
                children.add(optElem25.node);
            }
            var elem23_1 = optElem25.isSuccess() ? optElem25 : CstParseResult.success(null, "", location());
            if (optElem25.isFailure()) {
                restoreLocation(optStart25);
            }
            if (elem23_1.isFailure()) {
                restoreLocation(seqStart23);
                alt0_4 = elem23_1;
            }
        }
        if (alt0_4.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem23_2 = matchLiteralCst(")", false);
            if (elem23_2.isSuccess() && elem23_2.node != null) {
                children.add(elem23_2.node);
            }
            if (elem23_2.isFailure()) {
                restoreLocation(seqStart23);
                alt0_4 = elem23_2;
            }
        }
        if (alt0_4.isSuccess()) {
            alt0_4 = CstParseResult.success(null, substring(seqStart23.offset(), pos), location());
        }
        if (alt0_4.isSuccess()) {
            result = alt0_4;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_5 = matchLiteralCst("++", false);
        if (alt0_5.isSuccess() && alt0_5.node != null) {
            children.add(alt0_5.node);
        }
        if (alt0_5.isSuccess()) {
            result = alt0_5;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_6 = matchLiteralCst("--", false);
        if (alt0_6.isSuccess() && alt0_6.node != null) {
            children.add(alt0_6.node);
        }
        if (alt0_6.isSuccess()) {
            result = alt0_6;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_7 = CstParseResult.success(null, "", location());
        var seqStart30 = location();
        if (alt0_7.isSuccess()) {
            var elem30_0 = matchLiteralCst("::", false);
            if (elem30_0.isSuccess() && elem30_0.node != null) {
                children.add(elem30_0.node);
            }
            if (elem30_0.isFailure()) {
                restoreLocation(seqStart30);
                alt0_7 = elem30_0;
            }
        }
        if (alt0_7.isSuccess()) {
            var optStart32 = location();
            var trivia33 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem32 = parse_TypeArgs(trivia33);
            if (optElem32.isSuccess() && optElem32.node != null) {
                children.add(optElem32.node);
            }
            var elem30_1 = optElem32.isSuccess() ? optElem32 : CstParseResult.success(null, "", location());
            if (optElem32.isFailure()) {
                restoreLocation(optStart32);
            }
            if (elem30_1.isFailure()) {
                restoreLocation(seqStart30);
                alt0_7 = elem30_1;
            }
        }
        if (alt0_7.isSuccess()) {
            CstParseResult elem30_2 = null;
            var choiceStart35 = location();
            var savedChildren35 = new ArrayList<>(children);
            children.clear();
            children.addAll(savedChildren35);
            var trivia36 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var alt35_0 = parse_Identifier(trivia36);
            if (alt35_0.isSuccess() && alt35_0.node != null) {
                children.add(alt35_0.node);
            }
            if (alt35_0.isSuccess()) {
                elem30_2 = alt35_0;
            } else {
                restoreLocation(choiceStart35);
            children.clear();
            children.addAll(savedChildren35);
            var alt35_1 = matchLiteralCst("new", false);
            if (alt35_1.isSuccess() && alt35_1.node != null) {
                children.add(alt35_1.node);
            }
            if (alt35_1.isSuccess()) {
                elem30_2 = alt35_1;
            } else {
                restoreLocation(choiceStart35);
            }
            }
            if (elem30_2 == null) {
                children.clear();
                children.addAll(savedChildren35);
                elem30_2 = CstParseResult.failure("one of alternatives");
            }
            if (elem30_2.isFailure()) {
                restoreLocation(seqStart30);
                alt0_7 = elem30_2;
            }
        }
        if (alt0_7.isSuccess()) {
            alt0_7 = CstParseResult.success(null, substring(seqStart30.offset(), pos), location());
        }
        if (alt0_7.isSuccess()) {
            result = alt0_7;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        }
        }
        }
        }
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_POST_OP, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Primary(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(91, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_0 = parse_Literal(trivia1);
        if (alt0_0.isSuccess() && alt0_0.node != null) {
            children.add(alt0_0.node);
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_1 = matchLiteralCst("this", false);
        if (alt0_1.isSuccess() && alt0_1.node != null) {
            children.add(alt0_1.node);
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_2 = matchLiteralCst("super", false);
        if (alt0_2.isSuccess() && alt0_2.node != null) {
            children.add(alt0_2.node);
        }
        if (alt0_2.isSuccess()) {
            result = alt0_2;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_3 = CstParseResult.success(null, "", location());
        var seqStart4 = location();
        if (alt0_3.isSuccess()) {
            var elem4_0 = matchLiteralCst("new", false);
            if (elem4_0.isSuccess() && elem4_0.node != null) {
                children.add(elem4_0.node);
            }
            if (elem4_0.isFailure()) {
                restoreLocation(seqStart4);
                alt0_3 = elem4_0;
            }
        }
        if (alt0_3.isSuccess()) {
            var optStart6 = location();
            var trivia7 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem6 = parse_TypeArgs(trivia7);
            if (optElem6.isSuccess() && optElem6.node != null) {
                children.add(optElem6.node);
            }
            var elem4_1 = optElem6.isSuccess() ? optElem6 : CstParseResult.success(null, "", location());
            if (optElem6.isFailure()) {
                restoreLocation(optStart6);
            }
            if (elem4_1.isFailure()) {
                restoreLocation(seqStart4);
                alt0_3 = elem4_1;
            }
        }
        if (alt0_3.isSuccess()) {
            var trivia8 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem4_2 = parse_Type(trivia8);
            if (elem4_2.isSuccess() && elem4_2.node != null) {
                children.add(elem4_2.node);
            }
            if (elem4_2.isFailure()) {
                restoreLocation(seqStart4);
                alt0_3 = elem4_2;
            }
        }
        if (alt0_3.isSuccess()) {
            CstParseResult elem4_3 = null;
            var choiceStart10 = location();
            var savedChildren10 = new ArrayList<>(children);
            children.clear();
            children.addAll(savedChildren10);
            CstParseResult alt10_0 = CstParseResult.success(null, "", location());
            var seqStart11 = location();
            if (alt10_0.isSuccess()) {
                var elem11_0 = matchLiteralCst("(", false);
                if (elem11_0.isSuccess() && elem11_0.node != null) {
                    children.add(elem11_0.node);
                }
                if (elem11_0.isFailure()) {
                    restoreLocation(seqStart11);
                    alt10_0 = elem11_0;
                }
            }
            if (alt10_0.isSuccess()) {
                var optStart13 = location();
                var trivia14 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var optElem13 = parse_Args(trivia14);
                if (optElem13.isSuccess() && optElem13.node != null) {
                    children.add(optElem13.node);
                }
                var elem11_1 = optElem13.isSuccess() ? optElem13 : CstParseResult.success(null, "", location());
                if (optElem13.isFailure()) {
                    restoreLocation(optStart13);
                }
                if (elem11_1.isFailure()) {
                    restoreLocation(seqStart11);
                    alt10_0 = elem11_1;
                }
            }
            if (alt10_0.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var elem11_2 = matchLiteralCst(")", false);
                if (elem11_2.isSuccess() && elem11_2.node != null) {
                    children.add(elem11_2.node);
                }
                if (elem11_2.isFailure()) {
                    restoreLocation(seqStart11);
                    alt10_0 = elem11_2;
                }
            }
            if (alt10_0.isSuccess()) {
                var optStart16 = location();
                var trivia17 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var optElem16 = parse_ClassBody(trivia17);
                if (optElem16.isSuccess() && optElem16.node != null) {
                    children.add(optElem16.node);
                }
                var elem11_3 = optElem16.isSuccess() ? optElem16 : CstParseResult.success(null, "", location());
                if (optElem16.isFailure()) {
                    restoreLocation(optStart16);
                }
                if (elem11_3.isFailure()) {
                    restoreLocation(seqStart11);
                    alt10_0 = elem11_3;
                }
            }
            if (alt10_0.isSuccess()) {
                alt10_0 = CstParseResult.success(null, substring(seqStart11.offset(), pos), location());
            }
            if (alt10_0.isSuccess()) {
                elem4_3 = alt10_0;
            } else {
                restoreLocation(choiceStart10);
            children.clear();
            children.addAll(savedChildren10);
            CstParseResult alt10_1 = CstParseResult.success(null, "", location());
            var seqStart18 = location();
            if (alt10_1.isSuccess()) {
                var optStart19 = location();
                var trivia20 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var optElem19 = parse_Dims(trivia20);
                if (optElem19.isSuccess() && optElem19.node != null) {
                    children.add(optElem19.node);
                }
                var elem18_0 = optElem19.isSuccess() ? optElem19 : CstParseResult.success(null, "", location());
                if (optElem19.isFailure()) {
                    restoreLocation(optStart19);
                }
                if (elem18_0.isFailure()) {
                    restoreLocation(seqStart18);
                    alt10_1 = elem18_0;
                }
            }
            if (alt10_1.isSuccess()) {
                var optStart21 = location();
                var trivia22 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var optElem21 = parse_VarInit(trivia22);
                if (optElem21.isSuccess() && optElem21.node != null) {
                    children.add(optElem21.node);
                }
                var elem18_1 = optElem21.isSuccess() ? optElem21 : CstParseResult.success(null, "", location());
                if (optElem21.isFailure()) {
                    restoreLocation(optStart21);
                }
                if (elem18_1.isFailure()) {
                    restoreLocation(seqStart18);
                    alt10_1 = elem18_1;
                }
            }
            if (alt10_1.isSuccess()) {
                alt10_1 = CstParseResult.success(null, substring(seqStart18.offset(), pos), location());
            }
            if (alt10_1.isSuccess()) {
                elem4_3 = alt10_1;
            } else {
                restoreLocation(choiceStart10);
            }
            }
            if (elem4_3 == null) {
                children.clear();
                children.addAll(savedChildren10);
                elem4_3 = CstParseResult.failure("one of alternatives");
            }
            if (elem4_3.isFailure()) {
                restoreLocation(seqStart4);
                alt0_3 = elem4_3;
            }
        }
        if (alt0_3.isSuccess()) {
            alt0_3 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
        }
        if (alt0_3.isSuccess()) {
            result = alt0_3;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_4 = CstParseResult.success(null, "", location());
        var seqStart23 = location();
        if (alt0_4.isSuccess()) {
            var elem23_0 = matchLiteralCst("switch", false);
            if (elem23_0.isSuccess() && elem23_0.node != null) {
                children.add(elem23_0.node);
            }
            if (elem23_0.isFailure()) {
                restoreLocation(seqStart23);
                alt0_4 = elem23_0;
            }
        }
        if (alt0_4.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem23_1 = matchLiteralCst("(", false);
            if (elem23_1.isSuccess() && elem23_1.node != null) {
                children.add(elem23_1.node);
            }
            if (elem23_1.isFailure()) {
                restoreLocation(seqStart23);
                alt0_4 = elem23_1;
            }
        }
        if (alt0_4.isSuccess()) {
            var trivia26 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem23_2 = parse_Expr(trivia26);
            if (elem23_2.isSuccess() && elem23_2.node != null) {
                children.add(elem23_2.node);
            }
            if (elem23_2.isFailure()) {
                restoreLocation(seqStart23);
                alt0_4 = elem23_2;
            }
        }
        if (alt0_4.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem23_3 = matchLiteralCst(")", false);
            if (elem23_3.isSuccess() && elem23_3.node != null) {
                children.add(elem23_3.node);
            }
            if (elem23_3.isFailure()) {
                restoreLocation(seqStart23);
                alt0_4 = elem23_3;
            }
        }
        if (alt0_4.isSuccess()) {
            var trivia28 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem23_4 = parse_SwitchBlock(trivia28);
            if (elem23_4.isSuccess() && elem23_4.node != null) {
                children.add(elem23_4.node);
            }
            if (elem23_4.isFailure()) {
                restoreLocation(seqStart23);
                alt0_4 = elem23_4;
            }
        }
        if (alt0_4.isSuccess()) {
            alt0_4 = CstParseResult.success(null, substring(seqStart23.offset(), pos), location());
        }
        if (alt0_4.isSuccess()) {
            result = alt0_4;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia29 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_5 = parse_Lambda(trivia29);
        if (alt0_5.isSuccess() && alt0_5.node != null) {
            children.add(alt0_5.node);
        }
        if (alt0_5.isSuccess()) {
            result = alt0_5;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_6 = CstParseResult.success(null, "", location());
        var seqStart30 = location();
        if (alt0_6.isSuccess()) {
            var elem30_0 = matchLiteralCst("(", false);
            if (elem30_0.isSuccess() && elem30_0.node != null) {
                children.add(elem30_0.node);
            }
            if (elem30_0.isFailure()) {
                restoreLocation(seqStart30);
                alt0_6 = elem30_0;
            }
        }
        if (alt0_6.isSuccess()) {
            var trivia32 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem30_1 = parse_Expr(trivia32);
            if (elem30_1.isSuccess() && elem30_1.node != null) {
                children.add(elem30_1.node);
            }
            if (elem30_1.isFailure()) {
                restoreLocation(seqStart30);
                alt0_6 = elem30_1;
            }
        }
        if (alt0_6.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem30_2 = matchLiteralCst(")", false);
            if (elem30_2.isSuccess() && elem30_2.node != null) {
                children.add(elem30_2.node);
            }
            if (elem30_2.isFailure()) {
                restoreLocation(seqStart30);
                alt0_6 = elem30_2;
            }
        }
        if (alt0_6.isSuccess()) {
            alt0_6 = CstParseResult.success(null, substring(seqStart30.offset(), pos), location());
        }
        if (alt0_6.isSuccess()) {
            result = alt0_6;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia34 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_7 = parse_QualifiedName(trivia34);
        if (alt0_7.isSuccess() && alt0_7.node != null) {
            children.add(alt0_7.node);
        }
        if (alt0_7.isSuccess()) {
            result = alt0_7;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        }
        }
        }
        }
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_PRIMARY, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Lambda(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(92, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_LambdaParams(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem0_1 = matchLiteralCst("->", false);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_2 = null;
            var choiceStart4 = location();
            var savedChildren4 = new ArrayList<>(children);
            children.clear();
            children.addAll(savedChildren4);
            var trivia5 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var alt4_0 = parse_Expr(trivia5);
            if (alt4_0.isSuccess() && alt4_0.node != null) {
                children.add(alt4_0.node);
            }
            if (alt4_0.isSuccess()) {
                elem0_2 = alt4_0;
            } else {
                restoreLocation(choiceStart4);
            children.clear();
            children.addAll(savedChildren4);
            var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var alt4_1 = parse_Block(trivia6);
            if (alt4_1.isSuccess() && alt4_1.node != null) {
                children.add(alt4_1.node);
            }
            if (alt4_1.isSuccess()) {
                elem0_2 = alt4_1;
            } else {
                restoreLocation(choiceStart4);
            }
            }
            if (elem0_2 == null) {
                children.clear();
                children.addAll(savedChildren4);
                elem0_2 = CstParseResult.failure("one of alternatives");
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_LAMBDA, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_LambdaParams(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(93, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_0 = parse_Identifier(trivia1);
        if (alt0_0.isSuccess() && alt0_0.node != null) {
            children.add(alt0_0.node);
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_1 = matchLiteralCst("_", false);
        if (alt0_1.isSuccess() && alt0_1.node != null) {
            children.add(alt0_1.node);
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_2 = CstParseResult.success(null, "", location());
        var seqStart3 = location();
        if (alt0_2.isSuccess()) {
            var elem3_0 = matchLiteralCst("(", false);
            if (elem3_0.isSuccess() && elem3_0.node != null) {
                children.add(elem3_0.node);
            }
            if (elem3_0.isFailure()) {
                restoreLocation(seqStart3);
                alt0_2 = elem3_0;
            }
        }
        if (alt0_2.isSuccess()) {
            var optStart5 = location();
            var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem5 = parse_LambdaParam(trivia6);
            if (optElem5.isSuccess() && optElem5.node != null) {
                children.add(optElem5.node);
            }
            var elem3_1 = optElem5.isSuccess() ? optElem5 : CstParseResult.success(null, "", location());
            if (optElem5.isFailure()) {
                restoreLocation(optStart5);
            }
            if (elem3_1.isFailure()) {
                restoreLocation(seqStart3);
                alt0_2 = elem3_1;
            }
        }
        if (alt0_2.isSuccess()) {
            CstParseResult elem3_2 = CstParseResult.success(null, "", location());
            var zomStart7 = location();
            while (true) {
                var beforeLoc7 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem7 = CstParseResult.success(null, "", location());
                var seqStart9 = location();
                if (zomElem7.isSuccess()) {
                    var elem9_0 = matchLiteralCst(",", false);
                    if (elem9_0.isSuccess() && elem9_0.node != null) {
                        children.add(elem9_0.node);
                    }
                    if (elem9_0.isFailure()) {
                        restoreLocation(seqStart9);
                        zomElem7 = elem9_0;
                    }
                }
                if (zomElem7.isSuccess()) {
                    var trivia11 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem9_1 = parse_LambdaParam(trivia11);
                    if (elem9_1.isSuccess() && elem9_1.node != null) {
                        children.add(elem9_1.node);
                    }
                    if (elem9_1.isFailure()) {
                        restoreLocation(seqStart9);
                        zomElem7 = elem9_1;
                    }
                }
                if (zomElem7.isSuccess()) {
                    zomElem7 = CstParseResult.success(null, substring(seqStart9.offset(), pos), location());
                }
                if (zomElem7.isFailure() || location().offset() == beforeLoc7.offset()) {
                    restoreLocation(beforeLoc7);
                    break;
                }
            }
            elem3_2 = CstParseResult.success(null, substring(zomStart7.offset(), pos), location());
            if (elem3_2.isFailure()) {
                restoreLocation(seqStart3);
                alt0_2 = elem3_2;
            }
        }
        if (alt0_2.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem3_3 = matchLiteralCst(")", false);
            if (elem3_3.isSuccess() && elem3_3.node != null) {
                children.add(elem3_3.node);
            }
            if (elem3_3.isFailure()) {
                restoreLocation(seqStart3);
                alt0_2 = elem3_3;
            }
        }
        if (alt0_2.isSuccess()) {
            alt0_2 = CstParseResult.success(null, substring(seqStart3.offset(), pos), location());
        }
        if (alt0_2.isSuccess()) {
            result = alt0_2;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_LAMBDA_PARAMS, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_LambdaParam(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(94, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            CstParseResult elem0_0 = CstParseResult.success(null, "", location());
            var zomStart1 = location();
            while (true) {
                var beforeLoc1 = location();
                var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem1 = parse_Annotation(trivia2);
                if (zomElem1.isSuccess() && zomElem1.node != null) {
                    children.add(zomElem1.node);
                }
                if (zomElem1.isFailure() || location().offset() == beforeLoc1.offset()) {
                    restoreLocation(beforeLoc1);
                    break;
                }
            }
            elem0_0 = CstParseResult.success(null, substring(zomStart1.offset(), pos), location());
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart3 = location();
            while (true) {
                var beforeLoc3 = location();
                var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem3 = parse_Modifier(trivia4);
                if (zomElem3.isSuccess() && zomElem3.node != null) {
                    children.add(zomElem3.node);
                }
                if (zomElem3.isFailure() || location().offset() == beforeLoc3.offset()) {
                    restoreLocation(beforeLoc3);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart3.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            var optStart5 = location();
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult optElem5 = CstParseResult.success(null, "", location());
            var seqStart7 = location();
            if (optElem5.isSuccess()) {
                CstParseResult elem7_0 = null;
                var choiceStart9 = location();
                var savedChildren9 = new ArrayList<>(children);
                children.clear();
                children.addAll(savedChildren9);
                var alt9_0 = matchLiteralCst("var", false);
                if (alt9_0.isSuccess() && alt9_0.node != null) {
                    children.add(alt9_0.node);
                }
                if (alt9_0.isSuccess()) {
                    elem7_0 = alt9_0;
                } else {
                    restoreLocation(choiceStart9);
                children.clear();
                children.addAll(savedChildren9);
                var trivia11 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var alt9_1 = parse_Type(trivia11);
                if (alt9_1.isSuccess() && alt9_1.node != null) {
                    children.add(alt9_1.node);
                }
                if (alt9_1.isSuccess()) {
                    elem7_0 = alt9_1;
                } else {
                    restoreLocation(choiceStart9);
                }
                }
                if (elem7_0 == null) {
                    children.clear();
                    children.addAll(savedChildren9);
                    elem7_0 = CstParseResult.failure("one of alternatives");
                }
                if (elem7_0.isFailure()) {
                    restoreLocation(seqStart7);
                    optElem5 = elem7_0;
                }
            }
            if (optElem5.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var andStart12 = location();
                var savedChildrenAnd12 = new ArrayList<>(children);
                CstParseResult andElem12 = null;
                var choiceStart14 = location();
                var alt14_0 = matchLiteralCst("...", false);
                if (alt14_0.isSuccess()) {
                    andElem12 = alt14_0;
                } else {
                    restoreLocation(choiceStart14);
                var trivia16 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var alt14_1 = parse_Identifier(trivia16);
                if (alt14_1.isSuccess()) {
                    andElem12 = alt14_1;
                } else {
                    restoreLocation(choiceStart14);
                var alt14_2 = matchLiteralCst("_", false);
                if (alt14_2.isSuccess()) {
                    andElem12 = alt14_2;
                } else {
                    restoreLocation(choiceStart14);
                }
                }
                }
                if (andElem12 == null) {
                    andElem12 = CstParseResult.failure("one of alternatives");
                }
                restoreLocation(andStart12);
                children.clear();
                children.addAll(savedChildrenAnd12);
                var elem7_1 = andElem12.isSuccess() ? CstParseResult.success(null, "", location()) : andElem12;
                if (elem7_1.isFailure()) {
                    restoreLocation(seqStart7);
                    optElem5 = elem7_1;
                }
            }
            if (optElem5.isSuccess()) {
                optElem5 = CstParseResult.success(null, substring(seqStart7.offset(), pos), location());
            }
            var elem0_2 = optElem5.isSuccess() ? optElem5 : CstParseResult.success(null, "", location());
            if (optElem5.isFailure()) {
                restoreLocation(optStart5);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            var optStart18 = location();
            if (!inTokenBoundary) skipWhitespace();
            var optElem18 = matchLiteralCst("...", false);
            if (optElem18.isSuccess() && optElem18.node != null) {
                children.add(optElem18.node);
            }
            var elem0_3 = optElem18.isSuccess() ? optElem18 : CstParseResult.success(null, "", location());
            if (optElem18.isFailure()) {
                restoreLocation(optStart18);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_4 = null;
            var choiceStart21 = location();
            var savedChildren21 = new ArrayList<>(children);
            children.clear();
            children.addAll(savedChildren21);
            var trivia22 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var alt21_0 = parse_Identifier(trivia22);
            if (alt21_0.isSuccess() && alt21_0.node != null) {
                children.add(alt21_0.node);
            }
            if (alt21_0.isSuccess()) {
                elem0_4 = alt21_0;
            } else {
                restoreLocation(choiceStart21);
            children.clear();
            children.addAll(savedChildren21);
            var alt21_1 = matchLiteralCst("_", false);
            if (alt21_1.isSuccess() && alt21_1.node != null) {
                children.add(alt21_1.node);
            }
            if (alt21_1.isSuccess()) {
                elem0_4 = alt21_1;
            } else {
                restoreLocation(choiceStart21);
            }
            }
            if (elem0_4 == null) {
                children.clear();
                children.addAll(savedChildren21);
                elem0_4 = CstParseResult.failure("one of alternatives");
            }
            if (elem0_4.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_4;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_LAMBDA_PARAM, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Args(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(95, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_Expr(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem2 = CstParseResult.success(null, "", location());
                var seqStart4 = location();
                if (zomElem2.isSuccess()) {
                    var elem4_0 = matchLiteralCst(",", false);
                    if (elem4_0.isSuccess() && elem4_0.node != null) {
                        children.add(elem4_0.node);
                    }
                    if (elem4_0.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_0;
                    }
                }
                if (zomElem2.isSuccess()) {
                    var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem4_1 = parse_Expr(trivia6);
                    if (elem4_1.isSuccess() && elem4_1.node != null) {
                        children.add(elem4_1.node);
                    }
                    if (elem4_1.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_1;
                    }
                }
                if (zomElem2.isSuccess()) {
                    zomElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_ARGS, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_ExprList(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(96, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_Expr(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem2 = CstParseResult.success(null, "", location());
                var seqStart4 = location();
                if (zomElem2.isSuccess()) {
                    var elem4_0 = matchLiteralCst(",", false);
                    if (elem4_0.isSuccess() && elem4_0.node != null) {
                        children.add(elem4_0.node);
                    }
                    if (elem4_0.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_0;
                    }
                }
                if (zomElem2.isSuccess()) {
                    var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem4_1 = parse_Expr(trivia6);
                    if (elem4_1.isSuccess() && elem4_1.node != null) {
                        children.add(elem4_1.node);
                    }
                    if (elem4_1.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_1;
                    }
                }
                if (zomElem2.isSuccess()) {
                    zomElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_EXPR_LIST, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Type(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(97, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            CstParseResult elem0_0 = CstParseResult.success(null, "", location());
            var zomStart1 = location();
            while (true) {
                var beforeLoc1 = location();
                var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem1 = parse_Annotation(trivia2);
                if (zomElem1.isSuccess() && zomElem1.node != null) {
                    children.add(zomElem1.node);
                }
                if (zomElem1.isFailure() || location().offset() == beforeLoc1.offset()) {
                    restoreLocation(beforeLoc1);
                    break;
                }
            }
            elem0_0 = CstParseResult.success(null, substring(zomStart1.offset(), pos), location());
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_1 = null;
            var choiceStart4 = location();
            var savedChildren4 = new ArrayList<>(children);
            children.clear();
            children.addAll(savedChildren4);
            var trivia5 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var alt4_0 = parse_PrimType(trivia5);
            if (alt4_0.isSuccess() && alt4_0.node != null) {
                children.add(alt4_0.node);
            }
            if (alt4_0.isSuccess()) {
                elem0_1 = alt4_0;
            } else {
                restoreLocation(choiceStart4);
            children.clear();
            children.addAll(savedChildren4);
            var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var alt4_1 = parse_RefType(trivia6);
            if (alt4_1.isSuccess() && alt4_1.node != null) {
                children.add(alt4_1.node);
            }
            if (alt4_1.isSuccess()) {
                elem0_1 = alt4_1;
            } else {
                restoreLocation(choiceStart4);
            }
            }
            if (elem0_1 == null) {
                children.clear();
                children.addAll(savedChildren4);
                elem0_1 = CstParseResult.failure("one of alternatives");
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            var optStart7 = location();
            var trivia8 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem7 = parse_Dims(trivia8);
            if (optElem7.isSuccess() && optElem7.node != null) {
                children.add(optElem7.node);
            }
            var elem0_2 = optElem7.isSuccess() ? optElem7 : CstParseResult.success(null, "", location());
            if (optElem7.isFailure()) {
                restoreLocation(optStart7);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_TYPE, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_PrimType(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(98, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_0 = matchLiteralCst("boolean", false);
        if (alt0_0.isSuccess() && alt0_0.node != null) {
            children.add(alt0_0.node);
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_1 = matchLiteralCst("byte", false);
        if (alt0_1.isSuccess() && alt0_1.node != null) {
            children.add(alt0_1.node);
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_2 = matchLiteralCst("short", false);
        if (alt0_2.isSuccess() && alt0_2.node != null) {
            children.add(alt0_2.node);
        }
        if (alt0_2.isSuccess()) {
            result = alt0_2;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_3 = matchLiteralCst("int", false);
        if (alt0_3.isSuccess() && alt0_3.node != null) {
            children.add(alt0_3.node);
        }
        if (alt0_3.isSuccess()) {
            result = alt0_3;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_4 = matchLiteralCst("long", false);
        if (alt0_4.isSuccess() && alt0_4.node != null) {
            children.add(alt0_4.node);
        }
        if (alt0_4.isSuccess()) {
            result = alt0_4;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_5 = matchLiteralCst("float", false);
        if (alt0_5.isSuccess() && alt0_5.node != null) {
            children.add(alt0_5.node);
        }
        if (alt0_5.isSuccess()) {
            result = alt0_5;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_6 = matchLiteralCst("double", false);
        if (alt0_6.isSuccess() && alt0_6.node != null) {
            children.add(alt0_6.node);
        }
        if (alt0_6.isSuccess()) {
            result = alt0_6;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_7 = matchLiteralCst("char", false);
        if (alt0_7.isSuccess() && alt0_7.node != null) {
            children.add(alt0_7.node);
        }
        if (alt0_7.isSuccess()) {
            result = alt0_7;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_8 = matchLiteralCst("void", false);
        if (alt0_8.isSuccess() && alt0_8.node != null) {
            children.add(alt0_8.node);
        }
        if (alt0_8.isSuccess()) {
            result = alt0_8;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        }
        }
        }
        }
        }
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_PRIM_TYPE, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_RefType(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(99, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_AnnotatedTypeName(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem2 = CstParseResult.success(null, "", location());
                var seqStart4 = location();
                if (zomElem2.isSuccess()) {
                    var elem4_0 = matchLiteralCst(".", false);
                    if (elem4_0.isSuccess() && elem4_0.node != null) {
                        children.add(elem4_0.node);
                    }
                    if (elem4_0.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_0;
                    }
                }
                if (zomElem2.isSuccess()) {
                    var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem4_1 = parse_AnnotatedTypeName(trivia6);
                    if (elem4_1.isSuccess() && elem4_1.node != null) {
                        children.add(elem4_1.node);
                    }
                    if (elem4_1.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_1;
                    }
                }
                if (zomElem2.isSuccess()) {
                    zomElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_REF_TYPE, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_AnnotatedTypeName(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(100, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            CstParseResult elem0_0 = CstParseResult.success(null, "", location());
            var zomStart1 = location();
            while (true) {
                var beforeLoc1 = location();
                var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem1 = parse_Annotation(trivia2);
                if (zomElem1.isSuccess() && zomElem1.node != null) {
                    children.add(zomElem1.node);
                }
                if (zomElem1.isFailure() || location().offset() == beforeLoc1.offset()) {
                    restoreLocation(beforeLoc1);
                    break;
                }
            }
            elem0_0 = CstParseResult.success(null, substring(zomStart1.offset(), pos), location());
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            var trivia3 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_1 = parse_Identifier(trivia3);
            if (elem0_1.isSuccess() && elem0_1.node != null) {
                children.add(elem0_1.node);
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            var optStart4 = location();
            var trivia5 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var optElem4 = parse_TypeArgs(trivia5);
            if (optElem4.isSuccess() && optElem4.node != null) {
                children.add(optElem4.node);
            }
            var elem0_2 = optElem4.isSuccess() ? optElem4 : CstParseResult.success(null, "", location());
            if (optElem4.isFailure()) {
                restoreLocation(optStart4);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_ANNOTATED_TYPE_NAME, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Dims(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(101, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult oomFirst0 = CstParseResult.success(null, "", location());
        var seqStart2 = location();
        if (oomFirst0.isSuccess()) {
            CstParseResult elem2_0 = CstParseResult.success(null, "", location());
            var zomStart3 = location();
            while (true) {
                var beforeLoc3 = location();
                var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var zomElem3 = parse_Annotation(trivia4);
                if (zomElem3.isSuccess() && zomElem3.node != null) {
                    children.add(zomElem3.node);
                }
                if (zomElem3.isFailure() || location().offset() == beforeLoc3.offset()) {
                    restoreLocation(beforeLoc3);
                    break;
                }
            }
            elem2_0 = CstParseResult.success(null, substring(zomStart3.offset(), pos), location());
            if (elem2_0.isFailure()) {
                restoreLocation(seqStart2);
                oomFirst0 = elem2_0;
            }
        }
        if (oomFirst0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem2_1 = matchLiteralCst("[", false);
            if (elem2_1.isSuccess() && elem2_1.node != null) {
                children.add(elem2_1.node);
            }
            if (elem2_1.isFailure()) {
                restoreLocation(seqStart2);
                oomFirst0 = elem2_1;
            }
        }
        if (oomFirst0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem2_2 = matchLiteralCst("]", false);
            if (elem2_2.isSuccess() && elem2_2.node != null) {
                children.add(elem2_2.node);
            }
            if (elem2_2.isFailure()) {
                restoreLocation(seqStart2);
                oomFirst0 = elem2_2;
            }
        }
        if (oomFirst0.isSuccess()) {
            oomFirst0 = CstParseResult.success(null, substring(seqStart2.offset(), pos), location());
        }
        var result = oomFirst0;
        if (oomFirst0.isSuccess()) {
            var oomStart0 = location();
            while (true) {
                var beforeLoc0 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult oomElem0 = CstParseResult.success(null, "", location());
                var seqStart8 = location();
                if (oomElem0.isSuccess()) {
                    CstParseResult elem8_0 = CstParseResult.success(null, "", location());
                    var zomStart9 = location();
                    while (true) {
                        var beforeLoc9 = location();
                        var trivia10 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                        var zomElem9 = parse_Annotation(trivia10);
                        if (zomElem9.isSuccess() && zomElem9.node != null) {
                            children.add(zomElem9.node);
                        }
                        if (zomElem9.isFailure() || location().offset() == beforeLoc9.offset()) {
                            restoreLocation(beforeLoc9);
                            break;
                        }
                    }
                    elem8_0 = CstParseResult.success(null, substring(zomStart9.offset(), pos), location());
                    if (elem8_0.isFailure()) {
                        restoreLocation(seqStart8);
                        oomElem0 = elem8_0;
                    }
                }
                if (oomElem0.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var elem8_1 = matchLiteralCst("[", false);
                    if (elem8_1.isSuccess() && elem8_1.node != null) {
                        children.add(elem8_1.node);
                    }
                    if (elem8_1.isFailure()) {
                        restoreLocation(seqStart8);
                        oomElem0 = elem8_1;
                    }
                }
                if (oomElem0.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var elem8_2 = matchLiteralCst("]", false);
                    if (elem8_2.isSuccess() && elem8_2.node != null) {
                        children.add(elem8_2.node);
                    }
                    if (elem8_2.isFailure()) {
                        restoreLocation(seqStart8);
                        oomElem0 = elem8_2;
                    }
                }
                if (oomElem0.isSuccess()) {
                    oomElem0 = CstParseResult.success(null, substring(seqStart8.offset(), pos), location());
                }
                if (oomElem0.isFailure() || location().offset() == beforeLoc0.offset()) {
                    restoreLocation(beforeLoc0);
                    break;
                }
            }
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_DIMS, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_TypeArgs(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(102, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_0 = CstParseResult.success(null, "", location());
        var seqStart1 = location();
        if (alt0_0.isSuccess()) {
            var elem1_0 = matchLiteralCst("<", false);
            if (elem1_0.isSuccess() && elem1_0.node != null) {
                children.add(elem1_0.node);
            }
            if (elem1_0.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_0;
            }
        }
        if (alt0_0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem1_1 = matchLiteralCst(">", false);
            if (elem1_1.isSuccess() && elem1_1.node != null) {
                children.add(elem1_1.node);
            }
            if (elem1_1.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_1;
            }
        }
        if (alt0_0.isSuccess()) {
            alt0_0 = CstParseResult.success(null, substring(seqStart1.offset(), pos), location());
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_1 = CstParseResult.success(null, "", location());
        var seqStart4 = location();
        if (alt0_1.isSuccess()) {
            var elem4_0 = matchLiteralCst("<", false);
            if (elem4_0.isSuccess() && elem4_0.node != null) {
                children.add(elem4_0.node);
            }
            if (elem4_0.isFailure()) {
                restoreLocation(seqStart4);
                alt0_1 = elem4_0;
            }
        }
        if (alt0_1.isSuccess()) {
            var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem4_1 = parse_TypeArg(trivia6);
            if (elem4_1.isSuccess() && elem4_1.node != null) {
                children.add(elem4_1.node);
            }
            if (elem4_1.isFailure()) {
                restoreLocation(seqStart4);
                alt0_1 = elem4_1;
            }
        }
        if (alt0_1.isSuccess()) {
            CstParseResult elem4_2 = CstParseResult.success(null, "", location());
            var zomStart7 = location();
            while (true) {
                var beforeLoc7 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem7 = CstParseResult.success(null, "", location());
                var seqStart9 = location();
                if (zomElem7.isSuccess()) {
                    var elem9_0 = matchLiteralCst(",", false);
                    if (elem9_0.isSuccess() && elem9_0.node != null) {
                        children.add(elem9_0.node);
                    }
                    if (elem9_0.isFailure()) {
                        restoreLocation(seqStart9);
                        zomElem7 = elem9_0;
                    }
                }
                if (zomElem7.isSuccess()) {
                    var trivia11 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem9_1 = parse_TypeArg(trivia11);
                    if (elem9_1.isSuccess() && elem9_1.node != null) {
                        children.add(elem9_1.node);
                    }
                    if (elem9_1.isFailure()) {
                        restoreLocation(seqStart9);
                        zomElem7 = elem9_1;
                    }
                }
                if (zomElem7.isSuccess()) {
                    zomElem7 = CstParseResult.success(null, substring(seqStart9.offset(), pos), location());
                }
                if (zomElem7.isFailure() || location().offset() == beforeLoc7.offset()) {
                    restoreLocation(beforeLoc7);
                    break;
                }
            }
            elem4_2 = CstParseResult.success(null, substring(zomStart7.offset(), pos), location());
            if (elem4_2.isFailure()) {
                restoreLocation(seqStart4);
                alt0_1 = elem4_2;
            }
        }
        if (alt0_1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem4_3 = matchLiteralCst(">", false);
            if (elem4_3.isSuccess() && elem4_3.node != null) {
                children.add(elem4_3.node);
            }
            if (elem4_3.isFailure()) {
                restoreLocation(seqStart4);
                alt0_1 = elem4_3;
            }
        }
        if (alt0_1.isSuccess()) {
            alt0_1 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_TYPE_ARGS, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_TypeArg(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(103, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_0 = parse_Type(trivia1);
        if (alt0_0.isSuccess() && alt0_0.node != null) {
            children.add(alt0_0.node);
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_1 = CstParseResult.success(null, "", location());
        var seqStart2 = location();
        if (alt0_1.isSuccess()) {
            var elem2_0 = matchLiteralCst("?", false);
            if (elem2_0.isSuccess() && elem2_0.node != null) {
                children.add(elem2_0.node);
            }
            if (elem2_0.isFailure()) {
                restoreLocation(seqStart2);
                alt0_1 = elem2_0;
            }
        }
        if (alt0_1.isSuccess()) {
            var optStart4 = location();
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult optElem4 = CstParseResult.success(null, "", location());
            var seqStart6 = location();
            if (optElem4.isSuccess()) {
                CstParseResult elem6_0 = CstParseResult.success(null, "", location());
                var zomStart7 = location();
                while (true) {
                    var beforeLoc7 = location();
                    var trivia8 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var zomElem7 = parse_Annotation(trivia8);
                    if (zomElem7.isSuccess() && zomElem7.node != null) {
                        children.add(zomElem7.node);
                    }
                    if (zomElem7.isFailure() || location().offset() == beforeLoc7.offset()) {
                        restoreLocation(beforeLoc7);
                        break;
                    }
                }
                elem6_0 = CstParseResult.success(null, substring(zomStart7.offset(), pos), location());
                if (elem6_0.isFailure()) {
                    restoreLocation(seqStart6);
                    optElem4 = elem6_0;
                }
            }
            if (optElem4.isSuccess()) {
                CstParseResult elem6_1 = null;
                var choiceStart10 = location();
                var savedChildren10 = new ArrayList<>(children);
                children.clear();
                children.addAll(savedChildren10);
                var alt10_0 = matchLiteralCst("extends", false);
                if (alt10_0.isSuccess() && alt10_0.node != null) {
                    children.add(alt10_0.node);
                }
                if (alt10_0.isSuccess()) {
                    elem6_1 = alt10_0;
                } else {
                    restoreLocation(choiceStart10);
                children.clear();
                children.addAll(savedChildren10);
                var alt10_1 = matchLiteralCst("super", false);
                if (alt10_1.isSuccess() && alt10_1.node != null) {
                    children.add(alt10_1.node);
                }
                if (alt10_1.isSuccess()) {
                    elem6_1 = alt10_1;
                } else {
                    restoreLocation(choiceStart10);
                }
                }
                if (elem6_1 == null) {
                    children.clear();
                    children.addAll(savedChildren10);
                    elem6_1 = CstParseResult.failure("one of alternatives");
                }
                if (elem6_1.isFailure()) {
                    restoreLocation(seqStart6);
                    optElem4 = elem6_1;
                }
            }
            if (optElem4.isSuccess()) {
                var trivia13 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var elem6_2 = parse_Type(trivia13);
                if (elem6_2.isSuccess() && elem6_2.node != null) {
                    children.add(elem6_2.node);
                }
                if (elem6_2.isFailure()) {
                    restoreLocation(seqStart6);
                    optElem4 = elem6_2;
                }
            }
            if (optElem4.isSuccess()) {
                optElem4 = CstParseResult.success(null, substring(seqStart6.offset(), pos), location());
            }
            var elem2_1 = optElem4.isSuccess() ? optElem4 : CstParseResult.success(null, "", location());
            if (optElem4.isFailure()) {
                restoreLocation(optStart4);
            }
            if (elem2_1.isFailure()) {
                restoreLocation(seqStart2);
                alt0_1 = elem2_1;
            }
        }
        if (alt0_1.isSuccess()) {
            alt0_1 = CstParseResult.success(null, substring(seqStart2.offset(), pos), location());
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_TYPE_ARG, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_QualifiedName(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(104, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_0 = parse_Identifier(trivia1);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            CstParseResult elem0_1 = CstParseResult.success(null, "", location());
            var zomStart2 = location();
            while (true) {
                var beforeLoc2 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem2 = CstParseResult.success(null, "", location());
                var seqStart4 = location();
                if (zomElem2.isSuccess()) {
                    var andStart5 = location();
                    var savedChildrenAnd5 = new ArrayList<>(children);
                    CstParseResult andElem5 = CstParseResult.success(null, "", location());
                    var seqStart7 = location();
                    if (andElem5.isSuccess()) {
                        var elem7_0 = matchLiteralCst(".", false);
                        if (elem7_0.isFailure()) {
                            restoreLocation(seqStart7);
                            andElem5 = elem7_0;
                        }
                    }
                    if (andElem5.isSuccess()) {
                        var trivia9 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                        var elem7_1 = parse_Identifier(trivia9);
                        if (elem7_1.isFailure()) {
                            restoreLocation(seqStart7);
                            andElem5 = elem7_1;
                        }
                    }
                    if (andElem5.isSuccess()) {
                        andElem5 = CstParseResult.success(null, substring(seqStart7.offset(), pos), location());
                    }
                    restoreLocation(andStart5);
                    children.clear();
                    children.addAll(savedChildrenAnd5);
                    var elem4_0 = andElem5.isSuccess() ? CstParseResult.success(null, "", location()) : andElem5;
                    if (elem4_0.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_0;
                    }
                }
                if (zomElem2.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var elem4_1 = matchLiteralCst(".", false);
                    if (elem4_1.isSuccess() && elem4_1.node != null) {
                        children.add(elem4_1.node);
                    }
                    if (elem4_1.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_1;
                    }
                }
                if (zomElem2.isSuccess()) {
                    var trivia11 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem4_2 = parse_Identifier(trivia11);
                    if (elem4_2.isSuccess() && elem4_2.node != null) {
                        children.add(elem4_2.node);
                    }
                    if (elem4_2.isFailure()) {
                        restoreLocation(seqStart4);
                        zomElem2 = elem4_2;
                    }
                }
                if (zomElem2.isSuccess()) {
                    zomElem2 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
                }
                if (zomElem2.isFailure() || location().offset() == beforeLoc2.offset()) {
                    restoreLocation(beforeLoc2);
                    break;
                }
            }
            elem0_1 = CstParseResult.success(null, substring(zomStart2.offset(), pos), location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_QUALIFIED_NAME, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Identifier(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(105, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var notStart1 = location();
            var savedChildrenNot1 = new ArrayList<>(children);
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var notElem1 = parse_Keyword(trivia2);
            restoreLocation(notStart1);
            children.clear();
            children.addAll(savedChildrenNot1);
            var elem0_0 = notElem1.isSuccess() ? CstParseResult.failure("not match") : CstParseResult.success(null, "", location());
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var tbStart3 = location();
            inTokenBoundary = true;
            var savedChildrenTb3 = new ArrayList<>(children);
            CstParseResult tbElem3 = CstParseResult.success(null, "", location());
            var seqStart4 = location();
            if (tbElem3.isSuccess()) {
                var elem4_0 = matchCharClassCst("a-zA-Z_$", false, false);
                if (elem4_0.isFailure()) {
                    restoreLocation(seqStart4);
                    tbElem3 = elem4_0;
                }
            }
            if (tbElem3.isSuccess()) {
                CstParseResult elem4_1 = CstParseResult.success(null, "", location());
                var zomStart6 = location();
                while (true) {
                    var beforeLoc6 = location();
                    if (!inTokenBoundary) skipWhitespace();
                    var zomElem6 = matchCharClassCst("a-zA-Z0-9_$", false, false);
                    if (zomElem6.isFailure() || location().offset() == beforeLoc6.offset()) {
                        restoreLocation(beforeLoc6);
                        break;
                    }
                }
                elem4_1 = CstParseResult.success(null, substring(zomStart6.offset(), pos), location());
                if (elem4_1.isFailure()) {
                    restoreLocation(seqStart4);
                    tbElem3 = elem4_1;
                }
            }
            if (tbElem3.isSuccess()) {
                tbElem3 = CstParseResult.success(null, substring(seqStart4.offset(), pos), location());
            }
            inTokenBoundary = false;
            children.clear();
            children.addAll(savedChildrenTb3);
            CstParseResult elem0_1;
            if (tbElem3.isSuccess()) {
                var tbText3 = substring(tbStart3.offset(), pos);
                var tbSpan3 = SourceSpan.of(tbStart3, location());
                var tbNode3 = new CstNode.Token(tbSpan3, RULE_PEG_TOKEN, tbText3, List.of(), List.of());
                children.add(tbNode3);
                elem0_1 = CstParseResult.success(tbNode3, tbText3, location());
            } else {
                elem0_1 = tbElem3;
            }
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_IDENTIFIER, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Modifier(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(106, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_0 = matchLiteralCst("public", false);
        if (alt0_0.isSuccess() && alt0_0.node != null) {
            children.add(alt0_0.node);
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_1 = matchLiteralCst("protected", false);
        if (alt0_1.isSuccess() && alt0_1.node != null) {
            children.add(alt0_1.node);
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_2 = matchLiteralCst("private", false);
        if (alt0_2.isSuccess() && alt0_2.node != null) {
            children.add(alt0_2.node);
        }
        if (alt0_2.isSuccess()) {
            result = alt0_2;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_3 = matchLiteralCst("static", false);
        if (alt0_3.isSuccess() && alt0_3.node != null) {
            children.add(alt0_3.node);
        }
        if (alt0_3.isSuccess()) {
            result = alt0_3;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_4 = matchLiteralCst("final", false);
        if (alt0_4.isSuccess() && alt0_4.node != null) {
            children.add(alt0_4.node);
        }
        if (alt0_4.isSuccess()) {
            result = alt0_4;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_5 = matchLiteralCst("abstract", false);
        if (alt0_5.isSuccess() && alt0_5.node != null) {
            children.add(alt0_5.node);
        }
        if (alt0_5.isSuccess()) {
            result = alt0_5;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_6 = matchLiteralCst("native", false);
        if (alt0_6.isSuccess() && alt0_6.node != null) {
            children.add(alt0_6.node);
        }
        if (alt0_6.isSuccess()) {
            result = alt0_6;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_7 = matchLiteralCst("synchronized", false);
        if (alt0_7.isSuccess() && alt0_7.node != null) {
            children.add(alt0_7.node);
        }
        if (alt0_7.isSuccess()) {
            result = alt0_7;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_8 = matchLiteralCst("transient", false);
        if (alt0_8.isSuccess() && alt0_8.node != null) {
            children.add(alt0_8.node);
        }
        if (alt0_8.isSuccess()) {
            result = alt0_8;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_9 = matchLiteralCst("volatile", false);
        if (alt0_9.isSuccess() && alt0_9.node != null) {
            children.add(alt0_9.node);
        }
        if (alt0_9.isSuccess()) {
            result = alt0_9;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_10 = matchLiteralCst("strictfp", false);
        if (alt0_10.isSuccess() && alt0_10.node != null) {
            children.add(alt0_10.node);
        }
        if (alt0_10.isSuccess()) {
            result = alt0_10;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_11 = matchLiteralCst("default", false);
        if (alt0_11.isSuccess() && alt0_11.node != null) {
            children.add(alt0_11.node);
        }
        if (alt0_11.isSuccess()) {
            result = alt0_11;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_12 = matchLiteralCst("sealed", false);
        if (alt0_12.isSuccess() && alt0_12.node != null) {
            children.add(alt0_12.node);
        }
        if (alt0_12.isSuccess()) {
            result = alt0_12;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_13 = matchLiteralCst("non-sealed", false);
        if (alt0_13.isSuccess() && alt0_13.node != null) {
            children.add(alt0_13.node);
        }
        if (alt0_13.isSuccess()) {
            result = alt0_13;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        }
        }
        }
        }
        }
        }
        }
        }
        }
        }
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_MODIFIER, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Annotation(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(107, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            var elem0_0 = matchLiteralCst("@", false);
            if (elem0_0.isSuccess() && elem0_0.node != null) {
                children.add(elem0_0.node);
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var notStart2 = location();
            var savedChildrenNot2 = new ArrayList<>(children);
            var notElem2 = matchLiteralCst("interface", false);
            restoreLocation(notStart2);
            children.clear();
            children.addAll(savedChildrenNot2);
            var elem0_1 = notElem2.isSuccess() ? CstParseResult.failure("not match") : CstParseResult.success(null, "", location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem0_2 = parse_QualifiedName(trivia4);
            if (elem0_2.isSuccess() && elem0_2.node != null) {
                children.add(elem0_2.node);
            }
            if (elem0_2.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_2;
            }
        }
        if (result.isSuccess()) {
            var optStart5 = location();
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult optElem5 = CstParseResult.success(null, "", location());
            var seqStart7 = location();
            if (optElem5.isSuccess()) {
                var elem7_0 = matchLiteralCst("(", false);
                if (elem7_0.isSuccess() && elem7_0.node != null) {
                    children.add(elem7_0.node);
                }
                if (elem7_0.isFailure()) {
                    restoreLocation(seqStart7);
                    optElem5 = elem7_0;
                }
            }
            if (optElem5.isSuccess()) {
                var optStart9 = location();
                var trivia10 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var optElem9 = parse_AnnotationValue(trivia10);
                if (optElem9.isSuccess() && optElem9.node != null) {
                    children.add(optElem9.node);
                }
                var elem7_1 = optElem9.isSuccess() ? optElem9 : CstParseResult.success(null, "", location());
                if (optElem9.isFailure()) {
                    restoreLocation(optStart9);
                }
                if (elem7_1.isFailure()) {
                    restoreLocation(seqStart7);
                    optElem5 = elem7_1;
                }
            }
            if (optElem5.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var elem7_2 = matchLiteralCst(")", false);
                if (elem7_2.isSuccess() && elem7_2.node != null) {
                    children.add(elem7_2.node);
                }
                if (elem7_2.isFailure()) {
                    restoreLocation(seqStart7);
                    optElem5 = elem7_2;
                }
            }
            if (optElem5.isSuccess()) {
                optElem5 = CstParseResult.success(null, substring(seqStart7.offset(), pos), location());
            }
            var elem0_3 = optElem5.isSuccess() ? optElem5 : CstParseResult.success(null, "", location());
            if (optElem5.isFailure()) {
                restoreLocation(optStart5);
            }
            if (elem0_3.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_3;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_ANNOTATION, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_AnnotationValue(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(108, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_0 = CstParseResult.success(null, "", location());
        var seqStart1 = location();
        if (alt0_0.isSuccess()) {
            var trivia2 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem1_0 = parse_Identifier(trivia2);
            if (elem1_0.isSuccess() && elem1_0.node != null) {
                children.add(elem1_0.node);
            }
            if (elem1_0.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_0;
            }
        }
        if (alt0_0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem1_1 = matchLiteralCst("=", false);
            if (elem1_1.isSuccess() && elem1_1.node != null) {
                children.add(elem1_1.node);
            }
            if (elem1_1.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_1;
            }
        }
        if (alt0_0.isSuccess()) {
            var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
            var elem1_2 = parse_AnnotationElem(trivia4);
            if (elem1_2.isSuccess() && elem1_2.node != null) {
                children.add(elem1_2.node);
            }
            if (elem1_2.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_2;
            }
        }
        if (alt0_0.isSuccess()) {
            CstParseResult elem1_3 = CstParseResult.success(null, "", location());
            var zomStart5 = location();
            while (true) {
                var beforeLoc5 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem5 = CstParseResult.success(null, "", location());
                var seqStart7 = location();
                if (zomElem5.isSuccess()) {
                    var elem7_0 = matchLiteralCst(",", false);
                    if (elem7_0.isSuccess() && elem7_0.node != null) {
                        children.add(elem7_0.node);
                    }
                    if (elem7_0.isFailure()) {
                        restoreLocation(seqStart7);
                        zomElem5 = elem7_0;
                    }
                }
                if (zomElem5.isSuccess()) {
                    var trivia9 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem7_1 = parse_Identifier(trivia9);
                    if (elem7_1.isSuccess() && elem7_1.node != null) {
                        children.add(elem7_1.node);
                    }
                    if (elem7_1.isFailure()) {
                        restoreLocation(seqStart7);
                        zomElem5 = elem7_1;
                    }
                }
                if (zomElem5.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var elem7_2 = matchLiteralCst("=", false);
                    if (elem7_2.isSuccess() && elem7_2.node != null) {
                        children.add(elem7_2.node);
                    }
                    if (elem7_2.isFailure()) {
                        restoreLocation(seqStart7);
                        zomElem5 = elem7_2;
                    }
                }
                if (zomElem5.isSuccess()) {
                    var trivia11 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                    var elem7_3 = parse_AnnotationElem(trivia11);
                    if (elem7_3.isSuccess() && elem7_3.node != null) {
                        children.add(elem7_3.node);
                    }
                    if (elem7_3.isFailure()) {
                        restoreLocation(seqStart7);
                        zomElem5 = elem7_3;
                    }
                }
                if (zomElem5.isSuccess()) {
                    zomElem5 = CstParseResult.success(null, substring(seqStart7.offset(), pos), location());
                }
                if (zomElem5.isFailure() || location().offset() == beforeLoc5.offset()) {
                    restoreLocation(beforeLoc5);
                    break;
                }
            }
            elem1_3 = CstParseResult.success(null, substring(zomStart5.offset(), pos), location());
            if (elem1_3.isFailure()) {
                restoreLocation(seqStart1);
                alt0_0 = elem1_3;
            }
        }
        if (alt0_0.isSuccess()) {
            alt0_0 = CstParseResult.success(null, substring(seqStart1.offset(), pos), location());
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia12 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_1 = parse_AnnotationElem(trivia12);
        if (alt0_1.isSuccess() && alt0_1.node != null) {
            children.add(alt0_1.node);
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_ANNOTATION_VALUE, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_AnnotationElem(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(109, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        var trivia1 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_0 = parse_Annotation(trivia1);
        if (alt0_0.isSuccess() && alt0_0.node != null) {
            children.add(alt0_0.node);
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        CstParseResult alt0_1 = CstParseResult.success(null, "", location());
        var seqStart2 = location();
        if (alt0_1.isSuccess()) {
            var elem2_0 = matchLiteralCst("{", false);
            if (elem2_0.isSuccess() && elem2_0.node != null) {
                children.add(elem2_0.node);
            }
            if (elem2_0.isFailure()) {
                restoreLocation(seqStart2);
                alt0_1 = elem2_0;
            }
        }
        if (alt0_1.isSuccess()) {
            var optStart4 = location();
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult optElem4 = CstParseResult.success(null, "", location());
            var seqStart6 = location();
            if (optElem4.isSuccess()) {
                var trivia7 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                var elem6_0 = parse_AnnotationElem(trivia7);
                if (elem6_0.isSuccess() && elem6_0.node != null) {
                    children.add(elem6_0.node);
                }
                if (elem6_0.isFailure()) {
                    restoreLocation(seqStart6);
                    optElem4 = elem6_0;
                }
            }
            if (optElem4.isSuccess()) {
                CstParseResult elem6_1 = CstParseResult.success(null, "", location());
                var zomStart8 = location();
                while (true) {
                    var beforeLoc8 = location();
                    if (!inTokenBoundary) skipWhitespace();
                    CstParseResult zomElem8 = CstParseResult.success(null, "", location());
                    var seqStart10 = location();
                    if (zomElem8.isSuccess()) {
                        var elem10_0 = matchLiteralCst(",", false);
                        if (elem10_0.isSuccess() && elem10_0.node != null) {
                            children.add(elem10_0.node);
                        }
                        if (elem10_0.isFailure()) {
                            restoreLocation(seqStart10);
                            zomElem8 = elem10_0;
                        }
                    }
                    if (zomElem8.isSuccess()) {
                        var trivia12 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
                        var elem10_1 = parse_AnnotationElem(trivia12);
                        if (elem10_1.isSuccess() && elem10_1.node != null) {
                            children.add(elem10_1.node);
                        }
                        if (elem10_1.isFailure()) {
                            restoreLocation(seqStart10);
                            zomElem8 = elem10_1;
                        }
                    }
                    if (zomElem8.isSuccess()) {
                        zomElem8 = CstParseResult.success(null, substring(seqStart10.offset(), pos), location());
                    }
                    if (zomElem8.isFailure() || location().offset() == beforeLoc8.offset()) {
                        restoreLocation(beforeLoc8);
                        break;
                    }
                }
                elem6_1 = CstParseResult.success(null, substring(zomStart8.offset(), pos), location());
                if (elem6_1.isFailure()) {
                    restoreLocation(seqStart6);
                    optElem4 = elem6_1;
                }
            }
            if (optElem4.isSuccess()) {
                var optStart13 = location();
                if (!inTokenBoundary) skipWhitespace();
                var optElem13 = matchLiteralCst(",", false);
                if (optElem13.isSuccess() && optElem13.node != null) {
                    children.add(optElem13.node);
                }
                var elem6_2 = optElem13.isSuccess() ? optElem13 : CstParseResult.success(null, "", location());
                if (optElem13.isFailure()) {
                    restoreLocation(optStart13);
                }
                if (elem6_2.isFailure()) {
                    restoreLocation(seqStart6);
                    optElem4 = elem6_2;
                }
            }
            if (optElem4.isSuccess()) {
                optElem4 = CstParseResult.success(null, substring(seqStart6.offset(), pos), location());
            }
            var elem2_1 = optElem4.isSuccess() ? optElem4 : CstParseResult.success(null, "", location());
            if (optElem4.isFailure()) {
                restoreLocation(optStart4);
            }
            if (elem2_1.isFailure()) {
                restoreLocation(seqStart2);
                alt0_1 = elem2_1;
            }
        }
        if (alt0_1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem2_2 = matchLiteralCst("}", false);
            if (elem2_2.isSuccess() && elem2_2.node != null) {
                children.add(elem2_2.node);
            }
            if (elem2_2.isFailure()) {
                restoreLocation(seqStart2);
                alt0_1 = elem2_2;
            }
        }
        if (alt0_1.isSuccess()) {
            alt0_1 = CstParseResult.success(null, substring(seqStart2.offset(), pos), location());
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia16 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_2 = parse_Ternary(trivia16);
        if (alt0_2.isSuccess() && alt0_2.node != null) {
            children.add(alt0_2.node);
        }
        if (alt0_2.isSuccess()) {
            result = alt0_2;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_ANNOTATION_ELEM, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Literal(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(110, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_0 = matchLiteralCst("null", false);
        if (alt0_0.isSuccess() && alt0_0.node != null) {
            children.add(alt0_0.node);
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_1 = matchLiteralCst("true", false);
        if (alt0_1.isSuccess() && alt0_1.node != null) {
            children.add(alt0_1.node);
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var alt0_2 = matchLiteralCst("false", false);
        if (alt0_2.isSuccess() && alt0_2.node != null) {
            children.add(alt0_2.node);
        }
        if (alt0_2.isSuccess()) {
            result = alt0_2;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia4 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_3 = parse_CharLit(trivia4);
        if (alt0_3.isSuccess() && alt0_3.node != null) {
            children.add(alt0_3.node);
        }
        if (alt0_3.isSuccess()) {
            result = alt0_3;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia5 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_4 = parse_StringLit(trivia5);
        if (alt0_4.isSuccess() && alt0_4.node != null) {
            children.add(alt0_4.node);
        }
        if (alt0_4.isSuccess()) {
            result = alt0_4;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var trivia6 = inTokenBoundary ? List.<Trivia>of() : skipWhitespace();
        var alt0_5 = parse_NumLit(trivia6);
        if (alt0_5.isSuccess() && alt0_5.node != null) {
            children.add(alt0_5.node);
        }
        if (alt0_5.isSuccess()) {
            result = alt0_5;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        }
        }
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_LITERAL, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_CharLit(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(111, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        var tbStart0 = location();
        inTokenBoundary = true;
        var savedChildrenTb0 = new ArrayList<>(children);
        CstParseResult tbElem0 = CstParseResult.success(null, "", location());
        var seqStart1 = location();
        if (tbElem0.isSuccess()) {
            var elem1_0 = matchLiteralCst("'", false);
            if (elem1_0.isFailure()) {
                restoreLocation(seqStart1);
                tbElem0 = elem1_0;
            }
        }
        if (tbElem0.isSuccess()) {
            CstParseResult elem1_1 = CstParseResult.success(null, "", location());
            var zomStart3 = location();
            while (true) {
                var beforeLoc3 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem3 = null;
                var choiceStart5 = location();
                var alt5_0 = matchCharClassCst("'\\\\", true, false);
                if (alt5_0.isSuccess()) {
                    zomElem3 = alt5_0;
                } else {
                    restoreLocation(choiceStart5);
                CstParseResult alt5_1 = CstParseResult.success(null, "", location());
                var seqStart7 = location();
                if (alt5_1.isSuccess()) {
                    var elem7_0 = matchLiteralCst("\\", false);
                    if (elem7_0.isFailure()) {
                        restoreLocation(seqStart7);
                        alt5_1 = elem7_0;
                    }
                }
                if (alt5_1.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var elem7_1 = matchAnyCst();
                    if (elem7_1.isFailure()) {
                        restoreLocation(seqStart7);
                        alt5_1 = elem7_1;
                    }
                }
                if (alt5_1.isSuccess()) {
                    alt5_1 = CstParseResult.success(null, substring(seqStart7.offset(), pos), location());
                }
                if (alt5_1.isSuccess()) {
                    zomElem3 = alt5_1;
                } else {
                    restoreLocation(choiceStart5);
                }
                }
                if (zomElem3 == null) {
                    zomElem3 = CstParseResult.failure("one of alternatives");
                }
                if (zomElem3.isFailure() || location().offset() == beforeLoc3.offset()) {
                    restoreLocation(beforeLoc3);
                    break;
                }
            }
            elem1_1 = CstParseResult.success(null, substring(zomStart3.offset(), pos), location());
            if (elem1_1.isFailure()) {
                restoreLocation(seqStart1);
                tbElem0 = elem1_1;
            }
        }
        if (tbElem0.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem1_2 = matchLiteralCst("'", false);
            if (elem1_2.isFailure()) {
                restoreLocation(seqStart1);
                tbElem0 = elem1_2;
            }
        }
        if (tbElem0.isSuccess()) {
            tbElem0 = CstParseResult.success(null, substring(seqStart1.offset(), pos), location());
        }
        inTokenBoundary = false;
        children.clear();
        children.addAll(savedChildrenTb0);
        CstParseResult result;
        if (tbElem0.isSuccess()) {
            var tbText0 = substring(tbStart0.offset(), pos);
            var tbSpan0 = SourceSpan.of(tbStart0, location());
            var tbNode0 = new CstNode.Token(tbSpan0, RULE_PEG_TOKEN, tbText0, List.of(), List.of());
            children.add(tbNode0);
            result = CstParseResult.success(tbNode0, tbText0, location());
        } else {
            result = tbElem0;
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.Token(span, RULE_CHAR_LIT, result.text, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_StringLit(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(112, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        var tbStart1 = location();
        inTokenBoundary = true;
        var savedChildrenTb1 = new ArrayList<>(children);
        CstParseResult tbElem1 = CstParseResult.success(null, "", location());
        var seqStart2 = location();
        if (tbElem1.isSuccess()) {
            var elem2_0 = matchLiteralCst("\"\"\"", false);
            if (elem2_0.isFailure()) {
                restoreLocation(seqStart2);
                tbElem1 = elem2_0;
            }
        }
        if (tbElem1.isSuccess()) {
            CstParseResult elem2_1 = CstParseResult.success(null, "", location());
            var zomStart4 = location();
            while (true) {
                var beforeLoc4 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem4 = CstParseResult.success(null, "", location());
                var seqStart6 = location();
                if (zomElem4.isSuccess()) {
                    var notStart7 = location();
                    var notElem7 = matchLiteralCst("\"\"\"", false);
                    restoreLocation(notStart7);
                    var elem6_0 = notElem7.isSuccess() ? CstParseResult.failure("not match") : CstParseResult.success(null, "", location());
                    if (elem6_0.isFailure()) {
                        restoreLocation(seqStart6);
                        zomElem4 = elem6_0;
                    }
                }
                if (zomElem4.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var elem6_1 = matchAnyCst();
                    if (elem6_1.isFailure()) {
                        restoreLocation(seqStart6);
                        zomElem4 = elem6_1;
                    }
                }
                if (zomElem4.isSuccess()) {
                    zomElem4 = CstParseResult.success(null, substring(seqStart6.offset(), pos), location());
                }
                if (zomElem4.isFailure() || location().offset() == beforeLoc4.offset()) {
                    restoreLocation(beforeLoc4);
                    break;
                }
            }
            elem2_1 = CstParseResult.success(null, substring(zomStart4.offset(), pos), location());
            if (elem2_1.isFailure()) {
                restoreLocation(seqStart2);
                tbElem1 = elem2_1;
            }
        }
        if (tbElem1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem2_2 = matchLiteralCst("\"\"\"", false);
            if (elem2_2.isFailure()) {
                restoreLocation(seqStart2);
                tbElem1 = elem2_2;
            }
        }
        if (tbElem1.isSuccess()) {
            tbElem1 = CstParseResult.success(null, substring(seqStart2.offset(), pos), location());
        }
        inTokenBoundary = false;
        children.clear();
        children.addAll(savedChildrenTb1);
        CstParseResult alt0_0;
        if (tbElem1.isSuccess()) {
            var tbText1 = substring(tbStart1.offset(), pos);
            var tbSpan1 = SourceSpan.of(tbStart1, location());
            var tbNode1 = new CstNode.Token(tbSpan1, RULE_PEG_TOKEN, tbText1, List.of(), List.of());
            children.add(tbNode1);
            alt0_0 = CstParseResult.success(tbNode1, tbText1, location());
        } else {
            alt0_0 = tbElem1;
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var tbStart11 = location();
        inTokenBoundary = true;
        var savedChildrenTb11 = new ArrayList<>(children);
        CstParseResult tbElem11 = CstParseResult.success(null, "", location());
        var seqStart12 = location();
        if (tbElem11.isSuccess()) {
            var elem12_0 = matchLiteralCst("\"", false);
            if (elem12_0.isFailure()) {
                restoreLocation(seqStart12);
                tbElem11 = elem12_0;
            }
        }
        if (tbElem11.isSuccess()) {
            CstParseResult elem12_1 = CstParseResult.success(null, "", location());
            var zomStart14 = location();
            while (true) {
                var beforeLoc14 = location();
                if (!inTokenBoundary) skipWhitespace();
                CstParseResult zomElem14 = null;
                var choiceStart16 = location();
                var alt16_0 = matchCharClassCst("\"\\\\", true, false);
                if (alt16_0.isSuccess()) {
                    zomElem14 = alt16_0;
                } else {
                    restoreLocation(choiceStart16);
                CstParseResult alt16_1 = CstParseResult.success(null, "", location());
                var seqStart18 = location();
                if (alt16_1.isSuccess()) {
                    var elem18_0 = matchLiteralCst("\\", false);
                    if (elem18_0.isFailure()) {
                        restoreLocation(seqStart18);
                        alt16_1 = elem18_0;
                    }
                }
                if (alt16_1.isSuccess()) {
                    if (!inTokenBoundary) skipWhitespace();
                    var elem18_1 = matchAnyCst();
                    if (elem18_1.isFailure()) {
                        restoreLocation(seqStart18);
                        alt16_1 = elem18_1;
                    }
                }
                if (alt16_1.isSuccess()) {
                    alt16_1 = CstParseResult.success(null, substring(seqStart18.offset(), pos), location());
                }
                if (alt16_1.isSuccess()) {
                    zomElem14 = alt16_1;
                } else {
                    restoreLocation(choiceStart16);
                }
                }
                if (zomElem14 == null) {
                    zomElem14 = CstParseResult.failure("one of alternatives");
                }
                if (zomElem14.isFailure() || location().offset() == beforeLoc14.offset()) {
                    restoreLocation(beforeLoc14);
                    break;
                }
            }
            elem12_1 = CstParseResult.success(null, substring(zomStart14.offset(), pos), location());
            if (elem12_1.isFailure()) {
                restoreLocation(seqStart12);
                tbElem11 = elem12_1;
            }
        }
        if (tbElem11.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem12_2 = matchLiteralCst("\"", false);
            if (elem12_2.isFailure()) {
                restoreLocation(seqStart12);
                tbElem11 = elem12_2;
            }
        }
        if (tbElem11.isSuccess()) {
            tbElem11 = CstParseResult.success(null, substring(seqStart12.offset(), pos), location());
        }
        inTokenBoundary = false;
        children.clear();
        children.addAll(savedChildrenTb11);
        CstParseResult alt0_1;
        if (tbElem11.isSuccess()) {
            var tbText11 = substring(tbStart11.offset(), pos);
            var tbSpan11 = SourceSpan.of(tbStart11, location());
            var tbNode11 = new CstNode.Token(tbSpan11, RULE_PEG_TOKEN, tbText11, List.of(), List.of());
            children.add(tbNode11);
            alt0_1 = CstParseResult.success(tbNode11, tbText11, location());
        } else {
            alt0_1 = tbElem11;
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_STRING_LIT, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_NumLit(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(113, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = null;
        var choiceStart0 = location();
        var savedChildren0 = new ArrayList<>(children);
        children.clear();
        children.addAll(savedChildren0);
        var tbStart1 = location();
        inTokenBoundary = true;
        var savedChildrenTb1 = new ArrayList<>(children);
        CstParseResult tbElem1 = CstParseResult.success(null, "", location());
        var seqStart2 = location();
        if (tbElem1.isSuccess()) {
            var elem2_0 = matchLiteralCst("0", false);
            if (elem2_0.isFailure()) {
                restoreLocation(seqStart2);
                tbElem1 = elem2_0;
            }
        }
        if (tbElem1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem2_1 = matchCharClassCst("xX", false, false);
            if (elem2_1.isFailure()) {
                restoreLocation(seqStart2);
                tbElem1 = elem2_1;
            }
        }
        if (tbElem1.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var oomFirst5 = matchCharClassCst("0-9a-fA-F_", false, false);
            var elem2_2 = oomFirst5;
            if (oomFirst5.isSuccess()) {
                var oomStart5 = location();
                while (true) {
                    var beforeLoc5 = location();
                    if (!inTokenBoundary) skipWhitespace();
                    var oomElem5 = matchCharClassCst("0-9a-fA-F_", false, false);
                    if (oomElem5.isFailure() || location().offset() == beforeLoc5.offset()) {
                        restoreLocation(beforeLoc5);
                        break;
                    }
                }
            }
            if (elem2_2.isFailure()) {
                restoreLocation(seqStart2);
                tbElem1 = elem2_2;
            }
        }
        if (tbElem1.isSuccess()) {
            var optStart8 = location();
            if (!inTokenBoundary) skipWhitespace();
            var optElem8 = matchCharClassCst("lL", false, false);
            var elem2_3 = optElem8.isSuccess() ? optElem8 : CstParseResult.success(null, "", location());
            if (optElem8.isFailure()) {
                restoreLocation(optStart8);
            }
            if (elem2_3.isFailure()) {
                restoreLocation(seqStart2);
                tbElem1 = elem2_3;
            }
        }
        if (tbElem1.isSuccess()) {
            tbElem1 = CstParseResult.success(null, substring(seqStart2.offset(), pos), location());
        }
        inTokenBoundary = false;
        children.clear();
        children.addAll(savedChildrenTb1);
        CstParseResult alt0_0;
        if (tbElem1.isSuccess()) {
            var tbText1 = substring(tbStart1.offset(), pos);
            var tbSpan1 = SourceSpan.of(tbStart1, location());
            var tbNode1 = new CstNode.Token(tbSpan1, RULE_PEG_TOKEN, tbText1, List.of(), List.of());
            children.add(tbNode1);
            alt0_0 = CstParseResult.success(tbNode1, tbText1, location());
        } else {
            alt0_0 = tbElem1;
        }
        if (alt0_0.isSuccess()) {
            result = alt0_0;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var tbStart10 = location();
        inTokenBoundary = true;
        var savedChildrenTb10 = new ArrayList<>(children);
        CstParseResult tbElem10 = CstParseResult.success(null, "", location());
        var seqStart11 = location();
        if (tbElem10.isSuccess()) {
            var elem11_0 = matchLiteralCst("0", false);
            if (elem11_0.isFailure()) {
                restoreLocation(seqStart11);
                tbElem10 = elem11_0;
            }
        }
        if (tbElem10.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var elem11_1 = matchCharClassCst("bB", false, false);
            if (elem11_1.isFailure()) {
                restoreLocation(seqStart11);
                tbElem10 = elem11_1;
            }
        }
        if (tbElem10.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var oomFirst14 = matchCharClassCst("01_", false, false);
            var elem11_2 = oomFirst14;
            if (oomFirst14.isSuccess()) {
                var oomStart14 = location();
                while (true) {
                    var beforeLoc14 = location();
                    if (!inTokenBoundary) skipWhitespace();
                    var oomElem14 = matchCharClassCst("01_", false, false);
                    if (oomElem14.isFailure() || location().offset() == beforeLoc14.offset()) {
                        restoreLocation(beforeLoc14);
                        break;
                    }
                }
            }
            if (elem11_2.isFailure()) {
                restoreLocation(seqStart11);
                tbElem10 = elem11_2;
            }
        }
        if (tbElem10.isSuccess()) {
            var optStart17 = location();
            if (!inTokenBoundary) skipWhitespace();
            var optElem17 = matchCharClassCst("lL", false, false);
            var elem11_3 = optElem17.isSuccess() ? optElem17 : CstParseResult.success(null, "", location());
            if (optElem17.isFailure()) {
                restoreLocation(optStart17);
            }
            if (elem11_3.isFailure()) {
                restoreLocation(seqStart11);
                tbElem10 = elem11_3;
            }
        }
        if (tbElem10.isSuccess()) {
            tbElem10 = CstParseResult.success(null, substring(seqStart11.offset(), pos), location());
        }
        inTokenBoundary = false;
        children.clear();
        children.addAll(savedChildrenTb10);
        CstParseResult alt0_1;
        if (tbElem10.isSuccess()) {
            var tbText10 = substring(tbStart10.offset(), pos);
            var tbSpan10 = SourceSpan.of(tbStart10, location());
            var tbNode10 = new CstNode.Token(tbSpan10, RULE_PEG_TOKEN, tbText10, List.of(), List.of());
            children.add(tbNode10);
            alt0_1 = CstParseResult.success(tbNode10, tbText10, location());
        } else {
            alt0_1 = tbElem10;
        }
        if (alt0_1.isSuccess()) {
            result = alt0_1;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var tbStart19 = location();
        inTokenBoundary = true;
        var savedChildrenTb19 = new ArrayList<>(children);
        CstParseResult tbElem19 = CstParseResult.success(null, "", location());
        var seqStart20 = location();
        if (tbElem19.isSuccess()) {
            var elem20_0 = matchCharClassCst("0-9", false, false);
            if (elem20_0.isFailure()) {
                restoreLocation(seqStart20);
                tbElem19 = elem20_0;
            }
        }
        if (tbElem19.isSuccess()) {
            CstParseResult elem20_1 = CstParseResult.success(null, "", location());
            var zomStart22 = location();
            while (true) {
                var beforeLoc22 = location();
                if (!inTokenBoundary) skipWhitespace();
                var zomElem22 = matchCharClassCst("0-9_", false, false);
                if (zomElem22.isFailure() || location().offset() == beforeLoc22.offset()) {
                    restoreLocation(beforeLoc22);
                    break;
                }
            }
            elem20_1 = CstParseResult.success(null, substring(zomStart22.offset(), pos), location());
            if (elem20_1.isFailure()) {
                restoreLocation(seqStart20);
                tbElem19 = elem20_1;
            }
        }
        if (tbElem19.isSuccess()) {
            var optStart24 = location();
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult optElem24 = CstParseResult.success(null, "", location());
            var seqStart26 = location();
            if (optElem24.isSuccess()) {
                var elem26_0 = matchLiteralCst(".", false);
                if (elem26_0.isFailure()) {
                    restoreLocation(seqStart26);
                    optElem24 = elem26_0;
                }
            }
            if (optElem24.isSuccess()) {
                CstParseResult elem26_1 = CstParseResult.success(null, "", location());
                var zomStart28 = location();
                while (true) {
                    var beforeLoc28 = location();
                    if (!inTokenBoundary) skipWhitespace();
                    var zomElem28 = matchCharClassCst("0-9_", false, false);
                    if (zomElem28.isFailure() || location().offset() == beforeLoc28.offset()) {
                        restoreLocation(beforeLoc28);
                        break;
                    }
                }
                elem26_1 = CstParseResult.success(null, substring(zomStart28.offset(), pos), location());
                if (elem26_1.isFailure()) {
                    restoreLocation(seqStart26);
                    optElem24 = elem26_1;
                }
            }
            if (optElem24.isSuccess()) {
                optElem24 = CstParseResult.success(null, substring(seqStart26.offset(), pos), location());
            }
            var elem20_2 = optElem24.isSuccess() ? optElem24 : CstParseResult.success(null, "", location());
            if (optElem24.isFailure()) {
                restoreLocation(optStart24);
            }
            if (elem20_2.isFailure()) {
                restoreLocation(seqStart20);
                tbElem19 = elem20_2;
            }
        }
        if (tbElem19.isSuccess()) {
            var optStart30 = location();
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult optElem30 = CstParseResult.success(null, "", location());
            var seqStart32 = location();
            if (optElem30.isSuccess()) {
                var elem32_0 = matchCharClassCst("eE", false, false);
                if (elem32_0.isFailure()) {
                    restoreLocation(seqStart32);
                    optElem30 = elem32_0;
                }
            }
            if (optElem30.isSuccess()) {
                var optStart34 = location();
                if (!inTokenBoundary) skipWhitespace();
                var optElem34 = matchCharClassCst("+\\-", false, false);
                var elem32_1 = optElem34.isSuccess() ? optElem34 : CstParseResult.success(null, "", location());
                if (optElem34.isFailure()) {
                    restoreLocation(optStart34);
                }
                if (elem32_1.isFailure()) {
                    restoreLocation(seqStart32);
                    optElem30 = elem32_1;
                }
            }
            if (optElem30.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var oomFirst36 = matchCharClassCst("0-9_", false, false);
                var elem32_2 = oomFirst36;
                if (oomFirst36.isSuccess()) {
                    var oomStart36 = location();
                    while (true) {
                        var beforeLoc36 = location();
                        if (!inTokenBoundary) skipWhitespace();
                        var oomElem36 = matchCharClassCst("0-9_", false, false);
                        if (oomElem36.isFailure() || location().offset() == beforeLoc36.offset()) {
                            restoreLocation(beforeLoc36);
                            break;
                        }
                    }
                }
                if (elem32_2.isFailure()) {
                    restoreLocation(seqStart32);
                    optElem30 = elem32_2;
                }
            }
            if (optElem30.isSuccess()) {
                optElem30 = CstParseResult.success(null, substring(seqStart32.offset(), pos), location());
            }
            var elem20_3 = optElem30.isSuccess() ? optElem30 : CstParseResult.success(null, "", location());
            if (optElem30.isFailure()) {
                restoreLocation(optStart30);
            }
            if (elem20_3.isFailure()) {
                restoreLocation(seqStart20);
                tbElem19 = elem20_3;
            }
        }
        if (tbElem19.isSuccess()) {
            var optStart39 = location();
            if (!inTokenBoundary) skipWhitespace();
            var optElem39 = matchCharClassCst("fFdDlL", false, false);
            var elem20_4 = optElem39.isSuccess() ? optElem39 : CstParseResult.success(null, "", location());
            if (optElem39.isFailure()) {
                restoreLocation(optStart39);
            }
            if (elem20_4.isFailure()) {
                restoreLocation(seqStart20);
                tbElem19 = elem20_4;
            }
        }
        if (tbElem19.isSuccess()) {
            tbElem19 = CstParseResult.success(null, substring(seqStart20.offset(), pos), location());
        }
        inTokenBoundary = false;
        children.clear();
        children.addAll(savedChildrenTb19);
        CstParseResult alt0_2;
        if (tbElem19.isSuccess()) {
            var tbText19 = substring(tbStart19.offset(), pos);
            var tbSpan19 = SourceSpan.of(tbStart19, location());
            var tbNode19 = new CstNode.Token(tbSpan19, RULE_PEG_TOKEN, tbText19, List.of(), List.of());
            children.add(tbNode19);
            alt0_2 = CstParseResult.success(tbNode19, tbText19, location());
        } else {
            alt0_2 = tbElem19;
        }
        if (alt0_2.isSuccess()) {
            result = alt0_2;
        } else {
            restoreLocation(choiceStart0);
        children.clear();
        children.addAll(savedChildren0);
        var tbStart41 = location();
        inTokenBoundary = true;
        var savedChildrenTb41 = new ArrayList<>(children);
        CstParseResult tbElem41 = CstParseResult.success(null, "", location());
        var seqStart42 = location();
        if (tbElem41.isSuccess()) {
            var elem42_0 = matchLiteralCst(".", false);
            if (elem42_0.isFailure()) {
                restoreLocation(seqStart42);
                tbElem41 = elem42_0;
            }
        }
        if (tbElem41.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var oomFirst44 = matchCharClassCst("0-9_", false, false);
            var elem42_1 = oomFirst44;
            if (oomFirst44.isSuccess()) {
                var oomStart44 = location();
                while (true) {
                    var beforeLoc44 = location();
                    if (!inTokenBoundary) skipWhitespace();
                    var oomElem44 = matchCharClassCst("0-9_", false, false);
                    if (oomElem44.isFailure() || location().offset() == beforeLoc44.offset()) {
                        restoreLocation(beforeLoc44);
                        break;
                    }
                }
            }
            if (elem42_1.isFailure()) {
                restoreLocation(seqStart42);
                tbElem41 = elem42_1;
            }
        }
        if (tbElem41.isSuccess()) {
            var optStart47 = location();
            if (!inTokenBoundary) skipWhitespace();
            CstParseResult optElem47 = CstParseResult.success(null, "", location());
            var seqStart49 = location();
            if (optElem47.isSuccess()) {
                var elem49_0 = matchCharClassCst("eE", false, false);
                if (elem49_0.isFailure()) {
                    restoreLocation(seqStart49);
                    optElem47 = elem49_0;
                }
            }
            if (optElem47.isSuccess()) {
                var optStart51 = location();
                if (!inTokenBoundary) skipWhitespace();
                var optElem51 = matchCharClassCst("+\\-", false, false);
                var elem49_1 = optElem51.isSuccess() ? optElem51 : CstParseResult.success(null, "", location());
                if (optElem51.isFailure()) {
                    restoreLocation(optStart51);
                }
                if (elem49_1.isFailure()) {
                    restoreLocation(seqStart49);
                    optElem47 = elem49_1;
                }
            }
            if (optElem47.isSuccess()) {
                if (!inTokenBoundary) skipWhitespace();
                var oomFirst53 = matchCharClassCst("0-9_", false, false);
                var elem49_2 = oomFirst53;
                if (oomFirst53.isSuccess()) {
                    var oomStart53 = location();
                    while (true) {
                        var beforeLoc53 = location();
                        if (!inTokenBoundary) skipWhitespace();
                        var oomElem53 = matchCharClassCst("0-9_", false, false);
                        if (oomElem53.isFailure() || location().offset() == beforeLoc53.offset()) {
                            restoreLocation(beforeLoc53);
                            break;
                        }
                    }
                }
                if (elem49_2.isFailure()) {
                    restoreLocation(seqStart49);
                    optElem47 = elem49_2;
                }
            }
            if (optElem47.isSuccess()) {
                optElem47 = CstParseResult.success(null, substring(seqStart49.offset(), pos), location());
            }
            var elem42_2 = optElem47.isSuccess() ? optElem47 : CstParseResult.success(null, "", location());
            if (optElem47.isFailure()) {
                restoreLocation(optStart47);
            }
            if (elem42_2.isFailure()) {
                restoreLocation(seqStart42);
                tbElem41 = elem42_2;
            }
        }
        if (tbElem41.isSuccess()) {
            var optStart56 = location();
            if (!inTokenBoundary) skipWhitespace();
            var optElem56 = matchCharClassCst("fFdD", false, false);
            var elem42_3 = optElem56.isSuccess() ? optElem56 : CstParseResult.success(null, "", location());
            if (optElem56.isFailure()) {
                restoreLocation(optStart56);
            }
            if (elem42_3.isFailure()) {
                restoreLocation(seqStart42);
                tbElem41 = elem42_3;
            }
        }
        if (tbElem41.isSuccess()) {
            tbElem41 = CstParseResult.success(null, substring(seqStart42.offset(), pos), location());
        }
        inTokenBoundary = false;
        children.clear();
        children.addAll(savedChildrenTb41);
        CstParseResult alt0_3;
        if (tbElem41.isSuccess()) {
            var tbText41 = substring(tbStart41.offset(), pos);
            var tbSpan41 = SourceSpan.of(tbStart41, location());
            var tbNode41 = new CstNode.Token(tbSpan41, RULE_PEG_TOKEN, tbText41, List.of(), List.of());
            children.add(tbNode41);
            alt0_3 = CstParseResult.success(tbNode41, tbText41, location());
        } else {
            alt0_3 = tbElem41;
        }
        if (alt0_3.isSuccess()) {
            result = alt0_3;
        } else {
            restoreLocation(choiceStart0);
        }
        }
        }
        }
        if (result == null) {
            children.clear();
            children.addAll(savedChildren0);
            result = CstParseResult.failure("one of alternatives");
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_NUM_LIT, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    private CstParseResult parse_Keyword(List<Trivia> leadingTrivia) {
        var startLoc = location();
        
        // Check cache
        long key = cacheKey(114, startLoc.offset());
        var cached = cache.get(key);
        if (cached != null) {
            if (cached.isSuccess()) restoreLocation(cached.endLocation);
            return cached;
        }
        
        var children = new ArrayList<CstNode>();
        
        CstParseResult result = CstParseResult.success(null, "", location());
        var seqStart0 = location();
        if (result.isSuccess()) {
            CstParseResult elem0_0 = null;
            var choiceStart2 = location();
            var savedChildren2 = new ArrayList<>(children);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_0 = matchLiteralCst("abstract", false);
            if (alt2_0.isSuccess() && alt2_0.node != null) {
                children.add(alt2_0.node);
            }
            if (alt2_0.isSuccess()) {
                elem0_0 = alt2_0;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_1 = matchLiteralCst("assert", false);
            if (alt2_1.isSuccess() && alt2_1.node != null) {
                children.add(alt2_1.node);
            }
            if (alt2_1.isSuccess()) {
                elem0_0 = alt2_1;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_2 = matchLiteralCst("boolean", false);
            if (alt2_2.isSuccess() && alt2_2.node != null) {
                children.add(alt2_2.node);
            }
            if (alt2_2.isSuccess()) {
                elem0_0 = alt2_2;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_3 = matchLiteralCst("break", false);
            if (alt2_3.isSuccess() && alt2_3.node != null) {
                children.add(alt2_3.node);
            }
            if (alt2_3.isSuccess()) {
                elem0_0 = alt2_3;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_4 = matchLiteralCst("byte", false);
            if (alt2_4.isSuccess() && alt2_4.node != null) {
                children.add(alt2_4.node);
            }
            if (alt2_4.isSuccess()) {
                elem0_0 = alt2_4;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_5 = matchLiteralCst("case", false);
            if (alt2_5.isSuccess() && alt2_5.node != null) {
                children.add(alt2_5.node);
            }
            if (alt2_5.isSuccess()) {
                elem0_0 = alt2_5;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_6 = matchLiteralCst("catch", false);
            if (alt2_6.isSuccess() && alt2_6.node != null) {
                children.add(alt2_6.node);
            }
            if (alt2_6.isSuccess()) {
                elem0_0 = alt2_6;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_7 = matchLiteralCst("char", false);
            if (alt2_7.isSuccess() && alt2_7.node != null) {
                children.add(alt2_7.node);
            }
            if (alt2_7.isSuccess()) {
                elem0_0 = alt2_7;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_8 = matchLiteralCst("class", false);
            if (alt2_8.isSuccess() && alt2_8.node != null) {
                children.add(alt2_8.node);
            }
            if (alt2_8.isSuccess()) {
                elem0_0 = alt2_8;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_9 = matchLiteralCst("const", false);
            if (alt2_9.isSuccess() && alt2_9.node != null) {
                children.add(alt2_9.node);
            }
            if (alt2_9.isSuccess()) {
                elem0_0 = alt2_9;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_10 = matchLiteralCst("continue", false);
            if (alt2_10.isSuccess() && alt2_10.node != null) {
                children.add(alt2_10.node);
            }
            if (alt2_10.isSuccess()) {
                elem0_0 = alt2_10;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_11 = matchLiteralCst("default", false);
            if (alt2_11.isSuccess() && alt2_11.node != null) {
                children.add(alt2_11.node);
            }
            if (alt2_11.isSuccess()) {
                elem0_0 = alt2_11;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_12 = matchLiteralCst("double", false);
            if (alt2_12.isSuccess() && alt2_12.node != null) {
                children.add(alt2_12.node);
            }
            if (alt2_12.isSuccess()) {
                elem0_0 = alt2_12;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_13 = matchLiteralCst("do", false);
            if (alt2_13.isSuccess() && alt2_13.node != null) {
                children.add(alt2_13.node);
            }
            if (alt2_13.isSuccess()) {
                elem0_0 = alt2_13;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_14 = matchLiteralCst("else", false);
            if (alt2_14.isSuccess() && alt2_14.node != null) {
                children.add(alt2_14.node);
            }
            if (alt2_14.isSuccess()) {
                elem0_0 = alt2_14;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_15 = matchLiteralCst("enum", false);
            if (alt2_15.isSuccess() && alt2_15.node != null) {
                children.add(alt2_15.node);
            }
            if (alt2_15.isSuccess()) {
                elem0_0 = alt2_15;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_16 = matchLiteralCst("extends", false);
            if (alt2_16.isSuccess() && alt2_16.node != null) {
                children.add(alt2_16.node);
            }
            if (alt2_16.isSuccess()) {
                elem0_0 = alt2_16;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_17 = matchLiteralCst("false", false);
            if (alt2_17.isSuccess() && alt2_17.node != null) {
                children.add(alt2_17.node);
            }
            if (alt2_17.isSuccess()) {
                elem0_0 = alt2_17;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_18 = matchLiteralCst("finally", false);
            if (alt2_18.isSuccess() && alt2_18.node != null) {
                children.add(alt2_18.node);
            }
            if (alt2_18.isSuccess()) {
                elem0_0 = alt2_18;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_19 = matchLiteralCst("final", false);
            if (alt2_19.isSuccess() && alt2_19.node != null) {
                children.add(alt2_19.node);
            }
            if (alt2_19.isSuccess()) {
                elem0_0 = alt2_19;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_20 = matchLiteralCst("float", false);
            if (alt2_20.isSuccess() && alt2_20.node != null) {
                children.add(alt2_20.node);
            }
            if (alt2_20.isSuccess()) {
                elem0_0 = alt2_20;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_21 = matchLiteralCst("for", false);
            if (alt2_21.isSuccess() && alt2_21.node != null) {
                children.add(alt2_21.node);
            }
            if (alt2_21.isSuccess()) {
                elem0_0 = alt2_21;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_22 = matchLiteralCst("goto", false);
            if (alt2_22.isSuccess() && alt2_22.node != null) {
                children.add(alt2_22.node);
            }
            if (alt2_22.isSuccess()) {
                elem0_0 = alt2_22;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_23 = matchLiteralCst("implements", false);
            if (alt2_23.isSuccess() && alt2_23.node != null) {
                children.add(alt2_23.node);
            }
            if (alt2_23.isSuccess()) {
                elem0_0 = alt2_23;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_24 = matchLiteralCst("import", false);
            if (alt2_24.isSuccess() && alt2_24.node != null) {
                children.add(alt2_24.node);
            }
            if (alt2_24.isSuccess()) {
                elem0_0 = alt2_24;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_25 = matchLiteralCst("instanceof", false);
            if (alt2_25.isSuccess() && alt2_25.node != null) {
                children.add(alt2_25.node);
            }
            if (alt2_25.isSuccess()) {
                elem0_0 = alt2_25;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_26 = matchLiteralCst("interface", false);
            if (alt2_26.isSuccess() && alt2_26.node != null) {
                children.add(alt2_26.node);
            }
            if (alt2_26.isSuccess()) {
                elem0_0 = alt2_26;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_27 = matchLiteralCst("int", false);
            if (alt2_27.isSuccess() && alt2_27.node != null) {
                children.add(alt2_27.node);
            }
            if (alt2_27.isSuccess()) {
                elem0_0 = alt2_27;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_28 = matchLiteralCst("if", false);
            if (alt2_28.isSuccess() && alt2_28.node != null) {
                children.add(alt2_28.node);
            }
            if (alt2_28.isSuccess()) {
                elem0_0 = alt2_28;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_29 = matchLiteralCst("long", false);
            if (alt2_29.isSuccess() && alt2_29.node != null) {
                children.add(alt2_29.node);
            }
            if (alt2_29.isSuccess()) {
                elem0_0 = alt2_29;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_30 = matchLiteralCst("native", false);
            if (alt2_30.isSuccess() && alt2_30.node != null) {
                children.add(alt2_30.node);
            }
            if (alt2_30.isSuccess()) {
                elem0_0 = alt2_30;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_31 = matchLiteralCst("new", false);
            if (alt2_31.isSuccess() && alt2_31.node != null) {
                children.add(alt2_31.node);
            }
            if (alt2_31.isSuccess()) {
                elem0_0 = alt2_31;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_32 = matchLiteralCst("null", false);
            if (alt2_32.isSuccess() && alt2_32.node != null) {
                children.add(alt2_32.node);
            }
            if (alt2_32.isSuccess()) {
                elem0_0 = alt2_32;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_33 = matchLiteralCst("package", false);
            if (alt2_33.isSuccess() && alt2_33.node != null) {
                children.add(alt2_33.node);
            }
            if (alt2_33.isSuccess()) {
                elem0_0 = alt2_33;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_34 = matchLiteralCst("private", false);
            if (alt2_34.isSuccess() && alt2_34.node != null) {
                children.add(alt2_34.node);
            }
            if (alt2_34.isSuccess()) {
                elem0_0 = alt2_34;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_35 = matchLiteralCst("protected", false);
            if (alt2_35.isSuccess() && alt2_35.node != null) {
                children.add(alt2_35.node);
            }
            if (alt2_35.isSuccess()) {
                elem0_0 = alt2_35;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_36 = matchLiteralCst("public", false);
            if (alt2_36.isSuccess() && alt2_36.node != null) {
                children.add(alt2_36.node);
            }
            if (alt2_36.isSuccess()) {
                elem0_0 = alt2_36;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_37 = matchLiteralCst("return", false);
            if (alt2_37.isSuccess() && alt2_37.node != null) {
                children.add(alt2_37.node);
            }
            if (alt2_37.isSuccess()) {
                elem0_0 = alt2_37;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_38 = matchLiteralCst("short", false);
            if (alt2_38.isSuccess() && alt2_38.node != null) {
                children.add(alt2_38.node);
            }
            if (alt2_38.isSuccess()) {
                elem0_0 = alt2_38;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_39 = matchLiteralCst("static", false);
            if (alt2_39.isSuccess() && alt2_39.node != null) {
                children.add(alt2_39.node);
            }
            if (alt2_39.isSuccess()) {
                elem0_0 = alt2_39;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_40 = matchLiteralCst("strictfp", false);
            if (alt2_40.isSuccess() && alt2_40.node != null) {
                children.add(alt2_40.node);
            }
            if (alt2_40.isSuccess()) {
                elem0_0 = alt2_40;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_41 = matchLiteralCst("super", false);
            if (alt2_41.isSuccess() && alt2_41.node != null) {
                children.add(alt2_41.node);
            }
            if (alt2_41.isSuccess()) {
                elem0_0 = alt2_41;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_42 = matchLiteralCst("switch", false);
            if (alt2_42.isSuccess() && alt2_42.node != null) {
                children.add(alt2_42.node);
            }
            if (alt2_42.isSuccess()) {
                elem0_0 = alt2_42;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_43 = matchLiteralCst("synchronized", false);
            if (alt2_43.isSuccess() && alt2_43.node != null) {
                children.add(alt2_43.node);
            }
            if (alt2_43.isSuccess()) {
                elem0_0 = alt2_43;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_44 = matchLiteralCst("this", false);
            if (alt2_44.isSuccess() && alt2_44.node != null) {
                children.add(alt2_44.node);
            }
            if (alt2_44.isSuccess()) {
                elem0_0 = alt2_44;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_45 = matchLiteralCst("throws", false);
            if (alt2_45.isSuccess() && alt2_45.node != null) {
                children.add(alt2_45.node);
            }
            if (alt2_45.isSuccess()) {
                elem0_0 = alt2_45;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_46 = matchLiteralCst("throw", false);
            if (alt2_46.isSuccess() && alt2_46.node != null) {
                children.add(alt2_46.node);
            }
            if (alt2_46.isSuccess()) {
                elem0_0 = alt2_46;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_47 = matchLiteralCst("transient", false);
            if (alt2_47.isSuccess() && alt2_47.node != null) {
                children.add(alt2_47.node);
            }
            if (alt2_47.isSuccess()) {
                elem0_0 = alt2_47;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_48 = matchLiteralCst("true", false);
            if (alt2_48.isSuccess() && alt2_48.node != null) {
                children.add(alt2_48.node);
            }
            if (alt2_48.isSuccess()) {
                elem0_0 = alt2_48;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_49 = matchLiteralCst("try", false);
            if (alt2_49.isSuccess() && alt2_49.node != null) {
                children.add(alt2_49.node);
            }
            if (alt2_49.isSuccess()) {
                elem0_0 = alt2_49;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_50 = matchLiteralCst("void", false);
            if (alt2_50.isSuccess() && alt2_50.node != null) {
                children.add(alt2_50.node);
            }
            if (alt2_50.isSuccess()) {
                elem0_0 = alt2_50;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_51 = matchLiteralCst("volatile", false);
            if (alt2_51.isSuccess() && alt2_51.node != null) {
                children.add(alt2_51.node);
            }
            if (alt2_51.isSuccess()) {
                elem0_0 = alt2_51;
            } else {
                restoreLocation(choiceStart2);
            children.clear();
            children.addAll(savedChildren2);
            var alt2_52 = matchLiteralCst("while", false);
            if (alt2_52.isSuccess() && alt2_52.node != null) {
                children.add(alt2_52.node);
            }
            if (alt2_52.isSuccess()) {
                elem0_0 = alt2_52;
            } else {
                restoreLocation(choiceStart2);
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            }
            if (elem0_0 == null) {
                children.clear();
                children.addAll(savedChildren2);
                elem0_0 = CstParseResult.failure("one of alternatives");
            }
            if (elem0_0.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_0;
            }
        }
        if (result.isSuccess()) {
            if (!inTokenBoundary) skipWhitespace();
            var notStart56 = location();
            var savedChildrenNot56 = new ArrayList<>(children);
            var notElem56 = matchCharClassCst("a-zA-Z0-9_$", false, false);
            restoreLocation(notStart56);
            children.clear();
            children.addAll(savedChildrenNot56);
            var elem0_1 = notElem56.isSuccess() ? CstParseResult.failure("not match") : CstParseResult.success(null, "", location());
            if (elem0_1.isFailure()) {
                restoreLocation(seqStart0);
                result = elem0_1;
            }
        }
        if (result.isSuccess()) {
            result = CstParseResult.success(null, substring(seqStart0.offset(), pos), location());
        }
        
        CstParseResult finalResult;
        if (result.isSuccess()) {
            var endLoc = location();
            var span = SourceSpan.of(startLoc, endLoc);
            var node = new CstNode.NonTerminal(span, RULE_KEYWORD, children, leadingTrivia, List.of());
            finalResult = CstParseResult.success(node, result.text, endLoc);
        } else {
            restoreLocation(startLoc);
            finalResult = result;
        }
        
        cache.put(key, finalResult);
        return finalResult;
    }

    // === Helper Methods ===

    private List<Trivia> skipWhitespace() {
        var trivia = new ArrayList<Trivia>();
        if (inTokenBoundary) return trivia;
        while (!isAtEnd()) {
            var wsStartLoc = location();
            var wsStartPos = pos;
            CstParseResult wsResult = null;
            var choiceStart1 = location();
            var alt1_0 = matchCharClassCst(" \\t\\r\\n", false, false);
            if (alt1_0.isSuccess()) {
                wsResult = alt1_0;
            } else {
                restoreLocation(choiceStart1);
            CstParseResult alt1_1 = CstParseResult.success(null, "", location());
            var seqStart3 = location();
            if (alt1_1.isSuccess()) {
                var elem3_0 = matchLiteralCst("//", false);
                if (elem3_0.isFailure()) {
                    restoreLocation(seqStart3);
                    alt1_1 = elem3_0;
                }
            }
            if (alt1_1.isSuccess()) {
                CstParseResult elem3_1 = CstParseResult.success(null, "", location());
                var zomStart5 = location();
                while (true) {
                    var beforeLoc5 = location();
                    var zomElem5 = matchCharClassCst("\\n", true, false);
                    if (zomElem5.isFailure() || location().offset() == beforeLoc5.offset()) {
                        restoreLocation(beforeLoc5);
                        break;
                    }
                }
                elem3_1 = CstParseResult.success(null, substring(zomStart5.offset(), pos), location());
                if (elem3_1.isFailure()) {
                    restoreLocation(seqStart3);
                    alt1_1 = elem3_1;
                }
            }
            if (alt1_1.isSuccess()) {
                alt1_1 = CstParseResult.success(null, substring(seqStart3.offset(), pos), location());
            }
            if (alt1_1.isSuccess()) {
                wsResult = alt1_1;
            } else {
                restoreLocation(choiceStart1);
            CstParseResult alt1_2 = CstParseResult.success(null, "", location());
            var seqStart7 = location();
            if (alt1_2.isSuccess()) {
                var elem7_0 = matchLiteralCst("/*", false);
                if (elem7_0.isFailure()) {
                    restoreLocation(seqStart7);
                    alt1_2 = elem7_0;
                }
            }
            if (alt1_2.isSuccess()) {
                CstParseResult elem7_1 = CstParseResult.success(null, "", location());
                var zomStart9 = location();
                while (true) {
                    var beforeLoc9 = location();
                    CstParseResult zomElem9 = CstParseResult.success(null, "", location());
                    var seqStart11 = location();
                    if (zomElem9.isSuccess()) {
                        var notStart12 = location();
                        var notElem12 = matchLiteralCst("*/", false);
                        restoreLocation(notStart12);
                        var elem11_0 = notElem12.isSuccess() ? CstParseResult.failure("not match") : CstParseResult.success(null, "", location());
                        if (elem11_0.isFailure()) {
                            restoreLocation(seqStart11);
                            zomElem9 = elem11_0;
                        }
                    }
                    if (zomElem9.isSuccess()) {
                        var elem11_1 = matchAnyCst();
                        if (elem11_1.isFailure()) {
                            restoreLocation(seqStart11);
                            zomElem9 = elem11_1;
                        }
                    }
                    if (zomElem9.isSuccess()) {
                        zomElem9 = CstParseResult.success(null, substring(seqStart11.offset(), pos), location());
                    }
                    if (zomElem9.isFailure() || location().offset() == beforeLoc9.offset()) {
                        restoreLocation(beforeLoc9);
                        break;
                    }
                }
                elem7_1 = CstParseResult.success(null, substring(zomStart9.offset(), pos), location());
                if (elem7_1.isFailure()) {
                    restoreLocation(seqStart7);
                    alt1_2 = elem7_1;
                }
            }
            if (alt1_2.isSuccess()) {
                var elem7_2 = matchLiteralCst("*/", false);
                if (elem7_2.isFailure()) {
                    restoreLocation(seqStart7);
                    alt1_2 = elem7_2;
                }
            }
            if (alt1_2.isSuccess()) {
                alt1_2 = CstParseResult.success(null, substring(seqStart7.offset(), pos), location());
            }
            if (alt1_2.isSuccess()) {
                wsResult = alt1_2;
            } else {
                restoreLocation(choiceStart1);
            }
            }
            }
            if (wsResult == null) {
                wsResult = CstParseResult.failure("one of alternatives");
            }
            if (wsResult.isFailure() || pos == wsStartPos) break;
            var wsText = substring(wsStartPos, pos);
            var wsSpan = SourceSpan.of(wsStartLoc, location());
            trivia.add(classifyTrivia(wsSpan, wsText));
        }
        return trivia;
    }

    private Trivia classifyTrivia(SourceSpan span, String text) {
        if (text.startsWith("//")) {
            return new Trivia.LineComment(span, text);
        } else if (text.startsWith("/*")) {
            return new Trivia.BlockComment(span, text);
        } else {
            return new Trivia.Whitespace(span, text);
        }
    }

    private CstNode attachTrailingTrivia(CstNode node, List<Trivia> trailingTrivia) {
        if (trailingTrivia.isEmpty()) {
            return node;
        }
        return switch (node) {
            case CstNode.Terminal t -> new CstNode.Terminal(
                t.span(), t.rule(), t.text(), t.leadingTrivia(), trailingTrivia
            );
            case CstNode.NonTerminal nt -> new CstNode.NonTerminal(
                nt.span(), nt.rule(), nt.children(), nt.leadingTrivia(), trailingTrivia
            );
            case CstNode.Token tok -> new CstNode.Token(
                tok.span(), tok.rule(), tok.text(), tok.leadingTrivia(), trailingTrivia
            );
            case CstNode.Error err -> new CstNode.Error(
                err.span(), err.skippedText(), err.expected(), err.leadingTrivia(), trailingTrivia
            );
        };
    }

    private CstParseResult matchLiteralCst(String text, boolean caseInsensitive) {
        if (remaining() < text.length()) {
            return CstParseResult.failure("'" + text + "'");
        }
        var startLoc = location();
        for (int i = 0; i < text.length(); i++) {
            char expected = text.charAt(i);
            char actual = peek(i);
            if (caseInsensitive) {
                if (Character.toLowerCase(expected) != Character.toLowerCase(actual)) {
                    return CstParseResult.failure("'" + text + "'");
                }
            } else {
                if (expected != actual) {
                    return CstParseResult.failure("'" + text + "'");
                }
            }
        }
        for (int i = 0; i < text.length(); i++) {
            advance();
        }
        var span = SourceSpan.of(startLoc, location());
        var node = new CstNode.Terminal(span, RULE_PEG_LITERAL, text, List.of(), List.of());
        return CstParseResult.success(node, text, location());
    }

    private CstParseResult matchDictionaryCst(List<String> words, boolean caseInsensitive) {
        String longestMatch = null;
        int longestLen = 0;
        for (var word : words) {
            if (matchesWord(word, caseInsensitive) && word.length() > longestLen) {
                longestMatch = word;
                longestLen = word.length();
            }
        }
        if (longestMatch == null) {
            return CstParseResult.failure("dictionary word");
        }
        var startLoc = location();
        for (int i = 0; i < longestLen; i++) {
            advance();
        }
        var span = SourceSpan.of(startLoc, location());
        var node = new CstNode.Terminal(span, RULE_PEG_LITERAL, longestMatch, List.of(), List.of());
        return CstParseResult.success(node, longestMatch, location());
    }

    private boolean matchesWord(String word, boolean caseInsensitive) {
        if (remaining() < word.length()) return false;
        for (int i = 0; i < word.length(); i++) {
            char expected = word.charAt(i);
            char actual = peek(i);
            if (caseInsensitive) {
                if (Character.toLowerCase(expected) != Character.toLowerCase(actual)) return false;
            } else {
                if (expected != actual) return false;
            }
        }
        return true;
    }

    private CstParseResult matchCharClassCst(String pattern, boolean negated, boolean caseInsensitive) {
        if (isAtEnd()) {
            return CstParseResult.failure("character class");
        }
        var startLoc = location();
        char c = peek();
        boolean matches = matchesPattern(c, pattern, caseInsensitive);
        if (negated) matches = !matches;
        if (!matches) {
            return CstParseResult.failure("character class");
        }
        advance();
        var text = String.valueOf(c);
        var span = SourceSpan.of(startLoc, location());
        var node = new CstNode.Terminal(span, RULE_PEG_CHAR_CLASS, text, List.of(), List.of());
        return CstParseResult.success(node, text, location());
    }

    private boolean matchesPattern(char c, String pattern, boolean caseInsensitive) {
        char testChar = caseInsensitive ? Character.toLowerCase(c) : c;
        int i = 0;
        while (i < pattern.length()) {
            char start = pattern.charAt(i);
            if (start == '\\' && i + 1 < pattern.length()) {
                char escaped = pattern.charAt(i + 1);
                int consumed = 2;
                char expected = switch (escaped) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    case '\\' -> '\\';
                    case ']' -> ']';
                    case '-' -> '-';
                    case 'x' -> {
                        if (i + 4 <= pattern.length()) {
                            try {
                                var hex = pattern.substring(i + 2, i + 4);
                                consumed = 4;
                                yield (char) Integer.parseInt(hex, 16);
                            } catch (NumberFormatException e) { yield 'x'; }
                        }
                        yield 'x';
                    }
                    case 'u' -> {
                        if (i + 6 <= pattern.length()) {
                            try {
                                var hex = pattern.substring(i + 2, i + 6);
                                consumed = 6;
                                yield (char) Integer.parseInt(hex, 16);
                            } catch (NumberFormatException e) { yield 'u'; }
                        }
                        yield 'u';
                    }
                    default -> escaped;
                };
                if (caseInsensitive) expected = Character.toLowerCase(expected);
                if (testChar == expected) return true;
                i += consumed;
                continue;
            }
            if (i + 2 < pattern.length() && pattern.charAt(i + 1) == '-') {
                char end = pattern.charAt(i + 2);
                if (caseInsensitive) {
                    start = Character.toLowerCase(start);
                    end = Character.toLowerCase(end);
                }
                if (testChar >= start && testChar <= end) return true;
                i += 3;
            } else {
                if (caseInsensitive) start = Character.toLowerCase(start);
                if (testChar == start) return true;
                i++;
            }
        }
        return false;
    }

    private CstParseResult matchAnyCst() {
        if (isAtEnd()) {
            return CstParseResult.failure("any character");
        }
        var startLoc = location();
        char c = advance();
        var text = String.valueOf(c);
        var span = SourceSpan.of(startLoc, location());
        var node = new CstNode.Terminal(span, RULE_PEG_ANY, text, List.of(), List.of());
        return CstParseResult.success(node, text, location());
    }

    // === CST Parse Result ===

    private static final class CstParseResult {
        final boolean success;
        final CstNode node;
        final String text;
        final String expected;
        final SourceLocation endLocation;

        private CstParseResult(boolean success, CstNode node, String text, String expected, SourceLocation endLocation) {
            this.success = success;
            this.node = node;
            this.text = text;
            this.expected = expected;
            this.endLocation = endLocation;
        }

        boolean isSuccess() { return success; }
        boolean isFailure() { return !success; }

        static CstParseResult success(CstNode node, String text, SourceLocation endLocation) {
            return new CstParseResult(true, node, text, null, endLocation);
        }

        static CstParseResult failure(String expected) {
            return new CstParseResult(false, null, null, expected, null);
        }
    }
}

package org.pragmatica.jbct.parser.v6;

import org.pragmatica.peg.v6.cst.CstArray;

public abstract class Java25Visitor<T> {

    protected static final int RULE_CompilationUnit_KIND = 0;
    protected static final int RULE_OrdinaryUnit_KIND = 1;
    protected static final int RULE_PackageDecl_KIND = 2;
    protected static final int RULE_ImportDecl_KIND = 3;
    protected static final int RULE_ModuleDecl_KIND = 4;
    protected static final int RULE_ModuleDirective_KIND = 5;
    protected static final int RULE_RequiresDirective_KIND = 6;
    protected static final int RULE_ExportsDirective_KIND = 7;
    protected static final int RULE_OpensDirective_KIND = 8;
    protected static final int RULE_UsesDirective_KIND = 9;
    protected static final int RULE_ProvidesDirective_KIND = 10;
    protected static final int RULE_TypeDecl_KIND = 11;
    protected static final int RULE_TypeKind_KIND = 12;
    protected static final int RULE_ClassDecl_KIND = 13;
    protected static final int RULE_InterfaceDecl_KIND = 14;
    protected static final int RULE_AnnotationDecl_KIND = 15;
    protected static final int RULE_AnnotationBody_KIND = 16;
    protected static final int RULE_AnnotationMember_KIND = 17;
    protected static final int RULE_AnnotationElemDecl_KIND = 18;
    protected static final int RULE_EnumDecl_KIND = 19;
    protected static final int RULE_RecordDecl_KIND = 20;
    protected static final int RULE_ImplementsClause_KIND = 21;
    protected static final int RULE_PermitsClause_KIND = 22;
    protected static final int RULE_TypeList_KIND = 23;
    protected static final int RULE_TypeParams_KIND = 24;
    protected static final int RULE_TypeParam_KIND = 25;
    protected static final int RULE_ClassBody_KIND = 26;
    protected static final int RULE_ClassMember_KIND = 27;
    protected static final int RULE_Member_KIND = 28;
    protected static final int RULE_InitializerBlock_KIND = 29;
    protected static final int RULE_EnumBody_KIND = 30;
    protected static final int RULE_EnumConsts_KIND = 31;
    protected static final int RULE_EnumConst_KIND = 32;
    protected static final int RULE_RecordComponents_KIND = 33;
    protected static final int RULE_RecordComp_KIND = 34;
    protected static final int RULE_RecordBody_KIND = 35;
    protected static final int RULE_RecordMember_KIND = 36;
    protected static final int RULE_CompactConstructor_KIND = 37;
    protected static final int RULE_FieldDecl_KIND = 38;
    protected static final int RULE_VarDecls_KIND = 39;
    protected static final int RULE_VarDecl_KIND = 40;
    protected static final int RULE_VarInit_KIND = 41;
    protected static final int RULE_MethodDecl_KIND = 42;
    protected static final int RULE_Params_KIND = 43;
    protected static final int RULE_Param_KIND = 44;
    protected static final int RULE_Throws_KIND = 45;
    protected static final int RULE_ConstructorDecl_KIND = 46;
    protected static final int RULE_Block_KIND = 47;
    protected static final int RULE_BlockStmt_KIND = 48;
    protected static final int RULE_LocalTypeDecl_KIND = 49;
    protected static final int RULE_LocalVar_KIND = 50;
    protected static final int RULE_LocalVarType_KIND = 51;
    protected static final int RULE_Stmt_KIND = 52;
    protected static final int RULE_ForCtrl_KIND = 53;
    protected static final int RULE_ForInit_KIND = 54;
    protected static final int RULE_LocalVarNoSemi_KIND = 55;
    protected static final int RULE_ResourceSpec_KIND = 56;
    protected static final int RULE_Resource_KIND = 57;
    protected static final int RULE_Catch_KIND = 58;
    protected static final int RULE_Finally_KIND = 59;
    protected static final int RULE_SwitchBlock_KIND = 60;
    protected static final int RULE_SwitchRule_KIND = 61;
    protected static final int RULE_SwitchLabel_KIND = 62;
    protected static final int RULE_CaseItem_KIND = 63;
    protected static final int RULE_Pattern_KIND = 64;
    protected static final int RULE_TypePattern_KIND = 65;
    protected static final int RULE_RecordPattern_KIND = 66;
    protected static final int RULE_PatternList_KIND = 67;
    protected static final int RULE_Guard_KIND = 68;
    protected static final int RULE_Expr_KIND = 69;
    protected static final int RULE_Assignment_KIND = 70;
    protected static final int RULE_Ternary_KIND = 71;
    protected static final int RULE_LogOr_KIND = 72;
    protected static final int RULE_LogAnd_KIND = 73;
    protected static final int RULE_BitOr_KIND = 74;
    protected static final int RULE_BitXor_KIND = 75;
    protected static final int RULE_BitAnd_KIND = 76;
    protected static final int RULE_Equality_KIND = 77;
    protected static final int RULE_Relational_KIND = 78;
    protected static final int RULE_Shift_KIND = 79;
    protected static final int RULE_Additive_KIND = 80;
    protected static final int RULE_Multiplicative_KIND = 81;
    protected static final int RULE_Unary_KIND = 82;
    protected static final int RULE_Postfix_KIND = 83;
    protected static final int RULE_PostOp_KIND = 84;
    protected static final int RULE_Primary_KIND = 85;
    protected static final int RULE_TypeExpr_KIND = 86;
    protected static final int RULE_Lambda_KIND = 87;
    protected static final int RULE_LambdaParams_KIND = 88;
    protected static final int RULE_LambdaParam_KIND = 89;
    protected static final int RULE_Args_KIND = 90;
    protected static final int RULE_ExprList_KIND = 91;
    protected static final int RULE_Type_KIND = 92;
    protected static final int RULE_RefType_KIND = 93;
    protected static final int RULE_AnnotatedTypeName_KIND = 94;
    protected static final int RULE_Dims_KIND = 95;
    protected static final int RULE_ArrayType_KIND = 96;
    protected static final int RULE_DimExprs_KIND = 97;
    protected static final int RULE_TypeArgs_KIND = 98;
    protected static final int RULE_TypeArg_KIND = 99;
    protected static final int RULE_QualifiedName_KIND = 100;
    protected static final int RULE_Annotation_KIND = 101;
    protected static final int RULE_AnnotationValue_KIND = 102;
    protected static final int RULE_AnnotationElem_KIND = 103;
    protected static final int RULE_Literal_KIND = 104;

    public T visit(CstArray cst, int nodeIdx) {
        int kind = cst.kindAt(nodeIdx);
        return switch (kind) {
            case RULE_CompilationUnit_KIND -> visitCompilationUnit(cst, nodeIdx);
            case RULE_OrdinaryUnit_KIND -> visitOrdinaryUnit(cst, nodeIdx);
            case RULE_PackageDecl_KIND -> visitPackageDecl(cst, nodeIdx);
            case RULE_ImportDecl_KIND -> visitImportDecl(cst, nodeIdx);
            case RULE_ModuleDecl_KIND -> visitModuleDecl(cst, nodeIdx);
            case RULE_ModuleDirective_KIND -> visitModuleDirective(cst, nodeIdx);
            case RULE_RequiresDirective_KIND -> visitRequiresDirective(cst, nodeIdx);
            case RULE_ExportsDirective_KIND -> visitExportsDirective(cst, nodeIdx);
            case RULE_OpensDirective_KIND -> visitOpensDirective(cst, nodeIdx);
            case RULE_UsesDirective_KIND -> visitUsesDirective(cst, nodeIdx);
            case RULE_ProvidesDirective_KIND -> visitProvidesDirective(cst, nodeIdx);
            case RULE_TypeDecl_KIND -> visitTypeDecl(cst, nodeIdx);
            case RULE_TypeKind_KIND -> visitTypeKind(cst, nodeIdx);
            case RULE_ClassDecl_KIND -> visitClassDecl(cst, nodeIdx);
            case RULE_InterfaceDecl_KIND -> visitInterfaceDecl(cst, nodeIdx);
            case RULE_AnnotationDecl_KIND -> visitAnnotationDecl(cst, nodeIdx);
            case RULE_AnnotationBody_KIND -> visitAnnotationBody(cst, nodeIdx);
            case RULE_AnnotationMember_KIND -> visitAnnotationMember(cst, nodeIdx);
            case RULE_AnnotationElemDecl_KIND -> visitAnnotationElemDecl(cst, nodeIdx);
            case RULE_EnumDecl_KIND -> visitEnumDecl(cst, nodeIdx);
            case RULE_RecordDecl_KIND -> visitRecordDecl(cst, nodeIdx);
            case RULE_ImplementsClause_KIND -> visitImplementsClause(cst, nodeIdx);
            case RULE_PermitsClause_KIND -> visitPermitsClause(cst, nodeIdx);
            case RULE_TypeList_KIND -> visitTypeList(cst, nodeIdx);
            case RULE_TypeParams_KIND -> visitTypeParams(cst, nodeIdx);
            case RULE_TypeParam_KIND -> visitTypeParam(cst, nodeIdx);
            case RULE_ClassBody_KIND -> visitClassBody(cst, nodeIdx);
            case RULE_ClassMember_KIND -> visitClassMember(cst, nodeIdx);
            case RULE_Member_KIND -> visitMember(cst, nodeIdx);
            case RULE_InitializerBlock_KIND -> visitInitializerBlock(cst, nodeIdx);
            case RULE_EnumBody_KIND -> visitEnumBody(cst, nodeIdx);
            case RULE_EnumConsts_KIND -> visitEnumConsts(cst, nodeIdx);
            case RULE_EnumConst_KIND -> visitEnumConst(cst, nodeIdx);
            case RULE_RecordComponents_KIND -> visitRecordComponents(cst, nodeIdx);
            case RULE_RecordComp_KIND -> visitRecordComp(cst, nodeIdx);
            case RULE_RecordBody_KIND -> visitRecordBody(cst, nodeIdx);
            case RULE_RecordMember_KIND -> visitRecordMember(cst, nodeIdx);
            case RULE_CompactConstructor_KIND -> visitCompactConstructor(cst, nodeIdx);
            case RULE_FieldDecl_KIND -> visitFieldDecl(cst, nodeIdx);
            case RULE_VarDecls_KIND -> visitVarDecls(cst, nodeIdx);
            case RULE_VarDecl_KIND -> visitVarDecl(cst, nodeIdx);
            case RULE_VarInit_KIND -> visitVarInit(cst, nodeIdx);
            case RULE_MethodDecl_KIND -> visitMethodDecl(cst, nodeIdx);
            case RULE_Params_KIND -> visitParams(cst, nodeIdx);
            case RULE_Param_KIND -> visitParam(cst, nodeIdx);
            case RULE_Throws_KIND -> visitThrows(cst, nodeIdx);
            case RULE_ConstructorDecl_KIND -> visitConstructorDecl(cst, nodeIdx);
            case RULE_Block_KIND -> visitBlock(cst, nodeIdx);
            case RULE_BlockStmt_KIND -> visitBlockStmt(cst, nodeIdx);
            case RULE_LocalTypeDecl_KIND -> visitLocalTypeDecl(cst, nodeIdx);
            case RULE_LocalVar_KIND -> visitLocalVar(cst, nodeIdx);
            case RULE_LocalVarType_KIND -> visitLocalVarType(cst, nodeIdx);
            case RULE_Stmt_KIND -> visitStmt(cst, nodeIdx);
            case RULE_ForCtrl_KIND -> visitForCtrl(cst, nodeIdx);
            case RULE_ForInit_KIND -> visitForInit(cst, nodeIdx);
            case RULE_LocalVarNoSemi_KIND -> visitLocalVarNoSemi(cst, nodeIdx);
            case RULE_ResourceSpec_KIND -> visitResourceSpec(cst, nodeIdx);
            case RULE_Resource_KIND -> visitResource(cst, nodeIdx);
            case RULE_Catch_KIND -> visitCatch(cst, nodeIdx);
            case RULE_Finally_KIND -> visitFinally(cst, nodeIdx);
            case RULE_SwitchBlock_KIND -> visitSwitchBlock(cst, nodeIdx);
            case RULE_SwitchRule_KIND -> visitSwitchRule(cst, nodeIdx);
            case RULE_SwitchLabel_KIND -> visitSwitchLabel(cst, nodeIdx);
            case RULE_CaseItem_KIND -> visitCaseItem(cst, nodeIdx);
            case RULE_Pattern_KIND -> visitPattern(cst, nodeIdx);
            case RULE_TypePattern_KIND -> visitTypePattern(cst, nodeIdx);
            case RULE_RecordPattern_KIND -> visitRecordPattern(cst, nodeIdx);
            case RULE_PatternList_KIND -> visitPatternList(cst, nodeIdx);
            case RULE_Guard_KIND -> visitGuard(cst, nodeIdx);
            case RULE_Expr_KIND -> visitExpr(cst, nodeIdx);
            case RULE_Assignment_KIND -> visitAssignment(cst, nodeIdx);
            case RULE_Ternary_KIND -> visitTernary(cst, nodeIdx);
            case RULE_LogOr_KIND -> visitLogOr(cst, nodeIdx);
            case RULE_LogAnd_KIND -> visitLogAnd(cst, nodeIdx);
            case RULE_BitOr_KIND -> visitBitOr(cst, nodeIdx);
            case RULE_BitXor_KIND -> visitBitXor(cst, nodeIdx);
            case RULE_BitAnd_KIND -> visitBitAnd(cst, nodeIdx);
            case RULE_Equality_KIND -> visitEquality(cst, nodeIdx);
            case RULE_Relational_KIND -> visitRelational(cst, nodeIdx);
            case RULE_Shift_KIND -> visitShift(cst, nodeIdx);
            case RULE_Additive_KIND -> visitAdditive(cst, nodeIdx);
            case RULE_Multiplicative_KIND -> visitMultiplicative(cst, nodeIdx);
            case RULE_Unary_KIND -> visitUnary(cst, nodeIdx);
            case RULE_Postfix_KIND -> visitPostfix(cst, nodeIdx);
            case RULE_PostOp_KIND -> visitPostOp(cst, nodeIdx);
            case RULE_Primary_KIND -> visitPrimary(cst, nodeIdx);
            case RULE_TypeExpr_KIND -> visitTypeExpr(cst, nodeIdx);
            case RULE_Lambda_KIND -> visitLambda(cst, nodeIdx);
            case RULE_LambdaParams_KIND -> visitLambdaParams(cst, nodeIdx);
            case RULE_LambdaParam_KIND -> visitLambdaParam(cst, nodeIdx);
            case RULE_Args_KIND -> visitArgs(cst, nodeIdx);
            case RULE_ExprList_KIND -> visitExprList(cst, nodeIdx);
            case RULE_Type_KIND -> visitType(cst, nodeIdx);
            case RULE_RefType_KIND -> visitRefType(cst, nodeIdx);
            case RULE_AnnotatedTypeName_KIND -> visitAnnotatedTypeName(cst, nodeIdx);
            case RULE_Dims_KIND -> visitDims(cst, nodeIdx);
            case RULE_ArrayType_KIND -> visitArrayType(cst, nodeIdx);
            case RULE_DimExprs_KIND -> visitDimExprs(cst, nodeIdx);
            case RULE_TypeArgs_KIND -> visitTypeArgs(cst, nodeIdx);
            case RULE_TypeArg_KIND -> visitTypeArg(cst, nodeIdx);
            case RULE_QualifiedName_KIND -> visitQualifiedName(cst, nodeIdx);
            case RULE_Annotation_KIND -> visitAnnotation(cst, nodeIdx);
            case RULE_AnnotationValue_KIND -> visitAnnotationValue(cst, nodeIdx);
            case RULE_AnnotationElem_KIND -> visitAnnotationElem(cst, nodeIdx);
            case RULE_Literal_KIND -> visitLiteral(cst, nodeIdx);
            default -> defaultResult();
        };
    }

    protected T visitChildren(CstArray cst, int nodeIdx) {
        T agg = defaultResult();
        var iter = cst.children(nodeIdx).iterator();
        while (iter.hasNext()) {
            int child = iter.next();
            T childResult = visit(cst, child);
            agg = aggregateResult(agg, childResult);
        }
        return agg;
    }

    protected T defaultResult() { return null; }

    protected T aggregateResult(T agg, T next) { return next; }

    public T visitCompilationUnit(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitOrdinaryUnit(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitPackageDecl(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitImportDecl(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitModuleDecl(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitModuleDirective(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitRequiresDirective(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitExportsDirective(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitOpensDirective(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitUsesDirective(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitProvidesDirective(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitTypeDecl(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitTypeKind(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitClassDecl(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitInterfaceDecl(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitAnnotationDecl(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitAnnotationBody(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitAnnotationMember(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitAnnotationElemDecl(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitEnumDecl(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitRecordDecl(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitImplementsClause(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitPermitsClause(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitTypeList(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitTypeParams(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitTypeParam(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitClassBody(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitClassMember(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitMember(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitInitializerBlock(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitEnumBody(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitEnumConsts(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitEnumConst(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitRecordComponents(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitRecordComp(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitRecordBody(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitRecordMember(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitCompactConstructor(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitFieldDecl(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitVarDecls(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitVarDecl(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitVarInit(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitMethodDecl(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitParams(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitParam(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitThrows(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitConstructorDecl(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitBlock(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitBlockStmt(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitLocalTypeDecl(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitLocalVar(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitLocalVarType(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitStmt(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitForCtrl(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitForInit(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitLocalVarNoSemi(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitResourceSpec(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitResource(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitCatch(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitFinally(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitSwitchBlock(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitSwitchRule(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitSwitchLabel(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitCaseItem(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitPattern(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitTypePattern(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitRecordPattern(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitPatternList(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitGuard(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitExpr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitAssignment(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitTernary(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitLogOr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitLogAnd(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitBitOr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitBitXor(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitBitAnd(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitEquality(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitRelational(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitShift(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitAdditive(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitMultiplicative(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitUnary(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitPostfix(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitPostOp(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitPrimary(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitTypeExpr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitLambda(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitLambdaParams(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitLambdaParam(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitArgs(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitExprList(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitType(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitRefType(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitAnnotatedTypeName(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitDims(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitArrayType(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitDimExprs(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitTypeArgs(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitTypeArg(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitQualifiedName(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitAnnotation(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitAnnotationValue(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitAnnotationElem(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitLiteral(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

}

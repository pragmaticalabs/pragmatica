package org.pragmatica.aether.pg.parser;

import org.pragmatica.peg.cst.CstArray;

public abstract class PgSqlVisitor<T> {

    protected static final int RULE_Input_KIND = 0;
    protected static final int RULE_Statement_KIND = 1;
    protected static final int RULE_DdlStatement_KIND = 2;
    protected static final int RULE_CreateStatement_KIND = 3;
    protected static final int RULE_AlterStatement_KIND = 4;
    protected static final int RULE_DropStatement_KIND = 5;
    protected static final int RULE_DmlStatement_KIND = 6;
    protected static final int RULE_CreateTableStmt_KIND = 7;
    protected static final int RULE_TableElementList_KIND = 8;
    protected static final int RULE_TableElement_KIND = 9;
    protected static final int RULE_ColumnDef_KIND = 10;
    protected static final int RULE_ColConstraint_KIND = 11;
    protected static final int RULE_ColConstraintElem_KIND = 12;
    protected static final int RULE_CheckColConstraint_KIND = 13;
    protected static final int RULE_DefaultClause_KIND = 14;
    protected static final int RULE_CollateClause_KIND = 15;
    protected static final int RULE_ReferencesClause_KIND = 16;
    protected static final int RULE_GeneratedClause_KIND = 17;
    protected static final int RULE_IdentityClause_KIND = 18;
    protected static final int RULE_IdentitySpec_KIND = 19;
    protected static final int RULE_TableConstraint_KIND = 20;
    protected static final int RULE_TableConstraintElem_KIND = 21;
    protected static final int RULE_PrimaryKeyTblConstraint_KIND = 22;
    protected static final int RULE_UniqueTblConstraint_KIND = 23;
    protected static final int RULE_CheckTblConstraint_KIND = 24;
    protected static final int RULE_ForeignKeyTblConstraint_KIND = 25;
    protected static final int RULE_ExcludeTblConstraint_KIND = 26;
    protected static final int RULE_ExcludeElementList_KIND = 27;
    protected static final int RULE_ExcludeElement_KIND = 28;
    protected static final int RULE_IndexOptions_KIND = 29;
    protected static final int RULE_IncludeClause_KIND = 30;
    protected static final int RULE_WithStorageParams_KIND = 31;
    protected static final int RULE_StorageParamList_KIND = 32;
    protected static final int RULE_StorageParam_KIND = 33;
    protected static final int RULE_TableOptions_KIND = 34;
    protected static final int RULE_PartitionByClause_KIND = 35;
    protected static final int RULE_PartitionKeyList_KIND = 36;
    protected static final int RULE_PartitionKey_KIND = 37;
    protected static final int RULE_InheritsClause_KIND = 38;
    protected static final int RULE_ColumnList_KIND = 39;
    protected static final int RULE_QualifiedNameList_KIND = 40;
    protected static final int RULE_AlterTableStmt_KIND = 41;
    protected static final int RULE_AlterTableActions_KIND = 42;
    protected static final int RULE_AlterTableAction_KIND = 43;
    protected static final int RULE_AddColumnAction_KIND = 44;
    protected static final int RULE_AlterColumnAction_KIND = 45;
    protected static final int RULE_AlterColumnCmd_KIND = 46;
    protected static final int RULE_SetDataTypeCmd_KIND = 47;
    protected static final int RULE_SetDefaultCmd_KIND = 48;
    protected static final int RULE_SetStatisticsCmd_KIND = 49;
    protected static final int RULE_AddIdentityCmd_KIND = 50;
    protected static final int RULE_AddConstraintAction_KIND = 51;
    protected static final int RULE_AttachPartition_KIND = 52;
    protected static final int RULE_DetachPartition_KIND = 53;
    protected static final int RULE_ForValuesClause_KIND = 54;
    protected static final int RULE_DropTableStmt_KIND = 55;
    protected static final int RULE_CreateIndexStmt_KIND = 56;
    protected static final int RULE_IndexElemList_KIND = 57;
    protected static final int RULE_IndexElem_KIND = 58;
    protected static final int RULE_OpClass_KIND = 59;
    protected static final int RULE_AlterIndexStmt_KIND = 60;
    protected static final int RULE_DropIndexStmt_KIND = 61;
    protected static final int RULE_CreateSequenceStmt_KIND = 62;
    protected static final int RULE_AlterSequenceStmt_KIND = 63;
    protected static final int RULE_DropSequenceStmt_KIND = 64;
    protected static final int RULE_SequenceOptions_KIND = 65;
    protected static final int RULE_SequenceOption_KIND = 66;
    protected static final int RULE_CreateTypeStmt_KIND = 67;
    protected static final int RULE_EnumLabelList_KIND = 68;
    protected static final int RULE_CompositeFieldList_KIND = 69;
    protected static final int RULE_CompositeField_KIND = 70;
    protected static final int RULE_RangeOptionList_KIND = 71;
    protected static final int RULE_RangeOption_KIND = 72;
    protected static final int RULE_DomainConstraint_KIND = 73;
    protected static final int RULE_AlterTypeStmt_KIND = 74;
    protected static final int RULE_DropTypeStmt_KIND = 75;
    protected static final int RULE_DropSchemaStmt_KIND = 76;
    protected static final int RULE_CreateViewStmt_KIND = 77;
    protected static final int RULE_CreateMatViewStmt_KIND = 78;
    protected static final int RULE_AlterViewStmt_KIND = 79;
    protected static final int RULE_DropViewStmt_KIND = 80;
    protected static final int RULE_DropMatViewStmt_KIND = 81;
    protected static final int RULE_CreateExtensionStmt_KIND = 82;
    protected static final int RULE_ExtensionOptions_KIND = 83;
    protected static final int RULE_ExtensionOption_KIND = 84;
    protected static final int RULE_DropExtensionStmt_KIND = 85;
    protected static final int RULE_CommentStatement_KIND = 86;
    protected static final int RULE_CommentTarget_KIND = 87;
    protected static final int RULE_FuncArgTypes_KIND = 88;
    protected static final int RULE_FuncArgType_KIND = 89;
    protected static final int RULE_GrantStatement_KIND = 90;
    protected static final int RULE_RevokeStatement_KIND = 91;
    protected static final int RULE_PrivilegeList_KIND = 92;
    protected static final int RULE_Privilege_KIND = 93;
    protected static final int RULE_GrantTarget_KIND = 94;
    protected static final int RULE_GranteeList_KIND = 95;
    protected static final int RULE_AlterDefaultPrivilegesPassthrough_KIND = 96;
    protected static final int RULE_CreateFunctionPassthrough_KIND = 97;
    protected static final int RULE_CreateTriggerPassthrough_KIND = 98;
    protected static final int RULE_DropFunctionPassthrough_KIND = 99;
    protected static final int RULE_DropTriggerPassthrough_KIND = 100;
    protected static final int RULE_SelectStmt_KIND = 101;
    protected static final int RULE_SelectCore_KIND = 102;
    protected static final int RULE_SetQuantifier_KIND = 103;
    protected static final int RULE_TargetList_KIND = 104;
    protected static final int RULE_TargetElem_KIND = 105;
    protected static final int RULE_StarExpr_KIND = 106;
    protected static final int RULE_IntoClause_KIND = 107;
    protected static final int RULE_FromClause_KIND = 108;
    protected static final int RULE_FromList_KIND = 109;
    protected static final int RULE_TableRef_KIND = 110;
    protected static final int RULE_TableRefBase_KIND = 111;
    protected static final int RULE_BaseTableRef_KIND = 112;
    protected static final int RULE_SubqueryRef_KIND = 113;
    protected static final int RULE_LateralRef_KIND = 114;
    protected static final int RULE_FuncTableRef_KIND = 115;
    protected static final int RULE_Alias_KIND = 116;
    protected static final int RULE_TablesampleClause_KIND = 117;
    protected static final int RULE_JoinExpr_KIND = 118;
    protected static final int RULE_JoinClause_KIND = 119;
    protected static final int RULE_JoinQual_KIND = 120;
    protected static final int RULE_WhereClause_KIND = 121;
    protected static final int RULE_GroupByClause_KIND = 122;
    protected static final int RULE_GroupByList_KIND = 123;
    protected static final int RULE_GroupByElem_KIND = 124;
    protected static final int RULE_HavingClause_KIND = 125;
    protected static final int RULE_WindowClause_KIND = 126;
    protected static final int RULE_WindowDefList_KIND = 127;
    protected static final int RULE_WindowDef_KIND = 128;
    protected static final int RULE_WindowSpec_KIND = 129;
    protected static final int RULE_PartitionClause_KIND = 130;
    protected static final int RULE_FrameClause_KIND = 131;
    protected static final int RULE_FrameExtent_KIND = 132;
    protected static final int RULE_FrameBound_KIND = 133;
    protected static final int RULE_WithClause_KIND = 134;
    protected static final int RULE_CteList_KIND = 135;
    protected static final int RULE_CteDef_KIND = 136;
    protected static final int RULE_SetOp_KIND = 137;
    protected static final int RULE_OrderByClause_KIND = 138;
    protected static final int RULE_OrderByList_KIND = 139;
    protected static final int RULE_OrderByItem_KIND = 140;
    protected static final int RULE_LimitClause_KIND = 141;
    protected static final int RULE_OffsetClause_KIND = 142;
    protected static final int RULE_FetchClause_KIND = 143;
    protected static final int RULE_InsertStmt_KIND = 144;
    protected static final int RULE_InsertSource_KIND = 145;
    protected static final int RULE_ValuesClause_KIND = 146;
    protected static final int RULE_ValueRowList_KIND = 147;
    protected static final int RULE_ExprOrDefaultList_KIND = 148;
    protected static final int RULE_ExprOrDefault_KIND = 149;
    protected static final int RULE_OnConflictClause_KIND = 150;
    protected static final int RULE_ConflictTarget_KIND = 151;
    protected static final int RULE_ConflictAction_KIND = 152;
    protected static final int RULE_ReturningClause_KIND = 153;
    protected static final int RULE_UpdateStmt_KIND = 154;
    protected static final int RULE_UpdateSetList_KIND = 155;
    protected static final int RULE_UpdateSetItem_KIND = 156;
    protected static final int RULE_DeleteStmt_KIND = 157;
    protected static final int RULE_UsingClauseDelete_KIND = 158;
    protected static final int RULE_PassthroughStatement_KIND = 159;
    protected static final int RULE_TransactionStmt_KIND = 160;
    protected static final int RULE_SessionStmt_KIND = 161;
    protected static final int RULE_UtilityStmt_KIND = 162;
    protected static final int RULE_TruncateStmt_KIND = 163;
    protected static final int RULE_ExplainStmt_KIND = 164;
    protected static final int RULE_CopyStmt_KIND = 165;
    protected static final int RULE_RefreshMatViewStmt_KIND = 166;
    protected static final int RULE_RestOfStatement_KIND = 167;
    protected static final int RULE_Expr_KIND = 168;
    protected static final int RULE_OrExpr_KIND = 169;
    protected static final int RULE_AndExpr_KIND = 170;
    protected static final int RULE_NotExpr_KIND = 171;
    protected static final int RULE_CompareExpr_KIND = 172;
    protected static final int RULE_IsExpr_KIND = 173;
    protected static final int RULE_IsClause_KIND = 174;
    protected static final int RULE_InExpr_KIND = 175;
    protected static final int RULE_BetweenExpr_KIND = 176;
    protected static final int RULE_LikeExpr_KIND = 177;
    protected static final int RULE_SimilarToExpr_KIND = 178;
    protected static final int RULE_IsDistinctFrom_KIND = 179;
    protected static final int RULE_AddExpr_KIND = 180;
    protected static final int RULE_MulExpr_KIND = 181;
    protected static final int RULE_UnaryExpr_KIND = 182;
    protected static final int RULE_ExponentExpr_KIND = 183;
    protected static final int RULE_ConcatExpr_KIND = 184;
    protected static final int RULE_ArrayExpr_KIND = 185;
    protected static final int RULE_TypeCastExpr_KIND = 186;
    protected static final int RULE_PostfixExpr_KIND = 187;
    protected static final int RULE_PostfixOp_KIND = 188;
    protected static final int RULE_PrimaryExpr_KIND = 189;
    protected static final int RULE_ColRef_KIND = 190;
    protected static final int RULE_ExistsExpr_KIND = 191;
    protected static final int RULE_SubqueryExpr_KIND = 192;
    protected static final int RULE_AnyAllExpr_KIND = 193;
    protected static final int RULE_RowExpr_KIND = 194;
    protected static final int RULE_ArrayExprConstructor_KIND = 195;
    protected static final int RULE_CastExpr_KIND = 196;
    protected static final int RULE_CaseExpr_KIND = 197;
    protected static final int RULE_WhenClause_KIND = 198;
    protected static final int RULE_ElseClause_KIND = 199;
    protected static final int RULE_CoalesceExpr_KIND = 200;
    protected static final int RULE_NullIfExpr_KIND = 201;
    protected static final int RULE_GreatestLeastExpr_KIND = 202;
    protected static final int RULE_ExtractExpr_KIND = 203;
    protected static final int RULE_PositionExpr_KIND = 204;
    protected static final int RULE_SubstringExpr_KIND = 205;
    protected static final int RULE_TrimExpr_KIND = 206;
    protected static final int RULE_OverlayExpr_KIND = 207;
    protected static final int RULE_TypedLiteral_KIND = 208;
    protected static final int RULE_FuncCall_KIND = 209;
    protected static final int RULE_FuncCallArgs_KIND = 210;
    protected static final int RULE_FuncName_KIND = 211;
    protected static final int RULE_FilterClause_KIND = 212;
    protected static final int RULE_OverClause_KIND = 213;
    protected static final int RULE_WithinGroupClause_KIND = 214;
    protected static final int RULE_ExprList_KIND = 215;
    protected static final int RULE_Operator_KIND = 216;
    protected static final int RULE_DataType_KIND = 217;
    protected static final int RULE_ArrayType_KIND = 218;
    protected static final int RULE_ScalarType_KIND = 219;
    protected static final int RULE_NumericType_KIND = 220;
    protected static final int RULE_CharType_KIND = 221;
    protected static final int RULE_DateTimeType_KIND = 222;
    protected static final int RULE_TimestampType_KIND = 223;
    protected static final int RULE_TimeType_KIND = 224;
    protected static final int RULE_IntervalType_KIND = 225;
    protected static final int RULE_BitType_KIND = 226;
    protected static final int RULE_TypeModifiers_KIND = 227;
    protected static final int RULE_QualifiedTypeName_KIND = 228;
    protected static final int RULE_QualifiedName_KIND = 229;
    protected static final int RULE_Literal_KIND = 230;
    protected static final int RULE_SignedNumericLiteral_KIND = 231;
    protected static final int RULE_StringLiteral_KIND = 232;
    protected static final int RULE_DollarString_KIND = 233;
    protected static final int RULE_ClauseKeyword_KIND = 234;

    public T visit(CstArray cst, int nodeIdx) {
        int kind = cst.kindAt(nodeIdx);
        return switch (kind) {
            case RULE_Input_KIND -> visitInput(cst, nodeIdx);
            case RULE_Statement_KIND -> visitStatement(cst, nodeIdx);
            case RULE_DdlStatement_KIND -> visitDdlStatement(cst, nodeIdx);
            case RULE_CreateStatement_KIND -> visitCreateStatement(cst, nodeIdx);
            case RULE_AlterStatement_KIND -> visitAlterStatement(cst, nodeIdx);
            case RULE_DropStatement_KIND -> visitDropStatement(cst, nodeIdx);
            case RULE_DmlStatement_KIND -> visitDmlStatement(cst, nodeIdx);
            case RULE_CreateTableStmt_KIND -> visitCreateTableStmt(cst, nodeIdx);
            case RULE_TableElementList_KIND -> visitTableElementList(cst, nodeIdx);
            case RULE_TableElement_KIND -> visitTableElement(cst, nodeIdx);
            case RULE_ColumnDef_KIND -> visitColumnDef(cst, nodeIdx);
            case RULE_ColConstraint_KIND -> visitColConstraint(cst, nodeIdx);
            case RULE_ColConstraintElem_KIND -> visitColConstraintElem(cst, nodeIdx);
            case RULE_CheckColConstraint_KIND -> visitCheckColConstraint(cst, nodeIdx);
            case RULE_DefaultClause_KIND -> visitDefaultClause(cst, nodeIdx);
            case RULE_CollateClause_KIND -> visitCollateClause(cst, nodeIdx);
            case RULE_ReferencesClause_KIND -> visitReferencesClause(cst, nodeIdx);
            case RULE_GeneratedClause_KIND -> visitGeneratedClause(cst, nodeIdx);
            case RULE_IdentityClause_KIND -> visitIdentityClause(cst, nodeIdx);
            case RULE_IdentitySpec_KIND -> visitIdentitySpec(cst, nodeIdx);
            case RULE_TableConstraint_KIND -> visitTableConstraint(cst, nodeIdx);
            case RULE_TableConstraintElem_KIND -> visitTableConstraintElem(cst, nodeIdx);
            case RULE_PrimaryKeyTblConstraint_KIND -> visitPrimaryKeyTblConstraint(cst, nodeIdx);
            case RULE_UniqueTblConstraint_KIND -> visitUniqueTblConstraint(cst, nodeIdx);
            case RULE_CheckTblConstraint_KIND -> visitCheckTblConstraint(cst, nodeIdx);
            case RULE_ForeignKeyTblConstraint_KIND -> visitForeignKeyTblConstraint(cst, nodeIdx);
            case RULE_ExcludeTblConstraint_KIND -> visitExcludeTblConstraint(cst, nodeIdx);
            case RULE_ExcludeElementList_KIND -> visitExcludeElementList(cst, nodeIdx);
            case RULE_ExcludeElement_KIND -> visitExcludeElement(cst, nodeIdx);
            case RULE_IndexOptions_KIND -> visitIndexOptions(cst, nodeIdx);
            case RULE_IncludeClause_KIND -> visitIncludeClause(cst, nodeIdx);
            case RULE_WithStorageParams_KIND -> visitWithStorageParams(cst, nodeIdx);
            case RULE_StorageParamList_KIND -> visitStorageParamList(cst, nodeIdx);
            case RULE_StorageParam_KIND -> visitStorageParam(cst, nodeIdx);
            case RULE_TableOptions_KIND -> visitTableOptions(cst, nodeIdx);
            case RULE_PartitionByClause_KIND -> visitPartitionByClause(cst, nodeIdx);
            case RULE_PartitionKeyList_KIND -> visitPartitionKeyList(cst, nodeIdx);
            case RULE_PartitionKey_KIND -> visitPartitionKey(cst, nodeIdx);
            case RULE_InheritsClause_KIND -> visitInheritsClause(cst, nodeIdx);
            case RULE_ColumnList_KIND -> visitColumnList(cst, nodeIdx);
            case RULE_QualifiedNameList_KIND -> visitQualifiedNameList(cst, nodeIdx);
            case RULE_AlterTableStmt_KIND -> visitAlterTableStmt(cst, nodeIdx);
            case RULE_AlterTableActions_KIND -> visitAlterTableActions(cst, nodeIdx);
            case RULE_AlterTableAction_KIND -> visitAlterTableAction(cst, nodeIdx);
            case RULE_AddColumnAction_KIND -> visitAddColumnAction(cst, nodeIdx);
            case RULE_AlterColumnAction_KIND -> visitAlterColumnAction(cst, nodeIdx);
            case RULE_AlterColumnCmd_KIND -> visitAlterColumnCmd(cst, nodeIdx);
            case RULE_SetDataTypeCmd_KIND -> visitSetDataTypeCmd(cst, nodeIdx);
            case RULE_SetDefaultCmd_KIND -> visitSetDefaultCmd(cst, nodeIdx);
            case RULE_SetStatisticsCmd_KIND -> visitSetStatisticsCmd(cst, nodeIdx);
            case RULE_AddIdentityCmd_KIND -> visitAddIdentityCmd(cst, nodeIdx);
            case RULE_AddConstraintAction_KIND -> visitAddConstraintAction(cst, nodeIdx);
            case RULE_AttachPartition_KIND -> visitAttachPartition(cst, nodeIdx);
            case RULE_DetachPartition_KIND -> visitDetachPartition(cst, nodeIdx);
            case RULE_ForValuesClause_KIND -> visitForValuesClause(cst, nodeIdx);
            case RULE_DropTableStmt_KIND -> visitDropTableStmt(cst, nodeIdx);
            case RULE_CreateIndexStmt_KIND -> visitCreateIndexStmt(cst, nodeIdx);
            case RULE_IndexElemList_KIND -> visitIndexElemList(cst, nodeIdx);
            case RULE_IndexElem_KIND -> visitIndexElem(cst, nodeIdx);
            case RULE_OpClass_KIND -> visitOpClass(cst, nodeIdx);
            case RULE_AlterIndexStmt_KIND -> visitAlterIndexStmt(cst, nodeIdx);
            case RULE_DropIndexStmt_KIND -> visitDropIndexStmt(cst, nodeIdx);
            case RULE_CreateSequenceStmt_KIND -> visitCreateSequenceStmt(cst, nodeIdx);
            case RULE_AlterSequenceStmt_KIND -> visitAlterSequenceStmt(cst, nodeIdx);
            case RULE_DropSequenceStmt_KIND -> visitDropSequenceStmt(cst, nodeIdx);
            case RULE_SequenceOptions_KIND -> visitSequenceOptions(cst, nodeIdx);
            case RULE_SequenceOption_KIND -> visitSequenceOption(cst, nodeIdx);
            case RULE_CreateTypeStmt_KIND -> visitCreateTypeStmt(cst, nodeIdx);
            case RULE_EnumLabelList_KIND -> visitEnumLabelList(cst, nodeIdx);
            case RULE_CompositeFieldList_KIND -> visitCompositeFieldList(cst, nodeIdx);
            case RULE_CompositeField_KIND -> visitCompositeField(cst, nodeIdx);
            case RULE_RangeOptionList_KIND -> visitRangeOptionList(cst, nodeIdx);
            case RULE_RangeOption_KIND -> visitRangeOption(cst, nodeIdx);
            case RULE_DomainConstraint_KIND -> visitDomainConstraint(cst, nodeIdx);
            case RULE_AlterTypeStmt_KIND -> visitAlterTypeStmt(cst, nodeIdx);
            case RULE_DropTypeStmt_KIND -> visitDropTypeStmt(cst, nodeIdx);
            case RULE_DropSchemaStmt_KIND -> visitDropSchemaStmt(cst, nodeIdx);
            case RULE_CreateViewStmt_KIND -> visitCreateViewStmt(cst, nodeIdx);
            case RULE_CreateMatViewStmt_KIND -> visitCreateMatViewStmt(cst, nodeIdx);
            case RULE_AlterViewStmt_KIND -> visitAlterViewStmt(cst, nodeIdx);
            case RULE_DropViewStmt_KIND -> visitDropViewStmt(cst, nodeIdx);
            case RULE_DropMatViewStmt_KIND -> visitDropMatViewStmt(cst, nodeIdx);
            case RULE_CreateExtensionStmt_KIND -> visitCreateExtensionStmt(cst, nodeIdx);
            case RULE_ExtensionOptions_KIND -> visitExtensionOptions(cst, nodeIdx);
            case RULE_ExtensionOption_KIND -> visitExtensionOption(cst, nodeIdx);
            case RULE_DropExtensionStmt_KIND -> visitDropExtensionStmt(cst, nodeIdx);
            case RULE_CommentStatement_KIND -> visitCommentStatement(cst, nodeIdx);
            case RULE_CommentTarget_KIND -> visitCommentTarget(cst, nodeIdx);
            case RULE_FuncArgTypes_KIND -> visitFuncArgTypes(cst, nodeIdx);
            case RULE_FuncArgType_KIND -> visitFuncArgType(cst, nodeIdx);
            case RULE_GrantStatement_KIND -> visitGrantStatement(cst, nodeIdx);
            case RULE_RevokeStatement_KIND -> visitRevokeStatement(cst, nodeIdx);
            case RULE_PrivilegeList_KIND -> visitPrivilegeList(cst, nodeIdx);
            case RULE_Privilege_KIND -> visitPrivilege(cst, nodeIdx);
            case RULE_GrantTarget_KIND -> visitGrantTarget(cst, nodeIdx);
            case RULE_GranteeList_KIND -> visitGranteeList(cst, nodeIdx);
            case RULE_AlterDefaultPrivilegesPassthrough_KIND -> visitAlterDefaultPrivilegesPassthrough(cst, nodeIdx);
            case RULE_CreateFunctionPassthrough_KIND -> visitCreateFunctionPassthrough(cst, nodeIdx);
            case RULE_CreateTriggerPassthrough_KIND -> visitCreateTriggerPassthrough(cst, nodeIdx);
            case RULE_DropFunctionPassthrough_KIND -> visitDropFunctionPassthrough(cst, nodeIdx);
            case RULE_DropTriggerPassthrough_KIND -> visitDropTriggerPassthrough(cst, nodeIdx);
            case RULE_SelectStmt_KIND -> visitSelectStmt(cst, nodeIdx);
            case RULE_SelectCore_KIND -> visitSelectCore(cst, nodeIdx);
            case RULE_SetQuantifier_KIND -> visitSetQuantifier(cst, nodeIdx);
            case RULE_TargetList_KIND -> visitTargetList(cst, nodeIdx);
            case RULE_TargetElem_KIND -> visitTargetElem(cst, nodeIdx);
            case RULE_StarExpr_KIND -> visitStarExpr(cst, nodeIdx);
            case RULE_IntoClause_KIND -> visitIntoClause(cst, nodeIdx);
            case RULE_FromClause_KIND -> visitFromClause(cst, nodeIdx);
            case RULE_FromList_KIND -> visitFromList(cst, nodeIdx);
            case RULE_TableRef_KIND -> visitTableRef(cst, nodeIdx);
            case RULE_TableRefBase_KIND -> visitTableRefBase(cst, nodeIdx);
            case RULE_BaseTableRef_KIND -> visitBaseTableRef(cst, nodeIdx);
            case RULE_SubqueryRef_KIND -> visitSubqueryRef(cst, nodeIdx);
            case RULE_LateralRef_KIND -> visitLateralRef(cst, nodeIdx);
            case RULE_FuncTableRef_KIND -> visitFuncTableRef(cst, nodeIdx);
            case RULE_Alias_KIND -> visitAlias(cst, nodeIdx);
            case RULE_TablesampleClause_KIND -> visitTablesampleClause(cst, nodeIdx);
            case RULE_JoinExpr_KIND -> visitJoinExpr(cst, nodeIdx);
            case RULE_JoinClause_KIND -> visitJoinClause(cst, nodeIdx);
            case RULE_JoinQual_KIND -> visitJoinQual(cst, nodeIdx);
            case RULE_WhereClause_KIND -> visitWhereClause(cst, nodeIdx);
            case RULE_GroupByClause_KIND -> visitGroupByClause(cst, nodeIdx);
            case RULE_GroupByList_KIND -> visitGroupByList(cst, nodeIdx);
            case RULE_GroupByElem_KIND -> visitGroupByElem(cst, nodeIdx);
            case RULE_HavingClause_KIND -> visitHavingClause(cst, nodeIdx);
            case RULE_WindowClause_KIND -> visitWindowClause(cst, nodeIdx);
            case RULE_WindowDefList_KIND -> visitWindowDefList(cst, nodeIdx);
            case RULE_WindowDef_KIND -> visitWindowDef(cst, nodeIdx);
            case RULE_WindowSpec_KIND -> visitWindowSpec(cst, nodeIdx);
            case RULE_PartitionClause_KIND -> visitPartitionClause(cst, nodeIdx);
            case RULE_FrameClause_KIND -> visitFrameClause(cst, nodeIdx);
            case RULE_FrameExtent_KIND -> visitFrameExtent(cst, nodeIdx);
            case RULE_FrameBound_KIND -> visitFrameBound(cst, nodeIdx);
            case RULE_WithClause_KIND -> visitWithClause(cst, nodeIdx);
            case RULE_CteList_KIND -> visitCteList(cst, nodeIdx);
            case RULE_CteDef_KIND -> visitCteDef(cst, nodeIdx);
            case RULE_SetOp_KIND -> visitSetOp(cst, nodeIdx);
            case RULE_OrderByClause_KIND -> visitOrderByClause(cst, nodeIdx);
            case RULE_OrderByList_KIND -> visitOrderByList(cst, nodeIdx);
            case RULE_OrderByItem_KIND -> visitOrderByItem(cst, nodeIdx);
            case RULE_LimitClause_KIND -> visitLimitClause(cst, nodeIdx);
            case RULE_OffsetClause_KIND -> visitOffsetClause(cst, nodeIdx);
            case RULE_FetchClause_KIND -> visitFetchClause(cst, nodeIdx);
            case RULE_InsertStmt_KIND -> visitInsertStmt(cst, nodeIdx);
            case RULE_InsertSource_KIND -> visitInsertSource(cst, nodeIdx);
            case RULE_ValuesClause_KIND -> visitValuesClause(cst, nodeIdx);
            case RULE_ValueRowList_KIND -> visitValueRowList(cst, nodeIdx);
            case RULE_ExprOrDefaultList_KIND -> visitExprOrDefaultList(cst, nodeIdx);
            case RULE_ExprOrDefault_KIND -> visitExprOrDefault(cst, nodeIdx);
            case RULE_OnConflictClause_KIND -> visitOnConflictClause(cst, nodeIdx);
            case RULE_ConflictTarget_KIND -> visitConflictTarget(cst, nodeIdx);
            case RULE_ConflictAction_KIND -> visitConflictAction(cst, nodeIdx);
            case RULE_ReturningClause_KIND -> visitReturningClause(cst, nodeIdx);
            case RULE_UpdateStmt_KIND -> visitUpdateStmt(cst, nodeIdx);
            case RULE_UpdateSetList_KIND -> visitUpdateSetList(cst, nodeIdx);
            case RULE_UpdateSetItem_KIND -> visitUpdateSetItem(cst, nodeIdx);
            case RULE_DeleteStmt_KIND -> visitDeleteStmt(cst, nodeIdx);
            case RULE_UsingClauseDelete_KIND -> visitUsingClauseDelete(cst, nodeIdx);
            case RULE_PassthroughStatement_KIND -> visitPassthroughStatement(cst, nodeIdx);
            case RULE_TransactionStmt_KIND -> visitTransactionStmt(cst, nodeIdx);
            case RULE_SessionStmt_KIND -> visitSessionStmt(cst, nodeIdx);
            case RULE_UtilityStmt_KIND -> visitUtilityStmt(cst, nodeIdx);
            case RULE_TruncateStmt_KIND -> visitTruncateStmt(cst, nodeIdx);
            case RULE_ExplainStmt_KIND -> visitExplainStmt(cst, nodeIdx);
            case RULE_CopyStmt_KIND -> visitCopyStmt(cst, nodeIdx);
            case RULE_RefreshMatViewStmt_KIND -> visitRefreshMatViewStmt(cst, nodeIdx);
            case RULE_RestOfStatement_KIND -> visitRestOfStatement(cst, nodeIdx);
            case RULE_Expr_KIND -> visitExpr(cst, nodeIdx);
            case RULE_OrExpr_KIND -> visitOrExpr(cst, nodeIdx);
            case RULE_AndExpr_KIND -> visitAndExpr(cst, nodeIdx);
            case RULE_NotExpr_KIND -> visitNotExpr(cst, nodeIdx);
            case RULE_CompareExpr_KIND -> visitCompareExpr(cst, nodeIdx);
            case RULE_IsExpr_KIND -> visitIsExpr(cst, nodeIdx);
            case RULE_IsClause_KIND -> visitIsClause(cst, nodeIdx);
            case RULE_InExpr_KIND -> visitInExpr(cst, nodeIdx);
            case RULE_BetweenExpr_KIND -> visitBetweenExpr(cst, nodeIdx);
            case RULE_LikeExpr_KIND -> visitLikeExpr(cst, nodeIdx);
            case RULE_SimilarToExpr_KIND -> visitSimilarToExpr(cst, nodeIdx);
            case RULE_IsDistinctFrom_KIND -> visitIsDistinctFrom(cst, nodeIdx);
            case RULE_AddExpr_KIND -> visitAddExpr(cst, nodeIdx);
            case RULE_MulExpr_KIND -> visitMulExpr(cst, nodeIdx);
            case RULE_UnaryExpr_KIND -> visitUnaryExpr(cst, nodeIdx);
            case RULE_ExponentExpr_KIND -> visitExponentExpr(cst, nodeIdx);
            case RULE_ConcatExpr_KIND -> visitConcatExpr(cst, nodeIdx);
            case RULE_ArrayExpr_KIND -> visitArrayExpr(cst, nodeIdx);
            case RULE_TypeCastExpr_KIND -> visitTypeCastExpr(cst, nodeIdx);
            case RULE_PostfixExpr_KIND -> visitPostfixExpr(cst, nodeIdx);
            case RULE_PostfixOp_KIND -> visitPostfixOp(cst, nodeIdx);
            case RULE_PrimaryExpr_KIND -> visitPrimaryExpr(cst, nodeIdx);
            case RULE_ColRef_KIND -> visitColRef(cst, nodeIdx);
            case RULE_ExistsExpr_KIND -> visitExistsExpr(cst, nodeIdx);
            case RULE_SubqueryExpr_KIND -> visitSubqueryExpr(cst, nodeIdx);
            case RULE_AnyAllExpr_KIND -> visitAnyAllExpr(cst, nodeIdx);
            case RULE_RowExpr_KIND -> visitRowExpr(cst, nodeIdx);
            case RULE_ArrayExprConstructor_KIND -> visitArrayExprConstructor(cst, nodeIdx);
            case RULE_CastExpr_KIND -> visitCastExpr(cst, nodeIdx);
            case RULE_CaseExpr_KIND -> visitCaseExpr(cst, nodeIdx);
            case RULE_WhenClause_KIND -> visitWhenClause(cst, nodeIdx);
            case RULE_ElseClause_KIND -> visitElseClause(cst, nodeIdx);
            case RULE_CoalesceExpr_KIND -> visitCoalesceExpr(cst, nodeIdx);
            case RULE_NullIfExpr_KIND -> visitNullIfExpr(cst, nodeIdx);
            case RULE_GreatestLeastExpr_KIND -> visitGreatestLeastExpr(cst, nodeIdx);
            case RULE_ExtractExpr_KIND -> visitExtractExpr(cst, nodeIdx);
            case RULE_PositionExpr_KIND -> visitPositionExpr(cst, nodeIdx);
            case RULE_SubstringExpr_KIND -> visitSubstringExpr(cst, nodeIdx);
            case RULE_TrimExpr_KIND -> visitTrimExpr(cst, nodeIdx);
            case RULE_OverlayExpr_KIND -> visitOverlayExpr(cst, nodeIdx);
            case RULE_TypedLiteral_KIND -> visitTypedLiteral(cst, nodeIdx);
            case RULE_FuncCall_KIND -> visitFuncCall(cst, nodeIdx);
            case RULE_FuncCallArgs_KIND -> visitFuncCallArgs(cst, nodeIdx);
            case RULE_FuncName_KIND -> visitFuncName(cst, nodeIdx);
            case RULE_FilterClause_KIND -> visitFilterClause(cst, nodeIdx);
            case RULE_OverClause_KIND -> visitOverClause(cst, nodeIdx);
            case RULE_WithinGroupClause_KIND -> visitWithinGroupClause(cst, nodeIdx);
            case RULE_ExprList_KIND -> visitExprList(cst, nodeIdx);
            case RULE_Operator_KIND -> visitOperator(cst, nodeIdx);
            case RULE_DataType_KIND -> visitDataType(cst, nodeIdx);
            case RULE_ArrayType_KIND -> visitArrayType(cst, nodeIdx);
            case RULE_ScalarType_KIND -> visitScalarType(cst, nodeIdx);
            case RULE_NumericType_KIND -> visitNumericType(cst, nodeIdx);
            case RULE_CharType_KIND -> visitCharType(cst, nodeIdx);
            case RULE_DateTimeType_KIND -> visitDateTimeType(cst, nodeIdx);
            case RULE_TimestampType_KIND -> visitTimestampType(cst, nodeIdx);
            case RULE_TimeType_KIND -> visitTimeType(cst, nodeIdx);
            case RULE_IntervalType_KIND -> visitIntervalType(cst, nodeIdx);
            case RULE_BitType_KIND -> visitBitType(cst, nodeIdx);
            case RULE_TypeModifiers_KIND -> visitTypeModifiers(cst, nodeIdx);
            case RULE_QualifiedTypeName_KIND -> visitQualifiedTypeName(cst, nodeIdx);
            case RULE_QualifiedName_KIND -> visitQualifiedName(cst, nodeIdx);
            case RULE_Literal_KIND -> visitLiteral(cst, nodeIdx);
            case RULE_SignedNumericLiteral_KIND -> visitSignedNumericLiteral(cst, nodeIdx);
            case RULE_StringLiteral_KIND -> visitStringLiteral(cst, nodeIdx);
            case RULE_DollarString_KIND -> visitDollarString(cst, nodeIdx);
            case RULE_ClauseKeyword_KIND -> visitClauseKeyword(cst, nodeIdx);
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

    public T visitInput(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitStatement(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitDdlStatement(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitCreateStatement(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitAlterStatement(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitDropStatement(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitDmlStatement(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitCreateTableStmt(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitTableElementList(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitTableElement(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitColumnDef(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitColConstraint(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitColConstraintElem(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitCheckColConstraint(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitDefaultClause(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitCollateClause(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitReferencesClause(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitGeneratedClause(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitIdentityClause(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitIdentitySpec(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitTableConstraint(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitTableConstraintElem(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitPrimaryKeyTblConstraint(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitUniqueTblConstraint(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitCheckTblConstraint(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitForeignKeyTblConstraint(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitExcludeTblConstraint(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitExcludeElementList(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitExcludeElement(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitIndexOptions(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitIncludeClause(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitWithStorageParams(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitStorageParamList(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitStorageParam(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitTableOptions(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitPartitionByClause(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitPartitionKeyList(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitPartitionKey(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitInheritsClause(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitColumnList(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitQualifiedNameList(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitAlterTableStmt(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitAlterTableActions(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitAlterTableAction(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitAddColumnAction(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitAlterColumnAction(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitAlterColumnCmd(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitSetDataTypeCmd(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitSetDefaultCmd(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitSetStatisticsCmd(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitAddIdentityCmd(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitAddConstraintAction(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitAttachPartition(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitDetachPartition(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitForValuesClause(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitDropTableStmt(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitCreateIndexStmt(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitIndexElemList(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitIndexElem(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitOpClass(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitAlterIndexStmt(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitDropIndexStmt(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitCreateSequenceStmt(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitAlterSequenceStmt(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitDropSequenceStmt(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitSequenceOptions(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitSequenceOption(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitCreateTypeStmt(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitEnumLabelList(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitCompositeFieldList(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitCompositeField(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitRangeOptionList(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitRangeOption(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitDomainConstraint(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitAlterTypeStmt(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitDropTypeStmt(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitDropSchemaStmt(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitCreateViewStmt(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitCreateMatViewStmt(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitAlterViewStmt(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitDropViewStmt(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitDropMatViewStmt(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitCreateExtensionStmt(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitExtensionOptions(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitExtensionOption(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitDropExtensionStmt(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitCommentStatement(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitCommentTarget(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitFuncArgTypes(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitFuncArgType(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitGrantStatement(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitRevokeStatement(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitPrivilegeList(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitPrivilege(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitGrantTarget(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitGranteeList(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitAlterDefaultPrivilegesPassthrough(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitCreateFunctionPassthrough(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitCreateTriggerPassthrough(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitDropFunctionPassthrough(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitDropTriggerPassthrough(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitSelectStmt(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitSelectCore(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitSetQuantifier(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitTargetList(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitTargetElem(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitStarExpr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitIntoClause(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitFromClause(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitFromList(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitTableRef(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitTableRefBase(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitBaseTableRef(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitSubqueryRef(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitLateralRef(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitFuncTableRef(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitAlias(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitTablesampleClause(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitJoinExpr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitJoinClause(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitJoinQual(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitWhereClause(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitGroupByClause(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitGroupByList(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitGroupByElem(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitHavingClause(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitWindowClause(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitWindowDefList(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitWindowDef(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitWindowSpec(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitPartitionClause(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitFrameClause(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitFrameExtent(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitFrameBound(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitWithClause(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitCteList(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitCteDef(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitSetOp(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitOrderByClause(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitOrderByList(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitOrderByItem(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitLimitClause(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitOffsetClause(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitFetchClause(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitInsertStmt(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitInsertSource(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitValuesClause(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitValueRowList(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitExprOrDefaultList(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitExprOrDefault(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitOnConflictClause(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitConflictTarget(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitConflictAction(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitReturningClause(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitUpdateStmt(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitUpdateSetList(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitUpdateSetItem(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitDeleteStmt(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitUsingClauseDelete(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitPassthroughStatement(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitTransactionStmt(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitSessionStmt(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitUtilityStmt(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitTruncateStmt(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitExplainStmt(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitCopyStmt(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitRefreshMatViewStmt(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitRestOfStatement(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitExpr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitOrExpr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitAndExpr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitNotExpr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitCompareExpr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitIsExpr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitIsClause(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitInExpr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitBetweenExpr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitLikeExpr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitSimilarToExpr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitIsDistinctFrom(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitAddExpr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitMulExpr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitUnaryExpr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitExponentExpr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitConcatExpr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitArrayExpr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitTypeCastExpr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitPostfixExpr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitPostfixOp(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitPrimaryExpr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitColRef(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitExistsExpr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitSubqueryExpr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitAnyAllExpr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitRowExpr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitArrayExprConstructor(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitCastExpr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitCaseExpr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitWhenClause(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitElseClause(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitCoalesceExpr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitNullIfExpr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitGreatestLeastExpr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitExtractExpr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitPositionExpr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitSubstringExpr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitTrimExpr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitOverlayExpr(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitTypedLiteral(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitFuncCall(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitFuncCallArgs(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitFuncName(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitFilterClause(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitOverClause(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitWithinGroupClause(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitExprList(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitOperator(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitDataType(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitArrayType(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitScalarType(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitNumericType(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitCharType(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitDateTimeType(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitTimestampType(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitTimeType(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitIntervalType(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitBitType(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitTypeModifiers(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitQualifiedTypeName(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitQualifiedName(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitLiteral(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitSignedNumericLiteral(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitStringLiteral(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitDollarString(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

    public T visitClauseKeyword(CstArray cst, int nodeIdx) {
        return visitChildren(cst, nodeIdx);
    }

}

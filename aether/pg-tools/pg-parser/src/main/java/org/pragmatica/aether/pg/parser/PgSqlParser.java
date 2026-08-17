// peglib-generator: 0.7.2
package org.pragmatica.aether.pg.parser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.pragmatica.peg.token.TokenArray;
import org.pragmatica.peg.cst.CstArray;
import org.pragmatica.peg.cst.CstArrayBuilder;
import org.pragmatica.peg.cst.ParseResult;
import org.pragmatica.peg.diagnostic.Diagnostic;

public final class PgSqlParser {

    private static final String[] RULE_TABLE = {"Input", "Statement", "DdlStatement", "CreateStatement", "AlterStatement", "DropStatement", "DmlStatement", "CreateTableStmt", "TableElementList", "TableElement", "ColumnDef", "ColConstraint", "ColConstraintElem", "CheckColConstraint", "DefaultClause", "CollateClause", "ReferencesClause", "GeneratedClause", "IdentityClause", "IdentitySpec", "TableConstraint", "TableConstraintElem", "PrimaryKeyTblConstraint", "UniqueTblConstraint", "CheckTblConstraint", "ForeignKeyTblConstraint", "ExcludeTblConstraint", "ExcludeElementList", "ExcludeElement", "IndexOptions", "IncludeClause", "WithStorageParams", "StorageParamList", "StorageParam", "TableOptions", "PartitionByClause", "PartitionKeyList", "PartitionKey", "InheritsClause", "ColumnList", "QualifiedNameList", "AlterTableStmt", "AlterTableActions", "AlterTableAction", "AddColumnAction", "AlterColumnAction", "AlterColumnCmd", "SetDataTypeCmd", "SetDefaultCmd", "SetStatisticsCmd", "AddIdentityCmd", "AddConstraintAction", "AttachPartition", "DetachPartition", "ForValuesClause", "DropTableStmt", "CreateIndexStmt", "IndexElemList", "IndexElem", "OpClass", "AlterIndexStmt", "DropIndexStmt", "CreateSequenceStmt", "AlterSequenceStmt", "DropSequenceStmt", "SequenceOptions", "SequenceOption", "CreateTypeStmt", "EnumLabelList", "CompositeFieldList", "CompositeField", "RangeOptionList", "RangeOption", "DomainConstraint", "AlterTypeStmt", "DropTypeStmt", "DropSchemaStmt", "CreateViewStmt", "CreateMatViewStmt", "AlterViewStmt", "DropViewStmt", "DropMatViewStmt", "CreateExtensionStmt", "ExtensionOptions", "ExtensionOption", "DropExtensionStmt", "CommentStatement", "CommentTarget", "FuncArgTypes", "FuncArgType", "GrantStatement", "RevokeStatement", "PrivilegeList", "Privilege", "GrantTarget", "GranteeList", "AlterDefaultPrivilegesPassthrough", "CreateFunctionPassthrough", "CreateTriggerPassthrough", "DropFunctionPassthrough", "DropTriggerPassthrough", "SelectStmt", "SelectCore", "SetQuantifier", "TargetList", "TargetElem", "StarExpr", "IntoClause", "FromClause", "FromList", "TableRef", "TableRefBase", "BaseTableRef", "SubqueryRef", "LateralRef", "FuncTableRef", "Alias", "TablesampleClause", "JoinExpr", "JoinClause", "JoinQual", "WhereClause", "GroupByClause", "GroupByList", "GroupByElem", "HavingClause", "WindowClause", "WindowDefList", "WindowDef", "WindowSpec", "PartitionClause", "FrameClause", "FrameExtent", "FrameBound", "WithClause", "CteList", "CteDef", "SetOp", "OrderByClause", "OrderByList", "OrderByItem", "LimitClause", "OffsetClause", "FetchClause", "InsertStmt", "InsertSource", "ValuesClause", "ValueRowList", "ExprOrDefaultList", "ExprOrDefault", "OnConflictClause", "ConflictTarget", "ConflictAction", "ReturningClause", "UpdateStmt", "UpdateSetList", "UpdateSetItem", "DeleteStmt", "UsingClauseDelete", "PassthroughStatement", "TransactionStmt", "SessionStmt", "UtilityStmt", "TruncateStmt", "ExplainStmt", "CopyStmt", "RefreshMatViewStmt", "RestOfStatement", "Expr", "OrExpr", "AndExpr", "NotExpr", "CompareExpr", "IsExpr", "IsClause", "InExpr", "BetweenExpr", "LikeExpr", "SimilarToExpr", "IsDistinctFrom", "AddExpr", "MulExpr", "UnaryExpr", "ExponentExpr", "ConcatExpr", "ArrayExpr", "TypeCastExpr", "PostfixExpr", "PostfixOp", "PrimaryExpr", "ColRef", "ExistsExpr", "SubqueryExpr", "AnyAllExpr", "RowExpr", "ArrayExprConstructor", "CastExpr", "CaseExpr", "WhenClause", "ElseClause", "CoalesceExpr", "NullIfExpr", "GreatestLeastExpr", "ExtractExpr", "PositionExpr", "SubstringExpr", "TrimExpr", "OverlayExpr", "TypedLiteral", "FuncCall", "FuncCallArgs", "FuncName", "FilterClause", "OverClause", "WithinGroupClause", "ExprList", "Operator", "DataType", "ArrayType", "ScalarType", "NumericType", "CharType", "DateTimeType", "TimestampType", "TimeType", "IntervalType", "BitType", "TypeModifiers", "QualifiedTypeName", "QualifiedName", "Literal", "SignedNumericLiteral", "StringLiteral", "DollarString", "ClauseKeyword", "ERROR", "_ROOT"};

    private static final int RULE_Input_KIND = 0;
    private static final int RULE_Statement_KIND = 1;
    private static final int RULE_DdlStatement_KIND = 2;
    private static final int RULE_CreateStatement_KIND = 3;
    private static final int RULE_AlterStatement_KIND = 4;
    private static final int RULE_DropStatement_KIND = 5;
    private static final int RULE_DmlStatement_KIND = 6;
    private static final int RULE_CreateTableStmt_KIND = 7;
    private static final int RULE_TableElementList_KIND = 8;
    private static final int RULE_TableElement_KIND = 9;
    private static final int RULE_ColumnDef_KIND = 10;
    private static final int RULE_ColConstraint_KIND = 11;
    private static final int RULE_ColConstraintElem_KIND = 12;
    private static final int RULE_CheckColConstraint_KIND = 13;
    private static final int RULE_DefaultClause_KIND = 14;
    private static final int RULE_CollateClause_KIND = 15;
    private static final int RULE_ReferencesClause_KIND = 16;
    private static final int RULE_GeneratedClause_KIND = 17;
    private static final int RULE_IdentityClause_KIND = 18;
    private static final int RULE_IdentitySpec_KIND = 19;
    private static final int RULE_TableConstraint_KIND = 20;
    private static final int RULE_TableConstraintElem_KIND = 21;
    private static final int RULE_PrimaryKeyTblConstraint_KIND = 22;
    private static final int RULE_UniqueTblConstraint_KIND = 23;
    private static final int RULE_CheckTblConstraint_KIND = 24;
    private static final int RULE_ForeignKeyTblConstraint_KIND = 25;
    private static final int RULE_ExcludeTblConstraint_KIND = 26;
    private static final int RULE_ExcludeElementList_KIND = 27;
    private static final int RULE_ExcludeElement_KIND = 28;
    private static final int RULE_IndexOptions_KIND = 29;
    private static final int RULE_IncludeClause_KIND = 30;
    private static final int RULE_WithStorageParams_KIND = 31;
    private static final int RULE_StorageParamList_KIND = 32;
    private static final int RULE_StorageParam_KIND = 33;
    private static final int RULE_TableOptions_KIND = 34;
    private static final int RULE_PartitionByClause_KIND = 35;
    private static final int RULE_PartitionKeyList_KIND = 36;
    private static final int RULE_PartitionKey_KIND = 37;
    private static final int RULE_InheritsClause_KIND = 38;
    private static final int RULE_ColumnList_KIND = 39;
    private static final int RULE_QualifiedNameList_KIND = 40;
    private static final int RULE_AlterTableStmt_KIND = 41;
    private static final int RULE_AlterTableActions_KIND = 42;
    private static final int RULE_AlterTableAction_KIND = 43;
    private static final int RULE_AddColumnAction_KIND = 44;
    private static final int RULE_AlterColumnAction_KIND = 45;
    private static final int RULE_AlterColumnCmd_KIND = 46;
    private static final int RULE_SetDataTypeCmd_KIND = 47;
    private static final int RULE_SetDefaultCmd_KIND = 48;
    private static final int RULE_SetStatisticsCmd_KIND = 49;
    private static final int RULE_AddIdentityCmd_KIND = 50;
    private static final int RULE_AddConstraintAction_KIND = 51;
    private static final int RULE_AttachPartition_KIND = 52;
    private static final int RULE_DetachPartition_KIND = 53;
    private static final int RULE_ForValuesClause_KIND = 54;
    private static final int RULE_DropTableStmt_KIND = 55;
    private static final int RULE_CreateIndexStmt_KIND = 56;
    private static final int RULE_IndexElemList_KIND = 57;
    private static final int RULE_IndexElem_KIND = 58;
    private static final int RULE_OpClass_KIND = 59;
    private static final int RULE_AlterIndexStmt_KIND = 60;
    private static final int RULE_DropIndexStmt_KIND = 61;
    private static final int RULE_CreateSequenceStmt_KIND = 62;
    private static final int RULE_AlterSequenceStmt_KIND = 63;
    private static final int RULE_DropSequenceStmt_KIND = 64;
    private static final int RULE_SequenceOptions_KIND = 65;
    private static final int RULE_SequenceOption_KIND = 66;
    private static final int RULE_CreateTypeStmt_KIND = 67;
    private static final int RULE_EnumLabelList_KIND = 68;
    private static final int RULE_CompositeFieldList_KIND = 69;
    private static final int RULE_CompositeField_KIND = 70;
    private static final int RULE_RangeOptionList_KIND = 71;
    private static final int RULE_RangeOption_KIND = 72;
    private static final int RULE_DomainConstraint_KIND = 73;
    private static final int RULE_AlterTypeStmt_KIND = 74;
    private static final int RULE_DropTypeStmt_KIND = 75;
    private static final int RULE_DropSchemaStmt_KIND = 76;
    private static final int RULE_CreateViewStmt_KIND = 77;
    private static final int RULE_CreateMatViewStmt_KIND = 78;
    private static final int RULE_AlterViewStmt_KIND = 79;
    private static final int RULE_DropViewStmt_KIND = 80;
    private static final int RULE_DropMatViewStmt_KIND = 81;
    private static final int RULE_CreateExtensionStmt_KIND = 82;
    private static final int RULE_ExtensionOptions_KIND = 83;
    private static final int RULE_ExtensionOption_KIND = 84;
    private static final int RULE_DropExtensionStmt_KIND = 85;
    private static final int RULE_CommentStatement_KIND = 86;
    private static final int RULE_CommentTarget_KIND = 87;
    private static final int RULE_FuncArgTypes_KIND = 88;
    private static final int RULE_FuncArgType_KIND = 89;
    private static final int RULE_GrantStatement_KIND = 90;
    private static final int RULE_RevokeStatement_KIND = 91;
    private static final int RULE_PrivilegeList_KIND = 92;
    private static final int RULE_Privilege_KIND = 93;
    private static final int RULE_GrantTarget_KIND = 94;
    private static final int RULE_GranteeList_KIND = 95;
    private static final int RULE_AlterDefaultPrivilegesPassthrough_KIND = 96;
    private static final int RULE_CreateFunctionPassthrough_KIND = 97;
    private static final int RULE_CreateTriggerPassthrough_KIND = 98;
    private static final int RULE_DropFunctionPassthrough_KIND = 99;
    private static final int RULE_DropTriggerPassthrough_KIND = 100;
    private static final int RULE_SelectStmt_KIND = 101;
    private static final int RULE_SelectCore_KIND = 102;
    private static final int RULE_SetQuantifier_KIND = 103;
    private static final int RULE_TargetList_KIND = 104;
    private static final int RULE_TargetElem_KIND = 105;
    private static final int RULE_StarExpr_KIND = 106;
    private static final int RULE_IntoClause_KIND = 107;
    private static final int RULE_FromClause_KIND = 108;
    private static final int RULE_FromList_KIND = 109;
    private static final int RULE_TableRef_KIND = 110;
    private static final int RULE_TableRefBase_KIND = 111;
    private static final int RULE_BaseTableRef_KIND = 112;
    private static final int RULE_SubqueryRef_KIND = 113;
    private static final int RULE_LateralRef_KIND = 114;
    private static final int RULE_FuncTableRef_KIND = 115;
    private static final int RULE_Alias_KIND = 116;
    private static final int RULE_TablesampleClause_KIND = 117;
    private static final int RULE_JoinExpr_KIND = 118;
    private static final int RULE_JoinClause_KIND = 119;
    private static final int RULE_JoinQual_KIND = 120;
    private static final int RULE_WhereClause_KIND = 121;
    private static final int RULE_GroupByClause_KIND = 122;
    private static final int RULE_GroupByList_KIND = 123;
    private static final int RULE_GroupByElem_KIND = 124;
    private static final int RULE_HavingClause_KIND = 125;
    private static final int RULE_WindowClause_KIND = 126;
    private static final int RULE_WindowDefList_KIND = 127;
    private static final int RULE_WindowDef_KIND = 128;
    private static final int RULE_WindowSpec_KIND = 129;
    private static final int RULE_PartitionClause_KIND = 130;
    private static final int RULE_FrameClause_KIND = 131;
    private static final int RULE_FrameExtent_KIND = 132;
    private static final int RULE_FrameBound_KIND = 133;
    private static final int RULE_WithClause_KIND = 134;
    private static final int RULE_CteList_KIND = 135;
    private static final int RULE_CteDef_KIND = 136;
    private static final int RULE_SetOp_KIND = 137;
    private static final int RULE_OrderByClause_KIND = 138;
    private static final int RULE_OrderByList_KIND = 139;
    private static final int RULE_OrderByItem_KIND = 140;
    private static final int RULE_LimitClause_KIND = 141;
    private static final int RULE_OffsetClause_KIND = 142;
    private static final int RULE_FetchClause_KIND = 143;
    private static final int RULE_InsertStmt_KIND = 144;
    private static final int RULE_InsertSource_KIND = 145;
    private static final int RULE_ValuesClause_KIND = 146;
    private static final int RULE_ValueRowList_KIND = 147;
    private static final int RULE_ExprOrDefaultList_KIND = 148;
    private static final int RULE_ExprOrDefault_KIND = 149;
    private static final int RULE_OnConflictClause_KIND = 150;
    private static final int RULE_ConflictTarget_KIND = 151;
    private static final int RULE_ConflictAction_KIND = 152;
    private static final int RULE_ReturningClause_KIND = 153;
    private static final int RULE_UpdateStmt_KIND = 154;
    private static final int RULE_UpdateSetList_KIND = 155;
    private static final int RULE_UpdateSetItem_KIND = 156;
    private static final int RULE_DeleteStmt_KIND = 157;
    private static final int RULE_UsingClauseDelete_KIND = 158;
    private static final int RULE_PassthroughStatement_KIND = 159;
    private static final int RULE_TransactionStmt_KIND = 160;
    private static final int RULE_SessionStmt_KIND = 161;
    private static final int RULE_UtilityStmt_KIND = 162;
    private static final int RULE_TruncateStmt_KIND = 163;
    private static final int RULE_ExplainStmt_KIND = 164;
    private static final int RULE_CopyStmt_KIND = 165;
    private static final int RULE_RefreshMatViewStmt_KIND = 166;
    private static final int RULE_RestOfStatement_KIND = 167;
    private static final int RULE_Expr_KIND = 168;
    private static final int RULE_OrExpr_KIND = 169;
    private static final int RULE_AndExpr_KIND = 170;
    private static final int RULE_NotExpr_KIND = 171;
    private static final int RULE_CompareExpr_KIND = 172;
    private static final int RULE_IsExpr_KIND = 173;
    private static final int RULE_IsClause_KIND = 174;
    private static final int RULE_InExpr_KIND = 175;
    private static final int RULE_BetweenExpr_KIND = 176;
    private static final int RULE_LikeExpr_KIND = 177;
    private static final int RULE_SimilarToExpr_KIND = 178;
    private static final int RULE_IsDistinctFrom_KIND = 179;
    private static final int RULE_AddExpr_KIND = 180;
    private static final int RULE_MulExpr_KIND = 181;
    private static final int RULE_UnaryExpr_KIND = 182;
    private static final int RULE_ExponentExpr_KIND = 183;
    private static final int RULE_ConcatExpr_KIND = 184;
    private static final int RULE_ArrayExpr_KIND = 185;
    private static final int RULE_TypeCastExpr_KIND = 186;
    private static final int RULE_PostfixExpr_KIND = 187;
    private static final int RULE_PostfixOp_KIND = 188;
    private static final int RULE_PrimaryExpr_KIND = 189;
    private static final int RULE_ColRef_KIND = 190;
    private static final int RULE_ExistsExpr_KIND = 191;
    private static final int RULE_SubqueryExpr_KIND = 192;
    private static final int RULE_AnyAllExpr_KIND = 193;
    private static final int RULE_RowExpr_KIND = 194;
    private static final int RULE_ArrayExprConstructor_KIND = 195;
    private static final int RULE_CastExpr_KIND = 196;
    private static final int RULE_CaseExpr_KIND = 197;
    private static final int RULE_WhenClause_KIND = 198;
    private static final int RULE_ElseClause_KIND = 199;
    private static final int RULE_CoalesceExpr_KIND = 200;
    private static final int RULE_NullIfExpr_KIND = 201;
    private static final int RULE_GreatestLeastExpr_KIND = 202;
    private static final int RULE_ExtractExpr_KIND = 203;
    private static final int RULE_PositionExpr_KIND = 204;
    private static final int RULE_SubstringExpr_KIND = 205;
    private static final int RULE_TrimExpr_KIND = 206;
    private static final int RULE_OverlayExpr_KIND = 207;
    private static final int RULE_TypedLiteral_KIND = 208;
    private static final int RULE_FuncCall_KIND = 209;
    private static final int RULE_FuncCallArgs_KIND = 210;
    private static final int RULE_FuncName_KIND = 211;
    private static final int RULE_FilterClause_KIND = 212;
    private static final int RULE_OverClause_KIND = 213;
    private static final int RULE_WithinGroupClause_KIND = 214;
    private static final int RULE_ExprList_KIND = 215;
    private static final int RULE_Operator_KIND = 216;
    private static final int RULE_DataType_KIND = 217;
    private static final int RULE_ArrayType_KIND = 218;
    private static final int RULE_ScalarType_KIND = 219;
    private static final int RULE_NumericType_KIND = 220;
    private static final int RULE_CharType_KIND = 221;
    private static final int RULE_DateTimeType_KIND = 222;
    private static final int RULE_TimestampType_KIND = 223;
    private static final int RULE_TimeType_KIND = 224;
    private static final int RULE_IntervalType_KIND = 225;
    private static final int RULE_BitType_KIND = 226;
    private static final int RULE_TypeModifiers_KIND = 227;
    private static final int RULE_QualifiedTypeName_KIND = 228;
    private static final int RULE_QualifiedName_KIND = 229;
    private static final int RULE_Literal_KIND = 230;
    private static final int RULE_SignedNumericLiteral_KIND = 231;
    private static final int RULE_StringLiteral_KIND = 232;
    private static final int RULE_DollarString_KIND = 233;
    private static final int RULE_ClauseKeyword_KIND = 234;
    private static final int RULE_ERROR_KIND = 235;
    private static final int RULE_ROOT_KIND = 236;

    private static final int KIND_INLINE__SEMI = 394;
    private static final int KIND_EMPTYSTATEMENT = 5;
    private static final int KIND_INLINE_CREATE_CI = 456;
    private static final int KIND_CREATESCHEMASTMT = 43;
    private static final int KIND_INLINE_ALTER_CI = 503;
    private static final int KIND_ALTERSCHEMASTMT = 44;
    private static final int KIND_INLINE_DROP_CI = 504;
    private static final int KIND_INLINE_TEMP_CI = 599;
    private static final int KIND_INLINE_UNLOGGED_CI = 612;
    private static final int KIND_INLINE_TABLE_CI = 492;
    private static final int KIND_IFNOTEXISTS = 6;
    private static final int KIND_INLINE__LPAREN = 395;
    private static final int KIND_INLINE__RPAREN = 396;
    private static final int KIND_INLINE__COMMA = 397;
    private static final int KIND_COLID = 67;
    private static final int KIND_CONSTRAINTNAME = 12;
    private static final int KIND_NOTNULLCONSTRAINT = 8;
    private static final int KIND_NULLCONSTRAINT = 9;
    private static final int KIND_UNIQUECOLCONSTRAINT = 10;
    private static final int KIND_PRIMARYKEYCOLCONSTRAINT = 11;
    private static final int KIND_INLINE_CHECK_CI = 452;
    private static final int KIND_INLINE_DEFAULT_CI = 464;
    private static final int KIND_INLINE_COLLATE_CI = 453;
    private static final int KIND_INLINE_REFERENCES_CI = 487;
    private static final int KIND_FKACTIONS = 14;
    private static final int KIND_INLINE_GENERATED_CI = 577;
    private static final int KIND_INLINE_ALWAYS_CI = 578;
    private static final int KIND_INLINE_AS_CI = 446;
    private static final int KIND_INLINE_STORED_CI = 580;
    private static final int KIND_INLINE_BY_CI = 551;
    private static final int KIND_INLINE_IDENTITY_CI = 579;
    private static final int KIND_INLINE_PRIMARY_CI = 486;
    private static final int KIND_INLINE_KEY_CI = 517;
    private static final int KIND_INLINE_UNIQUE_CI = 497;
    private static final int KIND_NULLSDISTINCT = 18;
    private static final int KIND_NOINHERITCLAUSE = 13;
    private static final int KIND_INLINE_FOREIGN_CI = 472;
    private static final int KIND_FKDEFERRABLE = 17;
    private static final int KIND_INLINE_EXCLUDE_CI = 561;
    private static final int KIND_USINGCLAUSE = 38;
    private static final int KIND_INLINE_WITH_CI = 502;
    private static final int KIND_USINGINDEXTBLSPACE = 19;
    private static final int KIND_INLINE_INCLUDE_CI = 560;
    private static final int KIND_INLINE__EQ = 398;
    private static final int KIND_TABLESPACECLAUSE = 21;
    private static final int KIND_INLINE_PARTITION_CI = 326;
    private static final int KIND_PARTITIONSTRATEGY = 20;
    private static final int KIND_INLINE_INHERITS_CI = 558;
    private static final int KIND_IFEXISTS = 7;
    private static final int KIND_INLINE_ONLY_CI = 411;
    private static final int KIND_RENAMEACTION = 34;
    private static final int KIND_SETSCHEMAACTION = 35;
    private static final int KIND_DROPCOLUMNACTION = 22;
    private static final int KIND_DROPCONSTRAINTACTION = 29;
    private static final int KIND_VALIDATECONSTRAINTACTION = 30;
    private static final int KIND_RENAMECONSTRAINTACTION = 31;
    private static final int KIND_ALTEROWNERACTION = 33;
    private static final int KIND_SETTABLESPACEACTION = 36;
    private static final int KIND_INLINE_ADD_CI = 518;
    private static final int KIND_INLINE_COLUMN_CI = 454;
    private static final int KIND_DROPDEFAULTCMD = 24;
    private static final int KIND_SETNOTNULLCMD = 25;
    private static final int KIND_DROPNOTNULLCMD = 26;
    private static final int KIND_SETSTORAGECMD = 27;
    private static final int KIND_DROPIDENTITYCMD = 28;
    private static final int KIND_INLINE_SET_CI = 379;
    private static final int KIND_INLINE_DATA_CI = 609;
    private static final int KIND_INLINE_TYPE_CI = 512;
    private static final int KIND_INLINE_USING_CI = 499;
    private static final int KIND_INLINE_STATISTICS_CI = 610;
    private static final int KIND_NOTVALIDCLAUSE = 32;
    private static final int KIND_INLINE_ATTACH_CI = 555;
    private static final int KIND_INLINE_DETACH_CI = 556;
    private static final int KIND_INLINE_CONCURRENTLY_CI = 418;
    private static final int KIND_INLINE_FINALIZE_CI = 557;
    private static final int KIND_INLINE_FOR_CI = 471;
    private static final int KIND_INLINE_VALUES_CI = 346;
    private static final int KIND_INLINE_IN_CI = 475;
    private static final int KIND_INLINE_FROM_CI = 473;
    private static final int KIND_INLINE_TO_CI = 494;
    private static final int KIND_DROPBEHAVIOR = 23;
    private static final int KIND_INLINE_INDEX_CI = 508;
    private static final int KIND_INLINE_ON_CI = 392;
    private static final int KIND_ORDERSPEC = 40;
    private static final int KIND_NULLSORDER = 41;
    private static final int KIND_INLINE_RENAME_CI = 519;
    private static final int KIND_INLINE_TABLESPACE_CI = 559;
    private static final int KIND_INLINE_SEQUENCE_CI = 511;
    private static final int KIND_INLINE_INCREMENT_CI = 568;
    private static final int KIND_INLINE_MINVALUE_CI = 569;
    private static final int KIND_INLINE_MAXVALUE_CI = 570;
    private static final int KIND_INLINE_NO_CI = 524;
    private static final int KIND_INLINE_CYCLE_CI = 573;
    private static final int KIND_INLINE_START_CI = 571;
    private static final int KIND_INLINE_RESTART_CI = 575;
    private static final int KIND_INLINE_CACHE_CI = 572;
    private static final int KIND_INLINE_OWNED_CI = 574;
    private static final int KIND_INLINE_NONE_CI = 576;
    private static final int KIND_INLINE_ENUM_CI = 562;
    private static final int KIND_INLINE_RANGE_CI = 361;
    private static final int KIND_INLINE_VALUE_CI = 565;
    private static final int KIND_INLINE_BEFORE_CI = 566;
    private static final int KIND_INLINE_AFTER_CI = 567;
    private static final int KIND_INLINE_SCHEMA_CI = 510;
    private static final int KIND_INLINE_ATTRIBUTE_CI = 564;
    private static final int KIND_INLINE_OR_CI = 484;
    private static final int KIND_INLINE_REPLACE_CI = 603;
    private static final int KIND_INLINE_RECURSIVE_CI = 602;
    private static final int KIND_INLINE_VIEW_CI = 509;
    private static final int KIND_CHECKOPTIONCLAUSE = 45;
    private static final int KIND_INLINE_MATERIALIZED_CI = 601;
    private static final int KIND_INLINE_OWNER_CI = 607;
    private static final int KIND_INLINE_EXTENSION_CI = 516;
    private static final int KIND_INLINE_VERSION_CI = 608;
    private static final int KIND_INLINE_CASCADE_CI = 522;
    private static final int KIND_INLINE_COMMENT_CI = 586;
    private static final int KIND_INLINE_IS_CI = 527;
    private static final int KIND_INLINE_NULL_CI = 483;
    private static final int KIND_INLINE_CONSTRAINT_CI = 455;
    private static final int KIND_INLINE_FUNCTION_CI = 513;
    private static final int KIND_INLINE_OUT_CI = 645;
    private static final int KIND_INLINE_INOUT_CI = 644;
    private static final int KIND_INLINE_VARIADIC_CI = 500;
    private static final int KIND_INLINE_GRANT_CI = 474;
    private static final int KIND_INLINE_OPTION_CI = 590;
    private static final int KIND_INLINE_REVOKE_CI = 587;
    private static final int KIND_INLINE_ALL_CI = 440;
    private static final int KIND_INLINE_PRIVILEGES_CI = 588;
    private static final int KIND_INLINE_SELECT_CI = 488;
    private static final int KIND_INLINE_INSERT_CI = 505;
    private static final int KIND_INLINE_UPDATE_CI = 506;
    private static final int KIND_INLINE_DELETE_CI = 507;
    private static final int KIND_INLINE_TRUNCATE_CI = 600;
    private static final int KIND_INLINE_TRIGGER_CI = 515;
    private static final int KIND_INLINE_CONNECT_CI = 597;
    private static final int KIND_INLINE_TEMPORARY_CI = 598;
    private static final int KIND_INLINE_EXECUTE_CI = 595;
    private static final int KIND_INLINE_USAGE_CI = 596;
    private static final int KIND_INLINE_TABLES_CI = 591;
    private static final int KIND_INLINE_SEQUENCES_CI = 592;
    private static final int KIND_INLINE_FUNCTIONS_CI = 593;
    private static final int KIND_INLINE_SCHEMAS_CI = 594;
    private static final int KIND_GRANTEE = 46;
    private static final int KIND_INLINE_PROCEDURE_CI = 514;
    private static final int KIND_INLINE_AGGREGATE_CI = 646;
    private static final int KIND_INLINE_DISTINCT_CI = 467;
    private static final int KIND_COLLABEL = 68;
    private static final int KIND_INLINE__DOT = 399;
    private static final int KIND_INLINE__STAR = 400;
    private static final int KIND_INLINE_INTO_CI = 477;
    private static final int KIND_INLINE_LATERAL_CI = 478;
    private static final int KIND_WITHORDINALITY = 47;
    private static final int KIND_INLINE_TABLESAMPLE_CI = 614;
    private static final int KIND_INLINE_NATURAL_CI = 335;
    private static final int KIND_JOINTYPE = 48;
    private static final int KIND_INLINE_JOIN_CI = 370;
    private static final int KIND_INLINE_CROSS_CI = 359;
    private static final int KIND_INLINE_WHERE_CI = 360;
    private static final int KIND_INLINE_GROUP_CI = 353;
    private static final int KIND_INLINE_ROLLUP_CI = 615;
    private static final int KIND_INLINE_CUBE_CI = 616;
    private static final int KIND_GROUPINGSETSKW = 270;
    private static final int KIND_INLINE_HAVING_CI = 343;
    private static final int KIND_INLINE_WINDOW_CI = 347;
    private static final int KIND_WINDOWNAME = 49;
    private static final int KIND_FRAMETYPE = 50;
    private static final int KIND_FRAMEEXCLUSION = 51;
    private static final int KIND_INLINE_BETWEEN_CI = 531;
    private static final int KIND_INLINE_AND_CI = 443;
    private static final int KIND_INLINE_UNBOUNDED_CI = 620;
    private static final int KIND_INLINE_PRECEDING_CI = 617;
    private static final int KIND_INLINE_FOLLOWING_CI = 618;
    private static final int KIND_INLINE_CURRENT_CI = 619;
    private static final int KIND_INLINE_ROW_CI = 545;
    private static final int KIND_INLINE_NOT_CI = 482;
    private static final int KIND_INLINE_UNION_CI = 356;
    private static final int KIND_INLINE_INTERSECT_CI = 324;
    private static final int KIND_INLINE_EXCEPT_CI = 345;
    private static final int KIND_INLINE_ORDER_CI = 352;
    private static final int KIND_INLINE_LIMIT_CI = 354;
    private static final int KIND_INLINE_OFFSET_CI = 344;
    private static final int KIND_INLINE_ROWS_CI = 373;
    private static final int KIND_INLINE_FETCH_CI = 355;
    private static final int KIND_INLINE_FIRST_CI = 547;
    private static final int KIND_INLINE_NEXT_CI = 549;
    private static final int KIND_NUMERICLITERAL = 74;
    private static final int KIND_INLINE_TIES_CI = 550;
    private static final int KIND_INLINE_CONFLICT_CI = 329;
    private static final int KIND_INLINE_DO_CI = 393;
    private static final int KIND_INLINE_NOTHING_CI = 553;
    private static final int KIND_INLINE_RETURNING_CI = 325;
    private static final int KIND_INLINE_BEGIN_CI = 625;
    private static final int KIND_INLINE_COMMIT_CI = 626;
    private static final int KIND_INLINE_ROLLBACK_CI = 627;
    private static final int KIND_INLINE_END_CI = 469;
    private static final int KIND_INLINE_SAVEPOINT_CI = 628;
    private static final int KIND_INLINE_RELEASE_CI = 629;
    private static final int KIND_INLINE_PREPARE_CI = 630;
    private static final int KIND_INLINE_SHOW_CI = 631;
    private static final int KIND_INLINE_RESET_CI = 632;
    private static final int KIND_INLINE_VACUUM_CI = 633;
    private static final int KIND_INLINE_ANALYZE_CI = 442;
    private static final int KIND_INLINE_REINDEX_CI = 636;
    private static final int KIND_INLINE_CLUSTER_CI = 637;
    private static final int KIND_INLINE_NOTIFY_CI = 639;
    private static final int KIND_INLINE_LISTEN_CI = 640;
    private static final int KIND_INLINE_UNLISTEN_CI = 641;
    private static final int KIND_INLINE_LOAD_CI = 642;
    private static final int KIND_SECURITYLABELKW = 301;
    private static final int KIND_INLINE_DEALLOCATE_CI = 643;
    private static final int KIND_INLINE_EXPLAIN_CI = 634;
    private static final int KIND_INLINE_COPY_CI = 635;
    private static final int KIND_INLINE_REFRESH_CI = 638;
    private static final int KIND_BASICSTRING = 78;
    private static final int KIND_ESCAPESTRING = 79;
    private static final int KIND_COMPAREOP = 52;
    private static final int KIND_INLINE_TRUE_CI = 496;
    private static final int KIND_INLINE_FALSE_CI = 470;
    private static final int KIND_INLINE_UNKNOWN_CI = 541;
    private static final int KIND_INLINE_ISNULL_CI = 542;
    private static final int KIND_INLINE_NOTNULL_CI = 543;
    private static final int KIND_INLINE_SYMMETRIC_CI = 491;
    private static final int KIND_INLINE_ASYMMETRIC_CI = 448;
    private static final int KIND_INLINE_LIKE_CI = 528;
    private static final int KIND_INLINE_ILIKE_CI = 529;
    private static final int KIND_INLINE_ESCAPE_CI = 544;
    private static final int KIND_INLINE_SIMILAR_CI = 530;
    private static final int KIND_INLINE__PLUS = 401;
    private static final int KIND_INLINE__MINUS_GT = 380;
    private static final int KIND_INLINE__MINUS = 402;
    private static final int KIND_INLINE__SLASH = 403;
    private static final int KIND_INLINE__PERCENT = 404;
    private static final int KIND_INLINE__CARET = 405;
    private static final int KIND_INLINE__PIPE_PIPE = 381;
    private static final int KIND_INLINE__LBRACK = 406;
    private static final int KIND_INLINE__COLON = 407;
    private static final int KIND_INLINE__RBRACK = 408;
    private static final int KIND_INLINE__COLON_COLON = 382;
    private static final int KIND_INLINE__MINUS_GT_GT = 375;
    private static final int KIND_INLINE__HASH_GT_GT = 376;
    private static final int KIND_INLINE__HASH_GT = 383;
    private static final int KIND_INLINE__AT_GT = 384;
    private static final int KIND_INLINE__LT_AT = 385;
    private static final int KIND_INLINE__AMP_AMP = 386;
    private static final int KIND_PARAMREF = 55;
    private static final int KIND_INLINE_EXISTS_CI = 521;
    private static final int KIND_INLINE_ANY_CI = 444;
    private static final int KIND_INLINE_SOME_CI = 490;
    private static final int KIND_INLINE_ARRAY_CI = 445;
    private static final int KIND_INLINE_CAST_CI = 451;
    private static final int KIND_INLINE_CASE_CI = 450;
    private static final int KIND_INLINE_WHEN_CI = 501;
    private static final int KIND_INLINE_THEN_CI = 493;
    private static final int KIND_INLINE_ELSE_CI = 468;
    private static final int KIND_INLINE_COALESCE_CI = 532;
    private static final int KIND_INLINE_NULLIF_CI = 533;
    private static final int KIND_INLINE_GREATEST_CI = 534;
    private static final int KIND_INLINE_LEAST_CI = 535;
    private static final int KIND_INLINE_EXTRACT_CI = 536;
    private static final int KIND_INLINE_POSITION_CI = 537;
    private static final int KIND_INLINE_SUBSTRING_CI = 538;
    private static final int KIND_INLINE_TRIM_CI = 539;
    private static final int KIND_INLINE_LEADING_CI = 479;
    private static final int KIND_INLINE_TRAILING_CI = 495;
    private static final int KIND_INLINE_BOTH_CI = 449;
    private static final int KIND_INLINE_OVERLAY_CI = 540;
    private static final int KIND_INLINE_PLACING_CI = 485;
    private static final int KIND_INLINE_FILTER_CI = 349;
    private static final int KIND_INLINE_OVER_CI = 374;
    private static final int KIND_INLINE_WITHIN_CI = 350;
    private static final int KIND_INLINE__AT_AT = 387;
    private static final int KIND_INLINE__AT_QMARK = 388;
    private static final int KIND_INLINE__QMARK = 409;
    private static final int KIND_INLINE__QMARK_PIPE = 389;
    private static final int KIND_INLINE__QMARK_AMP = 390;
    private static final int KIND_INLINE_BOOLEAN_CI = 420;
    private static final int KIND_INLINE_BOOL_CI = 421;
    private static final int KIND_INLINE_JSONB_CI = 422;
    private static final int KIND_INLINE_JSON_CI = 423;
    private static final int KIND_INLINE_UUID_CI = 424;
    private static final int KIND_INLINE_BYTEA_CI = 425;
    private static final int KIND_INLINE_XML_CI = 426;
    private static final int KIND_INLINE_INET_CI = 434;
    private static final int KIND_INLINE_CIDR_CI = 435;
    private static final int KIND_INLINE_MACADDR8_CI = 436;
    private static final int KIND_INLINE_MACADDR_CI = 437;
    private static final int KIND_INLINE_MONEY_CI = 427;
    private static final int KIND_INLINE_BIGSERIAL_CI = 428;
    private static final int KIND_INLINE_SMALLSERIAL_CI = 429;
    private static final int KIND_INLINE_SERIAL8_CI = 430;
    private static final int KIND_INLINE_SERIAL4_CI = 431;
    private static final int KIND_INLINE_SERIAL2_CI = 432;
    private static final int KIND_INLINE_SERIAL_CI = 433;
    private static final int KIND_INLINE_TSVECTOR_CI = 438;
    private static final int KIND_INLINE_TSQUERY_CI = 439;
    private static final int KIND_INLINE_DOUBLE_CI = 336;
    private static final int KIND_INLINE_PRECISION_CI = 321;
    private static final int KIND_INLINE_SMALLINT_CI = 327;
    private static final int KIND_INLINE_INTEGER_CI = 330;
    private static final int KIND_INLINE_BIGINT_CI = 337;
    private static final int KIND_INLINE_INT8_CI = 362;
    private static final int KIND_INLINE_INT4_CI = 363;
    private static final int KIND_INLINE_INT2_CI = 364;
    private static final int KIND_INLINE_INT_CI = 377;
    private static final int KIND_INLINE_FLOAT8_CI = 338;
    private static final int KIND_INLINE_FLOAT4_CI = 339;
    private static final int KIND_INLINE_FLOAT_CI = 351;
    private static final int KIND_INLINE_NUMERIC_CI = 331;
    private static final int KIND_INLINE_DECIMAL_CI = 332;
    private static final int KIND_INLINE_REAL_CI = 365;
    private static final int KIND_INLINE_CHARACTER_CI = 322;
    private static final int KIND_INLINE_VARYING_CI = 333;
    private static final int KIND_INLINE_VARCHAR_CI = 334;
    private static final int KIND_INLINE_CHAR_CI = 366;
    private static final int KIND_INLINE_TEXT_CI = 367;
    private static final int KIND_INLINE_NAME_CI = 368;
    private static final int KIND_INLINE_CITEXT_CI = 340;
    private static final int KIND_INLINE_DATE_CI = 419;
    private static final int KIND_INLINE_TIMESTAMPTZ_CI = 320;
    private static final int KIND_INLINE_TIMESTAMP_CI = 323;
    private static final int KIND_INLINE_TIME_CI = 369;
    private static final int KIND_INLINE_ZONE_CI = 653;
    private static final int KIND_INLINE_WITHOUT_CI = 526;
    private static final int KIND_INLINE_TIMETZ_CI = 341;
    private static final int KIND_INLINE_INTERVAL_CI = 328;
    private static final int KIND_INTERVALFIELD = 57;
    private static final int KIND_INLINE_VARBIT_CI = 342;
    private static final int KIND_INLINE_BIT_CI = 378;
    private static final int KIND_BOOLEANLITERAL = 73;
    private static final int KIND_NULLLITERAL = 72;
    private static final int KIND_INLINE__DOLLAR_DOLLAR = 391;
    private static final int KIND_INLINE__DOLLAR = 410;
    private static final int KIND_INLINE_ANALYSE_CI = 441;
    private static final int KIND_INLINE_ASC_CI = 447;
    private static final int KIND_INLINE_CURRENT_CATALOG_CI = 457;
    private static final int KIND_INLINE_CURRENT_DATE_CI = 458;
    private static final int KIND_INLINE_CURRENT_ROLE_CI = 459;
    private static final int KIND_INLINE_CURRENT_SCHEMA_CI = 460;
    private static final int KIND_INLINE_CURRENT_TIME_CI = 461;
    private static final int KIND_INLINE_CURRENT_TIMESTAMP_CI = 462;
    private static final int KIND_INLINE_CURRENT_USER_CI = 463;
    private static final int KIND_INLINE_DEFERRABLE_CI = 465;
    private static final int KIND_INLINE_DESC_CI = 466;
    private static final int KIND_INLINE_INITIALLY_CI = 476;
    private static final int KIND_INLINE_LOCALTIME_CI = 480;
    private static final int KIND_INLINE_LOCALTIMESTAMP_CI = 481;
    private static final int KIND_INLINE_SESSION_USER_CI = 489;
    private static final int KIND_INLINE_USER_CI = 498;
    private static final int KIND_INLINE_INNER_CI = 357;
    private static final int KIND_INLINE_LEFT_CI = 371;
    private static final int KIND_INLINE_RIGHT_CI = 358;
    private static final int KIND_INLINE_FULL_CI = 372;
    private static final int KIND_INLINE_GROUPS_CI = 348;

    private static final int[] DEFAULT_SYNC = new int[] {394, 396, 397, 408};

    private static final int[] ALIAS_SERIALTYPE = new int[] {428, 429, 430, 431, 432, 433};
    private static final int[] ALIAS_RESERVEDKEYWORD = new int[] {324, 325, 343, 344, 345, 347, 352, 353, 354, 355, 356, 360, 392, 393, 411, 440, 441, 442, 443, 444, 445, 446, 447, 448, 449, 450, 451, 452, 453, 454, 455, 456, 457, 458, 459, 460, 461, 462, 463, 464, 465, 466, 467, 468, 469, 470, 471, 472, 473, 474, 475, 476, 477, 478, 479, 480, 481, 482, 483, 484, 485, 486, 487, 488, 489, 490, 491, 492, 493, 494, 495, 496, 497, 498, 499, 500, 501, 502};

    private final TokenArray tokens;
    private final CstArrayBuilder cst;
    private final List<Diagnostic> diagnostics;
    private int pos;
    private int errorPos;
    private String expected;
    private int found;
    private int lastFailedRuleKind;
    private final java.util.Map<String, long[]> captures = new java.util.HashMap<>();
    private final java.util.ArrayDeque<java.util.Map<String, long[]>> captureScopeStack = new java.util.ArrayDeque<>();
    private final int maxDiagnostics;

    private PgSqlParser(TokenArray tokens, int maxDiagnostics) {
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
        PgSqlParser p = new PgSqlParser(tokens, maxDiagnostics);
        int rootIdx = p.parseWithRecovery();
        CstArray cstArr = p.cst.build(rootIdx);
        return new ParseResult(cstArr, p.diagnostics);
    }

    public static ParseResult parseRuleFrom(TokenArray tokens, int fromTokenIdx, int ruleKind) {
        PgSqlParser p = new PgSqlParser(tokens, Integer.MAX_VALUE);
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

    private static boolean parseByKind(PgSqlParser p, int kind, int parent) {
        switch (kind) {
            case RULE_Input_KIND: return p.parseInput(parent);
            case RULE_Statement_KIND: return p.parseStatement(parent);
            case RULE_DdlStatement_KIND: return p.parseDdlStatement(parent);
            case RULE_CreateStatement_KIND: return p.parseCreateStatement(parent);
            case RULE_AlterStatement_KIND: return p.parseAlterStatement(parent);
            case RULE_DropStatement_KIND: return p.parseDropStatement(parent);
            case RULE_DmlStatement_KIND: return p.parseDmlStatement(parent);
            case RULE_CreateTableStmt_KIND: return p.parseCreateTableStmt(parent);
            case RULE_TableElementList_KIND: return p.parseTableElementList(parent);
            case RULE_TableElement_KIND: return p.parseTableElement(parent);
            case RULE_ColumnDef_KIND: return p.parseColumnDef(parent);
            case RULE_ColConstraint_KIND: return p.parseColConstraint(parent);
            case RULE_ColConstraintElem_KIND: return p.parseColConstraintElem(parent);
            case RULE_CheckColConstraint_KIND: return p.parseCheckColConstraint(parent);
            case RULE_DefaultClause_KIND: return p.parseDefaultClause(parent);
            case RULE_CollateClause_KIND: return p.parseCollateClause(parent);
            case RULE_ReferencesClause_KIND: return p.parseReferencesClause(parent);
            case RULE_GeneratedClause_KIND: return p.parseGeneratedClause(parent);
            case RULE_IdentityClause_KIND: return p.parseIdentityClause(parent);
            case RULE_IdentitySpec_KIND: return p.parseIdentitySpec(parent);
            case RULE_TableConstraint_KIND: return p.parseTableConstraint(parent);
            case RULE_TableConstraintElem_KIND: return p.parseTableConstraintElem(parent);
            case RULE_PrimaryKeyTblConstraint_KIND: return p.parsePrimaryKeyTblConstraint(parent);
            case RULE_UniqueTblConstraint_KIND: return p.parseUniqueTblConstraint(parent);
            case RULE_CheckTblConstraint_KIND: return p.parseCheckTblConstraint(parent);
            case RULE_ForeignKeyTblConstraint_KIND: return p.parseForeignKeyTblConstraint(parent);
            case RULE_ExcludeTblConstraint_KIND: return p.parseExcludeTblConstraint(parent);
            case RULE_ExcludeElementList_KIND: return p.parseExcludeElementList(parent);
            case RULE_ExcludeElement_KIND: return p.parseExcludeElement(parent);
            case RULE_IndexOptions_KIND: return p.parseIndexOptions(parent);
            case RULE_IncludeClause_KIND: return p.parseIncludeClause(parent);
            case RULE_WithStorageParams_KIND: return p.parseWithStorageParams(parent);
            case RULE_StorageParamList_KIND: return p.parseStorageParamList(parent);
            case RULE_StorageParam_KIND: return p.parseStorageParam(parent);
            case RULE_TableOptions_KIND: return p.parseTableOptions(parent);
            case RULE_PartitionByClause_KIND: return p.parsePartitionByClause(parent);
            case RULE_PartitionKeyList_KIND: return p.parsePartitionKeyList(parent);
            case RULE_PartitionKey_KIND: return p.parsePartitionKey(parent);
            case RULE_InheritsClause_KIND: return p.parseInheritsClause(parent);
            case RULE_ColumnList_KIND: return p.parseColumnList(parent);
            case RULE_QualifiedNameList_KIND: return p.parseQualifiedNameList(parent);
            case RULE_AlterTableStmt_KIND: return p.parseAlterTableStmt(parent);
            case RULE_AlterTableActions_KIND: return p.parseAlterTableActions(parent);
            case RULE_AlterTableAction_KIND: return p.parseAlterTableAction(parent);
            case RULE_AddColumnAction_KIND: return p.parseAddColumnAction(parent);
            case RULE_AlterColumnAction_KIND: return p.parseAlterColumnAction(parent);
            case RULE_AlterColumnCmd_KIND: return p.parseAlterColumnCmd(parent);
            case RULE_SetDataTypeCmd_KIND: return p.parseSetDataTypeCmd(parent);
            case RULE_SetDefaultCmd_KIND: return p.parseSetDefaultCmd(parent);
            case RULE_SetStatisticsCmd_KIND: return p.parseSetStatisticsCmd(parent);
            case RULE_AddIdentityCmd_KIND: return p.parseAddIdentityCmd(parent);
            case RULE_AddConstraintAction_KIND: return p.parseAddConstraintAction(parent);
            case RULE_AttachPartition_KIND: return p.parseAttachPartition(parent);
            case RULE_DetachPartition_KIND: return p.parseDetachPartition(parent);
            case RULE_ForValuesClause_KIND: return p.parseForValuesClause(parent);
            case RULE_DropTableStmt_KIND: return p.parseDropTableStmt(parent);
            case RULE_CreateIndexStmt_KIND: return p.parseCreateIndexStmt(parent);
            case RULE_IndexElemList_KIND: return p.parseIndexElemList(parent);
            case RULE_IndexElem_KIND: return p.parseIndexElem(parent);
            case RULE_OpClass_KIND: return p.parseOpClass(parent);
            case RULE_AlterIndexStmt_KIND: return p.parseAlterIndexStmt(parent);
            case RULE_DropIndexStmt_KIND: return p.parseDropIndexStmt(parent);
            case RULE_CreateSequenceStmt_KIND: return p.parseCreateSequenceStmt(parent);
            case RULE_AlterSequenceStmt_KIND: return p.parseAlterSequenceStmt(parent);
            case RULE_DropSequenceStmt_KIND: return p.parseDropSequenceStmt(parent);
            case RULE_SequenceOptions_KIND: return p.parseSequenceOptions(parent);
            case RULE_SequenceOption_KIND: return p.parseSequenceOption(parent);
            case RULE_CreateTypeStmt_KIND: return p.parseCreateTypeStmt(parent);
            case RULE_EnumLabelList_KIND: return p.parseEnumLabelList(parent);
            case RULE_CompositeFieldList_KIND: return p.parseCompositeFieldList(parent);
            case RULE_CompositeField_KIND: return p.parseCompositeField(parent);
            case RULE_RangeOptionList_KIND: return p.parseRangeOptionList(parent);
            case RULE_RangeOption_KIND: return p.parseRangeOption(parent);
            case RULE_DomainConstraint_KIND: return p.parseDomainConstraint(parent);
            case RULE_AlterTypeStmt_KIND: return p.parseAlterTypeStmt(parent);
            case RULE_DropTypeStmt_KIND: return p.parseDropTypeStmt(parent);
            case RULE_DropSchemaStmt_KIND: return p.parseDropSchemaStmt(parent);
            case RULE_CreateViewStmt_KIND: return p.parseCreateViewStmt(parent);
            case RULE_CreateMatViewStmt_KIND: return p.parseCreateMatViewStmt(parent);
            case RULE_AlterViewStmt_KIND: return p.parseAlterViewStmt(parent);
            case RULE_DropViewStmt_KIND: return p.parseDropViewStmt(parent);
            case RULE_DropMatViewStmt_KIND: return p.parseDropMatViewStmt(parent);
            case RULE_CreateExtensionStmt_KIND: return p.parseCreateExtensionStmt(parent);
            case RULE_ExtensionOptions_KIND: return p.parseExtensionOptions(parent);
            case RULE_ExtensionOption_KIND: return p.parseExtensionOption(parent);
            case RULE_DropExtensionStmt_KIND: return p.parseDropExtensionStmt(parent);
            case RULE_CommentStatement_KIND: return p.parseCommentStatement(parent);
            case RULE_CommentTarget_KIND: return p.parseCommentTarget(parent);
            case RULE_FuncArgTypes_KIND: return p.parseFuncArgTypes(parent);
            case RULE_FuncArgType_KIND: return p.parseFuncArgType(parent);
            case RULE_GrantStatement_KIND: return p.parseGrantStatement(parent);
            case RULE_RevokeStatement_KIND: return p.parseRevokeStatement(parent);
            case RULE_PrivilegeList_KIND: return p.parsePrivilegeList(parent);
            case RULE_Privilege_KIND: return p.parsePrivilege(parent);
            case RULE_GrantTarget_KIND: return p.parseGrantTarget(parent);
            case RULE_GranteeList_KIND: return p.parseGranteeList(parent);
            case RULE_AlterDefaultPrivilegesPassthrough_KIND: return p.parseAlterDefaultPrivilegesPassthrough(parent);
            case RULE_CreateFunctionPassthrough_KIND: return p.parseCreateFunctionPassthrough(parent);
            case RULE_CreateTriggerPassthrough_KIND: return p.parseCreateTriggerPassthrough(parent);
            case RULE_DropFunctionPassthrough_KIND: return p.parseDropFunctionPassthrough(parent);
            case RULE_DropTriggerPassthrough_KIND: return p.parseDropTriggerPassthrough(parent);
            case RULE_SelectStmt_KIND: return p.parseSelectStmt(parent);
            case RULE_SelectCore_KIND: return p.parseSelectCore(parent);
            case RULE_SetQuantifier_KIND: return p.parseSetQuantifier(parent);
            case RULE_TargetList_KIND: return p.parseTargetList(parent);
            case RULE_TargetElem_KIND: return p.parseTargetElem(parent);
            case RULE_StarExpr_KIND: return p.parseStarExpr(parent);
            case RULE_IntoClause_KIND: return p.parseIntoClause(parent);
            case RULE_FromClause_KIND: return p.parseFromClause(parent);
            case RULE_FromList_KIND: return p.parseFromList(parent);
            case RULE_TableRef_KIND: return p.parseTableRef(parent);
            case RULE_TableRefBase_KIND: return p.parseTableRefBase(parent);
            case RULE_BaseTableRef_KIND: return p.parseBaseTableRef(parent);
            case RULE_SubqueryRef_KIND: return p.parseSubqueryRef(parent);
            case RULE_LateralRef_KIND: return p.parseLateralRef(parent);
            case RULE_FuncTableRef_KIND: return p.parseFuncTableRef(parent);
            case RULE_Alias_KIND: return p.parseAlias(parent);
            case RULE_TablesampleClause_KIND: return p.parseTablesampleClause(parent);
            case RULE_JoinExpr_KIND: return p.parseJoinExpr(parent);
            case RULE_JoinClause_KIND: return p.parseJoinClause(parent);
            case RULE_JoinQual_KIND: return p.parseJoinQual(parent);
            case RULE_WhereClause_KIND: return p.parseWhereClause(parent);
            case RULE_GroupByClause_KIND: return p.parseGroupByClause(parent);
            case RULE_GroupByList_KIND: return p.parseGroupByList(parent);
            case RULE_GroupByElem_KIND: return p.parseGroupByElem(parent);
            case RULE_HavingClause_KIND: return p.parseHavingClause(parent);
            case RULE_WindowClause_KIND: return p.parseWindowClause(parent);
            case RULE_WindowDefList_KIND: return p.parseWindowDefList(parent);
            case RULE_WindowDef_KIND: return p.parseWindowDef(parent);
            case RULE_WindowSpec_KIND: return p.parseWindowSpec(parent);
            case RULE_PartitionClause_KIND: return p.parsePartitionClause(parent);
            case RULE_FrameClause_KIND: return p.parseFrameClause(parent);
            case RULE_FrameExtent_KIND: return p.parseFrameExtent(parent);
            case RULE_FrameBound_KIND: return p.parseFrameBound(parent);
            case RULE_WithClause_KIND: return p.parseWithClause(parent);
            case RULE_CteList_KIND: return p.parseCteList(parent);
            case RULE_CteDef_KIND: return p.parseCteDef(parent);
            case RULE_SetOp_KIND: return p.parseSetOp(parent);
            case RULE_OrderByClause_KIND: return p.parseOrderByClause(parent);
            case RULE_OrderByList_KIND: return p.parseOrderByList(parent);
            case RULE_OrderByItem_KIND: return p.parseOrderByItem(parent);
            case RULE_LimitClause_KIND: return p.parseLimitClause(parent);
            case RULE_OffsetClause_KIND: return p.parseOffsetClause(parent);
            case RULE_FetchClause_KIND: return p.parseFetchClause(parent);
            case RULE_InsertStmt_KIND: return p.parseInsertStmt(parent);
            case RULE_InsertSource_KIND: return p.parseInsertSource(parent);
            case RULE_ValuesClause_KIND: return p.parseValuesClause(parent);
            case RULE_ValueRowList_KIND: return p.parseValueRowList(parent);
            case RULE_ExprOrDefaultList_KIND: return p.parseExprOrDefaultList(parent);
            case RULE_ExprOrDefault_KIND: return p.parseExprOrDefault(parent);
            case RULE_OnConflictClause_KIND: return p.parseOnConflictClause(parent);
            case RULE_ConflictTarget_KIND: return p.parseConflictTarget(parent);
            case RULE_ConflictAction_KIND: return p.parseConflictAction(parent);
            case RULE_ReturningClause_KIND: return p.parseReturningClause(parent);
            case RULE_UpdateStmt_KIND: return p.parseUpdateStmt(parent);
            case RULE_UpdateSetList_KIND: return p.parseUpdateSetList(parent);
            case RULE_UpdateSetItem_KIND: return p.parseUpdateSetItem(parent);
            case RULE_DeleteStmt_KIND: return p.parseDeleteStmt(parent);
            case RULE_UsingClauseDelete_KIND: return p.parseUsingClauseDelete(parent);
            case RULE_PassthroughStatement_KIND: return p.parsePassthroughStatement(parent);
            case RULE_TransactionStmt_KIND: return p.parseTransactionStmt(parent);
            case RULE_SessionStmt_KIND: return p.parseSessionStmt(parent);
            case RULE_UtilityStmt_KIND: return p.parseUtilityStmt(parent);
            case RULE_TruncateStmt_KIND: return p.parseTruncateStmt(parent);
            case RULE_ExplainStmt_KIND: return p.parseExplainStmt(parent);
            case RULE_CopyStmt_KIND: return p.parseCopyStmt(parent);
            case RULE_RefreshMatViewStmt_KIND: return p.parseRefreshMatViewStmt(parent);
            case RULE_RestOfStatement_KIND: return p.parseRestOfStatement(parent);
            case RULE_Expr_KIND: return p.parseExpr(parent);
            case RULE_OrExpr_KIND: return p.parseOrExpr(parent);
            case RULE_AndExpr_KIND: return p.parseAndExpr(parent);
            case RULE_NotExpr_KIND: return p.parseNotExpr(parent);
            case RULE_CompareExpr_KIND: return p.parseCompareExpr(parent);
            case RULE_IsExpr_KIND: return p.parseIsExpr(parent);
            case RULE_IsClause_KIND: return p.parseIsClause(parent);
            case RULE_InExpr_KIND: return p.parseInExpr(parent);
            case RULE_BetweenExpr_KIND: return p.parseBetweenExpr(parent);
            case RULE_LikeExpr_KIND: return p.parseLikeExpr(parent);
            case RULE_SimilarToExpr_KIND: return p.parseSimilarToExpr(parent);
            case RULE_IsDistinctFrom_KIND: return p.parseIsDistinctFrom(parent);
            case RULE_AddExpr_KIND: return p.parseAddExpr(parent);
            case RULE_MulExpr_KIND: return p.parseMulExpr(parent);
            case RULE_UnaryExpr_KIND: return p.parseUnaryExpr(parent);
            case RULE_ExponentExpr_KIND: return p.parseExponentExpr(parent);
            case RULE_ConcatExpr_KIND: return p.parseConcatExpr(parent);
            case RULE_ArrayExpr_KIND: return p.parseArrayExpr(parent);
            case RULE_TypeCastExpr_KIND: return p.parseTypeCastExpr(parent);
            case RULE_PostfixExpr_KIND: return p.parsePostfixExpr(parent);
            case RULE_PostfixOp_KIND: return p.parsePostfixOp(parent);
            case RULE_PrimaryExpr_KIND: return p.parsePrimaryExpr(parent);
            case RULE_ColRef_KIND: return p.parseColRef(parent);
            case RULE_ExistsExpr_KIND: return p.parseExistsExpr(parent);
            case RULE_SubqueryExpr_KIND: return p.parseSubqueryExpr(parent);
            case RULE_AnyAllExpr_KIND: return p.parseAnyAllExpr(parent);
            case RULE_RowExpr_KIND: return p.parseRowExpr(parent);
            case RULE_ArrayExprConstructor_KIND: return p.parseArrayExprConstructor(parent);
            case RULE_CastExpr_KIND: return p.parseCastExpr(parent);
            case RULE_CaseExpr_KIND: return p.parseCaseExpr(parent);
            case RULE_WhenClause_KIND: return p.parseWhenClause(parent);
            case RULE_ElseClause_KIND: return p.parseElseClause(parent);
            case RULE_CoalesceExpr_KIND: return p.parseCoalesceExpr(parent);
            case RULE_NullIfExpr_KIND: return p.parseNullIfExpr(parent);
            case RULE_GreatestLeastExpr_KIND: return p.parseGreatestLeastExpr(parent);
            case RULE_ExtractExpr_KIND: return p.parseExtractExpr(parent);
            case RULE_PositionExpr_KIND: return p.parsePositionExpr(parent);
            case RULE_SubstringExpr_KIND: return p.parseSubstringExpr(parent);
            case RULE_TrimExpr_KIND: return p.parseTrimExpr(parent);
            case RULE_OverlayExpr_KIND: return p.parseOverlayExpr(parent);
            case RULE_TypedLiteral_KIND: return p.parseTypedLiteral(parent);
            case RULE_FuncCall_KIND: return p.parseFuncCall(parent);
            case RULE_FuncCallArgs_KIND: return p.parseFuncCallArgs(parent);
            case RULE_FuncName_KIND: return p.parseFuncName(parent);
            case RULE_FilterClause_KIND: return p.parseFilterClause(parent);
            case RULE_OverClause_KIND: return p.parseOverClause(parent);
            case RULE_WithinGroupClause_KIND: return p.parseWithinGroupClause(parent);
            case RULE_ExprList_KIND: return p.parseExprList(parent);
            case RULE_Operator_KIND: return p.parseOperator(parent);
            case RULE_DataType_KIND: return p.parseDataType(parent);
            case RULE_ArrayType_KIND: return p.parseArrayType(parent);
            case RULE_ScalarType_KIND: return p.parseScalarType(parent);
            case RULE_NumericType_KIND: return p.parseNumericType(parent);
            case RULE_CharType_KIND: return p.parseCharType(parent);
            case RULE_DateTimeType_KIND: return p.parseDateTimeType(parent);
            case RULE_TimestampType_KIND: return p.parseTimestampType(parent);
            case RULE_TimeType_KIND: return p.parseTimeType(parent);
            case RULE_IntervalType_KIND: return p.parseIntervalType(parent);
            case RULE_BitType_KIND: return p.parseBitType(parent);
            case RULE_TypeModifiers_KIND: return p.parseTypeModifiers(parent);
            case RULE_QualifiedTypeName_KIND: return p.parseQualifiedTypeName(parent);
            case RULE_QualifiedName_KIND: return p.parseQualifiedName(parent);
            case RULE_Literal_KIND: return p.parseLiteral(parent);
            case RULE_SignedNumericLiteral_KIND: return p.parseSignedNumericLiteral(parent);
            case RULE_StringLiteral_KIND: return p.parseStringLiteral(parent);
            case RULE_DollarString_KIND: return p.parseDollarString(parent);
            case RULE_ClauseKeyword_KIND: return p.parseClauseKeyword(parent);
            default: return false;
        }
    }

    public static Map<String, Integer> ruleKinds() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("Input", RULE_Input_KIND);
        m.put("Statement", RULE_Statement_KIND);
        m.put("DdlStatement", RULE_DdlStatement_KIND);
        m.put("CreateStatement", RULE_CreateStatement_KIND);
        m.put("AlterStatement", RULE_AlterStatement_KIND);
        m.put("DropStatement", RULE_DropStatement_KIND);
        m.put("DmlStatement", RULE_DmlStatement_KIND);
        m.put("CreateTableStmt", RULE_CreateTableStmt_KIND);
        m.put("TableElementList", RULE_TableElementList_KIND);
        m.put("TableElement", RULE_TableElement_KIND);
        m.put("ColumnDef", RULE_ColumnDef_KIND);
        m.put("ColConstraint", RULE_ColConstraint_KIND);
        m.put("ColConstraintElem", RULE_ColConstraintElem_KIND);
        m.put("CheckColConstraint", RULE_CheckColConstraint_KIND);
        m.put("DefaultClause", RULE_DefaultClause_KIND);
        m.put("CollateClause", RULE_CollateClause_KIND);
        m.put("ReferencesClause", RULE_ReferencesClause_KIND);
        m.put("GeneratedClause", RULE_GeneratedClause_KIND);
        m.put("IdentityClause", RULE_IdentityClause_KIND);
        m.put("IdentitySpec", RULE_IdentitySpec_KIND);
        m.put("TableConstraint", RULE_TableConstraint_KIND);
        m.put("TableConstraintElem", RULE_TableConstraintElem_KIND);
        m.put("PrimaryKeyTblConstraint", RULE_PrimaryKeyTblConstraint_KIND);
        m.put("UniqueTblConstraint", RULE_UniqueTblConstraint_KIND);
        m.put("CheckTblConstraint", RULE_CheckTblConstraint_KIND);
        m.put("ForeignKeyTblConstraint", RULE_ForeignKeyTblConstraint_KIND);
        m.put("ExcludeTblConstraint", RULE_ExcludeTblConstraint_KIND);
        m.put("ExcludeElementList", RULE_ExcludeElementList_KIND);
        m.put("ExcludeElement", RULE_ExcludeElement_KIND);
        m.put("IndexOptions", RULE_IndexOptions_KIND);
        m.put("IncludeClause", RULE_IncludeClause_KIND);
        m.put("WithStorageParams", RULE_WithStorageParams_KIND);
        m.put("StorageParamList", RULE_StorageParamList_KIND);
        m.put("StorageParam", RULE_StorageParam_KIND);
        m.put("TableOptions", RULE_TableOptions_KIND);
        m.put("PartitionByClause", RULE_PartitionByClause_KIND);
        m.put("PartitionKeyList", RULE_PartitionKeyList_KIND);
        m.put("PartitionKey", RULE_PartitionKey_KIND);
        m.put("InheritsClause", RULE_InheritsClause_KIND);
        m.put("ColumnList", RULE_ColumnList_KIND);
        m.put("QualifiedNameList", RULE_QualifiedNameList_KIND);
        m.put("AlterTableStmt", RULE_AlterTableStmt_KIND);
        m.put("AlterTableActions", RULE_AlterTableActions_KIND);
        m.put("AlterTableAction", RULE_AlterTableAction_KIND);
        m.put("AddColumnAction", RULE_AddColumnAction_KIND);
        m.put("AlterColumnAction", RULE_AlterColumnAction_KIND);
        m.put("AlterColumnCmd", RULE_AlterColumnCmd_KIND);
        m.put("SetDataTypeCmd", RULE_SetDataTypeCmd_KIND);
        m.put("SetDefaultCmd", RULE_SetDefaultCmd_KIND);
        m.put("SetStatisticsCmd", RULE_SetStatisticsCmd_KIND);
        m.put("AddIdentityCmd", RULE_AddIdentityCmd_KIND);
        m.put("AddConstraintAction", RULE_AddConstraintAction_KIND);
        m.put("AttachPartition", RULE_AttachPartition_KIND);
        m.put("DetachPartition", RULE_DetachPartition_KIND);
        m.put("ForValuesClause", RULE_ForValuesClause_KIND);
        m.put("DropTableStmt", RULE_DropTableStmt_KIND);
        m.put("CreateIndexStmt", RULE_CreateIndexStmt_KIND);
        m.put("IndexElemList", RULE_IndexElemList_KIND);
        m.put("IndexElem", RULE_IndexElem_KIND);
        m.put("OpClass", RULE_OpClass_KIND);
        m.put("AlterIndexStmt", RULE_AlterIndexStmt_KIND);
        m.put("DropIndexStmt", RULE_DropIndexStmt_KIND);
        m.put("CreateSequenceStmt", RULE_CreateSequenceStmt_KIND);
        m.put("AlterSequenceStmt", RULE_AlterSequenceStmt_KIND);
        m.put("DropSequenceStmt", RULE_DropSequenceStmt_KIND);
        m.put("SequenceOptions", RULE_SequenceOptions_KIND);
        m.put("SequenceOption", RULE_SequenceOption_KIND);
        m.put("CreateTypeStmt", RULE_CreateTypeStmt_KIND);
        m.put("EnumLabelList", RULE_EnumLabelList_KIND);
        m.put("CompositeFieldList", RULE_CompositeFieldList_KIND);
        m.put("CompositeField", RULE_CompositeField_KIND);
        m.put("RangeOptionList", RULE_RangeOptionList_KIND);
        m.put("RangeOption", RULE_RangeOption_KIND);
        m.put("DomainConstraint", RULE_DomainConstraint_KIND);
        m.put("AlterTypeStmt", RULE_AlterTypeStmt_KIND);
        m.put("DropTypeStmt", RULE_DropTypeStmt_KIND);
        m.put("DropSchemaStmt", RULE_DropSchemaStmt_KIND);
        m.put("CreateViewStmt", RULE_CreateViewStmt_KIND);
        m.put("CreateMatViewStmt", RULE_CreateMatViewStmt_KIND);
        m.put("AlterViewStmt", RULE_AlterViewStmt_KIND);
        m.put("DropViewStmt", RULE_DropViewStmt_KIND);
        m.put("DropMatViewStmt", RULE_DropMatViewStmt_KIND);
        m.put("CreateExtensionStmt", RULE_CreateExtensionStmt_KIND);
        m.put("ExtensionOptions", RULE_ExtensionOptions_KIND);
        m.put("ExtensionOption", RULE_ExtensionOption_KIND);
        m.put("DropExtensionStmt", RULE_DropExtensionStmt_KIND);
        m.put("CommentStatement", RULE_CommentStatement_KIND);
        m.put("CommentTarget", RULE_CommentTarget_KIND);
        m.put("FuncArgTypes", RULE_FuncArgTypes_KIND);
        m.put("FuncArgType", RULE_FuncArgType_KIND);
        m.put("GrantStatement", RULE_GrantStatement_KIND);
        m.put("RevokeStatement", RULE_RevokeStatement_KIND);
        m.put("PrivilegeList", RULE_PrivilegeList_KIND);
        m.put("Privilege", RULE_Privilege_KIND);
        m.put("GrantTarget", RULE_GrantTarget_KIND);
        m.put("GranteeList", RULE_GranteeList_KIND);
        m.put("AlterDefaultPrivilegesPassthrough", RULE_AlterDefaultPrivilegesPassthrough_KIND);
        m.put("CreateFunctionPassthrough", RULE_CreateFunctionPassthrough_KIND);
        m.put("CreateTriggerPassthrough", RULE_CreateTriggerPassthrough_KIND);
        m.put("DropFunctionPassthrough", RULE_DropFunctionPassthrough_KIND);
        m.put("DropTriggerPassthrough", RULE_DropTriggerPassthrough_KIND);
        m.put("SelectStmt", RULE_SelectStmt_KIND);
        m.put("SelectCore", RULE_SelectCore_KIND);
        m.put("SetQuantifier", RULE_SetQuantifier_KIND);
        m.put("TargetList", RULE_TargetList_KIND);
        m.put("TargetElem", RULE_TargetElem_KIND);
        m.put("StarExpr", RULE_StarExpr_KIND);
        m.put("IntoClause", RULE_IntoClause_KIND);
        m.put("FromClause", RULE_FromClause_KIND);
        m.put("FromList", RULE_FromList_KIND);
        m.put("TableRef", RULE_TableRef_KIND);
        m.put("TableRefBase", RULE_TableRefBase_KIND);
        m.put("BaseTableRef", RULE_BaseTableRef_KIND);
        m.put("SubqueryRef", RULE_SubqueryRef_KIND);
        m.put("LateralRef", RULE_LateralRef_KIND);
        m.put("FuncTableRef", RULE_FuncTableRef_KIND);
        m.put("Alias", RULE_Alias_KIND);
        m.put("TablesampleClause", RULE_TablesampleClause_KIND);
        m.put("JoinExpr", RULE_JoinExpr_KIND);
        m.put("JoinClause", RULE_JoinClause_KIND);
        m.put("JoinQual", RULE_JoinQual_KIND);
        m.put("WhereClause", RULE_WhereClause_KIND);
        m.put("GroupByClause", RULE_GroupByClause_KIND);
        m.put("GroupByList", RULE_GroupByList_KIND);
        m.put("GroupByElem", RULE_GroupByElem_KIND);
        m.put("HavingClause", RULE_HavingClause_KIND);
        m.put("WindowClause", RULE_WindowClause_KIND);
        m.put("WindowDefList", RULE_WindowDefList_KIND);
        m.put("WindowDef", RULE_WindowDef_KIND);
        m.put("WindowSpec", RULE_WindowSpec_KIND);
        m.put("PartitionClause", RULE_PartitionClause_KIND);
        m.put("FrameClause", RULE_FrameClause_KIND);
        m.put("FrameExtent", RULE_FrameExtent_KIND);
        m.put("FrameBound", RULE_FrameBound_KIND);
        m.put("WithClause", RULE_WithClause_KIND);
        m.put("CteList", RULE_CteList_KIND);
        m.put("CteDef", RULE_CteDef_KIND);
        m.put("SetOp", RULE_SetOp_KIND);
        m.put("OrderByClause", RULE_OrderByClause_KIND);
        m.put("OrderByList", RULE_OrderByList_KIND);
        m.put("OrderByItem", RULE_OrderByItem_KIND);
        m.put("LimitClause", RULE_LimitClause_KIND);
        m.put("OffsetClause", RULE_OffsetClause_KIND);
        m.put("FetchClause", RULE_FetchClause_KIND);
        m.put("InsertStmt", RULE_InsertStmt_KIND);
        m.put("InsertSource", RULE_InsertSource_KIND);
        m.put("ValuesClause", RULE_ValuesClause_KIND);
        m.put("ValueRowList", RULE_ValueRowList_KIND);
        m.put("ExprOrDefaultList", RULE_ExprOrDefaultList_KIND);
        m.put("ExprOrDefault", RULE_ExprOrDefault_KIND);
        m.put("OnConflictClause", RULE_OnConflictClause_KIND);
        m.put("ConflictTarget", RULE_ConflictTarget_KIND);
        m.put("ConflictAction", RULE_ConflictAction_KIND);
        m.put("ReturningClause", RULE_ReturningClause_KIND);
        m.put("UpdateStmt", RULE_UpdateStmt_KIND);
        m.put("UpdateSetList", RULE_UpdateSetList_KIND);
        m.put("UpdateSetItem", RULE_UpdateSetItem_KIND);
        m.put("DeleteStmt", RULE_DeleteStmt_KIND);
        m.put("UsingClauseDelete", RULE_UsingClauseDelete_KIND);
        m.put("PassthroughStatement", RULE_PassthroughStatement_KIND);
        m.put("TransactionStmt", RULE_TransactionStmt_KIND);
        m.put("SessionStmt", RULE_SessionStmt_KIND);
        m.put("UtilityStmt", RULE_UtilityStmt_KIND);
        m.put("TruncateStmt", RULE_TruncateStmt_KIND);
        m.put("ExplainStmt", RULE_ExplainStmt_KIND);
        m.put("CopyStmt", RULE_CopyStmt_KIND);
        m.put("RefreshMatViewStmt", RULE_RefreshMatViewStmt_KIND);
        m.put("RestOfStatement", RULE_RestOfStatement_KIND);
        m.put("Expr", RULE_Expr_KIND);
        m.put("OrExpr", RULE_OrExpr_KIND);
        m.put("AndExpr", RULE_AndExpr_KIND);
        m.put("NotExpr", RULE_NotExpr_KIND);
        m.put("CompareExpr", RULE_CompareExpr_KIND);
        m.put("IsExpr", RULE_IsExpr_KIND);
        m.put("IsClause", RULE_IsClause_KIND);
        m.put("InExpr", RULE_InExpr_KIND);
        m.put("BetweenExpr", RULE_BetweenExpr_KIND);
        m.put("LikeExpr", RULE_LikeExpr_KIND);
        m.put("SimilarToExpr", RULE_SimilarToExpr_KIND);
        m.put("IsDistinctFrom", RULE_IsDistinctFrom_KIND);
        m.put("AddExpr", RULE_AddExpr_KIND);
        m.put("MulExpr", RULE_MulExpr_KIND);
        m.put("UnaryExpr", RULE_UnaryExpr_KIND);
        m.put("ExponentExpr", RULE_ExponentExpr_KIND);
        m.put("ConcatExpr", RULE_ConcatExpr_KIND);
        m.put("ArrayExpr", RULE_ArrayExpr_KIND);
        m.put("TypeCastExpr", RULE_TypeCastExpr_KIND);
        m.put("PostfixExpr", RULE_PostfixExpr_KIND);
        m.put("PostfixOp", RULE_PostfixOp_KIND);
        m.put("PrimaryExpr", RULE_PrimaryExpr_KIND);
        m.put("ColRef", RULE_ColRef_KIND);
        m.put("ExistsExpr", RULE_ExistsExpr_KIND);
        m.put("SubqueryExpr", RULE_SubqueryExpr_KIND);
        m.put("AnyAllExpr", RULE_AnyAllExpr_KIND);
        m.put("RowExpr", RULE_RowExpr_KIND);
        m.put("ArrayExprConstructor", RULE_ArrayExprConstructor_KIND);
        m.put("CastExpr", RULE_CastExpr_KIND);
        m.put("CaseExpr", RULE_CaseExpr_KIND);
        m.put("WhenClause", RULE_WhenClause_KIND);
        m.put("ElseClause", RULE_ElseClause_KIND);
        m.put("CoalesceExpr", RULE_CoalesceExpr_KIND);
        m.put("NullIfExpr", RULE_NullIfExpr_KIND);
        m.put("GreatestLeastExpr", RULE_GreatestLeastExpr_KIND);
        m.put("ExtractExpr", RULE_ExtractExpr_KIND);
        m.put("PositionExpr", RULE_PositionExpr_KIND);
        m.put("SubstringExpr", RULE_SubstringExpr_KIND);
        m.put("TrimExpr", RULE_TrimExpr_KIND);
        m.put("OverlayExpr", RULE_OverlayExpr_KIND);
        m.put("TypedLiteral", RULE_TypedLiteral_KIND);
        m.put("FuncCall", RULE_FuncCall_KIND);
        m.put("FuncCallArgs", RULE_FuncCallArgs_KIND);
        m.put("FuncName", RULE_FuncName_KIND);
        m.put("FilterClause", RULE_FilterClause_KIND);
        m.put("OverClause", RULE_OverClause_KIND);
        m.put("WithinGroupClause", RULE_WithinGroupClause_KIND);
        m.put("ExprList", RULE_ExprList_KIND);
        m.put("Operator", RULE_Operator_KIND);
        m.put("DataType", RULE_DataType_KIND);
        m.put("ArrayType", RULE_ArrayType_KIND);
        m.put("ScalarType", RULE_ScalarType_KIND);
        m.put("NumericType", RULE_NumericType_KIND);
        m.put("CharType", RULE_CharType_KIND);
        m.put("DateTimeType", RULE_DateTimeType_KIND);
        m.put("TimestampType", RULE_TimestampType_KIND);
        m.put("TimeType", RULE_TimeType_KIND);
        m.put("IntervalType", RULE_IntervalType_KIND);
        m.put("BitType", RULE_BitType_KIND);
        m.put("TypeModifiers", RULE_TypeModifiers_KIND);
        m.put("QualifiedTypeName", RULE_QualifiedTypeName_KIND);
        m.put("QualifiedName", RULE_QualifiedName_KIND);
        m.put("Literal", RULE_Literal_KIND);
        m.put("SignedNumericLiteral", RULE_SignedNumericLiteral_KIND);
        m.put("StringLiteral", RULE_StringLiteral_KIND);
        m.put("DollarString", RULE_DollarString_KIND);
        m.put("ClauseKeyword", RULE_ClauseKeyword_KIND);
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
                // Empty or all-trivia input. That is only an error if the start
                // rule cannot match empty — a nullable start rule legitimately
                // succeeds here (Java's CompilationUnit does: a file holding
                // nothing but a license header is a valid compilation unit).
                // So attempt it once and report only on a genuine failure.
                //
                // The break below stays UNCONDITIONAL: we are at end-of-input, so
                // a nullable start rule that consumes nothing must not be retried.
                if (firstAttempt) {
                    int beforeNodesEmpty = cst.currentNodeCount();
                    errorPos = -1;
                    expected = null;
                    found = -1;
                    lastFailedRuleKind = -1;
                    if (!parseInput(root)) {
                        cst.truncate(beforeNodesEmpty);
                        if (diagnostics.size() < maxDiagnostics) {
                            int off = tokens.count() == 0 ? 0 : tokens.startAt(0);
                            diagnostics.add(Diagnostic.error(off, 1,
                                "empty input", "start of Input", "<end-of-input>"));
                        }
                    }
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
            boolean parsedOk = parseInput(root);
            if (!parsedOk) {
                // Roll back any partial CST built by the failed start-rule call.
                cst.truncate(beforeNodes);
                emitRecoveryError(root, beforePos);
            } else if (pos == beforePos) {
                // Start rule succeeded without consuming any token. Force
                // progress by skipping one token under an Error node, else we
                // loop forever on the same position.
                emitForcedAdvanceError(root, beforePos);
            } else {
                // The start rule succeeded but did not consume the whole stream.
                // Looping would silently re-parse the remainder as a SECOND
                // start-rule application, accepting a file that is really two
                // concatenated documents. For Java that hides real errors: in
                // 'import a.B;; import c.D;' the stray ';' is a type declaration
                // (JLS 7.3), so the following import is illegal — yet both halves
                // parse as valid compilation units on their own.
                //
                // Record the trailing-input diagnostic here, then keep looping so
                // the remainder still lands in the CST: callers that reconstruct
                // source from the tree depend on full coverage.
                int trailTok = pos;
                while (trailTok < tokens.count() && tokens.isTrivia(trailTok)) trailTok++;
                if (trailTok < tokens.count() && diagnostics.size() < maxDiagnostics) {
                    int tStart = tokens.startAt(trailTok);
                    int tEnd = tokens.endAt(trailTok);
                    int tLen = tEnd - tStart;
                    if (tLen < 1) tLen = 1;
                    diagnostics.add(Diagnostic.error(tStart, tLen,
                        "trailing input not consumed", "end of input",
                        tokens.input().substring(tStart, tEnd)));
                }
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

    private boolean parseInput(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_Input_KIND, firstTok, parent);
        if (!parseStatement(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__SEMI) { fail("';'", RULE_Input_KIND); break; }
                advance();
                if (!parseStatement(self)) { break; }
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
                if (peek() != KIND_INLINE__SEMI) { fail("';'", RULE_Input_KIND); break; }
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

    private boolean parseStatement(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_Statement_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseDdlStatement(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseDmlStatement(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parsePassthroughStatement(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_EMPTYSTATEMENT) { fail("EmptyStatement", RULE_Statement_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_Statement_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseDdlStatement(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_DdlStatement_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseCreateStatement(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseAlterStatement(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseDropStatement(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseCommentStatement(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseGrantStatement(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseRevokeStatement(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_DdlStatement_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseCreateStatement(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_CreateStatement_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_CREATE_CI) { fail("CreateKW", RULE_CreateStatement_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                    if (!parseCreateTableStmt(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseCreateIndexStmt(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseCreateSequenceStmt(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseCreateTypeStmt(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_CREATESCHEMASTMT) { fail("CreateSchemaStmt", RULE_CreateStatement_KIND); break; }
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
                    if (!parseCreateViewStmt(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseCreateMatViewStmt(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseCreateExtensionStmt(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseCreateFunctionPassthrough(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseCreateTriggerPassthrough(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_CreateStatement_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseAlterStatement(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_AlterStatement_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_ALTER_CI) { fail("AlterKW", RULE_AlterStatement_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                    if (!parseAlterTableStmt(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseAlterIndexStmt(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseAlterSequenceStmt(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseAlterTypeStmt(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_ALTERSCHEMASTMT) { fail("AlterSchemaStmt", RULE_AlterStatement_KIND); break; }
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
                    if (!parseAlterViewStmt(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseAlterDefaultPrivilegesPassthrough(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_AlterStatement_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseDropStatement(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_DropStatement_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_DROP_CI) { fail("DropKW", RULE_DropStatement_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                    if (!parseDropTableStmt(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseDropIndexStmt(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseDropSequenceStmt(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseDropTypeStmt(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseDropSchemaStmt(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseDropViewStmt(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseDropMatViewStmt(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseDropExtensionStmt(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseDropFunctionPassthrough(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseDropTriggerPassthrough(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_DropStatement_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseDmlStatement(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_DmlStatement_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseSelectStmt(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseInsertStmt(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseUpdateStmt(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseDeleteStmt(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_DmlStatement_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseCreateTableStmt(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_CreateTableStmt_KIND, firstTok, parent);
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                { int __k = peek(); if (__k != KIND_INLINE_TEMP_CI) { fail("TempKW", RULE_CreateTableStmt_KIND); break; } }
                advance();
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
                { int __k = peek(); if (__k != KIND_INLINE_UNLOGGED_CI) { fail("UnloggedKW", RULE_CreateTableStmt_KIND); break; } }
                advance();
                optOk_opt_1 = true;
            } while (false);
            if (!optOk_opt_1) {
                pos = savedPos_opt_1;
                cst.truncate(savedNodes_opt_1);
            }
        }
        { int __k = peek(); if (__k != KIND_INLINE_TABLE_CI) { fail("TableKW", RULE_CreateTableStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_2
        {
            int savedPos_opt_2 = pos;
            int savedNodes_opt_2 = cst.currentNodeCount();
            boolean optOk_opt_2 = false;
            do {
                if (peek() != KIND_IFNOTEXISTS) { fail("IfNotExists", RULE_CreateTableStmt_KIND); break; }
                advance();
                optOk_opt_2 = true;
            } while (false);
            if (!optOk_opt_2) {
                pos = savedPos_opt_2;
                cst.truncate(savedNodes_opt_2);
            }
        }
        if (!parseQualifiedName(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_CreateTableStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseTableElementList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_CreateTableStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // optional: opt_3
        {
            int savedPos_opt_3 = pos;
            int savedNodes_opt_3 = cst.currentNodeCount();
            boolean optOk_opt_3 = false;
            do {
                if (!parseTableOptions(self)) { break; }
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

    private boolean parseTableElementList(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_TableElementList_KIND, firstTok, parent);
        if (!parseTableElement(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_TableElementList_KIND); break; }
                advance();
                if (!parseTableElement(self)) { break; }
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

    private boolean parseTableElement(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_TableElement_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseTableConstraint(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseColumnDef(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_TableElement_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseColumnDef(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ColumnDef_KIND, firstTok, parent);
        if (peek() != KIND_COLID) { fail("ColId", RULE_ColumnDef_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseDataType(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (!parseColConstraint(self)) { break; }
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

    private boolean parseColConstraint(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ColConstraint_KIND, firstTok, parent);
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_CONSTRAINTNAME) { fail("ConstraintName", RULE_ColConstraint_KIND); break; }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (!parseColConstraintElem(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseColConstraintElem(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ColConstraintElem_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_NOTNULLCONSTRAINT) { fail("NotNullConstraint", RULE_ColConstraintElem_KIND); break; }
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
                    if (peek() != KIND_NULLCONSTRAINT) { fail("NullConstraint", RULE_ColConstraintElem_KIND); break; }
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
                    if (peek() != KIND_UNIQUECOLCONSTRAINT) { fail("UniqueColConstraint", RULE_ColConstraintElem_KIND); break; }
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
                    if (peek() != KIND_PRIMARYKEYCOLCONSTRAINT) { fail("PrimaryKeyColConstraint", RULE_ColConstraintElem_KIND); break; }
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
                    if (!parseCheckColConstraint(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseDefaultClause(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseReferencesClause(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseGeneratedClause(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseIdentityClause(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseCollateClause(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_ColConstraintElem_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseCheckColConstraint(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_CheckColConstraint_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_CHECK_CI) { fail("CheckKW", RULE_CheckColConstraint_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_CheckColConstraint_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_CheckColConstraint_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseDefaultClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_DefaultClause_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_DEFAULT_CI) { fail("DefaultKW", RULE_DefaultClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parseExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseCollateClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_CollateClause_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_COLLATE_CI) { fail("CollateKW", RULE_CollateClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parseQualifiedName(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseReferencesClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ReferencesClause_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_REFERENCES_CI) { fail("ReferencesKW", RULE_ReferencesClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parseQualifiedName(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_ReferencesClause_KIND); break; }
                advance();
                if (!parseColumnList(self)) { break; }
                if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_ReferencesClause_KIND); break; }
                advance();
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
                if (peek() != KIND_FKACTIONS) { fail("FkActions", RULE_ReferencesClause_KIND); break; }
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

    private boolean parseGeneratedClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_GeneratedClause_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_GENERATED_CI) { fail("GeneratedKW", RULE_GeneratedClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_INLINE_ALWAYS_CI) { fail("AlwaysKW", RULE_GeneratedClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_INLINE_AS_CI) { fail("AsKW", RULE_GeneratedClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                    if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_GeneratedClause_KIND); break; }
                    advance();
                    if (!parseExpr(self)) { break; }
                    if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_GeneratedClause_KIND); break; }
                    advance();
                    { int __k = peek(); if (__k != KIND_INLINE_STORED_CI) { fail("StoredKW", RULE_GeneratedClause_KIND); break; } }
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
                    if (!parseIdentitySpec(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_GeneratedClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseIdentityClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_IdentityClause_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_GENERATED_CI) { fail("GeneratedKW", RULE_IdentityClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_ALWAYS_CI) { fail("AlwaysKW", RULE_IdentityClause_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_BY_CI) { fail("ByKW", RULE_IdentityClause_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_INLINE_DEFAULT_CI) { fail("DefaultKW", RULE_IdentityClause_KIND); break; } }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_IdentityClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        { int __k = peek(); if (__k != KIND_INLINE_AS_CI) { fail("AsKW", RULE_IdentityClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_INLINE_IDENTITY_CI) { fail("IdentityKW", RULE_IdentityClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                if (!parseSequenceOptions(self)) { break; }
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

    private boolean parseIdentitySpec(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_IdentitySpec_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_IDENTITY_CI) { fail("IdentityKW", RULE_IdentitySpec_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parseSequenceOptions(self)) { break; }
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

    private boolean parseTableConstraint(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_TableConstraint_KIND, firstTok, parent);
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_CONSTRAINTNAME) { fail("ConstraintName", RULE_TableConstraint_KIND); break; }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (!parseTableConstraintElem(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseTableConstraintElem(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_TableConstraintElem_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parsePrimaryKeyTblConstraint(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseUniqueTblConstraint(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseCheckTblConstraint(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseForeignKeyTblConstraint(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseExcludeTblConstraint(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_TableConstraintElem_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parsePrimaryKeyTblConstraint(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_PrimaryKeyTblConstraint_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_PRIMARY_CI) { fail("PrimaryKW", RULE_PrimaryKeyTblConstraint_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_INLINE_KEY_CI) { fail("KeyKW", RULE_PrimaryKeyTblConstraint_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_PrimaryKeyTblConstraint_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseColumnList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_PrimaryKeyTblConstraint_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parseIndexOptions(self)) { break; }
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

    private boolean parseUniqueTblConstraint(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_UniqueTblConstraint_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_UNIQUE_CI) { fail("UniqueKW", RULE_UniqueTblConstraint_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_NULLSDISTINCT) { fail("NullsDistinct", RULE_UniqueTblConstraint_KIND); break; }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_UniqueTblConstraint_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseColumnList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_UniqueTblConstraint_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                if (!parseIndexOptions(self)) { break; }
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

    private boolean parseCheckTblConstraint(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_CheckTblConstraint_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_CHECK_CI) { fail("CheckKW", RULE_CheckTblConstraint_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_CheckTblConstraint_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_CheckTblConstraint_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_NOINHERITCLAUSE) { fail("NoInheritClause", RULE_CheckTblConstraint_KIND); break; }
                advance();
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

    private boolean parseForeignKeyTblConstraint(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ForeignKeyTblConstraint_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_FOREIGN_CI) { fail("ForeignKW", RULE_ForeignKeyTblConstraint_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_INLINE_KEY_CI) { fail("KeyKW", RULE_ForeignKeyTblConstraint_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_ForeignKeyTblConstraint_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseColumnList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_ForeignKeyTblConstraint_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        { int __k = peek(); if (__k != KIND_INLINE_REFERENCES_CI) { fail("ReferencesKW", RULE_ForeignKeyTblConstraint_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseQualifiedName(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_ForeignKeyTblConstraint_KIND); break; }
                advance();
                if (!parseColumnList(self)) { break; }
                if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_ForeignKeyTblConstraint_KIND); break; }
                advance();
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
                if (peek() != KIND_FKACTIONS) { fail("FkActions", RULE_ForeignKeyTblConstraint_KIND); break; }
                advance();
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
                if (peek() != KIND_FKDEFERRABLE) { fail("FkDeferrable", RULE_ForeignKeyTblConstraint_KIND); break; }
                advance();
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

    private boolean parseExcludeTblConstraint(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ExcludeTblConstraint_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_EXCLUDE_CI) { fail("ExcludeKW", RULE_ExcludeTblConstraint_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_USINGCLAUSE) { fail("UsingClause", RULE_ExcludeTblConstraint_KIND); break; }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_ExcludeTblConstraint_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseExcludeElementList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_ExcludeTblConstraint_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                if (!parseWhereClause(self)) { break; }
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

    private boolean parseExcludeElementList(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ExcludeElementList_KIND, firstTok, parent);
        if (!parseExcludeElement(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_ExcludeElementList_KIND); break; }
                advance();
                if (!parseExcludeElement(self)) { break; }
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

    private boolean parseExcludeElement(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ExcludeElement_KIND, firstTok, parent);
        if (!parseIndexElem(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        { int __k = peek(); if (__k != KIND_INLINE_WITH_CI) { fail("WithKW", RULE_ExcludeElement_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseOperator(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseIndexOptions(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_IndexOptions_KIND, firstTok, parent);
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parseIncludeClause(self)) { break; }
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
                if (!parseWithStorageParams(self)) { break; }
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
                if (peek() != KIND_USINGINDEXTBLSPACE) { fail("UsingIndexTblspace", RULE_IndexOptions_KIND); break; }
                advance();
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

    private boolean parseIncludeClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_IncludeClause_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_INCLUDE_CI) { fail("IncludeKW", RULE_IncludeClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_IncludeClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseColumnList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_IncludeClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseWithStorageParams(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_WithStorageParams_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_WITH_CI) { fail("WithKW", RULE_WithStorageParams_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_WithStorageParams_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseStorageParamList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_WithStorageParams_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseStorageParamList(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_StorageParamList_KIND, firstTok, parent);
        if (!parseStorageParam(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_StorageParamList_KIND); break; }
                advance();
                if (!parseStorageParam(self)) { break; }
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

    private boolean parseStorageParam(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_StorageParam_KIND, firstTok, parent);
        if (peek() != KIND_COLID) { fail("ColId", RULE_StorageParam_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_INLINE__EQ) { fail("'='", RULE_StorageParam_KIND); break; }
                advance();
                if (!parseSignedNumericLiteral(self)) { break; }
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

    private boolean parseTableOptions(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_TableOptions_KIND, firstTok, parent);
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parsePartitionByClause(self)) { break; }
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
                if (!parseInheritsClause(self)) { break; }
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
                if (peek() != KIND_TABLESPACECLAUSE) { fail("TablespaceClause", RULE_TableOptions_KIND); break; }
                advance();
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

    private boolean parsePartitionByClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_PartitionByClause_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_PARTITION_CI) { fail("PartitionKW", RULE_PartitionByClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_INLINE_BY_CI) { fail("ByKW", RULE_PartitionByClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (peek() != KIND_PARTITIONSTRATEGY) { fail("PartitionStrategy", RULE_PartitionByClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_PartitionByClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parsePartitionKeyList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_PartitionByClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parsePartitionKeyList(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_PartitionKeyList_KIND, firstTok, parent);
        if (!parsePartitionKey(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_PartitionKeyList_KIND); break; }
                advance();
                if (!parsePartitionKey(self)) { break; }
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

    private boolean parsePartitionKey(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_PartitionKey_KIND, firstTok, parent);
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
                    if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_PartitionKey_KIND); break; }
                    advance();
                    if (!parseExpr(self)) { break; }
                    if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_PartitionKey_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_PartitionKey_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseInheritsClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_InheritsClause_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_INHERITS_CI) { fail("InheritsKW", RULE_InheritsClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_InheritsClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseQualifiedNameList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_InheritsClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseColumnList(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ColumnList_KIND, firstTok, parent);
        if (peek() != KIND_COLID) { fail("ColId", RULE_ColumnList_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_ColumnList_KIND); break; }
                advance();
                if (peek() != KIND_COLID) { fail("ColId", RULE_ColumnList_KIND); break; }
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

    private boolean parseQualifiedNameList(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_QualifiedNameList_KIND, firstTok, parent);
        if (!parseQualifiedName(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_QualifiedNameList_KIND); break; }
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
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseAlterTableStmt(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_AlterTableStmt_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_TABLE_CI) { fail("TableKW", RULE_AlterTableStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_IFEXISTS) { fail("IfExists", RULE_AlterTableStmt_KIND); break; }
                advance();
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
                { int __k = peek(); if (__k != KIND_INLINE_ONLY_CI) { fail("OnlyKW", RULE_AlterTableStmt_KIND); break; } }
                advance();
                optOk_opt_1 = true;
            } while (false);
            if (!optOk_opt_1) {
                pos = savedPos_opt_1;
                cst.truncate(savedNodes_opt_1);
            }
        }
        if (!parseQualifiedName(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // choice: alt_2
        {
            int savedPos_alt_2 = pos;
            int savedNodes_alt_2 = cst.currentNodeCount();
            boolean matched_alt_2 = false;
            boolean cutHit_alt_2 = false;
            if (!matched_alt_2 && !cutHit_alt_2) {
                do {
                    if (!parseAlterTableActions(self)) { break; }
                    matched_alt_2 = true;
                } while (false);
                if (!matched_alt_2) {
                    pos = savedPos_alt_2;
                    cst.truncate(savedNodes_alt_2);
                }
            }
            if (!matched_alt_2 && !cutHit_alt_2) {
                do {
                    if (peek() != KIND_RENAMEACTION) { fail("RenameAction", RULE_AlterTableStmt_KIND); break; }
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
                    if (peek() != KIND_SETSCHEMAACTION) { fail("SetSchemaAction", RULE_AlterTableStmt_KIND); break; }
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
                    if (!parseAttachPartition(self)) { break; }
                    matched_alt_2 = true;
                } while (false);
                if (!matched_alt_2) {
                    pos = savedPos_alt_2;
                    cst.truncate(savedNodes_alt_2);
                }
            }
            if (!matched_alt_2 && !cutHit_alt_2) {
                do {
                    if (!parseDetachPartition(self)) { break; }
                    matched_alt_2 = true;
                } while (false);
                if (!matched_alt_2) {
                    pos = savedPos_alt_2;
                    cst.truncate(savedNodes_alt_2);
                }
            }
            if (!matched_alt_2) { fail("<choice>", RULE_AlterTableStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseAlterTableActions(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_AlterTableActions_KIND, firstTok, parent);
        if (!parseAlterTableAction(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_AlterTableActions_KIND); break; }
                advance();
                if (!parseAlterTableAction(self)) { break; }
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

    private boolean parseAlterTableAction(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_AlterTableAction_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseAddColumnAction(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_DROPCOLUMNACTION) { fail("DropColumnAction", RULE_AlterTableAction_KIND); break; }
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
                    if (!parseAlterColumnAction(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseAddConstraintAction(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_DROPCONSTRAINTACTION) { fail("DropConstraintAction", RULE_AlterTableAction_KIND); break; }
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
                    if (peek() != KIND_VALIDATECONSTRAINTACTION) { fail("ValidateConstraintAction", RULE_AlterTableAction_KIND); break; }
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
                    if (peek() != KIND_RENAMECONSTRAINTACTION) { fail("RenameConstraintAction", RULE_AlterTableAction_KIND); break; }
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
                    if (peek() != KIND_ALTEROWNERACTION) { fail("AlterOwnerAction", RULE_AlterTableAction_KIND); break; }
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
                    if (peek() != KIND_SETTABLESPACEACTION) { fail("SetTablespaceAction", RULE_AlterTableAction_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_AlterTableAction_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseAddColumnAction(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_AddColumnAction_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_ADD_CI) { fail("AddKW", RULE_AddColumnAction_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                { int __k = peek(); if (__k != KIND_INLINE_COLUMN_CI) { fail("ColumnKW", RULE_AddColumnAction_KIND); break; } }
                advance();
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
                if (peek() != KIND_IFNOTEXISTS) { fail("IfNotExists", RULE_AddColumnAction_KIND); break; }
                advance();
                optOk_opt_1 = true;
            } while (false);
            if (!optOk_opt_1) {
                pos = savedPos_opt_1;
                cst.truncate(savedNodes_opt_1);
            }
        }
        if (!parseColumnDef(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseAlterColumnAction(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_AlterColumnAction_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_ALTER_CI) { fail("AlterKW", RULE_AlterColumnAction_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                { int __k = peek(); if (__k != KIND_INLINE_COLUMN_CI) { fail("ColumnKW", RULE_AlterColumnAction_KIND); break; } }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (peek() != KIND_COLID) { fail("ColId", RULE_AlterColumnAction_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseAlterColumnCmd(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseAlterColumnCmd(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_AlterColumnCmd_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseSetDataTypeCmd(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseSetDefaultCmd(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_DROPDEFAULTCMD) { fail("DropDefaultCmd", RULE_AlterColumnCmd_KIND); break; }
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
                    if (peek() != KIND_SETNOTNULLCMD) { fail("SetNotNullCmd", RULE_AlterColumnCmd_KIND); break; }
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
                    if (peek() != KIND_DROPNOTNULLCMD) { fail("DropNotNullCmd", RULE_AlterColumnCmd_KIND); break; }
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
                    if (!parseSetStatisticsCmd(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_SETSTORAGECMD) { fail("SetStorageCmd", RULE_AlterColumnCmd_KIND); break; }
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
                    if (!parseAddIdentityCmd(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_DROPIDENTITYCMD) { fail("DropIdentityCmd", RULE_AlterColumnCmd_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_AlterColumnCmd_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseSetDataTypeCmd(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_SetDataTypeCmd_KIND, firstTok, parent);
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                { int __k = peek(); if (__k != KIND_INLINE_SET_CI) { fail("SetKW", RULE_SetDataTypeCmd_KIND); break; } }
                advance();
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
                { int __k = peek(); if (__k != KIND_INLINE_DATA_CI) { fail("DataKW", RULE_SetDataTypeCmd_KIND); break; } }
                advance();
                optOk_opt_1 = true;
            } while (false);
            if (!optOk_opt_1) {
                pos = savedPos_opt_1;
                cst.truncate(savedNodes_opt_1);
            }
        }
        { int __k = peek(); if (__k != KIND_INLINE_TYPE_CI) { fail("TypeKW", RULE_SetDataTypeCmd_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parseDataType(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_2
        {
            int savedPos_opt_2 = pos;
            int savedNodes_opt_2 = cst.currentNodeCount();
            boolean optOk_opt_2 = false;
            do {
                { int __k = peek(); if (__k != KIND_INLINE_USING_CI) { fail("UsingKW", RULE_SetDataTypeCmd_KIND); break; } }
                advance();
                if (!parseExpr(self)) { break; }
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

    private boolean parseSetDefaultCmd(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_SetDefaultCmd_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_SET_CI) { fail("SetKW", RULE_SetDefaultCmd_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_INLINE_DEFAULT_CI) { fail("DefaultKW", RULE_SetDefaultCmd_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parseExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseSetStatisticsCmd(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_SetStatisticsCmd_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_SET_CI) { fail("SetKW", RULE_SetStatisticsCmd_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_INLINE_STATISTICS_CI) { fail("StatisticsKW", RULE_SetStatisticsCmd_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseSignedNumericLiteral(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseAddIdentityCmd(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_AddIdentityCmd_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_ADD_CI) { fail("AddKW", RULE_AddIdentityCmd_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_INLINE_GENERATED_CI) { fail("GeneratedKW", RULE_AddIdentityCmd_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_ALWAYS_CI) { fail("AlwaysKW", RULE_AddIdentityCmd_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_BY_CI) { fail("ByKW", RULE_AddIdentityCmd_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_INLINE_DEFAULT_CI) { fail("DefaultKW", RULE_AddIdentityCmd_KIND); break; } }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_AddIdentityCmd_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        { int __k = peek(); if (__k != KIND_INLINE_AS_CI) { fail("AsKW", RULE_AddIdentityCmd_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_INLINE_IDENTITY_CI) { fail("IdentityKW", RULE_AddIdentityCmd_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                if (!parseSequenceOptions(self)) { break; }
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

    private boolean parseAddConstraintAction(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_AddConstraintAction_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_ADD_CI) { fail("AddKW", RULE_AddConstraintAction_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parseTableConstraint(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_NOTVALIDCLAUSE) { fail("NotValidClause", RULE_AddConstraintAction_KIND); break; }
                advance();
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

    private boolean parseAttachPartition(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_AttachPartition_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_ATTACH_CI) { fail("AttachKW", RULE_AttachPartition_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_INLINE_PARTITION_CI) { fail("PartitionKW", RULE_AttachPartition_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parseQualifiedName(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (!parseForValuesClause(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseDetachPartition(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_DetachPartition_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_DETACH_CI) { fail("DetachKW", RULE_DetachPartition_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_INLINE_PARTITION_CI) { fail("PartitionKW", RULE_DetachPartition_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parseQualifiedName(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
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
                            { int __k = peek(); if (__k != KIND_INLINE_CONCURRENTLY_CI) { fail("ConcurrentlyKW", RULE_DetachPartition_KIND); break; } }
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
                            { int __k = peek(); if (__k != KIND_INLINE_FINALIZE_CI) { fail("FinalizeKW", RULE_DetachPartition_KIND); break; } }
                            advance();
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1) { fail("<choice>", RULE_DetachPartition_KIND); break; }
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

    private boolean parseForValuesClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ForValuesClause_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_FOR_CI) { fail("ForKW", RULE_ForValuesClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_INLINE_VALUES_CI) { fail("ValuesKW", RULE_ForValuesClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_IN_CI) { fail("InKW", RULE_ForValuesClause_KIND); break; } }
                    advance();
                    if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_ForValuesClause_KIND); break; }
                    advance();
                    if (!parseExprList(self)) { break; }
                    if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_ForValuesClause_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_INLINE_FROM_CI) { fail("FromKW", RULE_ForValuesClause_KIND); break; } }
                    advance();
                    if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_ForValuesClause_KIND); break; }
                    advance();
                    if (!parseExprList(self)) { break; }
                    if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_ForValuesClause_KIND); break; }
                    advance();
                    { int __k = peek(); if (__k != KIND_INLINE_TO_CI) { fail("ToKW", RULE_ForValuesClause_KIND); break; } }
                    advance();
                    if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_ForValuesClause_KIND); break; }
                    advance();
                    if (!parseExprList(self)) { break; }
                    if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_ForValuesClause_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_INLINE_WITH_CI) { fail("WithKW", RULE_ForValuesClause_KIND); break; } }
                    advance();
                    if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_ForValuesClause_KIND); break; }
                    advance();
                    if (!parseExprList(self)) { break; }
                    if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_ForValuesClause_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_INLINE_DEFAULT_CI) { fail("DefaultKW", RULE_ForValuesClause_KIND); break; } }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_ForValuesClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseDropTableStmt(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_DropTableStmt_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_TABLE_CI) { fail("TableKW", RULE_DropTableStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_IFEXISTS) { fail("IfExists", RULE_DropTableStmt_KIND); break; }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (!parseQualifiedNameList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                if (peek() != KIND_DROPBEHAVIOR) { fail("DropBehavior", RULE_DropTableStmt_KIND); break; }
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

    private boolean parseCreateIndexStmt(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_CreateIndexStmt_KIND, firstTok, parent);
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                { int __k = peek(); if (__k != KIND_INLINE_UNIQUE_CI) { fail("UniqueKW", RULE_CreateIndexStmt_KIND); break; } }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        { int __k = peek(); if (__k != KIND_INLINE_INDEX_CI) { fail("IndexKW", RULE_CreateIndexStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                { int __k = peek(); if (__k != KIND_INLINE_CONCURRENTLY_CI) { fail("ConcurrentlyKW", RULE_CreateIndexStmt_KIND); break; } }
                advance();
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
                if (peek() != KIND_IFNOTEXISTS) { fail("IfNotExists", RULE_CreateIndexStmt_KIND); break; }
                advance();
                optOk_opt_2 = true;
            } while (false);
            if (!optOk_opt_2) {
                pos = savedPos_opt_2;
                cst.truncate(savedNodes_opt_2);
            }
        }
        // choice: alt_3
        {
            int savedPos_alt_3 = pos;
            int savedNodes_alt_3 = cst.currentNodeCount();
            boolean matched_alt_3 = false;
            boolean cutHit_alt_3 = false;
            if (!matched_alt_3 && !cutHit_alt_3) {
                do {
                    if (peek() != KIND_COLID) { fail("ColId", RULE_CreateIndexStmt_KIND); break; }
                    advance();
                    { int __k = peek(); if (__k != KIND_INLINE_ON_CI) { fail("OnKW", RULE_CreateIndexStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_ON_CI) { fail("OnKW", RULE_CreateIndexStmt_KIND); break; } }
                    advance();
                    matched_alt_3 = true;
                } while (false);
                if (!matched_alt_3) {
                    pos = savedPos_alt_3;
                    cst.truncate(savedNodes_alt_3);
                }
            }
            if (!matched_alt_3) { fail("<choice>", RULE_CreateIndexStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        // optional: opt_4
        {
            int savedPos_opt_4 = pos;
            int savedNodes_opt_4 = cst.currentNodeCount();
            boolean optOk_opt_4 = false;
            do {
                { int __k = peek(); if (__k != KIND_INLINE_ONLY_CI) { fail("OnlyKW", RULE_CreateIndexStmt_KIND); break; } }
                advance();
                optOk_opt_4 = true;
            } while (false);
            if (!optOk_opt_4) {
                pos = savedPos_opt_4;
                cst.truncate(savedNodes_opt_4);
            }
        }
        if (!parseQualifiedName(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_5
        {
            int savedPos_opt_5 = pos;
            int savedNodes_opt_5 = cst.currentNodeCount();
            boolean optOk_opt_5 = false;
            do {
                if (peek() != KIND_USINGCLAUSE) { fail("UsingClause", RULE_CreateIndexStmt_KIND); break; }
                advance();
                optOk_opt_5 = true;
            } while (false);
            if (!optOk_opt_5) {
                pos = savedPos_opt_5;
                cst.truncate(savedNodes_opt_5);
            }
        }
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_CreateIndexStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseIndexElemList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_CreateIndexStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // optional: opt_6
        {
            int savedPos_opt_6 = pos;
            int savedNodes_opt_6 = cst.currentNodeCount();
            boolean optOk_opt_6 = false;
            do {
                if (!parseIncludeClause(self)) { break; }
                optOk_opt_6 = true;
            } while (false);
            if (!optOk_opt_6) {
                pos = savedPos_opt_6;
                cst.truncate(savedNodes_opt_6);
            }
        }
        // optional: opt_7
        {
            int savedPos_opt_7 = pos;
            int savedNodes_opt_7 = cst.currentNodeCount();
            boolean optOk_opt_7 = false;
            do {
                if (peek() != KIND_NULLSDISTINCT) { fail("NullsDistinct", RULE_CreateIndexStmt_KIND); break; }
                advance();
                optOk_opt_7 = true;
            } while (false);
            if (!optOk_opt_7) {
                pos = savedPos_opt_7;
                cst.truncate(savedNodes_opt_7);
            }
        }
        // optional: opt_8
        {
            int savedPos_opt_8 = pos;
            int savedNodes_opt_8 = cst.currentNodeCount();
            boolean optOk_opt_8 = false;
            do {
                if (!parseWithStorageParams(self)) { break; }
                optOk_opt_8 = true;
            } while (false);
            if (!optOk_opt_8) {
                pos = savedPos_opt_8;
                cst.truncate(savedNodes_opt_8);
            }
        }
        // optional: opt_9
        {
            int savedPos_opt_9 = pos;
            int savedNodes_opt_9 = cst.currentNodeCount();
            boolean optOk_opt_9 = false;
            do {
                if (peek() != KIND_TABLESPACECLAUSE) { fail("TablespaceClause", RULE_CreateIndexStmt_KIND); break; }
                advance();
                optOk_opt_9 = true;
            } while (false);
            if (!optOk_opt_9) {
                pos = savedPos_opt_9;
                cst.truncate(savedNodes_opt_9);
            }
        }
        // optional: opt_10
        {
            int savedPos_opt_10 = pos;
            int savedNodes_opt_10 = cst.currentNodeCount();
            boolean optOk_opt_10 = false;
            do {
                if (!parseWhereClause(self)) { break; }
                optOk_opt_10 = true;
            } while (false);
            if (!optOk_opt_10) {
                pos = savedPos_opt_10;
                cst.truncate(savedNodes_opt_10);
            }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseIndexElemList(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_IndexElemList_KIND, firstTok, parent);
        if (!parseIndexElem(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_IndexElemList_KIND); break; }
                advance();
                if (!parseIndexElem(self)) { break; }
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

    private boolean parseIndexElem(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_IndexElem_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseFuncCall(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_COLID) { fail("ColId", RULE_IndexElem_KIND); break; }
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
                    if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_IndexElem_KIND); break; }
                    advance();
                    if (!parseExpr(self)) { break; }
                    if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_IndexElem_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_IndexElem_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                if (!parseOpClass(self)) { break; }
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
                if (peek() != KIND_ORDERSPEC) { fail("OrderSpec", RULE_IndexElem_KIND); break; }
                advance();
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
                if (peek() != KIND_NULLSORDER) { fail("NullsOrder", RULE_IndexElem_KIND); break; }
                advance();
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

    private boolean parseOpClass(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_OpClass_KIND, firstTok, parent);
        if (peek() != KIND_COLID) { fail("ColId", RULE_OpClass_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_OpClass_KIND); break; }
                advance();
                if (!parseStorageParamList(self)) { break; }
                if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_OpClass_KIND); break; }
                advance();
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

    private boolean parseAlterIndexStmt(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_AlterIndexStmt_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_INDEX_CI) { fail("IndexKW", RULE_AlterIndexStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_IFEXISTS) { fail("IfExists", RULE_AlterIndexStmt_KIND); break; }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (!parseQualifiedName(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // choice: alt_1
        {
            int savedPos_alt_1 = pos;
            int savedNodes_alt_1 = cst.currentNodeCount();
            boolean matched_alt_1 = false;
            boolean cutHit_alt_1 = false;
            if (!matched_alt_1 && !cutHit_alt_1) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_RENAME_CI) { fail("RenameKW", RULE_AlterIndexStmt_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_INLINE_TO_CI) { fail("ToKW", RULE_AlterIndexStmt_KIND); break; } }
                    advance();
                    if (peek() != KIND_COLID) { fail("ColId", RULE_AlterIndexStmt_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_INLINE_SET_CI) { fail("SetKW", RULE_AlterIndexStmt_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_INLINE_TABLESPACE_CI) { fail("TablespaceKW", RULE_AlterIndexStmt_KIND); break; } }
                    advance();
                    if (peek() != KIND_COLID) { fail("ColId", RULE_AlterIndexStmt_KIND); break; }
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
                    if (!parseAlterColumnAction(self)) { break; }
                    matched_alt_1 = true;
                } while (false);
                if (!matched_alt_1) {
                    pos = savedPos_alt_1;
                    cst.truncate(savedNodes_alt_1);
                }
            }
            if (!matched_alt_1) { fail("<choice>", RULE_AlterIndexStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseDropIndexStmt(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_DropIndexStmt_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_INDEX_CI) { fail("IndexKW", RULE_DropIndexStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                { int __k = peek(); if (__k != KIND_INLINE_CONCURRENTLY_CI) { fail("ConcurrentlyKW", RULE_DropIndexStmt_KIND); break; } }
                advance();
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
                if (peek() != KIND_IFEXISTS) { fail("IfExists", RULE_DropIndexStmt_KIND); break; }
                advance();
                optOk_opt_1 = true;
            } while (false);
            if (!optOk_opt_1) {
                pos = savedPos_opt_1;
                cst.truncate(savedNodes_opt_1);
            }
        }
        if (!parseQualifiedNameList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_2
        {
            int savedPos_opt_2 = pos;
            int savedNodes_opt_2 = cst.currentNodeCount();
            boolean optOk_opt_2 = false;
            do {
                if (peek() != KIND_DROPBEHAVIOR) { fail("DropBehavior", RULE_DropIndexStmt_KIND); break; }
                advance();
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

    private boolean parseCreateSequenceStmt(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_CreateSequenceStmt_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_SEQUENCE_CI) { fail("SequenceKW", RULE_CreateSequenceStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_IFNOTEXISTS) { fail("IfNotExists", RULE_CreateSequenceStmt_KIND); break; }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (!parseQualifiedName(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                if (!parseSequenceOptions(self)) { break; }
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

    private boolean parseAlterSequenceStmt(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_AlterSequenceStmt_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_SEQUENCE_CI) { fail("SequenceKW", RULE_AlterSequenceStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_IFEXISTS) { fail("IfExists", RULE_AlterSequenceStmt_KIND); break; }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (!parseQualifiedName(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (!parseSequenceOptions(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseDropSequenceStmt(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_DropSequenceStmt_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_SEQUENCE_CI) { fail("SequenceKW", RULE_DropSequenceStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_IFEXISTS) { fail("IfExists", RULE_DropSequenceStmt_KIND); break; }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (!parseQualifiedNameList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                if (peek() != KIND_DROPBEHAVIOR) { fail("DropBehavior", RULE_DropSequenceStmt_KIND); break; }
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

    private boolean parseSequenceOptions(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_SequenceOptions_KIND, firstTok, parent);
        if (!parseSequenceOption(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (!parseSequenceOption(self)) { break; }
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

    private boolean parseSequenceOption(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_SequenceOption_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_AS_CI) { fail("AsKW", RULE_SequenceOption_KIND); break; } }
                    advance();
                    if (!parseDataType(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_INCREMENT_CI) { fail("IncrementKW", RULE_SequenceOption_KIND); break; } }
                    advance();
                    // optional: opt_1
                    {
                        int savedPos_opt_1 = pos;
                        int savedNodes_opt_1 = cst.currentNodeCount();
                        boolean optOk_opt_1 = false;
                        do {
                            { int __k = peek(); if (__k != KIND_INLINE_BY_CI) { fail("ByKW", RULE_SequenceOption_KIND); break; } }
                            advance();
                            optOk_opt_1 = true;
                        } while (false);
                        if (!optOk_opt_1) {
                            pos = savedPos_opt_1;
                            cst.truncate(savedNodes_opt_1);
                        }
                    }
                    if (!parseSignedNumericLiteral(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    // choice: alt_2
                    {
                        int savedPos_alt_2 = pos;
                        int savedNodes_alt_2 = cst.currentNodeCount();
                        boolean matched_alt_2 = false;
                        boolean cutHit_alt_2 = false;
                        if (!matched_alt_2 && !cutHit_alt_2) {
                            do {
                                { int __k = peek(); if (__k != KIND_INLINE_MINVALUE_CI) { fail("MinvalueKW", RULE_SequenceOption_KIND); break; } }
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
                                { int __k = peek(); if (__k != KIND_INLINE_MAXVALUE_CI) { fail("MaxvalueKW", RULE_SequenceOption_KIND); break; } }
                                advance();
                                matched_alt_2 = true;
                            } while (false);
                            if (!matched_alt_2) {
                                pos = savedPos_alt_2;
                                cst.truncate(savedNodes_alt_2);
                            }
                        }
                        if (!matched_alt_2) { fail("<choice>", RULE_SequenceOption_KIND); break; }
                    }
                    if (!parseSignedNumericLiteral(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_NO_CI) { fail("NoKW", RULE_SequenceOption_KIND); break; } }
                    advance();
                    // choice: alt_3
                    {
                        int savedPos_alt_3 = pos;
                        int savedNodes_alt_3 = cst.currentNodeCount();
                        boolean matched_alt_3 = false;
                        boolean cutHit_alt_3 = false;
                        if (!matched_alt_3 && !cutHit_alt_3) {
                            do {
                                { int __k = peek(); if (__k != KIND_INLINE_MINVALUE_CI) { fail("MinvalueKW", RULE_SequenceOption_KIND); break; } }
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
                                { int __k = peek(); if (__k != KIND_INLINE_MAXVALUE_CI) { fail("MaxvalueKW", RULE_SequenceOption_KIND); break; } }
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
                                { int __k = peek(); if (__k != KIND_INLINE_CYCLE_CI) { fail("CycleKW", RULE_SequenceOption_KIND); break; } }
                                advance();
                                matched_alt_3 = true;
                            } while (false);
                            if (!matched_alt_3) {
                                pos = savedPos_alt_3;
                                cst.truncate(savedNodes_alt_3);
                            }
                        }
                        if (!matched_alt_3) { fail("<choice>", RULE_SequenceOption_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_INLINE_START_CI) { fail("StartKW", RULE_SequenceOption_KIND); break; } }
                    advance();
                    // optional: opt_4
                    {
                        int savedPos_opt_4 = pos;
                        int savedNodes_opt_4 = cst.currentNodeCount();
                        boolean optOk_opt_4 = false;
                        do {
                            { int __k = peek(); if (__k != KIND_INLINE_WITH_CI) { fail("WithKW", RULE_SequenceOption_KIND); break; } }
                            advance();
                            optOk_opt_4 = true;
                        } while (false);
                        if (!optOk_opt_4) {
                            pos = savedPos_opt_4;
                            cst.truncate(savedNodes_opt_4);
                        }
                    }
                    if (!parseSignedNumericLiteral(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_RESTART_CI) { fail("RestartKW", RULE_SequenceOption_KIND); break; } }
                    advance();
                    // optional: opt_5
                    {
                        int savedPos_opt_5 = pos;
                        int savedNodes_opt_5 = cst.currentNodeCount();
                        boolean optOk_opt_5 = false;
                        do {
                            // optional: opt_6
                            {
                                int savedPos_opt_6 = pos;
                                int savedNodes_opt_6 = cst.currentNodeCount();
                                boolean optOk_opt_6 = false;
                                do {
                                    { int __k = peek(); if (__k != KIND_INLINE_WITH_CI) { fail("WithKW", RULE_SequenceOption_KIND); break; } }
                                    advance();
                                    optOk_opt_6 = true;
                                } while (false);
                                if (!optOk_opt_6) {
                                    pos = savedPos_opt_6;
                                    cst.truncate(savedNodes_opt_6);
                                }
                            }
                            if (!parseSignedNumericLiteral(self)) { break; }
                            optOk_opt_5 = true;
                        } while (false);
                        if (!optOk_opt_5) {
                            pos = savedPos_opt_5;
                            cst.truncate(savedNodes_opt_5);
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
                    { int __k = peek(); if (__k != KIND_INLINE_CACHE_CI) { fail("CacheKW", RULE_SequenceOption_KIND); break; } }
                    advance();
                    if (!parseSignedNumericLiteral(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_CYCLE_CI) { fail("CycleKW", RULE_SequenceOption_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_OWNED_CI) { fail("OwnedKW", RULE_SequenceOption_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_INLINE_BY_CI) { fail("ByKW", RULE_SequenceOption_KIND); break; } }
                    advance();
                    // choice: alt_7
                    {
                        int savedPos_alt_7 = pos;
                        int savedNodes_alt_7 = cst.currentNodeCount();
                        boolean matched_alt_7 = false;
                        boolean cutHit_alt_7 = false;
                        if (!matched_alt_7 && !cutHit_alt_7) {
                            do {
                                if (!parseQualifiedName(self)) { break; }
                                matched_alt_7 = true;
                            } while (false);
                            if (!matched_alt_7) {
                                pos = savedPos_alt_7;
                                cst.truncate(savedNodes_alt_7);
                            }
                        }
                        if (!matched_alt_7 && !cutHit_alt_7) {
                            do {
                                { int __k = peek(); if (__k != KIND_INLINE_NONE_CI) { fail("NoneKW", RULE_SequenceOption_KIND); break; } }
                                advance();
                                matched_alt_7 = true;
                            } while (false);
                            if (!matched_alt_7) {
                                pos = savedPos_alt_7;
                                cst.truncate(savedNodes_alt_7);
                            }
                        }
                        if (!matched_alt_7) { fail("<choice>", RULE_SequenceOption_KIND); break; }
                    }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_SequenceOption_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseCreateTypeStmt(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_CreateTypeStmt_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_TYPE_CI) { fail("TypeKW", RULE_CreateTypeStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parseQualifiedName(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
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
                            { int __k = peek(); if (__k != KIND_INLINE_AS_CI) { fail("AsKW", RULE_CreateTypeStmt_KIND); break; } }
                            advance();
                            { int __k = peek(); if (__k != KIND_INLINE_ENUM_CI) { fail("EnumKW", RULE_CreateTypeStmt_KIND); break; } }
                            advance();
                            if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_CreateTypeStmt_KIND); break; }
                            advance();
                            if (!parseEnumLabelList(self)) { break; }
                            if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_CreateTypeStmt_KIND); break; }
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
                            { int __k = peek(); if (__k != KIND_INLINE_AS_CI) { fail("AsKW", RULE_CreateTypeStmt_KIND); break; } }
                            advance();
                            if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_CreateTypeStmt_KIND); break; }
                            advance();
                            if (!parseCompositeFieldList(self)) { break; }
                            if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_CreateTypeStmt_KIND); break; }
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
                            { int __k = peek(); if (__k != KIND_INLINE_AS_CI) { fail("AsKW", RULE_CreateTypeStmt_KIND); break; } }
                            advance();
                            { int __k = peek(); if (__k != KIND_INLINE_RANGE_CI) { fail("RangeKW", RULE_CreateTypeStmt_KIND); break; } }
                            advance();
                            if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_CreateTypeStmt_KIND); break; }
                            advance();
                            if (!parseRangeOptionList(self)) { break; }
                            if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_CreateTypeStmt_KIND); break; }
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
                            { int __k = peek(); if (__k != KIND_INLINE_AS_CI) { fail("AsKW", RULE_CreateTypeStmt_KIND); break; } }
                            advance();
                            if (!parseDataType(self)) { break; }
                            // zero-or-more: rep_2
                            while (true) {
                                int savedPos_rep_2 = pos;
                                int savedNodes_rep_2 = cst.currentNodeCount();
                                boolean iterOk_rep_2 = false;
                                do {
                                    if (!parseDomainConstraint(self)) { break; }
                                    iterOk_rep_2 = true;
                                } while (false);
                                if (!iterOk_rep_2) {
                                    pos = savedPos_rep_2;
                                    cst.truncate(savedNodes_rep_2);
                                    break;
                                }
                                if (pos == savedPos_rep_2) break; // guard against infinite loops on zero-width matches
                            }
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1) { fail("<choice>", RULE_CreateTypeStmt_KIND); break; }
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

    private boolean parseEnumLabelList(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_EnumLabelList_KIND, firstTok, parent);
        if (!parseStringLiteral(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_EnumLabelList_KIND); break; }
                advance();
                if (!parseStringLiteral(self)) { break; }
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

    private boolean parseCompositeFieldList(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_CompositeFieldList_KIND, firstTok, parent);
        if (!parseCompositeField(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_CompositeFieldList_KIND); break; }
                advance();
                if (!parseCompositeField(self)) { break; }
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

    private boolean parseCompositeField(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_CompositeField_KIND, firstTok, parent);
        if (peek() != KIND_COLID) { fail("ColId", RULE_CompositeField_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseDataType(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parseCollateClause(self)) { break; }
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

    private boolean parseRangeOptionList(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_RangeOptionList_KIND, firstTok, parent);
        if (!parseRangeOption(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_RangeOptionList_KIND); break; }
                advance();
                if (!parseRangeOption(self)) { break; }
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

    private boolean parseRangeOption(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_RangeOption_KIND, firstTok, parent);
        if (peek() != KIND_COLID) { fail("ColId", RULE_RangeOption_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (peek() != KIND_INLINE__EQ) { fail("'='", RULE_RangeOption_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_COLID) { fail("ColId", RULE_RangeOption_KIND); break; }
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
                    if (!parseStringLiteral(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseFuncCall(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_RangeOption_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseDomainConstraint(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_DomainConstraint_KIND, firstTok, parent);
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_CONSTRAINTNAME) { fail("ConstraintName", RULE_DomainConstraint_KIND); break; }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        // choice: alt_1
        {
            int savedPos_alt_1 = pos;
            int savedNodes_alt_1 = cst.currentNodeCount();
            boolean matched_alt_1 = false;
            boolean cutHit_alt_1 = false;
            if (!matched_alt_1 && !cutHit_alt_1) {
                do {
                    if (!parseCheckColConstraint(self)) { break; }
                    matched_alt_1 = true;
                } while (false);
                if (!matched_alt_1) {
                    pos = savedPos_alt_1;
                    cst.truncate(savedNodes_alt_1);
                }
            }
            if (!matched_alt_1 && !cutHit_alt_1) {
                do {
                    if (peek() != KIND_NOTNULLCONSTRAINT) { fail("NotNullConstraint", RULE_DomainConstraint_KIND); break; }
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
                    if (peek() != KIND_NULLCONSTRAINT) { fail("NullConstraint", RULE_DomainConstraint_KIND); break; }
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
                    if (!parseDefaultClause(self)) { break; }
                    matched_alt_1 = true;
                } while (false);
                if (!matched_alt_1) {
                    pos = savedPos_alt_1;
                    cst.truncate(savedNodes_alt_1);
                }
            }
            if (!matched_alt_1 && !cutHit_alt_1) {
                do {
                    if (!parseCollateClause(self)) { break; }
                    matched_alt_1 = true;
                } while (false);
                if (!matched_alt_1) {
                    pos = savedPos_alt_1;
                    cst.truncate(savedNodes_alt_1);
                }
            }
            if (!matched_alt_1) { fail("<choice>", RULE_DomainConstraint_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseAlterTypeStmt(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_AlterTypeStmt_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_TYPE_CI) { fail("TypeKW", RULE_AlterTypeStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parseQualifiedName(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_ADD_CI) { fail("AddKW", RULE_AlterTypeStmt_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_INLINE_VALUE_CI) { fail("ValueKW", RULE_AlterTypeStmt_KIND); break; } }
                    advance();
                    // optional: opt_1
                    {
                        int savedPos_opt_1 = pos;
                        int savedNodes_opt_1 = cst.currentNodeCount();
                        boolean optOk_opt_1 = false;
                        do {
                            if (peek() != KIND_IFNOTEXISTS) { fail("IfNotExists", RULE_AlterTypeStmt_KIND); break; }
                            advance();
                            optOk_opt_1 = true;
                        } while (false);
                        if (!optOk_opt_1) {
                            pos = savedPos_opt_1;
                            cst.truncate(savedNodes_opt_1);
                        }
                    }
                    if (!parseStringLiteral(self)) { break; }
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
                                        { int __k = peek(); if (__k != KIND_INLINE_BEFORE_CI) { fail("BeforeKW", RULE_AlterTypeStmt_KIND); break; } }
                                        advance();
                                        if (!parseStringLiteral(self)) { break; }
                                        matched_alt_3 = true;
                                    } while (false);
                                    if (!matched_alt_3) {
                                        pos = savedPos_alt_3;
                                        cst.truncate(savedNodes_alt_3);
                                    }
                                }
                                if (!matched_alt_3 && !cutHit_alt_3) {
                                    do {
                                        { int __k = peek(); if (__k != KIND_INLINE_AFTER_CI) { fail("AfterKW", RULE_AlterTypeStmt_KIND); break; } }
                                        advance();
                                        if (!parseStringLiteral(self)) { break; }
                                        matched_alt_3 = true;
                                    } while (false);
                                    if (!matched_alt_3) {
                                        pos = savedPos_alt_3;
                                        cst.truncate(savedNodes_alt_3);
                                    }
                                }
                                if (!matched_alt_3) { fail("<choice>", RULE_AlterTypeStmt_KIND); break; }
                            }
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
                    { int __k = peek(); if (__k != KIND_INLINE_RENAME_CI) { fail("RenameKW", RULE_AlterTypeStmt_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_INLINE_VALUE_CI) { fail("ValueKW", RULE_AlterTypeStmt_KIND); break; } }
                    advance();
                    if (!parseStringLiteral(self)) { break; }
                    { int __k = peek(); if (__k != KIND_INLINE_TO_CI) { fail("ToKW", RULE_AlterTypeStmt_KIND); break; } }
                    advance();
                    if (!parseStringLiteral(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_RENAME_CI) { fail("RenameKW", RULE_AlterTypeStmt_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_INLINE_TO_CI) { fail("ToKW", RULE_AlterTypeStmt_KIND); break; } }
                    advance();
                    if (peek() != KIND_COLID) { fail("ColId", RULE_AlterTypeStmt_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_INLINE_SET_CI) { fail("SetKW", RULE_AlterTypeStmt_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_INLINE_SCHEMA_CI) { fail("SchemaKW", RULE_AlterTypeStmt_KIND); break; } }
                    advance();
                    if (peek() != KIND_COLID) { fail("ColId", RULE_AlterTypeStmt_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_INLINE_ADD_CI) { fail("AddKW", RULE_AlterTypeStmt_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_INLINE_ATTRIBUTE_CI) { fail("AttributeKW", RULE_AlterTypeStmt_KIND); break; } }
                    advance();
                    if (peek() != KIND_COLID) { fail("ColId", RULE_AlterTypeStmt_KIND); break; }
                    advance();
                    if (!parseDataType(self)) { break; }
                    // optional: opt_4
                    {
                        int savedPos_opt_4 = pos;
                        int savedNodes_opt_4 = cst.currentNodeCount();
                        boolean optOk_opt_4 = false;
                        do {
                            if (!parseCollateClause(self)) { break; }
                            optOk_opt_4 = true;
                        } while (false);
                        if (!optOk_opt_4) {
                            pos = savedPos_opt_4;
                            cst.truncate(savedNodes_opt_4);
                        }
                    }
                    // optional: opt_5
                    {
                        int savedPos_opt_5 = pos;
                        int savedNodes_opt_5 = cst.currentNodeCount();
                        boolean optOk_opt_5 = false;
                        do {
                            if (peek() != KIND_DROPBEHAVIOR) { fail("DropBehavior", RULE_AlterTypeStmt_KIND); break; }
                            advance();
                            optOk_opt_5 = true;
                        } while (false);
                        if (!optOk_opt_5) {
                            pos = savedPos_opt_5;
                            cst.truncate(savedNodes_opt_5);
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
                    { int __k = peek(); if (__k != KIND_INLINE_DROP_CI) { fail("DropKW", RULE_AlterTypeStmt_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_INLINE_ATTRIBUTE_CI) { fail("AttributeKW", RULE_AlterTypeStmt_KIND); break; } }
                    advance();
                    // optional: opt_6
                    {
                        int savedPos_opt_6 = pos;
                        int savedNodes_opt_6 = cst.currentNodeCount();
                        boolean optOk_opt_6 = false;
                        do {
                            if (peek() != KIND_IFEXISTS) { fail("IfExists", RULE_AlterTypeStmt_KIND); break; }
                            advance();
                            optOk_opt_6 = true;
                        } while (false);
                        if (!optOk_opt_6) {
                            pos = savedPos_opt_6;
                            cst.truncate(savedNodes_opt_6);
                        }
                    }
                    if (peek() != KIND_COLID) { fail("ColId", RULE_AlterTypeStmt_KIND); break; }
                    advance();
                    // optional: opt_7
                    {
                        int savedPos_opt_7 = pos;
                        int savedNodes_opt_7 = cst.currentNodeCount();
                        boolean optOk_opt_7 = false;
                        do {
                            if (peek() != KIND_DROPBEHAVIOR) { fail("DropBehavior", RULE_AlterTypeStmt_KIND); break; }
                            advance();
                            optOk_opt_7 = true;
                        } while (false);
                        if (!optOk_opt_7) {
                            pos = savedPos_opt_7;
                            cst.truncate(savedNodes_opt_7);
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
                    { int __k = peek(); if (__k != KIND_INLINE_ALTER_CI) { fail("AlterKW", RULE_AlterTypeStmt_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_INLINE_ATTRIBUTE_CI) { fail("AttributeKW", RULE_AlterTypeStmt_KIND); break; } }
                    advance();
                    if (peek() != KIND_COLID) { fail("ColId", RULE_AlterTypeStmt_KIND); break; }
                    advance();
                    if (!parseSetDataTypeCmd(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_AlterTypeStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseDropTypeStmt(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_DropTypeStmt_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_TYPE_CI) { fail("TypeKW", RULE_DropTypeStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_IFEXISTS) { fail("IfExists", RULE_DropTypeStmt_KIND); break; }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (!parseQualifiedNameList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                if (peek() != KIND_DROPBEHAVIOR) { fail("DropBehavior", RULE_DropTypeStmt_KIND); break; }
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

    private boolean parseDropSchemaStmt(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_DropSchemaStmt_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_SCHEMA_CI) { fail("SchemaKW", RULE_DropSchemaStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_IFEXISTS) { fail("IfExists", RULE_DropSchemaStmt_KIND); break; }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (peek() != KIND_COLID) { fail("ColId", RULE_DropSchemaStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // zero-or-more: rep_1
        while (true) {
            int savedPos_rep_1 = pos;
            int savedNodes_rep_1 = cst.currentNodeCount();
            boolean iterOk_rep_1 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_DropSchemaStmt_KIND); break; }
                advance();
                if (peek() != KIND_COLID) { fail("ColId", RULE_DropSchemaStmt_KIND); break; }
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
                if (peek() != KIND_DROPBEHAVIOR) { fail("DropBehavior", RULE_DropSchemaStmt_KIND); break; }
                advance();
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

    private boolean parseCreateViewStmt(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_CreateViewStmt_KIND, firstTok, parent);
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                { int __k = peek(); if (__k != KIND_INLINE_OR_CI) { fail("OrKW", RULE_CreateViewStmt_KIND); break; } }
                advance();
                { int __k = peek(); if (__k != KIND_INLINE_REPLACE_CI) { fail("ReplaceKW", RULE_CreateViewStmt_KIND); break; } }
                advance();
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
                { int __k = peek(); if (__k != KIND_INLINE_TEMP_CI) { fail("TempKW", RULE_CreateViewStmt_KIND); break; } }
                advance();
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
                { int __k = peek(); if (__k != KIND_INLINE_RECURSIVE_CI) { fail("RecursiveKW", RULE_CreateViewStmt_KIND); break; } }
                advance();
                optOk_opt_2 = true;
            } while (false);
            if (!optOk_opt_2) {
                pos = savedPos_opt_2;
                cst.truncate(savedNodes_opt_2);
            }
        }
        { int __k = peek(); if (__k != KIND_INLINE_VIEW_CI) { fail("ViewKW", RULE_CreateViewStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parseQualifiedName(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_3
        {
            int savedPos_opt_3 = pos;
            int savedNodes_opt_3 = cst.currentNodeCount();
            boolean optOk_opt_3 = false;
            do {
                if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_CreateViewStmt_KIND); break; }
                advance();
                if (!parseColumnList(self)) { break; }
                if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_CreateViewStmt_KIND); break; }
                advance();
                optOk_opt_3 = true;
            } while (false);
            if (!optOk_opt_3) {
                pos = savedPos_opt_3;
                cst.truncate(savedNodes_opt_3);
            }
        }
        { int __k = peek(); if (__k != KIND_INLINE_AS_CI) { fail("AsKW", RULE_CreateViewStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseSelectStmt(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_4
        {
            int savedPos_opt_4 = pos;
            int savedNodes_opt_4 = cst.currentNodeCount();
            boolean optOk_opt_4 = false;
            do {
                if (peek() != KIND_CHECKOPTIONCLAUSE) { fail("CheckOptionClause", RULE_CreateViewStmt_KIND); break; }
                advance();
                optOk_opt_4 = true;
            } while (false);
            if (!optOk_opt_4) {
                pos = savedPos_opt_4;
                cst.truncate(savedNodes_opt_4);
            }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseCreateMatViewStmt(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_CreateMatViewStmt_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_MATERIALIZED_CI) { fail("MaterializedKW", RULE_CreateMatViewStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_INLINE_VIEW_CI) { fail("ViewKW", RULE_CreateMatViewStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_IFNOTEXISTS) { fail("IfNotExists", RULE_CreateMatViewStmt_KIND); break; }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (!parseQualifiedName(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_CreateMatViewStmt_KIND); break; }
                advance();
                if (!parseColumnList(self)) { break; }
                if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_CreateMatViewStmt_KIND); break; }
                advance();
                optOk_opt_1 = true;
            } while (false);
            if (!optOk_opt_1) {
                pos = savedPos_opt_1;
                cst.truncate(savedNodes_opt_1);
            }
        }
        { int __k = peek(); if (__k != KIND_INLINE_AS_CI) { fail("AsKW", RULE_CreateMatViewStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseSelectStmt(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_2
        {
            int savedPos_opt_2 = pos;
            int savedNodes_opt_2 = cst.currentNodeCount();
            boolean optOk_opt_2 = false;
            do {
                { int __k = peek(); if (__k != KIND_INLINE_WITH_CI) { fail("WithKW", RULE_CreateMatViewStmt_KIND); break; } }
                advance();
                // optional: opt_3
                {
                    int savedPos_opt_3 = pos;
                    int savedNodes_opt_3 = cst.currentNodeCount();
                    boolean optOk_opt_3 = false;
                    do {
                        { int __k = peek(); if (__k != KIND_INLINE_NO_CI) { fail("NoKW", RULE_CreateMatViewStmt_KIND); break; } }
                        advance();
                        optOk_opt_3 = true;
                    } while (false);
                    if (!optOk_opt_3) {
                        pos = savedPos_opt_3;
                        cst.truncate(savedNodes_opt_3);
                    }
                }
                { int __k = peek(); if (__k != KIND_INLINE_DATA_CI) { fail("DataKW", RULE_CreateMatViewStmt_KIND); break; } }
                advance();
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

    private boolean parseAlterViewStmt(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_AlterViewStmt_KIND, firstTok, parent);
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                { int __k = peek(); if (__k != KIND_INLINE_MATERIALIZED_CI) { fail("MaterializedKW", RULE_AlterViewStmt_KIND); break; } }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        { int __k = peek(); if (__k != KIND_INLINE_VIEW_CI) { fail("ViewKW", RULE_AlterViewStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                if (peek() != KIND_IFEXISTS) { fail("IfExists", RULE_AlterViewStmt_KIND); break; }
                advance();
                optOk_opt_1 = true;
            } while (false);
            if (!optOk_opt_1) {
                pos = savedPos_opt_1;
                cst.truncate(savedNodes_opt_1);
            }
        }
        if (!parseQualifiedName(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // choice: alt_2
        {
            int savedPos_alt_2 = pos;
            int savedNodes_alt_2 = cst.currentNodeCount();
            boolean matched_alt_2 = false;
            boolean cutHit_alt_2 = false;
            if (!matched_alt_2 && !cutHit_alt_2) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_RENAME_CI) { fail("RenameKW", RULE_AlterViewStmt_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_INLINE_TO_CI) { fail("ToKW", RULE_AlterViewStmt_KIND); break; } }
                    advance();
                    if (peek() != KIND_COLID) { fail("ColId", RULE_AlterViewStmt_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_INLINE_SET_CI) { fail("SetKW", RULE_AlterViewStmt_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_INLINE_SCHEMA_CI) { fail("SchemaKW", RULE_AlterViewStmt_KIND); break; } }
                    advance();
                    if (peek() != KIND_COLID) { fail("ColId", RULE_AlterViewStmt_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_INLINE_OWNER_CI) { fail("OwnerKW", RULE_AlterViewStmt_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_INLINE_TO_CI) { fail("ToKW", RULE_AlterViewStmt_KIND); break; } }
                    advance();
                    if (peek() != KIND_COLID) { fail("ColId", RULE_AlterViewStmt_KIND); break; }
                    advance();
                    matched_alt_2 = true;
                } while (false);
                if (!matched_alt_2) {
                    pos = savedPos_alt_2;
                    cst.truncate(savedNodes_alt_2);
                }
            }
            if (!matched_alt_2) { fail("<choice>", RULE_AlterViewStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseDropViewStmt(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_DropViewStmt_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_VIEW_CI) { fail("ViewKW", RULE_DropViewStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_IFEXISTS) { fail("IfExists", RULE_DropViewStmt_KIND); break; }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (!parseQualifiedNameList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                if (peek() != KIND_DROPBEHAVIOR) { fail("DropBehavior", RULE_DropViewStmt_KIND); break; }
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

    private boolean parseDropMatViewStmt(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_DropMatViewStmt_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_MATERIALIZED_CI) { fail("MaterializedKW", RULE_DropMatViewStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_INLINE_VIEW_CI) { fail("ViewKW", RULE_DropMatViewStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_IFEXISTS) { fail("IfExists", RULE_DropMatViewStmt_KIND); break; }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (!parseQualifiedNameList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                if (peek() != KIND_DROPBEHAVIOR) { fail("DropBehavior", RULE_DropMatViewStmt_KIND); break; }
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

    private boolean parseCreateExtensionStmt(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_CreateExtensionStmt_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_EXTENSION_CI) { fail("ExtensionKW", RULE_CreateExtensionStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_IFNOTEXISTS) { fail("IfNotExists", RULE_CreateExtensionStmt_KIND); break; }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (peek() != KIND_COLID) { fail("ColId", RULE_CreateExtensionStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                if (!parseExtensionOptions(self)) { break; }
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

    private boolean parseExtensionOptions(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ExtensionOptions_KIND, firstTok, parent);
        if (!parseExtensionOption(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (!parseExtensionOption(self)) { break; }
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

    private boolean parseExtensionOption(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ExtensionOption_KIND, firstTok, parent);
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                { int __k = peek(); if (__k != KIND_INLINE_WITH_CI) { fail("WithKW", RULE_ExtensionOption_KIND); break; } }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        // choice: alt_1
        {
            int savedPos_alt_1 = pos;
            int savedNodes_alt_1 = cst.currentNodeCount();
            boolean matched_alt_1 = false;
            boolean cutHit_alt_1 = false;
            if (!matched_alt_1 && !cutHit_alt_1) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_SCHEMA_CI) { fail("SchemaKW", RULE_ExtensionOption_KIND); break; } }
                    advance();
                    if (peek() != KIND_COLID) { fail("ColId", RULE_ExtensionOption_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_INLINE_VERSION_CI) { fail("VersionKW", RULE_ExtensionOption_KIND); break; } }
                    advance();
                    // choice: alt_2
                    {
                        int savedPos_alt_2 = pos;
                        int savedNodes_alt_2 = cst.currentNodeCount();
                        boolean matched_alt_2 = false;
                        boolean cutHit_alt_2 = false;
                        if (!matched_alt_2 && !cutHit_alt_2) {
                            do {
                                if (!parseStringLiteral(self)) { break; }
                                matched_alt_2 = true;
                            } while (false);
                            if (!matched_alt_2) {
                                pos = savedPos_alt_2;
                                cst.truncate(savedNodes_alt_2);
                            }
                        }
                        if (!matched_alt_2 && !cutHit_alt_2) {
                            do {
                                if (peek() != KIND_COLID) { fail("ColId", RULE_ExtensionOption_KIND); break; }
                                advance();
                                matched_alt_2 = true;
                            } while (false);
                            if (!matched_alt_2) {
                                pos = savedPos_alt_2;
                                cst.truncate(savedNodes_alt_2);
                            }
                        }
                        if (!matched_alt_2) { fail("<choice>", RULE_ExtensionOption_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_INLINE_FROM_CI) { fail("FromKW", RULE_ExtensionOption_KIND); break; } }
                    advance();
                    // choice: alt_3
                    {
                        int savedPos_alt_3 = pos;
                        int savedNodes_alt_3 = cst.currentNodeCount();
                        boolean matched_alt_3 = false;
                        boolean cutHit_alt_3 = false;
                        if (!matched_alt_3 && !cutHit_alt_3) {
                            do {
                                if (!parseStringLiteral(self)) { break; }
                                matched_alt_3 = true;
                            } while (false);
                            if (!matched_alt_3) {
                                pos = savedPos_alt_3;
                                cst.truncate(savedNodes_alt_3);
                            }
                        }
                        if (!matched_alt_3 && !cutHit_alt_3) {
                            do {
                                if (peek() != KIND_COLID) { fail("ColId", RULE_ExtensionOption_KIND); break; }
                                advance();
                                matched_alt_3 = true;
                            } while (false);
                            if (!matched_alt_3) {
                                pos = savedPos_alt_3;
                                cst.truncate(savedNodes_alt_3);
                            }
                        }
                        if (!matched_alt_3) { fail("<choice>", RULE_ExtensionOption_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_INLINE_CASCADE_CI) { fail("CascadeKW", RULE_ExtensionOption_KIND); break; } }
                    advance();
                    matched_alt_1 = true;
                } while (false);
                if (!matched_alt_1) {
                    pos = savedPos_alt_1;
                    cst.truncate(savedNodes_alt_1);
                }
            }
            if (!matched_alt_1) { fail("<choice>", RULE_ExtensionOption_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseDropExtensionStmt(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_DropExtensionStmt_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_EXTENSION_CI) { fail("ExtensionKW", RULE_DropExtensionStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_IFEXISTS) { fail("IfExists", RULE_DropExtensionStmt_KIND); break; }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (peek() != KIND_COLID) { fail("ColId", RULE_DropExtensionStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // zero-or-more: rep_1
        while (true) {
            int savedPos_rep_1 = pos;
            int savedNodes_rep_1 = cst.currentNodeCount();
            boolean iterOk_rep_1 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_DropExtensionStmt_KIND); break; }
                advance();
                if (peek() != KIND_COLID) { fail("ColId", RULE_DropExtensionStmt_KIND); break; }
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
                if (peek() != KIND_DROPBEHAVIOR) { fail("DropBehavior", RULE_DropExtensionStmt_KIND); break; }
                advance();
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

    private boolean parseCommentStatement(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_CommentStatement_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_COMMENT_CI) { fail("CommentKW", RULE_CommentStatement_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_INLINE_ON_CI) { fail("OnKW", RULE_CommentStatement_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parseCommentTarget(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        { int __k = peek(); if (__k != KIND_INLINE_IS_CI) { fail("IsKW", RULE_CommentStatement_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseStringLiteral(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_NULL_CI) { fail("NullKW", RULE_CommentStatement_KIND); break; } }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_CommentStatement_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseCommentTarget(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_CommentTarget_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_TABLE_CI) { fail("TableKW", RULE_CommentTarget_KIND); break; } }
                    advance();
                    if (!parseQualifiedName(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_COLUMN_CI) { fail("ColumnKW", RULE_CommentTarget_KIND); break; } }
                    advance();
                    if (!parseQualifiedName(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_INDEX_CI) { fail("IndexKW", RULE_CommentTarget_KIND); break; } }
                    advance();
                    if (!parseQualifiedName(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_SCHEMA_CI) { fail("SchemaKW", RULE_CommentTarget_KIND); break; } }
                    advance();
                    if (peek() != KIND_COLID) { fail("ColId", RULE_CommentTarget_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_INLINE_TYPE_CI) { fail("TypeKW", RULE_CommentTarget_KIND); break; } }
                    advance();
                    if (!parseQualifiedName(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_EXTENSION_CI) { fail("ExtensionKW", RULE_CommentTarget_KIND); break; } }
                    advance();
                    if (peek() != KIND_COLID) { fail("ColId", RULE_CommentTarget_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_INLINE_SEQUENCE_CI) { fail("SequenceKW", RULE_CommentTarget_KIND); break; } }
                    advance();
                    if (!parseQualifiedName(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_VIEW_CI) { fail("ViewKW", RULE_CommentTarget_KIND); break; } }
                    advance();
                    if (!parseQualifiedName(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_MATERIALIZED_CI) { fail("MaterializedKW", RULE_CommentTarget_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_INLINE_VIEW_CI) { fail("ViewKW", RULE_CommentTarget_KIND); break; } }
                    advance();
                    if (!parseQualifiedName(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_CONSTRAINT_CI) { fail("ConstraintKW", RULE_CommentTarget_KIND); break; } }
                    advance();
                    if (peek() != KIND_COLID) { fail("ColId", RULE_CommentTarget_KIND); break; }
                    advance();
                    { int __k = peek(); if (__k != KIND_INLINE_ON_CI) { fail("OnKW", RULE_CommentTarget_KIND); break; } }
                    advance();
                    if (!parseQualifiedName(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_FUNCTION_CI) { fail("FunctionKW", RULE_CommentTarget_KIND); break; } }
                    advance();
                    if (!parseQualifiedName(self)) { break; }
                    if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_CommentTarget_KIND); break; }
                    advance();
                    // optional: opt_1
                    {
                        int savedPos_opt_1 = pos;
                        int savedNodes_opt_1 = cst.currentNodeCount();
                        boolean optOk_opt_1 = false;
                        do {
                            if (!parseFuncArgTypes(self)) { break; }
                            optOk_opt_1 = true;
                        } while (false);
                        if (!optOk_opt_1) {
                            pos = savedPos_opt_1;
                            cst.truncate(savedNodes_opt_1);
                        }
                    }
                    if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_CommentTarget_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_CommentTarget_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseFuncArgTypes(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_FuncArgTypes_KIND, firstTok, parent);
        if (!parseFuncArgType(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_FuncArgTypes_KIND); break; }
                advance();
                if (!parseFuncArgType(self)) { break; }
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

    private boolean parseFuncArgType(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_FuncArgType_KIND, firstTok, parent);
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
                            { int __k = peek(); if (__k != KIND_INLINE_IN_CI) { fail("InKW", RULE_FuncArgType_KIND); break; } }
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
                            { int __k = peek(); if (__k != KIND_INLINE_OUT_CI) { fail("OutKW", RULE_FuncArgType_KIND); break; } }
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
                            { int __k = peek(); if (__k != KIND_INLINE_INOUT_CI) { fail("InoutKW", RULE_FuncArgType_KIND); break; } }
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
                            { int __k = peek(); if (__k != KIND_INLINE_VARIADIC_CI) { fail("VariadicKW", RULE_FuncArgType_KIND); break; } }
                            advance();
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1) { fail("<choice>", RULE_FuncArgType_KIND); break; }
                }
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (!parseDataType(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseGrantStatement(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_GrantStatement_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_GRANT_CI) { fail("GrantKW", RULE_GrantStatement_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parsePrivilegeList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        { int __k = peek(); if (__k != KIND_INLINE_ON_CI) { fail("OnKW", RULE_GrantStatement_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseGrantTarget(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        { int __k = peek(); if (__k != KIND_INLINE_TO_CI) { fail("ToKW", RULE_GrantStatement_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseGranteeList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                { int __k = peek(); if (__k != KIND_INLINE_WITH_CI) { fail("WithKW", RULE_GrantStatement_KIND); break; } }
                advance();
                { int __k = peek(); if (__k != KIND_INLINE_GRANT_CI) { fail("GrantKW", RULE_GrantStatement_KIND); break; } }
                advance();
                { int __k = peek(); if (__k != KIND_INLINE_OPTION_CI) { fail("OptionKW", RULE_GrantStatement_KIND); break; } }
                advance();
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

    private boolean parseRevokeStatement(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_RevokeStatement_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_REVOKE_CI) { fail("RevokeKW", RULE_RevokeStatement_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                { int __k = peek(); if (__k != KIND_INLINE_GRANT_CI) { fail("GrantKW", RULE_RevokeStatement_KIND); break; } }
                advance();
                { int __k = peek(); if (__k != KIND_INLINE_OPTION_CI) { fail("OptionKW", RULE_RevokeStatement_KIND); break; } }
                advance();
                { int __k = peek(); if (__k != KIND_INLINE_FOR_CI) { fail("ForKW", RULE_RevokeStatement_KIND); break; } }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (!parsePrivilegeList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        { int __k = peek(); if (__k != KIND_INLINE_ON_CI) { fail("OnKW", RULE_RevokeStatement_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseGrantTarget(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        { int __k = peek(); if (__k != KIND_INLINE_FROM_CI) { fail("FromKW", RULE_RevokeStatement_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseGranteeList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                if (peek() != KIND_DROPBEHAVIOR) { fail("DropBehavior", RULE_RevokeStatement_KIND); break; }
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

    private boolean parsePrivilegeList(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_PrivilegeList_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_ALL_CI) { fail("AllKW", RULE_PrivilegeList_KIND); break; } }
                    advance();
                    // optional: opt_1
                    {
                        int savedPos_opt_1 = pos;
                        int savedNodes_opt_1 = cst.currentNodeCount();
                        boolean optOk_opt_1 = false;
                        do {
                            { int __k = peek(); if (__k != KIND_INLINE_PRIVILEGES_CI) { fail("PrivilegesKW", RULE_PrivilegeList_KIND); break; } }
                            advance();
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
                    if (!parsePrivilege(self)) { break; }
                    // zero-or-more: rep_2
                    while (true) {
                        int savedPos_rep_2 = pos;
                        int savedNodes_rep_2 = cst.currentNodeCount();
                        boolean iterOk_rep_2 = false;
                        do {
                            if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_PrivilegeList_KIND); break; }
                            advance();
                            if (!parsePrivilege(self)) { break; }
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
            if (!matched_alt_0) { fail("<choice>", RULE_PrivilegeList_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parsePrivilege(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_Privilege_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_SELECT_CI) { fail("SelectKW", RULE_Privilege_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_INSERT_CI) { fail("InsertKW", RULE_Privilege_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_UPDATE_CI) { fail("UpdateKW", RULE_Privilege_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_DELETE_CI) { fail("DeleteKW", RULE_Privilege_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_TRUNCATE_CI) { fail("TruncateKW", RULE_Privilege_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_REFERENCES_CI) { fail("ReferencesKW", RULE_Privilege_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_TRIGGER_CI) { fail("TriggerKW", RULE_Privilege_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_CREATE_CI) { fail("CreateKW", RULE_Privilege_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_CONNECT_CI) { fail("ConnectKW", RULE_Privilege_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_TEMPORARY_CI) { fail("TemporaryKW", RULE_Privilege_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_EXECUTE_CI) { fail("ExecuteKW", RULE_Privilege_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_USAGE_CI) { fail("UsageKW", RULE_Privilege_KIND); break; } }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_Privilege_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_Privilege_KIND); break; }
                advance();
                if (!parseColumnList(self)) { break; }
                if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_Privilege_KIND); break; }
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

    private boolean parseGrantTarget(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_GrantTarget_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_ALL_CI) { fail("AllKW", RULE_GrantTarget_KIND); break; } }
                    advance();
                    // choice: alt_1
                    {
                        int savedPos_alt_1 = pos;
                        int savedNodes_alt_1 = cst.currentNodeCount();
                        boolean matched_alt_1 = false;
                        boolean cutHit_alt_1 = false;
                        if (!matched_alt_1 && !cutHit_alt_1) {
                            do {
                                { int __k = peek(); if (__k != KIND_INLINE_TABLES_CI) { fail("TablesKW", RULE_GrantTarget_KIND); break; } }
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
                                { int __k = peek(); if (__k != KIND_INLINE_SEQUENCES_CI) { fail("SequencesKW", RULE_GrantTarget_KIND); break; } }
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
                                { int __k = peek(); if (__k != KIND_INLINE_FUNCTIONS_CI) { fail("FunctionsKW", RULE_GrantTarget_KIND); break; } }
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
                                { int __k = peek(); if (__k != KIND_INLINE_SCHEMAS_CI) { fail("SchemasKW", RULE_GrantTarget_KIND); break; } }
                                advance();
                                matched_alt_1 = true;
                            } while (false);
                            if (!matched_alt_1) {
                                pos = savedPos_alt_1;
                                cst.truncate(savedNodes_alt_1);
                            }
                        }
                        if (!matched_alt_1) { fail("<choice>", RULE_GrantTarget_KIND); break; }
                    }
                    { int __k = peek(); if (__k != KIND_INLINE_IN_CI) { fail("InKW", RULE_GrantTarget_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_INLINE_SCHEMA_CI) { fail("SchemaKW", RULE_GrantTarget_KIND); break; } }
                    advance();
                    if (peek() != KIND_COLID) { fail("ColId", RULE_GrantTarget_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_INLINE_SCHEMA_CI) { fail("SchemaKW", RULE_GrantTarget_KIND); break; } }
                    advance();
                    if (peek() != KIND_COLID) { fail("ColId", RULE_GrantTarget_KIND); break; }
                    advance();
                    // zero-or-more: rep_2
                    while (true) {
                        int savedPos_rep_2 = pos;
                        int savedNodes_rep_2 = cst.currentNodeCount();
                        boolean iterOk_rep_2 = false;
                        do {
                            if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_GrantTarget_KIND); break; }
                            advance();
                            if (peek() != KIND_COLID) { fail("ColId", RULE_GrantTarget_KIND); break; }
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
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_SEQUENCE_CI) { fail("SequenceKW", RULE_GrantTarget_KIND); break; } }
                    advance();
                    if (!parseQualifiedNameList(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_TYPE_CI) { fail("TypeKW", RULE_GrantTarget_KIND); break; } }
                    advance();
                    if (!parseQualifiedNameList(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    // optional: opt_3
                    {
                        int savedPos_opt_3 = pos;
                        int savedNodes_opt_3 = cst.currentNodeCount();
                        boolean optOk_opt_3 = false;
                        do {
                            { int __k = peek(); if (__k != KIND_INLINE_TABLE_CI) { fail("TableKW", RULE_GrantTarget_KIND); break; } }
                            advance();
                            optOk_opt_3 = true;
                        } while (false);
                        if (!optOk_opt_3) {
                            pos = savedPos_opt_3;
                            cst.truncate(savedNodes_opt_3);
                        }
                    }
                    if (!parseQualifiedNameList(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_GrantTarget_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseGranteeList(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_GranteeList_KIND, firstTok, parent);
        if (peek() != KIND_GRANTEE) { fail("Grantee", RULE_GranteeList_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_GranteeList_KIND); break; }
                advance();
                if (peek() != KIND_GRANTEE) { fail("Grantee", RULE_GranteeList_KIND); break; }
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

    private boolean parseAlterDefaultPrivilegesPassthrough(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_AlterDefaultPrivilegesPassthrough_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_DEFAULT_CI) { fail("DefaultKW", RULE_AlterDefaultPrivilegesPassthrough_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_INLINE_PRIVILEGES_CI) { fail("PrivilegesKW", RULE_AlterDefaultPrivilegesPassthrough_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseRestOfStatement(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseCreateFunctionPassthrough(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_CreateFunctionPassthrough_KIND, firstTok, parent);
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                { int __k = peek(); if (__k != KIND_INLINE_OR_CI) { fail("OrKW", RULE_CreateFunctionPassthrough_KIND); break; } }
                advance();
                { int __k = peek(); if (__k != KIND_INLINE_REPLACE_CI) { fail("ReplaceKW", RULE_CreateFunctionPassthrough_KIND); break; } }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        // choice: alt_1
        {
            int savedPos_alt_1 = pos;
            int savedNodes_alt_1 = cst.currentNodeCount();
            boolean matched_alt_1 = false;
            boolean cutHit_alt_1 = false;
            if (!matched_alt_1 && !cutHit_alt_1) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_FUNCTION_CI) { fail("FunctionKW", RULE_CreateFunctionPassthrough_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_PROCEDURE_CI) { fail("ProcedureKW", RULE_CreateFunctionPassthrough_KIND); break; } }
                    advance();
                    matched_alt_1 = true;
                } while (false);
                if (!matched_alt_1) {
                    pos = savedPos_alt_1;
                    cst.truncate(savedNodes_alt_1);
                }
            }
            if (!matched_alt_1) { fail("<choice>", RULE_CreateFunctionPassthrough_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        if (!parseRestOfStatement(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseCreateTriggerPassthrough(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_CreateTriggerPassthrough_KIND, firstTok, parent);
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                { int __k = peek(); if (__k != KIND_INLINE_CONSTRAINT_CI) { fail("ConstraintKW", RULE_CreateTriggerPassthrough_KIND); break; } }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        { int __k = peek(); if (__k != KIND_INLINE_TRIGGER_CI) { fail("TriggerKW", RULE_CreateTriggerPassthrough_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseRestOfStatement(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseDropFunctionPassthrough(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_DropFunctionPassthrough_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_FUNCTION_CI) { fail("FunctionKW", RULE_DropFunctionPassthrough_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_PROCEDURE_CI) { fail("ProcedureKW", RULE_DropFunctionPassthrough_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_AGGREGATE_CI) { fail("AggregateKW", RULE_DropFunctionPassthrough_KIND); break; } }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_DropFunctionPassthrough_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        if (!parseRestOfStatement(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseDropTriggerPassthrough(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_DropTriggerPassthrough_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_TRIGGER_CI) { fail("TriggerKW", RULE_DropTriggerPassthrough_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseRestOfStatement(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseSelectStmt(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_SelectStmt_KIND, firstTok, parent);
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
                            if (!parseWithClause(self)) { break; }
                            optOk_opt_1 = true;
                        } while (false);
                        if (!optOk_opt_1) {
                            pos = savedPos_opt_1;
                            cst.truncate(savedNodes_opt_1);
                        }
                    }
                    if (!parseSelectCore(self)) { break; }
                    // zero-or-more: rep_2
                    while (true) {
                        int savedPos_rep_2 = pos;
                        int savedNodes_rep_2 = cst.currentNodeCount();
                        boolean iterOk_rep_2 = false;
                        do {
                            if (!parseSetOp(self)) { break; }
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
                            if (!parseOrderByClause(self)) { break; }
                            optOk_opt_3 = true;
                        } while (false);
                        if (!optOk_opt_3) {
                            pos = savedPos_opt_3;
                            cst.truncate(savedNodes_opt_3);
                        }
                    }
                    // optional: opt_4
                    {
                        int savedPos_opt_4 = pos;
                        int savedNodes_opt_4 = cst.currentNodeCount();
                        boolean optOk_opt_4 = false;
                        do {
                            if (!parseLimitClause(self)) { break; }
                            optOk_opt_4 = true;
                        } while (false);
                        if (!optOk_opt_4) {
                            pos = savedPos_opt_4;
                            cst.truncate(savedNodes_opt_4);
                        }
                    }
                    // optional: opt_5
                    {
                        int savedPos_opt_5 = pos;
                        int savedNodes_opt_5 = cst.currentNodeCount();
                        boolean optOk_opt_5 = false;
                        do {
                            if (!parseOffsetClause(self)) { break; }
                            optOk_opt_5 = true;
                        } while (false);
                        if (!optOk_opt_5) {
                            pos = savedPos_opt_5;
                            cst.truncate(savedNodes_opt_5);
                        }
                    }
                    // optional: opt_6
                    {
                        int savedPos_opt_6 = pos;
                        int savedNodes_opt_6 = cst.currentNodeCount();
                        boolean optOk_opt_6 = false;
                        do {
                            if (!parseFetchClause(self)) { break; }
                            optOk_opt_6 = true;
                        } while (false);
                        if (!optOk_opt_6) {
                            pos = savedPos_opt_6;
                            cst.truncate(savedNodes_opt_6);
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
                    if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_SelectStmt_KIND); break; }
                    advance();
                    if (!parseSelectStmt(self)) { break; }
                    if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_SelectStmt_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_SelectStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseSelectCore(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_SelectCore_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_SELECT_CI) { fail("SelectKW", RULE_SelectCore_KIND); break; } }
                    advance();
                    cutHit_alt_0 = true;
                    // optional: opt_1
                    {
                        int savedPos_opt_1 = pos;
                        int savedNodes_opt_1 = cst.currentNodeCount();
                        boolean optOk_opt_1 = false;
                        do {
                            if (!parseSetQuantifier(self)) { break; }
                            optOk_opt_1 = true;
                        } while (false);
                        if (!optOk_opt_1) {
                            pos = savedPos_opt_1;
                            cst.truncate(savedNodes_opt_1);
                        }
                    }
                    if (!parseTargetList(self)) { break; }
                    // optional: opt_2
                    {
                        int savedPos_opt_2 = pos;
                        int savedNodes_opt_2 = cst.currentNodeCount();
                        boolean optOk_opt_2 = false;
                        do {
                            if (!parseIntoClause(self)) { break; }
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
                            if (!parseFromClause(self)) { break; }
                            optOk_opt_3 = true;
                        } while (false);
                        if (!optOk_opt_3) {
                            pos = savedPos_opt_3;
                            cst.truncate(savedNodes_opt_3);
                        }
                    }
                    // optional: opt_4
                    {
                        int savedPos_opt_4 = pos;
                        int savedNodes_opt_4 = cst.currentNodeCount();
                        boolean optOk_opt_4 = false;
                        do {
                            if (!parseWhereClause(self)) { break; }
                            optOk_opt_4 = true;
                        } while (false);
                        if (!optOk_opt_4) {
                            pos = savedPos_opt_4;
                            cst.truncate(savedNodes_opt_4);
                        }
                    }
                    // optional: opt_5
                    {
                        int savedPos_opt_5 = pos;
                        int savedNodes_opt_5 = cst.currentNodeCount();
                        boolean optOk_opt_5 = false;
                        do {
                            if (!parseGroupByClause(self)) { break; }
                            optOk_opt_5 = true;
                        } while (false);
                        if (!optOk_opt_5) {
                            pos = savedPos_opt_5;
                            cst.truncate(savedNodes_opt_5);
                        }
                    }
                    // optional: opt_6
                    {
                        int savedPos_opt_6 = pos;
                        int savedNodes_opt_6 = cst.currentNodeCount();
                        boolean optOk_opt_6 = false;
                        do {
                            if (!parseHavingClause(self)) { break; }
                            optOk_opt_6 = true;
                        } while (false);
                        if (!optOk_opt_6) {
                            pos = savedPos_opt_6;
                            cst.truncate(savedNodes_opt_6);
                        }
                    }
                    // optional: opt_7
                    {
                        int savedPos_opt_7 = pos;
                        int savedNodes_opt_7 = cst.currentNodeCount();
                        boolean optOk_opt_7 = false;
                        do {
                            if (!parseWindowClause(self)) { break; }
                            optOk_opt_7 = true;
                        } while (false);
                        if (!optOk_opt_7) {
                            pos = savedPos_opt_7;
                            cst.truncate(savedNodes_opt_7);
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
                    if (!parseValuesClause(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_SelectCore_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseSetQuantifier(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_SetQuantifier_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_ALL_CI) { fail("AllKW", RULE_SetQuantifier_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_DISTINCT_CI) { fail("DistinctKW", RULE_SetQuantifier_KIND); break; } }
                    advance();
                    // optional: opt_1
                    {
                        int savedPos_opt_1 = pos;
                        int savedNodes_opt_1 = cst.currentNodeCount();
                        boolean optOk_opt_1 = false;
                        do {
                            { int __k = peek(); if (__k != KIND_INLINE_ON_CI) { fail("OnKW", RULE_SetQuantifier_KIND); break; } }
                            advance();
                            if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_SetQuantifier_KIND); break; }
                            advance();
                            if (!parseExprList(self)) { break; }
                            if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_SetQuantifier_KIND); break; }
                            advance();
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
            if (!matched_alt_0) { fail("<choice>", RULE_SetQuantifier_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseTargetList(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_TargetList_KIND, firstTok, parent);
        if (!parseTargetElem(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_TargetList_KIND); break; }
                advance();
                if (!parseTargetElem(self)) { break; }
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

    private boolean parseTargetElem(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_TargetElem_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseStarExpr(self)) { break; }
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
                    // optional: opt_1
                    {
                        int savedPos_opt_1 = pos;
                        int savedNodes_opt_1 = cst.currentNodeCount();
                        boolean optOk_opt_1 = false;
                        do {
                            // choice: alt_2
                            {
                                int savedPos_alt_2 = pos;
                                int savedNodes_alt_2 = cst.currentNodeCount();
                                boolean matched_alt_2 = false;
                                boolean cutHit_alt_2 = false;
                                if (!matched_alt_2 && !cutHit_alt_2) {
                                    do {
                                        { int __k = peek(); if (__k != KIND_INLINE_AS_CI) { fail("AsKW", RULE_TargetElem_KIND); break; } }
                                        advance();
                                        if (peek() != KIND_COLLABEL) { fail("ColLabel", RULE_TargetElem_KIND); break; }
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
                                        // not-predicate: not_3
                                        {
                                            int savedPos_not_3 = pos;
                                            int savedNodes_not_3 = cst.currentNodeCount();
                                            boolean notMatched_not_3 = false;
                                            do {
                                                if (!parseClauseKeyword(self)) { break; }
                                                notMatched_not_3 = true;
                                            } while (false);
                                            pos = savedPos_not_3;
                                            cst.truncate(savedNodes_not_3);
                                            if (notMatched_not_3) { fail("!<predicate>", RULE_TargetElem_KIND); break; }
                                        }
                                        if (peek() != KIND_COLLABEL) { fail("ColLabel", RULE_TargetElem_KIND); break; }
                                        advance();
                                        matched_alt_2 = true;
                                    } while (false);
                                    if (!matched_alt_2) {
                                        pos = savedPos_alt_2;
                                        cst.truncate(savedNodes_alt_2);
                                    }
                                }
                                if (!matched_alt_2) { fail("<choice>", RULE_TargetElem_KIND); break; }
                            }
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
            if (!matched_alt_0) { fail("<choice>", RULE_TargetElem_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseStarExpr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_StarExpr_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseQualifiedName(self)) { break; }
                    if (peek() != KIND_INLINE__DOT) { fail("'.'", RULE_StarExpr_KIND); break; }
                    advance();
                    if (peek() != KIND_INLINE__STAR) { fail("'*'", RULE_StarExpr_KIND); break; }
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
                    if (peek() != KIND_INLINE__STAR) { fail("'*'", RULE_StarExpr_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_StarExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseIntoClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_IntoClause_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_INTO_CI) { fail("IntoKW", RULE_IntoClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
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
                            { int __k = peek(); if (__k != KIND_INLINE_TEMP_CI) { fail("TempKW", RULE_IntoClause_KIND); break; } }
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
                            { int __k = peek(); if (__k != KIND_INLINE_TEMPORARY_CI) { fail("TemporaryKW", RULE_IntoClause_KIND); break; } }
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
                            { int __k = peek(); if (__k != KIND_INLINE_UNLOGGED_CI) { fail("UnloggedKW", RULE_IntoClause_KIND); break; } }
                            advance();
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1) { fail("<choice>", RULE_IntoClause_KIND); break; }
                }
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        // optional: opt_2
        {
            int savedPos_opt_2 = pos;
            int savedNodes_opt_2 = cst.currentNodeCount();
            boolean optOk_opt_2 = false;
            do {
                { int __k = peek(); if (__k != KIND_INLINE_TABLE_CI) { fail("TableKW", RULE_IntoClause_KIND); break; } }
                advance();
                optOk_opt_2 = true;
            } while (false);
            if (!optOk_opt_2) {
                pos = savedPos_opt_2;
                cst.truncate(savedNodes_opt_2);
            }
        }
        if (!parseQualifiedName(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseFromClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_FromClause_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_FROM_CI) { fail("FromKW", RULE_FromClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parseFromList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseFromList(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_FromList_KIND, firstTok, parent);
        if (!parseTableRef(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_FromList_KIND); break; }
                advance();
                if (!parseTableRef(self)) { break; }
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

    private boolean parseTableRef(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_TableRef_KIND, firstTok, parent);
        if (!parseTableRefBase(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (!parseJoinClause(self)) { break; }
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

    private boolean parseTableRefBase(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_TableRefBase_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseLateralRef(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseSubqueryRef(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseFuncTableRef(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseBaseTableRef(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_TableRefBase_KIND); break; }
                    advance();
                    if (!parseJoinExpr(self)) { break; }
                    if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_TableRefBase_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_TableRefBase_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseBaseTableRef(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_BaseTableRef_KIND, firstTok, parent);
        if (!parseQualifiedName(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_INLINE__STAR) { fail("'*'", RULE_BaseTableRef_KIND); break; }
                advance();
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
                if (!parseAlias(self)) { break; }
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
                if (!parseTablesampleClause(self)) { break; }
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

    private boolean parseSubqueryRef(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_SubqueryRef_KIND, firstTok, parent);
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                { int __k = peek(); if (__k != KIND_INLINE_LATERAL_CI) { fail("LateralKW", RULE_SubqueryRef_KIND); break; } }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_SubqueryRef_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseSelectStmt(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_SubqueryRef_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseAlias(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseLateralRef(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_LateralRef_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_LATERAL_CI) { fail("LateralKW", RULE_LateralRef_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseFuncTableRef(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_LateralRef_KIND); break; }
                    advance();
                    if (!parseSelectStmt(self)) { break; }
                    if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_LateralRef_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_LateralRef_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                if (!parseAlias(self)) { break; }
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

    private boolean parseFuncTableRef(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_FuncTableRef_KIND, firstTok, parent);
        if (!parseFuncCall(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_WITHORDINALITY) { fail("WithOrdinality", RULE_FuncTableRef_KIND); break; }
                advance();
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
                if (!parseAlias(self)) { break; }
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

    private boolean parseAlias(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_Alias_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_AS_CI) { fail("AsKW", RULE_Alias_KIND); break; } }
                    advance();
                    if (peek() != KIND_COLID) { fail("ColId", RULE_Alias_KIND); break; }
                    advance();
                    // optional: opt_1
                    {
                        int savedPos_opt_1 = pos;
                        int savedNodes_opt_1 = cst.currentNodeCount();
                        boolean optOk_opt_1 = false;
                        do {
                            if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_Alias_KIND); break; }
                            advance();
                            if (!parseColumnList(self)) { break; }
                            if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_Alias_KIND); break; }
                            advance();
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
                    // not-predicate: not_2
                    {
                        int savedPos_not_2 = pos;
                        int savedNodes_not_2 = cst.currentNodeCount();
                        boolean notMatched_not_2 = false;
                        do {
                            if (!parseClauseKeyword(self)) { break; }
                            notMatched_not_2 = true;
                        } while (false);
                        pos = savedPos_not_2;
                        cst.truncate(savedNodes_not_2);
                        if (notMatched_not_2) { fail("!<predicate>", RULE_Alias_KIND); break; }
                    }
                    if (peek() != KIND_COLID) { fail("ColId", RULE_Alias_KIND); break; }
                    advance();
                    // optional: opt_3
                    {
                        int savedPos_opt_3 = pos;
                        int savedNodes_opt_3 = cst.currentNodeCount();
                        boolean optOk_opt_3 = false;
                        do {
                            if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_Alias_KIND); break; }
                            advance();
                            if (!parseColumnList(self)) { break; }
                            if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_Alias_KIND); break; }
                            advance();
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
            if (!matched_alt_0) { fail("<choice>", RULE_Alias_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseTablesampleClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_TablesampleClause_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_TABLESAMPLE_CI) { fail("TablesampleKW", RULE_TablesampleClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseFuncCall(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseJoinExpr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_JoinExpr_KIND, firstTok, parent);
        if (!parseTableRef(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (!parseJoinClause(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (!parseJoinClause(self)) { break; }
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

    private boolean parseJoinClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_JoinClause_KIND, firstTok, parent);
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
                            { int __k = peek(); if (__k != KIND_INLINE_NATURAL_CI) { fail("NaturalKW", RULE_JoinClause_KIND); break; } }
                            advance();
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
                            if (peek() != KIND_JOINTYPE) { fail("JoinType", RULE_JoinClause_KIND); break; }
                            advance();
                            optOk_opt_2 = true;
                        } while (false);
                        if (!optOk_opt_2) {
                            pos = savedPos_opt_2;
                            cst.truncate(savedNodes_opt_2);
                        }
                    }
                    { int __k = peek(); if (__k != KIND_INLINE_JOIN_CI) { fail("JoinKW", RULE_JoinClause_KIND); break; } }
                    advance();
                    cutHit_alt_0 = true;
                    if (!parseTableRefBase(self)) { break; }
                    // optional: opt_3
                    {
                        int savedPos_opt_3 = pos;
                        int savedNodes_opt_3 = cst.currentNodeCount();
                        boolean optOk_opt_3 = false;
                        do {
                            if (!parseJoinQual(self)) { break; }
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
                    { int __k = peek(); if (__k != KIND_INLINE_CROSS_CI) { fail("CrossKW", RULE_JoinClause_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_INLINE_JOIN_CI) { fail("JoinKW", RULE_JoinClause_KIND); break; } }
                    advance();
                    cutHit_alt_0 = true;
                    if (!parseTableRefBase(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_NATURAL_CI) { fail("NaturalKW", RULE_JoinClause_KIND); break; } }
                    advance();
                    // optional: opt_4
                    {
                        int savedPos_opt_4 = pos;
                        int savedNodes_opt_4 = cst.currentNodeCount();
                        boolean optOk_opt_4 = false;
                        do {
                            if (peek() != KIND_JOINTYPE) { fail("JoinType", RULE_JoinClause_KIND); break; }
                            advance();
                            optOk_opt_4 = true;
                        } while (false);
                        if (!optOk_opt_4) {
                            pos = savedPos_opt_4;
                            cst.truncate(savedNodes_opt_4);
                        }
                    }
                    { int __k = peek(); if (__k != KIND_INLINE_JOIN_CI) { fail("JoinKW", RULE_JoinClause_KIND); break; } }
                    advance();
                    cutHit_alt_0 = true;
                    if (!parseTableRefBase(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_JoinClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseJoinQual(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_JoinQual_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_ON_CI) { fail("OnKW", RULE_JoinQual_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_USING_CI) { fail("UsingKW", RULE_JoinQual_KIND); break; } }
                    advance();
                    if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_JoinQual_KIND); break; }
                    advance();
                    if (!parseColumnList(self)) { break; }
                    if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_JoinQual_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_JoinQual_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseWhereClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_WhereClause_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_WHERE_CI) { fail("WhereKW", RULE_WhereClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parseExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseGroupByClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_GroupByClause_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_GROUP_CI) { fail("GroupKW", RULE_GroupByClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_INLINE_BY_CI) { fail("ByKW", RULE_GroupByClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_ALL_CI) { fail("AllKW", RULE_GroupByClause_KIND); break; } }
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
                    if (!parseGroupByList(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_GroupByClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseGroupByList(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_GroupByList_KIND, firstTok, parent);
        if (!parseGroupByElem(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_GroupByList_KIND); break; }
                advance();
                if (!parseGroupByElem(self)) { break; }
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

    private boolean parseGroupByElem(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_GroupByElem_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_ROLLUP_CI) { fail("RollupKW", RULE_GroupByElem_KIND); break; } }
                    advance();
                    if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_GroupByElem_KIND); break; }
                    advance();
                    if (!parseExprList(self)) { break; }
                    if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_GroupByElem_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_INLINE_CUBE_CI) { fail("CubeKW", RULE_GroupByElem_KIND); break; } }
                    advance();
                    if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_GroupByElem_KIND); break; }
                    advance();
                    if (!parseExprList(self)) { break; }
                    if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_GroupByElem_KIND); break; }
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
                    if (peek() != KIND_GROUPINGSETSKW) { fail("GroupingSetsKW", RULE_GroupByElem_KIND); break; }
                    advance();
                    if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_GroupByElem_KIND); break; }
                    advance();
                    if (!parseGroupByList(self)) { break; }
                    if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_GroupByElem_KIND); break; }
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
                    if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_GroupByElem_KIND); break; }
                    advance();
                    if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_GroupByElem_KIND); break; }
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
            if (!matched_alt_0) { fail("<choice>", RULE_GroupByElem_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseHavingClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_HavingClause_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_HAVING_CI) { fail("HavingKW", RULE_HavingClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parseExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseWindowClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_WindowClause_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_WINDOW_CI) { fail("WindowKW", RULE_WindowClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parseWindowDefList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseWindowDefList(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_WindowDefList_KIND, firstTok, parent);
        if (!parseWindowDef(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_WindowDefList_KIND); break; }
                advance();
                if (!parseWindowDef(self)) { break; }
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

    private boolean parseWindowDef(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_WindowDef_KIND, firstTok, parent);
        if (peek() != KIND_COLID) { fail("ColId", RULE_WindowDef_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        { int __k = peek(); if (__k != KIND_INLINE_AS_CI) { fail("AsKW", RULE_WindowDef_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_WindowDef_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseWindowSpec(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_WindowDef_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseWindowSpec(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_WindowSpec_KIND, firstTok, parent);
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_WINDOWNAME) { fail("WindowName", RULE_WindowSpec_KIND); break; }
                advance();
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
                if (!parsePartitionClause(self)) { break; }
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
                if (!parseOrderByClause(self)) { break; }
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
                if (!parseFrameClause(self)) { break; }
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

    private boolean parsePartitionClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_PartitionClause_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_PARTITION_CI) { fail("PartitionKW", RULE_PartitionClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_INLINE_BY_CI) { fail("ByKW", RULE_PartitionClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parseExprList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseFrameClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_FrameClause_KIND, firstTok, parent);
        if (peek() != KIND_FRAMETYPE) { fail("FrameType", RULE_FrameClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseFrameExtent(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_FRAMEEXCLUSION) { fail("FrameExclusion", RULE_FrameClause_KIND); break; }
                advance();
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

    private boolean parseFrameExtent(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_FrameExtent_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_BETWEEN_CI) { fail("BetweenKW", RULE_FrameExtent_KIND); break; } }
                    advance();
                    if (!parseFrameBound(self)) { break; }
                    { int __k = peek(); if (__k != KIND_INLINE_AND_CI) { fail("AndKW", RULE_FrameExtent_KIND); break; } }
                    advance();
                    if (!parseFrameBound(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseFrameBound(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_FrameExtent_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseFrameBound(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_FrameBound_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_UNBOUNDED_CI) { fail("UnboundedKW", RULE_FrameBound_KIND); break; } }
                    advance();
                    // choice: alt_1
                    {
                        int savedPos_alt_1 = pos;
                        int savedNodes_alt_1 = cst.currentNodeCount();
                        boolean matched_alt_1 = false;
                        boolean cutHit_alt_1 = false;
                        if (!matched_alt_1 && !cutHit_alt_1) {
                            do {
                                { int __k = peek(); if (__k != KIND_INLINE_PRECEDING_CI) { fail("PrecedingKW", RULE_FrameBound_KIND); break; } }
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
                                { int __k = peek(); if (__k != KIND_INLINE_FOLLOWING_CI) { fail("FollowingKW", RULE_FrameBound_KIND); break; } }
                                advance();
                                matched_alt_1 = true;
                            } while (false);
                            if (!matched_alt_1) {
                                pos = savedPos_alt_1;
                                cst.truncate(savedNodes_alt_1);
                            }
                        }
                        if (!matched_alt_1) { fail("<choice>", RULE_FrameBound_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_INLINE_CURRENT_CI) { fail("CurrentKW", RULE_FrameBound_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_INLINE_ROW_CI) { fail("RowKW", RULE_FrameBound_KIND); break; } }
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
                    // choice: alt_2
                    {
                        int savedPos_alt_2 = pos;
                        int savedNodes_alt_2 = cst.currentNodeCount();
                        boolean matched_alt_2 = false;
                        boolean cutHit_alt_2 = false;
                        if (!matched_alt_2 && !cutHit_alt_2) {
                            do {
                                { int __k = peek(); if (__k != KIND_INLINE_PRECEDING_CI) { fail("PrecedingKW", RULE_FrameBound_KIND); break; } }
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
                                { int __k = peek(); if (__k != KIND_INLINE_FOLLOWING_CI) { fail("FollowingKW", RULE_FrameBound_KIND); break; } }
                                advance();
                                matched_alt_2 = true;
                            } while (false);
                            if (!matched_alt_2) {
                                pos = savedPos_alt_2;
                                cst.truncate(savedNodes_alt_2);
                            }
                        }
                        if (!matched_alt_2) { fail("<choice>", RULE_FrameBound_KIND); break; }
                    }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_FrameBound_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseWithClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_WithClause_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_WITH_CI) { fail("WithKW", RULE_WithClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                { int __k = peek(); if (__k != KIND_INLINE_RECURSIVE_CI) { fail("RecursiveKW", RULE_WithClause_KIND); break; } }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (!parseCteList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseCteList(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_CteList_KIND, firstTok, parent);
        if (!parseCteDef(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_CteList_KIND); break; }
                advance();
                if (!parseCteDef(self)) { break; }
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

    private boolean parseCteDef(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_CteDef_KIND, firstTok, parent);
        if (peek() != KIND_COLID) { fail("ColId", RULE_CteDef_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_CteDef_KIND); break; }
                advance();
                if (!parseColumnList(self)) { break; }
                if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_CteDef_KIND); break; }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        { int __k = peek(); if (__k != KIND_INLINE_AS_CI) { fail("AsKW", RULE_CteDef_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                // optional: opt_2
                {
                    int savedPos_opt_2 = pos;
                    int savedNodes_opt_2 = cst.currentNodeCount();
                    boolean optOk_opt_2 = false;
                    do {
                        { int __k = peek(); if (__k != KIND_INLINE_NOT_CI) { fail("NotKW", RULE_CteDef_KIND); break; } }
                        advance();
                        optOk_opt_2 = true;
                    } while (false);
                    if (!optOk_opt_2) {
                        pos = savedPos_opt_2;
                        cst.truncate(savedNodes_opt_2);
                    }
                }
                { int __k = peek(); if (__k != KIND_INLINE_MATERIALIZED_CI) { fail("MaterializedKW", RULE_CteDef_KIND); break; } }
                advance();
                optOk_opt_1 = true;
            } while (false);
            if (!optOk_opt_1) {
                pos = savedPos_opt_1;
                cst.truncate(savedNodes_opt_1);
            }
        }
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_CteDef_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseDmlStatement(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_CteDef_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseSetOp(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_SetOp_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_UNION_CI) { fail("UnionKW", RULE_SetOp_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_INTERSECT_CI) { fail("IntersectKW", RULE_SetOp_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_EXCEPT_CI) { fail("ExceptKW", RULE_SetOp_KIND); break; } }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_SetOp_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                // choice: alt_2
                {
                    int savedPos_alt_2 = pos;
                    int savedNodes_alt_2 = cst.currentNodeCount();
                    boolean matched_alt_2 = false;
                    boolean cutHit_alt_2 = false;
                    if (!matched_alt_2 && !cutHit_alt_2) {
                        do {
                            { int __k = peek(); if (__k != KIND_INLINE_ALL_CI) { fail("AllKW", RULE_SetOp_KIND); break; } }
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
                            { int __k = peek(); if (__k != KIND_INLINE_DISTINCT_CI) { fail("DistinctKW", RULE_SetOp_KIND); break; } }
                            advance();
                            matched_alt_2 = true;
                        } while (false);
                        if (!matched_alt_2) {
                            pos = savedPos_alt_2;
                            cst.truncate(savedNodes_alt_2);
                        }
                    }
                    if (!matched_alt_2) { fail("<choice>", RULE_SetOp_KIND); break; }
                }
                optOk_opt_1 = true;
            } while (false);
            if (!optOk_opt_1) {
                pos = savedPos_opt_1;
                cst.truncate(savedNodes_opt_1);
            }
        }
        if (!parseSelectCore(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseOrderByClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_OrderByClause_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_ORDER_CI) { fail("OrderKW", RULE_OrderByClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_INLINE_BY_CI) { fail("ByKW", RULE_OrderByClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parseOrderByList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseOrderByList(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_OrderByList_KIND, firstTok, parent);
        if (!parseOrderByItem(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_OrderByList_KIND); break; }
                advance();
                if (!parseOrderByItem(self)) { break; }
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

    private boolean parseOrderByItem(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_OrderByItem_KIND, firstTok, parent);
        if (!parseExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_ORDERSPEC) { fail("OrderSpec", RULE_OrderByItem_KIND); break; }
                advance();
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
                if (peek() != KIND_NULLSORDER) { fail("NullsOrder", RULE_OrderByItem_KIND); break; }
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

    private boolean parseLimitClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_LimitClause_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_LIMIT_CI) { fail("LimitKW", RULE_LimitClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_ALL_CI) { fail("AllKW", RULE_LimitClause_KIND); break; } }
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
            if (!matched_alt_0) { fail("<choice>", RULE_LimitClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseOffsetClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_OffsetClause_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_OFFSET_CI) { fail("OffsetKW", RULE_OffsetClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parseExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
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
                            { int __k = peek(); if (__k != KIND_INLINE_ROW_CI) { fail("RowKW", RULE_OffsetClause_KIND); break; } }
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
                            { int __k = peek(); if (__k != KIND_INLINE_ROWS_CI) { fail("RowsKW", RULE_OffsetClause_KIND); break; } }
                            advance();
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1) { fail("<choice>", RULE_OffsetClause_KIND); break; }
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

    private boolean parseFetchClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_FetchClause_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_FETCH_CI) { fail("FetchKW", RULE_FetchClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_FIRST_CI) { fail("FirstKW", RULE_FetchClause_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_NEXT_CI) { fail("NextKW", RULE_FetchClause_KIND); break; } }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_FetchClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                // choice: alt_2
                {
                    int savedPos_alt_2 = pos;
                    int savedNodes_alt_2 = cst.currentNodeCount();
                    boolean matched_alt_2 = false;
                    boolean cutHit_alt_2 = false;
                    if (!matched_alt_2 && !cutHit_alt_2) {
                        do {
                            if (!parseExpr(self)) { break; }
                            matched_alt_2 = true;
                        } while (false);
                        if (!matched_alt_2) {
                            pos = savedPos_alt_2;
                            cst.truncate(savedNodes_alt_2);
                        }
                    }
                    if (!matched_alt_2 && !cutHit_alt_2) {
                        do {
                            if (peek() != KIND_NUMERICLITERAL) { fail("NumericLiteral", RULE_FetchClause_KIND); break; }
                            advance();
                            matched_alt_2 = true;
                        } while (false);
                        if (!matched_alt_2) {
                            pos = savedPos_alt_2;
                            cst.truncate(savedNodes_alt_2);
                        }
                    }
                    if (!matched_alt_2) { fail("<choice>", RULE_FetchClause_KIND); break; }
                }
                optOk_opt_1 = true;
            } while (false);
            if (!optOk_opt_1) {
                pos = savedPos_opt_1;
                cst.truncate(savedNodes_opt_1);
            }
        }
        // choice: alt_3
        {
            int savedPos_alt_3 = pos;
            int savedNodes_alt_3 = cst.currentNodeCount();
            boolean matched_alt_3 = false;
            boolean cutHit_alt_3 = false;
            if (!matched_alt_3 && !cutHit_alt_3) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_ROW_CI) { fail("RowKW", RULE_FetchClause_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_ROWS_CI) { fail("RowsKW", RULE_FetchClause_KIND); break; } }
                    advance();
                    matched_alt_3 = true;
                } while (false);
                if (!matched_alt_3) {
                    pos = savedPos_alt_3;
                    cst.truncate(savedNodes_alt_3);
                }
            }
            if (!matched_alt_3) { fail("<choice>", RULE_FetchClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        // choice: alt_4
        {
            int savedPos_alt_4 = pos;
            int savedNodes_alt_4 = cst.currentNodeCount();
            boolean matched_alt_4 = false;
            boolean cutHit_alt_4 = false;
            if (!matched_alt_4 && !cutHit_alt_4) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_ONLY_CI) { fail("OnlyKW", RULE_FetchClause_KIND); break; } }
                    advance();
                    matched_alt_4 = true;
                } while (false);
                if (!matched_alt_4) {
                    pos = savedPos_alt_4;
                    cst.truncate(savedNodes_alt_4);
                }
            }
            if (!matched_alt_4 && !cutHit_alt_4) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_WITH_CI) { fail("WithKW", RULE_FetchClause_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_INLINE_TIES_CI) { fail("TiesKW", RULE_FetchClause_KIND); break; } }
                    advance();
                    matched_alt_4 = true;
                } while (false);
                if (!matched_alt_4) {
                    pos = savedPos_alt_4;
                    cst.truncate(savedNodes_alt_4);
                }
            }
            if (!matched_alt_4) { fail("<choice>", RULE_FetchClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseInsertStmt(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_InsertStmt_KIND, firstTok, parent);
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parseWithClause(self)) { break; }
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        { int __k = peek(); if (__k != KIND_INLINE_INSERT_CI) { fail("InsertKW", RULE_InsertStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_INLINE_INTO_CI) { fail("IntoKW", RULE_InsertStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parseQualifiedName(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                if (!parseAlias(self)) { break; }
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
                if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_InsertStmt_KIND); break; }
                advance();
                if (!parseColumnList(self)) { break; }
                if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_InsertStmt_KIND); break; }
                advance();
                optOk_opt_2 = true;
            } while (false);
            if (!optOk_opt_2) {
                pos = savedPos_opt_2;
                cst.truncate(savedNodes_opt_2);
            }
        }
        if (!parseInsertSource(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_3
        {
            int savedPos_opt_3 = pos;
            int savedNodes_opt_3 = cst.currentNodeCount();
            boolean optOk_opt_3 = false;
            do {
                if (!parseOnConflictClause(self)) { break; }
                optOk_opt_3 = true;
            } while (false);
            if (!optOk_opt_3) {
                pos = savedPos_opt_3;
                cst.truncate(savedNodes_opt_3);
            }
        }
        // optional: opt_4
        {
            int savedPos_opt_4 = pos;
            int savedNodes_opt_4 = cst.currentNodeCount();
            boolean optOk_opt_4 = false;
            do {
                if (!parseReturningClause(self)) { break; }
                optOk_opt_4 = true;
            } while (false);
            if (!optOk_opt_4) {
                pos = savedPos_opt_4;
                cst.truncate(savedNodes_opt_4);
            }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseInsertSource(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_InsertSource_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_DEFAULT_CI) { fail("DefaultKW", RULE_InsertSource_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_INLINE_VALUES_CI) { fail("ValuesKW", RULE_InsertSource_KIND); break; } }
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
                    if (!parseSelectStmt(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseValuesClause(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_InsertSource_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseValuesClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ValuesClause_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_VALUES_CI) { fail("ValuesKW", RULE_ValuesClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parseValueRowList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseValueRowList(int parent) {
        if (peek() != KIND_INLINE__LPAREN) { fail("ValueRowList", RULE_ValueRowList_KIND); return false; }
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ValueRowList_KIND, firstTok, parent);
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_ValueRowList_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseExprOrDefaultList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_ValueRowList_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_ValueRowList_KIND); break; }
                advance();
                if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_ValueRowList_KIND); break; }
                advance();
                if (!parseExprOrDefaultList(self)) { break; }
                if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_ValueRowList_KIND); break; }
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

    private boolean parseExprOrDefaultList(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ExprOrDefaultList_KIND, firstTok, parent);
        if (!parseExprOrDefault(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_ExprOrDefaultList_KIND); break; }
                advance();
                if (!parseExprOrDefault(self)) { break; }
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

    private boolean parseExprOrDefault(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ExprOrDefault_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_DEFAULT_CI) { fail("DefaultKW", RULE_ExprOrDefault_KIND); break; } }
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
            if (!matched_alt_0) { fail("<choice>", RULE_ExprOrDefault_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseOnConflictClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_OnConflictClause_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_ON_CI) { fail("OnKW", RULE_OnConflictClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_INLINE_CONFLICT_CI) { fail("ConflictKW", RULE_OnConflictClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parseConflictTarget(self)) { break; }
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (!parseConflictAction(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseConflictTarget(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ConflictTarget_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_ConflictTarget_KIND); break; }
                    advance();
                    if (!parseIndexElemList(self)) { break; }
                    if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_ConflictTarget_KIND); break; }
                    advance();
                    // optional: opt_1
                    {
                        int savedPos_opt_1 = pos;
                        int savedNodes_opt_1 = cst.currentNodeCount();
                        boolean optOk_opt_1 = false;
                        do {
                            if (!parseWhereClause(self)) { break; }
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
                    { int __k = peek(); if (__k != KIND_INLINE_ON_CI) { fail("OnKW", RULE_ConflictTarget_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_INLINE_CONSTRAINT_CI) { fail("ConstraintKW", RULE_ConflictTarget_KIND); break; } }
                    advance();
                    if (peek() != KIND_COLID) { fail("ColId", RULE_ConflictTarget_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_ConflictTarget_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseConflictAction(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ConflictAction_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_DO_CI) { fail("DoKW", RULE_ConflictAction_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_INLINE_NOTHING_CI) { fail("NothingKW", RULE_ConflictAction_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_DO_CI) { fail("DoKW", RULE_ConflictAction_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_INLINE_UPDATE_CI) { fail("UpdateKW", RULE_ConflictAction_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_INLINE_SET_CI) { fail("SetKW", RULE_ConflictAction_KIND); break; } }
                    advance();
                    if (!parseUpdateSetList(self)) { break; }
                    // optional: opt_1
                    {
                        int savedPos_opt_1 = pos;
                        int savedNodes_opt_1 = cst.currentNodeCount();
                        boolean optOk_opt_1 = false;
                        do {
                            if (!parseWhereClause(self)) { break; }
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
            if (!matched_alt_0) { fail("<choice>", RULE_ConflictAction_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseReturningClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ReturningClause_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_RETURNING_CI) { fail("ReturningKW", RULE_ReturningClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parseTargetList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseUpdateStmt(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_UpdateStmt_KIND, firstTok, parent);
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parseWithClause(self)) { break; }
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        { int __k = peek(); if (__k != KIND_INLINE_UPDATE_CI) { fail("UpdateKW", RULE_UpdateStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                { int __k = peek(); if (__k != KIND_INLINE_ONLY_CI) { fail("OnlyKW", RULE_UpdateStmt_KIND); break; } }
                advance();
                optOk_opt_1 = true;
            } while (false);
            if (!optOk_opt_1) {
                pos = savedPos_opt_1;
                cst.truncate(savedNodes_opt_1);
            }
        }
        if (!parseQualifiedName(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_2
        {
            int savedPos_opt_2 = pos;
            int savedNodes_opt_2 = cst.currentNodeCount();
            boolean optOk_opt_2 = false;
            do {
                if (peek() != KIND_INLINE__STAR) { fail("'*'", RULE_UpdateStmt_KIND); break; }
                advance();
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
                if (!parseAlias(self)) { break; }
                optOk_opt_3 = true;
            } while (false);
            if (!optOk_opt_3) {
                pos = savedPos_opt_3;
                cst.truncate(savedNodes_opt_3);
            }
        }
        { int __k = peek(); if (__k != KIND_INLINE_SET_CI) { fail("SetKW", RULE_UpdateStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseUpdateSetList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_4
        {
            int savedPos_opt_4 = pos;
            int savedNodes_opt_4 = cst.currentNodeCount();
            boolean optOk_opt_4 = false;
            do {
                if (!parseFromClause(self)) { break; }
                optOk_opt_4 = true;
            } while (false);
            if (!optOk_opt_4) {
                pos = savedPos_opt_4;
                cst.truncate(savedNodes_opt_4);
            }
        }
        // optional: opt_5
        {
            int savedPos_opt_5 = pos;
            int savedNodes_opt_5 = cst.currentNodeCount();
            boolean optOk_opt_5 = false;
            do {
                if (!parseWhereClause(self)) { break; }
                optOk_opt_5 = true;
            } while (false);
            if (!optOk_opt_5) {
                pos = savedPos_opt_5;
                cst.truncate(savedNodes_opt_5);
            }
        }
        // optional: opt_6
        {
            int savedPos_opt_6 = pos;
            int savedNodes_opt_6 = cst.currentNodeCount();
            boolean optOk_opt_6 = false;
            do {
                if (!parseReturningClause(self)) { break; }
                optOk_opt_6 = true;
            } while (false);
            if (!optOk_opt_6) {
                pos = savedPos_opt_6;
                cst.truncate(savedNodes_opt_6);
            }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseUpdateSetList(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_UpdateSetList_KIND, firstTok, parent);
        if (!parseUpdateSetItem(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_UpdateSetList_KIND); break; }
                advance();
                if (!parseUpdateSetItem(self)) { break; }
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

    private boolean parseUpdateSetItem(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_UpdateSetItem_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_COLID) { fail("ColId", RULE_UpdateSetItem_KIND); break; }
                    advance();
                    if (peek() != KIND_INLINE__EQ) { fail("'='", RULE_UpdateSetItem_KIND); break; }
                    advance();
                    if (!parseExprOrDefault(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_UpdateSetItem_KIND); break; }
                    advance();
                    if (!parseColumnList(self)) { break; }
                    if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_UpdateSetItem_KIND); break; }
                    advance();
                    if (peek() != KIND_INLINE__EQ) { fail("'='", RULE_UpdateSetItem_KIND); break; }
                    advance();
                    // choice: alt_1
                    {
                        int savedPos_alt_1 = pos;
                        int savedNodes_alt_1 = cst.currentNodeCount();
                        boolean matched_alt_1 = false;
                        boolean cutHit_alt_1 = false;
                        if (!matched_alt_1 && !cutHit_alt_1) {
                            do {
                                if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_UpdateSetItem_KIND); break; }
                                advance();
                                if (!parseExprOrDefaultList(self)) { break; }
                                if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_UpdateSetItem_KIND); break; }
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
                                if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_UpdateSetItem_KIND); break; }
                                advance();
                                if (!parseSelectStmt(self)) { break; }
                                if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_UpdateSetItem_KIND); break; }
                                advance();
                                matched_alt_1 = true;
                            } while (false);
                            if (!matched_alt_1) {
                                pos = savedPos_alt_1;
                                cst.truncate(savedNodes_alt_1);
                            }
                        }
                        if (!matched_alt_1) { fail("<choice>", RULE_UpdateSetItem_KIND); break; }
                    }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_UpdateSetItem_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseDeleteStmt(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_DeleteStmt_KIND, firstTok, parent);
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parseWithClause(self)) { break; }
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        { int __k = peek(); if (__k != KIND_INLINE_DELETE_CI) { fail("DeleteKW", RULE_DeleteStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_INLINE_FROM_CI) { fail("FromKW", RULE_DeleteStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                { int __k = peek(); if (__k != KIND_INLINE_ONLY_CI) { fail("OnlyKW", RULE_DeleteStmt_KIND); break; } }
                advance();
                optOk_opt_1 = true;
            } while (false);
            if (!optOk_opt_1) {
                pos = savedPos_opt_1;
                cst.truncate(savedNodes_opt_1);
            }
        }
        if (!parseQualifiedName(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_2
        {
            int savedPos_opt_2 = pos;
            int savedNodes_opt_2 = cst.currentNodeCount();
            boolean optOk_opt_2 = false;
            do {
                if (peek() != KIND_INLINE__STAR) { fail("'*'", RULE_DeleteStmt_KIND); break; }
                advance();
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
                if (!parseAlias(self)) { break; }
                optOk_opt_3 = true;
            } while (false);
            if (!optOk_opt_3) {
                pos = savedPos_opt_3;
                cst.truncate(savedNodes_opt_3);
            }
        }
        // optional: opt_4
        {
            int savedPos_opt_4 = pos;
            int savedNodes_opt_4 = cst.currentNodeCount();
            boolean optOk_opt_4 = false;
            do {
                if (!parseUsingClauseDelete(self)) { break; }
                optOk_opt_4 = true;
            } while (false);
            if (!optOk_opt_4) {
                pos = savedPos_opt_4;
                cst.truncate(savedNodes_opt_4);
            }
        }
        // optional: opt_5
        {
            int savedPos_opt_5 = pos;
            int savedNodes_opt_5 = cst.currentNodeCount();
            boolean optOk_opt_5 = false;
            do {
                if (!parseWhereClause(self)) { break; }
                optOk_opt_5 = true;
            } while (false);
            if (!optOk_opt_5) {
                pos = savedPos_opt_5;
                cst.truncate(savedNodes_opt_5);
            }
        }
        // optional: opt_6
        {
            int savedPos_opt_6 = pos;
            int savedNodes_opt_6 = cst.currentNodeCount();
            boolean optOk_opt_6 = false;
            do {
                if (!parseReturningClause(self)) { break; }
                optOk_opt_6 = true;
            } while (false);
            if (!optOk_opt_6) {
                pos = savedPos_opt_6;
                cst.truncate(savedNodes_opt_6);
            }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseUsingClauseDelete(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_UsingClauseDelete_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_USING_CI) { fail("UsingKW", RULE_UsingClauseDelete_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parseFromList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parsePassthroughStatement(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_PassthroughStatement_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseTransactionStmt(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseSessionStmt(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseUtilityStmt(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseTruncateStmt(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseExplainStmt(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseCopyStmt(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseRefreshMatViewStmt(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_PassthroughStatement_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseTransactionStmt(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_TransactionStmt_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_BEGIN_CI) { fail("BeginKW", RULE_TransactionStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_COMMIT_CI) { fail("CommitKW", RULE_TransactionStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_ROLLBACK_CI) { fail("RollbackKW", RULE_TransactionStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_END_CI) { fail("EndKW", RULE_TransactionStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_SAVEPOINT_CI) { fail("SavepointKW", RULE_TransactionStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_RELEASE_CI) { fail("ReleaseKW", RULE_TransactionStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_PREPARE_CI) { fail("PrepareKW", RULE_TransactionStmt_KIND); break; } }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_TransactionStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        if (!parseRestOfStatement(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseSessionStmt(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_SessionStmt_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_SET_CI) { fail("SetKW", RULE_SessionStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_SHOW_CI) { fail("ShowKW", RULE_SessionStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_RESET_CI) { fail("ResetKW", RULE_SessionStmt_KIND); break; } }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_SessionStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        if (!parseRestOfStatement(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseUtilityStmt(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_UtilityStmt_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_VACUUM_CI) { fail("VacuumKW", RULE_UtilityStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_ANALYZE_CI) { fail("AnalyzeKW", RULE_UtilityStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_REINDEX_CI) { fail("ReindexKW", RULE_UtilityStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_CLUSTER_CI) { fail("ClusterKW", RULE_UtilityStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_NOTIFY_CI) { fail("NotifyKW", RULE_UtilityStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_LISTEN_CI) { fail("ListenKW", RULE_UtilityStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_UNLISTEN_CI) { fail("UnlistenKW", RULE_UtilityStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_LOAD_CI) { fail("LoadKW", RULE_UtilityStmt_KIND); break; } }
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
                    if (peek() != KIND_SECURITYLABELKW) { fail("SecurityLabelKW", RULE_UtilityStmt_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_INLINE_DEALLOCATE_CI) { fail("DeallocateKW", RULE_UtilityStmt_KIND); break; } }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_UtilityStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        if (!parseRestOfStatement(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseTruncateStmt(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_TruncateStmt_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_TRUNCATE_CI) { fail("TruncateKW", RULE_TruncateStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseRestOfStatement(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseExplainStmt(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ExplainStmt_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_EXPLAIN_CI) { fail("ExplainKW", RULE_ExplainStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseRestOfStatement(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseCopyStmt(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_CopyStmt_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_COPY_CI) { fail("CopyKW", RULE_CopyStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseRestOfStatement(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseRefreshMatViewStmt(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_RefreshMatViewStmt_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_REFRESH_CI) { fail("RefreshKW", RULE_RefreshMatViewStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_INLINE_MATERIALIZED_CI) { fail("MaterializedKW", RULE_RefreshMatViewStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_INLINE_VIEW_CI) { fail("ViewKW", RULE_RefreshMatViewStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseRestOfStatement(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseRestOfStatement(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_RestOfStatement_KIND, firstTok, parent);
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
                            if (!parseDollarString(self)) { break; }
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1 && !cutHit_alt_1) {
                        do {
                            if (peek() != KIND_BASICSTRING) { fail("BasicString", RULE_RestOfStatement_KIND); break; }
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
                            if (peek() != KIND_ESCAPESTRING) { fail("EscapeString", RULE_RestOfStatement_KIND); break; }
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
                            // not-predicate: not_2
                            {
                                int savedPos_not_2 = pos;
                                int savedNodes_not_2 = cst.currentNodeCount();
                                boolean notMatched_not_2 = false;
                                do {
                                    if (peek() != KIND_INLINE__SEMI) { fail("';'", RULE_RestOfStatement_KIND); break; }
                                    advance();
                                    notMatched_not_2 = true;
                                } while (false);
                                pos = savedPos_not_2;
                                cst.truncate(savedNodes_not_2);
                                if (notMatched_not_2) { fail("!<predicate>", RULE_RestOfStatement_KIND); break; }
                            }
                            if (peek() < 0) { fail("<any token>", RULE_RestOfStatement_KIND); break; }
                            advance();
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1) { fail("<choice>", RULE_RestOfStatement_KIND); break; }
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
        if (!parseOrExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseOrExpr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_OrExpr_KIND, firstTok, parent);
        if (!parseAndExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                { int __k = peek(); if (__k != KIND_INLINE_OR_CI) { fail("OrKW", RULE_OrExpr_KIND); break; } }
                advance();
                if (!parseAndExpr(self)) { break; }
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

    private boolean parseAndExpr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_AndExpr_KIND, firstTok, parent);
        if (!parseNotExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                { int __k = peek(); if (__k != KIND_INLINE_AND_CI) { fail("AndKW", RULE_AndExpr_KIND); break; } }
                advance();
                if (!parseNotExpr(self)) { break; }
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

    private boolean parseNotExpr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_NotExpr_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_NOT_CI) { fail("NotKW", RULE_NotExpr_KIND); break; } }
                    advance();
                    if (!parseNotExpr(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseCompareExpr(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_NotExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseCompareExpr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_CompareExpr_KIND, firstTok, parent);
        if (!parseIsExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
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
                            if (peek() != KIND_COMPAREOP) { fail("CompareOp", RULE_CompareExpr_KIND); break; }
                            advance();
                            if (!parseIsExpr(self)) { break; }
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1 && !cutHit_alt_1) {
                        do {
                            // optional: opt_2
                            {
                                int savedPos_opt_2 = pos;
                                int savedNodes_opt_2 = cst.currentNodeCount();
                                boolean optOk_opt_2 = false;
                                do {
                                    { int __k = peek(); if (__k != KIND_INLINE_NOT_CI) { fail("NotKW", RULE_CompareExpr_KIND); break; } }
                                    advance();
                                    optOk_opt_2 = true;
                                } while (false);
                                if (!optOk_opt_2) {
                                    pos = savedPos_opt_2;
                                    cst.truncate(savedNodes_opt_2);
                                }
                            }
                            if (!parseInExpr(self)) { break; }
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1 && !cutHit_alt_1) {
                        do {
                            // optional: opt_3
                            {
                                int savedPos_opt_3 = pos;
                                int savedNodes_opt_3 = cst.currentNodeCount();
                                boolean optOk_opt_3 = false;
                                do {
                                    { int __k = peek(); if (__k != KIND_INLINE_NOT_CI) { fail("NotKW", RULE_CompareExpr_KIND); break; } }
                                    advance();
                                    optOk_opt_3 = true;
                                } while (false);
                                if (!optOk_opt_3) {
                                    pos = savedPos_opt_3;
                                    cst.truncate(savedNodes_opt_3);
                                }
                            }
                            if (!parseBetweenExpr(self)) { break; }
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1 && !cutHit_alt_1) {
                        do {
                            // optional: opt_4
                            {
                                int savedPos_opt_4 = pos;
                                int savedNodes_opt_4 = cst.currentNodeCount();
                                boolean optOk_opt_4 = false;
                                do {
                                    { int __k = peek(); if (__k != KIND_INLINE_NOT_CI) { fail("NotKW", RULE_CompareExpr_KIND); break; } }
                                    advance();
                                    optOk_opt_4 = true;
                                } while (false);
                                if (!optOk_opt_4) {
                                    pos = savedPos_opt_4;
                                    cst.truncate(savedNodes_opt_4);
                                }
                            }
                            if (!parseLikeExpr(self)) { break; }
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1 && !cutHit_alt_1) {
                        do {
                            // optional: opt_5
                            {
                                int savedPos_opt_5 = pos;
                                int savedNodes_opt_5 = cst.currentNodeCount();
                                boolean optOk_opt_5 = false;
                                do {
                                    { int __k = peek(); if (__k != KIND_INLINE_NOT_CI) { fail("NotKW", RULE_CompareExpr_KIND); break; } }
                                    advance();
                                    optOk_opt_5 = true;
                                } while (false);
                                if (!optOk_opt_5) {
                                    pos = savedPos_opt_5;
                                    cst.truncate(savedNodes_opt_5);
                                }
                            }
                            if (!parseSimilarToExpr(self)) { break; }
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1 && !cutHit_alt_1) {
                        do {
                            if (!parseIsDistinctFrom(self)) { break; }
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1) { fail("<choice>", RULE_CompareExpr_KIND); break; }
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

    private boolean parseIsExpr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_IsExpr_KIND, firstTok, parent);
        if (!parseAddExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parseIsClause(self)) { break; }
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

    private boolean parseIsClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_IsClause_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_IS_CI) { fail("IsKW", RULE_IsClause_KIND); break; } }
                    advance();
                    // optional: opt_1
                    {
                        int savedPos_opt_1 = pos;
                        int savedNodes_opt_1 = cst.currentNodeCount();
                        boolean optOk_opt_1 = false;
                        do {
                            { int __k = peek(); if (__k != KIND_INLINE_NOT_CI) { fail("NotKW", RULE_IsClause_KIND); break; } }
                            advance();
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
                                { int __k = peek(); if (__k != KIND_INLINE_NULL_CI) { fail("NullKW", RULE_IsClause_KIND); break; } }
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
                                { int __k = peek(); if (__k != KIND_INLINE_TRUE_CI) { fail("TrueKW", RULE_IsClause_KIND); break; } }
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
                                { int __k = peek(); if (__k != KIND_INLINE_FALSE_CI) { fail("FalseKW", RULE_IsClause_KIND); break; } }
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
                                { int __k = peek(); if (__k != KIND_INLINE_UNKNOWN_CI) { fail("UnknownKW", RULE_IsClause_KIND); break; } }
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
                                { int __k = peek(); if (__k != KIND_INLINE_DISTINCT_CI) { fail("DistinctKW", RULE_IsClause_KIND); break; } }
                                advance();
                                { int __k = peek(); if (__k != KIND_INLINE_FROM_CI) { fail("FromKW", RULE_IsClause_KIND); break; } }
                                advance();
                                if (!parseAddExpr(self)) { break; }
                                matched_alt_2 = true;
                            } while (false);
                            if (!matched_alt_2) {
                                pos = savedPos_alt_2;
                                cst.truncate(savedNodes_alt_2);
                            }
                        }
                        if (!matched_alt_2) { fail("<choice>", RULE_IsClause_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_INLINE_ISNULL_CI) { fail("IsnullKW", RULE_IsClause_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_NOTNULL_CI) { fail("NotnullKW", RULE_IsClause_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_NOT_CI) { fail("NotKW", RULE_IsClause_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_INLINE_NULL_CI) { fail("NullKW", RULE_IsClause_KIND); break; } }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_IsClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseInExpr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_InExpr_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_IN_CI) { fail("InKW", RULE_InExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_InExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseSelectStmt(self)) { break; }
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
            if (!matched_alt_0) { fail("<choice>", RULE_InExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_InExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseBetweenExpr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_BetweenExpr_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_BETWEEN_CI) { fail("BetweenKW", RULE_BetweenExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
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
                            { int __k = peek(); if (__k != KIND_INLINE_SYMMETRIC_CI) { fail("SymmetricKW", RULE_BetweenExpr_KIND); break; } }
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
                            { int __k = peek(); if (__k != KIND_INLINE_ASYMMETRIC_CI) { fail("AsymmetricKW", RULE_BetweenExpr_KIND); break; } }
                            advance();
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1) { fail("<choice>", RULE_BetweenExpr_KIND); break; }
                }
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (!parseAddExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        { int __k = peek(); if (__k != KIND_INLINE_AND_CI) { fail("AndKW", RULE_BetweenExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseAddExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseLikeExpr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_LikeExpr_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_LIKE_CI) { fail("LikeKW", RULE_LikeExpr_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_ILIKE_CI) { fail("IlikeKW", RULE_LikeExpr_KIND); break; } }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_LikeExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        if (!parseAddExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                { int __k = peek(); if (__k != KIND_INLINE_ESCAPE_CI) { fail("EscapeKW", RULE_LikeExpr_KIND); break; } }
                advance();
                if (!parseAddExpr(self)) { break; }
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

    private boolean parseSimilarToExpr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_SimilarToExpr_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_SIMILAR_CI) { fail("SimilarKW", RULE_SimilarToExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_INLINE_TO_CI) { fail("ToKW", RULE_SimilarToExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseAddExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                { int __k = peek(); if (__k != KIND_INLINE_ESCAPE_CI) { fail("EscapeKW", RULE_SimilarToExpr_KIND); break; } }
                advance();
                if (!parseAddExpr(self)) { break; }
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

    private boolean parseIsDistinctFrom(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_IsDistinctFrom_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_IS_CI) { fail("IsKW", RULE_IsDistinctFrom_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                { int __k = peek(); if (__k != KIND_INLINE_NOT_CI) { fail("NotKW", RULE_IsDistinctFrom_KIND); break; } }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        { int __k = peek(); if (__k != KIND_INLINE_DISTINCT_CI) { fail("DistinctKW", RULE_IsDistinctFrom_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_INLINE_FROM_CI) { fail("FromKW", RULE_IsDistinctFrom_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseIsExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseAddExpr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_AddExpr_KIND, firstTok, parent);
        if (!parseMulExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
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
                            if (peek() != KIND_INLINE__PLUS) { fail("'+'", RULE_AddExpr_KIND); break; }
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
                            // not-predicate: not_2
                            {
                                int savedPos_not_2 = pos;
                                int savedNodes_not_2 = cst.currentNodeCount();
                                boolean notMatched_not_2 = false;
                                do {
                                    if (peek() != KIND_INLINE__MINUS_GT) { fail("'->'", RULE_AddExpr_KIND); break; }
                                    advance();
                                    notMatched_not_2 = true;
                                } while (false);
                                pos = savedPos_not_2;
                                cst.truncate(savedNodes_not_2);
                                if (notMatched_not_2) { fail("!<predicate>", RULE_AddExpr_KIND); break; }
                            }
                            if (peek() != KIND_INLINE__MINUS) { fail("'-'", RULE_AddExpr_KIND); break; }
                            advance();
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1) { fail("<choice>", RULE_AddExpr_KIND); break; }
                }
                if (!parseMulExpr(self)) { break; }
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

    private boolean parseMulExpr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_MulExpr_KIND, firstTok, parent);
        if (!parseUnaryExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
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
                            if (peek() != KIND_INLINE__STAR) { fail("'*'", RULE_MulExpr_KIND); break; }
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
                            if (peek() != KIND_INLINE__SLASH) { fail("'/'", RULE_MulExpr_KIND); break; }
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
                            if (peek() != KIND_INLINE__PERCENT) { fail("'%'", RULE_MulExpr_KIND); break; }
                            advance();
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1) { fail("<choice>", RULE_MulExpr_KIND); break; }
                }
                if (!parseUnaryExpr(self)) { break; }
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

    private boolean parseUnaryExpr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_UnaryExpr_KIND, firstTok, parent);
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
                                if (peek() != KIND_INLINE__PLUS) { fail("'+'", RULE_UnaryExpr_KIND); break; }
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
                                // not-predicate: not_2
                                {
                                    int savedPos_not_2 = pos;
                                    int savedNodes_not_2 = cst.currentNodeCount();
                                    boolean notMatched_not_2 = false;
                                    do {
                                        if (peek() != KIND_INLINE__MINUS_GT) { fail("'->'", RULE_UnaryExpr_KIND); break; }
                                        advance();
                                        notMatched_not_2 = true;
                                    } while (false);
                                    pos = savedPos_not_2;
                                    cst.truncate(savedNodes_not_2);
                                    if (notMatched_not_2) { fail("!<predicate>", RULE_UnaryExpr_KIND); break; }
                                }
                                if (peek() != KIND_INLINE__MINUS) { fail("'-'", RULE_UnaryExpr_KIND); break; }
                                advance();
                                matched_alt_1 = true;
                            } while (false);
                            if (!matched_alt_1) {
                                pos = savedPos_alt_1;
                                cst.truncate(savedNodes_alt_1);
                            }
                        }
                        if (!matched_alt_1) { fail("<choice>", RULE_UnaryExpr_KIND); break; }
                    }
                    if (!parseUnaryExpr(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseExponentExpr(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_UnaryExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseExponentExpr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ExponentExpr_KIND, firstTok, parent);
        if (!parseConcatExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_INLINE__CARET) { fail("'^'", RULE_ExponentExpr_KIND); break; }
                advance();
                if (!parseConcatExpr(self)) { break; }
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

    private boolean parseConcatExpr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ConcatExpr_KIND, firstTok, parent);
        if (!parseArrayExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__PIPE_PIPE) { fail("'||'", RULE_ConcatExpr_KIND); break; }
                advance();
                if (!parseArrayExpr(self)) { break; }
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

    private boolean parseArrayExpr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ArrayExpr_KIND, firstTok, parent);
        if (!parseTypeCastExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__LBRACK) { fail("'['", RULE_ArrayExpr_KIND); break; }
                advance();
                if (!parseExpr(self)) { break; }
                // optional: opt_1
                {
                    int savedPos_opt_1 = pos;
                    int savedNodes_opt_1 = cst.currentNodeCount();
                    boolean optOk_opt_1 = false;
                    do {
                        if (peek() != KIND_INLINE__COLON) { fail("':'", RULE_ArrayExpr_KIND); break; }
                        advance();
                        if (!parseExpr(self)) { break; }
                        optOk_opt_1 = true;
                    } while (false);
                    if (!optOk_opt_1) {
                        pos = savedPos_opt_1;
                        cst.truncate(savedNodes_opt_1);
                    }
                }
                if (peek() != KIND_INLINE__RBRACK) { fail("']'", RULE_ArrayExpr_KIND); break; }
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

    private boolean parseTypeCastExpr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_TypeCastExpr_KIND, firstTok, parent);
        if (!parsePostfixExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__COLON_COLON) { fail("'::'", RULE_TypeCastExpr_KIND); break; }
                advance();
                if (!parseDataType(self)) { break; }
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

    private boolean parsePostfixExpr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_PostfixExpr_KIND, firstTok, parent);
        if (!parsePrimaryExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (!parsePostfixOp(self)) { break; }
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

    private boolean parsePostfixOp(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_PostfixOp_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE__DOT) { fail("'.'", RULE_PostfixOp_KIND); break; }
                    advance();
                    if (peek() != KIND_INLINE__STAR) { fail("'*'", RULE_PostfixOp_KIND); break; }
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
                    if (peek() != KIND_INLINE__DOT) { fail("'.'", RULE_PostfixOp_KIND); break; }
                    advance();
                    if (peek() != KIND_COLLABEL) { fail("ColLabel", RULE_PostfixOp_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_INLINE__MINUS_GT_GT && __k != KIND_INLINE__HASH_GT_GT && __k != KIND_INLINE__MINUS_GT && __k != KIND_INLINE__HASH_GT) { fail("JsonOp", RULE_PostfixOp_KIND); break; } }
                    advance();
                    if (!parsePrimaryExpr(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE__AT_GT && __k != KIND_INLINE__LT_AT && __k != KIND_INLINE__AMP_AMP) { fail("ArrayOverlapOp", RULE_PostfixOp_KIND); break; } }
                    advance();
                    if (!parsePrimaryExpr(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_PostfixOp_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parsePrimaryExpr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_PrimaryExpr_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseExistsExpr(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseArrayExprConstructor(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseRowExpr(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseCastExpr(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseCaseExpr(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseCoalesceExpr(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseNullIfExpr(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseGreatestLeastExpr(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseExtractExpr(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parsePositionExpr(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseSubstringExpr(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseTrimExpr(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseOverlayExpr(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseAnyAllExpr(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseSubqueryExpr(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseTypedLiteral(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseFuncCall(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
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
                    if (peek() != KIND_PARAMREF) { fail("ParamRef", RULE_PrimaryExpr_KIND); break; }
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
                    if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_PrimaryExpr_KIND); break; }
                    advance();
                    if (!parseExpr(self)) { break; }
                    if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_PrimaryExpr_KIND); break; }
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
                    if (!parseColRef(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_PrimaryExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseColRef(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ColRef_KIND, firstTok, parent);
        if (!parseQualifiedName(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseExistsExpr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ExistsExpr_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_EXISTS_CI) { fail("ExistsKW", RULE_ExistsExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_ExistsExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseSelectStmt(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_ExistsExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseSubqueryExpr(int parent) {
        if (peek() != KIND_INLINE__LPAREN) { fail("SubqueryExpr", RULE_SubqueryExpr_KIND); return false; }
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_SubqueryExpr_KIND, firstTok, parent);
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_SubqueryExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseSelectStmt(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_SubqueryExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseAnyAllExpr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_AnyAllExpr_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_ANY_CI) { fail("AnyKW", RULE_AnyAllExpr_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_ALL_CI) { fail("AllKW", RULE_AnyAllExpr_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_SOME_CI) { fail("SomeKW", RULE_AnyAllExpr_KIND); break; } }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_AnyAllExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_AnyAllExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // choice: alt_1
        {
            int savedPos_alt_1 = pos;
            int savedNodes_alt_1 = cst.currentNodeCount();
            boolean matched_alt_1 = false;
            boolean cutHit_alt_1 = false;
            if (!matched_alt_1 && !cutHit_alt_1) {
                do {
                    if (!parseSelectStmt(self)) { break; }
                    matched_alt_1 = true;
                } while (false);
                if (!matched_alt_1) {
                    pos = savedPos_alt_1;
                    cst.truncate(savedNodes_alt_1);
                }
            }
            if (!matched_alt_1 && !cutHit_alt_1) {
                do {
                    if (!parseExprList(self)) { break; }
                    matched_alt_1 = true;
                } while (false);
                if (!matched_alt_1) {
                    pos = savedPos_alt_1;
                    cst.truncate(savedNodes_alt_1);
                }
            }
            if (!matched_alt_1) { fail("<choice>", RULE_AnyAllExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_AnyAllExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseRowExpr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_RowExpr_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_ROW_CI) { fail("RowKW", RULE_RowExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_RowExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseExprList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_RowExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseArrayExprConstructor(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ArrayExprConstructor_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_ARRAY_CI) { fail("ArrayKW", RULE_ArrayExprConstructor_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE__LBRACK) { fail("'['", RULE_ArrayExprConstructor_KIND); break; }
                    advance();
                    // optional: opt_1
                    {
                        int savedPos_opt_1 = pos;
                        int savedNodes_opt_1 = cst.currentNodeCount();
                        boolean optOk_opt_1 = false;
                        do {
                            if (!parseExprList(self)) { break; }
                            optOk_opt_1 = true;
                        } while (false);
                        if (!optOk_opt_1) {
                            pos = savedPos_opt_1;
                            cst.truncate(savedNodes_opt_1);
                        }
                    }
                    if (peek() != KIND_INLINE__RBRACK) { fail("']'", RULE_ArrayExprConstructor_KIND); break; }
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
                    if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_ArrayExprConstructor_KIND); break; }
                    advance();
                    if (!parseSelectStmt(self)) { break; }
                    if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_ArrayExprConstructor_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_ArrayExprConstructor_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseCastExpr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_CastExpr_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_CAST_CI) { fail("CastKW", RULE_CastExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_CastExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        { int __k = peek(); if (__k != KIND_INLINE_AS_CI) { fail("AsKW", RULE_CastExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseDataType(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_CastExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseCaseExpr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_CaseExpr_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_CASE_CI) { fail("CaseKW", RULE_CaseExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parseExpr(self)) { break; }
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (!parseWhenClause(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_1
        while (true) {
            int savedPos_rep_1 = pos;
            int savedNodes_rep_1 = cst.currentNodeCount();
            boolean iterOk_rep_1 = false;
            do {
                if (!parseWhenClause(self)) { break; }
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
                if (!parseElseClause(self)) { break; }
                optOk_opt_2 = true;
            } while (false);
            if (!optOk_opt_2) {
                pos = savedPos_opt_2;
                cst.truncate(savedNodes_opt_2);
            }
        }
        { int __k = peek(); if (__k != KIND_INLINE_END_CI) { fail("EndKW", RULE_CaseExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseWhenClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_WhenClause_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_WHEN_CI) { fail("WhenKW", RULE_WhenClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        { int __k = peek(); if (__k != KIND_INLINE_THEN_CI) { fail("ThenKW", RULE_WhenClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseElseClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ElseClause_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_ELSE_CI) { fail("ElseKW", RULE_ElseClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseCoalesceExpr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_CoalesceExpr_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_COALESCE_CI) { fail("CoalesceKW", RULE_CoalesceExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_CoalesceExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseExprList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_CoalesceExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseNullIfExpr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_NullIfExpr_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_NULLIF_CI) { fail("NullIfKW", RULE_NullIfExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_NullIfExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_NullIfExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_NullIfExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseGreatestLeastExpr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_GreatestLeastExpr_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_GREATEST_CI) { fail("GreatestKW", RULE_GreatestLeastExpr_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_LEAST_CI) { fail("LeastKW", RULE_GreatestLeastExpr_KIND); break; } }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_GreatestLeastExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_GreatestLeastExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseExprList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_GreatestLeastExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseExtractExpr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ExtractExpr_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_EXTRACT_CI) { fail("ExtractKW", RULE_ExtractExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_ExtractExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (peek() != KIND_COLID) { fail("ColId", RULE_ExtractExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        { int __k = peek(); if (__k != KIND_INLINE_FROM_CI) { fail("FromKW", RULE_ExtractExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_ExtractExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parsePositionExpr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_PositionExpr_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_POSITION_CI) { fail("PositionKW", RULE_PositionExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_PositionExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        { int __k = peek(); if (__k != KIND_INLINE_IN_CI) { fail("InKW", RULE_PositionExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_PositionExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseSubstringExpr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_SubstringExpr_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_SUBSTRING_CI) { fail("SubstringKW", RULE_SubstringExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_SubstringExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                { int __k = peek(); if (__k != KIND_INLINE_FROM_CI) { fail("FromKW", RULE_SubstringExpr_KIND); break; } }
                advance();
                if (!parseExpr(self)) { break; }
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
                { int __k = peek(); if (__k != KIND_INLINE_FOR_CI) { fail("ForKW", RULE_SubstringExpr_KIND); break; } }
                advance();
                if (!parseExpr(self)) { break; }
                optOk_opt_1 = true;
            } while (false);
            if (!optOk_opt_1) {
                pos = savedPos_opt_1;
                cst.truncate(savedNodes_opt_1);
            }
        }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_SubstringExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseTrimExpr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_TrimExpr_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_TRIM_CI) { fail("TrimKW", RULE_TrimExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_TrimExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
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
                            { int __k = peek(); if (__k != KIND_INLINE_LEADING_CI) { fail("LeadingKW", RULE_TrimExpr_KIND); break; } }
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
                            { int __k = peek(); if (__k != KIND_INLINE_TRAILING_CI) { fail("TrailingKW", RULE_TrimExpr_KIND); break; } }
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
                            { int __k = peek(); if (__k != KIND_INLINE_BOTH_CI) { fail("BothKW", RULE_TrimExpr_KIND); break; } }
                            advance();
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1) { fail("<choice>", RULE_TrimExpr_KIND); break; }
                }
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
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
        { int __k = peek(); if (__k != KIND_INLINE_FROM_CI) { fail("FromKW", RULE_TrimExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_TrimExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseOverlayExpr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_OverlayExpr_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_OVERLAY_CI) { fail("OverlayKW", RULE_OverlayExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_OverlayExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        { int __k = peek(); if (__k != KIND_INLINE_PLACING_CI) { fail("PlacingKW", RULE_OverlayExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        { int __k = peek(); if (__k != KIND_INLINE_FROM_CI) { fail("FromKW", RULE_OverlayExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                { int __k = peek(); if (__k != KIND_INLINE_FOR_CI) { fail("ForKW", RULE_OverlayExpr_KIND); break; } }
                advance();
                if (!parseExpr(self)) { break; }
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_OverlayExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseTypedLiteral(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_TypedLiteral_KIND, firstTok, parent);
        if (!parseDataType(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (!parseStringLiteral(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseFuncCall(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_FuncCall_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseFuncName(self)) { break; }
                    if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_FuncCall_KIND); break; }
                    advance();
                    if (!parseFuncCallArgs(self)) { break; }
                    if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_FuncCall_KIND); break; }
                    advance();
                    // optional: opt_1
                    {
                        int savedPos_opt_1 = pos;
                        int savedNodes_opt_1 = cst.currentNodeCount();
                        boolean optOk_opt_1 = false;
                        do {
                            if (!parseFilterClause(self)) { break; }
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
                            if (!parseOverClause(self)) { break; }
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
                            if (!parseWithinGroupClause(self)) { break; }
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
                    if (!parseFuncName(self)) { break; }
                    if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_FuncCall_KIND); break; }
                    advance();
                    if (peek() != KIND_INLINE__STAR) { fail("'*'", RULE_FuncCall_KIND); break; }
                    advance();
                    if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_FuncCall_KIND); break; }
                    advance();
                    // optional: opt_4
                    {
                        int savedPos_opt_4 = pos;
                        int savedNodes_opt_4 = cst.currentNodeCount();
                        boolean optOk_opt_4 = false;
                        do {
                            if (!parseFilterClause(self)) { break; }
                            optOk_opt_4 = true;
                        } while (false);
                        if (!optOk_opt_4) {
                            pos = savedPos_opt_4;
                            cst.truncate(savedNodes_opt_4);
                        }
                    }
                    // optional: opt_5
                    {
                        int savedPos_opt_5 = pos;
                        int savedNodes_opt_5 = cst.currentNodeCount();
                        boolean optOk_opt_5 = false;
                        do {
                            if (!parseOverClause(self)) { break; }
                            optOk_opt_5 = true;
                        } while (false);
                        if (!optOk_opt_5) {
                            pos = savedPos_opt_5;
                            cst.truncate(savedNodes_opt_5);
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
                    if (!parseFuncName(self)) { break; }
                    if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_FuncCall_KIND); break; }
                    advance();
                    if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_FuncCall_KIND); break; }
                    advance();
                    // optional: opt_6
                    {
                        int savedPos_opt_6 = pos;
                        int savedNodes_opt_6 = cst.currentNodeCount();
                        boolean optOk_opt_6 = false;
                        do {
                            if (!parseFilterClause(self)) { break; }
                            optOk_opt_6 = true;
                        } while (false);
                        if (!optOk_opt_6) {
                            pos = savedPos_opt_6;
                            cst.truncate(savedNodes_opt_6);
                        }
                    }
                    // optional: opt_7
                    {
                        int savedPos_opt_7 = pos;
                        int savedNodes_opt_7 = cst.currentNodeCount();
                        boolean optOk_opt_7 = false;
                        do {
                            if (!parseOverClause(self)) { break; }
                            optOk_opt_7 = true;
                        } while (false);
                        if (!optOk_opt_7) {
                            pos = savedPos_opt_7;
                            cst.truncate(savedNodes_opt_7);
                        }
                    }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_FuncCall_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseFuncCallArgs(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_FuncCallArgs_KIND, firstTok, parent);
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
                            { int __k = peek(); if (__k != KIND_INLINE_ALL_CI) { fail("AllKW", RULE_FuncCallArgs_KIND); break; } }
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
                            { int __k = peek(); if (__k != KIND_INLINE_DISTINCT_CI) { fail("DistinctKW", RULE_FuncCallArgs_KIND); break; } }
                            advance();
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1) { fail("<choice>", RULE_FuncCallArgs_KIND); break; }
                }
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (!parseExprList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_2
        {
            int savedPos_opt_2 = pos;
            int savedNodes_opt_2 = cst.currentNodeCount();
            boolean optOk_opt_2 = false;
            do {
                if (!parseOrderByClause(self)) { break; }
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

    private boolean parseFuncName(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_FuncName_KIND, firstTok, parent);
        if (!parseQualifiedName(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseFilterClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_FilterClause_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_FILTER_CI) { fail("FilterKW", RULE_FilterClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_FilterClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseWhereClause(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_FilterClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseOverClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_OverClause_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_OVER_CI) { fail("OverKW", RULE_OverClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_COLID) { fail("ColId", RULE_OverClause_KIND); break; }
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
                    if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_OverClause_KIND); break; }
                    advance();
                    if (!parseWindowSpec(self)) { break; }
                    if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_OverClause_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_OverClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseWithinGroupClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_WithinGroupClause_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_INLINE_WITHIN_CI) { fail("WithinKW", RULE_WithinGroupClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_INLINE_GROUP_CI) { fail("GroupKW", RULE_WithinGroupClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_WithinGroupClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseOrderByClause(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_WithinGroupClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
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

    private boolean parseOperator(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_Operator_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_COMPAREOP) { fail("CompareOp", RULE_Operator_KIND); break; }
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
                    if (peek() != KIND_INLINE__PLUS) { fail("'+'", RULE_Operator_KIND); break; }
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
                    if (peek() != KIND_INLINE__MINUS) { fail("'-'", RULE_Operator_KIND); break; }
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
                    if (peek() != KIND_INLINE__STAR) { fail("'*'", RULE_Operator_KIND); break; }
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
                    if (peek() != KIND_INLINE__SLASH) { fail("'/'", RULE_Operator_KIND); break; }
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
                    if (peek() != KIND_INLINE__PERCENT) { fail("'%'", RULE_Operator_KIND); break; }
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
                    if (peek() != KIND_INLINE__CARET) { fail("'^'", RULE_Operator_KIND); break; }
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
                    if (peek() != KIND_INLINE__PIPE_PIPE) { fail("'||'", RULE_Operator_KIND); break; }
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
                    if (peek() != KIND_INLINE__MINUS_GT_GT) { fail("'->>'", RULE_Operator_KIND); break; }
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
                    if (peek() != KIND_INLINE__MINUS_GT) { fail("'->'", RULE_Operator_KIND); break; }
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
                    if (peek() != KIND_INLINE__HASH_GT_GT) { fail("'#>>'", RULE_Operator_KIND); break; }
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
                    if (peek() != KIND_INLINE__HASH_GT) { fail("'#>'", RULE_Operator_KIND); break; }
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
                    if (peek() != KIND_INLINE__AT_GT) { fail("'@>'", RULE_Operator_KIND); break; }
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
                    if (peek() != KIND_INLINE__LT_AT) { fail("'<@'", RULE_Operator_KIND); break; }
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
                    if (peek() != KIND_INLINE__AMP_AMP) { fail("'&&'", RULE_Operator_KIND); break; }
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
                    if (peek() != KIND_INLINE__AT_AT) { fail("'@@'", RULE_Operator_KIND); break; }
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
                    if (peek() != KIND_INLINE__AT_QMARK) { fail("'@?'", RULE_Operator_KIND); break; }
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
                    if (peek() != KIND_INLINE__QMARK) { fail("'?'", RULE_Operator_KIND); break; }
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
                    if (peek() != KIND_INLINE__QMARK_PIPE) { fail("'?|'", RULE_Operator_KIND); break; }
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
                    if (peek() != KIND_INLINE__QMARK_AMP) { fail("'?&'", RULE_Operator_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_Operator_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseDataType(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_DataType_KIND, firstTok, parent);
        if (!parseArrayType(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
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
        if (!parseScalarType(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
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
                            if (peek() != KIND_INLINE__LBRACK) { fail("'['", RULE_ArrayType_KIND); break; }
                            advance();
                            // optional: opt_2
                            {
                                int savedPos_opt_2 = pos;
                                int savedNodes_opt_2 = cst.currentNodeCount();
                                boolean optOk_opt_2 = false;
                                do {
                                    if (peek() != KIND_NUMERICLITERAL) { fail("NumericLiteral", RULE_ArrayType_KIND); break; }
                                    advance();
                                    optOk_opt_2 = true;
                                } while (false);
                                if (!optOk_opt_2) {
                                    pos = savedPos_opt_2;
                                    cst.truncate(savedNodes_opt_2);
                                }
                            }
                            if (peek() != KIND_INLINE__RBRACK) { fail("']'", RULE_ArrayType_KIND); break; }
                            advance();
                            // zero-or-more: rep_3
                            while (true) {
                                int savedPos_rep_3 = pos;
                                int savedNodes_rep_3 = cst.currentNodeCount();
                                boolean iterOk_rep_3 = false;
                                do {
                                    if (peek() != KIND_INLINE__LBRACK) { fail("'['", RULE_ArrayType_KIND); break; }
                                    advance();
                                    // optional: opt_4
                                    {
                                        int savedPos_opt_4 = pos;
                                        int savedNodes_opt_4 = cst.currentNodeCount();
                                        boolean optOk_opt_4 = false;
                                        do {
                                            if (peek() != KIND_NUMERICLITERAL) { fail("NumericLiteral", RULE_ArrayType_KIND); break; }
                                            advance();
                                            optOk_opt_4 = true;
                                        } while (false);
                                        if (!optOk_opt_4) {
                                            pos = savedPos_opt_4;
                                            cst.truncate(savedNodes_opt_4);
                                        }
                                    }
                                    if (peek() != KIND_INLINE__RBRACK) { fail("']'", RULE_ArrayType_KIND); break; }
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
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1 && !cutHit_alt_1) {
                        do {
                            { int __k = peek(); if (__k != KIND_INLINE_ARRAY_CI) { fail("ArrayKW", RULE_ArrayType_KIND); break; } }
                            advance();
                            // optional: opt_5
                            {
                                int savedPos_opt_5 = pos;
                                int savedNodes_opt_5 = cst.currentNodeCount();
                                boolean optOk_opt_5 = false;
                                do {
                                    if (peek() != KIND_INLINE__LBRACK) { fail("'['", RULE_ArrayType_KIND); break; }
                                    advance();
                                    if (peek() != KIND_NUMERICLITERAL) { fail("NumericLiteral", RULE_ArrayType_KIND); break; }
                                    advance();
                                    if (peek() != KIND_INLINE__RBRACK) { fail("']'", RULE_ArrayType_KIND); break; }
                                    advance();
                                    optOk_opt_5 = true;
                                } while (false);
                                if (!optOk_opt_5) {
                                    pos = savedPos_opt_5;
                                    cst.truncate(savedNodes_opt_5);
                                }
                            }
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1) { fail("<choice>", RULE_ArrayType_KIND); break; }
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

    private boolean parseScalarType(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ScalarType_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseNumericType(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseCharType(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseDateTimeType(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_BOOLEAN_CI && __k != KIND_INLINE_BOOL_CI) { fail("BooleanType", RULE_ScalarType_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_JSONB_CI && __k != KIND_INLINE_JSON_CI) { fail("JsonType", RULE_ScalarType_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_UUID_CI) { fail("UuidType", RULE_ScalarType_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_BYTEA_CI) { fail("ByteaType", RULE_ScalarType_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_XML_CI) { fail("XmlType", RULE_ScalarType_KIND); break; } }
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
                    if (!parseBitType(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_INET_CI && __k != KIND_INLINE_CIDR_CI && __k != KIND_INLINE_MACADDR8_CI && __k != KIND_INLINE_MACADDR_CI) { fail("NetworkType", RULE_ScalarType_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INLINE_MONEY_CI) { fail("MoneyType", RULE_ScalarType_KIND); break; } }
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
                    if (java.util.Arrays.binarySearch(ALIAS_SERIALTYPE, peek()) < 0) { fail("SerialType", RULE_ScalarType_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_INLINE_TSVECTOR_CI && __k != KIND_INLINE_TSQUERY_CI) { fail("TsvectorType", RULE_ScalarType_KIND); break; } }
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
                    if (!parseQualifiedTypeName(self)) { break; }
                    // optional: opt_1
                    {
                        int savedPos_opt_1 = pos;
                        int savedNodes_opt_1 = cst.currentNodeCount();
                        boolean optOk_opt_1 = false;
                        do {
                            if (!parseTypeModifiers(self)) { break; }
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
            if (!matched_alt_0) { fail("<choice>", RULE_ScalarType_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseNumericType(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_NumericType_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE_DOUBLE_CI) { fail("'double'", RULE_NumericType_KIND); break; }
                    advance();
                    if (pos >= tokens.count()) { fail("[ \\t\\r\\n]", RULE_NumericType_KIND); break; }
                    { int __off = tokens.startAt(pos);
                      int __c = __off < tokens.input().length() ? tokens.input().charAt(__off) : -1;
                      if (!((__c >= 0 && (__c == 32 || __c == 9 || __c == 13 || __c == 10)))) { fail("[ \\t\\r\\n]", RULE_NumericType_KIND); break; } }
                    advance();
                    // zero-or-more: rep_1
                    while (true) {
                        int savedPos_rep_1 = pos;
                        int savedNodes_rep_1 = cst.currentNodeCount();
                        boolean iterOk_rep_1 = false;
                        do {
                            if (pos >= tokens.count()) { fail("[ \\t\\r\\n]", RULE_NumericType_KIND); break; }
                            { int __off = tokens.startAt(pos);
                              int __c = __off < tokens.input().length() ? tokens.input().charAt(__off) : -1;
                              if (!((__c >= 0 && (__c == 32 || __c == 9 || __c == 13 || __c == 10)))) { fail("[ \\t\\r\\n]", RULE_NumericType_KIND); break; } }
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
                    if (peek() != KIND_INLINE_PRECISION_CI) { fail("'precision'", RULE_NumericType_KIND); break; }
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
                    if (peek() != KIND_INLINE_SMALLINT_CI) { fail("'smallint'", RULE_NumericType_KIND); break; }
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
                    if (peek() != KIND_INLINE_INTEGER_CI) { fail("'integer'", RULE_NumericType_KIND); break; }
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
                    if (peek() != KIND_INLINE_BIGINT_CI) { fail("'bigint'", RULE_NumericType_KIND); break; }
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
                    if (peek() != KIND_INLINE_INT8_CI) { fail("'int8'", RULE_NumericType_KIND); break; }
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
                    if (peek() != KIND_INLINE_INT4_CI) { fail("'int4'", RULE_NumericType_KIND); break; }
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
                    if (peek() != KIND_INLINE_INT2_CI) { fail("'int2'", RULE_NumericType_KIND); break; }
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
                    if (peek() != KIND_INLINE_INT_CI) { fail("'int'", RULE_NumericType_KIND); break; }
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
                    if (peek() != KIND_INLINE_FLOAT8_CI) { fail("'float8'", RULE_NumericType_KIND); break; }
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
                    if (peek() != KIND_INLINE_FLOAT4_CI) { fail("'float4'", RULE_NumericType_KIND); break; }
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
                    if (peek() != KIND_INLINE_FLOAT_CI) { fail("'float'", RULE_NumericType_KIND); break; }
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
                    if (peek() != KIND_INLINE_NUMERIC_CI) { fail("'numeric'", RULE_NumericType_KIND); break; }
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
                    if (peek() != KIND_INLINE_DECIMAL_CI) { fail("'decimal'", RULE_NumericType_KIND); break; }
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
                    if (peek() != KIND_INLINE_REAL_CI) { fail("'real'", RULE_NumericType_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_NumericType_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        // no-op: not-predicate over char-level expression — handled by lexer
        // optional: opt_2
        {
            int savedPos_opt_2 = pos;
            int savedNodes_opt_2 = cst.currentNodeCount();
            boolean optOk_opt_2 = false;
            do {
                if (!parseTypeModifiers(self)) { break; }
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

    private boolean parseCharType(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_CharType_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE_CHARACTER_CI) { fail("'character'", RULE_CharType_KIND); break; }
                    advance();
                    if (pos >= tokens.count()) { fail("[ \\t\\r\\n]", RULE_CharType_KIND); break; }
                    { int __off = tokens.startAt(pos);
                      int __c = __off < tokens.input().length() ? tokens.input().charAt(__off) : -1;
                      if (!((__c >= 0 && (__c == 32 || __c == 9 || __c == 13 || __c == 10)))) { fail("[ \\t\\r\\n]", RULE_CharType_KIND); break; } }
                    advance();
                    // zero-or-more: rep_1
                    while (true) {
                        int savedPos_rep_1 = pos;
                        int savedNodes_rep_1 = cst.currentNodeCount();
                        boolean iterOk_rep_1 = false;
                        do {
                            if (pos >= tokens.count()) { fail("[ \\t\\r\\n]", RULE_CharType_KIND); break; }
                            { int __off = tokens.startAt(pos);
                              int __c = __off < tokens.input().length() ? tokens.input().charAt(__off) : -1;
                              if (!((__c >= 0 && (__c == 32 || __c == 9 || __c == 13 || __c == 10)))) { fail("[ \\t\\r\\n]", RULE_CharType_KIND); break; } }
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
                    if (peek() != KIND_INLINE_VARYING_CI) { fail("'varying'", RULE_CharType_KIND); break; }
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
                    if (peek() != KIND_INLINE_VARCHAR_CI) { fail("'varchar'", RULE_CharType_KIND); break; }
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
                    if (peek() != KIND_INLINE_CHARACTER_CI) { fail("'character'", RULE_CharType_KIND); break; }
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
                    if (peek() != KIND_INLINE_CHAR_CI) { fail("'char'", RULE_CharType_KIND); break; }
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
                    if (peek() != KIND_INLINE_TEXT_CI) { fail("'text'", RULE_CharType_KIND); break; }
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
                    if (peek() != KIND_INLINE_NAME_CI) { fail("'name'", RULE_CharType_KIND); break; }
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
                    if (peek() != KIND_INLINE_CITEXT_CI) { fail("'citext'", RULE_CharType_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_CharType_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        // no-op: not-predicate over char-level expression — handled by lexer
        // optional: opt_2
        {
            int savedPos_opt_2 = pos;
            int savedNodes_opt_2 = cst.currentNodeCount();
            boolean optOk_opt_2 = false;
            do {
                if (!parseTypeModifiers(self)) { break; }
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

    private boolean parseDateTimeType(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_DateTimeType_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseTimestampType(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseTimeType(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INLINE_DATE_CI) { fail("DateType", RULE_DateTimeType_KIND); break; } }
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
                    if (!parseIntervalType(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_DateTimeType_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseTimestampType(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_TimestampType_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE_TIMESTAMPTZ_CI) { fail("'timestamptz'", RULE_TimestampType_KIND); break; }
                    advance();
                    // no-op: not-predicate over char-level expression — handled by lexer
                    // optional: opt_1
                    {
                        int savedPos_opt_1 = pos;
                        int savedNodes_opt_1 = cst.currentNodeCount();
                        boolean optOk_opt_1 = false;
                        do {
                            if (!parseTypeModifiers(self)) { break; }
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
                    if (peek() != KIND_INLINE_TIMESTAMP_CI) { fail("'timestamp'", RULE_TimestampType_KIND); break; }
                    advance();
                    // no-op: not-predicate over char-level expression — handled by lexer
                    // optional: opt_2
                    {
                        int savedPos_opt_2 = pos;
                        int savedNodes_opt_2 = cst.currentNodeCount();
                        boolean optOk_opt_2 = false;
                        do {
                            if (!parseTypeModifiers(self)) { break; }
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
                            // choice: alt_4
                            {
                                int savedPos_alt_4 = pos;
                                int savedNodes_alt_4 = cst.currentNodeCount();
                                boolean matched_alt_4 = false;
                                boolean cutHit_alt_4 = false;
                                if (!matched_alt_4 && !cutHit_alt_4) {
                                    do {
                                        { int __k = peek(); if (__k != KIND_INLINE_WITH_CI) { fail("WithKW", RULE_TimestampType_KIND); break; } }
                                        advance();
                                        { int __k = peek(); if (__k != KIND_INLINE_TIME_CI) { fail("TimeKW", RULE_TimestampType_KIND); break; } }
                                        advance();
                                        { int __k = peek(); if (__k != KIND_INLINE_ZONE_CI) { fail("ZoneKW", RULE_TimestampType_KIND); break; } }
                                        advance();
                                        matched_alt_4 = true;
                                    } while (false);
                                    if (!matched_alt_4) {
                                        pos = savedPos_alt_4;
                                        cst.truncate(savedNodes_alt_4);
                                    }
                                }
                                if (!matched_alt_4 && !cutHit_alt_4) {
                                    do {
                                        { int __k = peek(); if (__k != KIND_INLINE_WITHOUT_CI) { fail("WithoutKW", RULE_TimestampType_KIND); break; } }
                                        advance();
                                        { int __k = peek(); if (__k != KIND_INLINE_TIME_CI) { fail("TimeKW", RULE_TimestampType_KIND); break; } }
                                        advance();
                                        { int __k = peek(); if (__k != KIND_INLINE_ZONE_CI) { fail("ZoneKW", RULE_TimestampType_KIND); break; } }
                                        advance();
                                        matched_alt_4 = true;
                                    } while (false);
                                    if (!matched_alt_4) {
                                        pos = savedPos_alt_4;
                                        cst.truncate(savedNodes_alt_4);
                                    }
                                }
                                if (!matched_alt_4) { fail("<choice>", RULE_TimestampType_KIND); break; }
                            }
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
            if (!matched_alt_0) { fail("<choice>", RULE_TimestampType_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseTimeType(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_TimeType_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE_TIMETZ_CI) { fail("'timetz'", RULE_TimeType_KIND); break; }
                    advance();
                    // no-op: not-predicate over char-level expression — handled by lexer
                    // optional: opt_1
                    {
                        int savedPos_opt_1 = pos;
                        int savedNodes_opt_1 = cst.currentNodeCount();
                        boolean optOk_opt_1 = false;
                        do {
                            if (!parseTypeModifiers(self)) { break; }
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
                    if (peek() != KIND_INLINE_TIME_CI) { fail("'time'", RULE_TimeType_KIND); break; }
                    advance();
                    // no-op: not-predicate over char-level expression — handled by lexer
                    // optional: opt_2
                    {
                        int savedPos_opt_2 = pos;
                        int savedNodes_opt_2 = cst.currentNodeCount();
                        boolean optOk_opt_2 = false;
                        do {
                            if (!parseTypeModifiers(self)) { break; }
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
                            // choice: alt_4
                            {
                                int savedPos_alt_4 = pos;
                                int savedNodes_alt_4 = cst.currentNodeCount();
                                boolean matched_alt_4 = false;
                                boolean cutHit_alt_4 = false;
                                if (!matched_alt_4 && !cutHit_alt_4) {
                                    do {
                                        { int __k = peek(); if (__k != KIND_INLINE_WITH_CI) { fail("WithKW", RULE_TimeType_KIND); break; } }
                                        advance();
                                        { int __k = peek(); if (__k != KIND_INLINE_TIME_CI) { fail("TimeKW", RULE_TimeType_KIND); break; } }
                                        advance();
                                        { int __k = peek(); if (__k != KIND_INLINE_ZONE_CI) { fail("ZoneKW", RULE_TimeType_KIND); break; } }
                                        advance();
                                        matched_alt_4 = true;
                                    } while (false);
                                    if (!matched_alt_4) {
                                        pos = savedPos_alt_4;
                                        cst.truncate(savedNodes_alt_4);
                                    }
                                }
                                if (!matched_alt_4 && !cutHit_alt_4) {
                                    do {
                                        { int __k = peek(); if (__k != KIND_INLINE_WITHOUT_CI) { fail("WithoutKW", RULE_TimeType_KIND); break; } }
                                        advance();
                                        { int __k = peek(); if (__k != KIND_INLINE_TIME_CI) { fail("TimeKW", RULE_TimeType_KIND); break; } }
                                        advance();
                                        { int __k = peek(); if (__k != KIND_INLINE_ZONE_CI) { fail("ZoneKW", RULE_TimeType_KIND); break; } }
                                        advance();
                                        matched_alt_4 = true;
                                    } while (false);
                                    if (!matched_alt_4) {
                                        pos = savedPos_alt_4;
                                        cst.truncate(savedNodes_alt_4);
                                    }
                                }
                                if (!matched_alt_4) { fail("<choice>", RULE_TimeType_KIND); break; }
                            }
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
            if (!matched_alt_0) { fail("<choice>", RULE_TimeType_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseIntervalType(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_IntervalType_KIND, firstTok, parent);
        if (peek() != KIND_INLINE_INTERVAL_CI) { fail("'interval'", RULE_IntervalType_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // no-op: not-predicate over char-level expression — handled by lexer
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (peek() != KIND_INTERVALFIELD) { fail("IntervalField", RULE_IntervalType_KIND); break; }
                advance();
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
                if (!parseTypeModifiers(self)) { break; }
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

    private boolean parseBitType(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_BitType_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE_VARBIT_CI) { fail("'varbit'", RULE_BitType_KIND); break; }
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
                    if (peek() != KIND_INLINE_BIT_CI) { fail("'bit'", RULE_BitType_KIND); break; }
                    advance();
                    if (pos >= tokens.count()) { fail("[ \\t\\r\\n]", RULE_BitType_KIND); break; }
                    { int __off = tokens.startAt(pos);
                      int __c = __off < tokens.input().length() ? tokens.input().charAt(__off) : -1;
                      if (!((__c >= 0 && (__c == 32 || __c == 9 || __c == 13 || __c == 10)))) { fail("[ \\t\\r\\n]", RULE_BitType_KIND); break; } }
                    advance();
                    // zero-or-more: rep_1
                    while (true) {
                        int savedPos_rep_1 = pos;
                        int savedNodes_rep_1 = cst.currentNodeCount();
                        boolean iterOk_rep_1 = false;
                        do {
                            if (pos >= tokens.count()) { fail("[ \\t\\r\\n]", RULE_BitType_KIND); break; }
                            { int __off = tokens.startAt(pos);
                              int __c = __off < tokens.input().length() ? tokens.input().charAt(__off) : -1;
                              if (!((__c >= 0 && (__c == 32 || __c == 9 || __c == 13 || __c == 10)))) { fail("[ \\t\\r\\n]", RULE_BitType_KIND); break; } }
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
                    if (peek() != KIND_INLINE_VARYING_CI) { fail("'varying'", RULE_BitType_KIND); break; }
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
                    if (peek() != KIND_INLINE_BIT_CI) { fail("'bit'", RULE_BitType_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_BitType_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        // no-op: not-predicate over char-level expression — handled by lexer
        // optional: opt_2
        {
            int savedPos_opt_2 = pos;
            int savedNodes_opt_2 = cst.currentNodeCount();
            boolean optOk_opt_2 = false;
            do {
                if (!parseTypeModifiers(self)) { break; }
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

    private boolean parseTypeModifiers(int parent) {
        if (peek() != KIND_INLINE__LPAREN) { fail("TypeModifiers", RULE_TypeModifiers_KIND); return false; }
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_TypeModifiers_KIND, firstTok, parent);
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_TypeModifiers_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseSignedNumericLiteral(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_TypeModifiers_KIND); break; }
                advance();
                if (!parseSignedNumericLiteral(self)) { break; }
                iterOk_rep_0 = true;
            } while (false);
            if (!iterOk_rep_0) {
                pos = savedPos_rep_0;
                cst.truncate(savedNodes_rep_0);
                break;
            }
            if (pos == savedPos_rep_0) break; // guard against infinite loops on zero-width matches
        }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_TypeModifiers_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseQualifiedTypeName(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_QualifiedTypeName_KIND, firstTok, parent);
        if (!parseQualifiedName(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
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
        if (peek() != KIND_COLID) { fail("ColId", RULE_QualifiedName_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__DOT) { fail("'.'", RULE_QualifiedName_KIND); break; }
                advance();
                // choice: alt_1
                {
                    int savedPos_alt_1 = pos;
                    int savedNodes_alt_1 = cst.currentNodeCount();
                    boolean matched_alt_1 = false;
                    boolean cutHit_alt_1 = false;
                    if (!matched_alt_1 && !cutHit_alt_1) {
                        do {
                            if (peek() != KIND_COLID) { fail("ColId", RULE_QualifiedName_KIND); break; }
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
                            if (peek() != KIND_INLINE__STAR) { fail("'*'", RULE_QualifiedName_KIND); break; }
                            advance();
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1) { fail("<choice>", RULE_QualifiedName_KIND); break; }
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
                    if (peek() != KIND_BOOLEANLITERAL) { fail("BooleanLiteral", RULE_Literal_KIND); break; }
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
                    if (peek() != KIND_NULLLITERAL) { fail("NullLiteral", RULE_Literal_KIND); break; }
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
                    if (peek() != KIND_NUMERICLITERAL) { fail("NumericLiteral", RULE_Literal_KIND); break; }
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
                    if (!parseStringLiteral(self)) { break; }
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

    private boolean parseSignedNumericLiteral(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_SignedNumericLiteral_KIND, firstTok, parent);
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (pos >= tokens.count()) { fail("[+\\-]", RULE_SignedNumericLiteral_KIND); break; }
                { int __off = tokens.startAt(pos);
                  int __c = __off < tokens.input().length() ? tokens.input().charAt(__off) : -1;
                  if (!((__c >= 0 && (__c == 43 || __c == 45)))) { fail("[+\\-]", RULE_SignedNumericLiteral_KIND); break; } }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (peek() != KIND_NUMERICLITERAL) { fail("NumericLiteral", RULE_SignedNumericLiteral_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseStringLiteral(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_StringLiteral_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_ESCAPESTRING) { fail("EscapeString", RULE_StringLiteral_KIND); break; }
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
                    if (!parseDollarString(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_BASICSTRING) { fail("BasicString", RULE_StringLiteral_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_StringLiteral_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseDollarString(int parent) {
        if (peek() != KIND_INLINE__DOLLAR_DOLLAR && peek() != KIND_INLINE__DOLLAR) { fail("DollarString", RULE_DollarString_KIND); return false; }
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_DollarString_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (peek() != KIND_INLINE__DOLLAR_DOLLAR) { fail("'$$'", RULE_DollarString_KIND); break; }
                    advance();
                    // zero-or-more: rep_1
                    while (true) {
                        int savedPos_rep_1 = pos;
                        int savedNodes_rep_1 = cst.currentNodeCount();
                        boolean iterOk_rep_1 = false;
                        do {
                            // not-predicate: not_2
                            {
                                int savedPos_not_2 = pos;
                                int savedNodes_not_2 = cst.currentNodeCount();
                                boolean notMatched_not_2 = false;
                                do {
                                    if (peek() != KIND_INLINE__DOLLAR_DOLLAR) { fail("'$$'", RULE_DollarString_KIND); break; }
                                    advance();
                                    notMatched_not_2 = true;
                                } while (false);
                                pos = savedPos_not_2;
                                cst.truncate(savedNodes_not_2);
                                if (notMatched_not_2) { fail("!<predicate>", RULE_DollarString_KIND); break; }
                            }
                            if (peek() < 0) { fail("<any token>", RULE_DollarString_KIND); break; }
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
                    if (peek() != KIND_INLINE__DOLLAR_DOLLAR) { fail("'$$'", RULE_DollarString_KIND); break; }
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
                    if (peek() != KIND_INLINE__DOLLAR) { fail("'$'", RULE_DollarString_KIND); break; }
                    advance();
                    // capture: $tag
                    int capStartTok_cap_3 = pos;
                    int capStartByte_cap_3 = pos < tokens.count() ? tokens.startAt(pos) : tokens.input().length();
                    // no-op: char-class a-zA-Z_ inside parser rule — handled by lexer (Phase B.3 no-op)
                    // zero-or-more: rep_4
                    while (true) {
                        int savedPos_rep_4 = pos;
                        int savedNodes_rep_4 = cst.currentNodeCount();
                        boolean iterOk_rep_4 = false;
                        do {
                            // no-op: char-class a-zA-Z0-9_ inside parser rule — handled by lexer (Phase B.3 no-op)
                            iterOk_rep_4 = true;
                        } while (false);
                        if (!iterOk_rep_4) {
                            pos = savedPos_rep_4;
                            cst.truncate(savedNodes_rep_4);
                            break;
                        }
                        if (pos == savedPos_rep_4) break; // guard against infinite loops on zero-width matches
                    }
                    int capEndByte_cap_3 = pos > capStartTok_cap_3 ? tokens.endAt(pos - 1) : capStartByte_cap_3;
                    captures.put("tag", new long[]{capStartByte_cap_3, capEndByte_cap_3});
                    if (peek() != KIND_INLINE__DOLLAR) { fail("'$'", RULE_DollarString_KIND); break; }
                    advance();
                    // zero-or-more: rep_5
                    while (true) {
                        int savedPos_rep_5 = pos;
                        int savedNodes_rep_5 = cst.currentNodeCount();
                        boolean iterOk_rep_5 = false;
                        do {
                            // not-predicate: not_6
                            {
                                int savedPos_not_6 = pos;
                                int savedNodes_not_6 = cst.currentNodeCount();
                                boolean notMatched_not_6 = false;
                                do {
                                    if (peek() != KIND_INLINE__DOLLAR) { fail("'$'", RULE_DollarString_KIND); break; }
                                    advance();
                                    // back-reference: $tag
                                    {
                                        long[] cap_bref_7 = captures.get("tag");
                                        if (cap_bref_7 == null) { fail("back-reference $tag not captured", RULE_DollarString_KIND); break; }
                                        int capLen_bref_7 = (int)(cap_bref_7[1] - cap_bref_7[0]);
                                        int posByte_bref_7 = pos < tokens.count() ? tokens.startAt(pos) : tokens.input().length();
                                        String inputStr_bref_7 = tokens.input();
                                        if (posByte_bref_7 + capLen_bref_7 > inputStr_bref_7.length()) { fail("back-reference $tag", RULE_DollarString_KIND); break; }
                                        boolean eq_bref_7 = true;
                                        for (int i = 0; i < capLen_bref_7; i++) {
                                            if (inputStr_bref_7.charAt(posByte_bref_7 + i) != inputStr_bref_7.charAt((int)cap_bref_7[0] + i)) { eq_bref_7 = false; break; }
                                        }
                                        if (!eq_bref_7) { fail("back-reference $tag", RULE_DollarString_KIND); break; }
                                        if (capLen_bref_7 > 0) {
                                            int targetByte_bref_7 = posByte_bref_7 + capLen_bref_7;
                                            while (pos < tokens.count() && tokens.startAt(pos) < targetByte_bref_7) pos++;
                                        }
                                    }
                                    if (peek() != KIND_INLINE__DOLLAR) { fail("'$'", RULE_DollarString_KIND); break; }
                                    advance();
                                    notMatched_not_6 = true;
                                } while (false);
                                pos = savedPos_not_6;
                                cst.truncate(savedNodes_not_6);
                                if (notMatched_not_6) { fail("!<predicate>", RULE_DollarString_KIND); break; }
                            }
                            if (peek() < 0) { fail("<any token>", RULE_DollarString_KIND); break; }
                            advance();
                            iterOk_rep_5 = true;
                        } while (false);
                        if (!iterOk_rep_5) {
                            pos = savedPos_rep_5;
                            cst.truncate(savedNodes_rep_5);
                            break;
                        }
                        if (pos == savedPos_rep_5) break; // guard against infinite loops on zero-width matches
                    }
                    if (peek() != KIND_INLINE__DOLLAR) { fail("'$'", RULE_DollarString_KIND); break; }
                    advance();
                    // back-reference: $tag
                    {
                        long[] cap_bref_8 = captures.get("tag");
                        if (cap_bref_8 == null) { fail("back-reference $tag not captured", RULE_DollarString_KIND); break; }
                        int capLen_bref_8 = (int)(cap_bref_8[1] - cap_bref_8[0]);
                        int posByte_bref_8 = pos < tokens.count() ? tokens.startAt(pos) : tokens.input().length();
                        String inputStr_bref_8 = tokens.input();
                        if (posByte_bref_8 + capLen_bref_8 > inputStr_bref_8.length()) { fail("back-reference $tag", RULE_DollarString_KIND); break; }
                        boolean eq_bref_8 = true;
                        for (int i = 0; i < capLen_bref_8; i++) {
                            if (inputStr_bref_8.charAt(posByte_bref_8 + i) != inputStr_bref_8.charAt((int)cap_bref_8[0] + i)) { eq_bref_8 = false; break; }
                        }
                        if (!eq_bref_8) { fail("back-reference $tag", RULE_DollarString_KIND); break; }
                        if (capLen_bref_8 > 0) {
                            int targetByte_bref_8 = posByte_bref_8 + capLen_bref_8;
                            while (pos < tokens.count() && tokens.startAt(pos) < targetByte_bref_8) pos++;
                        }
                    }
                    if (peek() != KIND_INLINE__DOLLAR) { fail("'$'", RULE_DollarString_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_DollarString_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseClauseKeyword(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ClauseKeyword_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (java.util.Arrays.binarySearch(ALIAS_RESERVEDKEYWORD, peek()) < 0) { fail("ReservedKeyword", RULE_ClauseKeyword_KIND); break; }
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
                    // choice: alt_1
                    {
                        int savedPos_alt_1 = pos;
                        int savedNodes_alt_1 = cst.currentNodeCount();
                        boolean matched_alt_1 = false;
                        boolean cutHit_alt_1 = false;
                        if (!matched_alt_1 && !cutHit_alt_1) {
                            do {
                                if (peek() != KIND_INLINE_SET_CI) { fail("'SET'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_INLINE_ORDER_CI) { fail("'ORDER'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_INLINE_GROUP_CI) { fail("'GROUP'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_INLINE_HAVING_CI) { fail("'HAVING'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_INLINE_LIMIT_CI) { fail("'LIMIT'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_INLINE_OFFSET_CI) { fail("'OFFSET'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_INLINE_FETCH_CI) { fail("'FETCH'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_INLINE_UNION_CI) { fail("'UNION'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_INLINE_INTERSECT_CI) { fail("'INTERSECT'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_INLINE_EXCEPT_CI) { fail("'EXCEPT'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_INLINE_JOIN_CI) { fail("'JOIN'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_INLINE_INNER_CI) { fail("'INNER'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_INLINE_LEFT_CI) { fail("'LEFT'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_INLINE_RIGHT_CI) { fail("'RIGHT'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_INLINE_FULL_CI) { fail("'FULL'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_INLINE_CROSS_CI) { fail("'CROSS'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_INLINE_NATURAL_CI) { fail("'NATURAL'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_INLINE_RETURNING_CI) { fail("'RETURNING'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_INLINE_VALUES_CI) { fail("'VALUES'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_INLINE_WHERE_CI) { fail("'WHERE'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_INLINE_ON_CI) { fail("'ON'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_INLINE_WINDOW_CI) { fail("'WINDOW'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_INLINE_PARTITION_CI) { fail("'PARTITION'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_INLINE_ROWS_CI) { fail("'ROWS'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_INLINE_RANGE_CI) { fail("'RANGE'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_INLINE_GROUPS_CI) { fail("'GROUPS'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_INLINE_DO_CI) { fail("'DO'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_INLINE_CONFLICT_CI) { fail("'CONFLICT'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_INLINE_OVER_CI) { fail("'OVER'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_INLINE_FILTER_CI) { fail("'FILTER'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_INLINE_WITHIN_CI) { fail("'WITHIN'", RULE_ClauseKeyword_KIND); break; }
                                advance();
                                matched_alt_1 = true;
                            } while (false);
                            if (!matched_alt_1) {
                                pos = savedPos_alt_1;
                                cst.truncate(savedNodes_alt_1);
                            }
                        }
                        if (!matched_alt_1) { fail("<choice>", RULE_ClauseKeyword_KIND); break; }
                    }
                    // no-op: not-predicate over char-level expression — handled by lexer
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_ClauseKeyword_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

}

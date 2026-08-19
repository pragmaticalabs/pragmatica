// peglib-generator: 0.7.2 (build:bb4663171d97)
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

    private static final String[] RULE_TABLE = {"Input", "Statement", "DdlStatement", "CreateStatement", "AlterStatement", "DropStatement", "DmlStatement", "CreateTableStmt", "IfNotExists", "IfExists", "TableElementList", "TableElement", "ColumnDef", "ColConstraint", "ColConstraintElem", "NotNullConstraint", "PrimaryKeyColConstraint", "CheckColConstraint", "DefaultClause", "CollateClause", "ReferencesClause", "GeneratedClause", "IdentityClause", "IdentitySpec", "TableConstraint", "ConstraintName", "TableConstraintElem", "PrimaryKeyTblConstraint", "UniqueTblConstraint", "CheckTblConstraint", "NoInheritClause", "ForeignKeyTblConstraint", "FkActions", "FkAction", "FkActionType", "FkDeferrable", "ExcludeTblConstraint", "ExcludeElementList", "ExcludeElement", "NullsDistinct", "IndexOptions", "IncludeClause", "WithStorageParams", "StorageParamList", "StorageParam", "UsingIndexTblspace", "TableOptions", "PartitionByClause", "PartitionKeyList", "PartitionKey", "InheritsClause", "TablespaceClause", "ColumnList", "QualifiedNameList", "AlterTableStmt", "AlterTableActions", "AlterTableAction", "AddColumnAction", "DropColumnAction", "AlterColumnAction", "AlterColumnCmd", "SetDataTypeCmd", "SetDefaultCmd", "DropDefaultCmd", "SetNotNullCmd", "DropNotNullCmd", "SetStatisticsCmd", "SetStorageCmd", "AddIdentityCmd", "DropIdentityCmd", "AddConstraintAction", "DropConstraintAction", "ValidateConstraintAction", "RenameConstraintAction", "NotValidClause", "AlterOwnerAction", "RenameAction", "SetSchemaAction", "SetTablespaceAction", "AttachPartition", "DetachPartition", "ForValuesClause", "DropTableStmt", "CreateIndexStmt", "UsingClause", "IndexElemList", "IndexElem", "OpClass", "NullsOrder", "AlterIndexStmt", "DropIndexStmt", "CreateSequenceStmt", "AlterSequenceStmt", "DropSequenceStmt", "SequenceOptions", "SequenceOption", "CreateTypeStmt", "EnumLabelList", "CompositeFieldList", "CompositeField", "RangeOptionList", "RangeOption", "DomainConstraint", "AlterTypeStmt", "DropTypeStmt", "CreateSchemaStmt", "AlterSchemaStmt", "DropSchemaStmt", "CreateViewStmt", "CheckOptionClause", "CreateMatViewStmt", "AlterViewStmt", "DropViewStmt", "DropMatViewStmt", "CreateExtensionStmt", "ExtensionOptions", "ExtensionOption", "DropExtensionStmt", "CommentStatement", "CommentTarget", "FuncArgTypes", "FuncArgType", "GrantStatement", "RevokeStatement", "PrivilegeList", "Privilege", "GrantTarget", "GranteeList", "Grantee", "AlterDefaultPrivilegesPassthrough", "CreateFunctionPassthrough", "CreateTriggerPassthrough", "DropFunctionPassthrough", "DropTriggerPassthrough", "SelectStmt", "SelectCore", "SetQuantifier", "TargetList", "TargetElem", "StarExpr", "IntoClause", "FromClause", "FromList", "TableRef", "TableRefBase", "BaseTableRef", "SubqueryRef", "LateralRef", "FuncTableRef", "WithOrdinality", "Alias", "TablesampleClause", "JoinExpr", "JoinClause", "JoinType", "JoinQual", "WhereClause", "GroupByClause", "GroupByList", "GroupByElem", "HavingClause", "WindowClause", "WindowDefList", "WindowDef", "WindowSpec", "PartitionClause", "FrameClause", "FrameExtent", "FrameBound", "FrameExclusion", "WithClause", "CteList", "CteDef", "SetOp", "OrderByClause", "OrderByList", "OrderByItem", "LimitClause", "OffsetClause", "FetchClause", "InsertStmt", "InsertSource", "ValuesClause", "ValueRowList", "ExprOrDefaultList", "ExprOrDefault", "OnConflictClause", "ConflictTarget", "ConflictAction", "ReturningClause", "UpdateStmt", "UpdateSetList", "UpdateSetItem", "DeleteStmt", "UsingClauseDelete", "PassthroughStatement", "TransactionStmt", "SessionStmt", "UtilityStmt", "TruncateStmt", "ExplainStmt", "CopyStmt", "RefreshMatViewStmt", "RestOfStatement", "Expr", "OrExpr", "AndExpr", "NotExpr", "CompareExpr", "IsExpr", "IsClause", "InExpr", "BetweenExpr", "LikeExpr", "SimilarToExpr", "IsDistinctFrom", "AddExpr", "MulExpr", "UnaryExpr", "ExponentExpr", "ConcatExpr", "ArrayExpr", "TypeCastExpr", "PostfixExpr", "PostfixOp", "PrimaryExpr", "ColRef", "ExistsExpr", "SubqueryExpr", "AnyAllExpr", "RowExpr", "ArrayExprConstructor", "CastExpr", "CaseExpr", "WhenClause", "ElseClause", "CoalesceExpr", "NullIfExpr", "GreatestLeastExpr", "ExtractExpr", "PositionExpr", "SubstringExpr", "TrimExpr", "OverlayExpr", "TypedLiteral", "SpecialFuncExpr", "FuncCall", "FuncCallArgs", "FuncName", "FilterClause", "OverClause", "WithinGroupClause", "ExprList", "Operator", "DataType", "ArrayType", "ScalarType", "NumericType", "CharType", "DateTimeType", "TimestampType", "TimeType", "IntervalType", "IntervalField", "BitType", "TypeModifiers", "QualifiedTypeName", "ColLabel", "QualifiedName", "Literal", "SignedNumericLiteral", "StringLiteral", "DollarString", "ClauseKeyword", "ERROR", "_ROOT"};

    private static final int RULE_Input_KIND = 0;
    private static final int RULE_Statement_KIND = 1;
    private static final int RULE_DdlStatement_KIND = 2;
    private static final int RULE_CreateStatement_KIND = 3;
    private static final int RULE_AlterStatement_KIND = 4;
    private static final int RULE_DropStatement_KIND = 5;
    private static final int RULE_DmlStatement_KIND = 6;
    private static final int RULE_CreateTableStmt_KIND = 7;
    private static final int RULE_IfNotExists_KIND = 8;
    private static final int RULE_IfExists_KIND = 9;
    private static final int RULE_TableElementList_KIND = 10;
    private static final int RULE_TableElement_KIND = 11;
    private static final int RULE_ColumnDef_KIND = 12;
    private static final int RULE_ColConstraint_KIND = 13;
    private static final int RULE_ColConstraintElem_KIND = 14;
    private static final int RULE_NotNullConstraint_KIND = 15;
    private static final int RULE_PrimaryKeyColConstraint_KIND = 16;
    private static final int RULE_CheckColConstraint_KIND = 17;
    private static final int RULE_DefaultClause_KIND = 18;
    private static final int RULE_CollateClause_KIND = 19;
    private static final int RULE_ReferencesClause_KIND = 20;
    private static final int RULE_GeneratedClause_KIND = 21;
    private static final int RULE_IdentityClause_KIND = 22;
    private static final int RULE_IdentitySpec_KIND = 23;
    private static final int RULE_TableConstraint_KIND = 24;
    private static final int RULE_ConstraintName_KIND = 25;
    private static final int RULE_TableConstraintElem_KIND = 26;
    private static final int RULE_PrimaryKeyTblConstraint_KIND = 27;
    private static final int RULE_UniqueTblConstraint_KIND = 28;
    private static final int RULE_CheckTblConstraint_KIND = 29;
    private static final int RULE_NoInheritClause_KIND = 30;
    private static final int RULE_ForeignKeyTblConstraint_KIND = 31;
    private static final int RULE_FkActions_KIND = 32;
    private static final int RULE_FkAction_KIND = 33;
    private static final int RULE_FkActionType_KIND = 34;
    private static final int RULE_FkDeferrable_KIND = 35;
    private static final int RULE_ExcludeTblConstraint_KIND = 36;
    private static final int RULE_ExcludeElementList_KIND = 37;
    private static final int RULE_ExcludeElement_KIND = 38;
    private static final int RULE_NullsDistinct_KIND = 39;
    private static final int RULE_IndexOptions_KIND = 40;
    private static final int RULE_IncludeClause_KIND = 41;
    private static final int RULE_WithStorageParams_KIND = 42;
    private static final int RULE_StorageParamList_KIND = 43;
    private static final int RULE_StorageParam_KIND = 44;
    private static final int RULE_UsingIndexTblspace_KIND = 45;
    private static final int RULE_TableOptions_KIND = 46;
    private static final int RULE_PartitionByClause_KIND = 47;
    private static final int RULE_PartitionKeyList_KIND = 48;
    private static final int RULE_PartitionKey_KIND = 49;
    private static final int RULE_InheritsClause_KIND = 50;
    private static final int RULE_TablespaceClause_KIND = 51;
    private static final int RULE_ColumnList_KIND = 52;
    private static final int RULE_QualifiedNameList_KIND = 53;
    private static final int RULE_AlterTableStmt_KIND = 54;
    private static final int RULE_AlterTableActions_KIND = 55;
    private static final int RULE_AlterTableAction_KIND = 56;
    private static final int RULE_AddColumnAction_KIND = 57;
    private static final int RULE_DropColumnAction_KIND = 58;
    private static final int RULE_AlterColumnAction_KIND = 59;
    private static final int RULE_AlterColumnCmd_KIND = 60;
    private static final int RULE_SetDataTypeCmd_KIND = 61;
    private static final int RULE_SetDefaultCmd_KIND = 62;
    private static final int RULE_DropDefaultCmd_KIND = 63;
    private static final int RULE_SetNotNullCmd_KIND = 64;
    private static final int RULE_DropNotNullCmd_KIND = 65;
    private static final int RULE_SetStatisticsCmd_KIND = 66;
    private static final int RULE_SetStorageCmd_KIND = 67;
    private static final int RULE_AddIdentityCmd_KIND = 68;
    private static final int RULE_DropIdentityCmd_KIND = 69;
    private static final int RULE_AddConstraintAction_KIND = 70;
    private static final int RULE_DropConstraintAction_KIND = 71;
    private static final int RULE_ValidateConstraintAction_KIND = 72;
    private static final int RULE_RenameConstraintAction_KIND = 73;
    private static final int RULE_NotValidClause_KIND = 74;
    private static final int RULE_AlterOwnerAction_KIND = 75;
    private static final int RULE_RenameAction_KIND = 76;
    private static final int RULE_SetSchemaAction_KIND = 77;
    private static final int RULE_SetTablespaceAction_KIND = 78;
    private static final int RULE_AttachPartition_KIND = 79;
    private static final int RULE_DetachPartition_KIND = 80;
    private static final int RULE_ForValuesClause_KIND = 81;
    private static final int RULE_DropTableStmt_KIND = 82;
    private static final int RULE_CreateIndexStmt_KIND = 83;
    private static final int RULE_UsingClause_KIND = 84;
    private static final int RULE_IndexElemList_KIND = 85;
    private static final int RULE_IndexElem_KIND = 86;
    private static final int RULE_OpClass_KIND = 87;
    private static final int RULE_NullsOrder_KIND = 88;
    private static final int RULE_AlterIndexStmt_KIND = 89;
    private static final int RULE_DropIndexStmt_KIND = 90;
    private static final int RULE_CreateSequenceStmt_KIND = 91;
    private static final int RULE_AlterSequenceStmt_KIND = 92;
    private static final int RULE_DropSequenceStmt_KIND = 93;
    private static final int RULE_SequenceOptions_KIND = 94;
    private static final int RULE_SequenceOption_KIND = 95;
    private static final int RULE_CreateTypeStmt_KIND = 96;
    private static final int RULE_EnumLabelList_KIND = 97;
    private static final int RULE_CompositeFieldList_KIND = 98;
    private static final int RULE_CompositeField_KIND = 99;
    private static final int RULE_RangeOptionList_KIND = 100;
    private static final int RULE_RangeOption_KIND = 101;
    private static final int RULE_DomainConstraint_KIND = 102;
    private static final int RULE_AlterTypeStmt_KIND = 103;
    private static final int RULE_DropTypeStmt_KIND = 104;
    private static final int RULE_CreateSchemaStmt_KIND = 105;
    private static final int RULE_AlterSchemaStmt_KIND = 106;
    private static final int RULE_DropSchemaStmt_KIND = 107;
    private static final int RULE_CreateViewStmt_KIND = 108;
    private static final int RULE_CheckOptionClause_KIND = 109;
    private static final int RULE_CreateMatViewStmt_KIND = 110;
    private static final int RULE_AlterViewStmt_KIND = 111;
    private static final int RULE_DropViewStmt_KIND = 112;
    private static final int RULE_DropMatViewStmt_KIND = 113;
    private static final int RULE_CreateExtensionStmt_KIND = 114;
    private static final int RULE_ExtensionOptions_KIND = 115;
    private static final int RULE_ExtensionOption_KIND = 116;
    private static final int RULE_DropExtensionStmt_KIND = 117;
    private static final int RULE_CommentStatement_KIND = 118;
    private static final int RULE_CommentTarget_KIND = 119;
    private static final int RULE_FuncArgTypes_KIND = 120;
    private static final int RULE_FuncArgType_KIND = 121;
    private static final int RULE_GrantStatement_KIND = 122;
    private static final int RULE_RevokeStatement_KIND = 123;
    private static final int RULE_PrivilegeList_KIND = 124;
    private static final int RULE_Privilege_KIND = 125;
    private static final int RULE_GrantTarget_KIND = 126;
    private static final int RULE_GranteeList_KIND = 127;
    private static final int RULE_Grantee_KIND = 128;
    private static final int RULE_AlterDefaultPrivilegesPassthrough_KIND = 129;
    private static final int RULE_CreateFunctionPassthrough_KIND = 130;
    private static final int RULE_CreateTriggerPassthrough_KIND = 131;
    private static final int RULE_DropFunctionPassthrough_KIND = 132;
    private static final int RULE_DropTriggerPassthrough_KIND = 133;
    private static final int RULE_SelectStmt_KIND = 134;
    private static final int RULE_SelectCore_KIND = 135;
    private static final int RULE_SetQuantifier_KIND = 136;
    private static final int RULE_TargetList_KIND = 137;
    private static final int RULE_TargetElem_KIND = 138;
    private static final int RULE_StarExpr_KIND = 139;
    private static final int RULE_IntoClause_KIND = 140;
    private static final int RULE_FromClause_KIND = 141;
    private static final int RULE_FromList_KIND = 142;
    private static final int RULE_TableRef_KIND = 143;
    private static final int RULE_TableRefBase_KIND = 144;
    private static final int RULE_BaseTableRef_KIND = 145;
    private static final int RULE_SubqueryRef_KIND = 146;
    private static final int RULE_LateralRef_KIND = 147;
    private static final int RULE_FuncTableRef_KIND = 148;
    private static final int RULE_WithOrdinality_KIND = 149;
    private static final int RULE_Alias_KIND = 150;
    private static final int RULE_TablesampleClause_KIND = 151;
    private static final int RULE_JoinExpr_KIND = 152;
    private static final int RULE_JoinClause_KIND = 153;
    private static final int RULE_JoinType_KIND = 154;
    private static final int RULE_JoinQual_KIND = 155;
    private static final int RULE_WhereClause_KIND = 156;
    private static final int RULE_GroupByClause_KIND = 157;
    private static final int RULE_GroupByList_KIND = 158;
    private static final int RULE_GroupByElem_KIND = 159;
    private static final int RULE_HavingClause_KIND = 160;
    private static final int RULE_WindowClause_KIND = 161;
    private static final int RULE_WindowDefList_KIND = 162;
    private static final int RULE_WindowDef_KIND = 163;
    private static final int RULE_WindowSpec_KIND = 164;
    private static final int RULE_PartitionClause_KIND = 165;
    private static final int RULE_FrameClause_KIND = 166;
    private static final int RULE_FrameExtent_KIND = 167;
    private static final int RULE_FrameBound_KIND = 168;
    private static final int RULE_FrameExclusion_KIND = 169;
    private static final int RULE_WithClause_KIND = 170;
    private static final int RULE_CteList_KIND = 171;
    private static final int RULE_CteDef_KIND = 172;
    private static final int RULE_SetOp_KIND = 173;
    private static final int RULE_OrderByClause_KIND = 174;
    private static final int RULE_OrderByList_KIND = 175;
    private static final int RULE_OrderByItem_KIND = 176;
    private static final int RULE_LimitClause_KIND = 177;
    private static final int RULE_OffsetClause_KIND = 178;
    private static final int RULE_FetchClause_KIND = 179;
    private static final int RULE_InsertStmt_KIND = 180;
    private static final int RULE_InsertSource_KIND = 181;
    private static final int RULE_ValuesClause_KIND = 182;
    private static final int RULE_ValueRowList_KIND = 183;
    private static final int RULE_ExprOrDefaultList_KIND = 184;
    private static final int RULE_ExprOrDefault_KIND = 185;
    private static final int RULE_OnConflictClause_KIND = 186;
    private static final int RULE_ConflictTarget_KIND = 187;
    private static final int RULE_ConflictAction_KIND = 188;
    private static final int RULE_ReturningClause_KIND = 189;
    private static final int RULE_UpdateStmt_KIND = 190;
    private static final int RULE_UpdateSetList_KIND = 191;
    private static final int RULE_UpdateSetItem_KIND = 192;
    private static final int RULE_DeleteStmt_KIND = 193;
    private static final int RULE_UsingClauseDelete_KIND = 194;
    private static final int RULE_PassthroughStatement_KIND = 195;
    private static final int RULE_TransactionStmt_KIND = 196;
    private static final int RULE_SessionStmt_KIND = 197;
    private static final int RULE_UtilityStmt_KIND = 198;
    private static final int RULE_TruncateStmt_KIND = 199;
    private static final int RULE_ExplainStmt_KIND = 200;
    private static final int RULE_CopyStmt_KIND = 201;
    private static final int RULE_RefreshMatViewStmt_KIND = 202;
    private static final int RULE_RestOfStatement_KIND = 203;
    private static final int RULE_Expr_KIND = 204;
    private static final int RULE_OrExpr_KIND = 205;
    private static final int RULE_AndExpr_KIND = 206;
    private static final int RULE_NotExpr_KIND = 207;
    private static final int RULE_CompareExpr_KIND = 208;
    private static final int RULE_IsExpr_KIND = 209;
    private static final int RULE_IsClause_KIND = 210;
    private static final int RULE_InExpr_KIND = 211;
    private static final int RULE_BetweenExpr_KIND = 212;
    private static final int RULE_LikeExpr_KIND = 213;
    private static final int RULE_SimilarToExpr_KIND = 214;
    private static final int RULE_IsDistinctFrom_KIND = 215;
    private static final int RULE_AddExpr_KIND = 216;
    private static final int RULE_MulExpr_KIND = 217;
    private static final int RULE_UnaryExpr_KIND = 218;
    private static final int RULE_ExponentExpr_KIND = 219;
    private static final int RULE_ConcatExpr_KIND = 220;
    private static final int RULE_ArrayExpr_KIND = 221;
    private static final int RULE_TypeCastExpr_KIND = 222;
    private static final int RULE_PostfixExpr_KIND = 223;
    private static final int RULE_PostfixOp_KIND = 224;
    private static final int RULE_PrimaryExpr_KIND = 225;
    private static final int RULE_ColRef_KIND = 226;
    private static final int RULE_ExistsExpr_KIND = 227;
    private static final int RULE_SubqueryExpr_KIND = 228;
    private static final int RULE_AnyAllExpr_KIND = 229;
    private static final int RULE_RowExpr_KIND = 230;
    private static final int RULE_ArrayExprConstructor_KIND = 231;
    private static final int RULE_CastExpr_KIND = 232;
    private static final int RULE_CaseExpr_KIND = 233;
    private static final int RULE_WhenClause_KIND = 234;
    private static final int RULE_ElseClause_KIND = 235;
    private static final int RULE_CoalesceExpr_KIND = 236;
    private static final int RULE_NullIfExpr_KIND = 237;
    private static final int RULE_GreatestLeastExpr_KIND = 238;
    private static final int RULE_ExtractExpr_KIND = 239;
    private static final int RULE_PositionExpr_KIND = 240;
    private static final int RULE_SubstringExpr_KIND = 241;
    private static final int RULE_TrimExpr_KIND = 242;
    private static final int RULE_OverlayExpr_KIND = 243;
    private static final int RULE_TypedLiteral_KIND = 244;
    private static final int RULE_SpecialFuncExpr_KIND = 245;
    private static final int RULE_FuncCall_KIND = 246;
    private static final int RULE_FuncCallArgs_KIND = 247;
    private static final int RULE_FuncName_KIND = 248;
    private static final int RULE_FilterClause_KIND = 249;
    private static final int RULE_OverClause_KIND = 250;
    private static final int RULE_WithinGroupClause_KIND = 251;
    private static final int RULE_ExprList_KIND = 252;
    private static final int RULE_Operator_KIND = 253;
    private static final int RULE_DataType_KIND = 254;
    private static final int RULE_ArrayType_KIND = 255;
    private static final int RULE_ScalarType_KIND = 256;
    private static final int RULE_NumericType_KIND = 257;
    private static final int RULE_CharType_KIND = 258;
    private static final int RULE_DateTimeType_KIND = 259;
    private static final int RULE_TimestampType_KIND = 260;
    private static final int RULE_TimeType_KIND = 261;
    private static final int RULE_IntervalType_KIND = 262;
    private static final int RULE_IntervalField_KIND = 263;
    private static final int RULE_BitType_KIND = 264;
    private static final int RULE_TypeModifiers_KIND = 265;
    private static final int RULE_QualifiedTypeName_KIND = 266;
    private static final int RULE_ColLabel_KIND = 267;
    private static final int RULE_QualifiedName_KIND = 268;
    private static final int RULE_Literal_KIND = 269;
    private static final int RULE_SignedNumericLiteral_KIND = 270;
    private static final int RULE_StringLiteral_KIND = 271;
    private static final int RULE_DollarString_KIND = 272;
    private static final int RULE_ClauseKeyword_KIND = 273;
    private static final int RULE_ERROR_KIND = 274;
    private static final int RULE_ROOT_KIND = 275;

    private static final int KIND_INLINE__SEMI = 365;
    private static final int KIND_CREATEKW = 41;
    private static final int KIND_ALTERKW = 42;
    private static final int KIND_DROPKW = 43;
    private static final int KIND_TEMPKW = 211;
    private static final int KIND_UNLOGGEDKW = 224;
    private static final int KIND_TABLEKW = 50;
    private static final int KIND_INLINE__LPAREN = 366;
    private static final int KIND_INLINE__RPAREN = 367;
    private static final int KIND_IFKW = 75;
    private static final int KIND_NOTKW = 67;
    private static final int KIND_EXISTSKW = 76;
    private static final int KIND_INLINE__COMMA = 368;
    private static final int KIND_COLID = 28;
    private static final int KIND_CONCURRENTLYKW = 12;
    private static final int KIND_DATETYPE = 18;
    private static final int KIND_UUIDTYPE = 21;
    private static final int KIND_BYTEATYPE = 22;
    private static final int KIND_XMLTYPE = 23;
    private static final int KIND_MONEYTYPE = 24;
    private static final int KIND_INSERTKW = 45;
    private static final int KIND_UPDATEKW = 46;
    private static final int KIND_DELETEKW = 47;
    private static final int KIND_INDEXKW = 51;
    private static final int KIND_VIEWKW = 52;
    private static final int KIND_SCHEMAKW = 53;
    private static final int KIND_SEQUENCEKW = 54;
    private static final int KIND_TYPEKW = 55;
    private static final int KIND_FUNCTIONKW = 56;
    private static final int KIND_PROCEDUREKW = 57;
    private static final int KIND_TRIGGERKW = 58;
    private static final int KIND_EXTENSIONKW = 59;
    private static final int KIND_KEYKW = 61;
    private static final int KIND_SETKW = 70;
    private static final int KIND_ADDKW = 71;
    private static final int KIND_RENAMEKW = 73;
    private static final int KIND_CASCADEKW = 77;
    private static final int KIND_RESTRICTKW = 78;
    private static final int KIND_NOKW = 79;
    private static final int KIND_ACTIONKW = 80;
    private static final int KIND_WITHOUTKW = 84;
    private static final int KIND_ISKW = 89;
    private static final int KIND_LIKEKW = 90;
    private static final int KIND_ILIKEKW = 91;
    private static final int KIND_SIMILARKW = 92;
    private static final int KIND_BETWEENKW = 93;
    private static final int KIND_COALESCEKW = 100;
    private static final int KIND_NULLIFKW = 101;
    private static final int KIND_GREATESTKW = 102;
    private static final int KIND_LEASTKW = 103;
    private static final int KIND_EXTRACTKW = 104;
    private static final int KIND_POSITIONKW = 105;
    private static final int KIND_SUBSTRINGKW = 106;
    private static final int KIND_TRIMKW = 107;
    private static final int KIND_OVERLAYKW = 108;
    private static final int KIND_UNKNOWNKW = 112;
    private static final int KIND_ISNULLKW = 113;
    private static final int KIND_NOTNULLKW = 114;
    private static final int KIND_ESCAPEKW = 118;
    private static final int KIND_ROWKW = 123;
    private static final int KIND_ROWSKW = 124;
    private static final int KIND_NULLSKW = 127;
    private static final int KIND_FIRSTKW = 128;
    private static final int KIND_LASTKW = 129;
    private static final int KIND_NEXTKW = 131;
    private static final int KIND_TIESKW = 132;
    private static final int KIND_BYKW = 134;
    private static final int KIND_JOINKW = 143;
    private static final int KIND_CROSSKW = 144;
    private static final int KIND_INNERKW = 145;
    private static final int KIND_LEFTKW = 146;
    private static final int KIND_RIGHTKW = 147;
    private static final int KIND_FULLKW = 148;
    private static final int KIND_OUTERKW = 149;
    private static final int KIND_NATURALKW = 150;
    private static final int KIND_VALUESKW = 153;
    private static final int KIND_NOTHINGKW = 155;
    private static final int KIND_CONFLICTKW = 156;
    private static final int KIND_PARTITIONKW = 158;
    private static final int KIND_RANGEKW = 159;
    private static final int KIND_LISTKW = 160;
    private static final int KIND_HASHKW = 161;
    private static final int KIND_ATTACHKW = 162;
    private static final int KIND_DETACHKW = 163;
    private static final int KIND_FINALIZEKW = 164;
    private static final int KIND_INHERITSKW = 166;
    private static final int KIND_TABLESPACEKW = 167;
    private static final int KIND_INCLUDEKW = 168;
    private static final int KIND_EXCLUDEKW = 169;
    private static final int KIND_ENUMKW = 170;
    private static final int KIND_DOMAINKW = 171;
    private static final int KIND_ATTRIBUTEKW = 172;
    private static final int KIND_VALUEKW = 173;
    private static final int KIND_BEFOREKW = 174;
    private static final int KIND_AFTERKW = 175;
    private static final int KIND_INCREMENTKW = 176;
    private static final int KIND_MINVALUEKW = 177;
    private static final int KIND_MAXVALUEKW = 178;
    private static final int KIND_STARTKW = 179;
    private static final int KIND_CACHEKW = 180;
    private static final int KIND_CYCLEKW = 181;
    private static final int KIND_OWNEDKW = 182;
    private static final int KIND_RESTARTKW = 183;
    private static final int KIND_NONEKW = 184;
    private static final int KIND_GENERATEDKW = 185;
    private static final int KIND_ALWAYSKW = 186;
    private static final int KIND_IDENTITYKW = 187;
    private static final int KIND_STOREDKW = 188;
    private static final int KIND_DEFERREDKW = 192;
    private static final int KIND_IMMEDIATEKW = 193;
    private static final int KIND_VALIDKW = 194;
    private static final int KIND_VALIDATEKW = 195;
    private static final int KIND_INHERITKW = 196;
    private static final int KIND_COMMENTKW = 197;
    private static final int KIND_REVOKEKW = 199;
    private static final int KIND_PRIVILEGESKW = 200;
    private static final int KIND_PUBLICKW = 201;
    private static final int KIND_OPTIONKW = 202;
    private static final int KIND_TABLESKW = 203;
    private static final int KIND_SEQUENCESKW = 204;
    private static final int KIND_FUNCTIONSKW = 205;
    private static final int KIND_SCHEMASKW = 206;
    private static final int KIND_EXECUTEKW = 207;
    private static final int KIND_USAGEKW = 208;
    private static final int KIND_CONNECTKW = 209;
    private static final int KIND_TEMPORARYKW = 210;
    private static final int KIND_TRUNCATEKW = 212;
    private static final int KIND_MATERIALIZEDKW = 213;
    private static final int KIND_RECURSIVEKW = 214;
    private static final int KIND_REPLACEKW = 215;
    private static final int KIND_CASCADEDKW = 216;
    private static final int KIND_LOCALKW = 217;
    private static final int KIND_AUTHORIZATIONKW = 218;
    private static final int KIND_OWNERKW = 219;
    private static final int KIND_VERSIONKW = 220;
    private static final int KIND_DATAKW = 221;
    private static final int KIND_STATISTICSKW = 222;
    private static final int KIND_STORAGEKW = 223;
    private static final int KIND_FILTERKW = 225;
    private static final int KIND_OVERKW = 226;
    private static final int KIND_WITHINKW = 227;
    private static final int KIND_ORDINALITYKW = 228;
    private static final int KIND_TABLESAMPLEKW = 229;
    private static final int KIND_GROUPINGSETSKW = 230;
    private static final int KIND_ROLLUPKW = 231;
    private static final int KIND_CUBEKW = 232;
    private static final int KIND_PRECEDINGKW = 233;
    private static final int KIND_FOLLOWINGKW = 234;
    private static final int KIND_CURRENTKW = 235;
    private static final int KIND_UNBOUNDEDKW = 236;
    private static final int KIND_GROUPSKW = 237;
    private static final int KIND_OTHERSKW = 238;
    private static final int KIND_SEARCHKW = 239;
    private static final int KIND_BREADTHKW = 240;
    private static final int KIND_DEPTHKW = 241;
    private static final int KIND_BEGINKW = 242;
    private static final int KIND_COMMITKW = 243;
    private static final int KIND_ROLLBACKKW = 244;
    private static final int KIND_SAVEPOINTKW = 245;
    private static final int KIND_RELEASEKW = 246;
    private static final int KIND_PREPAREKW = 247;
    private static final int KIND_SHOWKW = 248;
    private static final int KIND_RESETKW = 249;
    private static final int KIND_VACUUMKW = 250;
    private static final int KIND_EXPLAINKW = 252;
    private static final int KIND_COPYKW = 253;
    private static final int KIND_REINDEXKW = 254;
    private static final int KIND_CLUSTERKW = 255;
    private static final int KIND_REFRESHKW = 256;
    private static final int KIND_NOTIFYKW = 257;
    private static final int KIND_LISTENKW = 258;
    private static final int KIND_UNLISTENKW = 259;
    private static final int KIND_LOADKW = 260;
    private static final int KIND_SECURITYLABELKW = 261;
    private static final int KIND_DEALLOCATEKW = 262;
    private static final int KIND_INOUTKW = 263;
    private static final int KIND_OUTKW = 264;
    private static final int KIND_AGGREGATEKW = 266;
    private static final int KIND_YEARKW = 267;
    private static final int KIND_MONTHKW = 268;
    private static final int KIND_DAYKW = 269;
    private static final int KIND_HOURKW = 270;
    private static final int KIND_MINUTEKW = 271;
    private static final int KIND_SECONDKW = 272;
    private static final int KIND_TIMEKW = 273;
    private static final int KIND_ZONEKW = 274;
    private static final int KIND_INLINE_TIMESTAMPTZ_CI = 289;
    private static final int KIND_INLINE_PRECISION_CI = 291;
    private static final int KIND_INLINE_CHARACTER_CI = 292;
    private static final int KIND_INLINE_TIMESTAMP_CI = 293;
    private static final int KIND_INLINE_SMALLINT_CI = 297;
    private static final int KIND_INLINE_INTERVAL_CI = 298;
    private static final int KIND_INLINE_INTEGER_CI = 300;
    private static final int KIND_INLINE_NUMERIC_CI = 301;
    private static final int KIND_INLINE_DECIMAL_CI = 302;
    private static final int KIND_INLINE_VARYING_CI = 303;
    private static final int KIND_INLINE_VARCHAR_CI = 304;
    private static final int KIND_INLINE_DOUBLE_CI = 306;
    private static final int KIND_INLINE_BIGINT_CI = 307;
    private static final int KIND_INLINE_FLOAT8_CI = 308;
    private static final int KIND_INLINE_FLOAT4_CI = 309;
    private static final int KIND_INLINE_CITEXT_CI = 310;
    private static final int KIND_INLINE_TIMETZ_CI = 311;
    private static final int KIND_INLINE_VARBIT_CI = 312;
    private static final int KIND_INLINE_FLOAT_CI = 321;
    private static final int KIND_INLINE_INT8_CI = 333;
    private static final int KIND_INLINE_INT4_CI = 334;
    private static final int KIND_INLINE_INT2_CI = 335;
    private static final int KIND_INLINE_REAL_CI = 336;
    private static final int KIND_INLINE_CHAR_CI = 337;
    private static final int KIND_INLINE_TEXT_CI = 338;
    private static final int KIND_INLINE_NAME_CI = 339;
    private static final int KIND_INLINE_INT_CI = 348;
    private static final int KIND_INLINE_BIT_CI = 349;
    private static final int KIND_INLINE__DOLLAR_DOLLAR = 362;
    private static final int KIND_INLINE__DOLLAR = 381;
    private static final int KIND_INLINE_BTREE_CI = 382;
    private static final int KIND_INLINE_GIN_CI = 383;
    private static final int KIND_INLINE_GIST_CI = 384;
    private static final int KIND_INLINE_BRIN_CI = 385;
    private static final int KIND_INLINE_SPGIST_CI = 386;
    private static final int KIND_INLINE_BOOLEAN_CI = 393;
    private static final int KIND_INLINE_BOOL_CI = 394;
    private static final int KIND_INLINE_JSONB_CI = 395;
    private static final int KIND_INLINE_JSON_CI = 396;
    private static final int KIND_INLINE_BIGSERIAL_CI = 397;
    private static final int KIND_INLINE_SMALLSERIAL_CI = 398;
    private static final int KIND_INLINE_SERIAL8_CI = 399;
    private static final int KIND_INLINE_SERIAL4_CI = 400;
    private static final int KIND_INLINE_SERIAL2_CI = 401;
    private static final int KIND_INLINE_SERIAL_CI = 402;
    private static final int KIND_INLINE_INET_CI = 403;
    private static final int KIND_INLINE_CIDR_CI = 404;
    private static final int KIND_INLINE_MACADDR8_CI = 405;
    private static final int KIND_INLINE_MACADDR_CI = 406;
    private static final int KIND_INLINE_TSVECTOR_CI = 407;
    private static final int KIND_INLINE_TSQUERY_CI = 408;
    private static final int KIND_NULLCONSTRAINT = 5;
    private static final int KIND_UNIQUECOLCONSTRAINT = 6;
    private static final int KIND_PRIMARYKW = 60;
    private static final int KIND_CHECKKW = 66;
    private static final int KIND_DEFAULTKW = 69;
    private static final int KIND_COLLATEKW = 189;
    private static final int KIND_REFERENCESKW = 63;
    private static final int KIND_ASKW = 82;
    private static final int KIND_CONSTRAINTKW = 64;
    private static final int KIND_FOREIGNKW = 62;
    private static final int KIND_ONKW = 81;
    private static final int KIND_DEFERRABLEKW = 190;
    private static final int KIND_INITIALLYKW = 191;
    private static final int KIND_WITHKW = 83;
    private static final int KIND_DISTINCTKW = 115;
    private static final int KIND_INLINE__EQ = 369;
    private static final int KIND_USINGKW = 85;
    private static final int KIND_ONLYKW = 9;
    private static final int KIND_COLUMNKW = 72;
    private static final int KIND_TOKW = 74;
    private static final int KIND_FORKW = 165;
    private static final int KIND_INKW = 86;
    private static final int KIND_FROMKW = 48;
    private static final int KIND_ASCKW = 125;
    private static final int KIND_DESCKW = 126;
    private static final int KIND_ORKW = 88;
    private static final int KIND_VARIADICKW = 265;
    private static final int KIND_GRANTKW = 198;
    private static final int KIND_ALLKW = 119;
    private static final int KIND_SELECTKW = 44;
    private static final int KIND_GROUPKW = 135;
    private static final int KIND_INLINE__DOT = 370;
    private static final int KIND_INLINE__STAR = 371;
    private static final int KIND_INTOKW = 152;
    private static final int KIND_LATERALKW = 151;
    private static final int KIND_WHEREKW = 49;
    private static final int KIND_HAVINGKW = 136;
    private static final int KIND_WINDOWKW = 139;
    private static final int KIND_ORDERKW = 133;
    private static final int KIND_ANDKW = 87;
    private static final int KIND_UNIONKW = 140;
    private static final int KIND_INTERSECTKW = 141;
    private static final int KIND_EXCEPTKW = 142;
    private static final int KIND_LIMITKW = 137;
    private static final int KIND_OFFSETKW = 138;
    private static final int KIND_FETCHKW = 130;
    private static final int KIND_NUMERICLITERAL = 34;
    private static final int KIND_DOKW = 154;
    private static final int KIND_RETURNINGKW = 157;
    private static final int KIND_ENDKW = 98;
    private static final int KIND_ANALYZEKW = 251;
    private static final int KIND_BASICSTRING = 38;
    private static final int KIND_ESCAPESTRING = 39;
    private static final int KIND_INLINE__LT_EQ = 387;
    private static final int KIND_INLINE__GT_EQ = 388;
    private static final int KIND_INLINE__LT_GT = 389;
    private static final int KIND_INLINE__BANG_EQ = 390;
    private static final int KIND_INLINE__LT = 391;
    private static final int KIND_INLINE__GT = 392;
    private static final int KIND_TRUEKW = 110;
    private static final int KIND_FALSEKW = 111;
    private static final int KIND_SYMMETRICKW = 116;
    private static final int KIND_ASYMMETRICKW = 117;
    private static final int KIND_INLINE__PLUS = 372;
    private static final int KIND_INLINE__MINUS_GT = 351;
    private static final int KIND_INLINE__MINUS = 373;
    private static final int KIND_INLINE__SLASH = 374;
    private static final int KIND_INLINE__PERCENT = 375;
    private static final int KIND_INLINE__CARET = 376;
    private static final int KIND_INLINE__PIPE_PIPE = 352;
    private static final int KIND_INLINE__LBRACK = 377;
    private static final int KIND_INLINE__COLON = 378;
    private static final int KIND_INLINE__RBRACK = 379;
    private static final int KIND_INLINE__COLON_COLON = 353;
    private static final int KIND_INLINE__MINUS_GT_GT = 346;
    private static final int KIND_INLINE__HASH_GT_GT = 347;
    private static final int KIND_INLINE__HASH_GT = 354;
    private static final int KIND_INLINE__AT_GT = 355;
    private static final int KIND_INLINE__LT_AT = 356;
    private static final int KIND_INLINE__AMP_AMP = 357;
    private static final int KIND_PARAMREF = 17;
    private static final int KIND_ANYKW = 120;
    private static final int KIND_SOMEKW = 121;
    private static final int KIND_ARRAYKW = 122;
    private static final int KIND_CASTKW = 99;
    private static final int KIND_CASEKW = 94;
    private static final int KIND_WHENKW = 95;
    private static final int KIND_THENKW = 96;
    private static final int KIND_ELSEKW = 97;
    private static final int KIND_LEADINGKW = 275;
    private static final int KIND_TRAILINGKW = 276;
    private static final int KIND_BOTHKW = 277;
    private static final int KIND_PLACINGKW = 109;
    private static final int KIND_INLINE_CURRENT_TIMESTAMP_CI = 280;
    private static final int KIND_INLINE_CURRENT_TIME_CI = 284;
    private static final int KIND_INLINE_LOCALTIMESTAMP_CI = 282;
    private static final int KIND_INLINE_LOCALTIME_CI = 290;
    private static final int KIND_INLINE_CURRENT_CATALOG_CI = 281;
    private static final int KIND_INLINE_CURRENT_DATE_CI = 285;
    private static final int KIND_INLINE_CURRENT_ROLE_CI = 286;
    private static final int KIND_INLINE_CURRENT_SCHEMA_CI = 283;
    private static final int KIND_INLINE_CURRENT_USER_CI = 287;
    private static final int KIND_INLINE_SESSION_USER_CI = 288;
    private static final int KIND_INLINE_USER_CI = 332;
    private static final int KIND_INLINE__AT_AT = 358;
    private static final int KIND_INLINE__AT_QMARK = 359;
    private static final int KIND_INLINE__QMARK = 380;
    private static final int KIND_INLINE__QMARK_PIPE = 360;
    private static final int KIND_INLINE__QMARK_AMP = 361;
    private static final int KIND_INLINE_ANALYSE_CI = 409;

    private static final int[] DEFAULT_SYNC = new int[] {365, 367, 368, 379};

    private static final int[] ALIAS_INDEXMETHOD = new int[] {161, 382, 383, 384, 385, 386};
    private static final int[] ALIAS_COMPAREOP = new int[] {369, 387, 388, 389, 390, 391, 392};
    private static final int[] ALIAS_SERIALTYPE = new int[] {397, 398, 399, 400, 401, 402};
    private static final int[] ALIAS_RESERVEDKEYWORD = new int[] {5, 6, 9, 41, 44, 48, 49, 50, 60, 62, 63, 64, 66, 67, 69, 72, 74, 81, 82, 83, 85, 86, 87, 88, 94, 95, 96, 97, 98, 99, 109, 110, 111, 115, 116, 117, 119, 120, 121, 122, 125, 126, 130, 133, 135, 136, 137, 138, 139, 140, 141, 142, 151, 152, 154, 157, 165, 189, 190, 191, 198, 251, 265, 275, 276, 277, 280, 281, 282, 283, 284, 285, 286, 287, 288, 290, 332, 409};

    private static final int[] IDFALL_COLID = new int[] {12, 18, 21, 22, 23, 24, 28, 42, 43, 45, 46, 47, 51, 52, 53, 54, 55, 56, 57, 58, 59, 61, 70, 71, 73, 75, 76, 77, 78, 79, 80, 84, 89, 90, 91, 92, 93, 100, 101, 102, 103, 104, 105, 106, 107, 108, 112, 113, 114, 118, 123, 124, 127, 128, 129, 131, 132, 134, 143, 144, 145, 146, 147, 148, 149, 150, 153, 155, 156, 158, 159, 160, 161, 162, 163, 164, 166, 167, 168, 169, 170, 171, 172, 173, 174, 175, 176, 177, 178, 179, 180, 181, 182, 183, 184, 185, 186, 187, 188, 192, 193, 194, 195, 196, 197, 199, 200, 201, 202, 203, 204, 205, 206, 207, 208, 209, 210, 211, 212, 213, 214, 215, 216, 217, 218, 219, 220, 221, 222, 223, 224, 225, 226, 227, 228, 229, 230, 231, 232, 233, 234, 235, 236, 237, 238, 239, 240, 241, 242, 243, 244, 245, 246, 247, 248, 249, 250, 252, 253, 254, 255, 256, 257, 258, 259, 260, 261, 262, 263, 264, 266, 267, 268, 269, 270, 271, 272, 273, 274, 289, 291, 292, 293, 297, 298, 300, 301, 302, 303, 304, 306, 307, 308, 309, 310, 311, 312, 321, 333, 334, 335, 336, 337, 338, 339, 348, 349, 362, 381, 382, 383, 384, 385, 386, 393, 394, 395, 396, 397, 398, 399, 400, 401, 402, 403, 404, 405, 406, 407, 408};

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
            case RULE_IfNotExists_KIND: return p.parseIfNotExists(parent);
            case RULE_IfExists_KIND: return p.parseIfExists(parent);
            case RULE_TableElementList_KIND: return p.parseTableElementList(parent);
            case RULE_TableElement_KIND: return p.parseTableElement(parent);
            case RULE_ColumnDef_KIND: return p.parseColumnDef(parent);
            case RULE_ColConstraint_KIND: return p.parseColConstraint(parent);
            case RULE_ColConstraintElem_KIND: return p.parseColConstraintElem(parent);
            case RULE_NotNullConstraint_KIND: return p.parseNotNullConstraint(parent);
            case RULE_PrimaryKeyColConstraint_KIND: return p.parsePrimaryKeyColConstraint(parent);
            case RULE_CheckColConstraint_KIND: return p.parseCheckColConstraint(parent);
            case RULE_DefaultClause_KIND: return p.parseDefaultClause(parent);
            case RULE_CollateClause_KIND: return p.parseCollateClause(parent);
            case RULE_ReferencesClause_KIND: return p.parseReferencesClause(parent);
            case RULE_GeneratedClause_KIND: return p.parseGeneratedClause(parent);
            case RULE_IdentityClause_KIND: return p.parseIdentityClause(parent);
            case RULE_IdentitySpec_KIND: return p.parseIdentitySpec(parent);
            case RULE_TableConstraint_KIND: return p.parseTableConstraint(parent);
            case RULE_ConstraintName_KIND: return p.parseConstraintName(parent);
            case RULE_TableConstraintElem_KIND: return p.parseTableConstraintElem(parent);
            case RULE_PrimaryKeyTblConstraint_KIND: return p.parsePrimaryKeyTblConstraint(parent);
            case RULE_UniqueTblConstraint_KIND: return p.parseUniqueTblConstraint(parent);
            case RULE_CheckTblConstraint_KIND: return p.parseCheckTblConstraint(parent);
            case RULE_NoInheritClause_KIND: return p.parseNoInheritClause(parent);
            case RULE_ForeignKeyTblConstraint_KIND: return p.parseForeignKeyTblConstraint(parent);
            case RULE_FkActions_KIND: return p.parseFkActions(parent);
            case RULE_FkAction_KIND: return p.parseFkAction(parent);
            case RULE_FkActionType_KIND: return p.parseFkActionType(parent);
            case RULE_FkDeferrable_KIND: return p.parseFkDeferrable(parent);
            case RULE_ExcludeTblConstraint_KIND: return p.parseExcludeTblConstraint(parent);
            case RULE_ExcludeElementList_KIND: return p.parseExcludeElementList(parent);
            case RULE_ExcludeElement_KIND: return p.parseExcludeElement(parent);
            case RULE_NullsDistinct_KIND: return p.parseNullsDistinct(parent);
            case RULE_IndexOptions_KIND: return p.parseIndexOptions(parent);
            case RULE_IncludeClause_KIND: return p.parseIncludeClause(parent);
            case RULE_WithStorageParams_KIND: return p.parseWithStorageParams(parent);
            case RULE_StorageParamList_KIND: return p.parseStorageParamList(parent);
            case RULE_StorageParam_KIND: return p.parseStorageParam(parent);
            case RULE_UsingIndexTblspace_KIND: return p.parseUsingIndexTblspace(parent);
            case RULE_TableOptions_KIND: return p.parseTableOptions(parent);
            case RULE_PartitionByClause_KIND: return p.parsePartitionByClause(parent);
            case RULE_PartitionKeyList_KIND: return p.parsePartitionKeyList(parent);
            case RULE_PartitionKey_KIND: return p.parsePartitionKey(parent);
            case RULE_InheritsClause_KIND: return p.parseInheritsClause(parent);
            case RULE_TablespaceClause_KIND: return p.parseTablespaceClause(parent);
            case RULE_ColumnList_KIND: return p.parseColumnList(parent);
            case RULE_QualifiedNameList_KIND: return p.parseQualifiedNameList(parent);
            case RULE_AlterTableStmt_KIND: return p.parseAlterTableStmt(parent);
            case RULE_AlterTableActions_KIND: return p.parseAlterTableActions(parent);
            case RULE_AlterTableAction_KIND: return p.parseAlterTableAction(parent);
            case RULE_AddColumnAction_KIND: return p.parseAddColumnAction(parent);
            case RULE_DropColumnAction_KIND: return p.parseDropColumnAction(parent);
            case RULE_AlterColumnAction_KIND: return p.parseAlterColumnAction(parent);
            case RULE_AlterColumnCmd_KIND: return p.parseAlterColumnCmd(parent);
            case RULE_SetDataTypeCmd_KIND: return p.parseSetDataTypeCmd(parent);
            case RULE_SetDefaultCmd_KIND: return p.parseSetDefaultCmd(parent);
            case RULE_DropDefaultCmd_KIND: return p.parseDropDefaultCmd(parent);
            case RULE_SetNotNullCmd_KIND: return p.parseSetNotNullCmd(parent);
            case RULE_DropNotNullCmd_KIND: return p.parseDropNotNullCmd(parent);
            case RULE_SetStatisticsCmd_KIND: return p.parseSetStatisticsCmd(parent);
            case RULE_SetStorageCmd_KIND: return p.parseSetStorageCmd(parent);
            case RULE_AddIdentityCmd_KIND: return p.parseAddIdentityCmd(parent);
            case RULE_DropIdentityCmd_KIND: return p.parseDropIdentityCmd(parent);
            case RULE_AddConstraintAction_KIND: return p.parseAddConstraintAction(parent);
            case RULE_DropConstraintAction_KIND: return p.parseDropConstraintAction(parent);
            case RULE_ValidateConstraintAction_KIND: return p.parseValidateConstraintAction(parent);
            case RULE_RenameConstraintAction_KIND: return p.parseRenameConstraintAction(parent);
            case RULE_NotValidClause_KIND: return p.parseNotValidClause(parent);
            case RULE_AlterOwnerAction_KIND: return p.parseAlterOwnerAction(parent);
            case RULE_RenameAction_KIND: return p.parseRenameAction(parent);
            case RULE_SetSchemaAction_KIND: return p.parseSetSchemaAction(parent);
            case RULE_SetTablespaceAction_KIND: return p.parseSetTablespaceAction(parent);
            case RULE_AttachPartition_KIND: return p.parseAttachPartition(parent);
            case RULE_DetachPartition_KIND: return p.parseDetachPartition(parent);
            case RULE_ForValuesClause_KIND: return p.parseForValuesClause(parent);
            case RULE_DropTableStmt_KIND: return p.parseDropTableStmt(parent);
            case RULE_CreateIndexStmt_KIND: return p.parseCreateIndexStmt(parent);
            case RULE_UsingClause_KIND: return p.parseUsingClause(parent);
            case RULE_IndexElemList_KIND: return p.parseIndexElemList(parent);
            case RULE_IndexElem_KIND: return p.parseIndexElem(parent);
            case RULE_OpClass_KIND: return p.parseOpClass(parent);
            case RULE_NullsOrder_KIND: return p.parseNullsOrder(parent);
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
            case RULE_CreateSchemaStmt_KIND: return p.parseCreateSchemaStmt(parent);
            case RULE_AlterSchemaStmt_KIND: return p.parseAlterSchemaStmt(parent);
            case RULE_DropSchemaStmt_KIND: return p.parseDropSchemaStmt(parent);
            case RULE_CreateViewStmt_KIND: return p.parseCreateViewStmt(parent);
            case RULE_CheckOptionClause_KIND: return p.parseCheckOptionClause(parent);
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
            case RULE_Grantee_KIND: return p.parseGrantee(parent);
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
            case RULE_WithOrdinality_KIND: return p.parseWithOrdinality(parent);
            case RULE_Alias_KIND: return p.parseAlias(parent);
            case RULE_TablesampleClause_KIND: return p.parseTablesampleClause(parent);
            case RULE_JoinExpr_KIND: return p.parseJoinExpr(parent);
            case RULE_JoinClause_KIND: return p.parseJoinClause(parent);
            case RULE_JoinType_KIND: return p.parseJoinType(parent);
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
            case RULE_FrameExclusion_KIND: return p.parseFrameExclusion(parent);
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
            case RULE_SpecialFuncExpr_KIND: return p.parseSpecialFuncExpr(parent);
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
            case RULE_IntervalField_KIND: return p.parseIntervalField(parent);
            case RULE_BitType_KIND: return p.parseBitType(parent);
            case RULE_TypeModifiers_KIND: return p.parseTypeModifiers(parent);
            case RULE_QualifiedTypeName_KIND: return p.parseQualifiedTypeName(parent);
            case RULE_ColLabel_KIND: return p.parseColLabel(parent);
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
        m.put("IfNotExists", RULE_IfNotExists_KIND);
        m.put("IfExists", RULE_IfExists_KIND);
        m.put("TableElementList", RULE_TableElementList_KIND);
        m.put("TableElement", RULE_TableElement_KIND);
        m.put("ColumnDef", RULE_ColumnDef_KIND);
        m.put("ColConstraint", RULE_ColConstraint_KIND);
        m.put("ColConstraintElem", RULE_ColConstraintElem_KIND);
        m.put("NotNullConstraint", RULE_NotNullConstraint_KIND);
        m.put("PrimaryKeyColConstraint", RULE_PrimaryKeyColConstraint_KIND);
        m.put("CheckColConstraint", RULE_CheckColConstraint_KIND);
        m.put("DefaultClause", RULE_DefaultClause_KIND);
        m.put("CollateClause", RULE_CollateClause_KIND);
        m.put("ReferencesClause", RULE_ReferencesClause_KIND);
        m.put("GeneratedClause", RULE_GeneratedClause_KIND);
        m.put("IdentityClause", RULE_IdentityClause_KIND);
        m.put("IdentitySpec", RULE_IdentitySpec_KIND);
        m.put("TableConstraint", RULE_TableConstraint_KIND);
        m.put("ConstraintName", RULE_ConstraintName_KIND);
        m.put("TableConstraintElem", RULE_TableConstraintElem_KIND);
        m.put("PrimaryKeyTblConstraint", RULE_PrimaryKeyTblConstraint_KIND);
        m.put("UniqueTblConstraint", RULE_UniqueTblConstraint_KIND);
        m.put("CheckTblConstraint", RULE_CheckTblConstraint_KIND);
        m.put("NoInheritClause", RULE_NoInheritClause_KIND);
        m.put("ForeignKeyTblConstraint", RULE_ForeignKeyTblConstraint_KIND);
        m.put("FkActions", RULE_FkActions_KIND);
        m.put("FkAction", RULE_FkAction_KIND);
        m.put("FkActionType", RULE_FkActionType_KIND);
        m.put("FkDeferrable", RULE_FkDeferrable_KIND);
        m.put("ExcludeTblConstraint", RULE_ExcludeTblConstraint_KIND);
        m.put("ExcludeElementList", RULE_ExcludeElementList_KIND);
        m.put("ExcludeElement", RULE_ExcludeElement_KIND);
        m.put("NullsDistinct", RULE_NullsDistinct_KIND);
        m.put("IndexOptions", RULE_IndexOptions_KIND);
        m.put("IncludeClause", RULE_IncludeClause_KIND);
        m.put("WithStorageParams", RULE_WithStorageParams_KIND);
        m.put("StorageParamList", RULE_StorageParamList_KIND);
        m.put("StorageParam", RULE_StorageParam_KIND);
        m.put("UsingIndexTblspace", RULE_UsingIndexTblspace_KIND);
        m.put("TableOptions", RULE_TableOptions_KIND);
        m.put("PartitionByClause", RULE_PartitionByClause_KIND);
        m.put("PartitionKeyList", RULE_PartitionKeyList_KIND);
        m.put("PartitionKey", RULE_PartitionKey_KIND);
        m.put("InheritsClause", RULE_InheritsClause_KIND);
        m.put("TablespaceClause", RULE_TablespaceClause_KIND);
        m.put("ColumnList", RULE_ColumnList_KIND);
        m.put("QualifiedNameList", RULE_QualifiedNameList_KIND);
        m.put("AlterTableStmt", RULE_AlterTableStmt_KIND);
        m.put("AlterTableActions", RULE_AlterTableActions_KIND);
        m.put("AlterTableAction", RULE_AlterTableAction_KIND);
        m.put("AddColumnAction", RULE_AddColumnAction_KIND);
        m.put("DropColumnAction", RULE_DropColumnAction_KIND);
        m.put("AlterColumnAction", RULE_AlterColumnAction_KIND);
        m.put("AlterColumnCmd", RULE_AlterColumnCmd_KIND);
        m.put("SetDataTypeCmd", RULE_SetDataTypeCmd_KIND);
        m.put("SetDefaultCmd", RULE_SetDefaultCmd_KIND);
        m.put("DropDefaultCmd", RULE_DropDefaultCmd_KIND);
        m.put("SetNotNullCmd", RULE_SetNotNullCmd_KIND);
        m.put("DropNotNullCmd", RULE_DropNotNullCmd_KIND);
        m.put("SetStatisticsCmd", RULE_SetStatisticsCmd_KIND);
        m.put("SetStorageCmd", RULE_SetStorageCmd_KIND);
        m.put("AddIdentityCmd", RULE_AddIdentityCmd_KIND);
        m.put("DropIdentityCmd", RULE_DropIdentityCmd_KIND);
        m.put("AddConstraintAction", RULE_AddConstraintAction_KIND);
        m.put("DropConstraintAction", RULE_DropConstraintAction_KIND);
        m.put("ValidateConstraintAction", RULE_ValidateConstraintAction_KIND);
        m.put("RenameConstraintAction", RULE_RenameConstraintAction_KIND);
        m.put("NotValidClause", RULE_NotValidClause_KIND);
        m.put("AlterOwnerAction", RULE_AlterOwnerAction_KIND);
        m.put("RenameAction", RULE_RenameAction_KIND);
        m.put("SetSchemaAction", RULE_SetSchemaAction_KIND);
        m.put("SetTablespaceAction", RULE_SetTablespaceAction_KIND);
        m.put("AttachPartition", RULE_AttachPartition_KIND);
        m.put("DetachPartition", RULE_DetachPartition_KIND);
        m.put("ForValuesClause", RULE_ForValuesClause_KIND);
        m.put("DropTableStmt", RULE_DropTableStmt_KIND);
        m.put("CreateIndexStmt", RULE_CreateIndexStmt_KIND);
        m.put("UsingClause", RULE_UsingClause_KIND);
        m.put("IndexElemList", RULE_IndexElemList_KIND);
        m.put("IndexElem", RULE_IndexElem_KIND);
        m.put("OpClass", RULE_OpClass_KIND);
        m.put("NullsOrder", RULE_NullsOrder_KIND);
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
        m.put("CreateSchemaStmt", RULE_CreateSchemaStmt_KIND);
        m.put("AlterSchemaStmt", RULE_AlterSchemaStmt_KIND);
        m.put("DropSchemaStmt", RULE_DropSchemaStmt_KIND);
        m.put("CreateViewStmt", RULE_CreateViewStmt_KIND);
        m.put("CheckOptionClause", RULE_CheckOptionClause_KIND);
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
        m.put("Grantee", RULE_Grantee_KIND);
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
        m.put("WithOrdinality", RULE_WithOrdinality_KIND);
        m.put("Alias", RULE_Alias_KIND);
        m.put("TablesampleClause", RULE_TablesampleClause_KIND);
        m.put("JoinExpr", RULE_JoinExpr_KIND);
        m.put("JoinClause", RULE_JoinClause_KIND);
        m.put("JoinType", RULE_JoinType_KIND);
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
        m.put("FrameExclusion", RULE_FrameExclusion_KIND);
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
        m.put("SpecialFuncExpr", RULE_SpecialFuncExpr_KIND);
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
        m.put("IntervalField", RULE_IntervalField_KIND);
        m.put("BitType", RULE_BitType_KIND);
        m.put("TypeModifiers", RULE_TypeModifiers_KIND);
        m.put("QualifiedTypeName", RULE_QualifiedTypeName_KIND);
        m.put("ColLabel", RULE_ColLabel_KIND);
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
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__SEMI) { fail("';'", RULE_Input_KIND); break; }
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
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                if (!parseStatement(self)) { break; }
                // zero-or-more: rep_2
                while (true) {
                    int savedPos_rep_2 = pos;
                    int savedNodes_rep_2 = cst.currentNodeCount();
                    boolean iterOk_rep_2 = false;
                    do {
                        if (peek() != KIND_INLINE__SEMI) { fail("';'", RULE_Input_KIND); break; }
                        advance();
                        // zero-or-more: rep_3
                        while (true) {
                            int savedPos_rep_3 = pos;
                            int savedNodes_rep_3 = cst.currentNodeCount();
                            boolean iterOk_rep_3 = false;
                            do {
                                if (peek() != KIND_INLINE__SEMI) { fail("';'", RULE_Input_KIND); break; }
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
                        if (!parseStatement(self)) { break; }
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
        // zero-or-more: rep_4
        while (true) {
            int savedPos_rep_4 = pos;
            int savedNodes_rep_4 = cst.currentNodeCount();
            boolean iterOk_rep_4 = false;
            do {
                if (peek() != KIND_INLINE__SEMI) { fail("';'", RULE_Input_KIND); break; }
                advance();
                iterOk_rep_4 = true;
            } while (false);
            if (!iterOk_rep_4) {
                pos = savedPos_rep_4;
                cst.truncate(savedNodes_rep_4);
                break;
            }
            if (pos == savedPos_rep_4) break; // guard against infinite loops on zero-width matches
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
        { int __k = peek(); if (__k != KIND_CREATEKW) { fail("CreateKW", RULE_CreateStatement_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                    if (!parseCreateSchemaStmt(self)) { break; }
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
        { int __k = peek(); if (__k != KIND_ALTERKW) { fail("AlterKW", RULE_AlterStatement_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                    if (!parseAlterSchemaStmt(self)) { break; }
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
        { int __k = peek(); if (__k != KIND_DROPKW) { fail("DropKW", RULE_DropStatement_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                { int __k = peek(); if (__k != KIND_TEMPKW) { fail("TempKW", RULE_CreateTableStmt_KIND); break; } }
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
                { int __k = peek(); if (__k != KIND_UNLOGGEDKW) { fail("UnloggedKW", RULE_CreateTableStmt_KIND); break; } }
                advance();
                optOk_opt_1 = true;
            } while (false);
            if (!optOk_opt_1) {
                pos = savedPos_opt_1;
                cst.truncate(savedNodes_opt_1);
            }
        }
        { int __k = peek(); if (__k != KIND_TABLEKW) { fail("TableKW", RULE_CreateTableStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_2
        {
            int savedPos_opt_2 = pos;
            int savedNodes_opt_2 = cst.currentNodeCount();
            boolean optOk_opt_2 = false;
            do {
                if (!parseIfNotExists(self)) { break; }
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

    private boolean parseIfNotExists(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_IfNotExists_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_IFKW) { fail("IfKW", RULE_IfNotExists_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_NOTKW) { fail("NotKW", RULE_IfNotExists_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_EXISTSKW) { fail("ExistsKW", RULE_IfNotExists_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseIfExists(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_IfExists_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_IFKW) { fail("IfKW", RULE_IfExists_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_EXISTSKW) { fail("ExistsKW", RULE_IfExists_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
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
        if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_ColumnDef_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
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
                if (!parseConstraintName(self)) { break; }
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
                    if (!parseNotNullConstraint(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_NULLCONSTRAINT) { fail("NullConstraint", RULE_ColConstraintElem_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_UNIQUECOLCONSTRAINT) { fail("UniqueColConstraint", RULE_ColConstraintElem_KIND); break; } }
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
                    if (!parsePrimaryKeyColConstraint(self)) { break; }
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

    private boolean parseNotNullConstraint(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_NotNullConstraint_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_NOTKW) { fail("NotKW", RULE_NotNullConstraint_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_NULLCONSTRAINT) { fail("NullKW", RULE_NotNullConstraint_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parsePrimaryKeyColConstraint(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_PrimaryKeyColConstraint_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_PRIMARYKW) { fail("PrimaryKW", RULE_PrimaryKeyColConstraint_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_KEYKW) { fail("KeyKW", RULE_PrimaryKeyColConstraint_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
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
        { int __k = peek(); if (__k != KIND_CHECKKW) { fail("CheckKW", RULE_CheckColConstraint_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
        { int __k = peek(); if (__k != KIND_DEFAULTKW) { fail("DefaultKW", RULE_DefaultClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
        { int __k = peek(); if (__k != KIND_COLLATEKW) { fail("CollateKW", RULE_CollateClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
        { int __k = peek(); if (__k != KIND_REFERENCESKW) { fail("ReferencesKW", RULE_ReferencesClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                if (!parseFkActions(self)) { break; }
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
        { int __k = peek(); if (__k != KIND_GENERATEDKW) { fail("GeneratedKW", RULE_GeneratedClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_ALWAYSKW) { fail("AlwaysKW", RULE_GeneratedClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_ASKW) { fail("AsKW", RULE_GeneratedClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                    { int __k = peek(); if (__k != KIND_STOREDKW) { fail("StoredKW", RULE_GeneratedClause_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_GENERATEDKW) { fail("GeneratedKW", RULE_IdentityClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_ALWAYSKW) { fail("AlwaysKW", RULE_IdentityClause_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_BYKW) { fail("ByKW", RULE_IdentityClause_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_DEFAULTKW) { fail("DefaultKW", RULE_IdentityClause_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_ASKW) { fail("AsKW", RULE_IdentityClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_IDENTITYKW) { fail("IdentityKW", RULE_IdentityClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
        { int __k = peek(); if (__k != KIND_IDENTITYKW) { fail("IdentityKW", RULE_IdentitySpec_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                if (!parseConstraintName(self)) { break; }
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

    private boolean parseConstraintName(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ConstraintName_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_CONSTRAINTKW) { fail("ConstraintKW", RULE_ConstraintName_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_ConstraintName_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
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
        { int __k = peek(); if (__k != KIND_PRIMARYKW) { fail("PrimaryKW", RULE_PrimaryKeyTblConstraint_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_KEYKW) { fail("KeyKW", RULE_PrimaryKeyTblConstraint_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
        { int __k = peek(); if (__k != KIND_UNIQUECOLCONSTRAINT) { fail("UniqueKW", RULE_UniqueTblConstraint_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parseNullsDistinct(self)) { break; }
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
        { int __k = peek(); if (__k != KIND_CHECKKW) { fail("CheckKW", RULE_CheckTblConstraint_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                if (!parseNoInheritClause(self)) { break; }
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

    private boolean parseNoInheritClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_NoInheritClause_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_NOKW) { fail("NoKW", RULE_NoInheritClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_INHERITKW) { fail("InheritKW", RULE_NoInheritClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
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
        { int __k = peek(); if (__k != KIND_FOREIGNKW) { fail("ForeignKW", RULE_ForeignKeyTblConstraint_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_KEYKW) { fail("KeyKW", RULE_ForeignKeyTblConstraint_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_ForeignKeyTblConstraint_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseColumnList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_ForeignKeyTblConstraint_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        { int __k = peek(); if (__k != KIND_REFERENCESKW) { fail("ReferencesKW", RULE_ForeignKeyTblConstraint_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                if (!parseFkActions(self)) { break; }
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
                if (!parseFkDeferrable(self)) { break; }
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

    private boolean parseFkActions(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_FkActions_KIND, firstTok, parent);
        if (!parseFkAction(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (!parseFkAction(self)) { break; }
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

    private boolean parseFkAction(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_FkAction_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_ONKW) { fail("OnKW", RULE_FkAction_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_UPDATEKW) { fail("UpdateKW", RULE_FkAction_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_DELETEKW) { fail("DeleteKW", RULE_FkAction_KIND); break; } }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_FkAction_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        if (!parseFkActionType(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseFkActionType(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_FkActionType_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_CASCADEKW) { fail("CascadeKW", RULE_FkActionType_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_RESTRICTKW) { fail("RestrictKW", RULE_FkActionType_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_SETKW) { fail("SetKW", RULE_FkActionType_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_NULLCONSTRAINT) { fail("NullKW", RULE_FkActionType_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_SETKW) { fail("SetKW", RULE_FkActionType_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_DEFAULTKW) { fail("DefaultKW", RULE_FkActionType_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_NOKW) { fail("NoKW", RULE_FkActionType_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_ACTIONKW) { fail("ActionKW", RULE_FkActionType_KIND); break; } }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_FkActionType_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseFkDeferrable(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_FkDeferrable_KIND, firstTok, parent);
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                { int __k = peek(); if (__k != KIND_NOTKW) { fail("NotKW", RULE_FkDeferrable_KIND); break; } }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        { int __k = peek(); if (__k != KIND_DEFERRABLEKW) { fail("DeferrableKW", RULE_FkDeferrable_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                { int __k = peek(); if (__k != KIND_INITIALLYKW) { fail("InitiallyKW", RULE_FkDeferrable_KIND); break; } }
                advance();
                // choice: alt_2
                {
                    int savedPos_alt_2 = pos;
                    int savedNodes_alt_2 = cst.currentNodeCount();
                    boolean matched_alt_2 = false;
                    boolean cutHit_alt_2 = false;
                    if (!matched_alt_2 && !cutHit_alt_2) {
                        do {
                            { int __k = peek(); if (__k != KIND_DEFERREDKW) { fail("DeferredKW", RULE_FkDeferrable_KIND); break; } }
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
                            { int __k = peek(); if (__k != KIND_IMMEDIATEKW) { fail("ImmediateKW", RULE_FkDeferrable_KIND); break; } }
                            advance();
                            matched_alt_2 = true;
                        } while (false);
                        if (!matched_alt_2) {
                            pos = savedPos_alt_2;
                            cst.truncate(savedNodes_alt_2);
                        }
                    }
                    if (!matched_alt_2) { fail("<choice>", RULE_FkDeferrable_KIND); break; }
                }
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

    private boolean parseExcludeTblConstraint(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ExcludeTblConstraint_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_EXCLUDEKW) { fail("ExcludeKW", RULE_ExcludeTblConstraint_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parseUsingClause(self)) { break; }
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
        { int __k = peek(); if (__k != KIND_WITHKW) { fail("WithKW", RULE_ExcludeElement_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseOperator(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseNullsDistinct(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_NullsDistinct_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_NULLSKW) { fail("NullsKW", RULE_NullsDistinct_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                { int __k = peek(); if (__k != KIND_NOTKW) { fail("NotKW", RULE_NullsDistinct_KIND); break; } }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        { int __k = peek(); if (__k != KIND_DISTINCTKW) { fail("DistinctKW", RULE_NullsDistinct_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
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
                if (!parseUsingIndexTblspace(self)) { break; }
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
        { int __k = peek(); if (__k != KIND_INCLUDEKW) { fail("IncludeKW", RULE_IncludeClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
        { int __k = peek(); if (__k != KIND_WITHKW) { fail("WithKW", RULE_WithStorageParams_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
        if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_StorageParam_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
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

    private boolean parseUsingIndexTblspace(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_UsingIndexTblspace_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_USINGKW) { fail("UsingKW", RULE_UsingIndexTblspace_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_INDEXKW) { fail("IndexKW", RULE_UsingIndexTblspace_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_TABLESPACEKW) { fail("TablespaceKW", RULE_UsingIndexTblspace_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_UsingIndexTblspace_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
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
                if (!parseTablespaceClause(self)) { break; }
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
        { int __k = peek(); if (__k != KIND_PARTITIONKW) { fail("PartitionKW", RULE_PartitionByClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_BYKW) { fail("ByKW", RULE_PartitionByClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        { int __k = peek(); if (__k != KIND_RANGEKW && __k != KIND_LISTKW && __k != KIND_HASHKW) { fail("PartitionStrategy", RULE_PartitionByClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
        { int __k = peek(); if (__k != KIND_INHERITSKW) { fail("InheritsKW", RULE_InheritsClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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

    private boolean parseTablespaceClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_TablespaceClause_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_TABLESPACEKW) { fail("TablespaceKW", RULE_TablespaceClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_TablespaceClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
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
        if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_ColumnList_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_ColumnList_KIND); break; }
                advance();
                if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_ColumnList_KIND); break; }
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
        { int __k = peek(); if (__k != KIND_TABLEKW) { fail("TableKW", RULE_AlterTableStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parseIfExists(self)) { break; }
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
                { int __k = peek(); if (__k != KIND_ONLYKW) { fail("OnlyKW", RULE_AlterTableStmt_KIND); break; } }
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
                    if (!parseRenameAction(self)) { break; }
                    matched_alt_2 = true;
                } while (false);
                if (!matched_alt_2) {
                    pos = savedPos_alt_2;
                    cst.truncate(savedNodes_alt_2);
                }
            }
            if (!matched_alt_2 && !cutHit_alt_2) {
                do {
                    if (!parseSetSchemaAction(self)) { break; }
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
                    if (!parseDropColumnAction(self)) { break; }
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
                    if (!parseDropConstraintAction(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseValidateConstraintAction(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseRenameConstraintAction(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseAlterOwnerAction(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseSetTablespaceAction(self)) { break; }
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
        { int __k = peek(); if (__k != KIND_ADDKW) { fail("AddKW", RULE_AddColumnAction_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                { int __k = peek(); if (__k != KIND_COLUMNKW) { fail("ColumnKW", RULE_AddColumnAction_KIND); break; } }
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
                if (!parseIfNotExists(self)) { break; }
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

    private boolean parseDropColumnAction(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_DropColumnAction_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_DROPKW) { fail("DropKW", RULE_DropColumnAction_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                { int __k = peek(); if (__k != KIND_COLUMNKW) { fail("ColumnKW", RULE_DropColumnAction_KIND); break; } }
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
                if (!parseIfExists(self)) { break; }
                optOk_opt_1 = true;
            } while (false);
            if (!optOk_opt_1) {
                pos = savedPos_opt_1;
                cst.truncate(savedNodes_opt_1);
            }
        }
        if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_DropColumnAction_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // optional: opt_2
        {
            int savedPos_opt_2 = pos;
            int savedNodes_opt_2 = cst.currentNodeCount();
            boolean optOk_opt_2 = false;
            do {
                { int __k = peek(); if (__k != KIND_CASCADEKW && __k != KIND_RESTRICTKW) { fail("DropBehavior", RULE_DropColumnAction_KIND); break; } }
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

    private boolean parseAlterColumnAction(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_AlterColumnAction_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_ALTERKW) { fail("AlterKW", RULE_AlterColumnAction_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                { int __k = peek(); if (__k != KIND_COLUMNKW) { fail("ColumnKW", RULE_AlterColumnAction_KIND); break; } }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_AlterColumnAction_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
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
                    if (!parseDropDefaultCmd(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseSetNotNullCmd(self)) { break; }
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (!parseDropNotNullCmd(self)) { break; }
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
                    if (!parseSetStorageCmd(self)) { break; }
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
                    if (!parseDropIdentityCmd(self)) { break; }
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
                { int __k = peek(); if (__k != KIND_SETKW) { fail("SetKW", RULE_SetDataTypeCmd_KIND); break; } }
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
                { int __k = peek(); if (__k != KIND_DATAKW) { fail("DataKW", RULE_SetDataTypeCmd_KIND); break; } }
                advance();
                optOk_opt_1 = true;
            } while (false);
            if (!optOk_opt_1) {
                pos = savedPos_opt_1;
                cst.truncate(savedNodes_opt_1);
            }
        }
        { int __k = peek(); if (__k != KIND_TYPEKW) { fail("TypeKW", RULE_SetDataTypeCmd_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parseDataType(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_2
        {
            int savedPos_opt_2 = pos;
            int savedNodes_opt_2 = cst.currentNodeCount();
            boolean optOk_opt_2 = false;
            do {
                { int __k = peek(); if (__k != KIND_USINGKW) { fail("UsingKW", RULE_SetDataTypeCmd_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_SETKW) { fail("SetKW", RULE_SetDefaultCmd_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_DEFAULTKW) { fail("DefaultKW", RULE_SetDefaultCmd_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parseExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseDropDefaultCmd(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_DropDefaultCmd_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_DROPKW) { fail("DropKW", RULE_DropDefaultCmd_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_DEFAULTKW) { fail("DefaultKW", RULE_DropDefaultCmd_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseSetNotNullCmd(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_SetNotNullCmd_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_SETKW) { fail("SetKW", RULE_SetNotNullCmd_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_NOTKW) { fail("NotKW", RULE_SetNotNullCmd_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_NULLCONSTRAINT) { fail("NullKW", RULE_SetNotNullCmd_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseDropNotNullCmd(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_DropNotNullCmd_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_DROPKW) { fail("DropKW", RULE_DropNotNullCmd_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_NOTKW) { fail("NotKW", RULE_DropNotNullCmd_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_NULLCONSTRAINT) { fail("NullKW", RULE_DropNotNullCmd_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
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
        { int __k = peek(); if (__k != KIND_SETKW) { fail("SetKW", RULE_SetStatisticsCmd_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_STATISTICSKW) { fail("StatisticsKW", RULE_SetStatisticsCmd_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseSignedNumericLiteral(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseSetStorageCmd(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_SetStorageCmd_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_SETKW) { fail("SetKW", RULE_SetStorageCmd_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_STORAGEKW) { fail("StorageKW", RULE_SetStorageCmd_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_SetStorageCmd_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
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
        { int __k = peek(); if (__k != KIND_ADDKW) { fail("AddKW", RULE_AddIdentityCmd_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_GENERATEDKW) { fail("GeneratedKW", RULE_AddIdentityCmd_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_ALWAYSKW) { fail("AlwaysKW", RULE_AddIdentityCmd_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_BYKW) { fail("ByKW", RULE_AddIdentityCmd_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_DEFAULTKW) { fail("DefaultKW", RULE_AddIdentityCmd_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_ASKW) { fail("AsKW", RULE_AddIdentityCmd_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_IDENTITYKW) { fail("IdentityKW", RULE_AddIdentityCmd_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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

    private boolean parseDropIdentityCmd(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_DropIdentityCmd_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_DROPKW) { fail("DropKW", RULE_DropIdentityCmd_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_IDENTITYKW) { fail("IdentityKW", RULE_DropIdentityCmd_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parseIfExists(self)) { break; }
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

    private boolean parseAddConstraintAction(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_AddConstraintAction_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_ADDKW) { fail("AddKW", RULE_AddConstraintAction_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parseTableConstraint(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parseNotValidClause(self)) { break; }
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

    private boolean parseDropConstraintAction(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_DropConstraintAction_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_DROPKW) { fail("DropKW", RULE_DropConstraintAction_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_CONSTRAINTKW) { fail("ConstraintKW", RULE_DropConstraintAction_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parseIfExists(self)) { break; }
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_DropConstraintAction_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                { int __k = peek(); if (__k != KIND_CASCADEKW && __k != KIND_RESTRICTKW) { fail("DropBehavior", RULE_DropConstraintAction_KIND); break; } }
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

    private boolean parseValidateConstraintAction(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ValidateConstraintAction_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_VALIDATEKW) { fail("ValidateKW", RULE_ValidateConstraintAction_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_CONSTRAINTKW) { fail("ConstraintKW", RULE_ValidateConstraintAction_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_ValidateConstraintAction_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseRenameConstraintAction(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_RenameConstraintAction_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_RENAMEKW) { fail("RenameKW", RULE_RenameConstraintAction_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_CONSTRAINTKW) { fail("ConstraintKW", RULE_RenameConstraintAction_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_RenameConstraintAction_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        { int __k = peek(); if (__k != KIND_TOKW) { fail("ToKW", RULE_RenameConstraintAction_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_RenameConstraintAction_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseNotValidClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_NotValidClause_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_NOTKW) { fail("NotKW", RULE_NotValidClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_VALIDKW) { fail("ValidKW", RULE_NotValidClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseAlterOwnerAction(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_AlterOwnerAction_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_OWNERKW) { fail("OwnerKW", RULE_AlterOwnerAction_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_TOKW) { fail("ToKW", RULE_AlterOwnerAction_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_AlterOwnerAction_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseRenameAction(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_RenameAction_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_RENAMEKW) { fail("RenameKW", RULE_RenameAction_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_COLUMNKW) { fail("ColumnKW", RULE_RenameAction_KIND); break; } }
                    advance();
                    if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_RenameAction_KIND); break; }
                    advance();
                    { int __k = peek(); if (__k != KIND_TOKW) { fail("ToKW", RULE_RenameAction_KIND); break; } }
                    advance();
                    if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_RenameAction_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_TOKW) { fail("ToKW", RULE_RenameAction_KIND); break; } }
                    advance();
                    if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_RenameAction_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_RenameAction_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseSetSchemaAction(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_SetSchemaAction_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_SETKW) { fail("SetKW", RULE_SetSchemaAction_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_SCHEMAKW) { fail("SchemaKW", RULE_SetSchemaAction_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_SetSchemaAction_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseSetTablespaceAction(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_SetTablespaceAction_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_SETKW) { fail("SetKW", RULE_SetTablespaceAction_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_TABLESPACEKW) { fail("TablespaceKW", RULE_SetTablespaceAction_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_SetTablespaceAction_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
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
        { int __k = peek(); if (__k != KIND_ATTACHKW) { fail("AttachKW", RULE_AttachPartition_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_PARTITIONKW) { fail("PartitionKW", RULE_AttachPartition_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
        { int __k = peek(); if (__k != KIND_DETACHKW) { fail("DetachKW", RULE_DetachPartition_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_PARTITIONKW) { fail("PartitionKW", RULE_DetachPartition_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                            { int __k = peek(); if (__k != KIND_CONCURRENTLYKW) { fail("ConcurrentlyKW", RULE_DetachPartition_KIND); break; } }
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
                            { int __k = peek(); if (__k != KIND_FINALIZEKW) { fail("FinalizeKW", RULE_DetachPartition_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_FORKW) { fail("ForKW", RULE_ForValuesClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_VALUESKW) { fail("ValuesKW", RULE_ForValuesClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_INKW) { fail("InKW", RULE_ForValuesClause_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_FROMKW) { fail("FromKW", RULE_ForValuesClause_KIND); break; } }
                    advance();
                    if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_ForValuesClause_KIND); break; }
                    advance();
                    if (!parseExprList(self)) { break; }
                    if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_ForValuesClause_KIND); break; }
                    advance();
                    { int __k = peek(); if (__k != KIND_TOKW) { fail("ToKW", RULE_ForValuesClause_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_WITHKW) { fail("WithKW", RULE_ForValuesClause_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_DEFAULTKW) { fail("DefaultKW", RULE_ForValuesClause_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_TABLEKW) { fail("TableKW", RULE_DropTableStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parseIfExists(self)) { break; }
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
                { int __k = peek(); if (__k != KIND_CASCADEKW && __k != KIND_RESTRICTKW) { fail("DropBehavior", RULE_DropTableStmt_KIND); break; } }
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
                { int __k = peek(); if (__k != KIND_UNIQUECOLCONSTRAINT) { fail("UniqueKW", RULE_CreateIndexStmt_KIND); break; } }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        { int __k = peek(); if (__k != KIND_INDEXKW) { fail("IndexKW", RULE_CreateIndexStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                { int __k = peek(); if (__k != KIND_CONCURRENTLYKW) { fail("ConcurrentlyKW", RULE_CreateIndexStmt_KIND); break; } }
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
                if (!parseIfNotExists(self)) { break; }
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
                    if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_CreateIndexStmt_KIND); break; }
                    advance();
                    { int __k = peek(); if (__k != KIND_ONKW) { fail("OnKW", RULE_CreateIndexStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_ONKW) { fail("OnKW", RULE_CreateIndexStmt_KIND); break; } }
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
                { int __k = peek(); if (__k != KIND_ONLYKW) { fail("OnlyKW", RULE_CreateIndexStmt_KIND); break; } }
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
                if (!parseUsingClause(self)) { break; }
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
                if (!parseNullsDistinct(self)) { break; }
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
                if (!parseTablespaceClause(self)) { break; }
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

    private boolean parseUsingClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_UsingClause_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_USINGKW) { fail("UsingKW", RULE_UsingClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (java.util.Arrays.binarySearch(ALIAS_INDEXMETHOD, peek()) < 0) { fail("IndexMethod", RULE_UsingClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
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
                    if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_IndexElem_KIND); break; }
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
                { int __k = peek(); if (__k != KIND_ASCKW && __k != KIND_DESCKW) { fail("OrderSpec", RULE_IndexElem_KIND); break; } }
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
                if (!parseNullsOrder(self)) { break; }
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
        if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_OpClass_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
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

    private boolean parseNullsOrder(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_NullsOrder_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_NULLSKW) { fail("NullsKW", RULE_NullsOrder_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_FIRSTKW) { fail("FirstKW", RULE_NullsOrder_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_LASTKW) { fail("LastKW", RULE_NullsOrder_KIND); break; } }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_NullsOrder_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
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
        { int __k = peek(); if (__k != KIND_INDEXKW) { fail("IndexKW", RULE_AlterIndexStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parseIfExists(self)) { break; }
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
                    { int __k = peek(); if (__k != KIND_RENAMEKW) { fail("RenameKW", RULE_AlterIndexStmt_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_TOKW) { fail("ToKW", RULE_AlterIndexStmt_KIND); break; } }
                    advance();
                    if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_AlterIndexStmt_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_SETKW) { fail("SetKW", RULE_AlterIndexStmt_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_TABLESPACEKW) { fail("TablespaceKW", RULE_AlterIndexStmt_KIND); break; } }
                    advance();
                    if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_AlterIndexStmt_KIND); break; }
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
        { int __k = peek(); if (__k != KIND_INDEXKW) { fail("IndexKW", RULE_DropIndexStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                { int __k = peek(); if (__k != KIND_CONCURRENTLYKW) { fail("ConcurrentlyKW", RULE_DropIndexStmt_KIND); break; } }
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
                if (!parseIfExists(self)) { break; }
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
                { int __k = peek(); if (__k != KIND_CASCADEKW && __k != KIND_RESTRICTKW) { fail("DropBehavior", RULE_DropIndexStmt_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_SEQUENCEKW) { fail("SequenceKW", RULE_CreateSequenceStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parseIfNotExists(self)) { break; }
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
        { int __k = peek(); if (__k != KIND_SEQUENCEKW) { fail("SequenceKW", RULE_AlterSequenceStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parseIfExists(self)) { break; }
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
        { int __k = peek(); if (__k != KIND_SEQUENCEKW) { fail("SequenceKW", RULE_DropSequenceStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parseIfExists(self)) { break; }
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
                { int __k = peek(); if (__k != KIND_CASCADEKW && __k != KIND_RESTRICTKW) { fail("DropBehavior", RULE_DropSequenceStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_ASKW) { fail("AsKW", RULE_SequenceOption_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INCREMENTKW) { fail("IncrementKW", RULE_SequenceOption_KIND); break; } }
                    advance();
                    // optional: opt_1
                    {
                        int savedPos_opt_1 = pos;
                        int savedNodes_opt_1 = cst.currentNodeCount();
                        boolean optOk_opt_1 = false;
                        do {
                            { int __k = peek(); if (__k != KIND_BYKW) { fail("ByKW", RULE_SequenceOption_KIND); break; } }
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
                                { int __k = peek(); if (__k != KIND_MINVALUEKW) { fail("MinvalueKW", RULE_SequenceOption_KIND); break; } }
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
                                { int __k = peek(); if (__k != KIND_MAXVALUEKW) { fail("MaxvalueKW", RULE_SequenceOption_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_NOKW) { fail("NoKW", RULE_SequenceOption_KIND); break; } }
                    advance();
                    // choice: alt_3
                    {
                        int savedPos_alt_3 = pos;
                        int savedNodes_alt_3 = cst.currentNodeCount();
                        boolean matched_alt_3 = false;
                        boolean cutHit_alt_3 = false;
                        if (!matched_alt_3 && !cutHit_alt_3) {
                            do {
                                { int __k = peek(); if (__k != KIND_MINVALUEKW) { fail("MinvalueKW", RULE_SequenceOption_KIND); break; } }
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
                                { int __k = peek(); if (__k != KIND_MAXVALUEKW) { fail("MaxvalueKW", RULE_SequenceOption_KIND); break; } }
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
                                { int __k = peek(); if (__k != KIND_CYCLEKW) { fail("CycleKW", RULE_SequenceOption_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_STARTKW) { fail("StartKW", RULE_SequenceOption_KIND); break; } }
                    advance();
                    // optional: opt_4
                    {
                        int savedPos_opt_4 = pos;
                        int savedNodes_opt_4 = cst.currentNodeCount();
                        boolean optOk_opt_4 = false;
                        do {
                            { int __k = peek(); if (__k != KIND_WITHKW) { fail("WithKW", RULE_SequenceOption_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_RESTARTKW) { fail("RestartKW", RULE_SequenceOption_KIND); break; } }
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
                                    { int __k = peek(); if (__k != KIND_WITHKW) { fail("WithKW", RULE_SequenceOption_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_CACHEKW) { fail("CacheKW", RULE_SequenceOption_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_CYCLEKW) { fail("CycleKW", RULE_SequenceOption_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_OWNEDKW) { fail("OwnedKW", RULE_SequenceOption_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_BYKW) { fail("ByKW", RULE_SequenceOption_KIND); break; } }
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
                                { int __k = peek(); if (__k != KIND_NONEKW) { fail("NoneKW", RULE_SequenceOption_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_TYPEKW) { fail("TypeKW", RULE_CreateTypeStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                            { int __k = peek(); if (__k != KIND_ASKW) { fail("AsKW", RULE_CreateTypeStmt_KIND); break; } }
                            advance();
                            { int __k = peek(); if (__k != KIND_ENUMKW) { fail("EnumKW", RULE_CreateTypeStmt_KIND); break; } }
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
                            { int __k = peek(); if (__k != KIND_ASKW) { fail("AsKW", RULE_CreateTypeStmt_KIND); break; } }
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
                            { int __k = peek(); if (__k != KIND_ASKW) { fail("AsKW", RULE_CreateTypeStmt_KIND); break; } }
                            advance();
                            { int __k = peek(); if (__k != KIND_RANGEKW) { fail("RangeKW", RULE_CreateTypeStmt_KIND); break; } }
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
                            { int __k = peek(); if (__k != KIND_ASKW) { fail("AsKW", RULE_CreateTypeStmt_KIND); break; } }
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
        if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_CompositeField_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
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
        if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_RangeOption_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
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
                    if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_RangeOption_KIND); break; }
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
                if (!parseConstraintName(self)) { break; }
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
                    if (!parseNotNullConstraint(self)) { break; }
                    matched_alt_1 = true;
                } while (false);
                if (!matched_alt_1) {
                    pos = savedPos_alt_1;
                    cst.truncate(savedNodes_alt_1);
                }
            }
            if (!matched_alt_1 && !cutHit_alt_1) {
                do {
                    { int __k = peek(); if (__k != KIND_NULLCONSTRAINT) { fail("NullConstraint", RULE_DomainConstraint_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_TYPEKW) { fail("TypeKW", RULE_AlterTypeStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                    { int __k = peek(); if (__k != KIND_ADDKW) { fail("AddKW", RULE_AlterTypeStmt_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_VALUEKW) { fail("ValueKW", RULE_AlterTypeStmt_KIND); break; } }
                    advance();
                    // optional: opt_1
                    {
                        int savedPos_opt_1 = pos;
                        int savedNodes_opt_1 = cst.currentNodeCount();
                        boolean optOk_opt_1 = false;
                        do {
                            if (!parseIfNotExists(self)) { break; }
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
                                        { int __k = peek(); if (__k != KIND_BEFOREKW) { fail("BeforeKW", RULE_AlterTypeStmt_KIND); break; } }
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
                                        { int __k = peek(); if (__k != KIND_AFTERKW) { fail("AfterKW", RULE_AlterTypeStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_RENAMEKW) { fail("RenameKW", RULE_AlterTypeStmt_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_VALUEKW) { fail("ValueKW", RULE_AlterTypeStmt_KIND); break; } }
                    advance();
                    if (!parseStringLiteral(self)) { break; }
                    { int __k = peek(); if (__k != KIND_TOKW) { fail("ToKW", RULE_AlterTypeStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_RENAMEKW) { fail("RenameKW", RULE_AlterTypeStmt_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_TOKW) { fail("ToKW", RULE_AlterTypeStmt_KIND); break; } }
                    advance();
                    if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_AlterTypeStmt_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_SETKW) { fail("SetKW", RULE_AlterTypeStmt_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_SCHEMAKW) { fail("SchemaKW", RULE_AlterTypeStmt_KIND); break; } }
                    advance();
                    if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_AlterTypeStmt_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_ADDKW) { fail("AddKW", RULE_AlterTypeStmt_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_ATTRIBUTEKW) { fail("AttributeKW", RULE_AlterTypeStmt_KIND); break; } }
                    advance();
                    if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_AlterTypeStmt_KIND); break; }
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
                            { int __k = peek(); if (__k != KIND_CASCADEKW && __k != KIND_RESTRICTKW) { fail("DropBehavior", RULE_AlterTypeStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_DROPKW) { fail("DropKW", RULE_AlterTypeStmt_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_ATTRIBUTEKW) { fail("AttributeKW", RULE_AlterTypeStmt_KIND); break; } }
                    advance();
                    // optional: opt_6
                    {
                        int savedPos_opt_6 = pos;
                        int savedNodes_opt_6 = cst.currentNodeCount();
                        boolean optOk_opt_6 = false;
                        do {
                            if (!parseIfExists(self)) { break; }
                            optOk_opt_6 = true;
                        } while (false);
                        if (!optOk_opt_6) {
                            pos = savedPos_opt_6;
                            cst.truncate(savedNodes_opt_6);
                        }
                    }
                    if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_AlterTypeStmt_KIND); break; }
                    advance();
                    // optional: opt_7
                    {
                        int savedPos_opt_7 = pos;
                        int savedNodes_opt_7 = cst.currentNodeCount();
                        boolean optOk_opt_7 = false;
                        do {
                            { int __k = peek(); if (__k != KIND_CASCADEKW && __k != KIND_RESTRICTKW) { fail("DropBehavior", RULE_AlterTypeStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_ALTERKW) { fail("AlterKW", RULE_AlterTypeStmt_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_ATTRIBUTEKW) { fail("AttributeKW", RULE_AlterTypeStmt_KIND); break; } }
                    advance();
                    if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_AlterTypeStmt_KIND); break; }
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
        { int __k = peek(); if (__k != KIND_TYPEKW) { fail("TypeKW", RULE_DropTypeStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parseIfExists(self)) { break; }
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
                { int __k = peek(); if (__k != KIND_CASCADEKW && __k != KIND_RESTRICTKW) { fail("DropBehavior", RULE_DropTypeStmt_KIND); break; } }
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

    private boolean parseCreateSchemaStmt(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_CreateSchemaStmt_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_SCHEMAKW) { fail("SchemaKW", RULE_CreateSchemaStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parseIfNotExists(self)) { break; }
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
                    { int __k = peek(); if (__k != KIND_AUTHORIZATIONKW) { fail("AuthorizationKW", RULE_CreateSchemaStmt_KIND); break; } }
                    advance();
                    if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_CreateSchemaStmt_KIND); break; }
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
                    if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_CreateSchemaStmt_KIND); break; }
                    advance();
                    // optional: opt_2
                    {
                        int savedPos_opt_2 = pos;
                        int savedNodes_opt_2 = cst.currentNodeCount();
                        boolean optOk_opt_2 = false;
                        do {
                            { int __k = peek(); if (__k != KIND_AUTHORIZATIONKW) { fail("AuthorizationKW", RULE_CreateSchemaStmt_KIND); break; } }
                            advance();
                            if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_CreateSchemaStmt_KIND); break; }
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
            if (!matched_alt_1) { fail("<choice>", RULE_CreateSchemaStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
        int lastTok = pos > firstTok ? pos - 1 : firstTok;
        if (lastTok >= tokens.count()) lastTok = tokens.count() - 1;
        if (lastTok < firstTok) lastTok = firstTok;
        cst.endNode(self, lastTok);
        return true;
    }

    private boolean parseAlterSchemaStmt(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_AlterSchemaStmt_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_SCHEMAKW) { fail("SchemaKW", RULE_AlterSchemaStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_AlterSchemaStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_RENAMEKW) { fail("RenameKW", RULE_AlterSchemaStmt_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_TOKW) { fail("ToKW", RULE_AlterSchemaStmt_KIND); break; } }
                    advance();
                    if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_AlterSchemaStmt_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_OWNERKW) { fail("OwnerKW", RULE_AlterSchemaStmt_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_TOKW) { fail("ToKW", RULE_AlterSchemaStmt_KIND); break; } }
                    advance();
                    if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_AlterSchemaStmt_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_AlterSchemaStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
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
        { int __k = peek(); if (__k != KIND_SCHEMAKW) { fail("SchemaKW", RULE_DropSchemaStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parseIfExists(self)) { break; }
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_DropSchemaStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // zero-or-more: rep_1
        while (true) {
            int savedPos_rep_1 = pos;
            int savedNodes_rep_1 = cst.currentNodeCount();
            boolean iterOk_rep_1 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_DropSchemaStmt_KIND); break; }
                advance();
                if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_DropSchemaStmt_KIND); break; }
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
                { int __k = peek(); if (__k != KIND_CASCADEKW && __k != KIND_RESTRICTKW) { fail("DropBehavior", RULE_DropSchemaStmt_KIND); break; } }
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
                { int __k = peek(); if (__k != KIND_ORKW) { fail("OrKW", RULE_CreateViewStmt_KIND); break; } }
                advance();
                { int __k = peek(); if (__k != KIND_REPLACEKW) { fail("ReplaceKW", RULE_CreateViewStmt_KIND); break; } }
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
                { int __k = peek(); if (__k != KIND_TEMPKW) { fail("TempKW", RULE_CreateViewStmt_KIND); break; } }
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
                { int __k = peek(); if (__k != KIND_RECURSIVEKW) { fail("RecursiveKW", RULE_CreateViewStmt_KIND); break; } }
                advance();
                optOk_opt_2 = true;
            } while (false);
            if (!optOk_opt_2) {
                pos = savedPos_opt_2;
                cst.truncate(savedNodes_opt_2);
            }
        }
        { int __k = peek(); if (__k != KIND_VIEWKW) { fail("ViewKW", RULE_CreateViewStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
        { int __k = peek(); if (__k != KIND_ASKW) { fail("AsKW", RULE_CreateViewStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseSelectStmt(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_4
        {
            int savedPos_opt_4 = pos;
            int savedNodes_opt_4 = cst.currentNodeCount();
            boolean optOk_opt_4 = false;
            do {
                if (!parseCheckOptionClause(self)) { break; }
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

    private boolean parseCheckOptionClause(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_CheckOptionClause_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_WITHKW) { fail("WithKW", RULE_CheckOptionClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                            { int __k = peek(); if (__k != KIND_CASCADEDKW) { fail("CascadedKW", RULE_CheckOptionClause_KIND); break; } }
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
                            { int __k = peek(); if (__k != KIND_LOCALKW) { fail("LocalKW", RULE_CheckOptionClause_KIND); break; } }
                            advance();
                            matched_alt_1 = true;
                        } while (false);
                        if (!matched_alt_1) {
                            pos = savedPos_alt_1;
                            cst.truncate(savedNodes_alt_1);
                        }
                    }
                    if (!matched_alt_1) { fail("<choice>", RULE_CheckOptionClause_KIND); break; }
                }
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        { int __k = peek(); if (__k != KIND_CHECKKW) { fail("CheckKW", RULE_CheckOptionClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_OPTIONKW) { fail("OptionKW", RULE_CheckOptionClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
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
        { int __k = peek(); if (__k != KIND_MATERIALIZEDKW) { fail("MaterializedKW", RULE_CreateMatViewStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_VIEWKW) { fail("ViewKW", RULE_CreateMatViewStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parseIfNotExists(self)) { break; }
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
        { int __k = peek(); if (__k != KIND_ASKW) { fail("AsKW", RULE_CreateMatViewStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseSelectStmt(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_2
        {
            int savedPos_opt_2 = pos;
            int savedNodes_opt_2 = cst.currentNodeCount();
            boolean optOk_opt_2 = false;
            do {
                { int __k = peek(); if (__k != KIND_WITHKW) { fail("WithKW", RULE_CreateMatViewStmt_KIND); break; } }
                advance();
                // optional: opt_3
                {
                    int savedPos_opt_3 = pos;
                    int savedNodes_opt_3 = cst.currentNodeCount();
                    boolean optOk_opt_3 = false;
                    do {
                        { int __k = peek(); if (__k != KIND_NOKW) { fail("NoKW", RULE_CreateMatViewStmt_KIND); break; } }
                        advance();
                        optOk_opt_3 = true;
                    } while (false);
                    if (!optOk_opt_3) {
                        pos = savedPos_opt_3;
                        cst.truncate(savedNodes_opt_3);
                    }
                }
                { int __k = peek(); if (__k != KIND_DATAKW) { fail("DataKW", RULE_CreateMatViewStmt_KIND); break; } }
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
                { int __k = peek(); if (__k != KIND_MATERIALIZEDKW) { fail("MaterializedKW", RULE_AlterViewStmt_KIND); break; } }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        { int __k = peek(); if (__k != KIND_VIEWKW) { fail("ViewKW", RULE_AlterViewStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                if (!parseIfExists(self)) { break; }
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
                    { int __k = peek(); if (__k != KIND_RENAMEKW) { fail("RenameKW", RULE_AlterViewStmt_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_TOKW) { fail("ToKW", RULE_AlterViewStmt_KIND); break; } }
                    advance();
                    if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_AlterViewStmt_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_SETKW) { fail("SetKW", RULE_AlterViewStmt_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_SCHEMAKW) { fail("SchemaKW", RULE_AlterViewStmt_KIND); break; } }
                    advance();
                    if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_AlterViewStmt_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_OWNERKW) { fail("OwnerKW", RULE_AlterViewStmt_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_TOKW) { fail("ToKW", RULE_AlterViewStmt_KIND); break; } }
                    advance();
                    if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_AlterViewStmt_KIND); break; }
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
        { int __k = peek(); if (__k != KIND_VIEWKW) { fail("ViewKW", RULE_DropViewStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parseIfExists(self)) { break; }
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
                { int __k = peek(); if (__k != KIND_CASCADEKW && __k != KIND_RESTRICTKW) { fail("DropBehavior", RULE_DropViewStmt_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_MATERIALIZEDKW) { fail("MaterializedKW", RULE_DropMatViewStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_VIEWKW) { fail("ViewKW", RULE_DropMatViewStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parseIfExists(self)) { break; }
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
                { int __k = peek(); if (__k != KIND_CASCADEKW && __k != KIND_RESTRICTKW) { fail("DropBehavior", RULE_DropMatViewStmt_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_EXTENSIONKW) { fail("ExtensionKW", RULE_CreateExtensionStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parseIfNotExists(self)) { break; }
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_CreateExtensionStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
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
                { int __k = peek(); if (__k != KIND_WITHKW) { fail("WithKW", RULE_ExtensionOption_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_SCHEMAKW) { fail("SchemaKW", RULE_ExtensionOption_KIND); break; } }
                    advance();
                    if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_ExtensionOption_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_VERSIONKW) { fail("VersionKW", RULE_ExtensionOption_KIND); break; } }
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
                                if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_ExtensionOption_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_FROMKW) { fail("FromKW", RULE_ExtensionOption_KIND); break; } }
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
                                if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_ExtensionOption_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_CASCADEKW) { fail("CascadeKW", RULE_ExtensionOption_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_EXTENSIONKW) { fail("ExtensionKW", RULE_DropExtensionStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parseIfExists(self)) { break; }
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_DropExtensionStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        // zero-or-more: rep_1
        while (true) {
            int savedPos_rep_1 = pos;
            int savedNodes_rep_1 = cst.currentNodeCount();
            boolean iterOk_rep_1 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_DropExtensionStmt_KIND); break; }
                advance();
                if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_DropExtensionStmt_KIND); break; }
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
                { int __k = peek(); if (__k != KIND_CASCADEKW && __k != KIND_RESTRICTKW) { fail("DropBehavior", RULE_DropExtensionStmt_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_COMMENTKW) { fail("CommentKW", RULE_CommentStatement_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_ONKW) { fail("OnKW", RULE_CommentStatement_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parseCommentTarget(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        { int __k = peek(); if (__k != KIND_ISKW) { fail("IsKW", RULE_CommentStatement_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                    { int __k = peek(); if (__k != KIND_NULLCONSTRAINT) { fail("NullKW", RULE_CommentStatement_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_TABLEKW) { fail("TableKW", RULE_CommentTarget_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_COLUMNKW) { fail("ColumnKW", RULE_CommentTarget_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INDEXKW) { fail("IndexKW", RULE_CommentTarget_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_SCHEMAKW) { fail("SchemaKW", RULE_CommentTarget_KIND); break; } }
                    advance();
                    if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_CommentTarget_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_TYPEKW) { fail("TypeKW", RULE_CommentTarget_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_EXTENSIONKW) { fail("ExtensionKW", RULE_CommentTarget_KIND); break; } }
                    advance();
                    if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_CommentTarget_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_SEQUENCEKW) { fail("SequenceKW", RULE_CommentTarget_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_VIEWKW) { fail("ViewKW", RULE_CommentTarget_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_MATERIALIZEDKW) { fail("MaterializedKW", RULE_CommentTarget_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_VIEWKW) { fail("ViewKW", RULE_CommentTarget_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_CONSTRAINTKW) { fail("ConstraintKW", RULE_CommentTarget_KIND); break; } }
                    advance();
                    if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_CommentTarget_KIND); break; }
                    advance();
                    { int __k = peek(); if (__k != KIND_ONKW) { fail("OnKW", RULE_CommentTarget_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_FUNCTIONKW) { fail("FunctionKW", RULE_CommentTarget_KIND); break; } }
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
                            { int __k = peek(); if (__k != KIND_INKW) { fail("InKW", RULE_FuncArgType_KIND); break; } }
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
                            { int __k = peek(); if (__k != KIND_OUTKW) { fail("OutKW", RULE_FuncArgType_KIND); break; } }
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
                            { int __k = peek(); if (__k != KIND_INOUTKW) { fail("InoutKW", RULE_FuncArgType_KIND); break; } }
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
                            { int __k = peek(); if (__k != KIND_VARIADICKW) { fail("VariadicKW", RULE_FuncArgType_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_GRANTKW) { fail("GrantKW", RULE_GrantStatement_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        if (!parsePrivilegeList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        { int __k = peek(); if (__k != KIND_ONKW) { fail("OnKW", RULE_GrantStatement_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseGrantTarget(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        { int __k = peek(); if (__k != KIND_TOKW) { fail("ToKW", RULE_GrantStatement_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseGranteeList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                { int __k = peek(); if (__k != KIND_WITHKW) { fail("WithKW", RULE_GrantStatement_KIND); break; } }
                advance();
                { int __k = peek(); if (__k != KIND_GRANTKW) { fail("GrantKW", RULE_GrantStatement_KIND); break; } }
                advance();
                { int __k = peek(); if (__k != KIND_OPTIONKW) { fail("OptionKW", RULE_GrantStatement_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_REVOKEKW) { fail("RevokeKW", RULE_RevokeStatement_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                { int __k = peek(); if (__k != KIND_GRANTKW) { fail("GrantKW", RULE_RevokeStatement_KIND); break; } }
                advance();
                { int __k = peek(); if (__k != KIND_OPTIONKW) { fail("OptionKW", RULE_RevokeStatement_KIND); break; } }
                advance();
                { int __k = peek(); if (__k != KIND_FORKW) { fail("ForKW", RULE_RevokeStatement_KIND); break; } }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        if (!parsePrivilegeList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        { int __k = peek(); if (__k != KIND_ONKW) { fail("OnKW", RULE_RevokeStatement_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseGrantTarget(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        { int __k = peek(); if (__k != KIND_FROMKW) { fail("FromKW", RULE_RevokeStatement_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseGranteeList(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                { int __k = peek(); if (__k != KIND_CASCADEKW && __k != KIND_RESTRICTKW) { fail("DropBehavior", RULE_RevokeStatement_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_ALLKW) { fail("AllKW", RULE_PrivilegeList_KIND); break; } }
                    advance();
                    // optional: opt_1
                    {
                        int savedPos_opt_1 = pos;
                        int savedNodes_opt_1 = cst.currentNodeCount();
                        boolean optOk_opt_1 = false;
                        do {
                            { int __k = peek(); if (__k != KIND_PRIVILEGESKW) { fail("PrivilegesKW", RULE_PrivilegeList_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_SELECTKW) { fail("SelectKW", RULE_Privilege_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INSERTKW) { fail("InsertKW", RULE_Privilege_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_UPDATEKW) { fail("UpdateKW", RULE_Privilege_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_DELETEKW) { fail("DeleteKW", RULE_Privilege_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_TRUNCATEKW) { fail("TruncateKW", RULE_Privilege_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_REFERENCESKW) { fail("ReferencesKW", RULE_Privilege_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_TRIGGERKW) { fail("TriggerKW", RULE_Privilege_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_CREATEKW) { fail("CreateKW", RULE_Privilege_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_CONNECTKW) { fail("ConnectKW", RULE_Privilege_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_TEMPORARYKW) { fail("TemporaryKW", RULE_Privilege_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_EXECUTEKW) { fail("ExecuteKW", RULE_Privilege_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_USAGEKW) { fail("UsageKW", RULE_Privilege_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_ALLKW) { fail("AllKW", RULE_GrantTarget_KIND); break; } }
                    advance();
                    // choice: alt_1
                    {
                        int savedPos_alt_1 = pos;
                        int savedNodes_alt_1 = cst.currentNodeCount();
                        boolean matched_alt_1 = false;
                        boolean cutHit_alt_1 = false;
                        if (!matched_alt_1 && !cutHit_alt_1) {
                            do {
                                { int __k = peek(); if (__k != KIND_TABLESKW) { fail("TablesKW", RULE_GrantTarget_KIND); break; } }
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
                                { int __k = peek(); if (__k != KIND_SEQUENCESKW) { fail("SequencesKW", RULE_GrantTarget_KIND); break; } }
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
                                { int __k = peek(); if (__k != KIND_FUNCTIONSKW) { fail("FunctionsKW", RULE_GrantTarget_KIND); break; } }
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
                                { int __k = peek(); if (__k != KIND_SCHEMASKW) { fail("SchemasKW", RULE_GrantTarget_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INKW) { fail("InKW", RULE_GrantTarget_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_SCHEMAKW) { fail("SchemaKW", RULE_GrantTarget_KIND); break; } }
                    advance();
                    if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_GrantTarget_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_SCHEMAKW) { fail("SchemaKW", RULE_GrantTarget_KIND); break; } }
                    advance();
                    if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_GrantTarget_KIND); break; }
                    advance();
                    // zero-or-more: rep_2
                    while (true) {
                        int savedPos_rep_2 = pos;
                        int savedNodes_rep_2 = cst.currentNodeCount();
                        boolean iterOk_rep_2 = false;
                        do {
                            if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_GrantTarget_KIND); break; }
                            advance();
                            if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_GrantTarget_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_SEQUENCEKW) { fail("SequenceKW", RULE_GrantTarget_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_TYPEKW) { fail("TypeKW", RULE_GrantTarget_KIND); break; } }
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
                            { int __k = peek(); if (__k != KIND_TABLEKW) { fail("TableKW", RULE_GrantTarget_KIND); break; } }
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
        if (!parseGrantee(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // zero-or-more: rep_0
        while (true) {
            int savedPos_rep_0 = pos;
            int savedNodes_rep_0 = cst.currentNodeCount();
            boolean iterOk_rep_0 = false;
            do {
                if (peek() != KIND_INLINE__COMMA) { fail("','", RULE_GranteeList_KIND); break; }
                advance();
                if (!parseGrantee(self)) { break; }
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

    private boolean parseGrantee(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_Grantee_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_PUBLICKW) { fail("PublicKW", RULE_Grantee_KIND); break; } }
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
                            { int __k = peek(); if (__k != KIND_GROUPKW) { fail("GroupKW", RULE_Grantee_KIND); break; } }
                            advance();
                            optOk_opt_1 = true;
                        } while (false);
                        if (!optOk_opt_1) {
                            pos = savedPos_opt_1;
                            cst.truncate(savedNodes_opt_1);
                        }
                    }
                    if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_Grantee_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_Grantee_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
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
        { int __k = peek(); if (__k != KIND_DEFAULTKW) { fail("DefaultKW", RULE_AlterDefaultPrivilegesPassthrough_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_PRIVILEGESKW) { fail("PrivilegesKW", RULE_AlterDefaultPrivilegesPassthrough_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                { int __k = peek(); if (__k != KIND_ORKW) { fail("OrKW", RULE_CreateFunctionPassthrough_KIND); break; } }
                advance();
                { int __k = peek(); if (__k != KIND_REPLACEKW) { fail("ReplaceKW", RULE_CreateFunctionPassthrough_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_FUNCTIONKW) { fail("FunctionKW", RULE_CreateFunctionPassthrough_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_PROCEDUREKW) { fail("ProcedureKW", RULE_CreateFunctionPassthrough_KIND); break; } }
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
                { int __k = peek(); if (__k != KIND_CONSTRAINTKW) { fail("ConstraintKW", RULE_CreateTriggerPassthrough_KIND); break; } }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        { int __k = peek(); if (__k != KIND_TRIGGERKW) { fail("TriggerKW", RULE_CreateTriggerPassthrough_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                    { int __k = peek(); if (__k != KIND_FUNCTIONKW) { fail("FunctionKW", RULE_DropFunctionPassthrough_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_PROCEDUREKW) { fail("ProcedureKW", RULE_DropFunctionPassthrough_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_AGGREGATEKW) { fail("AggregateKW", RULE_DropFunctionPassthrough_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_TRIGGERKW) { fail("TriggerKW", RULE_DropTriggerPassthrough_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                    { int __k = peek(); if (__k != KIND_SELECTKW) { fail("SelectKW", RULE_SelectCore_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_ALLKW) { fail("AllKW", RULE_SetQuantifier_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_DISTINCTKW) { fail("DistinctKW", RULE_SetQuantifier_KIND); break; } }
                    advance();
                    // optional: opt_1
                    {
                        int savedPos_opt_1 = pos;
                        int savedNodes_opt_1 = cst.currentNodeCount();
                        boolean optOk_opt_1 = false;
                        do {
                            { int __k = peek(); if (__k != KIND_ONKW) { fail("OnKW", RULE_SetQuantifier_KIND); break; } }
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
                                        { int __k = peek(); if (__k != KIND_ASKW) { fail("AsKW", RULE_TargetElem_KIND); break; } }
                                        advance();
                                        if (!parseColLabel(self)) { break; }
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
                                        if (!parseColLabel(self)) { break; }
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
        { int __k = peek(); if (__k != KIND_INTOKW) { fail("IntoKW", RULE_IntoClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                            { int __k = peek(); if (__k != KIND_TEMPKW) { fail("TempKW", RULE_IntoClause_KIND); break; } }
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
                            { int __k = peek(); if (__k != KIND_TEMPORARYKW) { fail("TemporaryKW", RULE_IntoClause_KIND); break; } }
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
                            { int __k = peek(); if (__k != KIND_UNLOGGEDKW) { fail("UnloggedKW", RULE_IntoClause_KIND); break; } }
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
                { int __k = peek(); if (__k != KIND_TABLEKW) { fail("TableKW", RULE_IntoClause_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_FROMKW) { fail("FromKW", RULE_FromClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                { int __k = peek(); if (__k != KIND_LATERALKW) { fail("LateralKW", RULE_SubqueryRef_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_LATERALKW) { fail("LateralKW", RULE_LateralRef_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                if (!parseWithOrdinality(self)) { break; }
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

    private boolean parseWithOrdinality(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_WithOrdinality_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_WITHKW) { fail("WithKW", RULE_WithOrdinality_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_ORDINALITYKW) { fail("OrdinalityKW", RULE_WithOrdinality_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
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
                    { int __k = peek(); if (__k != KIND_ASKW) { fail("AsKW", RULE_Alias_KIND); break; } }
                    advance();
                    if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_Alias_KIND); break; }
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
                    if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_Alias_KIND); break; }
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
        { int __k = peek(); if (__k != KIND_TABLESAMPLEKW) { fail("TablesampleKW", RULE_TablesampleClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                            { int __k = peek(); if (__k != KIND_NATURALKW) { fail("NaturalKW", RULE_JoinClause_KIND); break; } }
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
                            if (!parseJoinType(self)) { break; }
                            optOk_opt_2 = true;
                        } while (false);
                        if (!optOk_opt_2) {
                            pos = savedPos_opt_2;
                            cst.truncate(savedNodes_opt_2);
                        }
                    }
                    { int __k = peek(); if (__k != KIND_JOINKW) { fail("JoinKW", RULE_JoinClause_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_CROSSKW) { fail("CrossKW", RULE_JoinClause_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_JOINKW) { fail("JoinKW", RULE_JoinClause_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_NATURALKW) { fail("NaturalKW", RULE_JoinClause_KIND); break; } }
                    advance();
                    // optional: opt_4
                    {
                        int savedPos_opt_4 = pos;
                        int savedNodes_opt_4 = cst.currentNodeCount();
                        boolean optOk_opt_4 = false;
                        do {
                            if (!parseJoinType(self)) { break; }
                            optOk_opt_4 = true;
                        } while (false);
                        if (!optOk_opt_4) {
                            pos = savedPos_opt_4;
                            cst.truncate(savedNodes_opt_4);
                        }
                    }
                    { int __k = peek(); if (__k != KIND_JOINKW) { fail("JoinKW", RULE_JoinClause_KIND); break; } }
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

    private boolean parseJoinType(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_JoinType_KIND, firstTok, parent);
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
                                { int __k = peek(); if (__k != KIND_LEFTKW) { fail("LeftKW", RULE_JoinType_KIND); break; } }
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
                                { int __k = peek(); if (__k != KIND_RIGHTKW) { fail("RightKW", RULE_JoinType_KIND); break; } }
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
                                { int __k = peek(); if (__k != KIND_FULLKW) { fail("FullKW", RULE_JoinType_KIND); break; } }
                                advance();
                                matched_alt_1 = true;
                            } while (false);
                            if (!matched_alt_1) {
                                pos = savedPos_alt_1;
                                cst.truncate(savedNodes_alt_1);
                            }
                        }
                        if (!matched_alt_1) { fail("<choice>", RULE_JoinType_KIND); break; }
                    }
                    // optional: opt_2
                    {
                        int savedPos_opt_2 = pos;
                        int savedNodes_opt_2 = cst.currentNodeCount();
                        boolean optOk_opt_2 = false;
                        do {
                            { int __k = peek(); if (__k != KIND_OUTERKW) { fail("OuterKW", RULE_JoinType_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INNERKW) { fail("InnerKW", RULE_JoinType_KIND); break; } }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_JoinType_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
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
                    { int __k = peek(); if (__k != KIND_ONKW) { fail("OnKW", RULE_JoinQual_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_USINGKW) { fail("UsingKW", RULE_JoinQual_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_WHEREKW) { fail("WhereKW", RULE_WhereClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
        { int __k = peek(); if (__k != KIND_GROUPKW) { fail("GroupKW", RULE_GroupByClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_BYKW) { fail("ByKW", RULE_GroupByClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                    { int __k = peek(); if (__k != KIND_ALLKW) { fail("AllKW", RULE_GroupByClause_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_ROLLUPKW) { fail("RollupKW", RULE_GroupByElem_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_CUBEKW) { fail("CubeKW", RULE_GroupByElem_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_HAVINGKW) { fail("HavingKW", RULE_HavingClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
        { int __k = peek(); if (__k != KIND_WINDOWKW) { fail("WindowKW", RULE_WindowClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
        if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_WindowDef_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        { int __k = peek(); if (__k != KIND_ASKW) { fail("AsKW", RULE_WindowDef_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                // not-predicate: not_1
                {
                    int savedPos_not_1 = pos;
                    int savedNodes_not_1 = cst.currentNodeCount();
                    boolean notMatched_not_1 = false;
                    do {
                        { int __k = peek(); if (__k != KIND_PARTITIONKW) { fail("PartitionKW", RULE_WindowSpec_KIND); break; } }
                        advance();
                        notMatched_not_1 = true;
                    } while (false);
                    pos = savedPos_not_1;
                    cst.truncate(savedNodes_not_1);
                    if (notMatched_not_1) { fail("!<predicate>", RULE_WindowSpec_KIND); break; }
                }
                // not-predicate: not_2
                {
                    int savedPos_not_2 = pos;
                    int savedNodes_not_2 = cst.currentNodeCount();
                    boolean notMatched_not_2 = false;
                    do {
                        { int __k = peek(); if (__k != KIND_ORDERKW) { fail("OrderKW", RULE_WindowSpec_KIND); break; } }
                        advance();
                        notMatched_not_2 = true;
                    } while (false);
                    pos = savedPos_not_2;
                    cst.truncate(savedNodes_not_2);
                    if (notMatched_not_2) { fail("!<predicate>", RULE_WindowSpec_KIND); break; }
                }
                // not-predicate: not_3
                {
                    int savedPos_not_3 = pos;
                    int savedNodes_not_3 = cst.currentNodeCount();
                    boolean notMatched_not_3 = false;
                    do {
                        { int __k = peek(); if (__k != KIND_ROWSKW) { fail("RowsKW", RULE_WindowSpec_KIND); break; } }
                        advance();
                        notMatched_not_3 = true;
                    } while (false);
                    pos = savedPos_not_3;
                    cst.truncate(savedNodes_not_3);
                    if (notMatched_not_3) { fail("!<predicate>", RULE_WindowSpec_KIND); break; }
                }
                // not-predicate: not_4
                {
                    int savedPos_not_4 = pos;
                    int savedNodes_not_4 = cst.currentNodeCount();
                    boolean notMatched_not_4 = false;
                    do {
                        { int __k = peek(); if (__k != KIND_RANGEKW) { fail("RangeKW", RULE_WindowSpec_KIND); break; } }
                        advance();
                        notMatched_not_4 = true;
                    } while (false);
                    pos = savedPos_not_4;
                    cst.truncate(savedNodes_not_4);
                    if (notMatched_not_4) { fail("!<predicate>", RULE_WindowSpec_KIND); break; }
                }
                // not-predicate: not_5
                {
                    int savedPos_not_5 = pos;
                    int savedNodes_not_5 = cst.currentNodeCount();
                    boolean notMatched_not_5 = false;
                    do {
                        { int __k = peek(); if (__k != KIND_GROUPSKW) { fail("GroupsKW", RULE_WindowSpec_KIND); break; } }
                        advance();
                        notMatched_not_5 = true;
                    } while (false);
                    pos = savedPos_not_5;
                    cst.truncate(savedNodes_not_5);
                    if (notMatched_not_5) { fail("!<predicate>", RULE_WindowSpec_KIND); break; }
                }
                if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_WindowSpec_KIND); break; }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        // optional: opt_6
        {
            int savedPos_opt_6 = pos;
            int savedNodes_opt_6 = cst.currentNodeCount();
            boolean optOk_opt_6 = false;
            do {
                if (!parsePartitionClause(self)) { break; }
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
                if (!parseOrderByClause(self)) { break; }
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
                if (!parseFrameClause(self)) { break; }
                optOk_opt_8 = true;
            } while (false);
            if (!optOk_opt_8) {
                pos = savedPos_opt_8;
                cst.truncate(savedNodes_opt_8);
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
        { int __k = peek(); if (__k != KIND_PARTITIONKW) { fail("PartitionKW", RULE_PartitionClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_BYKW) { fail("ByKW", RULE_PartitionClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
        { int __k = peek(); if (__k != KIND_ROWSKW && __k != KIND_RANGEKW && __k != KIND_GROUPSKW) { fail("FrameType", RULE_FrameClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseFrameExtent(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                if (!parseFrameExclusion(self)) { break; }
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
                    { int __k = peek(); if (__k != KIND_BETWEENKW) { fail("BetweenKW", RULE_FrameExtent_KIND); break; } }
                    advance();
                    if (!parseFrameBound(self)) { break; }
                    { int __k = peek(); if (__k != KIND_ANDKW) { fail("AndKW", RULE_FrameExtent_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_UNBOUNDEDKW) { fail("UnboundedKW", RULE_FrameBound_KIND); break; } }
                    advance();
                    // choice: alt_1
                    {
                        int savedPos_alt_1 = pos;
                        int savedNodes_alt_1 = cst.currentNodeCount();
                        boolean matched_alt_1 = false;
                        boolean cutHit_alt_1 = false;
                        if (!matched_alt_1 && !cutHit_alt_1) {
                            do {
                                { int __k = peek(); if (__k != KIND_PRECEDINGKW) { fail("PrecedingKW", RULE_FrameBound_KIND); break; } }
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
                                { int __k = peek(); if (__k != KIND_FOLLOWINGKW) { fail("FollowingKW", RULE_FrameBound_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_CURRENTKW) { fail("CurrentKW", RULE_FrameBound_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_ROWKW) { fail("RowKW", RULE_FrameBound_KIND); break; } }
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
                                { int __k = peek(); if (__k != KIND_PRECEDINGKW) { fail("PrecedingKW", RULE_FrameBound_KIND); break; } }
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
                                { int __k = peek(); if (__k != KIND_FOLLOWINGKW) { fail("FollowingKW", RULE_FrameBound_KIND); break; } }
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

    private boolean parseFrameExclusion(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_FrameExclusion_KIND, firstTok, parent);
        { int __k = peek(); if (__k != KIND_EXCLUDEKW) { fail("ExcludeKW", RULE_FrameExclusion_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_CURRENTKW) { fail("CurrentKW", RULE_FrameExclusion_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_ROWKW) { fail("RowKW", RULE_FrameExclusion_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_GROUPKW) { fail("GroupKW", RULE_FrameExclusion_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_TIESKW) { fail("TiesKW", RULE_FrameExclusion_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_NOKW) { fail("NoKW", RULE_FrameExclusion_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_OTHERSKW) { fail("OthersKW", RULE_FrameExclusion_KIND); break; } }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_FrameExclusion_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
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
        { int __k = peek(); if (__k != KIND_WITHKW) { fail("WithKW", RULE_WithClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                { int __k = peek(); if (__k != KIND_RECURSIVEKW) { fail("RecursiveKW", RULE_WithClause_KIND); break; } }
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
        if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_CteDef_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
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
        { int __k = peek(); if (__k != KIND_ASKW) { fail("AsKW", RULE_CteDef_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                        { int __k = peek(); if (__k != KIND_NOTKW) { fail("NotKW", RULE_CteDef_KIND); break; } }
                        advance();
                        optOk_opt_2 = true;
                    } while (false);
                    if (!optOk_opt_2) {
                        pos = savedPos_opt_2;
                        cst.truncate(savedNodes_opt_2);
                    }
                }
                { int __k = peek(); if (__k != KIND_MATERIALIZEDKW) { fail("MaterializedKW", RULE_CteDef_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_UNIONKW) { fail("UnionKW", RULE_SetOp_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_INTERSECTKW) { fail("IntersectKW", RULE_SetOp_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_EXCEPTKW) { fail("ExceptKW", RULE_SetOp_KIND); break; } }
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
                            { int __k = peek(); if (__k != KIND_ALLKW) { fail("AllKW", RULE_SetOp_KIND); break; } }
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
                            { int __k = peek(); if (__k != KIND_DISTINCTKW) { fail("DistinctKW", RULE_SetOp_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_ORDERKW) { fail("OrderKW", RULE_OrderByClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_BYKW) { fail("ByKW", RULE_OrderByClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                { int __k = peek(); if (__k != KIND_ASCKW && __k != KIND_DESCKW) { fail("OrderSpec", RULE_OrderByItem_KIND); break; } }
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
                if (!parseNullsOrder(self)) { break; }
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
        { int __k = peek(); if (__k != KIND_LIMITKW) { fail("LimitKW", RULE_LimitClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                    { int __k = peek(); if (__k != KIND_ALLKW) { fail("AllKW", RULE_LimitClause_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_OFFSETKW) { fail("OffsetKW", RULE_OffsetClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                            { int __k = peek(); if (__k != KIND_ROWKW) { fail("RowKW", RULE_OffsetClause_KIND); break; } }
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
                            { int __k = peek(); if (__k != KIND_ROWSKW) { fail("RowsKW", RULE_OffsetClause_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_FETCHKW) { fail("FetchKW", RULE_FetchClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_FIRSTKW) { fail("FirstKW", RULE_FetchClause_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_NEXTKW) { fail("NextKW", RULE_FetchClause_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_ROWKW) { fail("RowKW", RULE_FetchClause_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_ROWSKW) { fail("RowsKW", RULE_FetchClause_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_ONLYKW) { fail("OnlyKW", RULE_FetchClause_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_WITHKW) { fail("WithKW", RULE_FetchClause_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_TIESKW) { fail("TiesKW", RULE_FetchClause_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_INSERTKW) { fail("InsertKW", RULE_InsertStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_INTOKW) { fail("IntoKW", RULE_InsertStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                    { int __k = peek(); if (__k != KIND_DEFAULTKW) { fail("DefaultKW", RULE_InsertSource_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_VALUESKW) { fail("ValuesKW", RULE_InsertSource_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_VALUESKW) { fail("ValuesKW", RULE_ValuesClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                    { int __k = peek(); if (__k != KIND_DEFAULTKW) { fail("DefaultKW", RULE_ExprOrDefault_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_ONKW) { fail("OnKW", RULE_OnConflictClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_CONFLICTKW) { fail("ConflictKW", RULE_OnConflictClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                    { int __k = peek(); if (__k != KIND_ONKW) { fail("OnKW", RULE_ConflictTarget_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_CONSTRAINTKW) { fail("ConstraintKW", RULE_ConflictTarget_KIND); break; } }
                    advance();
                    if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_ConflictTarget_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_DOKW) { fail("DoKW", RULE_ConflictAction_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_NOTHINGKW) { fail("NothingKW", RULE_ConflictAction_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_DOKW) { fail("DoKW", RULE_ConflictAction_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_UPDATEKW) { fail("UpdateKW", RULE_ConflictAction_KIND); break; } }
                    advance();
                    { int __k = peek(); if (__k != KIND_SETKW) { fail("SetKW", RULE_ConflictAction_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_RETURNINGKW) { fail("ReturningKW", RULE_ReturningClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
        { int __k = peek(); if (__k != KIND_UPDATEKW) { fail("UpdateKW", RULE_UpdateStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                { int __k = peek(); if (__k != KIND_ONLYKW) { fail("OnlyKW", RULE_UpdateStmt_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_SETKW) { fail("SetKW", RULE_UpdateStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                    if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_UpdateSetItem_KIND); break; }
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
        { int __k = peek(); if (__k != KIND_DELETEKW) { fail("DeleteKW", RULE_DeleteStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_FROMKW) { fail("FromKW", RULE_DeleteStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // cut: no enclosing Choice — no-op
        // optional: opt_1
        {
            int savedPos_opt_1 = pos;
            int savedNodes_opt_1 = cst.currentNodeCount();
            boolean optOk_opt_1 = false;
            do {
                { int __k = peek(); if (__k != KIND_ONLYKW) { fail("OnlyKW", RULE_DeleteStmt_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_USINGKW) { fail("UsingKW", RULE_UsingClauseDelete_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                    { int __k = peek(); if (__k != KIND_BEGINKW) { fail("BeginKW", RULE_TransactionStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_COMMITKW) { fail("CommitKW", RULE_TransactionStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_ROLLBACKKW) { fail("RollbackKW", RULE_TransactionStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_ENDKW) { fail("EndKW", RULE_TransactionStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_SAVEPOINTKW) { fail("SavepointKW", RULE_TransactionStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_RELEASEKW) { fail("ReleaseKW", RULE_TransactionStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_PREPAREKW) { fail("PrepareKW", RULE_TransactionStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_SETKW) { fail("SetKW", RULE_SessionStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_SHOWKW) { fail("ShowKW", RULE_SessionStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_RESETKW) { fail("ResetKW", RULE_SessionStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_VACUUMKW) { fail("VacuumKW", RULE_UtilityStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_ANALYZEKW) { fail("AnalyzeKW", RULE_UtilityStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_REINDEXKW) { fail("ReindexKW", RULE_UtilityStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_CLUSTERKW) { fail("ClusterKW", RULE_UtilityStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_NOTIFYKW) { fail("NotifyKW", RULE_UtilityStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_LISTENKW) { fail("ListenKW", RULE_UtilityStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_UNLISTENKW) { fail("UnlistenKW", RULE_UtilityStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_LOADKW) { fail("LoadKW", RULE_UtilityStmt_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_DEALLOCATEKW) { fail("DeallocateKW", RULE_UtilityStmt_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_TRUNCATEKW) { fail("TruncateKW", RULE_TruncateStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
        { int __k = peek(); if (__k != KIND_EXPLAINKW) { fail("ExplainKW", RULE_ExplainStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
        { int __k = peek(); if (__k != KIND_COPYKW) { fail("CopyKW", RULE_CopyStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
        { int __k = peek(); if (__k != KIND_REFRESHKW) { fail("RefreshKW", RULE_RefreshMatViewStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_MATERIALIZEDKW) { fail("MaterializedKW", RULE_RefreshMatViewStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_VIEWKW) { fail("ViewKW", RULE_RefreshMatViewStmt_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                { int __k = peek(); if (__k != KIND_ORKW) { fail("OrKW", RULE_OrExpr_KIND); break; } }
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
                { int __k = peek(); if (__k != KIND_ANDKW) { fail("AndKW", RULE_AndExpr_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_NOTKW) { fail("NotKW", RULE_NotExpr_KIND); break; } }
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
                            if (java.util.Arrays.binarySearch(ALIAS_COMPAREOP, peek()) < 0) { fail("CompareOp", RULE_CompareExpr_KIND); break; }
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
                                    { int __k = peek(); if (__k != KIND_NOTKW) { fail("NotKW", RULE_CompareExpr_KIND); break; } }
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
                                    { int __k = peek(); if (__k != KIND_NOTKW) { fail("NotKW", RULE_CompareExpr_KIND); break; } }
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
                                    { int __k = peek(); if (__k != KIND_NOTKW) { fail("NotKW", RULE_CompareExpr_KIND); break; } }
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
                                    { int __k = peek(); if (__k != KIND_NOTKW) { fail("NotKW", RULE_CompareExpr_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_ISKW) { fail("IsKW", RULE_IsClause_KIND); break; } }
                    advance();
                    // optional: opt_1
                    {
                        int savedPos_opt_1 = pos;
                        int savedNodes_opt_1 = cst.currentNodeCount();
                        boolean optOk_opt_1 = false;
                        do {
                            { int __k = peek(); if (__k != KIND_NOTKW) { fail("NotKW", RULE_IsClause_KIND); break; } }
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
                                { int __k = peek(); if (__k != KIND_NULLCONSTRAINT) { fail("NullKW", RULE_IsClause_KIND); break; } }
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
                                { int __k = peek(); if (__k != KIND_TRUEKW) { fail("TrueKW", RULE_IsClause_KIND); break; } }
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
                                { int __k = peek(); if (__k != KIND_FALSEKW) { fail("FalseKW", RULE_IsClause_KIND); break; } }
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
                                { int __k = peek(); if (__k != KIND_UNKNOWNKW) { fail("UnknownKW", RULE_IsClause_KIND); break; } }
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
                                { int __k = peek(); if (__k != KIND_DISTINCTKW) { fail("DistinctKW", RULE_IsClause_KIND); break; } }
                                advance();
                                { int __k = peek(); if (__k != KIND_FROMKW) { fail("FromKW", RULE_IsClause_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_ISNULLKW) { fail("IsnullKW", RULE_IsClause_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_NOTNULLKW) { fail("NotnullKW", RULE_IsClause_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_INKW) { fail("InKW", RULE_InExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
        { int __k = peek(); if (__k != KIND_BETWEENKW) { fail("BetweenKW", RULE_BetweenExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                            { int __k = peek(); if (__k != KIND_SYMMETRICKW) { fail("SymmetricKW", RULE_BetweenExpr_KIND); break; } }
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
                            { int __k = peek(); if (__k != KIND_ASYMMETRICKW) { fail("AsymmetricKW", RULE_BetweenExpr_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_ANDKW) { fail("AndKW", RULE_BetweenExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                    { int __k = peek(); if (__k != KIND_LIKEKW) { fail("LikeKW", RULE_LikeExpr_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_ILIKEKW) { fail("IlikeKW", RULE_LikeExpr_KIND); break; } }
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
                { int __k = peek(); if (__k != KIND_ESCAPEKW) { fail("EscapeKW", RULE_LikeExpr_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_SIMILARKW) { fail("SimilarKW", RULE_SimilarToExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_TOKW) { fail("ToKW", RULE_SimilarToExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseAddExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                { int __k = peek(); if (__k != KIND_ESCAPEKW) { fail("EscapeKW", RULE_SimilarToExpr_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_ISKW) { fail("IsKW", RULE_IsDistinctFrom_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                { int __k = peek(); if (__k != KIND_NOTKW) { fail("NotKW", RULE_IsDistinctFrom_KIND); break; } }
                advance();
                optOk_opt_0 = true;
            } while (false);
            if (!optOk_opt_0) {
                pos = savedPos_opt_0;
                cst.truncate(savedNodes_opt_0);
            }
        }
        { int __k = peek(); if (__k != KIND_DISTINCTKW) { fail("DistinctKW", RULE_IsDistinctFrom_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_FROMKW) { fail("FromKW", RULE_IsDistinctFrom_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                    if (!parseColLabel(self)) { break; }
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
                    if (!parseSpecialFuncExpr(self)) { break; }
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
        { int __k = peek(); if (__k != KIND_EXISTSKW) { fail("ExistsKW", RULE_ExistsExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                    { int __k = peek(); if (__k != KIND_ANYKW) { fail("AnyKW", RULE_AnyAllExpr_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_ALLKW) { fail("AllKW", RULE_AnyAllExpr_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_SOMEKW) { fail("SomeKW", RULE_AnyAllExpr_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_ROWKW) { fail("RowKW", RULE_RowExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
        { int __k = peek(); if (__k != KIND_ARRAYKW) { fail("ArrayKW", RULE_ArrayExprConstructor_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
        { int __k = peek(); if (__k != KIND_CASTKW) { fail("CastKW", RULE_CastExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_CastExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        { int __k = peek(); if (__k != KIND_ASKW) { fail("AsKW", RULE_CastExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
        { int __k = peek(); if (__k != KIND_CASEKW) { fail("CaseKW", RULE_CaseExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
        { int __k = peek(); if (__k != KIND_ENDKW) { fail("EndKW", RULE_CaseExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
        { int __k = peek(); if (__k != KIND_WHENKW) { fail("WhenKW", RULE_WhenClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        { int __k = peek(); if (__k != KIND_THENKW) { fail("ThenKW", RULE_WhenClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
        { int __k = peek(); if (__k != KIND_ELSEKW) { fail("ElseKW", RULE_ElseClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
        { int __k = peek(); if (__k != KIND_COALESCEKW) { fail("CoalesceKW", RULE_CoalesceExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
        { int __k = peek(); if (__k != KIND_NULLIFKW) { fail("NullIfKW", RULE_NullIfExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                    { int __k = peek(); if (__k != KIND_GREATESTKW) { fail("GreatestKW", RULE_GreatestLeastExpr_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_LEASTKW) { fail("LeastKW", RULE_GreatestLeastExpr_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_EXTRACTKW) { fail("ExtractKW", RULE_ExtractExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_ExtractExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_ExtractExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        { int __k = peek(); if (__k != KIND_FROMKW) { fail("FromKW", RULE_ExtractExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
        { int __k = peek(); if (__k != KIND_POSITIONKW) { fail("PositionKW", RULE_PositionExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_PositionExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        { int __k = peek(); if (__k != KIND_INKW) { fail("InKW", RULE_PositionExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
        { int __k = peek(); if (__k != KIND_SUBSTRINGKW) { fail("SubstringKW", RULE_SubstringExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                { int __k = peek(); if (__k != KIND_FROMKW) { fail("FromKW", RULE_SubstringExpr_KIND); break; } }
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
                { int __k = peek(); if (__k != KIND_FORKW) { fail("ForKW", RULE_SubstringExpr_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_TRIMKW) { fail("TrimKW", RULE_TrimExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                            { int __k = peek(); if (__k != KIND_LEADINGKW) { fail("LeadingKW", RULE_TrimExpr_KIND); break; } }
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
                            { int __k = peek(); if (__k != KIND_TRAILINGKW) { fail("TrailingKW", RULE_TrimExpr_KIND); break; } }
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
                            { int __k = peek(); if (__k != KIND_BOTHKW) { fail("BothKW", RULE_TrimExpr_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_FROMKW) { fail("FromKW", RULE_TrimExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
        { int __k = peek(); if (__k != KIND_OVERLAYKW) { fail("OverlayKW", RULE_OverlayExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_OverlayExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        advance();
        if (!parseExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        { int __k = peek(); if (__k != KIND_PLACINGKW) { fail("PlacingKW", RULE_OverlayExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        { int __k = peek(); if (__k != KIND_FROMKW) { fail("FromKW", RULE_OverlayExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        if (!parseExpr(self)) { pos = savedPos; cst.truncate(savedNodes); return false; }
        // optional: opt_0
        {
            int savedPos_opt_0 = pos;
            int savedNodes_opt_0 = cst.currentNodeCount();
            boolean optOk_opt_0 = false;
            do {
                { int __k = peek(); if (__k != KIND_FORKW) { fail("ForKW", RULE_OverlayExpr_KIND); break; } }
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

    private boolean parseSpecialFuncExpr(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_SpecialFuncExpr_KIND, firstTok, parent);
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
                                if (peek() != KIND_INLINE_CURRENT_TIMESTAMP_CI) { fail("'CURRENT_TIMESTAMP'", RULE_SpecialFuncExpr_KIND); break; }
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
                                if (peek() != KIND_INLINE_CURRENT_TIME_CI) { fail("'CURRENT_TIME'", RULE_SpecialFuncExpr_KIND); break; }
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
                                if (peek() != KIND_INLINE_LOCALTIMESTAMP_CI) { fail("'LOCALTIMESTAMP'", RULE_SpecialFuncExpr_KIND); break; }
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
                                if (peek() != KIND_INLINE_LOCALTIME_CI) { fail("'LOCALTIME'", RULE_SpecialFuncExpr_KIND); break; }
                                advance();
                                matched_alt_1 = true;
                            } while (false);
                            if (!matched_alt_1) {
                                pos = savedPos_alt_1;
                                cst.truncate(savedNodes_alt_1);
                            }
                        }
                        if (!matched_alt_1) { fail("<choice>", RULE_SpecialFuncExpr_KIND); break; }
                    }
                    // no-op: not-predicate over char-level expression — handled by lexer
                    // optional: opt_2
                    {
                        int savedPos_opt_2 = pos;
                        int savedNodes_opt_2 = cst.currentNodeCount();
                        boolean optOk_opt_2 = false;
                        do {
                            if (peek() != KIND_INLINE__LPAREN) { fail("'('", RULE_SpecialFuncExpr_KIND); break; }
                            advance();
                            if (peek() != KIND_NUMERICLITERAL) { fail("NumericLiteral", RULE_SpecialFuncExpr_KIND); break; }
                            advance();
                            if (peek() != KIND_INLINE__RPAREN) { fail("')'", RULE_SpecialFuncExpr_KIND); break; }
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
                    // choice: alt_3
                    {
                        int savedPos_alt_3 = pos;
                        int savedNodes_alt_3 = cst.currentNodeCount();
                        boolean matched_alt_3 = false;
                        boolean cutHit_alt_3 = false;
                        if (!matched_alt_3 && !cutHit_alt_3) {
                            do {
                                if (peek() != KIND_INLINE_CURRENT_CATALOG_CI) { fail("'CURRENT_CATALOG'", RULE_SpecialFuncExpr_KIND); break; }
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
                                if (peek() != KIND_INLINE_CURRENT_DATE_CI) { fail("'CURRENT_DATE'", RULE_SpecialFuncExpr_KIND); break; }
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
                                if (peek() != KIND_INLINE_CURRENT_ROLE_CI) { fail("'CURRENT_ROLE'", RULE_SpecialFuncExpr_KIND); break; }
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
                                if (peek() != KIND_INLINE_CURRENT_SCHEMA_CI) { fail("'CURRENT_SCHEMA'", RULE_SpecialFuncExpr_KIND); break; }
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
                                if (peek() != KIND_INLINE_CURRENT_USER_CI) { fail("'CURRENT_USER'", RULE_SpecialFuncExpr_KIND); break; }
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
                                if (peek() != KIND_INLINE_SESSION_USER_CI) { fail("'SESSION_USER'", RULE_SpecialFuncExpr_KIND); break; }
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
                                if (peek() != KIND_INLINE_USER_CI) { fail("'USER'", RULE_SpecialFuncExpr_KIND); break; }
                                advance();
                                matched_alt_3 = true;
                            } while (false);
                            if (!matched_alt_3) {
                                pos = savedPos_alt_3;
                                cst.truncate(savedNodes_alt_3);
                            }
                        }
                        if (!matched_alt_3) { fail("<choice>", RULE_SpecialFuncExpr_KIND); break; }
                    }
                    // no-op: not-predicate over char-level expression — handled by lexer
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_SpecialFuncExpr_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
        }
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
                            { int __k = peek(); if (__k != KIND_ALLKW) { fail("AllKW", RULE_FuncCallArgs_KIND); break; } }
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
                            { int __k = peek(); if (__k != KIND_DISTINCTKW) { fail("DistinctKW", RULE_FuncCallArgs_KIND); break; } }
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
        { int __k = peek(); if (__k != KIND_FILTERKW) { fail("FilterKW", RULE_FilterClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
        { int __k = peek(); if (__k != KIND_OVERKW) { fail("OverKW", RULE_OverClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_OverClause_KIND); break; }
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
        { int __k = peek(); if (__k != KIND_WITHINKW) { fail("WithinKW", RULE_WithinGroupClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
        advance();
        { int __k = peek(); if (__k != KIND_GROUPKW) { fail("GroupKW", RULE_WithinGroupClause_KIND); pos = savedPos; cst.truncate(savedNodes); return false; } }
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
                    if (java.util.Arrays.binarySearch(ALIAS_COMPAREOP, peek()) < 0) { fail("CompareOp", RULE_Operator_KIND); break; }
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
                            { int __k = peek(); if (__k != KIND_ARRAYKW) { fail("ArrayKW", RULE_ArrayType_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_UUIDTYPE) { fail("UuidType", RULE_ScalarType_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_BYTEATYPE) { fail("ByteaType", RULE_ScalarType_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_XMLTYPE) { fail("XmlType", RULE_ScalarType_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_MONEYTYPE) { fail("MoneyType", RULE_ScalarType_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_DATETYPE) { fail("DateType", RULE_DateTimeType_KIND); break; } }
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
                                        { int __k = peek(); if (__k != KIND_WITHKW) { fail("WithKW", RULE_TimestampType_KIND); break; } }
                                        advance();
                                        { int __k = peek(); if (__k != KIND_TIMEKW) { fail("TimeKW", RULE_TimestampType_KIND); break; } }
                                        advance();
                                        { int __k = peek(); if (__k != KIND_ZONEKW) { fail("ZoneKW", RULE_TimestampType_KIND); break; } }
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
                                        { int __k = peek(); if (__k != KIND_WITHOUTKW) { fail("WithoutKW", RULE_TimestampType_KIND); break; } }
                                        advance();
                                        { int __k = peek(); if (__k != KIND_TIMEKW) { fail("TimeKW", RULE_TimestampType_KIND); break; } }
                                        advance();
                                        { int __k = peek(); if (__k != KIND_ZONEKW) { fail("ZoneKW", RULE_TimestampType_KIND); break; } }
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
                    if (peek() != KIND_TIMEKW) { fail("'time'", RULE_TimeType_KIND); break; }
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
                                        { int __k = peek(); if (__k != KIND_WITHKW) { fail("WithKW", RULE_TimeType_KIND); break; } }
                                        advance();
                                        { int __k = peek(); if (__k != KIND_TIMEKW) { fail("TimeKW", RULE_TimeType_KIND); break; } }
                                        advance();
                                        { int __k = peek(); if (__k != KIND_ZONEKW) { fail("ZoneKW", RULE_TimeType_KIND); break; } }
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
                                        { int __k = peek(); if (__k != KIND_WITHOUTKW) { fail("WithoutKW", RULE_TimeType_KIND); break; } }
                                        advance();
                                        { int __k = peek(); if (__k != KIND_TIMEKW) { fail("TimeKW", RULE_TimeType_KIND); break; } }
                                        advance();
                                        { int __k = peek(); if (__k != KIND_ZONEKW) { fail("ZoneKW", RULE_TimeType_KIND); break; } }
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
                if (!parseIntervalField(self)) { break; }
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

    private boolean parseIntervalField(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_IntervalField_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    { int __k = peek(); if (__k != KIND_YEARKW) { fail("YearKW", RULE_IntervalField_KIND); break; } }
                    advance();
                    // optional: opt_1
                    {
                        int savedPos_opt_1 = pos;
                        int savedNodes_opt_1 = cst.currentNodeCount();
                        boolean optOk_opt_1 = false;
                        do {
                            { int __k = peek(); if (__k != KIND_TOKW) { fail("ToKW", RULE_IntervalField_KIND); break; } }
                            advance();
                            { int __k = peek(); if (__k != KIND_MONTHKW) { fail("MonthKW", RULE_IntervalField_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_MONTHKW) { fail("MonthKW", RULE_IntervalField_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_DAYKW) { fail("DayKW", RULE_IntervalField_KIND); break; } }
                    advance();
                    // optional: opt_2
                    {
                        int savedPos_opt_2 = pos;
                        int savedNodes_opt_2 = cst.currentNodeCount();
                        boolean optOk_opt_2 = false;
                        do {
                            { int __k = peek(); if (__k != KIND_TOKW) { fail("ToKW", RULE_IntervalField_KIND); break; } }
                            advance();
                            // choice: alt_3
                            {
                                int savedPos_alt_3 = pos;
                                int savedNodes_alt_3 = cst.currentNodeCount();
                                boolean matched_alt_3 = false;
                                boolean cutHit_alt_3 = false;
                                if (!matched_alt_3 && !cutHit_alt_3) {
                                    do {
                                        { int __k = peek(); if (__k != KIND_HOURKW) { fail("HourKW", RULE_IntervalField_KIND); break; } }
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
                                        { int __k = peek(); if (__k != KIND_MINUTEKW) { fail("MinuteKW", RULE_IntervalField_KIND); break; } }
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
                                        { int __k = peek(); if (__k != KIND_SECONDKW) { fail("SecondKW", RULE_IntervalField_KIND); break; } }
                                        advance();
                                        matched_alt_3 = true;
                                    } while (false);
                                    if (!matched_alt_3) {
                                        pos = savedPos_alt_3;
                                        cst.truncate(savedNodes_alt_3);
                                    }
                                }
                                if (!matched_alt_3) { fail("<choice>", RULE_IntervalField_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_HOURKW) { fail("HourKW", RULE_IntervalField_KIND); break; } }
                    advance();
                    // optional: opt_4
                    {
                        int savedPos_opt_4 = pos;
                        int savedNodes_opt_4 = cst.currentNodeCount();
                        boolean optOk_opt_4 = false;
                        do {
                            { int __k = peek(); if (__k != KIND_TOKW) { fail("ToKW", RULE_IntervalField_KIND); break; } }
                            advance();
                            // choice: alt_5
                            {
                                int savedPos_alt_5 = pos;
                                int savedNodes_alt_5 = cst.currentNodeCount();
                                boolean matched_alt_5 = false;
                                boolean cutHit_alt_5 = false;
                                if (!matched_alt_5 && !cutHit_alt_5) {
                                    do {
                                        { int __k = peek(); if (__k != KIND_MINUTEKW) { fail("MinuteKW", RULE_IntervalField_KIND); break; } }
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
                                        { int __k = peek(); if (__k != KIND_SECONDKW) { fail("SecondKW", RULE_IntervalField_KIND); break; } }
                                        advance();
                                        matched_alt_5 = true;
                                    } while (false);
                                    if (!matched_alt_5) {
                                        pos = savedPos_alt_5;
                                        cst.truncate(savedNodes_alt_5);
                                    }
                                }
                                if (!matched_alt_5) { fail("<choice>", RULE_IntervalField_KIND); break; }
                            }
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
                    { int __k = peek(); if (__k != KIND_MINUTEKW) { fail("MinuteKW", RULE_IntervalField_KIND); break; } }
                    advance();
                    // optional: opt_6
                    {
                        int savedPos_opt_6 = pos;
                        int savedNodes_opt_6 = cst.currentNodeCount();
                        boolean optOk_opt_6 = false;
                        do {
                            { int __k = peek(); if (__k != KIND_TOKW) { fail("ToKW", RULE_IntervalField_KIND); break; } }
                            advance();
                            { int __k = peek(); if (__k != KIND_SECONDKW) { fail("SecondKW", RULE_IntervalField_KIND); break; } }
                            advance();
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
                    { int __k = peek(); if (__k != KIND_SECONDKW) { fail("SecondKW", RULE_IntervalField_KIND); break; } }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_IntervalField_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
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

    private boolean parseColLabel(int parent) {
        int firstTok = pos;
        int savedPos = pos;
        int savedNodes = cst.currentNodeCount();
        int self = cst.beginNode(RULE_ColLabel_KIND, firstTok, parent);
        // choice: alt_0
        {
            int savedPos_alt_0 = pos;
            int savedNodes_alt_0 = cst.currentNodeCount();
            boolean matched_alt_0 = false;
            boolean cutHit_alt_0 = false;
            if (!matched_alt_0 && !cutHit_alt_0) {
                do {
                    if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_ColLabel_KIND); break; }
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
                    if (java.util.Arrays.binarySearch(ALIAS_RESERVEDKEYWORD, peek()) < 0) { fail("ReservedKeyword", RULE_ColLabel_KIND); break; }
                    advance();
                    matched_alt_0 = true;
                } while (false);
                if (!matched_alt_0) {
                    pos = savedPos_alt_0;
                    cst.truncate(savedNodes_alt_0);
                }
            }
            if (!matched_alt_0) { fail("<choice>", RULE_ColLabel_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
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
        if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_QualifiedName_KIND); pos = savedPos; cst.truncate(savedNodes); return false; }
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
                            if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_QualifiedName_KIND); break; }
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
                    { int __k = peek(); if (__k != KIND_TRUEKW && __k != KIND_FALSEKW) { fail("BooleanLiteral", RULE_Literal_KIND); break; } }
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
                    { int __k = peek(); if (__k != KIND_NULLCONSTRAINT) { fail("NullLiteral", RULE_Literal_KIND); break; } }
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
                    if (java.util.Arrays.binarySearch(IDFALL_COLID, peek()) < 0) { fail("ColId", RULE_DollarString_KIND); break; }
                    advance();
                    int capEndByte_cap_3 = pos > capStartTok_cap_3 ? tokens.endAt(pos - 1) : capStartByte_cap_3;
                    captures.put("tag", new long[]{capStartByte_cap_3, capEndByte_cap_3});
                    if (peek() != KIND_INLINE__DOLLAR) { fail("'$'", RULE_DollarString_KIND); break; }
                    advance();
                    // zero-or-more: rep_4
                    while (true) {
                        int savedPos_rep_4 = pos;
                        int savedNodes_rep_4 = cst.currentNodeCount();
                        boolean iterOk_rep_4 = false;
                        do {
                            // not-predicate: not_5
                            {
                                int savedPos_not_5 = pos;
                                int savedNodes_not_5 = cst.currentNodeCount();
                                boolean notMatched_not_5 = false;
                                do {
                                    if (peek() != KIND_INLINE__DOLLAR) { fail("'$'", RULE_DollarString_KIND); break; }
                                    advance();
                                    // back-reference: $tag
                                    {
                                        long[] cap_bref_6 = captures.get("tag");
                                        if (cap_bref_6 == null) { fail("back-reference $tag not captured", RULE_DollarString_KIND); break; }
                                        int capLen_bref_6 = (int)(cap_bref_6[1] - cap_bref_6[0]);
                                        int posByte_bref_6 = pos < tokens.count() ? tokens.startAt(pos) : tokens.input().length();
                                        String inputStr_bref_6 = tokens.input();
                                        if (posByte_bref_6 + capLen_bref_6 > inputStr_bref_6.length()) { fail("back-reference $tag", RULE_DollarString_KIND); break; }
                                        boolean eq_bref_6 = true;
                                        for (int i = 0; i < capLen_bref_6; i++) {
                                            if (inputStr_bref_6.charAt(posByte_bref_6 + i) != inputStr_bref_6.charAt((int)cap_bref_6[0] + i)) { eq_bref_6 = false; break; }
                                        }
                                        if (!eq_bref_6) { fail("back-reference $tag", RULE_DollarString_KIND); break; }
                                        if (capLen_bref_6 > 0) {
                                            int targetByte_bref_6 = posByte_bref_6 + capLen_bref_6;
                                            while (pos < tokens.count() && tokens.startAt(pos) < targetByte_bref_6) pos++;
                                        }
                                    }
                                    if (peek() != KIND_INLINE__DOLLAR) { fail("'$'", RULE_DollarString_KIND); break; }
                                    advance();
                                    notMatched_not_5 = true;
                                } while (false);
                                pos = savedPos_not_5;
                                cst.truncate(savedNodes_not_5);
                                if (notMatched_not_5) { fail("!<predicate>", RULE_DollarString_KIND); break; }
                            }
                            if (peek() < 0) { fail("<any token>", RULE_DollarString_KIND); break; }
                            advance();
                            iterOk_rep_4 = true;
                        } while (false);
                        if (!iterOk_rep_4) {
                            pos = savedPos_rep_4;
                            cst.truncate(savedNodes_rep_4);
                            break;
                        }
                        if (pos == savedPos_rep_4) break; // guard against infinite loops on zero-width matches
                    }
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
                                if (peek() != KIND_SETKW) { fail("'SET'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_ORDERKW) { fail("'ORDER'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_GROUPKW) { fail("'GROUP'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_HAVINGKW) { fail("'HAVING'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_LIMITKW) { fail("'LIMIT'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_OFFSETKW) { fail("'OFFSET'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_FETCHKW) { fail("'FETCH'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_UNIONKW) { fail("'UNION'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_INTERSECTKW) { fail("'INTERSECT'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_EXCEPTKW) { fail("'EXCEPT'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_JOINKW) { fail("'JOIN'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_INNERKW) { fail("'INNER'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_LEFTKW) { fail("'LEFT'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_RIGHTKW) { fail("'RIGHT'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_FULLKW) { fail("'FULL'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_CROSSKW) { fail("'CROSS'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_NATURALKW) { fail("'NATURAL'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_RETURNINGKW) { fail("'RETURNING'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_VALUESKW) { fail("'VALUES'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_WHEREKW) { fail("'WHERE'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_ONKW) { fail("'ON'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_WINDOWKW) { fail("'WINDOW'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_PARTITIONKW) { fail("'PARTITION'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_ROWSKW) { fail("'ROWS'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_RANGEKW) { fail("'RANGE'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_GROUPSKW) { fail("'GROUPS'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_DOKW) { fail("'DO'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_CONFLICTKW) { fail("'CONFLICT'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_OVERKW) { fail("'OVER'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_FILTERKW) { fail("'FILTER'", RULE_ClauseKeyword_KIND); break; }
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
                                if (peek() != KIND_WITHINKW) { fail("'WITHIN'", RULE_ClauseKeyword_KIND); break; }
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

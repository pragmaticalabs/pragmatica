package org.pragmatica.jbct.parser;

/// Enum mirror of the v6 grammar's rule-kind integer constants
/// (see `Java25ParserV6.RULE_TABLE`, also surfaced via `Java25ParserV6.ruleKinds()`).
///
/// Each value carries its `kindId` (matching the rule's index in v6's RULE_TABLE) and exposes
/// it via {@link #kindId()}. `RuleKind.of(int)` performs O(1) reverse lookup. Switching on
/// `RuleKind` in formatter / linter dispatch restores the compile-time exhaustiveness that
/// raw `int` switches cannot provide (the `default` arm is still required since UNKNOWN may
/// surface for out-of-range kind ids).
///
/// Naming: rule names from the grammar are converted from CamelCase to UPPER_SNAKE_CASE,
/// except `_ROOT` (synthetic root sentinel) → `ROOT` and `ERROR` (synthetic error sentinel)
/// which already match the case convention.
public enum RuleKind {
    COMPILATION_UNIT(0),
    MODULAR_UNIT(1),
    ORDINARY_UNIT(2),
    TOP_LEVEL_MEMBER(3),
    PACKAGE_DECL(4),
    IMPORT_DECL(5),
    QUALIFIED_IMPORT_NAME(6),
    MODULE_DECL(7),
    MODULE_DIRECTIVE(8),
    REQUIRES_DIRECTIVE(9),
    EXPORTS_DIRECTIVE(10),
    OPENS_DIRECTIVE(11),
    USES_DIRECTIVE(12),
    PROVIDES_DIRECTIVE(13),
    TYPE_DECL(14),
    TYPE_KIND(15),
    CLASS_DECL(16),
    INTERFACE_DECL(17),
    ANNOTATION_DECL(18),
    ANNOTATION_BODY(19),
    ANNOTATION_MEMBER(20),
    ANNOTATION_ELEM_DECL(21),
    ENUM_DECL(22),
    RECORD_DECL(23),
    IMPLEMENTS_CLAUSE(24),
    PERMITS_CLAUSE(25),
    TYPE_LIST(26),
    TYPE_PARAMS(27),
    TYPE_PARAM(28),
    CLASS_BODY(29),
    INTERFACE_BODY(30),
    INTERFACE_MEMBER(31),
    INTERFACE_FIELD_DECL(32),
    INTERFACE_VAR_DECL(33),
    CLASS_MEMBER(34),
    MEMBER(35),
    INITIALIZER_BLOCK(36),
    ENUM_BODY(37),
    ENUM_CONSTS(38),
    ENUM_CONST(39),
    RECORD_COMPONENTS(40),
    RECORD_COMP(41),
    RECORD_BODY(42),
    RECORD_MEMBER(43),
    RECORD_STATIC_FIELD(44),
    COMPACT_CONSTRUCTOR(45),
    FIELD_DECL(46),
    VAR_DECLS(47),
    VAR_DECL(48),
    LOCAL_VAR_DECLS(49),
    LOCAL_VAR_DECL(50),
    VAR_INIT(51),
    METHOD_DECL(52),
    PARAMS(53),
    ORDINARY_PARAMS(54),
    PLAIN_PARAM(55),
    RECEIVER_PARAM(56),
    LAST_PARAM(57),
    THROWS(58),
    CONSTRUCTOR_DECL(59),
    BLOCK(60),
    BLOCK_STMT(61),
    LOCAL_TYPE_DECL(62),
    LOCAL_VAR(63),
    LOCAL_VAR_TYPE(64),
    STMT(65),
    FOR_CTRL(66),
    FOR_INIT(67),
    LOCAL_VAR_NO_SEMI(68),
    RESOURCE_SPEC(69),
    RESOURCE(70),
    RESOURCE_CHAIN(71),
    FIELD_OP(72),
    CATCH(73),
    FINALLY(74),
    SWITCH_BLOCK(75),
    SWITCH_RULE(76),
    SWITCH_LABEL(77),
    CASE_ITEM(78),
    PATTERN(79),
    TOP_TYPE_PATTERN(80),
    TYPE_PATTERN(81),
    RECORD_PATTERN(82),
    PATTERN_LIST(83),
    COMPONENT_PATTERN(84),
    GUARD(85),
    EXPR(86),
    ASSIGNMENT(87),
    TERNARY(88),
    LOG_OR(89),
    LOG_AND(90),
    BIT_OR(91),
    BIT_XOR(92),
    BIT_AND(93),
    EQUALITY(94),
    RELATIONAL(95),
    SHIFT(96),
    ADDITIVE(97),
    MULTIPLICATIVE(98),
    UNARY(99),
    POSTFIX(100),
    POST_OP(101),
    PRIMARY(102),
    TYPE_EXPR(103),
    UNANN_TYPE(104),
    PLAIN_REF_TYPE(105),
    PLAIN_TYPE_NAME(106),
    LAMBDA(107),
    LAMBDA_PARAMS(108),
    LAMBDA_PARAM_LIST(109),
    TYPED_LAMBDA_PARAMS(110),
    TYPED_LAMBDA_PARAM(111),
    VAR_LAMBDA_PARAMS(112),
    VAR_LAMBDA_PARAM(113),
    INFERRED_LAMBDA_PARAMS(114),
    STMT_EXPR(115),
    CALL_CHAIN(116),
    CHAIN_OP(117),
    CALL_OP(118),
    ARGS(119),
    EXPR_LIST(120),
    TYPE(121),
    REF_TYPE(122),
    ANNOTATED_TYPE_NAME(123),
    DIMS(124),
    ARRAY_TYPE(125),
    RAW_ARRAY_ELEM_TYPE(126),
    RAW_TYPE_NAME(127),
    WILDCARD_ONLY_TYPE_ARGS(128),
    DIM_EXPRS(129),
    TYPE_ARGS(130),
    TYPE_ARG(131),
    QUALIFIED_NAME(132),
    ANNOTATION(133),
    ANNOTATION_VALUE(134),
    ANNOTATION_ARG(135),
    ANNOTATION_ELEM(136),
    LITERAL(137),
    ERROR(138),
    ROOT(139),
    UNKNOWN(-1);
    private final int kindId;
    RuleKind(int kindId) {
        this.kindId = kindId;
    }
    public int kindId() {
        return kindId;
    }
    private static final RuleKind[] BY_ID;
    static {
        int max = -1;

        for (var k : values()) {
            max = Math.max(max, k.kindId);
        }

        BY_ID = new RuleKind[max + 1];
        for (var k : values()) {
            if (k.kindId >= 0) {
                BY_ID[k.kindId] = k;
            }
        }
    }
    /// O(1) lookup of the enum value for a v6 kindId. Returns UNKNOWN for out-of-range or
    /// unmapped kinds (defensive — should not occur in practice for grammar-emitted kinds).
    public static RuleKind of(int kindId) {
        if (kindId < 0 || kindId >= BY_ID.length) {
            return UNKNOWN;
        }

        var k = BY_ID[kindId];

        return k != null
               ? k
               : UNKNOWN;
    }
    /// True for leaves that originate from a `< … >` token-boundary or named-token rule
    /// (Identifier, Modifier, *KW, PrimType, NumLit, StringLit, CharLit). Used by emit code
    /// to distinguish token-like leaves from grammar-literal leaves where the difference
    /// affects spacing or display.
    public boolean isTokenLike() {
        return false;  // No grammar rules currently emit leaves with rule kinds matching
        // token-like categories — tokens surface in TokenArray, not as
        // CstArray leaves. Kept for forward-compatibility; flip per kind
        // if Stage 4 discovers leaf nodes that need this distinction.
    }
    /// True for grammar-literal leaf rules (specifically the `Literal` rule for numeric,
    /// string, and character literals). Used by emit code that needs to distinguish a
    /// generic terminal from a grammar-defined literal node.
    public boolean isLiteral() {
        return this == LITERAL;
    }
}

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
    ORDINARY_UNIT(1),
    PACKAGE_DECL(2),
    IMPORT_DECL(3),
    MODULE_DECL(4),
    MODULE_DIRECTIVE(5),
    REQUIRES_DIRECTIVE(6),
    EXPORTS_DIRECTIVE(7),
    OPENS_DIRECTIVE(8),
    USES_DIRECTIVE(9),
    PROVIDES_DIRECTIVE(10),
    TYPE_DECL(11),
    TYPE_KIND(12),
    CLASS_DECL(13),
    INTERFACE_DECL(14),
    ANNOTATION_DECL(15),
    ANNOTATION_BODY(16),
    ANNOTATION_MEMBER(17),
    ANNOTATION_ELEM_DECL(18),
    ENUM_DECL(19),
    RECORD_DECL(20),
    IMPLEMENTS_CLAUSE(21),
    PERMITS_CLAUSE(22),
    TYPE_LIST(23),
    TYPE_PARAMS(24),
    TYPE_PARAM(25),
    CLASS_BODY(26),
    CLASS_MEMBER(27),
    MEMBER(28),
    INITIALIZER_BLOCK(29),
    ENUM_BODY(30),
    ENUM_CONSTS(31),
    ENUM_CONST(32),
    RECORD_COMPONENTS(33),
    RECORD_COMP(34),
    RECORD_BODY(35),
    RECORD_MEMBER(36),
    COMPACT_CONSTRUCTOR(37),
    FIELD_DECL(38),
    VAR_DECLS(39),
    VAR_DECL(40),
    VAR_INIT(41),
    METHOD_DECL(42),
    PARAMS(43),
    PARAM(44),
    THROWS(45),
    CONSTRUCTOR_DECL(46),
    BLOCK(47),
    BLOCK_STMT(48),
    LOCAL_TYPE_DECL(49),
    LOCAL_VAR(50),
    LOCAL_VAR_TYPE(51),
    STMT(52),
    FOR_CTRL(53),
    FOR_INIT(54),
    LOCAL_VAR_NO_SEMI(55),
    RESOURCE_SPEC(56),
    RESOURCE(57),
    CATCH(58),
    FINALLY(59),
    SWITCH_BLOCK(60),
    SWITCH_RULE(61),
    SWITCH_LABEL(62),
    CASE_ITEM(63),
    PATTERN(64),
    TYPE_PATTERN(65),
    RECORD_PATTERN(66),
    PATTERN_LIST(67),
    GUARD(68),
    EXPR(69),
    ASSIGNMENT(70),
    TERNARY(71),
    LOG_OR(72),
    LOG_AND(73),
    BIT_OR(74),
    BIT_XOR(75),
    BIT_AND(76),
    EQUALITY(77),
    RELATIONAL(78),
    SHIFT(79),
    ADDITIVE(80),
    MULTIPLICATIVE(81),
    UNARY(82),
    POSTFIX(83),
    POST_OP(84),
    PRIMARY(85),
    TYPE_EXPR(86),
    LAMBDA(87),
    LAMBDA_PARAMS(88),
    LAMBDA_PARAM(89),
    ARGS(90),
    EXPR_LIST(91),
    TYPE(92),
    REF_TYPE(93),
    ANNOTATED_TYPE_NAME(94),
    DIMS(95),
    ARRAY_TYPE(96),
    DIM_EXPRS(97),
    TYPE_ARGS(98),
    TYPE_ARG(99),
    QUALIFIED_NAME(100),
    ANNOTATION(101),
    ANNOTATION_VALUE(102),
    ANNOTATION_ELEM(103),
    LITERAL(104),
    ERROR(105),
    ROOT(106),
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
        return k != null ? k : UNKNOWN;
    }

    /// True for leaves that originate from a `< … >` token-boundary or named-token rule
    /// (Identifier, Modifier, *KW, PrimType, NumLit, StringLit, CharLit). Used by emit code
    /// to distinguish token-like leaves from grammar-literal leaves where the difference
    /// affects spacing or display.
    public boolean isTokenLike() {
        return false; // No grammar rules currently emit leaves with rule kinds matching
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

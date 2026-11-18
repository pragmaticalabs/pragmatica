package org.pragmatica.jbct.cli.skeleton;

/// What a skeleton line declares. `FIELD` and `ENUM_CONSTANT` are listed only when fields are asked for.
public enum Kind {
    CLASS("class"),
    INTERFACE("interface"),
    RECORD("record"),
    ENUM("enum"),
    ANNOTATION("annotation"),
    METHOD("method"),
    CONSTRUCTOR("constructor"),
    INITIALIZER("initializer"),
    FIELD("field"),
    ENUM_CONSTANT("enum-constant");
    private final String label;
    Kind(String label) {
        this.label = label;
    }
    public String label() {
        return label;
    }
    /// True for the kinds that are listed only with `--fields`.
    public boolean isFieldLike() {
        return this == FIELD || this == ENUM_CONSTANT;
    }
}

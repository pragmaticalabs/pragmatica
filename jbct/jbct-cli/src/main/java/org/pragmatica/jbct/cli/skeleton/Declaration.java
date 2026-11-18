package org.pragmatica.jbct.cli.skeleton;

import java.util.List;

import org.pragmatica.lang.Option;


/// One declaration of a skeleton: what it is, its name, its signature as written in the source, the range from
/// its first annotation or modifier to its last character, the row where its doc comment starts (the comment is
/// not inside the range), its annotations as written, `@Name`, and the declarations nested in it.
public record Declaration(Kind kind,
                          String name,
                          String signature,
                          Range range,
                          Option<Integer> docRow,
                          List<String> annotations,
                          List<Declaration> children) {
    public static Declaration declaration(Kind kind,
                                          String name,
                                          String signature,
                                          Range range,
                                          Option<Integer> docRow,
                                          List<String> annotations,
                                          List<Declaration> children) {
        return new Declaration(kind, name, signature, range, docRow, annotations, List.copyOf(children));
    }

    /// The name with the signature appended, empty for a declaration with neither (an instance initializer).
    public String label() {
        return name + signature;
    }
}

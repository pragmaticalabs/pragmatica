package org.pragmatica.jbct.cli.skeleton;

import java.util.List;

/// The declarations of one source file, in source order.
public record Skeleton(List<Declaration> declarations) {
    public static Skeleton skeleton(List<Declaration> declarations) {
        return new Skeleton(List.copyOf(declarations));
    }
}

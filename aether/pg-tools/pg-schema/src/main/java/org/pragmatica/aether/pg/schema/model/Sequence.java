package org.pragmatica.aether.pg.schema.model;

import org.pragmatica.lang.Option;


/// A sequence definition.
public record Sequence(String name,
                       String schema,
                       Option<String> dataType,
                       Option<Long> startValue,
                       Option<Long> increment,
                       Option<Long> minValue,
                       Option<Long> maxValue,
                       Option<Long> cache,
                       boolean cycle,
                       Option<String> ownedBy) {
    public static Sequence sequence(String name, String schema) {
        return new Sequence(name,
                            schema,
                            Option.empty(),
                            Option.empty(),
                            Option.empty(),
                            Option.empty(),
                            Option.empty(),
                            Option.empty(),
                            false,
                            Option.empty());
    }
}

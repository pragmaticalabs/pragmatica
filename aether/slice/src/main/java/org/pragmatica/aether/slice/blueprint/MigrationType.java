package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.lang.Option;


/// Migration script type based on filename prefix (Flyway-style naming convention).
public enum MigrationType {
    VERSIONED,
    REPEATABLE,
    UNDO,
    BASELINE;
    public static Option<MigrationType> migrationType(String filename) {
        if (filename == null || filename.isEmpty()) {return Option.none();}
        return switch (filename.charAt(0)){
            case 'V' -> Option.some(VERSIONED);
            case 'R' -> Option.some(REPEATABLE);
            case 'U' -> Option.some(UNDO);
            case 'B' -> Option.some(BASELINE);
            default -> Option.none();
        };
    }
}

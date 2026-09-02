package org.pragmatica.jbct.init;

import java.nio.file.Path;
import java.util.List;


/// Outcome of an AI-tools install/update operation.
/// `installed` lists files written into the project's .claude/ directory;
/// `skippedGlobal` lists files skipped because an equivalent already exists in
/// the user's global ~/.claude/ directory (Claude Code resolves those globally).
public record AiToolsOutcome(List<Path> installed, List<Path> skippedGlobal) {
    public static AiToolsOutcome aiToolsOutcome(List<Path> installed, List<Path> skippedGlobal) {
        return new AiToolsOutcome(List.copyOf(installed), List.copyOf(skippedGlobal));
    }

    /// True when nothing was installed and nothing was skipped (no work performed).
    public boolean isEmpty() {
        return installed.isEmpty() && skippedGlobal.isEmpty();
    }
}

package org.pragmatica.aether.repository.domain;

import org.pragmatica.lang.Result;

public interface SemanticVersion {
    int major();
    int minor();
    int patch();

    static Result<SemanticVersion> create(int major, int minor, int patch) {
        record semanticVersion(int major, int minor, int patch) implements SemanticVersion {}

        if (major < 0 || minor < 0 || patch < 0) {
            return RepositoryErrors.INVALID_VERSION.result();
        }

        return Result.success(new semanticVersion(major, minor, patch));
    }
}

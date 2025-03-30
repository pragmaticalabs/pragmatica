package org.pragmatica.aether.repository.domain;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public interface LocatedArtifact extends Artifact {
    Location location();

    List<Artifact> dependencies();

    sealed interface Location {
        record File(Path path) implements Location {
        }

        record Memory(byte[] data) implements Location {
            @Override
            public boolean equals(Object o) {
                if (o instanceof Memory memory) {
                    return Objects.deepEquals(data, memory.data);
                }
                return false;
            }

            @Override
            public int hashCode() {
                return Arrays.hashCode(data);
            }
        }
    }
}

package org.pragmatica.aether.config.cluster;
public record CoreSpec( int count, int min, int max) {
    /// Factory method.
    public static CoreSpec coreSpec(int count, int min, int max) {
        return new CoreSpec(count, min, max);
    }
}

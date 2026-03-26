package org.pragmatica.aether.config.cluster;
/// Core node specification.
///
/// @param count desired core node count (must be odd, >= 3)
/// @param min minimum core nodes (quorum safety floor)
/// @param max maximum core nodes (growth cap)
public record CoreSpec(int count, int min, int max) {
    /// Factory method.
    public static CoreSpec coreSpec(int count, int min, int max) {
        return new CoreSpec(count, min, max);
    }
}

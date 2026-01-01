package org.pragmatica.aether.example;

import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;

import java.util.List;

/**
 * Example slice implementation that provides simple string processing capabilities.
 * This demonstrates the complete lifecycle of a deployable slice.
 * <p>
 * Note: start() and stop() use default implementations from Slice interface,
 * which return successfully resolved Promise instances.
 */
public record StringProcessorSlice() implements Slice {
    @Override
    public List<SliceMethod< ? , ? >> methods() {
        return List.of();
    }
}

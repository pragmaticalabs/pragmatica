package org.pragmatica.aether.infra.blob;

import java.util.Arrays;

/**
 * Container for blob content with metadata.
 *
 * @param metadata Blob metadata
 * @param data     Blob content bytes
 */
public record BlobContent(BlobMetadata metadata, byte[] data) {
    /**
     * Creates blob content.
     */
    public static BlobContent blobContent(BlobMetadata metadata, byte[] data) {
        return new BlobContent(metadata, Arrays.copyOf(data, data.length));
    }

    /**
     * Returns a copy of the data to prevent mutation.
     */
    @Override
    public byte[] data() {
        return Arrays.copyOf(data, data.length);
    }
}

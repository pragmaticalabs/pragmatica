package org.pragmatica.postgres.io.frontend;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.pragmatica.postgres.message.frontend.StartupMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #2948: the startup message carries {@code application_name} only when one is set.
 */
public class StartupMessageEncoderTest {
    private static String encoded(StartupMessage message) {
        var buffer = ByteBuffer.allocate(512);

        new StartupMessageEncoder().write(message, buffer, StandardCharsets.UTF_8);
        buffer.flip();

        var bytes = new byte[buffer.remaining()];

        buffer.get(bytes);

        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test
    public void write_includesApplicationName_whenSet() {
        var text = encoded(new StartupMessage("u", "d", "bench-arm-a"));

        assertTrue(text.contains("\0application_name\0bench-arm-a\0"), text);
    }

    // The encoder is the byte-level authority: a name that is not ASCII must go out as its UTF-8 bytes, not as '?'.
    @Test
    public void write_encodesApplicationNameAsUtf8() {
        var text = encoded(new StartupMessage("u", "d", "z\u00f3\u0142w-\u65e5"));

        assertTrue(text.contains("\0application_name\0z\u00f3\u0142w-\u65e5\0"), text);
    }

    @Test
    public void write_omitsApplicationName_whenNull() {
        assertFalse(encoded(new StartupMessage("u", "d")).contains("application_name"));
    }

    @Test
    public void write_lengthPrefixCoversTheWholeMessage() {
        var buffer = ByteBuffer.allocate(512);

        new StartupMessageEncoder().write(new StartupMessage("u", "d", "n"), buffer, StandardCharsets.UTF_8);

        assertEquals(buffer.position(), buffer.getInt(0));
    }
}

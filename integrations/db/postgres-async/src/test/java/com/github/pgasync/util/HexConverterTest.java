package com.github.pgasync.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class HexConverterTest {
    @Test
    public void correctHexadecimalStringIsParsedSuccessfully() {
        assertArrayEquals(new byte[]{0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF}, HexConverter.parseHexBinary("0123456789ABCDEF"));
        assertArrayEquals(new byte[]{0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF}, HexConverter.parseHexBinary("0123456789abcdef"));
    }

    @Test
    public void incorrectHexadecimalCharacterThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> {
            HexConverter.parseHexBinary("G");
        });
    }

    @Test
    public void incorrectLenghtThrowsException() {
        assertThrows(IllegalArgumentException.class, () -> {
            HexConverter.parseHexBinary("012");
        });
    }
}

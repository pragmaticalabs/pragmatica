package org.pragmatica.postgres.conversion;

import java.nio.charset.Charset;

import org.pragmatica.postgres.net.PgWriter;


/// Single-shot, reusable `PgWriter` implementation. The framework allocates one per
/// Bind message and resets it between parameters; converters write exactly once and
/// the framework reads back `(bytes, isBinary)` to construct the wire message.
final class CapturingPgWriter implements PgWriter {
    private final Charset encoding;
    private byte[] bytes;
    private boolean binary;
    private boolean written;

    CapturingPgWriter(Charset encoding) {
        this.encoding = encoding;
    }

    @Override
    public void writeText(String value) {
        ensureUnwritten();
        bytes = value.getBytes(encoding);
        binary = false;
        written = true;
    }

    @Override
    public void writeBinary(byte[] value) {
        ensureUnwritten();
        bytes = value;
        binary = true;
        written = true;
    }

    void reset() {
        written = false;
        bytes = null;
        binary = false;
    }

    byte[] bytes() {
        ensureWritten();

        return bytes;
    }

    boolean isBinary() {
        ensureWritten();

        return binary;
    }

    boolean isWritten() {
        return written;
    }

    private void ensureUnwritten() {
        if (written) {
            throw new IllegalStateException("PgWriter already produced a value; converters must call writeText/writeBinary exactly once");
        }
    }

    private void ensureWritten() {
        if (!written) {
            throw new IllegalStateException("PgWriter was not written by the converter");
        }
    }
}

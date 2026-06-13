package org.pragmatica.postgres.net;

/**
 * Converters extend the driver to handle complex data types,
 * for example json or hstore that have no "standard" Java
 * representation.
 *
 * @author Antti Laisi.
 */
public interface Converter<T> {

    /**
     * @return Class to convert
     */
    Class<T> type();

    /**
     * Write a value into the bind sink. The converter must call exactly one of
     * `writer.writeText(...)` / `writer.writeBinary(...)` per invocation.
     *
     * @param value Value to encode, never null
     * @param writer Sink to receive the encoded wire bytes
     */
    void from(T value, PgWriter writer);

    /**
     * Decode a value received from PostgreSQL.
     *
     * @param value Value carried in either text or binary wire format. Use
     *              `value.asBytes()` for raw bytes, pattern-match on
     *              `PgValue.Text` / `PgValue.Binary` to handle each format
     *              explicitly.
     * @return Converted object, never null
     */
    T to(PgValue value);
}

package org.pragmatica.postgres.conversion;

import org.pragmatica.postgres.Oid;

import java.nio.ByteBuffer;
import java.time.*;
import java.util.UUID;

public sealed interface BinaryCodec<T> {
    Oid oid();
    T decode(ByteBuffer buffer, int length);
    void encode(T value, ByteBuffer buffer);
    int estimateSize(T value);

    long PG_EPOCH_MICROS_OFFSET = 946_684_800_000_000L;

    record Int2Codec() implements BinaryCodec<Short> {
        @Override public Oid oid() { return Oid.INT2; }
        @Override public Short decode(ByteBuffer buffer, int length) { return buffer.getShort(); }
        @Override public void encode(Short value, ByteBuffer buffer) { buffer.putShort(value); }
        @Override public int estimateSize(Short value) { return 2; }
    }

    record Int4Codec() implements BinaryCodec<Integer> {
        @Override public Oid oid() { return Oid.INT4; }
        @Override public Integer decode(ByteBuffer buffer, int length) { return buffer.getInt(); }
        @Override public void encode(Integer value, ByteBuffer buffer) { buffer.putInt(value); }
        @Override public int estimateSize(Integer value) { return 4; }
    }

    record Int8Codec() implements BinaryCodec<Long> {
        @Override public Oid oid() { return Oid.INT8; }
        @Override public Long decode(ByteBuffer buffer, int length) { return buffer.getLong(); }
        @Override public void encode(Long value, ByteBuffer buffer) { buffer.putLong(value); }
        @Override public int estimateSize(Long value) { return 8; }
    }

    record Float4Codec() implements BinaryCodec<Float> {
        @Override public Oid oid() { return Oid.FLOAT4; }
        @Override public Float decode(ByteBuffer buffer, int length) { return buffer.getFloat(); }
        @Override public void encode(Float value, ByteBuffer buffer) { buffer.putFloat(value); }
        @Override public int estimateSize(Float value) { return 4; }
    }

    record Float8Codec() implements BinaryCodec<Double> {
        @Override public Oid oid() { return Oid.FLOAT8; }
        @Override public Double decode(ByteBuffer buffer, int length) { return buffer.getDouble(); }
        @Override public void encode(Double value, ByteBuffer buffer) { buffer.putDouble(value); }
        @Override public int estimateSize(Double value) { return 8; }
    }

    record BoolCodec() implements BinaryCodec<Boolean> {
        @Override public Oid oid() { return Oid.BOOL; }
        @Override public Boolean decode(ByteBuffer buffer, int length) { return buffer.get() != 0; }
        @Override public void encode(Boolean value, ByteBuffer buffer) { buffer.put((byte) (value ? 1 : 0)); }
        @Override public int estimateSize(Boolean value) { return 1; }
    }

    record ByteaCodec() implements BinaryCodec<byte[]> {
        @Override public Oid oid() { return Oid.BYTEA; }
        @Override public byte[] decode(ByteBuffer buffer, int length) {
            var bytes = new byte[length];
            buffer.get(bytes);
            return bytes;
        }
        @Override public void encode(byte[] value, ByteBuffer buffer) { buffer.put(value); }
        @Override public int estimateSize(byte[] value) { return value.length; }
    }

    record UuidCodec() implements BinaryCodec<UUID> {
        @Override public Oid oid() { return Oid.UUID; }
        @Override public UUID decode(ByteBuffer buffer, int length) {
            return new UUID(buffer.getLong(), buffer.getLong());
        }
        @Override public void encode(UUID value, ByteBuffer buffer) {
            buffer.putLong(value.getMostSignificantBits());
            buffer.putLong(value.getLeastSignificantBits());
        }
        @Override public int estimateSize(UUID value) { return 16; }
    }

    record DateCodec() implements BinaryCodec<LocalDate> {
        private static final LocalDate PG_EPOCH = LocalDate.of(2000, 1, 1);
        @Override public Oid oid() { return Oid.DATE; }
        @Override public LocalDate decode(ByteBuffer buffer, int length) {
            return PG_EPOCH.plusDays(buffer.getInt());
        }
        @Override public void encode(LocalDate value, ByteBuffer buffer) {
            buffer.putInt((int) (value.toEpochDay() - PG_EPOCH.toEpochDay()));
        }
        @Override public int estimateSize(LocalDate value) { return 4; }
    }

    record TimeCodec() implements BinaryCodec<LocalTime> {
        @Override public Oid oid() { return Oid.TIME; }
        @Override public LocalTime decode(ByteBuffer buffer, int length) {
            long micros = buffer.getLong();
            return LocalTime.ofNanoOfDay(micros * 1000);
        }
        @Override public void encode(LocalTime value, ByteBuffer buffer) {
            buffer.putLong(value.toNanoOfDay() / 1000);
        }
        @Override public int estimateSize(LocalTime value) { return 8; }
    }

    record TimeTzCodec() implements BinaryCodec<OffsetTime> {
        @Override public Oid oid() { return Oid.TIMETZ; }
        @Override public OffsetTime decode(ByteBuffer buffer, int length) {
            long micros = buffer.getLong();
            int offsetSecs = -buffer.getInt(); // PG stores offset inverted
            return OffsetTime.of(LocalTime.ofNanoOfDay(micros * 1000), ZoneOffset.ofTotalSeconds(offsetSecs));
        }
        @Override public void encode(OffsetTime value, ByteBuffer buffer) {
            buffer.putLong(value.toLocalTime().toNanoOfDay() / 1000);
            buffer.putInt(-value.getOffset().getTotalSeconds());
        }
        @Override public int estimateSize(OffsetTime value) { return 12; }
    }

    record TimestampCodec() implements BinaryCodec<LocalDateTime> {
        @Override public Oid oid() { return Oid.TIMESTAMP; }
        @Override public LocalDateTime decode(ByteBuffer buffer, int length) {
            long pgMicros = buffer.getLong();
            long epochMicros = pgMicros + PG_EPOCH_MICROS_OFFSET;
            long epochSecs = Math.floorDiv(epochMicros, 1_000_000);
            int nanoAdj = (int) (Math.floorMod(epochMicros, 1_000_000) * 1000);
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSecs, nanoAdj), ZoneOffset.UTC);
        }
        @Override public void encode(LocalDateTime value, ByteBuffer buffer) {
            var instant = value.toInstant(ZoneOffset.UTC);
            long epochMicros = instant.getEpochSecond() * 1_000_000 + instant.getNano() / 1000;
            buffer.putLong(epochMicros - PG_EPOCH_MICROS_OFFSET);
        }
        @Override public int estimateSize(LocalDateTime value) { return 8; }
    }

    record TimestampTzCodec() implements BinaryCodec<Instant> {
        @Override public Oid oid() { return Oid.TIMESTAMPTZ; }
        @Override public Instant decode(ByteBuffer buffer, int length) {
            long pgMicros = buffer.getLong();
            long epochMicros = pgMicros + PG_EPOCH_MICROS_OFFSET;
            long epochSecs = Math.floorDiv(epochMicros, 1_000_000);
            int nanoAdj = (int) (Math.floorMod(epochMicros, 1_000_000) * 1000);
            return Instant.ofEpochSecond(epochSecs, nanoAdj);
        }
        @Override public void encode(Instant value, ByteBuffer buffer) {
            long epochMicros = value.getEpochSecond() * 1_000_000 + value.getNano() / 1000;
            buffer.putLong(epochMicros - PG_EPOCH_MICROS_OFFSET);
        }
        @Override public int estimateSize(Instant value) { return 8; }
    }
}

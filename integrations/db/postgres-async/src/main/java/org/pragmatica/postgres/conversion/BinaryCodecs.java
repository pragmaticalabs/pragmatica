package org.pragmatica.postgres.conversion;

import org.pragmatica.postgres.Oid;

import java.util.EnumMap;

public final class BinaryCodecs {
    private BinaryCodecs() {}

    private static final EnumMap<Oid, BinaryCodec<?>> CODECS = new EnumMap<>(Oid.class);

    static {
        register(new BinaryCodec.Int2Codec());
        register(new BinaryCodec.Int4Codec());
        register(new BinaryCodec.Int8Codec());
        register(new BinaryCodec.Float4Codec());
        register(new BinaryCodec.Float8Codec());
        register(new BinaryCodec.BoolCodec());
        register(new BinaryCodec.ByteaCodec());
        register(new BinaryCodec.UuidCodec());
        register(new BinaryCodec.DateCodec());
        register(new BinaryCodec.TimeCodec());
        register(new BinaryCodec.TimeTzCodec());
        register(new BinaryCodec.TimestampCodec());
        register(new BinaryCodec.TimestampTzCodec());
    }

    private static void register(BinaryCodec<?> codec) {
        CODECS.put(codec.oid(), codec);
    }

    public static BinaryCodec<?> forOid(Oid oid) {
        return CODECS.get(oid);
    }

    public static short[] resultFormatCodes(Oid[] columnTypes) {
        var codes = new short[columnTypes.length];
        for (int i = 0; i < columnTypes.length; i++) {
            codes[i] = (short) (columnTypes[i].supportsBinary() ? 1 : 0);
        }
        return codes;
    }
}

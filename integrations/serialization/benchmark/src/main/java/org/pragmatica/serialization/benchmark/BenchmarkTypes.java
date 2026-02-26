package org.pragmatica.serialization.benchmark;

import io.netty.buffer.ByteBuf;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.serialization.SliceCodec.TypeCodec;

import java.util.ArrayList;
import java.util.List;

public sealed interface BenchmarkTypes {
    record unused() implements BenchmarkTypes {}

    record SimpleRecord(long id, String name) {}

    record ComplexRecord(String id, List<SimpleRecord> items, long timestamp) {}

    enum BenchmarkEnum { A, B, C, D, E }

    record MixedRecord(SimpleRecord source, BenchmarkEnum state, int count, String payload) {}

    // --- Tag constants ---

    int TAG_SIMPLE_RECORD = SliceCodec.deterministicTag(
        "org.pragmatica.serialization.benchmark.BenchmarkTypes.SimpleRecord");
    int TAG_COMPLEX_RECORD = SliceCodec.deterministicTag(
        "org.pragmatica.serialization.benchmark.BenchmarkTypes.ComplexRecord");
    int TAG_BENCHMARK_ENUM = SliceCodec.deterministicTag(
        "org.pragmatica.serialization.benchmark.BenchmarkTypes.BenchmarkEnum");
    int TAG_MIXED_RECORD = SliceCodec.deterministicTag(
        "org.pragmatica.serialization.benchmark.BenchmarkTypes.MixedRecord");

    // --- SimpleRecord codec ---

    TypeCodec<SimpleRecord> SIMPLE_RECORD_CODEC = new TypeCodec<>(
        SimpleRecord.class, TAG_SIMPLE_RECORD, _ -> TAG_SIMPLE_RECORD,
        BenchmarkTypes::writeSimpleRecord, BenchmarkTypes::readSimpleRecord);

    private static void writeSimpleRecord(SliceCodec codec, ByteBuf buf, SimpleRecord value) {
        codec.write(buf, value.id());
        codec.write(buf, value.name());
    }

    private static SimpleRecord readSimpleRecord(SliceCodec codec, ByteBuf buf) {
        var id = (Long) codec.read(buf);
        var name = (String) codec.read(buf);
        return new SimpleRecord(id, name);
    }

    // --- ComplexRecord codec ---

    @SuppressWarnings({"unchecked", "rawtypes"})
    TypeCodec<ComplexRecord> COMPLEX_RECORD_CODEC = new TypeCodec<>(
        ComplexRecord.class, TAG_COMPLEX_RECORD, _ -> TAG_COMPLEX_RECORD,
        BenchmarkTypes::writeComplexRecord, BenchmarkTypes::readComplexRecord);

    @SuppressWarnings("unchecked")
    private static void writeComplexRecord(SliceCodec codec, ByteBuf buf, ComplexRecord value) {
        codec.write(buf, value.id());
        codec.write(buf, value.items());
        codec.write(buf, value.timestamp());
    }

    @SuppressWarnings("unchecked")
    private static ComplexRecord readComplexRecord(SliceCodec codec, ByteBuf buf) {
        var id = (String) codec.read(buf);
        var items = (List<SimpleRecord>) codec.read(buf);
        var timestamp = (Long) codec.read(buf);
        return new ComplexRecord(id, items, timestamp);
    }

    // --- BenchmarkEnum codec ---

    TypeCodec<BenchmarkEnum> BENCHMARK_ENUM_CODEC = new TypeCodec<>(
        BenchmarkEnum.class, TAG_BENCHMARK_ENUM, _ -> TAG_BENCHMARK_ENUM,
        BenchmarkTypes::writeBenchmarkEnum, BenchmarkTypes::readBenchmarkEnum);

    private static void writeBenchmarkEnum(SliceCodec codec, ByteBuf buf, BenchmarkEnum value) {
        SliceCodec.writeCompact(buf, value.ordinal());
    }

    private static BenchmarkEnum readBenchmarkEnum(SliceCodec codec, ByteBuf buf) {
        return BenchmarkEnum.values()[SliceCodec.readCompact(buf)];
    }

    // --- MixedRecord codec ---

    TypeCodec<MixedRecord> MIXED_RECORD_CODEC = new TypeCodec<>(
        MixedRecord.class, TAG_MIXED_RECORD, _ -> TAG_MIXED_RECORD,
        BenchmarkTypes::writeMixedRecord, BenchmarkTypes::readMixedRecord);

    private static void writeMixedRecord(SliceCodec codec, ByteBuf buf, MixedRecord value) {
        codec.write(buf, value.source());
        codec.write(buf, value.state());
        codec.write(buf, value.count());
        codec.write(buf, value.payload());
    }

    private static MixedRecord readMixedRecord(SliceCodec codec, ByteBuf buf) {
        var source = (SimpleRecord) codec.read(buf);
        var state = (BenchmarkEnum) codec.read(buf);
        var count = (Integer) codec.read(buf);
        var payload = (String) codec.read(buf);
        return new MixedRecord(source, state, count, payload);
    }

    // --- Codec list for SliceCodec construction ---

    List<TypeCodec<?>> ALL_CODECS = List.of(
        SIMPLE_RECORD_CODEC, COMPLEX_RECORD_CODEC, BENCHMARK_ENUM_CODEC, MIXED_RECORD_CODEC);
}

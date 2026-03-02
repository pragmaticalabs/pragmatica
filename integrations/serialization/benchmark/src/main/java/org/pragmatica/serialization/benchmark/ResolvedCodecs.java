package org.pragmatica.serialization.benchmark;

import io.netty.buffer.ByteBuf;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.serialization.SliceCodec.TypeCodec;
import org.pragmatica.serialization.SliceCodec.TypeWriter;
import org.pragmatica.serialization.SliceCodec.TypeReader;

import java.util.List;

import static org.pragmatica.serialization.benchmark.BenchmarkTypes.*;

/// Pre-resolved codecs that inline primitive writes and capture nested codec refs.
/// Simulates what generated code would do with upfront dependency resolution.
public sealed interface ResolvedCodecs {
    record unused() implements ResolvedCodecs {}

    static SliceCodec resolvedCodec() {
        // Phase 1: resolve SimpleRecord + BenchmarkEnum (no nested codec deps)
        var simpleRecordCodec = resolvedSimpleRecordCodec();
        var benchmarkEnumCodec = resolvedBenchmarkEnumCodec();

        // Phase 2: resolve MixedRecord (depends on SimpleRecord + BenchmarkEnum)
        var mixedRecordCodec = resolvedMixedRecordCodec(
            simpleRecordCodec.writer(), simpleRecordCodec.reader(),
            benchmarkEnumCodec.writer(), benchmarkEnumCodec.reader());

        // Phase 3: resolve ComplexRecord (List field still dispatched, but String/long inlined)
        var complexRecordCodec = resolvedComplexRecordCodec(
            simpleRecordCodec.writer(), simpleRecordCodec.reader());

        return SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), List.of(
            simpleRecordCodec, complexRecordCodec, benchmarkEnumCodec, mixedRecordCodec));
    }

    // --- SimpleRecord: all fields inlined ---

    private static TypeCodec<SimpleRecord> resolvedSimpleRecordCodec() {
        return new TypeCodec<>(SimpleRecord.class, TAG_SIMPLE_RECORD, _ -> TAG_SIMPLE_RECORD,
            (_, buf, value) -> {
                buf.writeByte(SliceCodec.TAG_LONG);
                buf.writeLong(value.id());
                buf.writeByte(SliceCodec.TAG_STRING);
                SliceCodec.writeString(buf, value.name());
            },
            (_, buf) -> {
                buf.readByte();
                long id = buf.readLong();
                buf.readByte();
                var name = SliceCodec.readString(buf);
                return new SimpleRecord(id, name);
            });
    }

    // --- BenchmarkEnum: same as current (already minimal) ---

    private static TypeCodec<BenchmarkEnum> resolvedBenchmarkEnumCodec() {
        return new TypeCodec<>(BenchmarkEnum.class, TAG_BENCHMARK_ENUM, _ -> TAG_BENCHMARK_ENUM,
            (_, buf, value) -> SliceCodec.writeCompact(buf, value.ordinal()),
            (_, buf) -> BenchmarkEnum.values()[SliceCodec.readCompact(buf)]);
    }

    // --- MixedRecord: captures pre-resolved SimpleRecord + BenchmarkEnum writers/readers ---

    private static TypeCodec<MixedRecord> resolvedMixedRecordCodec(
            TypeWriter<SimpleRecord> simpleWriter, TypeReader<SimpleRecord> simpleReader,
            TypeWriter<BenchmarkEnum> enumWriter, TypeReader<BenchmarkEnum> enumReader) {
        return new TypeCodec<>(MixedRecord.class, TAG_MIXED_RECORD, _ -> TAG_MIXED_RECORD,
            (codec, buf, value) -> {
                SliceCodec.writeCompact(buf, TAG_SIMPLE_RECORD);
                simpleWriter.writeBody(codec, buf, value.source());
                SliceCodec.writeCompact(buf, TAG_BENCHMARK_ENUM);
                enumWriter.writeBody(codec, buf, value.state());
                buf.writeByte(SliceCodec.TAG_INT);
                buf.writeInt(value.count());
                buf.writeByte(SliceCodec.TAG_STRING);
                SliceCodec.writeString(buf, value.payload());
            },
            (codec, buf) -> {
                SliceCodec.readCompact(buf);
                var source = simpleReader.readBody(codec, buf);
                SliceCodec.readCompact(buf);
                var state = enumReader.readBody(codec, buf);
                buf.readByte();
                int count = buf.readInt();
                buf.readByte();
                var payload = SliceCodec.readString(buf);
                return new MixedRecord(source, state, count, payload);
            });
    }

    // --- ComplexRecord: String/long inlined, List still dispatched ---

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static TypeCodec<ComplexRecord> resolvedComplexRecordCodec(
            TypeWriter<SimpleRecord> simpleWriter, TypeReader<SimpleRecord> simpleReader) {
        return new TypeCodec<>(ComplexRecord.class, TAG_COMPLEX_RECORD, _ -> TAG_COMPLEX_RECORD,
            (codec, buf, value) -> {
                buf.writeByte(SliceCodec.TAG_STRING);
                SliceCodec.writeString(buf, value.id());
                codec.write(buf, value.items());
                buf.writeByte(SliceCodec.TAG_LONG);
                buf.writeLong(value.timestamp());
            },
            (codec, buf) -> {
                buf.readByte();
                var id = SliceCodec.readString(buf);
                var items = (List<SimpleRecord>) codec.read(buf);
                buf.readByte();
                long timestamp = buf.readLong();
                return new ComplexRecord(id, items, timestamp);
            });
    }
}

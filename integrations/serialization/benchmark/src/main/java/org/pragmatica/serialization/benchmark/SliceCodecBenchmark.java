package org.pragmatica.serialization.benchmark;

import org.apache.fory.Fory;
import org.apache.fory.config.Language;
import org.openjdk.jmh.annotations.*;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import org.pragmatica.serialization.benchmark.BenchmarkTypes.BenchmarkEnum;
import org.pragmatica.serialization.benchmark.BenchmarkTypes.ComplexRecord;
import org.pragmatica.serialization.benchmark.BenchmarkTypes.MixedRecord;
import org.pragmatica.serialization.benchmark.BenchmarkTypes.SimpleRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
@State(Scope.Thread)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class SliceCodecBenchmark {

    private SliceCodec codec;
    private Fory fory;

    private SimpleRecord simpleRecord;
    private ComplexRecord complexSmall;
    private ComplexRecord complexLarge;
    private MixedRecord mixedRecord;

    // Pre-serialized payloads for deserialization benchmarks
    private byte[] simpleRecordSliceBytes;
    private byte[] complexSmallSliceBytes;
    private byte[] complexLargeSliceBytes;
    private byte[] mixedRecordSliceBytes;

    private byte[] simpleRecordForyBytes;
    private byte[] complexSmallForyBytes;
    private byte[] complexLargeForyBytes;
    private byte[] mixedRecordForyBytes;

    @Setup(Level.Trial)
    public void setup() {
        // --- SliceCodec setup ---
        codec = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), BenchmarkTypes.ALL_CODECS);

        // --- Fory setup ---
        fory = Fory.builder()
                   .withLanguage(Language.JAVA)
                   .requireClassRegistration(true)
                   .build();
        fory.register(SimpleRecord.class);
        fory.register(ComplexRecord.class);
        fory.register(BenchmarkEnum.class);
        fory.register(MixedRecord.class);

        // --- Test data ---
        simpleRecord = new SimpleRecord(42L, "benchmark-record");

        complexSmall = new ComplexRecord("batch-001",
            List.of(new SimpleRecord(1L, "item-1")), System.currentTimeMillis());

        var largeItems = new ArrayList<SimpleRecord>(100);
        for (int i = 0; i < 100; i++) {
            largeItems.add(new SimpleRecord(i, "item-" + i));
        }
        complexLarge = new ComplexRecord("batch-large", List.copyOf(largeItems), System.currentTimeMillis());

        mixedRecord = new MixedRecord(
            new SimpleRecord(7L, "source-node"), BenchmarkEnum.C, 256, "payload-data");

        // --- Pre-serialize for deserialization benchmarks ---
        simpleRecordSliceBytes = codec.encode(simpleRecord);
        complexSmallSliceBytes = codec.encode(complexSmall);
        complexLargeSliceBytes = codec.encode(complexLarge);
        mixedRecordSliceBytes = codec.encode(mixedRecord);

        simpleRecordForyBytes = fory.serialize(simpleRecord);
        complexSmallForyBytes = fory.serialize(complexSmall);
        complexLargeForyBytes = fory.serialize(complexLarge);
        mixedRecordForyBytes = fory.serialize(mixedRecord);

        // --- Print payload sizes ---
        System.out.printf("%n=== Payload sizes (bytes) ===%n");
        System.out.printf("%-20s SliceCodec: %4d  Fory: %4d%n",
            "SimpleRecord", simpleRecordSliceBytes.length, simpleRecordForyBytes.length);
        System.out.printf("%-20s SliceCodec: %4d  Fory: %4d%n",
            "ComplexSmall", complexSmallSliceBytes.length, complexSmallForyBytes.length);
        System.out.printf("%-20s SliceCodec: %4d  Fory: %4d%n",
            "ComplexLarge", complexLargeSliceBytes.length, complexLargeForyBytes.length);
        System.out.printf("%-20s SliceCodec: %4d  Fory: %4d%n",
            "MixedRecord", mixedRecordSliceBytes.length, mixedRecordForyBytes.length);
    }

    // =====================================================
    // Serialization benchmarks
    // =====================================================

    @Benchmark
    public byte[] simpleRecord_serialize_sliceCodec() {
        return codec.encode(simpleRecord);
    }

    @Benchmark
    public byte[] simpleRecord_serialize_fory() {
        return fory.serialize(simpleRecord);
    }

    @Benchmark
    public byte[] complexSmall_serialize_sliceCodec() {
        return codec.encode(complexSmall);
    }

    @Benchmark
    public byte[] complexSmall_serialize_fory() {
        return fory.serialize(complexSmall);
    }

    @Benchmark
    public byte[] complexLarge_serialize_sliceCodec() {
        return codec.encode(complexLarge);
    }

    @Benchmark
    public byte[] complexLarge_serialize_fory() {
        return fory.serialize(complexLarge);
    }

    @Benchmark
    public byte[] mixedRecord_serialize_sliceCodec() {
        return codec.encode(mixedRecord);
    }

    @Benchmark
    public byte[] mixedRecord_serialize_fory() {
        return fory.serialize(mixedRecord);
    }

    // =====================================================
    // Deserialization benchmarks
    // =====================================================

    @Benchmark
    public SimpleRecord simpleRecord_deserialize_sliceCodec() {
        return codec.decode(simpleRecordSliceBytes);
    }

    @Benchmark
    public SimpleRecord simpleRecord_deserialize_fory() {
        return (SimpleRecord) fory.deserialize(simpleRecordForyBytes);
    }

    @Benchmark
    public ComplexRecord complexSmall_deserialize_sliceCodec() {
        return codec.decode(complexSmallSliceBytes);
    }

    @Benchmark
    public ComplexRecord complexSmall_deserialize_fory() {
        return (ComplexRecord) fory.deserialize(complexSmallForyBytes);
    }

    @Benchmark
    public ComplexRecord complexLarge_deserialize_sliceCodec() {
        return codec.decode(complexLargeSliceBytes);
    }

    @Benchmark
    public ComplexRecord complexLarge_deserialize_fory() {
        return (ComplexRecord) fory.deserialize(complexLargeForyBytes);
    }

    @Benchmark
    public MixedRecord mixedRecord_deserialize_sliceCodec() {
        return codec.decode(mixedRecordSliceBytes);
    }

    @Benchmark
    public MixedRecord mixedRecord_deserialize_fory() {
        return (MixedRecord) fory.deserialize(mixedRecordForyBytes);
    }

    // =====================================================
    // Round-trip benchmarks
    // =====================================================

    @Benchmark
    public SimpleRecord simpleRecord_roundTrip_sliceCodec() {
        return codec.decode(codec.encode(simpleRecord));
    }

    @Benchmark
    public SimpleRecord simpleRecord_roundTrip_fory() {
        return (SimpleRecord) fory.deserialize(fory.serialize(simpleRecord));
    }

    @Benchmark
    public ComplexRecord complexLarge_roundTrip_sliceCodec() {
        return codec.decode(codec.encode(complexLarge));
    }

    @Benchmark
    public ComplexRecord complexLarge_roundTrip_fory() {
        return (ComplexRecord) fory.deserialize(fory.serialize(complexLarge));
    }
}

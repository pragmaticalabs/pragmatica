package org.pragmatica.jbct.parser.benchmark;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.pragmatica.jbct.parser.Java25Parser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode({Mode.Throughput, Mode.AverageTime})
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
public class Java25ParserBenchmark {

    private static final String FIXTURE_RESOURCE = "FactoryClassGenerator.java.txt";

    private String source;

    @Setup
    public void loadSource() throws IOException {
        try (InputStream in = Java25ParserBenchmark.class.getClassLoader().getResourceAsStream(FIXTURE_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Benchmark fixture not found on classpath: " + FIXTURE_RESOURCE);
            }
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Benchmark
    public void parseCst(Blackhole bh) {
        bh.consume(new Java25Parser().parse(source));
    }

    @Benchmark
    public void parseAst(Blackhole bh) {
        bh.consume(new Java25Parser().parseAst(source));
    }

    @Benchmark
    public void parseWithDiagnostics(Blackhole bh) {
        bh.consume(new Java25Parser().parseWithDiagnostics(source));
    }
}

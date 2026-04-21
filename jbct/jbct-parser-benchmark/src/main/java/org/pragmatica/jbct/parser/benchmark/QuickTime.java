package org.pragmatica.jbct.parser.benchmark;

import org.pragmatica.jbct.parser.Java25Parser;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Timing + cache-stats harness. Args: [warmup=2] [measure=3] */
public final class QuickTime {

    public static void main(String[] args) throws Exception {
        int warmups = args.length > 0 ? Integer.parseInt(args[0]) : 2;
        int measures = args.length > 1 ? Integer.parseInt(args[1]) : 3;

        String src;
        try (InputStream in = QuickTime.class.getClassLoader().getResourceAsStream("FactoryClassGenerator.java.txt")) {
            if (in == null) throw new IllegalStateException("fixture missing");
            src = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        System.out.printf("source: %d chars, warmups=%d, measures=%d%n%n", src.length(), warmups, measures);

        // Warmup both
        for (int i = 0; i < warmups; i++) {
            new Java25Parser().parse(src);
            new Java25ParserExp().parse(src);
        }

        // Original
        long[] originalTimes = new long[measures];
        for (int i = 0; i < measures; i++) {
            long t = System.nanoTime();
            var r = new Java25Parser().parse(src);
            originalTimes[i] = System.nanoTime() - t;
            if (!r.isSuccess()) throw new IllegalStateException("parse failed");
        }

        // Exp with packrat on — capture last-iteration cache stats
        long[] expOnTimes = new long[measures];
        Java25ParserExp lastOn = null;
        for (int i = 0; i < measures; i++) {
            var p = new Java25ParserExp();
            long t = System.nanoTime();
            var r = p.parse(src);
            expOnTimes[i] = System.nanoTime() - t;
            if (!r.isSuccess()) throw new IllegalStateException("parse failed");
            lastOn = p;
        }

        // Exp with packrat off
        long[] expOffTimes = new long[measures];
        for (int i = 0; i < measures; i++) {
            var p = new Java25ParserExp();
            p.setPackratEnabled(false);
            long t = System.nanoTime();
            var r = p.parse(src);
            expOffTimes[i] = System.nanoTime() - t;
            if (!r.isSuccess()) throw new IllegalStateException("parse failed");
        }

        System.out.printf("%-40s %10s %10s %10s%n", "variant", "min(ms)", "mean(ms)", "max(ms)");
        report("Java25Parser       (HashMap, pr=on)", originalTimes);
        report("Java25ParserExp    (HashMap, pr=on)", expOnTimes);
        report("Java25ParserExp    (HashMap, pr=off)", expOffTimes);

        System.out.printf("%n  cache final size (Exp, packrat=on): %,d entries%n", lastOn.cacheSize());
        double meanOn = mean(expOnTimes) / 1e6;
        double meanOff = mean(expOffTimes) / 1e6;
        System.out.printf("  packrat speedup: %.2fx (%.0fms -> %.0fms)%n", meanOff / meanOn, meanOff, meanOn);
    }

    private static void report(String label, long[] times) {
        long min = Long.MAX_VALUE, max = 0, sum = 0;
        for (long t : times) { if (t < min) min = t; if (t > max) max = t; sum += t; }
        System.out.printf("%-40s %10.2f %10.2f %10.2f%n", label, min / 1e6, sum / (double) times.length / 1e6, max / 1e6);
    }

    private static double mean(long[] times) {
        long sum = 0;
        for (long t : times) sum += t;
        return (double) sum / times.length;
    }
}

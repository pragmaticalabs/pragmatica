package org.pragmatica.aether.forge.load.pattern;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Processes templates containing pattern placeholders like {@code ${type:args}}.
 * <p>
 * A template can contain multiple patterns intermixed with literal text.
 * Example: {@code {"sku": "${random:SKU-#####}", "qty": ${range:1-10}}}
 */
public final class TemplateProcessor {
    private static final Pattern PATTERN_REGEX = Pattern.compile("(\\$\\{[a-z]+(?::[^}]*)?})");

    private final String template;
    private final List<Segment> segments;

    /**
     * A segment is either literal text or a pattern generator.
     */
    private sealed interface Segment {
        String process();

        record Literal(String text) implements Segment {
            @Override
            public String process() {
                return text;
            }
        }

        record Generator(PatternGenerator generator) implements Segment {
            @Override
            public String process() {
                return generator.generate();
            }
        }
    }

    private TemplateProcessor(String template, List<Segment> segments) {
        this.template = template;
        this.segments = segments;
    }

    /**
     * Compiles a template string into a processor.
     * <p>
     * Parses all patterns at compile time and validates them.
     *
     * @param template the template string
     * @return Result containing the processor or an error
     */
    public static Result<TemplateProcessor> compile(String template) {
        return Option.option(template)
                     .filter(s -> !s.isEmpty())
                     .map(t -> buildSegments(t)
                                            .map(segments -> new TemplateProcessor(t, segments)))
                     .or(Result.success(new TemplateProcessor("",
                                                              List.of())));
    }

    private static Result<List<Segment>> buildSegments(String template) {
        var matcher = PATTERN_REGEX.matcher(template);
        var matches = new ArrayList<MatchInfo>();
        while (matcher.find()) {
            matches.add(new MatchInfo(matcher.start(), matcher.end(), matcher.group(1)));
        }
        return buildSegmentsFromMatches(template, matches);
    }

    private record MatchInfo(int start, int end, String pattern) {}

    private static Result<List<Segment>> buildSegmentsFromMatches(String template, List<MatchInfo> matches) {
        if (matches.isEmpty()) {
            return Result.success(template.isEmpty()
                                  ? List.of()
                                  : List.of(new Segment.Literal(template)));
        }
        var generatorResults = matches.stream()
                                      .map(m -> PatternParser.parse(m.pattern()))
                                      .toList();
        return Result.allOf(generatorResults)
                     .map(generators -> assembleSegments(template, matches, generators));
    }

    private static List<Segment> assembleSegments(String template,
                                                  List<MatchInfo> matches,
                                                  List<PatternGenerator> generators) {
        var segments = new ArrayList<Segment>();
        int lastEnd = 0;
        for (int i = 0; i < matches.size(); i++) {
            var match = matches.get(i);
            if (match.start() > lastEnd) {
                segments.add(new Segment.Literal(template.substring(lastEnd, match.start())));
            }
            segments.add(new Segment.Generator(generators.get(i)));
            lastEnd = match.end();
        }
        if (lastEnd < template.length()) {
            segments.add(new Segment.Literal(template.substring(lastEnd)));
        }
        return List.copyOf(segments);
    }

    /**
     * Processes the template, replacing all patterns with generated values.
     *
     * @return the processed string
     */
    public String process() {
        if (segments.isEmpty()) {
            return template;
        }
        var result = new StringBuilder();
        for (var segment : segments) {
            result.append(segment.process());
        }
        return result.toString();
    }

    /**
     * Returns the original template string.
     */
    public String template() {
        return template;
    }

    /**
     * Returns true if this template contains any patterns.
     */
    public boolean hasPatterns() {
        return segments.stream()
                       .anyMatch(s -> s instanceof Segment.Generator);
    }

    /**
     * Returns the number of pattern generators in this template.
     */
    public int patternCount() {
        return (int) segments.stream()
                            .filter(s -> s instanceof Segment.Generator)
                            .count();
    }

    /**
     * Resets all sequence generators in this template.
     */
    public void resetSequences() {
        for (var segment : segments) {
            if (segment instanceof Segment.Generator gen && gen.generator() instanceof SequenceGenerator seq) {
                seq.reset();
            }
        }
    }
}

package org.st4.style;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One construct the house style strikes, under the AGENTS.md rule that
 * strikes it.
 *
 * <p>A construct is a pattern over lowered prose rather than a word: a verb
 * in every form, a phrase, or a shape such as a verb negating its
 * object. Each lists samples it is in and, where a near miss is worth
 * pinning, samples it is not in; HouseStyleTest reads both back, so a
 * pattern that drifts fails there rather than in review.
 *
 * @param rule the AGENTS.md heading the construct is struck under
 * @param name what the construct is, in a few words
 * @param pattern the construct, over lowered prose
 * @param in samples the construct is in, at least one
 * @param not samples near it that the construct is not in
 */
public record Construct(String rule, String name, Pattern pattern,
        List<String> in, List<String> not) {

    /** A construct spelled as a regular expression over lowered prose. */
    public static Construct of(String rule, String name, String regex,
            String in, String not) {
        return new Construct(rule, name, Pattern.compile(regex), List.of(in),
                not.isEmpty() ? List.of() : List.of(not));
    }

    /** Where the construct is in lowered {@code prose}. */
    public List<Match> matches(String prose) {
        List<Match> out = new ArrayList<>();
        Matcher matcher = pattern.matcher(prose);
        while (matcher.find()) {
            out.add(new Match(matcher.start(), matcher.group()));
        }
        return out;
    }

    /**
     * One place a construct is in: the offset it begins at and the text
     * matched.
     */
    public record Match(int at, String text) {}
}

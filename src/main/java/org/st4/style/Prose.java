package org.st4.style;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The prose of a source file: its comments and its strings, by the marks
 * its language writes them with.
 *
 * <p>The scan tracks what it is in, so a mark inside a string is part of
 * the string, as the {@code //} of a URL is, and a quote inside a comment
 * is part of the comment. Java, Go and C# write {@code //} and
 * {@code /* *}{@code /}, and so does a {@code go.mod}; an assembler source
 * writes {@code ;}; Python, a shell script and a YAML file write
 * {@code #}, and Python's triple-quoted docstrings are read as comments
 * too. An XML file, a Maven POM or a .NET project, writes
 * {@code <!-- -->}, and the scan reads its comments alone: a quote in one
 * is an attribute's value, a path or a version rather than prose.
 *
 * <p>A string is a message, a line of help or a line a tool writes into a
 * file, and its words are prose as much as a comment's. A Java text block
 * and a Go raw string run over lines. Literals the source joins, by a
 * {@code +}, a line break or a shell's {@code \}, are one string, so a
 * message wrapped over lines is read whole. An escape reads as the
 * character it encodes, and a line break it encodes as a space. The holes
 * of an interpolated string, C#'s {@code $"..."} and Python's
 * {@code f"..."}, are code, and are blanked.
 */
public final class Prose {

    private Prose() {}

    /**
     * One comment or one string: the line it begins on, and its text
     * without the marks, over as many lines as it runs.
     */
    public record Run(int line, String text) {}

    /** The comments of {@code text}, a source file at {@code path}. */
    public static List<Run> comments(Path path, String text) {
        return scan(path, text, false);
    }

    /** The strings of {@code text}, a source file at {@code path}, each run
     *  of literals the source joins read as one. */
    public static List<Run> strings(Path path, String text) {
        return scan(path, text, true);
    }

    /** One literal: where it opens and closes in the file, the lines it
     *  begins and ends on, and its text. */
    private record Literal(int from, int to, int line, int last, String text) {}

    /** What lies between two literals the source joins: space, a
     *  {@code +}, a shell's {@code \}, and the prefix of the second. */
    private static final Pattern JOINS =
            Pattern.compile("[\\s+\\\\]*[$@rRbBfFuU]{0,2}");

    /** A hole of an interpolated string. */
    private static final Pattern HOLE = Pattern.compile("\\{[^{}\\n]*\\}");

    private static List<Run> scan(Path path, String text, boolean strings) {
        String name = path.toString();
        boolean cLike = name.endsWith(".java") || name.endsWith(".go")
                || name.endsWith(".cs") || name.endsWith("go.mod");
        boolean python = name.endsWith(".py");
        boolean java = name.endsWith(".java");
        boolean go = name.endsWith(".go");
        boolean xml = isXml(name);
        char mark = cLike ? '/' : name.endsWith(".S") ? ';' : '#';
        List<Run> comments = new ArrayList<>();
        List<Literal> literals = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        int line = 1;
        int began = 1;
        int opened = 0;
        boolean holes = false;
        // 0 code, 1 a string, 2 a line comment, 3 a block comment, 4 a
        // python docstring, 5 a java text block, 6 a go raw string, 7 an
        // xml comment
        int in = 0;
        char quote = 0;
        for (int at = 0; at < text.length(); at++) {
            char one = text.charAt(at);
            char next = at + 1 < text.length() ? text.charAt(at + 1) : 0;
            if (one == '\n') {
                line++;
            }
            switch (in) {
                case 0 -> {
                    if (xml) {
                        if (text.startsWith("<!--", at)) {
                            in = 7;
                            began = line;
                            at += 3;
                        }
                    } else if (one == '"' || one == '\'') {
                        began = line;
                        opened = at;
                        holes = interpolated(text, at, name);
                        quote = one;
                        if ((python || java && one == '"') && next == one
                                && at + 2 < text.length()
                                && text.charAt(at + 2) == one) {
                            in = python ? 4 : 5;
                            at += 2;
                        } else {
                            in = 1;
                        }
                    } else if (go && one == '`') {
                        in = 6;
                        began = line;
                        opened = at;
                    } else if (cLike && one == mark && next == '/') {
                        in = 2;
                        began = line;
                        at++;
                    } else if (cLike && one == mark && next == '*') {
                        in = 3;
                        began = line;
                        at++;
                    } else if (!cLike && one == mark) {
                        in = 2;
                        began = line;
                    }
                }
                case 1, 5 -> {
                    boolean closes = in == 1
                            ? one == quote || one == '\n'
                            : one == quote && next == quote
                                    && at + 2 < text.length()
                                    && text.charAt(at + 2) == quote;
                    if (one == '\\' && at + 1 < text.length()) {
                        at++;
                        if (next == '\n') {
                            line++;
                            run.append(in == 1 ? ' ' : '\n');
                        } else {
                            run.append(escaped(next));
                        }
                    } else if (closes) {
                        if (in == 5) {
                            at += 2;
                        }
                        literals.add(new Literal(opened, at + 1, began, line,
                                holes ? blanked(run) : run.toString()));
                        run.setLength(0);
                        in = 0;
                    } else {
                        run.append(one);
                    }
                }
                case 6 -> {
                    if (one == '`') {
                        literals.add(new Literal(opened, at + 1, began, line,
                                run.toString()));
                        run.setLength(0);
                        in = 0;
                    } else {
                        run.append(one);
                    }
                }
                case 2 -> {
                    if (one == '\n') {
                        comments.add(new Run(began, run.toString()));
                        run.setLength(0);
                        in = 0;
                    } else {
                        run.append(one);
                    }
                }
                case 3 -> {
                    if (one == '*' && next == '/') {
                        comments.add(new Run(began, run.toString()));
                        run.setLength(0);
                        in = 0;
                        at++;
                    } else {
                        run.append(one);
                    }
                }
                case 7 -> {
                    if (text.startsWith("-->", at)) {
                        comments.add(new Run(began, run.toString()));
                        run.setLength(0);
                        in = 0;
                        at += 2;
                    } else {
                        run.append(one);
                    }
                }
                default -> {
                    if (one == quote && next == quote
                            && at + 2 < text.length()
                            && text.charAt(at + 2) == quote) {
                        comments.add(new Run(began, run.toString()));
                        run.setLength(0);
                        in = 0;
                        at += 2;
                    } else {
                        run.append(one);
                    }
                }
            }
        }
        if (run.length() != 0 && (in >= 2 && in <= 4 || in == 7)) {
            comments.add(new Run(began, run.toString()));
        }
        return strings ? joined(text, literals) : comments;
    }

    /** Whether {@code name} is an XML file: a POM, a .NET project or
     *  solution, or the properties a .NET build reads. */
    static boolean isXml(String name) {
        return name.endsWith(".xml") || name.endsWith(".csproj")
                || name.endsWith(".props") || name.endsWith(".targets")
                || name.endsWith(".slnx");
    }

    /** Whether the literal whose quote is at {@code at} is interpolated:
     *  a {@code $} before it in C#, an {@code f} in Python. */
    private static boolean interpolated(String text, int at, String name) {
        int from = at;
        while (from > 0 && from > at - 2
                && "$@rRbBfFuU".indexOf(text.charAt(from - 1)) >= 0) {
            from--;
        }
        String prefix = text.substring(from, at);
        return name.endsWith(".cs") ? prefix.contains("$")
                : name.endsWith(".py") && prefix.toLowerCase().contains("f");
    }

    /** The character an escape of {@code one} encodes, a line break as a
     *  space. */
    private static char escaped(char one) {
        return switch (one) {
            case 'n', 't', 'r' -> ' ';
            default -> one;
        };
    }

    /** {@code run} with each hole blanked, a space a character. */
    private static String blanked(CharSequence run) {
        Matcher hole = HOLE.matcher(run);
        StringBuilder out = new StringBuilder(run);
        while (hole.find()) {
            for (int i = hole.start(); i < hole.end(); i++) {
                out.setCharAt(i, ' ');
            }
        }
        return out.toString();
    }

    /** The literals as runs, each run the literals the source joins. A
     *  literal that begins on a later line than the one before it ends on
     *  begins a line of the run, so a hit is reported at its line. */
    private static List<Run> joined(String text, List<Literal> literals) {
        List<Run> out = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        int first = 0;
        for (int i = 0; i < literals.size(); i++) {
            Literal literal = literals.get(i);
            Literal before = literals.get(Math.max(i - 1, 0));
            if (i > 0 && JOINS.matcher(
                    text.substring(before.to(), literal.from())).matches()) {
                run.append("\n".repeat(literal.line() - before.last()));
            } else {
                if (i > 0) {
                    out.add(new Run(first, run.toString()));
                }
                run.setLength(0);
                first = literal.line();
            }
            run.append(literal.text());
        }
        if (!literals.isEmpty()) {
            out.add(new Run(first, run.toString()));
        }
        return out;
    }
}

package org.st4.style;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The comments of a source file, by the marks its language writes them
 * with.
 *
 * <p>A mark inside a string is not a comment, so the scan tracks what it
 * stands in: a URL in a literal does not open a comment, and a struck word
 * in one is not read. Java, Go and C# write {@code //} and {@code /* *}{@code /};
 * an assembler source writes {@code ;}; Python and a shell script write
 * {@code #}, and Python's triple-quoted docstrings are read as comments too.
 * A Java text block runs from one {@code """} to the next and is a string,
 * so a {@code //} inside one does not open a comment.
 */
public final class Comments {

    private Comments() {}

    /**
     * One comment: the line it begins on, and its text without the marks,
     * over as many lines as it runs.
     */
    public record Comment(int line, String text) {}

    /** The comments of {@code text}, a source file at {@code path}. */
    public static List<Comment> of(Path path, String text) {
        String name = path.toString();
        boolean cLike = name.endsWith(".java") || name.endsWith(".go")
                || name.endsWith(".cs");
        boolean python = name.endsWith(".py");
        boolean java = name.endsWith(".java");
        char mark = cLike ? '/' : name.endsWith(".S") ? ';' : '#';
        List<Comment> out = new ArrayList<>();
        StringBuilder run = new StringBuilder();
        int line = 1;
        int began = 1;
        // 0 code, 1 a string, 2 a line comment, 3 a block comment, 4 a
        // python docstring, 5 a java text block
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
                    if (one == '"' || one == '\'') {
                        if ((python || java && one == '"') && next == one
                                && at + 2 < text.length()
                                && text.charAt(at + 2) == one) {
                            in = python ? 4 : 5;
                            quote = one;
                            began = line;
                            at += 2;
                        } else {
                            in = 1;
                            quote = one;
                        }
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
                case 1 -> {
                    if (one == '\\') {
                        at++;
                    } else if (one == quote || one == '\n') {
                        in = 0;
                    }
                }
                case 2 -> {
                    if (one == '\n') {
                        out.add(new Comment(began, run.toString()));
                        run.setLength(0);
                        in = 0;
                    } else {
                        run.append(one);
                    }
                }
                case 3 -> {
                    if (one == '*' && next == '/') {
                        out.add(new Comment(began, run.toString()));
                        run.setLength(0);
                        in = 0;
                        at++;
                    } else {
                        run.append(one);
                    }
                }
                case 4 -> {
                    if (one == quote && next == quote
                            && at + 2 < text.length()
                            && text.charAt(at + 2) == quote) {
                        out.add(new Comment(began, run.toString()));
                        run.setLength(0);
                        in = 0;
                        at += 2;
                    } else {
                        run.append(one);
                    }
                }
                default -> {
                    // a text block is a string: nothing in it is read
                    if (one == quote && next == quote
                            && at + 2 < text.length()
                            && text.charAt(at + 2) == quote) {
                        in = 0;
                        at += 2;
                    }
                }
            }
        }
        if (run.length() != 0) {
            out.add(new Comment(began, run.toString()));
        }
        return out;
    }
}

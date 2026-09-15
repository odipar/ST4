package org.st4;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The documents against the phrases struck in review.
 *
 * <p>AGENTS.md defines the rules and logs what has been struck; this reads
 * every Markdown file in the tree for the phrases among them a match can
 * find. AGENTS.md and CLAUDE.md are left out: they quote the struck phrases
 * to define them.
 *
 * <p>It reads code comments the same way, in the four languages this
 * repository writes them: Java, C#, 68000 assembly and Python. The code
 * around a comment is left unread, since a field named {@code holds} or a
 * call to {@code getState} is a name and not prose.
 */
final class HouseStyleTest {

    /** The two files that define the rules, which quote what they strike. */
    private static final List<String> DEFINES_THE_RULES =
            List.of("AGENTS.md", "CLAUDE.md");

    /** Struck in review, AGENTS.md. A phrase here is matched anywhere in a
     *  line, so a stem stands for its forms. */
    private static final List<String> STRUCK = List.of(
            "promise",
            "guarantee",
            "implies",
            "imply ",
            "can be told",
            "roles stand",
            "it ruled",
            "it measured",
            "answered",
            "carries",
            "sits in",
            "stand apart",
            "keeps its place",
            "keeps the place",
            "stands where it",
            " stands on ",
            " stand on ",
            "is the base",
            "in flight",
            "spells out",
            "spell out",
            "because it says",
            "says it",
            "says so",
            "says what to take",
            "asks of",
            "asks for",
            "set-ness",
            "takes the machine with it",
            "consume ",
            "consumes",
            "consumed",
            "consuming",
            "stand as they were",
            "understand",
            "refuse",
            "whatever",
            "whichever way",
            "where it sits",
            "stood still",
            " a tail ",
            "sliver",
            "literally",
            "smear",
            "bears it out",
            "pressure point",
            "door left open",
            "cover version",
            "smuggl",
            "catastroph",
            "—",
            "–",
            "−",
            "vendor",
            "is deliberate",
            "by design",
            "on purpose",
            "asked properly",
            "not a shrug",
            "most of the point",
            "the answer to that",
            "worth reading",
            "the ones that matter",
            "the whole point",
            "actually",
            " hold ",
            "holds",
            "holding",
            "held",
            "states",
            "stated",
            "stating",
            "gives",
            "giving",
            "given",
            " take ",
            " takes ",
            " taking ",
            " taken ",
            "nothing",
            " own ",
            " own.",
            "written down",
            "write down",
            "writes down",
            "writing down",
            "a tune down",
            "the structure down",
            "puts down",
            "belongs to no",
            "performs no ",
            "writes no ",
            "reaches no ",
            "and no other",
            "for none",
            "on no chip",
            "with no error",
            "is not defined",
            "not defined by",
            "no other step",
            "would rather");

    private static List<Path> documents() throws IOException {
        try (Stream<Path> tree = Files.walk(Path.of("."))) {
            return tree.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".md"))
                    .filter(path -> !path.toString().contains("/target/"))
                    .filter(path -> !DEFINES_THE_RULES
                            .contains(path.getFileName().toString()))
                    .sorted()
                    .toList();
        }
    }

    /** The four languages this repository writes comments in. */
    private static final List<String> SOURCES =
            List.of(".java", ".cs", ".S", ".py");

    /** Every source in the tree but this one, which quotes the struck
     *  phrases to ban them, and the built trees, which are output. */
    private static List<Path> sources() throws IOException {
        try (Stream<Path> tree = Files.walk(Path.of("."))) {
            return tree.filter(Files::isRegularFile)
                    .filter(path -> SOURCES.stream()
                            .anyMatch(one -> path.toString().endsWith(one)))
                    .filter(path -> !path.toString().contains("/target/")
                            && !path.toString().contains("/bin/")
                            && !path.toString().contains("/obj/"))
                    .filter(path -> !path.getFileName().toString()
                            .equals("HouseStyleTest.java"))
                    .sorted()
                    .toList();
        }
    }

    /** The comment text of a source, each piece with the line it opens on. */
    private static List<String[]> commentsOf(Path source) throws IOException {
        List<String> lines = Files.readAllLines(source);
        String named = source.toString();
        if (named.endsWith(".java") || named.endsWith(".cs")) {
            return braces(lines);
        }
        if (named.endsWith(".S")) {
            return opener(lines, ";");
        }
        return opener(lines, "#");
    }

    /** A comment opened by {@code //} or run between {@code /*} and its close. */
    private static List<String[]> braces(List<String> lines) {
        List<String[]> out = new ArrayList<>();
        boolean block = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String text = "";
            if (block) {
                int end = line.indexOf("*/");
                text = end >= 0 ? line.substring(0, end) : line;
                if (end >= 0) {
                    block = false;
                }
            } else {
                int open = line.indexOf("/*");
                int slash = line.indexOf("//");
                if (open >= 0 && (slash < 0 || open < slash)) {
                    int end = line.indexOf("*/", open + 2);
                    if (end >= 0) {
                        text = line.substring(open + 2, end);
                    } else {
                        text = line.substring(open + 2);
                        block = true;
                    }
                } else if (slash >= 0) {
                    text = line.substring(slash + 2);
                }
            }
            text = text.replace('*', ' ').strip();
            if (!text.isEmpty()) {
                out.add(new String[] {String.valueOf(i + 1), text});
            }
        }
        return out;
    }

    /** A comment opened by one mark and running to the end of the line. */
    private static List<String[]> opener(List<String> lines, String mark) {
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("#!")) {
                continue;
            }
            int at = line.indexOf(mark);
            if (at < 0) {
                continue;
            }
            String text = line.substring(at + mark.length()).strip();
            if (!text.isEmpty()) {
                out.add(new String[] {String.valueOf(i + 1), text});
            }
        }
        return out;
    }

    @Test
    void noCommentUsesAPhraseStruckInReview() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path source : sources()) {
            for (String[] comment : commentsOf(source)) {
                String lower = comment[1].toLowerCase();
                for (String phrase : STRUCK) {
                    if (lower.contains(phrase.toLowerCase())) {
                        found.add(source + ":" + comment[0] + " reads \""
                                + phrase + "\": " + comment[1]);
                    }
                }
            }
        }
        assertTrue(found.isEmpty(), () -> String.join("\n", found)
                + "\nAGENTS.md, Struck in review, has what to write instead.");
    }

    @Test
    void noDocumentUsesAPhraseStruckInReview() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path document : documents()) {
            List<String> lines = Files.readAllLines(document);
            for (int i = 0; i < lines.size(); i++) {
                String lower = lines.get(i).toLowerCase();
                for (String phrase : STRUCK) {
                    if (lower.contains(phrase.toLowerCase())) {
                        found.add(document + ":" + (i + 1) + " reads \""
                                + phrase + "\": " + lines.get(i).strip());
                    }
                }
            }
        }
        assertTrue(found.isEmpty(), () -> String.join("\n", found)
                + "\nAGENTS.md, Struck in review, has what to write instead.");
    }
}

package org.st4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.st4.style.Comments;
import org.st4.style.Comments.Comment;
import org.st4.style.Construct;
import org.st4.style.HouseStyle;
import org.st4.style.HouseStyle.Hit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The style check against itself, and the tree against the style check.
 *
 * <p>The first half reads STRUCK.md back: every construct is in each of its
 * samples and in none of its counter-samples, every rule is a heading of
 * AGENTS.md, and the list is parsed as its preamble describes. The
 * second half runs the check over this repository: no document and no code
 * comment has a struck construct.
 */
final class HouseStyleTest {

    private static final Path ROOT = Path.of(".");

    private static HouseStyle style() throws IOException {
        return HouseStyle.read(ROOT);
    }

    @Test
    void everyConstructIsInItsSamplesAndNotInItsCounterSamples()
            throws IOException {
        HouseStyle style = style();
        List<String> wrong = new ArrayList<>();
        for (Construct construct : style.constructs()) {
            for (String in : construct.in()) {
                if (construct.matches(style.lower(in)).isEmpty()) {
                    wrong.add(construct.name() + " is not in \"" + in + '"');
                }
            }
            for (String not : construct.not()) {
                if (!construct.matches(style.lower(not)).isEmpty()) {
                    wrong.add(construct.name() + " is in \"" + not + '"');
                }
            }
        }
        assertTrue(wrong.isEmpty(), () -> String.join("\n", wrong));
    }

    @Test
    void everyRuleIsAHeadingOfAgentsMd() throws IOException {
        Set<String> headings = new HashSet<>();
        for (String line : Files.readAllLines(ROOT.resolve("AGENTS.md"))) {
            if (line.startsWith("## ")) {
                headings.add(line.substring(3).strip());
            }
        }
        List<String> wrong = new ArrayList<>();
        for (Construct construct : style().constructs()) {
            if (!headings.contains(construct.rule())) {
                wrong.add(construct.name() + " is struck under \""
                        + construct.rule() + "\", which AGENTS.md does not"
                        + " have as a heading");
            }
        }
        assertTrue(wrong.isEmpty(), () -> String.join("\n", wrong));
    }

    @Test
    void everyConstructHasItsOwnName() throws IOException {
        Set<String> seen = new HashSet<>();
        for (Construct construct : style().constructs()) {
            assertTrue(seen.add(construct.name()),
                    "two entries are named \"" + construct.name() + '"');
        }
        assertTrue(seen.size() > 30, "the list has grown short: " + seen);
    }

    @Test
    void theListIsParsedAsItsPreambleDescribes() {
        HouseStyle style = HouseStyle.parse(List.of(
                "# Struck",
                "",
                "Prose before the entries, which is not read.",
                "",
                "names",
                "    Windows",
                "",
                "carried",
                "    /org/st4/style/",
                "",
                "own",
                "    packer.go",
                "",
                "## Programs do not intend",
                "",
                "A line of prose under a rule.",
                "",
                "wanting",
                "    \\bwant",
                "    (?:s|ed)?\\b",
                "    in: the file wants a header",
                "    not: an unwanted byte",
                "",
                "## Shape",
                "",
                "an em dash",
                "    [—]",
                "    in: a — dash"));
        assertEquals(List.of("Windows"), style.names());
        assertEquals(List.of("/org/st4/style/"), style.carried());
        assertEquals(List.of("packer.go"), style.own());
        assertEquals(2, style.constructs().size());
        Construct wanting = style.constructs().get(0);
        assertEquals("Programs do not intend", wanting.rule());
        assertEquals("wanting", wanting.name());
        assertEquals("\\bwant(?:s|ed)?\\b", wanting.pattern().pattern());
        assertEquals(List.of("the file wants a header"), wanting.in());
        assertEquals(List.of("an unwanted byte"), wanting.not());
        assertEquals("Shape", style.constructs().get(1).rule());
        assertTrue(style.isCarried(Path.of("src/main/java/org/st4/style/A.java")));
        assertTrue(!style.isCarried(Path.of("go/st4/packer.go")));
        assertTrue(!style.isCarried(Path.of("src/main/java/org/dtx/A.java")));
    }

    @Test
    void anEntryWithoutAPatternOrASampleIsRefusedWhileParsing() {
        assertThrows(IllegalArgumentException.class, () -> HouseStyle.parse(
                List.of("## Shape", "", "an em dash", "    in: a — dash")));
        assertThrows(IllegalArgumentException.class, () -> HouseStyle.parse(
                List.of("## Shape", "", "an em dash", "    [—]")));
        assertThrows(IllegalArgumentException.class, () -> HouseStyle.parse(
                List.of("other", "    x")));
    }

    @Test
    void aHitNamesTheFileTheLineTheTextAndTheRule() throws IOException {
        List<Hit> hits = style().document(Path.of("doc/a.md"),
                List.of("The first line.", "", "The ring holds a row."));
        assertEquals(1, hits.size());
        assertEquals("doc/a.md:3 has \"holds\" - The verb that says the"
                + " action, holding, the third person", hits.get(0).toString());
    }

    @Test
    void aConstructBrokenByALineWrapIsFoundAtTheLineItBeginsOn()
            throws IOException {
        List<Hit> hits = style().document(Path.of("a.md"), List.of(
                "A value the row has, which is",
                "what makes the row."));
        assertEquals(1, hits.size());
        assertEquals(1, hits.get(0).line());
        assertEquals("is what", hits.get(0).text());
        hits = style().document(Path.of("a.md"), List.of(
                "- a row whose column the reader",
                "  writes no register."));
        assertEquals("writes no", hits.get(0).text());
    }

    @Test
    void aNameSpelledLikeAWordPasses() {
        HouseStyle style = HouseStyle.parse(List.of(
                "names", "    ST4_WINDOW", "",
                "## Plain words", "", "a window", "    window",
                "    in: the window the decoder reads through"));
        assertTrue(style.document(Path.of("a.md"),
                List.of("ST4_WINDOW, the symbol.")).isEmpty());
        assertEquals(1, style.document(Path.of("a.md"),
                List.of("The window the decoder reads through.")).size());
    }

    @Test
    void aTableRowAndAFenceAreReadOnTheirOwn() throws IOException {
        List<Hit> hits = style().document(Path.of("a.md"), List.of(
                "| column | what it is |",
                "| ring | what it holds |",
                "",
                "```",
                "the ring holds a row",
                "```"));
        assertEquals(List.of(2, 5),
                hits.stream().map(Hit::line).toList());
    }

    @Test
    void aCommentIsReadAndAStringIsNot() throws IOException {
        List<Hit> hits = style().source(Path.of("A.java"),
                "class A {\n"
                        + "    String s = \"the ring holds\";\n"
                        + "    /**\n"
                        + "     * The ring, which\n"
                        + "     * holds a row.\n"
                        + "     */\n"
                        + "    int ring; // the payload states it\n"
                        + "}\n");
        assertEquals(List.of("A.java:5 has \"holds\" - The verb that says"
                + " the action, holding, the third person", "A.java:7 has"
                + " \"states\" - The verb that says the action, stating, the"
                + " third person"),
                hits.stream().map(Hit::toString).toList());
    }

    @Test
    void theCommentScannerReadsCommentsAndNotStrings() {
        // A struck phrase in a comment is a hit and one in a string is not,
        // or the check would read a URL's // as prose and a literal as a
        // sentence. Each language is tried in the marks it writes.
        record Sample(String name, String text, String prose, String hidden) {}
        for (Sample one : List.of(
                new Sample("a.java",
                        "String at = \"http://x/promise\"; // a promise here\n"
                                + "/* and a guarantee */\n",
                        "a promise here", "http"),
                new Sample("a.go",
                        "s := \"a promise\" // a guarantee here\n",
                        "a guarantee here", "promise"),
                new Sample("a.cs",
                        "var s = \"a promise\";\n/// a guarantee here\n",
                        "a guarantee here", "promise"),
                new Sample("a.S",
                        "\tmove.l  #1,d0          ; a promise here\n",
                        "a promise here", ""),
                new Sample("a.py",
                        "at = \"a promise\"  # a guarantee here\n"
                                + "\"\"\"and a docstring\"\"\"\n",
                        "a guarantee here", "promise"),
                new Sample("a.sh",
                        "echo \"a promise\"   # a guarantee here\n",
                        "a guarantee here", "promise"))) {
            StringBuilder read = new StringBuilder();
            for (Comment comment : Comments.of(Path.of(one.name()), one.text())) {
                read.append(comment.text()).append('\n');
            }
            String found = read.toString();
            assertTrue(found.contains(one.prose()),
                    one.name() + ": the scanner read \"" + found.strip()
                            + "\", without \"" + one.prose() + '"');
            if (!one.hidden().isEmpty()) {
                assertTrue(!found.contains(one.hidden()),
                        one.name() + ": the scanner read \"" + one.hidden()
                                + "\" out of a string");
            }
        }
    }

    @Test
    void theCheckReadsATreeAndSkipsWhatGivesTheRulesAndWhatIsCarried(
            @TempDir Path root) throws IOException {
        Files.copy(ROOT.resolve(HouseStyle.STRUCK),
                root.resolve(HouseStyle.STRUCK));
        Files.writeString(root.resolve("AGENTS.md"),
                "the ring holds a row, and this file is not read\n");
        Files.createDirectories(root.resolve("doc"));
        Files.writeString(root.resolve("doc/a.md"),
                "A document.\n\nThe ring holds a row.\n");
        Files.createDirectories(root.resolve("src/org/st4/style"));
        Files.writeString(root.resolve("src/org/st4/style/A.java"),
                "// a carried copy, whose ring holds a row\n");
        Files.writeString(root.resolve("a.go"),
                "// the ring holds a row\nvar s = \"the ring holds\"\n");
        Files.createDirectories(root.resolve("target"));
        Files.writeString(root.resolve("target/b.md"),
                "the ring holds a row, in build output\n");
        List<String> hits = HouseStyle.read(root).check(root).stream()
                .map(hit -> root.relativize(hit.file()) + ":" + hit.line())
                .toList();
        assertEquals(List.of("doc/a.md:3", "a.go:1"), hits);
    }

    @Test
    void noDocumentHasAStruckConstruct() throws IOException {
        HouseStyle style = style();
        List<Path> documents = HouseStyle.documents(ROOT);
        assertTrue(!documents.isEmpty(), "no document was found");
        List<String> hits = new ArrayList<>();
        for (Path document : documents) {
            for (Hit hit : style.document(document,
                    Files.readAllLines(document))) {
                hits.add(hit.toString());
            }
        }
        assertTrue(hits.isEmpty(), () -> String.join("\n", hits)
                + "\nAGENTS.md defines the rule each construct is struck under;"
                + " reword the line, or take the entry off " + HouseStyle.STRUCK
                + " in the same change.");
    }

    @Test
    void noCommentHasAStruckConstruct() throws IOException {
        HouseStyle style = style();
        List<Path> sources = style.sources(ROOT);
        assertTrue(!sources.isEmpty(), "no source was found");
        List<String> hits = new ArrayList<>();
        for (Path source : sources) {
            for (Hit hit : style.source(source, Files.readString(source))) {
                hits.add(hit.toString());
            }
        }
        assertTrue(hits.isEmpty(), () -> String.join("\n", hits)
                + "\nAGENTS.md reads a code comment against the rules a"
                + " document is read against; reword the comment, or take"
                + " the entry off " + HouseStyle.STRUCK + " in the same"
                + " change.");
    }
}
